package com.ondo.wholesale.chat.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 방 목록 커서 (MUL-158) — 정렬 (lastActivityAt desc, roomId desc)의 한 지점. FE 는 열지 않는 불투명 문자열이다.
 *
 * <p>시각은 마이크로초까지 싣는다. timestamptz 정밀도가 마이크로초라 밀리초로 자르면 같은 밀리초 안의 방이
 * 다음 장에서 빠지거나 겹친다. 위치만 담는다 — 조건(filter, q)을 바꾸면 첫 장부터 받는다(계약).
 */
public record ChatRoomCursor(OffsetDateTime lastActivityAt, long roomId) {

    private static final Pattern SHAPE = Pattern.compile("^(-?\\d{1,19})\\|(\\d{1,19})$");

    public String encode() {
        Instant at = lastActivityAt.toInstant();
        long micros = Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000L), at.getNano() / 1_000L);
        String raw = micros + "|" + roomId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 모양이 틀리면 빈 값 — 호출부가 400 으로 바꾼다. */
    public static Optional<ChatRoomCursor> decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            Matcher m = SHAPE.matcher(raw);
            if (!m.matches()) {
                return Optional.empty();
            }
            long micros = Long.parseLong(m.group(1));
            long roomId = Long.parseLong(m.group(2));
            Instant at = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                    Math.floorMod(micros, 1_000_000L) * 1_000L);
            return Optional.of(new ChatRoomCursor(OffsetDateTime.ofInstant(at, ZoneOffset.UTC), roomId));
        } catch (IllegalArgumentException | ArithmeticException e) {
            return Optional.empty();
        }
    }
}
