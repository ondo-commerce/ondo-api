package com.ondo.retail.order;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 다음 시도까지 얼마나 미룰지 (MUL-140).
 *
 * <p><b>왜 정책이 여럿인가</b> — 재시도는 필요하지만 순진하게 하면 장애를 더 키운다.
 * 도매가 죽으면 밀린 주문이 한꺼번에 실패하고, 그대로 두면 같은 무리가 같은 주기로
 * 도매를 계속 때려 회복을 막는다. 셋을 같은 조건에서 재보고 고른다.
 */
public enum RetryPolicy {

    /**
     * 늘 같은 간격.
     *
     * <p>단순하지만 간격이 짧으면 아픈 도매를 쉬지 않고 두드리고, 길면 잠깐의 장애에도
     * 접수가 한참 늦는다. 무엇보다 <b>몰림을 전혀 안 줄인다</b> — 같이 실패한 건들이
     * 계속 같이 깨어난다.
     */
    FIXED {
        @Override
        Duration delay(int attempts, RetryPolicyProperties p) {
            return p.baseDelay();
        }
    },

    /**
     * 갈수록 두 배씩 미룬다. 1 → 2 → 4 → 8 …
     *
     * <p>두드리는 <b>총 횟수</b>가 줄어 도매에 숨 쉴 틈이 생긴다. 그런데 몰림은 그대로다 —
     * 같은 순간에 실패한 건들이 같은 곡선을 타므로 <b>같은 순간에 깨어난다.</b>
     * 간격만 벌어진 파도가 된다.
     */
    EXPONENTIAL {
        @Override
        Duration delay(int attempts, RetryPolicyProperties p) {
            return capped(p.baseDelay().multipliedBy(1L << Math.min(attempts, 20)), p.maxDelay());
        }
    },

    /**
     * 지수 백오프에 무작위를 섞는다.
     *
     * <p>{@link #EXPONENTIAL} 이 못 푸는 <b>동시성</b>을 푼다. 각 건의 대기 시간을
     * ±비율만큼 흩뜨리면 파도가 물결이 된다. 총 호출 수는 지수와 비슷한데 순간 최대치가
     * 낮아진다 — 막 일어난 도매가 다시 쓰러지지 않는 지점이 여기다.
     */
    EXPONENTIAL_JITTER {
        @Override
        Duration delay(int attempts, RetryPolicyProperties p) {
            Duration base = EXPONENTIAL.delay(attempts, p);
            double spread = p.jitterRatio();
            if (spread <= 0) {
                return base;
            }
            // [1-비율, 1+비율] 사이에서 고른다. 0 이하로 내려가지 않게 하한을 둔다
            double factor = 1 + ThreadLocalRandom.current().nextDouble(-spread, spread);
            long millis = Math.max(1, (long) (base.toMillis() * factor));
            return Duration.ofMillis(millis);
        }
    };

    abstract Duration delay(int attempts, RetryPolicyProperties p);

    /**
     * 다음 시도까지의 간격.
     *
     * @param attempts 지금까지 시도한 횟수. 0 이면 첫 재시도다
     */
    public Duration nextDelay(int attempts, RetryPolicyProperties properties) {
        return delay(Math.max(attempts, 0), properties);
    }

    private static Duration capped(Duration value, Duration max) {
        // 곱하다 보면 넘칠 수 있다. 음수가 되면 상한으로 본다
        return value.isNegative() || value.compareTo(max) > 0 ? max : value;
    }
}
