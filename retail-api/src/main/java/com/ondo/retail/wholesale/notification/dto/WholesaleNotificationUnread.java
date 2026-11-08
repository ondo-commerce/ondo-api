package com.ondo.retail.wholesale.notification.dto;

/** 도매가 주는 알림 배지 값 (MUL-155). */
public record WholesaleNotificationUnread(int notifications, Long latestNotificationId) {
}
