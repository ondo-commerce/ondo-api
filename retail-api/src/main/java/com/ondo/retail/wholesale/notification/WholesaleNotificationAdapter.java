package com.ondo.retail.wholesale.notification;

import static com.ondo.retail.wholesale.WholesaleCall.call;

import com.ondo.retail.common.error.BusinessException;
import com.ondo.retail.common.error.ErrorCode;
import com.ondo.retail.notification.NotificationClient;
import com.ondo.retail.notification.NotificationCursor;
import com.ondo.retail.notification.dto.NotificationResponse;
import com.ondo.retail.wholesale.WholesaleApiException;
import com.ondo.retail.wholesale.notification.dto.WholesaleNotification;
import com.ondo.retail.wholesale.notification.dto.WholesaleNotificationReadRequest;
import com.ondo.retail.wholesale.notification.dto.WholesaleNotificationUnread;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * 도매 알림 API 를 소매 말로 옮긴다 (MUL-155).
 *
 * <p>실패는 미송·상품과 같다 — 도매가 안 뜨거나 5xx 면 {@code WholesaleApiException} → 500.
 * 따로 다루는 건 「모두 읽음」의 409 하나다. 그건 장애가 아니라 정상 흐름(그 사이 새 알림이 왔다)이라
 * 소매 409 {@code UNREAD_CHANGED} 로 옮긴다.
 *
 * <p>서버가 재시도하지 않는다. 화면이 30초마다 배지를 다시 부르고, 목록·읽음은 사용자가 다시 누른다.
 * 서버가 대신 재시도하면 같은 요청이 겹쳐 쌓일 뿐이다(docs/design/notification.md).
 */
@Component
@RequiredArgsConstructor
public class WholesaleNotificationAdapter implements NotificationClient {

    private static final String UNREAD_CHANGED = "UNREAD_CHANGED";

    private final WholesaleNotificationApi api;

    @Override
    public List<NotificationResponse> find(Long retailerId, NotificationCursor before, int limit) {
        Long beforeMicros = before == null ? null : before.epochMicros();
        Long beforeId = before == null ? null : before.id();
        return call("알림 목록", () -> api.notifications(retailerId, limit, beforeMicros, beforeId))
                .data().stream()
                .map(WholesaleNotificationAdapter::toResponse)
                .toList();
    }

    @Override
    public Unread unread(Long retailerId) {
        WholesaleNotificationUnread data = call("알림 배지", () -> api.unreadCount(retailerId)).data();
        return new Unread(data.notifications(), data.latestNotificationId());
    }

    @Override
    public int markRead(Long retailerId, List<Long> ids) {
        return call("알림 읽음", () -> api.read(WholesaleNotificationReadRequest.ids(retailerId, ids)))
                .data().notifications();
    }

    @Override
    public int markAllRead(Long retailerId, int expectedUnread) {
        try {
            return call("알림 모두 읽음",
                    () -> api.read(WholesaleNotificationReadRequest.all(retailerId, expectedUnread)))
                    .data().notifications();
        } catch (WholesaleApiException e) {
            if (e.getCause() instanceof HttpClientErrorException conflict
                    && conflict.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)
                    && isUnreadChanged(conflict)) {
                throw new BusinessException(ErrorCode.UNREAD_CHANGED);
            }
            throw e;
        }
    }

    private static boolean isUnreadChanged(HttpClientErrorException e) {
        try {
            WholesaleError body = e.getResponseBodyAs(WholesaleError.class);
            return body != null && UNREAD_CHANGED.equals(body.code());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static NotificationResponse toResponse(WholesaleNotification source) {
        WholesaleNotification.Target t = source.target();
        return new NotificationResponse(
                source.id(),
                source.type(),
                source.title(),
                source.body(),
                new NotificationResponse.Target(t.kind(), t.orderId(), t.wholesalerId()),
                source.createdAt(),
                source.readAt());
    }

    /** 도매 실패 응답 봉투. */
    private record WholesaleError(String code, String message) {
    }
}
