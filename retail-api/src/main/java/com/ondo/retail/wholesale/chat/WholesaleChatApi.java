package com.ondo.retail.wholesale.chat;

import com.ondo.retail.chat.dto.ChatMessageResponses.ChatMessagePage;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatReadResult;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatSendResult;
import com.ondo.retail.chat.dto.ChatRoomResponses.ChatRoomDetail;
import com.ondo.retail.wholesale.dto.WholesaleEnvelope;
import com.ondo.retail.wholesale.chat.dto.WholesaleChatDtos;
import com.ondo.retail.wholesale.chat.dto.WholesaleChatDtos.OpenRequest;
import com.ondo.retail.wholesale.chat.dto.WholesaleChatDtos.ReadRequest;
import com.ondo.retail.wholesale.chat.dto.WholesaleChatDtos.SendRequest;
import com.ondo.retail.wholesale.chat.dto.WholesaleChatDtos.Unread;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * 도매 소매접점 채팅 API 의 계약 (MUL-157). 구현은 스프링이 끼운다({@code WholesaleHttpConfig}).
 *
 * <p>도매 게이트웨이는 메시지·방을 <b>이미 소매 계약 모양</b>으로 준다(정확 수량·넘김 이유 없음). 그래서 응답을 소매 DTO 로
 * 바로 받는다 — 소매 레코드에 없는 칸은 읽히지도 나가지도 않는다. keyset 시각은 알림과 같은 이유로 epoch 마이크로초다.
 */
@HttpExchange("/api/retail-gateway/chat")
public interface WholesaleChatApi {

    @GetExchange("/rooms")
    WholesaleEnvelope<List<ChatRoomDetail>> rooms(
            @RequestParam Long retailerId,
            @RequestParam int limit,
            @RequestParam(required = false) Long beforeMicros,
            @RequestParam(required = false) Long beforeId);

    @PostExchange("/rooms")
    WholesaleEnvelope<WholesaleChatDtos.Opened> open(@RequestBody OpenRequest request);

    @GetExchange("/rooms/{roomId}")
    WholesaleEnvelope<ChatRoomDetail> room(@PathVariable Long roomId, @RequestParam Long retailerId);

    @GetExchange("/rooms/{roomId}/messages")
    ChatMessagePage messages(
            @PathVariable Long roomId,
            @RequestParam Long retailerId,
            @RequestParam(required = false) Long afterSequence,
            @RequestParam(required = false) Long beforeSequence,
            @RequestParam int size);

    /** 201 새로 만듦 / 200 같은 키 replay — 상태 코드가 뜻을 가져서 ResponseEntity 로 받는다. */
    @PostExchange("/rooms/{roomId}/messages")
    ResponseEntity<WholesaleEnvelope<ChatSendResult>> send(
            @PathVariable Long roomId,
            @RequestParam Long retailerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SendRequest request);

    @PostExchange("/rooms/{roomId}/read")
    WholesaleEnvelope<ChatReadResult> read(
            @PathVariable Long roomId,
            @RequestParam Long retailerId,
            @RequestBody ReadRequest request);

    @GetExchange("/unread-count")
    WholesaleEnvelope<Unread> unreadCount(@RequestParam Long retailerId);
}
