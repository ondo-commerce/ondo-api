package com.ondo.wholesale.retailgateway;

import com.ondo.wholesale.chat.dto.ChatReadResult;
import com.ondo.wholesale.chat.dto.MarkChatReadRequest;
import com.ondo.wholesale.chat.dto.SendChatMessageRequest;
import com.ondo.wholesale.chat.service.RetailChatService;
import com.ondo.wholesale.common.error.ApiException;
import com.ondo.wholesale.common.response.ApiResponse;
import com.ondo.wholesale.retailgateway.dto.RetailChatDtos.MessagePage;
import com.ondo.wholesale.retailgateway.dto.RetailChatDtos.OpenRequest;
import com.ondo.wholesale.retailgateway.dto.RetailChatDtos.OpenResponse;
import com.ondo.wholesale.retailgateway.dto.RetailChatDtos.SendResponse;
import com.ondo.wholesale.retailgateway.dto.RetailChatDtos.UnreadResponse;
import com.ondo.wholesale.retailgateway.dto.RetailChatRoomResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 소매 채팅 (MUL-157) — 소매 백엔드가 부른다. 설계: docs/design/chat.md a 안.
 *
 * <p>방·메시지·판정의 원본은 도매 DB 다. 소매의 {@code /api/retail/chat/*} 6 op 는 여기를 부르는 얇은 프록시다.
 * 응답은 <b>소매 모양</b>이다 — 재고 facts 에 정확 수량이 없고 메시지에 넘김 이유가 없다.
 *
 * <p>{@code retailerId} 는 소매 세션에서 꺼낸 값이다. 남의 방은 404(존재를 숨긴다). 프론트 커서 문자열·size 범위는
 * 소매가 검증하고 여기는 keyset 두 값을 받는다(알림 게이트웨이와 같은 이유로 시각은 epoch 마이크로초).
 */
@Tag(name = "08 소매접점")
@RestController
@RequestMapping("/api/retail-gateway/chat")
@RequiredArgsConstructor
public class RetailGatewayChatController {

    /** 소매가 다음 장 여부를 알려고 size(≤100) + 1 로 부른다. */
    private static final int MAX_LIMIT = 101;

    private final RetailChatService service;

    @Operation(summary = "소매 채팅방 목록 (소매 백엔드 → 도매)", description = """
            그 소매처 방만, `lastActivityAt desc, roomId desc`. keyset — `beforeMicros`(epoch µs)·`beforeId` 는 직전 장
            마지막 행 값이고 둘이 같이 온다. 첫 장은 둘 다 생략.

            에러: 400 `VALIDATION_FAILED`(limit 범위 밖, keyset 한쪽만 옴)""")
    @GetMapping("/rooms")
    public ApiResponse<List<RetailChatRoomResponse>> rooms(@RequestParam Long retailerId,
                                                           @RequestParam(defaultValue = "31") int limit,
                                                           @RequestParam(required = false) Long beforeMicros,
                                                           @RequestParam(required = false) Long beforeId) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.validationFailed("limit", "limit 는 1 이상 " + MAX_LIMIT + " 이하다.");
        }
        if ((beforeMicros == null) != (beforeId == null)) {
            throw ApiException.validationFailed("beforeId", "beforeMicros 와 beforeId 는 같이 온다.");
        }
        return ApiResponse.of(service.rooms(retailerId, beforeMicros == null ? null : fromMicros(beforeMicros),
                beforeId, limit));
    }

    @Operation(summary = "소매 채팅방 열기 (소매 백엔드 → 도매)", description = """
            (도매처, 소매처) 쌍에 방 1개. 있으면 그 방(`created: false`), 없으면 새로(`created: true`). listingId 는 방의
            상품 문맥만 바꾼다(null 이면 지운다). retailerName 은 방에 스냅샷으로 남는다.

            에러: 400 `VALIDATION_FAILED` / 404 `RESOURCE_NOT_FOUND`(없는 도매처)""")
    @PostMapping("/rooms")
    public OpenResponse open(@RequestBody OpenRequest request) {
        if (request.retailerId() == null) {
            throw ApiException.validationFailed("retailerId", "retailerId 가 필요하다.");
        }
        if (request.wholesalerId() == null) {
            throw ApiException.validationFailed("wholesalerId", "wholesalerId 가 필요하다.");
        }
        if (request.retailerName() == null || request.retailerName().isBlank()) {
            throw ApiException.validationFailed("retailerName", "retailerName 이 필요하다.");
        }
        return service.open(request.retailerId(), request.retailerName().strip(), request.wholesalerId(),
                request.listingId());
    }

    @Operation(summary = "소매 채팅방 머리 (소매 백엔드 → 도매)", description = "에러: 404 `RESOURCE_NOT_FOUND`")
    @GetMapping("/rooms/{roomId}")
    public RetailChatRoomResponse room(@PathVariable Long roomId, @RequestParam Long retailerId) {
        return service.room(retailerId, roomId);
    }

    @Operation(summary = "소매 메시지 조회 (소매 백엔드 → 도매)", description = """
            소매 chatMessages 와 같은 규칙. 메시지는 소매 모양(넘김 이유·정확 수량 없음).

            에러: 400 `VALIDATION_FAILED` / 404 `RESOURCE_NOT_FOUND`""")
    @GetMapping("/rooms/{roomId}/messages")
    public MessagePage messages(@PathVariable Long roomId, @RequestParam Long retailerId,
                                @RequestParam(required = false) Long afterSequence,
                                @RequestParam(required = false) Long beforeSequence,
                                @RequestParam(defaultValue = "50") int size) {
        return service.messages(retailerId, roomId, afterSequence, beforeSequence, size);
    }

    @Operation(summary = "소매 보내기 + 자동 답 (소매 백엔드 → 도매)", description = """
            소매처가 쓴 TEXT 를 저장하고 같은 트랜잭션에서 자동 답·되묻기·넘김 줄을 만들어 `generatedMessages` 로 내린다.
            멱등 키 범위는 (방, RETAILER). 새 키 201 / 같은 키 + 같은 본문 200(처음과 같은 본문, 자동 답을 다시 만들지 않는다) /
            같은 키 + 다른 본문 409.

            에러: 400 `VALIDATION_FAILED`(errors[].field = body | Idempotency-Key) / 404 / 409 `IDEMPOTENCY_KEY_REUSED`""")
    @PostMapping("/rooms/{roomId}/messages")
    public ResponseEntity<SendResponse> send(@PathVariable Long roomId, @RequestParam Long retailerId,
                                             @RequestHeader(value = "Idempotency-Key", required = false)
                                             String idempotencyKey,
                                             @RequestBody SendChatMessageRequest request) {
        RetailChatService.SendOutcome outcome = service.send(retailerId, roomId, idempotencyKey, request);
        return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(outcome.result());
    }

    @Operation(summary = "소매 읽음 (소매 백엔드 → 도매)", description = """
            max(기존, 요청)으로 저장. unreadCount = WHOLESALER 메시지 중 lastReadSequence 초과분(SYSTEM 제외).

            에러: 400 `VALIDATION_FAILED`(음수, maxSequence 초과) / 404""")
    @PostMapping("/rooms/{roomId}/read")
    public ChatReadResult read(@PathVariable Long roomId, @RequestParam Long retailerId,
                               @RequestBody MarkChatReadRequest request) {
        return service.markRead(retailerId, roomId, request);
    }

    @Operation(summary = "소매 채팅 배지 (소매 백엔드 → 도매)", description = "그 소매처 방들의 unreadCount 합.")
    @GetMapping("/unread-count")
    public UnreadResponse unreadCount(@RequestParam Long retailerId) {
        return new UnreadResponse(service.unreadChats(retailerId));
    }

    private static OffsetDateTime fromMicros(long micros) {
        Instant instant = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                Math.floorMod(micros, 1_000_000L) * 1_000L);
        return instant.atOffset(ZoneOffset.UTC);
    }
}
