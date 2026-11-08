package com.ondo.retail.notification;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 알림 목록 keyset 커서 (MUL-155). 직전 장 마지막 행의 (createdAt, id).
 *
 * <p>프론트에는 불투명 문자열이다(FE 가 열지 않는다). 안은 base64url({@code "<epoch µs>|<id>"}).
 * 마이크로초인 건 저장(Postgres timestamptz)의 정밀도와 맞추려는 것이다 — 밀리초로 자르면 같은
 * 밀리초 안의 다른 행이 경계에서 빠지거나 겹친다.
 *
 * @param epochMicros createdAt 의 epoch 마이크로초
 * @param id          알림 id. 같은 시각이면 id 로 갈린다
 */
public record NotificationCursor(long epochMicros, long id) {

    private static final Pattern RAW = Pattern.compile("^(\\d{1,19})\\|(\\d{1,19})$");

    public static NotificationCursor of(OffsetDateTime createdAt, long id) {
        Instant at = createdAt.toInstant();
        return new NotificationCursor(Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000L),
                at.getNano() / 1_000L), id);
    }

    public String encode() {
        String raw = epochMicros + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 못 읽으면 빈 값. 부르는 쪽이 400 으로 바꾼다. */
    public static Optional<NotificationCursor> decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            Matcher m = RAW.matcher(raw);
            if (!m.matches()) {
                return Optional.empty();
            }
            long micros = Long.parseLong(m.group(1));
            long id = Long.parseLong(m.group(2));
            return id > 0 ? Optional.of(new NotificationCursor(micros, id)) : Optional.empty();
        } catch (IllegalArgumentException e) {
            // base64 가 아니거나(IllegalArgumentException) 숫자가 long 을 넘는다(NumberFormatException)
            return Optional.empty();
        }
    }
}
