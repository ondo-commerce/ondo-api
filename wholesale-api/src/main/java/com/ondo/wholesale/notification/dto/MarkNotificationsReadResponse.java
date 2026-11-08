package com.ondo.wholesale.notification.dto;

/** 읽음 결과 (MUL-154). {@code notifications} = 처리 뒤 남은 안 읽음 수. */
public record MarkNotificationsReadResponse(int notifications) {}
