package com.ondo.wholesale.notification.dto;

/**
 * 헤더 배지 둘 (MUL-154) — 웹 스펙 {@code NotificationUnreadCount}.
 *
 * @param notifications        안 읽은 알림 수
 * @param chatsNeedingReply    답이 필요한 채팅방 수. 도매에 채팅이 아직 없어 0 이다 — 채팅 작업이 채운다
 * @param latestNotificationId 목록 첫 행(createdAt desc, id desc)의 id. 알림이 없으면 null
 */
public record NotificationUnreadCountResponse(int notifications, int chatsNeedingReply, Long latestNotificationId) {}
