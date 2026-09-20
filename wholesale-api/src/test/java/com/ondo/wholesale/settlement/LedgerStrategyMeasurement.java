package com.ondo.wholesale.settlement;

import com.ondo.wholesale.settlement.domain.ReceivableEntryType;
import com.ondo.wholesale.settlement.service.LedgerConflictException;
import com.ondo.wholesale.settlement.service.LedgerWriteStrategy;
import com.ondo.wholesale.settlement.service.LedgerWriteStrategySwitch;
import com.ondo.wholesale.settlement.service.ReceivableLedgerWriter;
import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.OrderFixture;
import com.ondo.wholesale.support.OutboundFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 미수 원장 쓰기 전략 비교 측정 (MUL-143).
 *
 * <p><b>평소에는 안 돈다.</b> 스레드를 여럿 띄우고 결과를 파일로 내보내는 측정이라 일반
 * 테스트와 섞이면 안 된다. {@code -Dmeasure=true} 를 줘야 돈다.
 *
 * <pre>
 *   ./gradlew test --tests '*LedgerStrategyMeasurement*' -Dmeasure=true
 * </pre>
 *
 * <p><b>무엇을 재나</b> — 같은 거래처에 정산 쓰기가 동시에 몰릴 때 장부가 틀어지는지,
 * 그리고 그걸 막는 값이 얼마인지.
 *
 * <p><b>왜 잔액이 아니라 {@code balance_after} 중복을 보나</b> — 원장은 추가 전용이라
 * 잔액 칸을 덮어쓰는 일이 없다. 진짜 증상은 <b>두 요청이 같은 잔액을 읽고 각자 이어 쓰는</b>
 * 것이고, 그러면 두 줄의 {@code balance_after} 가 같은 값에서 출발한다. 모든 행이 같은
 * 금액(+1,000)이므로 정상이면 값이 하나씩 다르다 — <b>중복 하나가 틀어진 순서 하나</b>다.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "measure", matches = "true")
class LedgerStrategyMeasurement extends PostgresTestSupport {

    /** 동시에 정산을 넣는 세션 수. */
    private static final int 동시_세션 = 16;

    /** 세션마다 넣는 건수. */
    private static final int 세션당_건수 = 40;

    /** 한 건의 금액. 전부 같아야 balance_after 중복이 곧 순서 오류가 된다. */
    private static final long 금액 = 1_000;

    /** 낙관적 락이 충돌했을 때 다시 해보는 한도. 넘으면 실패로 센다. */
    private static final int 재시도_한도 = 50;

    @Autowired ReceivableLedgerWriter writer;
    @Autowired LedgerWriteStrategySwitch strategySwitch;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;

    private final AtomicInteger 일련번호 = new AtomicInteger();

    /** 예상 못 한 예외. 스레드 안에서 터지면 조용히 사라지므로 붙잡아 둔다. */
    private final java.util.concurrent.atomic.AtomicReference<Throwable> 사고 =
            new java.util.concurrent.atomic.AtomicReference<>();

    @Test
    @DisplayName("전략 셋을 같은 조건에서 돌리고 표로 남긴다")
    void 전략을_비교한다() throws Exception {
        Map<LedgerWriteStrategy, 결과> results = new EnumMap<>(LedgerWriteStrategy.class);

        for (LedgerWriteStrategy strategy : LedgerWriteStrategy.values()) {
            results.put(strategy, 한_전략을_잰다(strategy));
        }
        표로_남긴다(results);
    }

    private 결과 한_전략을_잰다(LedgerWriteStrategy strategy) throws Exception {
        strategySwitch.set(strategy);

        long 도매처 = MasterDataFixture.도매처를_넣는다(
                jdbc, "ledger-" + strategy.name().toLowerCase() + "@ondo.test", "95" + (10000000 + 일련번호.incrementAndGet()));
        long 거래처 = OrderFixture.거래처를_넣는다(jdbc, 도매처, 700L + 일련번호.get(), "측정 소매");
        long 주문 = OrderFixture.주문을_넣는다(jdbc, 도매처, 거래처, 1, "CONFIRMED", OffsetDateTime.now());
        // OUTBOUND 줄은 어느 출고의 어느 주문인지를 가리켜야 한다 (V12 target_ck)
        long 봉투 = OutboundFixture.출고를_넣는다(jdbc, 도매처, 거래처, 1);

        TransactionTemplate tx = new TransactionTemplate(txManager);
        AtomicLong 충돌 = new AtomicLong();
        AtomicLong 실패 = new AtomicLong();

        ExecutorService pool = Executors.newFixedThreadPool(동시_세션);
        CountDownLatch 출발 = new CountDownLatch(1);
        CountDownLatch 완료 = new CountDownLatch(동시_세션);

        for (int s = 0; s < 동시_세션; s++) {
            pool.submit(() -> {
                try {
                    출발.await();
                    for (int i = 0; i < 세션당_건수; i++) {
                        한_건을_넣는다(tx, 거래처, 주문, 봉투, 충돌, 실패);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    사고.compareAndSet(null, t);
                } finally {
                    완료.countDown();
                }
            });
        }

        long 시작 = System.currentTimeMillis();
        출발.countDown();
        완료.await(5, TimeUnit.MINUTES);
        long 걸린시간 = System.currentTimeMillis() - 시작;
        pool.shutdownNow();

        // 스레드 안에서 터진 건 밖으로 안 나온다. 조용히 0 건으로 끝나면 측정이 아니라 사고다
        Throwable t = 사고.getAndSet(null);
        if (t != null) {
            throw new IllegalStateException("측정 중 예상 못 한 예외. 전략=" + strategy, t);
        }

        return 검산(거래처, 걸린시간, 충돌.get(), 실패.get());
    }

    /**
     * 한 건을 넣는다. 낙관적 락은 충돌하면 트랜잭션을 되돌리고 다시 한다.
     *
     * <p>원장 행만 남고 잔액이 안 바뀌면 둘이 어긋나므로 통째로 무르는 게 맞다.
     */
    private void 한_건을_넣는다(TransactionTemplate tx, long 거래처, long 주문, long 봉투,
                            AtomicLong 충돌, AtomicLong 실패) {
        for (int attempt = 0; attempt < 재시도_한도; attempt++) {
            String requestId = "MEASURE-" + 일련번호.incrementAndGet();
            try {
                tx.executeWithoutResult(status -> writer.append(거래처, OffsetDateTime.now(),
                        List.of(new ReceivableLedgerWriter.Line(
                                ReceivableEntryType.OUTBOUND, 금액, requestId, 주문, 봉투, null, null))));
                return;
            } catch (LedgerConflictException e) {
                충돌.incrementAndGet();
            }
        }
        실패.incrementAndGet();
    }

    /**
     * 장부가 맞는지 본다.
     *
     * <p>세 가지를 본다 — 요약 잔액이 맞는지, 원장 마지막 잔액이 맞는지, 그리고
     * {@code balance_after} 에 중복이 있는지. 앞 둘이 맞아도 중복이 있으면 중간 과정이
     * 틀어진 것이다.
     */
    private 결과 검산(long 거래처, long 걸린시간, long 충돌, long 실패) {
        long 기대 = (long) 동시_세션 * 세션당_건수 * 금액;

        Long 요약잔액 = jdbc.queryForObject(
                "select receivable_balance from wholesale.partner where id = ?", Long.class, 거래처);
        Long 원장합계 = jdbc.queryForObject(
                "select coalesce(sum(delta), 0) from wholesale.receivable_ledger where partner_id = ?",
                Long.class, 거래처);
        Long 줄수 = jdbc.queryForObject(
                "select count(*) from wholesale.receivable_ledger where partner_id = ?",
                Long.class, 거래처);

        // 같은 잔액에서 출발한 줄이 몇 개인가 = 순서가 틀어진 횟수
        Long 중복 = jdbc.queryForObject("""
                select coalesce(sum(cnt - 1), 0) from (
                    select count(*) as cnt from wholesale.receivable_ledger
                     where partner_id = ? group by balance_after having count(*) > 1) d
                """, Long.class, 거래처);

        return new 결과(줄수, 기대, 요약잔액, 원장합계, 중복, 충돌, 실패, 걸린시간);
    }

    private void 표로_남긴다(Map<LedgerWriteStrategy, 결과> results) throws Exception {
        StringBuilder md = new StringBuilder("""
                # 미수 원장 쓰기 전략 비교 — 측정 결과

                같은 거래처에 동시 %d 세션 × %d 건 = %d 건을 넣는다. 전부 같은 금액(+%,d)이라
                정상이면 `balance_after` 가 하나씩 다르다 — **중복 하나가 틀어진 순서 하나**다.

                | 전략 | 원장 줄 | 요약 잔액 | 기대 잔액 | 순서 오류 | 충돌·재시도 | 못 넣음 | 걸린 시간 |
                |---|---:|---:|---:|---:|---:|---:|---:|
                """.formatted(동시_세션, 세션당_건수, 동시_세션 * 세션당_건수, 금액));

        results.forEach((strategy, r) -> md.append(
                "| %s | %d | %,d | %,d | **%d** | %d | **%d** | %d ms |%n"
                        .formatted(이름(strategy), r.줄수(), r.요약잔액(), r.기대잔액(),
                                r.순서오류(), r.충돌(), r.실패(), r.걸린시간())));

        md.append("""

                ## 읽는 법

                **순서 오류** — 두 요청이 같은 잔액을 읽고 각자 이어 쓴 횟수. 원장은 추가 전용이라
                잔액 칸이 덮어써지지는 않는다. 그런데 출발점이 겹치면 **줄마다 적어 둔 그 시점 잔액이
                거짓말이 된다** — "왜 이 숫자인지" 를 설명하려고 만든 장부가 설명을 못 하게 된다.

                **요약 잔액 대 기대 잔액** — 미수 목록의 정렬·페이지가 요약 칸을 읽는다. 둘이 어긋나면
                화면에 틀린 금액이 뜬다.

                **충돌·재시도** — 낙관적 락에서 다른 요청이 먼저 바꿔 일을 다시 한 횟수. 비관적 락은
                기다렸다 하므로 0 이다. 기다리는 비용과 다시 하는 비용의 맞바꿈이다.

                **못 넣음** — 재시도 한도(50 회)를 넘겨 끝내 못 넣은 건수. 낙관적 락은 충돌이 잦으면
                <b>영영 못 들어가는 건이 생긴다.</b> 같은 거래처에 쓰기가 몰리는 정산에서는 이게 치명적이다 —
                출고는 했는데 미수에 안 잡힌다.

                > 잠금이 값을 지키는 게 아니라 **순서를 지킨다.** 추가 전용 원장이라 잃어버린 갱신은
                > 구조적으로 안 생기고, 남는 문제가 순서다.
                """);

        Path out = Path.of("..", "load", "settlement", "results", "strategy-comparison.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString());
        System.out.println(md);
    }

    private static String 이름(LedgerWriteStrategy s) {
        return switch (s) {
            case NONE -> "A 잠금 없음";
            case OPTIMISTIC -> "B 낙관적 락";
            case PESSIMISTIC -> "C 비관적 락 (지금)";
        };
    }

    private record 결과(long 줄수, long 기대잔액, long 요약잔액, long 원장합계,
                       long 순서오류, long 충돌, long 실패, long 걸린시간) {}
}
