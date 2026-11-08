package com.ondo.retail.chat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 채팅방 목록 keyset 커서 (MUL-157). 직전 장 마지막 방의 (lastActivityAt, roomId).
 *
 * <p>프론트에는 불투명 문자열이다. 안은 base64url({@code "<epoch µs>|<roomId>"}) — 알림 커서({@code NotificationCursor})와
 * 같은 이유로 마이크로초다(Postgres timestamptz 정밀도). 위치만 담는다.
 */
public record ChatRoomCursor(long epochMicros, long roomId) {

    private static final Pattern RAW = Pattern.compile("^(\\d{1,19})\\|(\\d{1,19})$");

    public static ChatRoomCursor of(OffsetDateTime lastActivityAt, long roomId) {
        Instant at = lastActivityAt.toInstant();
        return new ChatRoomCursor(Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000L),
                at.getNano() / 1_000L), roomId);
    }

    public String encode() {
        String raw = epochMicros + "|" + roomId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 못 읽으면 빈 값. 부르는 쪽이 400 으로 바꾼다. */
    public static Optional<ChatRoomCursor> decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            Matcher m = RAW.matcher(raw);
            if (!m.matches()) {
                return Optional.empty();
            }
            long micros = Long.parseLong(m.group(1));
            long roomId = Long.parseLong(m.group(2));
            return roomId > 0 ? Optional.of(new ChatRoomCursor(micros, roomId)) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
