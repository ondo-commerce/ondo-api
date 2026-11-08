package com.ondo.retail.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 알림 keyset 커서 (MUL-155).
 *
 * <p>잡고 싶은 사고 — 시각을 밀리초로 잘라 같은 밀리초의 행이 경계에서 빠지는 것, 그리고 깨진 커서가
 * 500 으로 새는 것. 400 의 HTTP 모양은 NotificationControllerTest 가 본다.
 */
class NotificationCursorTest {

    @Test
    @DisplayName("마이크로초까지 그대로 왕복한다")
    void 마이크로초까지_왕복한다() {
        OffsetDateTime at = OffsetDateTime.of(2026, 11, 7, 10, 0, 0, 123_456_000, ZoneOffset.ofHours(9));

        NotificationCursor cursor = NotificationCursor.of(at, 2031L);
        NotificationCursor back = NotificationCursor.decode(cursor.encode()).orElseThrow();

        assertThat(back).isEqualTo(cursor);
        assertThat(back.epochMicros() % 1_000_000L).isEqualTo(123_456L);
        assertThat(back.id()).isEqualTo(2031L);
    }

    @Test
    @DisplayName("base64 가 아니거나 · 모양이 틀리거나 · id 가 0 이면 못 읽는다")
    void 깨진_커서는_빈_값이다() {
        assertThat(NotificationCursor.decode("***")).isEmpty();
        assertThat(NotificationCursor.decode(b64("abc"))).isEmpty();
        assertThat(NotificationCursor.decode(b64("1|0"))).isEmpty();
        assertThat(NotificationCursor.decode(b64("-1|3"))).isEmpty();
        assertThat(NotificationCursor.decode(b64("99999999999999999999|3"))).isEmpty();
    }

    private static String b64(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
