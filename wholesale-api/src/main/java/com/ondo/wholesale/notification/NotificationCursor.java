package com.ondo.wholesale.notification;

import com.ondo.wholesale.common.error.ApiException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 알림 목록 keyset 커서 (MUL-154) — 정렬 {@code (createdAt desc, id desc)} 의 한 지점.
 *
 * <p>불투명 값이다. FE 는 열지 않는다. 안은 {@code base64url("<epoch 마이크로초>|<id>")}.
 *
 * <p><b>마이크로초로 담는다.</b> 밀리초로 자르면 같은 밀리초 안에 든 다른 행이 커서보다
 * "뒤" 로 계산돼 다음 장에서 빠지거나 겹친다. timestamptz 의 정밀도가 마이크로초다.
 */
record NotificationCursor(OffsetDateTime createdAt, long id) {

    private static final Pattern SHAPE = Pattern.compile("^(\\d{1,18})\\|(\\d{1,18})$");
    /** 9999-12-31T23:59:59.999999Z. 이보다 크면 DB 에 넘기기 전에 400 으로 끊는다 */
    private static final long MAX_MICROS = 253_402_300_799_999_999L;

    static NotificationCursor of(OffsetDateTime createdAt, long id) {
        return new NotificationCursor(createdAt, id);
    }

    String encode() {
        Instant at = createdAt.toInstant();
        long micros = Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000L), at.getNano() / 1_000);
        String raw = micros + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 잘못된 값이면 400 VALIDATION_FAILED {field: cursor}. */
    static NotificationCursor decode(String cursor) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        Matcher m = SHAPE.matcher(raw);
        if (!m.matches()) {
            throw invalid();
        }
        long micros = Long.parseLong(m.group(1));
        long id = Long.parseLong(m.group(2));
        if (micros > MAX_MICROS) {
            throw invalid();
        }
        Instant at = Instant.ofEpochSecond(micros / 1_000_000L, (micros % 1_000_000L) * 1_000L);
        return new NotificationCursor(at.atOffset(ZoneOffset.UTC), id);
    }

    private static ApiException invalid() {
        return ApiException.validationFailed("cursor", "cursor 가 올바르지 않습니다.");
    }
}
