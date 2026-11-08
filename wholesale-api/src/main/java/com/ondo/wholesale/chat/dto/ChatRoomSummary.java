package com.ondo.wholesale.chat.dto;

import com.ondo.wholesale.chat.domain.ChatMessageKind;
import com.ondo.wholesale.chat.domain.ChatSender;

import java.time.OffsetDateTime;

/** 방 목록 행 (MUL-158, 계약 {@code ChatRoomSummary}). 방은 (도매처, 소매처) 쌍에 1개이고 양쪽이 같은 roomId 를 본다. */
public record ChatRoomSummary(
        Long roomId,
        ChatCounterpart counterpart,
        ChatMessagePreview lastMessage,
        OffsetDateTime lastActivityAt,
        Integer unreadCount,
        Long myLastReadSequence,
        Boolean needsReply,
        Boolean openHandoff) {

    /** 대화 상대 사업체. 도매 응답이면 소매처(사업체 id, 계정 id 아님). */
    public record ChatCounterpart(Long id, String name) {
    }

    /** 목록 행의 마지막 메시지 한 줄. preview 는 본문 앞 80 코드 포인트, 줄바꿈은 공백. */
    public record ChatMessagePreview(
            Long sequence,
            ChatSender sender,
            ChatMessageKind kind,
            String preview,
            OffsetDateTime sentAt) {
    }
}
