package com.ondo.retail.chat;

import com.ondo.retail.chat.dto.ChatMessageResponses.ChatMessagePage;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatReadResult;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatSendResult;
import com.ondo.retail.chat.dto.ChatRoomResponses.ChatRoomDetail;
import com.ondo.retail.chat.dto.ChatRoomResponses.ChatRoomPage;
import com.ondo.retail.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 채팅 6 op (MUL-157). 웹 초안 계약({@code retail.json} 「채팅」, x-ondo-draft) 그대로다 — chatRooms · createChatRoom ·
 * chatRoom · chatMessages · sendChatMessage · markChatRead. 방·메시지·자동 답은 도매에 있다(docs/design/chat.md a 안).
 *
 * <p>소매처 id 는 <b>세션에서만</b> 꺼낸다. 남의 방은 403 이 아니라 404 다(존재를 숨긴다).
 *
 * <p>보내기의 403 {@code ACCOUNT_NOT_APPROVED}(승인 전 계정, 보안 설정이 낸다)와 404 는 <b>영구 거절</b>이다
 * (docs/design/chat.md d, D49 설계안) — 같은 키로 다시 보내도 늘 같다. 화면은 다시 보내기를 멈춘다.
 * 401 · 500 은 일시라 같은 키 재전송이 안전하다(200 replay). 도매에 닿지 못하면 500 {@code INTERNAL_ERROR}(계약에 502 가 없다).
 */
@Tag(name = "채팅", description = "도매처와의 1:1 방. 방·메시지·자동 답은 도매가 갖고 소매는 대신 부른다.")
@RestController
@RequestMapping("/api/retail/chat/rooms")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @Operation(operationId = "chatRooms", summary = "채팅방 목록 (커서)", description = """
            내 방 목록. 정렬 lastActivityAt 내림차순, 같으면 roomId 내림차순. `cursor` 는 직전 응답 `meta.nextCursor` 그대로,
            `size` 1~100 (기본 30).

            에러: 400 `VALIDATION_FAILED`(잘못된 cursor, size) / 401 / 403 / 500""")
    @GetMapping
    public ChatRoomPage chatRooms(@RequestParam(required = false) String cursor,
                                  @RequestParam(required = false) String size,
                                  Authentication authentication) {
        return chatService.rooms(retailerId(authentication), size, cursor);
    }

    @Operation(operationId = "createChatRoom", summary = "채팅방 열기 (있으면 그 방)", description = """
            도매처와의 방을 연다. (도매처, 소매처) 쌍에 1개라 이미 있으면 그 방을 200, 새로 만들면 201. 도매 쪽과 같은 roomId 다.
            listingId 는 방을 가르지 않고 방의 상품 문맥(자동 답이 '이 상품'으로 읽는 것)만 바꾼다(null 이면 지운다). 30분 지나면 문맥 없음.

            에러: 400 `VALIDATION_FAILED` / 401 / 403 / 404(없는 도매처) / 500""")
    @PostMapping
    public ResponseEntity<ApiResponse<ChatRoomDetail>> createChatRoom(@RequestBody Map<String, Object> body,
                                                                      Authentication authentication) {
        ChatClient.Opened opened = chatService.open(retailerId(authentication), body);
        return ResponseEntity.status(opened.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.of(opened.room()));
    }

    @Operation(operationId = "chatRoom", summary = "채팅방 머리", description = """
            방 머리 1건 — 목록 행과 같은 칸 + `counterpartLastReadSequence`(도매가 읽은 마지막 sequence).

            에러: 401 / 403 / 404 `RESOURCE_NOT_FOUND` / 500""")
    @GetMapping("/{roomId}")
    public ApiResponse<ChatRoomDetail> chatRoom(@PathVariable Long roomId, Authentication authentication) {
        return ApiResponse.of(chatService.room(retailerId(authentication), roomId));
    }

    @Operation(operationId = "chatMessages", summary = "메시지 조회 (sequence 커서)", description = """
            data 는 항상 sequence 오름차순. `afterSequence=N`: N 다음부터 size 개(hasMore = 더 새 것이 남았다).
            `beforeSequence=N`: N 바로 앞 size 개(hasMore = 더 오래된 것이 남았다). 둘 다 없음: 최신 size 개. 둘 다 있음: 400.
            size 1~100 (기본 50). 넘김 줄(HANDOFF)은 이유 없이 kind 만, 재고 답은 구간·주문 가능만 싣는다.

            에러: 400 `VALIDATION_FAILED` / 401 / 403 / 404 / 500""")
    @GetMapping("/{roomId}/messages")
    public ChatMessagePage chatMessages(@PathVariable Long roomId,
                                        @RequestParam(required = false) String afterSequence,
                                        @RequestParam(required = false) String beforeSequence,
                                        @RequestParam(required = false) String size,
                                        Authentication authentication) {
        return chatService.messages(retailerId(authentication), roomId, afterSequence, beforeSequence, size);
    }

    @Operation(operationId = "sendChatMessage", summary = "메시지 보내기 (Idempotency-Key 필수)", description = """
            사람이 쓴 TEXT. 멱등 키 범위는 (방, 보낸 사업체). 새 키: 201 / 같은 키 + 같은 본문: 200 + 처음과 같은 본문
            (자동 답을 다시 만들지 않는다) / 같은 키 + 다른 본문: 409 `IDEMPOTENCY_KEY_REUSED`.
            자동 답·되묻기·넘김 줄은 `generatedMessages` 로 함께 온다(`generation: DONE`). 붙일 줄이 없으면 `[]`, `NONE`.

            에러: 400 `VALIDATION_FAILED`(errors[].field = body | Idempotency-Key) / 401 / 403 `ACCOUNT_NOT_APPROVED`(영구) /
            404(영구) / 409 `IDEMPOTENCY_KEY_REUSED` / 500""")
    @PostMapping("/{roomId}/messages")
    public ResponseEntity<ApiResponse<ChatSendResult>> sendChatMessage(
            @PathVariable Long roomId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> body,
            Authentication authentication) {
        ChatClient.Sent sent = chatService.send(retailerId(authentication), roomId, idempotencyKey, body);
        return ResponseEntity.status(sent.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ApiResponse.of(sent.result()));
    }

    @Operation(operationId = "markChatRead", summary = "읽음 처리", description = """
            서버가 max(기존, 요청)으로 저장하고 unreadCount 를 같은 트랜잭션에서 센다. unreadCount = 도매 쪽이 보낸 메시지
            (TEXT, AUTO_REPLY, CLARIFY) 중 lastReadSequence 초과분. SYSTEM(HANDOFF)은 세지 않는다.

            에러: 400 `VALIDATION_FAILED`(음수, 방의 maxSequence 초과) / 401 / 403 / 404 / 500""")
    @PostMapping("/{roomId}/read")
    public ApiResponse<ChatReadResult> markChatRead(@PathVariable Long roomId,
                                                    @RequestBody(required = false) Map<String, Object> body,
                                                    Authentication authentication) {
        return ApiResponse.of(chatService.markRead(retailerId(authentication), roomId, body));
    }

    private static Long retailerId(Authentication authentication) {
        return Long.valueOf(authentication.getName());
    }
}
