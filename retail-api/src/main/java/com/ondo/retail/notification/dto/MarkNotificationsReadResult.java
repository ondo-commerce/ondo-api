package com.ondo.retail.notification.dto;

/** 읽음 결과 (MUL-155). @param notifications 처리 뒤 남은 안 읽음 수 */
public record MarkNotificationsReadResult(int notifications) {
}
