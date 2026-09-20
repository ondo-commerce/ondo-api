package com.ondo.retail.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 재시도 간격 정책 (MUL-140).
 *
 * <p>핵심은 <b>지수만으로는 몰림이 안 풀린다</b>는 것이다. 간격은 벌어지지만 같은 순간에
 * 실패한 건들이 같은 곡선을 타서 같은 순간에 깨어난다. 지터가 그걸 흩뜨린다.
 */
class RetryPolicyTest {

    private final RetryPolicyProperties 기본 = new RetryPolicyProperties(
            null, Duration.ofSeconds(1), Duration.ofSeconds(60), 0.2, 10);

    @Test
    @DisplayName("고정은 몇 번째든 같은 간격이다")
    void 고정은_늘_같다() {
        assertThat(RetryPolicy.FIXED.nextDelay(1, 기본)).isEqualTo(Duration.ofSeconds(1));
        assertThat(RetryPolicy.FIXED.nextDelay(9, 기본)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("지수는 두 배씩 벌어진다")
    void 지수는_두배씩_간다() {
        assertThat(RetryPolicy.EXPONENTIAL.nextDelay(0, 기본)).isEqualTo(Duration.ofSeconds(1));
        assertThat(RetryPolicy.EXPONENTIAL.nextDelay(1, 기본)).isEqualTo(Duration.ofSeconds(2));
        assertThat(RetryPolicy.EXPONENTIAL.nextDelay(2, 기본)).isEqualTo(Duration.ofSeconds(4));
        assertThat(RetryPolicy.EXPONENTIAL.nextDelay(3, 기본)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    @DisplayName("아무리 밀려도 상한을 넘지 않는다 — 넘으면 기한 안에 다시 시도조차 못 한다")
    void 상한을_넘지_않는다() {
        assertThat(RetryPolicy.EXPONENTIAL.nextDelay(30, 기본)).isEqualTo(Duration.ofSeconds(60));
        assertThat(RetryPolicy.EXPONENTIAL_JITTER.nextDelay(30, 기본))
                .isLessThanOrEqualTo(Duration.ofSeconds(72));   // 60s + 지터 20%
    }

    @Test
    @DisplayName("지수는 같이 실패한 건들을 같은 시각에 깨운다 — 이게 지터가 필요한 이유다")
    void 지수만으로는_몰림이_안_풀린다() {
        long distinct = IntStream.range(0, 100)
                .mapToLong(i -> RetryPolicy.EXPONENTIAL.nextDelay(3, 기본).toMillis())
                .distinct()
                .count();

        // 100 건이 전부 똑같은 간격을 받는다 = 8초 뒤에 한꺼번에 몰린다
        assertThat(distinct).isEqualTo(1);
    }

    @Test
    @DisplayName("지터는 같은 조건의 건들을 흩뜨린다")
    void 지터는_흩뜨린다() {
        long distinct = IntStream.range(0, 100)
                .mapToLong(i -> RetryPolicy.EXPONENTIAL_JITTER.nextDelay(3, 기본).toMillis())
                .distinct()
                .count();

        assertThat(distinct).isGreaterThan(50);
    }

    @Test
    @DisplayName("지터를 섞어도 기준 간격 ±비율 안에 있다")
    void 지터_폭을_벗어나지_않는다() {
        for (int i = 0; i < 200; i++) {
            Duration d = RetryPolicy.EXPONENTIAL_JITTER.nextDelay(3, 기본);
            assertThat(d).isBetween(Duration.ofMillis(6_400), Duration.ofMillis(9_600));  // 8s ±20%
        }
    }

    @Test
    @DisplayName("간격은 언제나 0 보다 크다 — 0 이면 쉬지 않고 두드린다")
    void 간격은_항상_양수다() {
        RetryPolicyProperties 큰지터 = new RetryPolicyProperties(
                null, Duration.ofMillis(1), Duration.ofSeconds(60), 0.99, 10);

        for (int i = 0; i < 200; i++) {
            assertThat(RetryPolicy.EXPONENTIAL_JITTER.nextDelay(0, 큰지터))
                    .isGreaterThan(Duration.ZERO);
        }
    }
}
