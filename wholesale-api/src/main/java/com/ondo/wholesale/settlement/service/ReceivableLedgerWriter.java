package com.ondo.wholesale.settlement.service;

import com.ondo.wholesale.settlement.domain.LedgerEntry;
import com.ondo.wholesale.settlement.domain.ReceivableEntryType;
import com.ondo.wholesale.settlement.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 미수 원장에 행을 쓰는 유일한 자리 (MUL-123) — 출고 확정 · 입금 · 입금 취소 · 조정이 전부 여기를 거친다.
 *
 * <p>한 번에 하는 일은 넷이다: 거래처 행 락 → {@code partner.receivable_balance} 읽기 →
 * 행 추가({@code balance_after}를 이어서) → {@code receivable_balance} 갱신. 거래처 행 락이
 * 같은 거래처의 쓰기를 한 줄로 세우므로 잔액이 어긋나지 않는다. 락 안에서는 이것 말고 아무것도
 * 하지 않는다 — 뒤에 선 요청이 그만큼 기다린다.
 *
 * <p>{@code receivable_balance}는 원장 마지막 {@code balance_after}를 베껴 둔 값이다. 미수 목록의
 * 정렬·페이지가 이 칸을 읽는다. 원장을 여기 말고 다른 데서 쓰면 둘이 어긋나므로 쓰는 곳을 하나로 둔다.
 *
 * <p>부르는 쪽의 트랜잭션 안에서만 돈다 — 원장만 커밋되고 원래 일이 롤백되는 일이 없게.
 *
 * <p><b>잠금이 값을 지키는 게 아니라 순서를 지킨다</b> (MUL-143). 원장은 추가 전용이라
 * 잔액 칸을 덮어쓰는 일이 없다. 문제는 두 요청이 <b>같은 잔액을 읽고 각자 이어 쓰는</b>
 * 것이다 — 두 줄의 {@code balance_after} 가 같은 값에서 출발해 장부가 틀어진다.
 * 무엇으로 순서를 세울지는 {@link LedgerWriteStrategy} 가 정하고, 셋을 같은 조건에서
 * 재보려고 설정으로 갈아끼울 수 있게 뒀다.
 */
@Component
@RequiredArgsConstructor
public class ReceivableLedgerWriter {

    private final NamedParameterJdbcTemplate jdbc;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final LedgerWriteStrategySwitch strategy;

    /**
     * 원장 한 행의 재료. 부호는 종류가 정한다 — 틀리면 DB({@code receivable_ledger_sign_ck})가 거절한다.
     */
    public record Line(ReceivableEntryType entryType, long delta, String requestId,
                       Long orderId, Long outboundId, Long paymentId, String memo) {

        /** 출고로 미수가 생긴다(+). 주문마다 한 행. */
        public static Line outbound(long orderId, long outboundId, long amount) {
            return new Line(ReceivableEntryType.OUTBOUND, amount,
                    "OUTBOUND-" + outboundId + "-" + orderId, orderId, outboundId, null, null);
        }

        /** 입금 취소로 갚을 돈이 다시 늘어난다(+). 취소한 입금을 가리킨다 (MUL-127). */
        public static Line paymentVoid(long paymentId, long amount) {
            return new Line(ReceivableEntryType.PAYMENT_VOID, amount,
                    "PAYMENT_VOID-" + paymentId, null, null, paymentId, null);
        }

        /** 입금으로 갚을 돈이 줄어든다(−). 주문을 가리키지 않는다 — 어느 주문 값인지는 배분이 적는다. */
        public static Line payment(long paymentId, long amount) {
            return new Line(ReceivableEntryType.PAYMENT, -amount,
                    "PAYMENT-" + paymentId, null, null, paymentId, null);
        }
    }

    /**
     * 같은 거래처의 행들을 순서대로 쓴다. 돌려주는 행의 마지막 {@code balanceAfter}가 새 잔액이다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<LedgerEntry> append(long partnerId, OffsetDateTime occurredAt, List<Line> lines) {
        Snapshot snapshot = readBalance(partnerId);
        long balance = snapshot.balance();
        List<LedgerEntry> written = new ArrayList<>(lines.size());
        for (Line line : lines) {
            balance += line.delta();
            written.add(ledgerEntryRepository.save(LedgerEntry.builder()
                    .partnerId(partnerId)
                    .requestId(line.requestId())
                    .entryType(line.entryType())
                    .delta(line.delta())
                    .balanceAfter(balance)
                    .orderId(line.orderId())
                    .outboundId(line.outboundId())
                    .paymentId(line.paymentId())
                    .memo(line.memo())
                    .occurredAt(occurredAt)
                    .build()));
        }
        writeBalance(partnerId, balance, snapshot.version());
        return written;
    }

    /** 잔액과 버전을 읽는다. 비관적 락이면 여기서 줄을 세운다. */
    private Snapshot readBalance(long partnerId) {
        String sql = "select receivable_balance, balance_version from wholesale.partner where id = :id"
                + (strategy.current() == LedgerWriteStrategy.PESSIMISTIC ? " for update" : "");

        return jdbc.queryForObject(sql, new MapSqlParameterSource("id", partnerId),
                (rs, rowNum) -> new Snapshot(rs.getLong("receivable_balance"),
                        rs.getLong("balance_version")));
    }

    /**
     * 요약 잔액을 갱신한다.
     *
     * <p>낙관적 락이면 읽을 때 본 버전을 조건에 넣는다. 그 사이 다른 요청이 올렸으면
     * 0 행이 바뀌고, 그때 던지는 예외가 부르는 쪽의 트랜잭션을 되돌린다 — 원장 행만
     * 남고 잔액이 안 바뀌면 둘이 어긋나므로 통째로 무르는 게 맞다.
     */
    private void writeBalance(long partnerId, long balance, long version) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("balance", balance)
                .addValue("id", partnerId)
                .addValue("version", version);

        if (strategy.current() != LedgerWriteStrategy.OPTIMISTIC) {
            jdbc.update("update wholesale.partner set receivable_balance = :balance where id = :id",
                    params);
            return;
        }

        int updated = jdbc.update("""
                update wholesale.partner
                   set receivable_balance = :balance,
                       balance_version    = balance_version + 1
                 where id = :id and balance_version = :version
                """, params);

        if (updated == 0) {
            throw new LedgerConflictException(partnerId);
        }
    }

    /** 읽은 시점의 잔액과 버전. 버전은 낙관적 락에서만 쓴다. */
    private record Snapshot(long balance, long version) {}
}
