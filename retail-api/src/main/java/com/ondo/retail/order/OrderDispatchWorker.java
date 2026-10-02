package com.ondo.retail.order;

import com.ondo.retail.order.dto.WholesaleOrderReceipt;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 밀린 접수를 대신 보낸다 (MUL-140).
 *
 * <p><b>왜 필요한가</b> — 도매가 멈추면 주문서는 저장되지만 접수는 실패한다. 지금까지는
 * 사용자가 다시 눌러야 했는데, 실패를 본 사용자는 보통 다시 안 누르고 다른 도매를 찾는다.
 * 대기함에 줄이 쌓여도 <b>가져갈 사람이 없으면</b> 그대로 남는다. 그 사람이 여기다.
 *
 * <p><b>이 클래스에 {@code @Transactional} 이 없는 것은 일부러다.</b> 도매 호출은
 * 트랜잭션 밖에서 한다. {@code SKIP LOCKED} 로 집은 잠금은 트랜잭션이 끝나야 풀리는데,
 * 그 안에서 HTTP 를 부르면 응답을 기다리는 내내 DB 커넥션을 붙들고 있게 된다.
 * 도매가 느려지면 소매 커넥션 풀이 마르고 로그인까지 죽는다.
 *
 * <pre>
 *   claim()   집으면서 다음 시각을 잠깐 뒤로 민다      트랜잭션
 *   ...       도매 호출                              트랜잭션 밖
 *   record()  결과에 따라 진짜 다음 시각을 적는다       트랜잭션 (건별)
 * </pre>
 *
 * <p>태스크가 여럿이면 워커도 여럿이다. 서로 다른 구간을 가져가는 건
 * {@link OrderDispatchRepository#findDue} 의 {@code SKIP LOCKED} 가 맡는다.
 *
 * <p>주기 호출은 {@link OrderDispatchScheduler} 로 떼어 뒀다. 테스트에서 스케줄러가
 * 배경으로 도는 채로 {@code run()} 을 부르면 둘이 같은 줄을 두고 경합해 결과가
 * 그때그때 달라진다.
 */
@Slf4j
@Component
public class OrderDispatchWorker {

    private final OrderDispatchStore store;
    private final OrderClient orderClient;
    private final Counter sent;
    private final Counter retried;
    private final Counter givenUp;

    public OrderDispatchWorker(OrderDispatchStore store, OrderClient orderClient,
                               MeterRegistry registry) {
        this.store = store;
        this.orderClient = orderClient;
        // 정책별 도매 호출 수를 비교하려면 세어야 한다. 복구 순간에 얼마나 몰리는지가
        // 정책을 고르는 근거다 (MUL-142)
        this.sent = Counter.builder("ondo.order.dispatch")
                .tag("result", "sent").register(registry);
        this.retried = Counter.builder("ondo.order.dispatch")
                .tag("result", "retried").register(registry);
        this.givenUp = Counter.builder("ondo.order.dispatch")
                .tag("result", "given_up").register(registry);
    }

    /** 한 바퀴 돈다. 주기 호출은 {@link OrderDispatchScheduler} 가 맡는다. */
    public void run() {
        List<OrderDispatchStore.Claimed> batch;
        try {
            batch = store.claim();
        } catch (RuntimeException e) {
            // 여기서 터지면 스케줄러가 멈춘다. 다음 바퀴에 다시 해보게 두고 넘어간다
            log.error("대기함을 집는 데 실패했다", e);
            return;
        }
        if (batch.isEmpty()) {
            return;
        }

        log.info("밀린 접수를 보낸다. {} 건", batch.size());
        for (OrderDispatchStore.Claimed claimed : batch) {
            send(claimed);
        }
    }

    /**
     * 한 건을 보낸다.
     *
     * <p>굳혀 둔 명령을 그대로 쓴다 — 다시 조립하면 그 사이 바뀐 판매가가 섞인다.
     * 사용자가 승인한 그 내용으로 보내야 한다.
     */
    private void send(OrderDispatchStore.Claimed claimed) {
        try {
            WholesaleOrderReceipt receipt = orderClient.place(claimed.payload());
            boolean willRetry = store.record(claimed.id(), receipt);

            if (receipt.accepted()) {
                sent.increment();
                log.info("밀린 접수가 들어갔다. dispatchId={} 도매처={} 시도={}",
                        claimed.id(), claimed.wholesalerId(), claimed.attempts() + 1);
            } else if (willRetry) {
                retried.increment();
            } else {
                givenUp.increment();
                log.info("대기 주문을 끝낸다. dispatchId={} 사유={}", claimed.id(), receipt.reason());
            }

        } catch (RuntimeException e) {
            // 어댑터가 결과로 돌려주므로 여기 오는 건 예상 못 한 사고다. 한 건 때문에
            // 나머지가 멈추면 안 되니 삼키고 다음으로 간다. 집을 때 밀어 둔 시각이
            // 지나면 다시 잡힌다
            log.error("밀린 접수 처리 중 오류. dispatchId={}", claimed.id(), e);
        }
    }
}
