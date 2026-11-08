package com.ondo.retail.notification;

import com.ondo.retail.common.response.ApiResponse;
import com.ondo.retail.notification.dto.MarkNotificationsReadResult;
import com.ondo.retail.notification.dto.NotificationPageResponse;
import com.ondo.retail.notification.dto.RetailUnreadCount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 (MUL-155). 웹 초안 계약 {@code notifications} · {@code markNotificationsRead} ·
 * {@code notificationUnreadCount} 그대로다.
 *
 * <p>소매처 id 는 <b>세션에서만</b> 꺼낸다. 읽음 범위는 소매 사업체(= 로그인한 소매처)다.
 */
@Tag(name = "알림", description = "도매가 만든 내 알림. 읽음 범위는 소매 사업체다.")
@RestController
@RequestMapping("/api/retail/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "알림 목록 (커서)", description = """
            내 알림만, createdAt 내림차순 · 같으면 id 내림차순. keyset 커서라 새 알림이 와도 다음 장에
            중복·누락이 없다. `cursor` 는 직전 응답 `meta.nextCursor` 그대로, `size` 1~100 (기본 20).

            에러: 400 `VALIDATION_FAILED` (errors[{field: cursor|size}])""")
    @GetMapping
    public NotificationPageResponse notifications(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String size,
            Authentication authentication) {
        return notificationService.page(retailerId(authentication), size, cursor);
    }

    @Operation(summary = "알림 읽음 처리", description = """
            `{ids}` 고른 것만(멱등, 없는·남의 id 무시) 또는 `{all: true, expectedUnread}` 안 읽음 전부.
            수가 다르면 아무것도 안 바꾸고 409. 응답 `notifications` = 남은 안 읽음 수.

            에러: 400 `VALIDATION_FAILED` (errors[{field: ids|all|expectedUnread}]) / 409 `UNREAD_CHANGED`""")
    @PostMapping("/read")
    public ApiResponse<MarkNotificationsReadResult> read(
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        return ApiResponse.of(notificationService.markRead(retailerId(authentication), body));
    }

    @Operation(summary = "헤더 배지 둘", description = """
            안 읽은 알림 수 · 안 읽은 채팅 수(내 방 unreadCount 합, MUL-157) · 가장 최근 알림 id.""")
    @GetMapping("/unread-count")
    public ApiResponse<RetailUnreadCount> unreadCount(Authentication authentication) {
        return ApiResponse.of(notificationService.unreadCount(retailerId(authentication)));
    }

    private static Long retailerId(Authentication authentication) {
        return Long.valueOf(authentication.getName());
    }
}
