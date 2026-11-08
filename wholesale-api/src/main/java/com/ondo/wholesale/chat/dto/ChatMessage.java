package com.ondo.wholesale.chat.dto;

import com.ondo.wholesale.chat.domain.ChatMessageKind;
import com.ondo.wholesale.chat.domain.ChatSender;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 메시지 1건 (MUL-158, 계약 {@code ChatMessage} — 도매 응답 모양). 수정·삭제 없음.
 *
 * <p>{@code autoReply} 는 AUTO_REPLY·CLARIFY 에만, {@code handoff} 는 HANDOFF 에만 값이 있고 나머지는 null.
 * {@code handoff} 는 도매 응답에만 있는 칸이다 — 소매 응답(MUL-157)은 이 칸을 싣지 않는다.
 */
public record ChatMessage(
        Long id,
        Long roomId,
        Long sequence,
        UUID clientMessageId,
        ChatSender sender,
        ChatMessageKind kind,
        String body,
        OffsetDateTime sentAt,
        ChatInquiry inquiry,
        ChatAutoReply autoReply,
        ChatHandoff handoff) {
}
