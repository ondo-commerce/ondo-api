package com.ondo.wholesale.retailgateway.dto;

import com.ondo.wholesale.chat.domain.ChatGeneratedBy;
import com.ondo.wholesale.chat.domain.ChatIntent;
import com.ondo.wholesale.chat.domain.ChatMessageKind;
import com.ondo.wholesale.chat.domain.ChatSender;
import com.ondo.wholesale.chat.domain.StockBand;
import com.ondo.wholesale.chat.dto.ChatAutoReply.ChatBackorderFacts;
import com.ondo.wholesale.chat.dto.ChatAutoReply.ChatChoice;
import com.ondo.wholesale.chat.dto.ChatAutoReply.ChatReceivableFacts;
import com.ondo.wholesale.chat.dto.ChatAutoReply.ChatSubject;
import com.ondo.wholesale.chat.dto.ChatInquiry;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 소매에게 가는 메시지 1건 (MUL-157, 소매 계약 {@code ChatMessage}). 도매 모양과 다른 점 둘:
 * <ul>
 *   <li>{@code handoff}(넘김 이유) 칸이 <b>없다</b> — 도매 화면·통계에만 보인다</li>
 *   <li>재고 facts 에 정확 수량 칸이 <b>없다</b> — 구간(band)·한도·주문 가능만. 화면에서 가리는 게 아니라 안 싣는다</li>
 * </ul>
 */
public record RetailChatMessageResponse(
        Long id,
        Long roomId,
        Long sequence,
        UUID clientMessageId,
        ChatSender sender,
        ChatMessageKind kind,
        String body,
        OffsetDateTime sentAt,
        ChatInquiry inquiry,
        AutoReply autoReply) {

    /** 소매 계약 {@code ChatAutoReply}. */
    public record AutoReply(
            ChatIntent intent,
            Long replyToSequence,
            ChatGeneratedBy generatedBy,
            OffsetDateTime checkedAt,
            ChatSubject subject,
            Facts facts,
            List<ChatChoice> choices) {
    }

    /** 소매 계약 {@code ChatRetailFacts}. intent 에 맞는 칸 하나만 차고 나머지는 null. */
    public record Facts(StockFacts stock, ChatBackorderFacts backorder, ChatReceivableFacts receivable) {
    }

    /**
     * 소매 계약 {@code ChatRetailStockFacts}. 정확 수량 필드가 없다.
     *
     * @param orderable 게시 중이고 옵션이 살아 있다. 재고 수량과 무관하다(band 가 NONE 이어도 true 일 수 있다)
     */
    public record StockFacts(StockBand band, Integer orderLimit, Boolean orderable) {
    }
}
