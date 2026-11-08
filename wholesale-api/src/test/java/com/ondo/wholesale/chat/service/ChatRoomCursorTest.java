package com.ondo.wholesale.chat.service;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 방 목록 커서 단위 검증 (MUL-158).
 *
 * <p>잡고 싶은 사고: 시각을 밀리초로 잘라 같은 밀리초 안의 방이 다음 장에서 빠지거나 겹친다.
 * 커서로 실제 장을 넘기는 것은 ChatRoomQueryIntegrationTest 가 본다.
 */
class ChatRoomCursorTest {

    @Test
    void 마이크로초까지_그대로_되돌아온다() {
        OffsetDateTime at = OffsetDateTime.parse("2026-10-28T10:00:00.123456+09:00");
        ChatRoomCursor cursor = new ChatRoomCursor(at, 42L);

        ChatRoomCursor decoded = ChatRoomCursor.decode(cursor.encode()).orElseThrow();

        assertThat(decoded.lastActivityAt().toInstant()).isEqualTo(at.toInstant());
        assertThat(decoded.roomId()).isEqualTo(42L);
    }

    @Test
    void 모양이_틀린_커서는_빈_값이다() {
        assertThat(ChatRoomCursor.decode("not-a-cursor")).isEmpty();
        assertThat(ChatRoomCursor.decode("")).isEmpty();
        assertThat(ChatRoomCursor.decode("%%%")).isEmpty();
        assertThat(ChatRoomCursor.decode(java.util.Base64.getUrlEncoder().encodeToString("1|x".getBytes()))).isEmpty();
    }
}
