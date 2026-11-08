package com.ondo.wholesale.chat.service;

import com.ondo.wholesale.chat.domain.ChatGeneration;
import com.ondo.wholesale.chat.domain.ChatMessageKind;
import com.ondo.wholesale.chat.domain.ChatSender;
import com.ondo.wholesale.chat.dto.ChatInquiry;
import com.ondo.wholesale.chat.dto.ChatMessage;
import com.ondo.wholesale.chat.dto.ChatMessagePage.ChatMessagePageMeta;
import com.ondo.wholesale.chat.dto.ChatReadResult;
import com.ondo.wholesale.chat.dto.ChatRoomSummary.ChatCounterpart;
import com.ondo.wholesale.chat.dto.ChatRoomSummary.ChatMessagePreview;
import com.ondo.wholesale.chat.dto.ChatSendResult;
import com.ondo.wholesale.chat.dto.MarkChatReadRequest;
import com.ondo.wholesale.chat.dto.SendChatMessageRequest;
import com.ondo.wholesale.chat.repository.ChatMessageRepository;
import com.ondo.wholesale.chat.repository.ChatMessageRepository.NewMessage;
import com.ondo.wholesale.chat.repository.ChatRoomRepository;
import com.ondo.wholesale.chat.repository.ChatRoomRepository.RetailSummaryRow;
import com.ondo.wholesale.chat.repository.ChatRoomRepository.RoomRow;
import com.ondo.wholesale.chat.repository.ChatSendIdempotencyRepository;
import com.ondo.wholesale.common.error.ApiException;
import com.ondo.wholesale.common.error.ErrorCode;
import com.ondo.wholesale.common.error.ResourceNotFoundException;
import com.ondo.wholesale.retailgateway.dto.RetailChatDtos.MessagePage;
import com.ondo.wholesale.retailgateway.dto.RetailChatDtos.OpenResponse;
import com.ondo.wholesale.retailgateway.dto.RetailChatDtos.SendResponse;
import com.ondo.wholesale.retailgateway.dto.RetailChatRoomResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 소매 채팅 6 op + 배지의 도매 쪽 (MUL-157, docs/design/chat.md a 안). 소매 백엔드가 {@code /api/retail-gateway/chat/*}
 * 로 부른다. 방·메시지의 원본이 도매 DB 라 계산은 여기서 하고, 응답은 <b>소매 모양</b>으로 바꿔 내보낸다
 * ({@link RetailChatProjection}) — 정확 수량과 넘김 이유가 도매 밖으로 나가지 않는다.
 *
 * <p>모든 op 는 {@code retailerId}(소매 세션에서 꺼낸 값)로 방을 거른다. 남의 방은 없는 방과 구별되지 않는다(404).
 *
 * <p>계산 규칙(소매 계약): unreadCount = WHOLESALER 가 보낸 것(사람 답·자동 답·되묻기) 중 내 읽음 위치 초과분 —
 * SYSTEM(HANDOFF)은 세지 않는다. myLastReadSequence 는 안 읽었으면 0. needsReply·openHandoff 는 도매와 같은 뜻.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RetailChatService {

    private final ChatRoomRepository roomRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatSendIdempotencyRepository idempotencyRepository;
    private final ChatMessageAppender appender;
    private final ChatAutoReplyGenerator autoReply;
    private final RetailChatProjection projection;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbc;

    /** {@code replayed} 가 참이면 같은 키 재요청 — 200, 아니면 201. */
    public record SendOutcome(SendResponse result, boolean replayed) {
    }

    /* --- 조회 ---------------------------------------------------------------- */

    /** 방 목록 한 장 — (lastActivityAt desc, roomId desc) 키셋. 커서 문자열은 소매가 만든다. */
    @Transactional(readOnly = true)
    public List<RetailChatRoomResponse> rooms(long retailerId, OffsetDateTime beforeAt, Long beforeId, int limit) {
        return roomRepository.findRetailSummaries(retailerId, beforeAt, beforeId, limit).stream()
                .map(RetailChatService::roomOf)
                .toList();
    }

    @Transactional(readOnly = true)
    public RetailChatRoomResponse room(long retailerId, long roomId) {
        return roomRepository.findRetailSummary(roomId, retailerId)
                .map(RetailChatService::roomOf)
                .orElseThrow(ChatQueryService::roomNotFound);
    }

    /** 메시지 한 장. 규칙은 도매 chatMessages 와 같다(after → 더 새 것, before·없음 → 더 오래된 것). */
    @Transactional(readOnly = true)
    public MessagePage messages(long retailerId, long roomId, Long afterSequence, Long beforeSequence, int size) {
        RoomRow room = roomRepository.findForRetailer(roomId, retailerId)
                .orElseThrow(ChatQueryService::roomNotFound);
        if (size < 1 || size > ChatQueryService.SIZE_MAX) {
            throw ApiException.validationFailed("size", "size 는 1~" + ChatQueryService.SIZE_MAX + " 이어야 합니다.");
        }
        if (afterSequence != null && beforeSequence != null) {
            throw ApiException.validationFailed("afterSequence", "afterSequence 와 beforeSequence 는 같이 쓸 수 없습니다.");
        }
        if (afterSequence != null && afterSequence < 0) {
            throw ApiException.validationFailed("afterSequence", "0 이상이어야 합니다.");
        }
        if (beforeSequence != null && beforeSequence < 1) {
            throw ApiException.validationFailed("beforeSequence", "1 이상이어야 합니다.");
        }
        List<ChatMessage> rows = afterSequence != null
                ? messageRepository.findAfter(roomId, afterSequence, size + 1)
                : messageRepository.findBefore(roomId, beforeSequence, size + 1);
        boolean hasMore = rows.size() > size;
        List<ChatMessage> data = !hasMore ? rows
                : afterSequence != null ? rows.subList(0, size) : rows.subList(1, rows.size());
        return new MessagePage(projection.messages(data),
                new ChatMessagePageMeta(hasMore, room.lastSequence(), room.wholesalerLastReadSequence()));
    }

    /** 소매 헤더 배지의 채팅 몫. 방 목록 unreadCount 의 합과 같은 정의다. */
    @Transactional(readOnly = true)
    public int unreadChats(long retailerId) {
        return roomRepository.retailUnreadTotal(retailerId);
    }

    /* --- 쓰기 ---------------------------------------------------------------- */

    /**
     * 방 열기(멱등, 키 불필요). 쌍이 있으면 그 방, 없으면 새로. listingId 는 방을 가르지 않고 방의 상품 문맥만 바꾼다
     * (null 이면 지운다, 이미 있는 방도). 없는 도매처(승인 전·탈퇴 포함)는 404.
     */
    public OpenResponse open(long retailerId, String retailerName, long wholesalerId, Long listingId) {
        boolean exists = jdbc.sql("""
                        select exists (select 1 from wholesale.wholesaler
                                        where id = :id and deleted_at is null and approval_status = 'APPROVED')
                        """)
                .param("id", wholesalerId)
                .query(Boolean.class)
                .single();
        if (!exists) {
            throw new ResourceNotFoundException("도매처를 찾을 수 없습니다.");
        }
        ChatRoomRepository.Opened opened = roomRepository.openForRetailer(wholesalerId, retailerId, retailerName);
        OffsetDateTime now = jdbc.sql("select clock_timestamp()").query(OffsetDateTime.class).single();
        roomRepository.setContext(opened.room().id(), listingId, now);
        return new OpenResponse(opened.created(), room(retailerId, opened.room().id()));
    }

    /**
     * 소매 보내기. 검사 순서는 도매 보내기·웹 목과 같다: 키 형식(400) → 본문 형식(400) → 방(404) → 멱등(200 replay, 409)
     * → 글자 수(400) → 저장 → 자동 답.
     *
     * <p>자동 답은 같은 트랜잭션·같은 방 잠금 아래서 만든다. replay 는 처음 결과를 그대로 돌려준다 — 자동 답을 다시
     * 만들지 않고, id·sequence·checkedAt 도 처음 값이다. 멱등 기록에는 도매 모양 결과를 남기고 내보낼 때마다 소매 모양으로 바꾼다.
     */
    public SendOutcome send(long retailerId, long roomId, String idempotencyKey, SendChatMessageRequest request) {
        UUID key = ChatCommandService.parseKey(idempotencyKey);
        if (request == null || request.body() == null) {
            throw ApiException.validationFailed("body", "메시지를 입력해 주세요.");
        }
        RoomRow room = roomRepository.lockForRetailer(roomId, retailerId)
                .orElseThrow(ChatQueryService::roomNotFound);

        String requestHash = ChatCommandService.fingerprint(objectMapper, request);
        Optional<ChatSendIdempotencyRepository.Recorded> known =
                idempotencyRepository.find(room.id(), ChatSender.RETAILER, key);
        if (known.isPresent()) {
            if (!known.get().requestHash().equals(requestHash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return new SendOutcome(projection.send(
                    objectMapper.readValue(known.get().responseBody(), ChatSendResult.class)), true);
        }

        String text = request.body().strip();
        if (text.isEmpty()) {
            throw ApiException.validationFailed("body", "메시지를 입력해 주세요.");
        }
        if (text.codePointCount(0, text.length()) > ChatCommandService.BODY_MAX) {
            throw ApiException.validationFailed("body", "1,000자까지 보낼 수 있어요.");
        }
        ChatInquiry inquiry = request.inquiry();
        if (inquiry != null && inquiry.intent() == null) {
            throw ApiException.validationFailed("inquiry.intent", "필수입니다.");
        }

        ChatMessageAppender.Appended appended = appender.append(room,
                NewMessage.text(ChatSender.RETAILER, text, key, inquiry));
        // 내가 보낸 것까지는 읽은 것이다
        roomRepository.advanceRetailerRead(room.id(), appended.message().sequence());
        List<ChatMessage> generated = autoReply.reply(room, appended.roomAfter(room), appended.message());

        ChatSendResult result = new ChatSendResult(appended.message(), generated,
                generated.isEmpty() ? ChatGeneration.NONE : ChatGeneration.DONE);
        idempotencyRepository.save(room.id(), ChatSender.RETAILER, key, requestHash,
                appended.message().id(), objectMapper.writeValueAsString(result));
        return new SendOutcome(projection.send(result), false);
    }

    /** 읽음. max(기존, 요청)으로 저장하고 미확인 수(WHOLESALER 만)를 같은 트랜잭션에서 센다. 0 미만·maxSequence 초과는 400. */
    public ChatReadResult markRead(long retailerId, long roomId, MarkChatReadRequest request) {
        RoomRow room = roomRepository.lockForRetailer(roomId, retailerId)
                .orElseThrow(ChatQueryService::roomNotFound);
        Long requested = request == null ? null : request.lastReadSequence();
        if (requested == null) {
            throw ApiException.validationFailed("lastReadSequence", "필수입니다.");
        }
        if (requested < 0 || requested > room.lastSequence()) {
            throw ApiException.validationFailed("lastReadSequence", "방에 없는 sequence 입니다.");
        }
        long saved = roomRepository.advanceRetailerRead(room.id(), requested);
        int unread = messageRepository.countFromAfter(room.id(), ChatSender.WHOLESALER, saved);
        return new ChatReadResult(room.id(), saved, unread);
    }

    /* --- 조립 ---------------------------------------------------------------- */

    static RetailChatRoomResponse roomOf(RetailSummaryRow row) {
        RoomRow room = row.room();
        ChatMessagePreview last = row.lastMessageSequence() == null ? null : new ChatMessagePreview(
                row.lastMessageSequence(), row.lastMessageSender(), row.lastMessageKind(),
                ChatQueryService.preview(row.lastMessageBody()), utc(row.lastMessageSentAt()));
        boolean openHandoff = room.openHandoffSequence() != null;
        boolean lastIsRetailerText = last != null
                && last.sender() == ChatSender.RETAILER && last.kind() == ChatMessageKind.TEXT;
        return new RetailChatRoomResponse(
                room.id(),
                new ChatCounterpart(room.wholesalerId(), row.wholesalerName()),
                last,
                utc(room.lastActivityAt()),
                row.unreadCount(),
                room.retailerLastReadSequence() == null ? 0L : room.retailerLastReadSequence(),
                openHandoff || lastIsRetailerText,
                openHandoff,
                room.wholesalerLastReadSequence());
    }

    private static OffsetDateTime utc(OffsetDateTime time) {
        return time == null ? null : time.withOffsetSameInstant(ZoneOffset.UTC);
    }
}
