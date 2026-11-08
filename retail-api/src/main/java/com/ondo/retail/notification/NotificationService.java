package com.ondo.retail.notification;

import com.ondo.retail.common.error.BusinessException;
import com.ondo.retail.common.error.ErrorCode;
import com.ondo.retail.common.error.ErrorResponse.FieldError;
import com.ondo.retail.notification.dto.MarkNotificationsReadResult;
import com.ondo.retail.notification.dto.NotificationPageResponse;
import com.ondo.retail.notification.dto.NotificationResponse;
import com.ondo.retail.notification.dto.RetailUnreadCount;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 소매 알림 3 op (MUL-155). 설계: docs/design/notification.md
 *
 * <p>알림은 도매에 있다. 여기는 <b>프론트 계약의 검증과 커서</b>만 맡고 나머지는 도매에 묻는다.
 * 검증을 여기서 하는 건 에러 모양(errors[{field}])이 소매 계약이라서다 — 도매 400 을 옮기면
 * 칸 이름이 어긋난다.
 *
 * <p>{@code @Transactional} 이 없다. 소매 DB 를 안 보고, 도매 호출을 트랜잭션 안에 두면
 * 커넥션을 붙든 채 기다린다({@code BackorderService} 와 같은 이유).
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;
    static final int MAX_IDS = 100;

    /** 화면이 아직 채팅 배지를 못 받는다. 채팅(MUL-157)이 붙으면 도매가 같이 준다. */
    private static final int CHATS_NOT_YET = 0;

    private final NotificationClient client;

    /**
     * 한 장. size + 1 개를 받아 하나가 더 있으면 다음 장이 있는 것이다 — 전체 개수를 세지 않는다.
     *
     * @param rawSize   쿼리 값 그대로. 비면 20
     * @param rawCursor 쿼리 값 그대로. 비면 첫 장
     */
    public NotificationPageResponse page(Long retailerId, String rawSize, String rawCursor) {
        int size = parseSize(rawSize);
        NotificationCursor before = parseCursor(rawCursor);

        List<NotificationResponse> rows = client.find(retailerId, before, size + 1);
        if (rows.size() <= size) {
            return new NotificationPageResponse(rows, new NotificationPageResponse.Meta(null));
        }
        List<NotificationResponse> page = rows.subList(0, size);
        NotificationResponse last = page.getLast();
        String next = NotificationCursor.of(last.createdAt(), last.id()).encode();
        return new NotificationPageResponse(List.copyOf(page), new NotificationPageResponse.Meta(next));
    }

    public RetailUnreadCount unreadCount(Long retailerId) {
        NotificationClient.Unread unread = client.unread(retailerId);
        return new RetailUnreadCount(unread.notifications(), CHATS_NOT_YET, unread.latestNotificationId());
    }

    /**
     * 본문은 둘 중 하나다 — {@code {ids}} 또는 {@code {all: true, expectedUnread}}. 계약의 {@code oneOf}
     * 두 갈래가 모두 {@code additionalProperties: false} 라 섞이거나 모르는 칸이 오면 400 이다.
     *
     * <p>Map 으로 받는 건 "칸이 왔는가" 를 봐야 해서다. 레코드로 받으면 안 온 것과 null 이 같아진다.
     */
    public MarkNotificationsReadResult markRead(Long retailerId, Map<String, Object> body) {
        boolean hasIds = body.containsKey("ids");
        boolean hasAll = body.containsKey("all");
        if (hasIds && hasAll) {
            throw invalid("all", "CONFLICT", "ids 와 all 은 같이 보낼 수 없어요");
        }
        if (!hasIds && !hasAll) {
            throw invalid("ids", "REQUIRED", "ids 또는 all 이 필요해요");
        }
        Set<String> allowed = hasIds ? Set.of("ids") : Set.of("all", "expectedUnread");
        for (String key : body.keySet()) {
            if (!allowed.contains(key)) {
                throw invalid(key, "UNKNOWN_FIELD", key + " 는 받지 않아요");
            }
        }

        if (hasIds) {
            return new MarkNotificationsReadResult(client.markRead(retailerId, parseIds(body.get("ids"))));
        }
        if (!Boolean.TRUE.equals(body.get("all"))) {
            throw invalid("all", "INVALID", "all 은 true 만 받아요");
        }
        if (!body.containsKey("expectedUnread") || body.get("expectedUnread") == null) {
            throw invalid("expectedUnread", "REQUIRED", "expectedUnread 가 필요해요");
        }
        if (!(body.get("expectedUnread") instanceof Integer expected) || expected < 1) {
            throw invalid("expectedUnread", "OUT_OF_RANGE", "expectedUnread 는 1 이상 정수여야 해요");
        }
        return new MarkNotificationsReadResult(client.markAllRead(retailerId, expected));
    }

    // ── 검증 ────────────────────────────────────────────────

    private static int parseSize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return DEFAULT_SIZE;
        }
        if (!raw.matches("\\d{1,3}")) {
            throw invalid("size", "OUT_OF_RANGE", "size 는 1~" + MAX_SIZE + " 이어야 해요");
        }
        int size = Integer.parseInt(raw);
        if (size < 1 || size > MAX_SIZE) {
            throw invalid("size", "OUT_OF_RANGE", "size 는 1~" + MAX_SIZE + " 이어야 해요");
        }
        return size;
    }

    private static NotificationCursor parseCursor(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return NotificationCursor.decode(raw)
                .orElseThrow(() -> invalid("cursor", "INVALID", "cursor 가 올바르지 않아요"));
    }

    private static List<Long> parseIds(Object value) {
        if (!(value instanceof List<?> list)) {
            throw invalid("ids", "INVALID", "ids 는 정수 배열이어야 해요");
        }
        List<Long> ids = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Integer || item instanceof Long)) {
                throw invalid("ids", "INVALID", "ids 는 정수 배열이어야 해요");
            }
            ids.add(((Number) item).longValue());
        }
        if (ids.isEmpty() || ids.size() > MAX_IDS) {
            throw invalid("ids", "SIZE", "ids 는 1~" + MAX_IDS + "개여야 해요");
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw invalid("ids", "DUPLICATE", "ids 에 같은 id 가 두 번 있어요");
        }
        return ids;
    }

    private static BusinessException invalid(String field, String code, String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, List.of(new FieldError(field, code, message)));
    }
}
