package com.ondo.wholesale.notification.dto;

import com.ondo.wholesale.common.response.ResponseEnvelope;

import java.util.List;

/**
 * 알림 목록 봉투 (MUL-154) — 계약의 {@code meta} 가 오프셋 PageMeta 가 아니라 커서
 * ({@code CursorPageMeta})라 전용 봉투를 쓴다. 실시간으로 앞이 늘어나는 목록이라 전체 개수·쪽 번호가 없다.
 */
public record NotificationPageResponse(List<NotificationResponse> data, CursorPageMeta meta)
        implements ResponseEnvelope {

    /** 다음 장 커서. 마지막 장이면 null — 칸은 빠지지 않는다(required). */
    public record CursorPageMeta(String nextCursor) {}
}
