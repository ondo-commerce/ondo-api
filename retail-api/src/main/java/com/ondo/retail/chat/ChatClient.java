package com.ondo.retail.chat;

import com.ondo.retail.chat.dto.ChatMessageResponses.ChatInquiry;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatMessagePage;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatReadResult;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatSendResult;
import com.ondo.retail.chat.dto.ChatRoomResponses.ChatRoomDetail;
import java.util.List;

/**
 * 도매에 있는 채팅방·메시지를 읽고 쓰는 통로 (MUL-157).
 *
 * <p>방·메시지·자동 답 판정의 원본은 도매 DB 다(docs/design/chat.md a 안) — 판정 원본(재고·미송·미수·포장)이 전부 도매에
 * 있고 정확 수량을 소매로 넘기지 않으려는 것이다. {@code com.ondo.retail.wholesale.chat.WholesaleChatAdapter} 가 구현한다.
 *
 * <p>모든 {@code retailerId} 는 세션에서 꺼낸 값이어야 한다. 남의 방·없는 방은
 * {@code BusinessException(RESOURCE_NOT_FOUND)}, 같은 키 다른 본문은 {@code IDEMPOTENCY_KEY_REUSED},
 * 도매가 거절한 입력은 {@code VALIDATION_FAILED}(칸 이름 그대로)로 던진다. 도매 장애는 {@code WholesaleApiException}.
 */
public interface ChatClient {

    /** 최근 활동순 최대 {@code limit} 개. {@code before} 는 직전 장 마지막 방(첫 장이면 null). */
    List<ChatRoomDetail> rooms(Long retailerId, ChatRoomCursor before, int limit);

    /** 방 열기(쌍에 1개, 있으면 그 방). listingId 는 방의 상품 문맥만 바꾼다. 없는 도매처는 RESOURCE_NOT_FOUND. */
    Opened open(Long retailerId, String retailerName, Long wholesalerId, Long listingId);

    ChatRoomDetail room(Long retailerId, Long roomId);

    ChatMessagePage messages(Long retailerId, Long roomId, Long afterSequence, Long beforeSequence, int size);

    /** 보내기 + 자동 답. {@code replayed} 면 같은 키 재요청(200). */
    Sent send(Long retailerId, Long roomId, String idempotencyKey, String body, ChatInquiry inquiry);

    ChatReadResult markRead(Long retailerId, Long roomId, long lastReadSequence);

    /** 헤더 배지의 채팅 몫 = 내 방 unreadCount 합. */
    int unreadChats(Long retailerId);

    record Opened(boolean created, ChatRoomDetail room) {
    }

    record Sent(ChatSendResult result, boolean replayed) {
    }
}
