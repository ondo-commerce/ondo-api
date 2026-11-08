package com.ondo.retail.notification.dto;

import java.util.List;

/**
 * 알림 목록 한 장 (MUL-155). {@code { data: [...], meta: { nextCursor } }}.
 *
 * <p>오프셋 {@code PageResponse} 와 다르다 — 실시간으로 앞에 쌓이는 목록이라 전체 개수·쪽 번호가 없다.
 */
public record NotificationPageResponse(List<NotificationResponse> data, Meta meta) {

    /** @param nextCursor 다음 장 커서(불투명). 마지막 장이면 null */
    public record Meta(String nextCursor) {
    }
}
