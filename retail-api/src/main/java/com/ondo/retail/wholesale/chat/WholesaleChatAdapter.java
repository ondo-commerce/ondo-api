package com.ondo.retail.wholesale.chat;

import static com.ondo.retail.wholesale.WholesaleCall.call;

import com.ondo.retail.chat.ChatClient;
import com.ondo.retail.chat.ChatRoomCursor;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatInquiry;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatMessagePage;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatReadResult;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatSendResult;
import com.ondo.retail.chat.dto.ChatRoomResponses.ChatRoomDetail;
import com.ondo.retail.common.error.BusinessException;
import com.ondo.retail.common.error.ErrorCode;
import com.ondo.retail.common.error.ErrorResponse.FieldError;
import com.ondo.retail.wholesale.WholesaleApiException;
import com.ondo.retail.wholesale.chat.dto.WholesaleChatDtos.OpenRequest;
import com.ondo.retail.wholesale.chat.dto.WholesaleChatDtos;
import com.ondo.retail.wholesale.chat.dto.WholesaleChatDtos.ReadRequest;
import com.ondo.retail.wholesale.chat.dto.WholesaleChatDtos.SendRequest;
import com.ondo.retail.wholesale.dto.WholesaleEnvelope;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * 도매 채팅 게이트웨이를 소매 말로 옮긴다 (MUL-157).
 *
 * <p>도매의 4xx 중 계약에 있는 셋은 소매 코드로 그대로 옮긴다 — 정상 흐름이라서다:
 * <ul>
 *   <li>404 → {@code RESOURCE_NOT_FOUND}. 소매가 방 존재를 따로 판단하지 않는다(남의 방도 404, docs/design/chat.md d)</li>
 *   <li>409 {@code IDEMPOTENCY_KEY_REUSED} → 같은 코드</li>
 *   <li>400 {@code VALIDATION_FAILED} → 같은 코드, 칸 이름(errors[].field) 그대로 — 빈 본문·1,000자 초과·읽음 위치 초과</li>
 * </ul>
 * 그 밖(연결 실패·타임아웃·5xx·다른 4xx)은 {@code WholesaleApiException} → 500 {@code INTERNAL_ERROR}. 계약에 502 가 없다.
 *
 * <p>서버가 재시도하지 않는다. 보내기는 화면이 같은 키로 다시 보내면 도매가 replay 한다.
 */
@Component
@RequiredArgsConstructor
public class WholesaleChatAdapter implements ChatClient {

    private final WholesaleChatApi api;

    @Override
    public List<ChatRoomDetail> rooms(Long retailerId, ChatRoomCursor before, int limit) {
        Long beforeMicros = before == null ? null : before.epochMicros();
        Long beforeId = before == null ? null : before.roomId();
        return call("채팅방 목록", () -> api.rooms(retailerId, limit, beforeMicros, beforeId)).data();
    }

    @Override
    public Opened open(Long retailerId, String retailerName, Long wholesalerId, Long listingId) {
        WholesaleChatDtos.Opened opened = translated("채팅방 열기",
                () -> api.open(new OpenRequest(retailerId, retailerName, wholesalerId, listingId))).data();
        return new Opened(Boolean.TRUE.equals(opened.created()), opened.room());
    }

    @Override
    public ChatRoomDetail room(Long retailerId, Long roomId) {
        return translated("채팅방 머리", () -> api.room(roomId, retailerId)).data();
    }

    @Override
    public ChatMessagePage messages(Long retailerId, Long roomId, Long afterSequence, Long beforeSequence,
                                    int size) {
        return translated("채팅 메시지", () -> api.messages(roomId, retailerId, afterSequence, beforeSequence, size));
    }

    @Override
    public Sent send(Long retailerId, Long roomId, String idempotencyKey, String body, ChatInquiry inquiry) {
        ResponseEntity<WholesaleEnvelope<ChatSendResult>> response = translated("채팅 보내기",
                () -> api.send(roomId, retailerId, idempotencyKey, new SendRequest(body, inquiry)));
        boolean replayed = response.getStatusCode().isSameCodeAs(HttpStatus.OK);
        return new Sent(response.getBody().data(), replayed);
    }

    @Override
    public ChatReadResult markRead(Long retailerId, Long roomId, long lastReadSequence) {
        return translated("채팅 읽음", () -> api.read(roomId, retailerId, new ReadRequest(lastReadSequence))).data();
    }

    @Override
    public int unreadChats(Long retailerId) {
        return call("채팅 배지", () -> api.unreadCount(retailerId)).data().chats();
    }

    /** 계약에 있는 도매 4xx 는 소매 코드로, 나머지는 장애(500)로. */
    private static <T> T translated(String what, Supplier<T> request) {
        try {
            return call(what, request);
        } catch (WholesaleApiException e) {
            if (e.getCause() instanceof HttpClientErrorException rejected) {
                BusinessException known = known(rejected);
                if (known != null) {
                    throw known;
                }
            }
            throw e;
        }
    }

    private static BusinessException known(HttpClientErrorException e) {
        if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        WholesaleError body = bodyOf(e);
        if (body == null) {
            return null;
        }
        if (e.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT) && "IDEMPOTENCY_KEY_REUSED".equals(body.code())) {
            return new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        if (e.getStatusCode().isSameCodeAs(HttpStatus.BAD_REQUEST) && "VALIDATION_FAILED".equals(body.code())) {
            List<FieldError> errors = body.errors() == null ? List.of() : body.errors().stream()
                    .map(f -> new FieldError(f.field(), "INVALID", f.reason()))
                    .toList();
            return new BusinessException(ErrorCode.VALIDATION_FAILED, errors);
        }
        return null;
    }

    private static WholesaleError bodyOf(HttpClientErrorException e) {
        try {
            return e.getResponseBodyAs(WholesaleError.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** 도매 실패 응답 봉투 — {@code {code, message, errors[{field, reason}]}}. */
    private record WholesaleError(String code, String message, List<WholesaleFieldError> errors) {
    }

    private record WholesaleFieldError(String field, String reason) {
    }
}
