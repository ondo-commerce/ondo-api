package com.ondo.retail.order;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 접수 대기함 설정 (MUL-139 · MUL-140).
 *
 * @param maxWait      소매처를 기다리게 할 수 있는 시간. 실제 기한은 이 값과 영업일 경계 중
 *                     이른 쪽이다({@link DispatchDeadline}).
 *                     <p>길게 잡을수록 그동안 다른 도매에서 살 기회를 뺏는 셈이라, 결국 못
 *                     넣으면 손해가 커진다. 짧게 잡으면 재시도할 틈이 없어 기능이 무의미해진다.
 * @param pollInterval 워커가 대기함을 들여다보는 주기. 평소에는 {@code PENDING} 이 0 건이라
 *                     부분 인덱스만 스쳐 지나간다
 * @param batchSize    한 바퀴에 집는 최대 건수. 도매가 막 살아난 순간 밀린 것을 통째로
 *                     쏟아부으면 다시 쓰러뜨린다
 * @param claimTimeout 집어 두고 이만큼은 다른 워커가 못 건드린다. 도매 호출이 끝나기 전에
 *                     같은 줄이 다시 집히면 한 주문을 두 번 부르게 된다 — 도매 멱등 제약이
 *                     막아주긴 해도 호출 수 측정이 오염된다.
 *                     <p>도매 타임아웃(연결 2s + 응답 5s)보다 넉넉해야 한다. 호출이 끝나면
 *                     결과에 따라 진짜 다음 시각으로 다시 쓰므로, 이 값이 재시도 간격을
 *                     늘리지는 않는다. 워커가 호출 도중에 죽은 경우에만 이만큼 기다린다
 * @param retry        간격 정책
 */
@ConfigurationProperties(prefix = "ondo.order.dispatch")
public record OrderDispatchProperties(
        Duration maxWait,
        Duration pollInterval,
        int batchSize,
        Duration claimTimeout,
        RetryPolicyProperties retry) {

    public OrderDispatchProperties {
        maxWait = maxWait != null ? maxWait : Duration.ofMinutes(30);
        pollInterval = pollInterval != null ? pollInterval : Duration.ofSeconds(1);
        batchSize = batchSize > 0 ? batchSize : 50;
        claimTimeout = claimTimeout != null ? claimTimeout : Duration.ofSeconds(15);
        retry = retry != null ? retry : new RetryPolicyProperties(null, null, null, 0, 0);
    }
}
