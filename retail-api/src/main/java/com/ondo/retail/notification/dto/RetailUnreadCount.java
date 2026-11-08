package com.ondo.retail.notification.dto;

/**
 * 헤더 배지 둘 (MUL-155). 계약 {@code RetailUnreadCount}.
 *
 * @param notifications        안 읽은 알림 수
 * @param chats                안 읽은 채팅 메시지 수 = 내 방 unreadCount 합(SYSTEM 제외). 도매 채팅 게이트웨이 배지에서 받는다(MUL-157)
 * @param latestNotificationId 가장 최근 알림 id. 없으면 null. 화면은 이 값이 바뀌었는지로 새 알림을 안다
 */
public record RetailUnreadCount(int notifications, int chats, Long latestNotificationId) {
}
