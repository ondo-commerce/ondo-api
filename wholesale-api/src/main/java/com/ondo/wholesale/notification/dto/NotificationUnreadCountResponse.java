package com.ondo.wholesale.notification.dto;

/**
 * 헤더 배지 둘 (MUL-154) — 웹 스펙 {@code NotificationUnreadCount}.
 *
 * @param notifications        안 읽은 알림 수
 * @param chatsNeedingReply    답이 필요한 채팅방 수. 방 목록 needsReply 와 같은 정의 (MUL-159)
 * @param latestNotificationId 목록 첫 행(createdAt desc, id desc)의 id. 알림이 없으면 null
 */
public record NotificationUnreadCountResponse(int notifications, int chatsNeedingReply, Long latestNotificationId) {}
