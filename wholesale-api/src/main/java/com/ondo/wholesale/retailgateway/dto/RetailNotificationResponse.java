package com.ondo.wholesale.retailgateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

/**
 * 소매 알림 한 건 (MUL-155). 소매 계약 {@code Notification} 과 같은 모양이라 소매가 그대로 옮긴다.
 *
 * <p>{@code type} 을 문자열로 둔 건 계약이 "값이 늘 수 있다" 고 해서다. 도매가 먼저 새 값을 내도
 * 소매가 역직렬화에서 깨지지 않는다.
 */
public record RetailNotificationResponse(
        long id,
        String type,
        String title,
        String body,
        Target target,
        OffsetDateTime createdAt,
        OffsetDateTime readAt
) {

    /**
     * 누르면 갈 곳. kind 별로 칸 하나만 찬다 — ORDER 면 orderId, BACKORDERS 면 wholesalerId.
     * 빈 칸은 직렬화에서 뺀다(계약 {@code additionalProperties: false}).
     */
    public record Target(
            String kind,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long orderId,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long wholesalerId
    ) {}
}
