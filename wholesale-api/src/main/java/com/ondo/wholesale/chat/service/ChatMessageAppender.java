package com.ondo.wholesale.chat.service;

import com.ondo.wholesale.chat.domain.ChatMessageKind;
import com.ondo.wholesale.chat.domain.ChatSender;
import com.ondo.wholesale.chat.dto.ChatMessage;
import com.ondo.wholesale.chat.repository.ChatMessageRepository;
import com.ondo.wholesale.chat.repository.ChatMessageRepository.NewMessage;
import com.ondo.wholesale.chat.repository.ChatRoomRepository;
import com.ondo.wholesale.chat.repository.ChatRoomRepository.RoomRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방에 줄을 붙이는 단 하나의 입구 (MUL-158). 사장 보내기가 지금 쓰고, 소매 보내기·자동 답 줄(MUL-157)도 여기로 온다.
 *
 * <p>sequence 채번과 방 머리(마지막 메시지·활동 시각·열린 넘김)를 같은 자리에서 고친다 — 쓰는 곳이 여럿이면
 * 목록의 needsReply·openHandoff 가 메시지와 갈라진다. 방 잠금을 잡은 트랜잭션 안에서만 부른다.
 *
 * <p>열린 넘김 규칙(계약 openHandoff): HANDOFF 가 오면 열리고, WHOLESALER TEXT 가 오면 닫힌다.
 * AUTO_REPLY·CLARIFY 로는 닫히지 않는다 — 사장이 직접 써야 닫힌다.
 */
@Component
@RequiredArgsConstructor
public class ChatMessageAppender {

    private final ChatRoomRepository roomRepository;
    private final ChatMessageRepository messageRepository;

    /** {@code lockedRoom} 은 이 트랜잭션에서 FOR UPDATE 로 읽은 행이어야 한다. 붙인 뒤의 방은 다시 읽지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Appended append(RoomRow lockedRoom, NewMessage message) {
        long sequence = lockedRoom.lastSequence() + 1;
        long messageId = messageRepository.insert(lockedRoom.id(), sequence, message);
        ChatMessage saved = messageRepository.findById(messageId);
        Long openHandoff = nextOpenHandoff(lockedRoom.openHandoffSequence(), message, sequence);
        roomRepository.recordAppended(lockedRoom.id(), sequence, messageId, saved.sentAt(), openHandoff);
        return new Appended(saved, openHandoff);
    }

    /** 붙인 줄과 그 뒤 열린 넘김 sequence(없으면 null). */
    public record Appended(ChatMessage message, Long openHandoffSequence) {
    }

    static Long nextOpenHandoff(Long current, NewMessage message, long sequence) {
        if (message.kind() == ChatMessageKind.HANDOFF) {
            return sequence;
        }
        if (message.sender() == ChatSender.WHOLESALER && message.kind() == ChatMessageKind.TEXT) {
            return null;
        }
        return current;
    }
}
