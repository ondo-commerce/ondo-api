package com.ondo.wholesale.chat.dto;

import com.ondo.wholesale.chat.dto.ChatRoomSummary.ChatCounterpart;
import com.ondo.wholesale.chat.dto.ChatRoomSummary.ChatMessagePreview;

import java.time.OffsetDateTime;

/**
 * 방 머리 (MUL-158, 계약 {@code ChatRoomDetail}). {@link ChatRoomSummary} 와 같은 칸 + 상대 읽음 위치.
 * {@code counterpartLastReadSequence} 는 상대가 한 번도 안 읽었으면 null.
 */
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

    public static ChatRoomDetail of(ChatRoomSummary s, Long counterpartLastReadSequence) {
        return new ChatRoomDetail(s.roomId(), s.counterpart(), s.lastMessage(), s.lastActivityAt(),
                s.unreadCount(), s.myLastReadSequence(), s.needsReply(), s.openHandoff(),
                counterpartLastReadSequence);
    }
}
