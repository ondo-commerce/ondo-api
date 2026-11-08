package com.ondo.wholesale.notification;

import com.ondo.wholesale.common.error.ApiException;

import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 읽음 처리 본문 (MUL-154) — 스펙 {@code MarkNotificationsReadRequest}.
 *
 * <p>스펙이 {@code oneOf}({ids} | {all, expectedUnread}, 둘 다 additionalProperties false)라
 * 레코드 하나에 Bean Validation 을 붙이는 걸로는 "둘을 섞으면 400" 을 못 낸다. 그래서 본문을
 * {@code Map} 으로 받아 여기서 고른다. 어긋나면 400 VALIDATION_FAILED, field 는 ids|all|expectedUnread.
 */
sealed interface MarkReadRequest {

    int IDS_MAX = 100;

    /** 고른 알림만. 중복 id 는 하나로 접는다. */
    record Ids(Set<Long> ids) implements MarkReadRequest {}

    /** 내 안 읽음 전부. 화면이 버튼에 보여 준 수와 실제로 바뀐 수가 다르면 409. */
    record All(int expectedUnread) implements MarkReadRequest {}

    static MarkReadRequest parse(Map<String, Object> body) {
        boolean hasIds = body.containsKey("ids");
        boolean hasAll = body.containsKey("all");
        if (hasIds && hasAll) {
            throw ApiException.validationFailed("all", "ids 와 all 은 같이 보낼 수 없습니다.");
        }
        if (!hasIds && !hasAll) {
            throw ApiException.validationFailed("ids", "ids 또는 all 이 필요합니다.");
        }
        List<String> allowed = hasIds ? List.of("ids") : List.of("all", "expectedUnread");
        body.keySet().stream()
                .filter(k -> !allowed.contains(k))
                .findFirst()
                .ifPresent(extra -> {
                    throw ApiException.validationFailed(extra, extra + " 는 받지 않습니다.");
                });
        return hasIds ? parseIds(body.get("ids")) : parseAll(body.get("all"), body);
    }

    private static Ids parseIds(Object value) {
        if (!(value instanceof List<?> list)) {
            throw ApiException.validationFailed("ids", "ids 는 정수 배열이어야 합니다.");
        }
        if (list.isEmpty() || list.size() > IDS_MAX) {
            throw ApiException.validationFailed("ids", "ids 는 1~" + IDS_MAX + "개여야 합니다.");
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (Object v : list) {
            Long id = asLong(v);
            if (id == null) {
                throw ApiException.validationFailed("ids", "ids 는 정수 배열이어야 합니다.");
            }
            ids.add(id);
        }
        return new Ids(ids);
    }

    private static All parseAll(Object all, Map<String, Object> body) {
        if (!Boolean.TRUE.equals(all)) {
            throw ApiException.validationFailed("all", "all 은 true 만 받습니다.");
        }
        if (!body.containsKey("expectedUnread") || body.get("expectedUnread") == null) {
            throw ApiException.validationFailed("expectedUnread", "expectedUnread 가 필요합니다.");
        }
        Long expected = asLong(body.get("expectedUnread"));
        if (expected == null || expected < 1 || expected > Integer.MAX_VALUE) {
            throw ApiException.validationFailed("expectedUnread", "expectedUnread 는 1 이상 정수여야 합니다.");
        }
        return new All(expected.intValue());
    }

    /** JSON 정수만 받는다. 1.0 · "3" · true 는 정수가 아니다. long 을 넘는 값도 거절한다. */
    private static Long asLong(Object v) {
        if (v instanceof Integer i) {
            return i.longValue();
        }
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof BigInteger b && b.bitLength() < 64) {
            return b.longValue();
        }
        return null;
    }
}
