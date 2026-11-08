package com.ondo.wholesale.retailgateway.dto;

/**
 * 소매 알림 배지 (MUL-155).
 *
 * @param notifications        안 읽은 수
 * @param latestNotificationId 가장 최근 알림(createdAt desc, id desc 첫 행)의 id. 없으면 null.
 *                             id 최대값이 아니다 — id 는 순서 의미가 없다(계약)
 */
public record RetailNotificationUnreadResponse(int notifications, Long latestNotificationId) {}
