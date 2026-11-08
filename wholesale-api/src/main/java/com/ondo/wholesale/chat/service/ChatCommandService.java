package com.ondo.wholesale.chat.service;

import com.ondo.wholesale.chat.domain.ChatGeneration;
import com.ondo.wholesale.chat.domain.ChatSender;
import com.ondo.wholesale.chat.dto.ChatInquiry;
import com.ondo.wholesale.chat.dto.ChatReadResult;
import com.ondo.wholesale.chat.dto.ChatSendResult;
import com.ondo.wholesale.chat.dto.MarkChatReadRequest;
import com.ondo.wholesale.chat.dto.SendChatMessageRequest;
import com.ondo.wholesale.chat.repository.ChatMessageRepository;
import com.ondo.wholesale.chat.repository.ChatMessageRepository.NewMessage;
import com.ondo.wholesale.chat.repository.ChatRoomRepository;
import com.ondo.wholesale.chat.repository.ChatRoomRepository.RoomRow;
import com.ondo.wholesale.chat.repository.ChatSendIdempotencyRepository;
import com.ondo.wholesale.common.error.ApiException;
import com.ondo.wholesale.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 도매 채팅 쓰기 2종 (MUL-158) — 사장 보내기 · 읽음.
 *
 * <p>둘 다 방 행을 FOR UPDATE 로 잡고 시작한다. 보내기는 그 잠금 아래서 sequence 를 채번하고,
 * 같은 키로 동시에 온 두 번째 요청은 첫 요청의 커밋을 기다렸다가 멱등 기록을 보고 replay 한다
 * (계약: "처음 요청이 아직 처리 중이면 끝난 뒤 같은 본문").
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ChatCommandService {

    static final int BODY_MAX = 1000;
    private static final Pattern UUID_SHAPE =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final ChatRoomRepository roomRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatSendIdempotencyRepository idempotencyRepository;
    private final ChatMessageAppender appender;
    private final ObjectMapper objectMapper;

    /** {@code replayed}가 참이면 같은 키 재요청 — 컨트롤러가 201 대신 200 을 내린다. */
    public record SendOutcome(ChatSendResult result, boolean replayed) {
    }

    /**
     * 사장 보내기(사람이 쓴 TEXT). 검사 순서: 키 형식(400) → 본문 형식(400) → 방(404) → 멱등(200 replay, 409)
     * → 글자 수(400) → 저장. 멱등을 글자 수보다 먼저 보는 이유 — 같은 키에 다른 본문이면 그 본문이 규칙에 맞든
     * 아니든 409 가 계약이다(목과 같은 순서).
     *
     * <p>자동 답은 소매 글에만 붙는다. 사장 글엔 만들 것이 없어 {@code generation=NONE}.
     */
    public SendOutcome send(long wholesalerId, long roomId, String idempotencyKey, SendChatMessageRequest request) {
        UUID key = parseKey(idempotencyKey);
        if (request == null || request.body() == null) {
            throw ApiException.validationFailed("body", "메시지를 입력해 주세요.");
        }
        RoomRow room = roomRepository.lockForWholesaler(roomId, wholesalerId)
                .orElseThrow(ChatQueryService::roomNotFound);

        String requestHash = fingerprint(request);
        Optional<ChatSendIdempotencyRepository.Recorded> known =
                idempotencyRepository.find(room.id(), ChatSender.WHOLESALER, key);
        if (known.isPresent()) {
            if (!known.get().requestHash().equals(requestHash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return new SendOutcome(objectMapper.readValue(known.get().responseBody(), ChatSendResult.class), true);
        }

        String text = request.body().strip();
        if (text.isEmpty()) {
            throw ApiException.validationFailed("body", "메시지를 입력해 주세요.");
        }
        if (text.codePointCount(0, text.length()) > BODY_MAX) {
            throw ApiException.validationFailed("body", "1,000자까지 보낼 수 있어요.");
        }
        ChatInquiry inquiry = request.inquiry();
        if (inquiry != null && inquiry.intent() == null) {
            throw ApiException.validationFailed("inquiry.intent", "필수입니다.");
        }

        ChatMessageAppender.Appended appended = appender.append(room,
                NewMessage.text(ChatSender.WHOLESALER, text, key, inquiry));
        // 내가 보낸 것까지는 읽은 것이다
        roomRepository.advanceWholesalerRead(room.id(), appended.message().sequence());

        ChatSendResult result = new ChatSendResult(appended.message(), List.of(), ChatGeneration.NONE);
        idempotencyRepository.save(room.id(), ChatSender.WHOLESALER, key, requestHash,
                appended.message().id(), objectMapper.writeValueAsString(result));
        return new SendOutcome(result, false);
    }

    /**
     * 읽음 위치 올리기. max(기존, 요청)으로 저장하고 미확인 수를 같은 트랜잭션에서 센다 —
     * 늦게 온 작은 값이 배지를 되살리지 않는다. 0 미만·maxSequence 초과는 400.
     */
    public ChatReadResult markRead(long wholesalerId, long roomId, MarkChatReadRequest request) {
        RoomRow room = roomRepository.lockForWholesaler(roomId, wholesalerId)
                .orElseThrow(ChatQueryService::roomNotFound);
        Long requested = request == null ? null : request.lastReadSequence();
        if (requested == null) {
            throw ApiException.validationFailed("lastReadSequence", "필수입니다.");
        }
        if (requested < 0 || requested > room.lastSequence()) {
            throw ApiException.validationFailed("lastReadSequence", "방에 없는 sequence 입니다.");
        }
        long saved = roomRepository.advanceWholesalerRead(room.id(), requested);
        int unread = messageRepository.countFromAfter(room.id(), ChatSender.RETAILER, saved);
        return new ChatReadResult(room.id(), saved, unread);
    }

    /** 계약 format uuid. {@code UUID.fromString} 은 "1-1-1-1-1" 도 받아서 모양을 따로 본다. */
    private static UUID parseKey(String idempotencyKey) {
        if (idempotencyKey == null || !UUID_SHAPE.matcher(idempotencyKey).matches()) {
            throw ApiException.validationFailed("Idempotency-Key", "Idempotency-Key 헤더(uuid)가 필요합니다.");
        }
        return UUID.fromString(idempotencyKey);
    }

    /**
     * "같은 본문" 지문 — 보낸 그대로의 body(공백을 지우기 전)와 inquiry 를 본다. notListed 가 빠진 것과 false 는 같다.
     */
    private String fingerprint(SendChatMessageRequest request) {
        ChatInquiry inquiry = request.inquiry();
        ChatInquiry normalized = inquiry == null ? null : new ChatInquiry(inquiry.intent(), inquiry.listingId(),
                inquiry.variantId(), inquiry.backorderId(), inquiry.label(), Boolean.TRUE.equals(inquiry.notListed()));
        String canonical = objectMapper.writeValueAsString(Arrays.asList(request.body(), normalized));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 은 JVM 표준 알고리즘이다", e);
        }
    }
}
