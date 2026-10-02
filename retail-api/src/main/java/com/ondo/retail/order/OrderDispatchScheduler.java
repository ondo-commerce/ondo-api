package com.ondo.retail.order;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 워커를 주기적으로 깨운다 (MUL-140).
 *
 * <p>깨우는 일만 한다. 워커 본체와 떼어 둔 이유가 둘이다.
 *
 * <p>첫째, <b>테스트</b>. 스케줄러가 배경으로 도는 채로 테스트가 {@code run()} 을 부르면
 * 둘이 같은 줄을 두고 경합한다. 잡는 쪽이 그때그때 달라져 결과가 흔들린다.
 * 꺼 두고 테스트가 직접 부르면 무엇이 언제 도는지가 분명해진다.
 *
 * <p>둘째, 나중에 <b>워커만 도는 태스크</b>를 따로 띄우고 싶어질 수 있다. API 를 받는
 * 태스크와 밀린 것을 보내는 태스크를 나누면 부하가 서로 안 섞인다. 그때 이 빈만
 * 켜고 끄면 된다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ondo.order.dispatch.scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class OrderDispatchScheduler {

    private final OrderDispatchWorker worker;

    @Scheduled(fixedDelayString = "${ondo.order.dispatch.poll-interval:1s}")
    public void tick() {
        worker.run();
    }
}
