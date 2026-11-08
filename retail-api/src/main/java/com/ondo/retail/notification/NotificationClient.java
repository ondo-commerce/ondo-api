package com.ondo.retail.notification;

import com.ondo.retail.notification.dto.NotificationResponse;
import java.util.List;

/**
 * 도매에 있는 소매 알림을 읽고 읽음 처리하는 통로 (MUL-155).
 *
 * <p>알림은 도매 DB 에 있다 — 만드는 사건(입고일 변경 등)이 전부 도매 트랜잭션이라서다
 * (docs/design/notification.md). {@code com.ondo.retail.wholesale.notification.WholesaleNotificationAdapter}
 * 가 구현한다. {@code BackorderClient} 와 같은 이유로 이 패키지는 상대가 HTTP 인지 모른다.
 *
 * <p>모든 {@code retailerId} 는 세션에서 꺼낸 값이어야 한다.
 */
public interface NotificationClient {

    /**
     * 최신순(createdAt desc, id desc) 으로 최대 {@code limit} 개.
     *
     * @param before 이 지점보다 뒤(더 오래된 것)만. 첫 장이면 null
     */
    List<NotificationResponse> find(Long retailerId, NotificationCursor before, int limit);

    /** 안 읽은 수와 가장 최근 알림 id. */
    Unread unread(Long retailerId);

    /** 고른 것만 읽음. 남은 안 읽음 수를 돌려준다. */
    int markRead(Long retailerId, List<Long> ids);

    /**
     * 안 읽음 전부. 실제 수가 {@code expectedUnread} 와 다르면 아무것도 안 바꾸고
     * {@code BusinessException(UNREAD_CHANGED)} 를 던진다.
     */
    int markAllRead(Long retailerId, int expectedUnread);

    record Unread(int notifications, Long latestNotificationId) {
    }
}
