package com.ondo.wholesale.retailgateway.dto;

/** 읽음 결과 (MUL-155). {@code notifications} = 처리 뒤 남은 안 읽음 수. */
public record RetailNotificationReadResponse(int notifications) {}
