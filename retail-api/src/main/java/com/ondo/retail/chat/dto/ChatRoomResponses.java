package com.ondo.retail.chat.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 채팅방 응답 모양 (MUL-157). 웹 초안 계약 {@code ChatRoomSummary} · {@code ChatRoomDetail} 그대로다.
 * 방은 (도매처, 소매처) 쌍에 1개이고 양쪽이 같은 roomId 를 본다.
 */
public final class ChatRoomResponses {

    private ChatRoomResponses() {
    }

    /** 대화 상대 도매처(사업체 id, 상호). */
    public record ChatCounterpart(Long id, String name) {
    }

    /** 목록 행의 마지막 메시지 한 줄. preview 는 본문 앞 80 코드 포인트(도매가 자른다). */
    public record ChatMessagePreview(Long sequence, String sender, String kind, String preview,
                                     OffsetDateTime sentAt) {
    }

    /** 계약 {@code ChatRoomSummary}. */
    public record ChatRoomSummary(
            Long roomId,
            ChatCounterpart counterpart,
            ChatMessagePreview lastMessage,
            OffsetDateTime lastActivityAt,
            Integer unreadCount,
            Long myLastReadSequence,
            Boolean needsReply,
            Boolean openHandoff) {
    }

    /** 계약 {@code ChatRoomDetail} — Summary 와 같은 칸 + 상대(도매) 읽음 위치. */
    public record ChatRoomDetail(
            Long roomId,
            ChatCounterpart counterpart,
            ChatMessagePreview lastMessage,
            OffsetDateTime lastActivityAt,
            Integer unreadCount,
            Long myLastReadSequence,
            Boolean needsReply,
            Boolean openHandoff,
            Long counterpartLastReadSequence) {

        public ChatRoomSummary summary() {
            return new ChatRoomSummary(roomId, counterpart, lastMessage, lastActivityAt, unreadCount,
                    myLastReadSequence, needsReply, openHandoff);
        }
    }

    /** 방 목록 한 장 — {@code {data, meta: {nextCursor}}}. */
    public record ChatRoomPage(List<ChatRoomSummary> data, Meta meta) {

        /** @param nextCursor 다음 장 커서(불투명). 마지막 장이면 null — 칸은 늘 나간다 */
        public record Meta(String nextCursor) {
        }
    }
}
