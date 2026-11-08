package com.ondo.wholesale.retailgateway.dto;

import com.ondo.wholesale.chat.dto.ChatRoomSummary.ChatCounterpart;
import com.ondo.wholesale.chat.dto.ChatRoomSummary.ChatMessagePreview;

import java.time.OffsetDateTime;

/**
 * 소매가 보는 방 한 줄 (MUL-157, 소매 계약 {@code ChatRoomDetail} — 목록이면 소매가 마지막 칸을 버리고 {@code ChatRoomSummary}).
 *
 * @param counterpart                 도매처(사업체 id, 상호)
 * @param unreadCount                 도매 쪽이 보낸 것(사람 답·자동 답·되묻기) 중 내 읽음 위치 초과분. SYSTEM 제외
 * @param myLastReadSequence          내가 읽은 마지막 sequence. 안 읽었으면 0
 * @param counterpartLastReadSequence 도매가 읽은 마지막 sequence(도매는 안 읽었으면 0)
 */
public record RetailChatRoomResponse(
        Long roomId,
        ChatCounterpart counterpart,
        ChatMessagePreview lastMessage,
        OffsetDateTime lastActivityAt,
        Integer unreadCount,
        Long myLastReadSequence,
        Boolean needsReply,
        Boolean openHandoff,
        Long counterpartLastReadSequence) {
}
