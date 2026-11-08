package com.ondo.wholesale.chat;

import com.ondo.wholesale.chat.dto.ChatMessagePage;
import com.ondo.wholesale.chat.dto.ChatReadResult;
import com.ondo.wholesale.chat.dto.ChatRoomDetail;
import com.ondo.wholesale.chat.dto.ChatRoomPage;
import com.ondo.wholesale.chat.dto.ChatSendResult;
import com.ondo.wholesale.chat.dto.MarkChatReadRequest;
import com.ondo.wholesale.chat.dto.SendChatMessageRequest;
import com.ondo.wholesale.chat.service.ChatCommandService;
import com.ondo.wholesale.chat.service.ChatQueryService;
import com.ondo.wholesale.security.WholesalePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 도매 채팅 5 op (MUL-158) — 원본 계약: ondo-web {@code packages/api/openapi/wholesale.json} 「10 채팅」 초안
 * ({@code x-ondo-draft}). 방·메시지의 원본은 도매 DB 다(docs/design/chat.md a). 자동 답 설정·문의 지표는 MUL-159.
 */
@Tag(name = "10 채팅")
@RestController
@RequestMapping("/api/wholesale/chat/rooms")
@RequiredArgsConstructor
public class ChatController {

    private final ChatQueryService chatQueryService;
    private final ChatCommandService chatCommandService;

    @Operation(operationId = "chatRooms", summary = "채팅방 목록 (커서)", description = """
            내 방 목록. 정렬 `lastActivityAt` 내림차순, 같으면 `roomId` 내림차순(고정). 커서 페이징이라 목록이
            도중에 바뀌어도 중복·누락이 없다. `lastActivityAt` 은 마지막 메시지 시각, 메시지가 없으면 방 생성 시각.
            `filter` UNREAD = unreadCount > 0, NEEDS_REPLY = needsReply. `q` 는 소매처 이름 부분 일치(영문 대소문자 무시).
            cursor 는 위치만 담는다 — 조건을 바꾸면 첫 장부터 받는다.

            에러: 400 `VALIDATION_FAILED`(잘못된 cursor · size · filter · q 50자 초과) / 401 / 403""")
    @GetMapping
    public ChatRoomPage chatRooms(@AuthenticationPrincipal WholesalePrincipal principal,
                                  @RequestParam(required = false) String cursor,
                                  @RequestParam(required = false, defaultValue = "30") Integer size,
                                  @RequestParam(required = false) String filter,
                                  @RequestParam(required = false) String q) {
        return chatQueryService.rooms(principal.wholesalerId(), cursor, size, filter, q);
    }

    @Operation(operationId = "chatRoom", summary = "채팅방 머리", description = """
            방 머리 1건 — 목록 행과 같은 칸 + `counterpartLastReadSequence`(소매가 한 번도 안 읽었으면 null).

            에러: 401 / 403 / 404 `RESOURCE_NOT_FOUND`(없는 방이거나 내 방이 아니다 — 남의 방도 404)""")
    @GetMapping("/{roomId}")
    public ChatRoomDetail chatRoom(@AuthenticationPrincipal WholesalePrincipal principal,
                                   @PathVariable Long roomId) {
        return chatQueryService.room(principal.wholesalerId(), roomId);
    }

    @Operation(operationId = "chatMessages", summary = "메시지 조회 (sequence 커서)", description = """
            data 는 항상 sequence 오름차순. `afterSequence=N`: N 다음부터 size 개(hasMore = 더 새 것이 남았다 →
            기다리지 말고 바로 이어 받는다). `beforeSequence=N`: N 바로 앞 size 개(hasMore = 더 오래된 것이 남았다).
            둘 다 없음: 최신 size 개. 둘 다 있음: 400. HANDOFF 줄은 `handoff{intent, reason, sourceSequence}` 를 싣는다.

            에러: 400 `VALIDATION_FAILED` / 401 / 403 / 404 `RESOURCE_NOT_FOUND`""")
    @GetMapping("/{roomId}/messages")
    public ChatMessagePage chatMessages(@AuthenticationPrincipal WholesalePrincipal principal,
                                        @PathVariable Long roomId,
                                        @RequestParam(required = false) Long afterSequence,
                                        @RequestParam(required = false) Long beforeSequence,
                                        @RequestParam(required = false, defaultValue = "50") Integer size) {
        return chatQueryService.messages(principal.wholesalerId(), roomId, afterSequence, beforeSequence, size);
    }

    @Operation(operationId = "sendChatMessage", summary = "메시지 보내기 (Idempotency-Key 필수)", description = """
            사람이 쓴 TEXT. 멱등 키 범위는 (방, 보낸 사업체). 새 키: 201, `message.clientMessageId` = 키.
            같은 키 + 같은 본문: 200 + 처음과 같은 본문. 같은 키 + 다른 본문: 409 `IDEMPOTENCY_KEY_REUSED`.
            사장 글엔 자동 답이 없어 `generatedMessages=[]`, `generation=NONE`.

            에러: 400 `VALIDATION_FAILED`(errors[].field = body | Idempotency-Key) / 401 / 403 / 404 /
            409 `IDEMPOTENCY_KEY_REUSED`""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "새로 만듦", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "같은 키 재요청(replay) — 처음 응답과 같은 본문",
                    useReturnTypeSchema = true)})
    @PostMapping("/{roomId}/messages")
    public ResponseEntity<ChatSendResult> sendChatMessage(@AuthenticationPrincipal WholesalePrincipal principal,
                                                          @PathVariable Long roomId,
                                                          @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                          @RequestBody SendChatMessageRequest request) {
        ChatCommandService.SendOutcome outcome =
                chatCommandService.send(principal.wholesalerId(), roomId, idempotencyKey, request);
        return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(outcome.result());
    }

    @Operation(operationId = "markChatRead", summary = "읽음 처리", description = """
            서버가 max(기존, 요청)으로 저장하고 unreadCount 를 같은 트랜잭션에서 센다 — 늦게 온 작은 값이 배지를
            되살리지 않는다. unreadCount = RETAILER 메시지 중 lastReadSequence 초과분(SYSTEM 은 세지 않는다).

            에러: 400 `VALIDATION_FAILED`(음수, 방의 maxSequence 초과) / 401 / 403 / 404""")
    @PostMapping("/{roomId}/read")
    public ChatReadResult markChatRead(@AuthenticationPrincipal WholesalePrincipal principal,
                                       @PathVariable Long roomId,
                                       @RequestBody MarkChatReadRequest request) {
        return chatCommandService.markRead(principal.wholesalerId(), roomId, request);
    }
}
