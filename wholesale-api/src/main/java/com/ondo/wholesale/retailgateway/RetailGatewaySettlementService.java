package com.ondo.wholesale.retailgateway;

import com.ondo.wholesale.common.time.KstDays;
import com.ondo.wholesale.retailgateway.dto.RetailSettlementLedgerResponse;
import com.ondo.wholesale.retailgateway.dto.RetailSettlementSummaryResponse;
import com.ondo.wholesale.settlement.service.OverdueCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 소매 정산 조회 (MUL-129) — 소매처 하나가 보는 도매처별 미수 · 연체 · 거래 원장.
 *
 * <p>돈 기록은 전부 도매 DB 에 있어서 계산도 여기서 한다. 소매는 주문번호 · 장끼 번호만 채운다.
 *
 * <p>모든 조회에 {@code retailerId}를 조건으로 건다. 소매처별로 잘린 데이터라 한 줄만 새도 남의
 * 거래 내역이 넘어간다. 부호는 도매 저장 그대로(플러스 = 갚을 돈)가 소매 화면 기준과 같다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RetailGatewaySettlementService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** "이번 주 보낸 입금" 창 — 오늘 포함 7일. 달력 주(월~일)가 아니다(소매 화면 계약). */
    private static final int PAID_WINDOW_DAYS = 7;

    private final JdbcClient jdbc;

    /** 거래 관계가 있는 도매처 전부 — 거래 관계는 주문이 들어와야 생긴다. 빚이 큰 순. */
    public List<RetailSettlementSummaryResponse> summaries(long retailerId) {
        LocalDate today = LocalDate.now(KST);
        List<PartnerRow> partners = jdbc.sql("""
                        SELECT pt.id, w.id AS wholesaler_id, w.biz_name, pt.receivable_balance,
                               ba.bank_name, ba.account_no AS bank_account_no, ba.account_holder AS bank_account_holder,
                               (SELECT max(p.paid_at) FROM wholesale.payment p
                                 WHERE p.partner_id = pt.id AND p.voided_at IS NULL) AS last_paid_at,
                               (SELECT coalesce(sum(p.amount), 0) FROM wholesale.payment p
                                 WHERE p.partner_id = pt.id AND p.voided_at IS NULL
                                   AND p.paid_at >= :windowStart) AS paid_recent
                        FROM wholesale.partner pt
                        JOIN wholesale.wholesaler w ON w.id = pt.wholesaler_id
                        LEFT JOIN wholesale.bank_account ba ON ba.wholesaler_id = w.id AND ba.is_primary
                        WHERE pt.retailer_id = :retailerId
                        ORDER BY pt.receivable_balance DESC, w.id
                        """)
                .param("retailerId", retailerId)
                .param("windowStart", KstDays.start(today.minusDays(PAID_WINDOW_DAYS - 1)))
                .query((rs, i) -> new PartnerRow(
                        rs.getLong("id"), rs.getLong("wholesaler_id"), rs.getString("biz_name"),
                        rs.getLong("receivable_balance"),
                        rs.getString("bank_name"), rs.getString("bank_account_no"), rs.getString("bank_account_holder"),
                        rs.getObject("last_paid_at", OffsetDateTime.class), rs.getLong("paid_recent")))
                .list();
        if (partners.isEmpty()) {
            return List.of();
        }

        List<Long> partnerIds = partners.stream().map(PartnerRow::id).toList();
        Map<Long, List<OverdueCalculator.Shipment>> shipments = shipmentsByPartner(partnerIds);
        Map<Long, Map<Long, Long>> paid = paidByOrderByPartner(partnerIds);

        return partners.stream().map(p -> {
            OverdueCalculator.Overdue overdue = OverdueCalculator.of(
                    shipments.getOrDefault(p.id(), List.of()), paid.getOrDefault(p.id(), Map.of()), today);
            return new RetailSettlementSummaryResponse(
                    p.wholesalerId(), p.wholesalerName(), p.balance(),
                    new RetailSettlementSummaryResponse.Overdue(overdue.amount(), overdue.count(), overdue.maxDays()),
                    p.lastPaidAt() == null ? null : p.lastPaidAt().atZoneSameInstant(KST).toLocalDate(),
                    p.paidRecent(), p.bankName(), p.bankAccountNo(), p.bankAccountHolder());
        }).toList();
    }

    /**
     * 도매처 하나와의 거래 원장. 오래된 순 — 소매 화면이 누적 잔액을 위에서부터 이어 쌓는다.
     * 거래 관계가 없으면 빈 목록이다(남의 도매처인지 알려주지 않는다).
     */
    public List<RetailSettlementLedgerResponse> ledger(long retailerId, long wholesalerId) {
        List<Row> rows = jdbc.sql("""
                        SELECT l.id, l.entry_type, l.delta, l.occurred_at, l.payment_id,
                               o.retail_order_id, ob.statement_number, ob.shipped_at, p.method, p.amount
                        FROM wholesale.receivable_ledger l
                        JOIN wholesale.partner pt       ON pt.id = l.partner_id
                        LEFT JOIN wholesale.orders o    ON o.id = l.order_id
                        LEFT JOIN wholesale.outbound ob ON ob.id = l.outbound_id
                        LEFT JOIN wholesale.payment p   ON p.id = l.payment_id
                        WHERE pt.retailer_id = :retailerId AND pt.wholesaler_id = :wholesalerId
                          AND (l.entry_type = 'OUTBOUND'
                               OR (l.entry_type = 'PAYMENT' AND p.voided_at IS NULL))
                        ORDER BY l.occurred_at, l.id
                        """)
                .param("retailerId", retailerId)
                .param("wholesalerId", wholesalerId)
                .query((rs, i) -> new Row(
                        rs.getLong("id"), rs.getString("entry_type"), rs.getLong("delta"),
                        rs.getObject("occurred_at", OffsetDateTime.class),
                        rs.getObject("payment_id", Long.class), rs.getObject("retail_order_id", Long.class),
                        rs.getObject("statement_number", Integer.class),
                        rs.getObject("shipped_at", OffsetDateTime.class),
                        rs.getString("method"), rs.getObject("amount", Long.class)))
                .list();

        List<Long> paymentIds = rows.stream().map(Row::paymentId).filter(id -> id != null).toList();
        Map<Long, List<RetailSettlementLedgerResponse.Allocation>> allocations = allocationsByPayment(paymentIds);

        List<RetailSettlementLedgerResponse> result = new ArrayList<>();
        for (Row row : rows) {
            LocalDate date = row.occurredAt().atZoneSameInstant(KST).toLocalDate();
            if ("OUTBOUND".equals(row.entryType())) {
                result.add(new RetailSettlementLedgerResponse(row.id(), "SHIPMENT", date, row.delta(),
                        row.retailOrderId(), row.statementNumber(), row.shippedAt(), null, null, null));
            } else {
                List<RetailSettlementLedgerResponse.Allocation> lines =
                        allocations.getOrDefault(row.paymentId(), List.of());
                long allocated = lines.stream().mapToLong(RetailSettlementLedgerResponse.Allocation::amount).sum();
                result.add(new RetailSettlementLedgerResponse(row.id(), "PAYMENT", date, row.delta(),
                        null, null, null, row.method(), lines, row.paymentAmount() - allocated));
            }
        }
        return result;
    }

    private Map<Long, List<OverdueCalculator.Shipment>> shipmentsByPartner(List<Long> partnerIds) {
        Map<Long, List<OverdueCalculator.Shipment>> result = new HashMap<>();
        jdbc.sql("""
                        SELECT partner_id, id, order_id, delta, occurred_at FROM wholesale.receivable_ledger
                        WHERE partner_id IN (:ids) AND entry_type = 'OUTBOUND'
                        """)
                .param("ids", partnerIds)
                .query(rs -> {
                    result.computeIfAbsent(rs.getLong("partner_id"), k -> new ArrayList<>())
                            .add(new OverdueCalculator.Shipment(rs.getLong("order_id"),
                                    rs.getObject("occurred_at", OffsetDateTime.class)
                                            .atZoneSameInstant(KST).toLocalDate(),
                                    rs.getLong("delta"), rs.getLong("id")));
                });
        return result;
    }

    private Map<Long, Map<Long, Long>> paidByOrderByPartner(List<Long> partnerIds) {
        Map<Long, Map<Long, Long>> result = new HashMap<>();
        jdbc.sql("""
                        SELECT p.partner_id, a.order_id, sum(a.amount) AS paid
                        FROM wholesale.payment_allocation a
                        JOIN wholesale.payment p ON p.id = a.payment_id
                        WHERE p.partner_id IN (:ids) AND p.voided_at IS NULL AND a.cancelled_at IS NULL
                        GROUP BY p.partner_id, a.order_id
                        """)
                .param("ids", partnerIds)
                .query(rs -> {
                    result.computeIfAbsent(rs.getLong("partner_id"), k -> new HashMap<>())
                            .put(rs.getLong("order_id"), rs.getLong("paid"));
                });
        return result;
    }

    private Map<Long, List<RetailSettlementLedgerResponse.Allocation>> allocationsByPayment(List<Long> paymentIds) {
        Map<Long, List<RetailSettlementLedgerResponse.Allocation>> result = new HashMap<>();
        if (paymentIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                        SELECT a.payment_id, o.retail_order_id, a.amount
                        FROM wholesale.payment_allocation a
                        JOIN wholesale.orders o ON o.id = a.order_id
                        WHERE a.payment_id IN (:ids) AND a.cancelled_at IS NULL
                        ORDER BY a.id
                        """)
                .param("ids", paymentIds)
                .query(rs -> {
                    result.computeIfAbsent(rs.getLong("payment_id"), k -> new ArrayList<>())
                            .add(new RetailSettlementLedgerResponse.Allocation(
                                    rs.getObject("retail_order_id", Long.class), rs.getLong("amount")));
                });
        return result;
    }

    private record PartnerRow(long id, long wholesalerId, String wholesalerName, long balance,
                              String bankName, String bankAccountNo, String bankAccountHolder,
                              OffsetDateTime lastPaidAt, long paidRecent) {}

    private record Row(long id, String entryType, long delta, OffsetDateTime occurredAt, Long paymentId,
                       Long retailOrderId, Integer statementNumber, OffsetDateTime shippedAt,
                       String method, Long paymentAmount) {}
}
