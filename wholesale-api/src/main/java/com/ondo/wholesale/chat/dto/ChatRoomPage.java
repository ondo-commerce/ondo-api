package com.ondo.wholesale.chat.dto;

import com.ondo.wholesale.common.response.ResponseEnvelope;

import java.util.List;

/**
 * 방 목록 봉투 (MUL-158) — {@code {data, meta: {nextCursor}}}. 오프셋 PageMeta 가 아니라 커서 메타다
 * (실시간으로 순서가 바뀌는 목록이라 전체 개수·쪽 번호가 없다).
 */
public record ChatRoomPage(List<ChatRoomSummary> data, CursorPageMeta meta) implements ResponseEnvelope {

    /** 다음 장 커서(불투명 문자열). 마지막 장이면 null — 칸은 늘 나간다. */
    public record CursorPageMeta(String nextCursor) {
    }
}
