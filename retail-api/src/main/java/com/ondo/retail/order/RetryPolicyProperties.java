package com.ondo.retail.order;

import java.time.Duration;

/**
 * 재시도 간격 설정 (MUL-140).
 *
 * <p>정책을 설정으로 갈아끼울 수 있게 둔 건 <b>같은 조건에서 셋을 비교해야</b> 해서다.
 * 코드를 고쳐가며 재면 다른 게 같이 바뀐다.
 *
 * @param policy      고정 · 지수 · 지수+지터
 * @param baseDelay   첫 재시도까지. 지수는 여기서 두 배씩 간다
 * @param maxDelay    아무리 밀려도 이보다 길게는 안 미룬다. 상한이 없으면 몇 번 실패한
 *                    뒤엔 기한 안에 다시 시도조차 못 한다
 * @param jitterRatio 지터 폭. 0.2 면 ±20%
 * @param maxAttempts 이 횟수를 넘기면 포기한다({@code EXPIRED})
 */
public record RetryPolicyProperties(
        RetryPolicy policy,
        Duration baseDelay,
        Duration maxDelay,
        double jitterRatio,
        int maxAttempts) {

    public RetryPolicyProperties {
        policy = policy != null ? policy : RetryPolicy.EXPONENTIAL_JITTER;
        baseDelay = baseDelay != null ? baseDelay : Duration.ofSeconds(1);
        maxDelay = maxDelay != null ? maxDelay : Duration.ofSeconds(60);
        jitterRatio = jitterRatio > 0 ? jitterRatio : 0.2;
        maxAttempts = maxAttempts > 0 ? maxAttempts : 10;
    }
}
