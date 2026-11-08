package com.ondo.wholesale.chat.dto;

/** 읽음 위치 (MUL-158, 계약 {@code MarkChatReadRequest}). 0 이상, 방의 maxSequence 이하. */
public record MarkChatReadRequest(Long lastReadSequence) {
}
