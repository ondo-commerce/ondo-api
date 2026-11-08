package com.ondo.retail.notification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 알림 한 건 (MUL-155). 계약 {@code Notification}.
 *
 * <p>문구(title, body)는 도매가 만든 그대로다. 소매는 고치지 않는다 — 남의 소매처 정보가 없다는
 * 보장도 문구를 만든 쪽(도매)이 진다.
 *
 * <p>{@code type} 은 문자열이다. 계약이 "값이 늘 수 있다" 고 했으니 모르는 값도 그대로 내린다.
 *
 * @param body   없으면 null (칸은 있다)
 * @param readAt 안 읽었으면 null
 */
public record NotificationResponse(
        long id,
        String type,
        String title,
        String body,
        Target target,
        OffsetDateTime createdAt,
        OffsetDateTime readAt) {

    /**
     * 누르면 갈 곳. kind 별로 칸 하나만 내린다 — ORDER 면 orderId, BACKORDERS 면 wholesalerId
     * (계약 {@code additionalProperties: false}).
     */
    public record Target(
            String kind,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long orderId,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long wholesalerId) {
    }
}
