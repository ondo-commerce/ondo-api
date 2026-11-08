package com.ondo.retail.wholesale.notification.dto;

import java.time.OffsetDateTime;

/** 도매가 주는 소매 알림 한 건 (MUL-155). 소매 계약과 같은 모양이다. */
public record WholesaleNotification(
        long id,
        String type,
        String title,
        String body,
        Target target,
        OffsetDateTime createdAt,
        OffsetDateTime readAt) {

    public record Target(String kind, Long orderId, Long wholesalerId) {
    }
}
