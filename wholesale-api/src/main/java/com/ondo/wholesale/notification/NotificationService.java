package com.ondo.wholesale.notification;

import com.ondo.wholesale.common.error.ApiException;
import com.ondo.wholesale.common.error.ErrorCode;
import com.ondo.wholesale.notification.dto.MarkNotificationsReadResponse;
import com.ondo.wholesale.notification.dto.NotificationPageResponse;
import com.ondo.wholesale.notification.dto.NotificationResponse;
import com.ondo.wholesale.notification.dto.NotificationUnreadCountResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 도매 알림 목록·안 읽음 수·읽음 처리 (MUL-154).
 *
 * <p>모든 범위는 도매처(사업체) id 다. 같은 사업체의 탭·기기·직원이 같은 읽음을 본다.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    static final int PAGE_DEFAULT = 20;
    static final int PAGE_MAX = 100;

    private final NotificationRepository repository;

    @Transactional(readOnly = true)
    public NotificationPageResponse list(long wholesalerId, String cursor, Integer size) {
        int pageSize = size == null ? PAGE_DEFAULT : size;
        if (pageSize < 1 || pageSize > PAGE_MAX) {
            throw ApiException.validationFailed("size", "size 는 1~" + PAGE_MAX + " 이어야 합니다.");
        }
        NotificationCursor after = (cursor == null || cursor.isEmpty()) ? null : NotificationCursor.decode(cursor);

        // 한 행 더 읽어 다음 장이 있는지 본다. 개수를 따로 세지 않는다
        List<NotificationResponse> rows = repository.page(wholesalerId, after, pageSize + 1);
        if (rows.size() <= pageSize) {
            return new NotificationPageResponse(rows, new NotificationPageResponse.CursorPageMeta(null));
        }
        List<NotificationResponse> page = rows.subList(0, pageSize);
        NotificationResponse last = page.getLast();
        String next = NotificationCursor.of(last.createdAt(), last.id()).encode();
        return new NotificationPageResponse(List.copyOf(page), new NotificationPageResponse.CursorPageMeta(next));
    }

    @Transactional(readOnly = true)
    public NotificationUnreadCountResponse unreadCount(long wholesalerId) {
        // 도매 채팅이 아직 없다. 채팅 작업이 needsReply 방 수로 채운다
        int chatsNeedingReply = 0;
        return new NotificationUnreadCountResponse(
                repository.countUnread(wholesalerId),
                chatsNeedingReply,
                repository.latestId(wholesalerId).orElse(null));
    }

    /**
     * 읽음 처리.
     *
     * <p>{ids} — 멱등이다. 없는 id, 남의 id 는 조용히 넘어간다.
     *
     * <p>{all} — 한 트랜잭션에서 안 읽음을 전부 바꾸고, 바뀐 수가 expectedUnread 와 다르면
     * 예외로 롤백한다(409 UNREAD_CHANGED). 그 사이 새 알림이 왔거나 다른 탭이 읽었다는 뜻이다.
     * 동시에 두 탭이 all 을 보내면 뒤쪽 UPDATE 가 앞쪽 커밋을 기다린 뒤 0 행을 바꿔 409 가 된다.
     */
    @Transactional
    public MarkNotificationsReadResponse markRead(long wholesalerId, Map<String, Object> body) {
        switch (MarkReadRequest.parse(body)) {
            case MarkReadRequest.Ids ids -> repository.markRead(wholesalerId, ids.ids());
            case MarkReadRequest.All all -> {
                int changed = repository.markAllRead(wholesalerId);
                if (changed != all.expectedUnread()) {
                    throw new ApiException(ErrorCode.UNREAD_CHANGED,
                            "그 사이 안 읽은 알림 수가 바뀌었습니다. expected=%d actual=%d"
                                    .formatted(all.expectedUnread(), changed));
                }
            }
        }
        return new MarkNotificationsReadResponse(repository.countUnread(wholesalerId));
    }
}
