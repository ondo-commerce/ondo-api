package com.ondo.wholesale.chat.dto;

import com.ondo.wholesale.common.response.ResponseEnvelope;

import java.util.List;

/** 메시지 조회 봉투 (MUL-158) — {@code {data, meta}}. data 는 항상 sequence 오름차순. */
public record ChatMessagePage(List<ChatMessage> data, ChatMessagePageMeta meta) implements ResponseEnvelope {

    /**
     * hasMore: afterSequence 면 "더 새 것이 남았다", beforeSequence 나 인자 없음이면 "더 오래된 것이 남았다".
     * maxSequence: 방의 지금 가장 큰 sequence(없으면 0). counterpartLastReadSequence: 상대가 한 번도 안 읽었으면 null.
     */
    public record ChatMessagePageMeta(Boolean hasMore, Long maxSequence, Long counterpartLastReadSequence) {
    }
}
