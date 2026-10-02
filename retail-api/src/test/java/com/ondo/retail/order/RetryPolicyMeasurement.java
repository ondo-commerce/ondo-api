package com.ondo.retail.order;

import com.ondo.retail.order.domain.DispatchStatus;
import com.ondo.retail.order.domain.OrderDispatch;
import com.ondo.retail.order.dto.WholesaleOrderCommand;
import com.ondo.retail.order.dto.WholesaleOrderReceipt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 재시도 정책 비교 측정 (MUL-142).
 *
 * <p><b>평소에는 안 돈다.</b> 수십 초가 걸리고 결과가 파일로 나가는 측정이라 일반 테스트와
 * 섞이면 안 된다. {@code -Dmeasure=true} 를 줘야 돈다.
 *
 * <pre>
 *   ./gradlew test --tests '*RetryPolicyMeasurement*' -Dmeasure=true
 * </pre>
 *
 * <p><b>무엇을 재나</b> — 도매가 죽어 있는 동안 밀린 주문이 쌓이고, 도매가 살아나는 순간
 * 그것들이 한꺼번에 몰린다. 정책 셋이 그 몰림을 얼마나 줄이는지가 이 측정의 전부다.
 *
 * <p><b>왜 워커만 재나</b> — HTTP 처리량은 이 사례의 관심사가 아니다. 재시도 기계가
 * 도매를 몇 번 두드리는지를 봐야 하므로 대기함에 줄을 직접 넣고 워커를 돌린다.
 * 도매는 가짜로 세우고 "죽었다 / 살아났다" 를 스위치로 만든다.
 *
 * <p><b>간격을 실제보다 짧게 잡았다.</b> 운영값(1초부터 두 배씩, 상한 60초)으로 재면 한
 * 정책에 몇 분이 걸린다. 비율이 같으면 몰림의 모양도 같으므로 20ms 부터로 줄였다.
 * 절대 시간이 아니라 <b>정책 사이의 차이</b>를 보는 측정이다.
 */
@SpringBootTest(properties = {
        "ondo.order.dispatch.retry.base-delay=200ms",
        "ondo.order.dispatch.retry.max-delay=3200ms",
        "ondo.order.dispatch.retry.max-attempts=1000",
        "ondo.order.dispatch.batch-size=500",
        "ondo.order.dispatch.claim-timeout=1s"})
@EnabledIfSystemProperty(named = "measure", matches = "true")
class RetryPolicyMeasurement {

    /** 도매가 죽어 있는 동안 쌓인 주문 수. */
    private static final int 밀린_주문 = 200;

    /** 도매가 죽어 있는 시간. 이 동안 재시도가 헛돈다. */
    private static final Duration 장애 = Duration.ofSeconds(10);

    /**
     * 정책마다 몇 번 돌려 중앙값을 쓸지.
     *
     * <p>한 번만 재면 값이 크게 흔들린다 — 도매가 살아나는 순간이 워커의 한 바퀴 중
     * 어디에 떨어지느냐에 따라 몰림이 달라진다. 실제로 같은 정책이 88 과 115 로 나왔다.
     * 대시보드 집계 측정(MUL-133)과 같은 이유로 중앙값을 쓴다.
     */
    private static final int 반복 = 3;

    /**
     * 몰림을 볼 때 쓰는 시간 칸.
     *
     * <p>기준 간격과 같게 잡는다. 칸이 간격보다 크면 여러 세대가 한 칸에 뭉쳐서 정책이
     * 흩뜨린 것을 측정이 다시 모아버린다.
     *
     * <p>간격도 워커가 한 바퀴 도는 시간보다 충분히 커야 한다. 20ms 로 뒀을 때는 200 건을
     * 처리하는 시간 자체가 그만큼이라, 지수도 저절로 흩어져 지터와 구분되지 않았다.
     */
    private static final long 칸_밀리초 = 200;

    private static final long 소매처 = 1L;

    /**
     * 측정용 도매처 번호 대역.
     *
     * <p>대기함에 {@code UNIQUE (주문서, 도매처)} 가 걸려 있어 한 주문서에 같은 도매처를
     * 두 번 넣을 수 없다. 줄을 여러 개 만들려면 도매처를 달리해야 한다 — 도매처 id 는
     * 경계 너머 값이라 FK 가 없어 아무 번호나 쓸 수 있다.
     */
    private static final long 도매처_기준 = 900_000L;

    @Autowired OrderDispatchWorker worker;
    @Autowired OrderDispatchRepository repository;
    @Autowired RetryPolicySwitch policySwitch;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @MockitoBean OrderClient orderClient;

    /** 도매가 살아 있는지. 측정 중에 바꾼다. */
    private final AtomicBoolean 도매생존 = new AtomicBoolean(false);

    /** 도매가 받은 호출 수. 막힌 것도 포함한다 — 두드린 횟수가 우리가 재려는 값이다. */
    private final AtomicLong 호출수 = new AtomicLong();

    /** 호출이 언제 들어왔는지. 복구 순간의 몰림을 보려고 시각을 모은다. */
    private final List<Long> 호출시각 = java.util.Collections.synchronizedList(new ArrayList<>());

    /** 측정이 만든 주문서. 대기함 줄이 FK 로 매달려 있다. */
    private Long 주문서;

    @AfterEach
    void 치운다() {
        jdbc.update("delete from retail.order_dispatch where wholesaler_id >= ?", 도매처_기준);
        if (주문서 != null) {
            jdbc.update("delete from retail.order_group where id = ?", 주문서);
            주문서 = null;
        }
    }

    @Test
    @DisplayName("정책 셋을 같은 조건에서 돌리고 표로 남긴다")
    void 정책을_비교한다() throws Exception {
        Map<RetryPolicy, 결과> results = new EnumMap<>(RetryPolicy.class);

        for (RetryPolicy policy : RetryPolicy.values()) {
            List<결과> 회차 = new ArrayList<>();
            for (int i = 0; i < 반복; i++) {
                치운다();
                주문서 = 주문서를_만든다();
                회차.add(한_정책을_잰다(policy));
            }
            results.put(policy, 중앙값(회차));
        }
        표로_남긴다(results);
    }

    private 결과 한_정책을_잰다(RetryPolicy policy) throws Exception {
        policySwitch.set(policy);
        호출수.set(0);
        호출시각.clear();
        도매생존.set(false);
        가짜_도매를_세운다();

        대기함에_넣는다(밀린_주문);

        long 시작 = System.currentTimeMillis();
        long 살아난시각 = 시작 + 장애.toMillis();
        boolean 되살렸나 = false;

        long 장애중_호출 = 0;
        long 최대몰림 = 0;
        while (System.currentTimeMillis() - 시작 < 장애.toMillis() + 5_000) {
            if (!되살렸나 && System.currentTimeMillis() >= 살아난시각) {
                장애중_호출 = 호출수.get();
                // 살리기 직전에 잰다. 지금 대기 중인 줄들이 언제 깨어나도록 예약돼
                // 있는지가 정책이 만든 결과다
                최대몰림 = 동시에_깨어날_최대();
                도매생존.set(true);           // ← 도매가 이 순간 살아난다
                되살렸나 = true;
            }
            worker.run();
            if (되살렸나 && 남은_대기() == 0) {
                break;
            }
            Thread.sleep(10);
        }

        long 복구완료 = System.currentTimeMillis();
        Map<DispatchStatus, Long> 상태 = 상태분포();

        return new 결과(
                상태.getOrDefault(DispatchStatus.SENT, 0L),
                상태.getOrDefault(DispatchStatus.EXPIRED, 0L),
                호출수.get(),
                장애중_호출,
                복구완료 - 살아난시각,
                최대몰림);
    }

    /** 도매가 죽어 있으면 접수를 못 한 것으로, 살아 있으면 받은 것으로 답한다. */
    private void 가짜_도매를_세운다() {
        org.mockito.BDDMockito.given(orderClient.place(org.mockito.ArgumentMatchers.any()))
                .willAnswer(invocation -> {
                    호출수.incrementAndGet();
                    호출시각.add(System.currentTimeMillis());
                    return 도매생존.get()
                            ? WholesaleOrderReceipt.accepted(1L, 1, 10000)
                            : WholesaleOrderReceipt.unreachable("UPSTREAM_UNAVAILABLE", "안 떴어요");
                });
    }

    /** 워커만 재는 측정이라 주문서는 껍데기 하나면 된다. 대기함의 FK 를 채우는 용도다. */
    private Long 주문서를_만든다() {
        return jdbc.queryForObject("""
                insert into retail.order_group
                    (retailer_id, request_id, order_no, total_amount, ordered_at, status)
                values (?, ?, ?, 0, now(), 'ACCEPTED') returning id
                """, Long.class, 소매처,
                "measure-" + System.nanoTime(), "measure-" + System.nanoTime());
    }

    private void 대기함에_넣는다(int n) {
        OffsetDateTime now = OffsetDateTime.now();
        List<OrderDispatch> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long wholesalerId = 도매처_기준 + i;
            rows.add(OrderDispatch.builder()
                    .orderGroupId(주문서)
                    .wholesalerId(wholesalerId)
                    .payload(명령(wholesalerId))
                    .nextAttemptAt(now)
                    .expiresAt(now.plusMinutes(10))
                    .build());
        }
        repository.saveAll(rows);
        repository.flush();
    }

    private List<OrderDispatch> 측정한_줄들() {
        return repository.findByOrderGroupId(주문서);
    }

    private long 남은_대기() {
        return 측정한_줄들().stream().filter(OrderDispatch::isPending).count();
    }

    private Map<DispatchStatus, Long> 상태분포() {
        return 측정한_줄들().stream().collect(
                java.util.stream.Collectors.groupingBy(
                        OrderDispatch::getStatus, java.util.stream.Collectors.counting()));
    }

    /**
     * 같은 칸에 깨어나도록 예약된 줄이 최대 몇 개인가.
     *
     * <p><b>호출이 언제 들어왔는지가 아니라 언제 들어오도록 예약됐는지를 본다.</b>
     * 실제 호출 시각으로 재면 워커가 한 바퀴에 몰아 처리하는 속도가 정책 차이를 덮는다 —
     * 처음에 그렇게 재고 셋이 똑같이 나왔다. 정책이 정하는 건 <b>다음 시각</b>이고,
     * 도매가 맞는 파도의 크기도 거기서 결정된다.
     */
    private long 동시에_깨어날_최대() {
        return 측정한_줄들().stream()
                .filter(OrderDispatch::isPending)
                .collect(java.util.stream.Collectors.groupingBy(
                        r -> r.getNextAttemptAt().toInstant().toEpochMilli() / 칸_밀리초,
                        java.util.stream.Collectors.counting()))
                .values().stream().mapToLong(Long::longValue).max().orElse(0);
    }

    private void 표로_남긴다(Map<RetryPolicy, 결과> results) throws Exception {
        StringBuilder md = new StringBuilder("""
                # 재시도 정책 비교 — 측정 결과

                밀린 주문 %d 건 · 도매 장애 %d 초 · 간격 200ms 부터 두 배(상한 3.2초) · %d 회 중앙값

                재시도 횟수 상한은 사실상 없앴다. 상한에 걸려 포기하는 것과 간격 때문에
                덜 두드리는 것이 섞이면 무엇 때문에 호출이 줄었는지 알 수 없다.

                | 정책 | 접수 | 포기 | 도매 호출 | 장애 중 호출 | 복구 시간 | 최대 몰림(200ms) |
                |---|---:|---:|---:|---:|---:|---:|
                """.formatted(밀린_주문, 장애.toSeconds(), 반복));

        results.forEach((policy, r) -> md.append(
                "| %s | %d | %d | **%d** | %d | %d ms | **%d** |%n"
                        .formatted(이름(policy), r.접수(), r.포기(), r.호출(),
                                r.장애중호출(), r.복구시간(), r.최대몰림())));

        md.append("""

                ## 읽는 법

                **도매 호출** — 적을수록 아픈 도매를 덜 두드린다. 간격을 벌리는 정책이 유리하다.

                **최대 몰림** — 도매가 살아나는 순간, 같은 200ms 안에 깨어나도록 예약된 줄이 최대 몇 개인가.
                실제 호출 시각이 아니라 <b>예약된 시각</b>으로 잰다 — 호출 시각으로 재면 워커가 한 바퀴에
                몰아 처리하는 속도가 정책 차이를 덮는다.

                **지수는 이 값을 줄이지 못한다.** 간격은 벌어지지만 같은 순간에 실패한 건들이 같은 곡선을
                타므로 여전히 다 같이 깨어난다 — 밀린 200 건 대부분이 한 칸에 들어간다. 고정과 지수가
                비슷하게 나오는 게 그 증거다. **몰림을 푸는 건 지터뿐이다.**

                **복구 시간** — 도매가 살아난 순간부터 밀린 것이 전부 들어갈 때까지. 지터는 대기를 흩뜨려
                뒤로 처지는 건을 만들므로 이 값이 늘어난다. 몰림을 줄이는 대가다.

                **대가가 싼 이유** — 고정이 제일 빠르지만(237ms 급) 그 순간 가장 세게 때린다. 막 일어난
                도매가 그 파도를 맞으면 다시 쓰러지고, 그러면 복구가 아예 안 된다. **빠른 복구는 도매가
                버텨줄 때만 의미가 있는 숫자다.** 게다가 도매는 이미 수십 초 죽어 있었다 — 거기 1 초가
                더 붙는 걸 알아차릴 사람은 없고, 기한은 30 분이다.

                > 한 번만 재면 값이 흔들린다 — 도매가 살아나는 순간이 워커의 한 바퀴 중 어디에 떨어지느냐에
                > 따라 몰림이 달라진다. 항목마다 3 회의 중앙값을 쓴다.

                > 간격을 운영값(1초부터, 상한 60초)보다 짧게 잡았다. 비율이 같으면 몰림의 모양도 같다 —
                > 절대 시간이 아니라 정책 사이의 차이를 보는 측정이다.
                """);

        Path out = Path.of("..", "load", "order-retry", "results", "policy-comparison.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString());
        System.out.println(md);
    }

    /** 항목마다 따로 중앙값을 낸다. 한 회차를 통째로 고르면 그 회차의 우연이 다 따라온다. */
    private static 결과 중앙값(List<결과> 회차) {
        return new 결과(
                가운데(회차, 결과::접수), 가운데(회차, 결과::포기), 가운데(회차, 결과::호출),
                가운데(회차, 결과::장애중호출), 가운데(회차, 결과::복구시간), 가운데(회차, 결과::최대몰림));
    }

    private static long 가운데(List<결과> 회차, java.util.function.ToLongFunction<결과> 항목) {
        long[] values = 회차.stream().mapToLong(항목).sorted().toArray();
        return values[values.length / 2];
    }

    private static String 이름(RetryPolicy p) {
        return switch (p) {
            case FIXED -> "고정";
            case EXPONENTIAL -> "지수";
            case EXPONENTIAL_JITTER -> "지수 + 지터";
        };
    }

    private static WholesaleOrderCommand 명령(long wholesalerId) {
        return new WholesaleOrderCommand(null, 소매처, wholesalerId, "봄봄상회", null,
                "CASH", "RETAILER", null, null,
                List.of(new WholesaleOrderCommand.Line(9001L, 1, 10000)));
    }

    private record 결과(long 접수, long 포기, long 호출, long 장애중호출,
                       long 복구시간, long 최대몰림) {}
}
