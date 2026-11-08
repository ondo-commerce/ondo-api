package com.ondo.wholesale.notification.dto;

import java.time.OffsetDateTime;

/**
 * 알림 한 건 (MUL-154) — 웹 스펙 {@code Notification}.
 *
 * <p>{@code type} 을 enum 이 아니라 문자열로 둔다. 계약이 "값이 늘 수 있다" 고 적었고,
 * 화면은 모르는 값을 title 만 그린다. {@code body}·{@code readAt} 은 null 이어도 칸이 나간다(required).
 */
public record NotificationResponse(
        Long id,
        String type,
        String title,
        String body,
        Target target,
        OffsetDateTime createdAt,
        OffsetDateTime readAt) {

    /** 누르면 갈 곳. 지금은 {@code ORDER} 뿐이고 id 는 도매 주문 id 다. */
    public record Target(String kind, Long id) {}
}
