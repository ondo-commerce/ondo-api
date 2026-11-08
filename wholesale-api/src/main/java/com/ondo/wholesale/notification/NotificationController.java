package com.ondo.wholesale.notification;

import com.ondo.wholesale.notification.dto.MarkNotificationsReadResponse;
import com.ondo.wholesale.notification.dto.NotificationPageResponse;
import com.ondo.wholesale.notification.dto.NotificationUnreadCountResponse;
import com.ondo.wholesale.security.WholesalePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 도매 알림 API (MUL-154) — 원본 계약: 웹 레포 {@code packages/api/openapi/wholesale.json}
 * 의 초안 op 3개(notifications · markNotificationsRead · notificationUnreadCount).
 */
@Tag(name = "11 알림")
@RestController
@RequestMapping("/api/wholesale/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(operationId = "notifications", summary = "알림 목록 (커서)", description = """
            내 알림 목록. 정렬 createdAt 내림차순, 같으면 id 내림차순(고정). keyset 커서라 첫 장을 받은 뒤
            새 알림이 생겨도 다음 장에 중복, 누락이 없다(새 알림은 첫 장 앞에 붙는다). id 는 순서 의미가 없다.

            cursor 는 직전 응답 meta.nextCursor 그대로(불투명). size 1~100, 기본 20.

            에러: 400 VALIDATION_FAILED(잘못된 cursor, size 범위 밖 — errors[{field: cursor|size}])""")
    @GetMapping
    public NotificationPageResponse list(@AuthenticationPrincipal WholesalePrincipal principal,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(required = false) Integer size) {
        return notificationService.list(principal.wholesalerId(), cursor, size);
    }

    @Operation(operationId = "notificationUnreadCount", summary = "안 읽은 알림 수와 답 필요 방 수",
            description = """
                    헤더 배지 둘. notifications = 안 읽은 알림 수, chatsNeedingReply = 답 필요 방 수
                    (도매 채팅 전이라 지금은 0), latestNotificationId = 목록 첫 행 id(없으면 null).""")
    @GetMapping("/unread-count")
    public NotificationUnreadCountResponse unreadCount(@AuthenticationPrincipal WholesalePrincipal principal) {
        return notificationService.unreadCount(principal.wholesalerId());
    }

    @Operation(operationId = "markNotificationsRead", summary = "알림 읽음 처리", description = """
            본문은 둘 중 하나. {ids}(1~100개, 멱등, 없는·남의 id 는 무시) 또는 {all: true, expectedUnread}
            (안 읽음 전부. 바뀐 수가 expectedUnread 와 다르면 롤백하고 409 UNREAD_CHANGED).
            응답 notifications = 처리 뒤 남은 안 읽음 수.

            에러: 400 VALIDATION_FAILED(errors[{field: ids|all|expectedUnread}]) / 409 UNREAD_CHANGED""")
    @PostMapping("/read")
    public MarkNotificationsReadResponse markRead(@AuthenticationPrincipal WholesalePrincipal principal,
                                                  @RequestBody Map<String, Object> body) {
        return notificationService.markRead(principal.wholesalerId(), body);
    }
}
