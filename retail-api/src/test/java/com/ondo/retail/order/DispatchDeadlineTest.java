package com.ondo.retail.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 대기 주문의 기한 (MUL-139).
 *
 * <p>두 선 중 이른 쪽을 고르는지, 그리고 <b>영업일 경계가 자정이 아니라 낮 12시</b>인지가
 * 핵심이다. 경계를 넘겨 접수되면 어제 주문이 오늘 주문으로 집계돼 소매 화면의 날짜와
 * 도매 장부의 날짜가 어긋난다.
 */
class DispatchDeadlineTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static OffsetDateTime kst(int month, int day, int hour, int minute) {
        return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, KST).toOffsetDateTime();
    }

    @Test
    @DisplayName("영업일이 넉넉히 남았으면 대기 한도가 기한이다")
    void 한도가_먼저_오면_한도를_쓴다() {
        // 저녁 8시 주문. 다음 경계(내일 낮 12시)까지 16시간이 남았다
        OffsetDateTime orderedAt = kst(9, 20, 20, 0);

        OffsetDateTime deadline = DispatchDeadline.of(orderedAt, Duration.ofMinutes(30));

        assertThat(deadline).isEqualTo(kst(9, 20, 20, 30));
    }

    @Test
    @DisplayName("영업일 경계가 먼저 오면 한도가 남아 있어도 경계에서 끊는다")
    void 경계가_먼저_오면_경계를_쓴다() {
        // 11:50 주문. 30분을 다 주면 12:20 인데 그건 다음 영업일이다
        OffsetDateTime orderedAt = kst(9, 20, 11, 50);

        OffsetDateTime deadline = DispatchDeadline.of(orderedAt, Duration.ofMinutes(30));

        assertThat(deadline).isEqualTo(kst(9, 20, 12, 0));
    }

    @Test
    @DisplayName("새벽 주문의 영업일은 전날 12시에 시작했으므로 경계는 그날 낮 12시다")
    void 새벽_주문은_그날_낮_12시가_경계다() {
        // 동대문은 저녁에 열어 새벽에 마친다. 새벽 3시는 전날 영업일에 속한다
        OffsetDateTime orderedAt = kst(9, 20, 3, 0);

        assertThat(DispatchDeadline.nextBoundaryAfter(orderedAt)).isEqualTo(kst(9, 20, 12, 0));
    }

    @Test
    @DisplayName("낮 12시 정각 주문의 영업일은 방금 시작했으므로 경계는 다음 날이다")
    void 정오_주문은_다음날_낮_12시가_경계다() {
        OffsetDateTime orderedAt = kst(9, 20, 12, 0);

        assertThat(DispatchDeadline.nextBoundaryAfter(orderedAt)).isEqualTo(kst(9, 21, 12, 0));
    }

    @Test
    @DisplayName("기한은 주문 시각보다 뒤다 — 경계를 골라도 과거가 되지 않는다")
    void 기한은_항상_주문_시각_뒤다() {
        for (int hour = 0; hour < 24; hour++) {
            OffsetDateTime orderedAt = kst(9, 20, hour, 30);
            assertThat(DispatchDeadline.of(orderedAt, Duration.ofMinutes(30)))
                    .as("%d시 주문", hour)
                    .isAfter(orderedAt);
        }
    }
}
