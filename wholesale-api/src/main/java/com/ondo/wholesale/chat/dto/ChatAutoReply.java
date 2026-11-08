package com.ondo.wholesale.chat.dto;

import com.ondo.wholesale.chat.domain.ChatChoiceKind;
import com.ondo.wholesale.chat.domain.ChatGeneratedBy;
import com.ondo.wholesale.chat.domain.ChatIntent;
import com.ondo.wholesale.chat.domain.StockBand;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 자동 답(AUTO_REPLY, CLARIFY)에만 붙는 근거 (MUL-158, 계약 {@code ChatAutoReply} — 도매 응답 모양).
 *
 * <p>저장: {@code intent}·{@code replyToSequence} 는 {@code chat_message} 의 칸(intent, source_sequence),
 * 나머지는 {@code auto_reply} jsonb 에 {@link Detail} 모양으로 둔다. 만드는 쪽은 판정(MUL-157)이다.
 */
public record ChatAutoReply(
        ChatIntent intent,
        Long replyToSequence,
        ChatGeneratedBy generatedBy,
        OffsetDateTime checkedAt,
        ChatSubject subject,
        ChatWholesaleFacts facts,
        List<ChatChoice> choices) {

    public static ChatAutoReply of(ChatIntent intent, Long replyToSequence, Detail detail) {
        return new ChatAutoReply(intent, replyToSequence, detail.generatedBy(), detail.checkedAt(),
                detail.subject(), detail.facts(), detail.choices());
    }

    /** {@code chat_message.auto_reply} jsonb 의 모양 — 칸으로 뺀 intent·replyToSequence 를 뺀 나머지. */
    public record Detail(
            ChatGeneratedBy generatedBy,
            OffsetDateTime checkedAt,
            ChatSubject subject,
            ChatWholesaleFacts facts,
            List<ChatChoice> choices) {
    }

    /** 자동 답이 가리킨 대상. 서버가 방의 도매처·소매처 것인지 검증한 뒤의 값. */
    public record ChatSubject(Long listingId, Long variantId, Long backorderId, String label) {
    }

    /** 근거 값(도매용). intent 에 맞는 칸 하나만 차고 나머지는 null. 정확 수량이 있다 — 소매로 그대로 넘기지 않는다. */
    public record ChatWholesaleFacts(
            ChatWholesaleStockFacts stock,
            ChatBackorderFacts backorder,
            ChatReceivableFacts receivable) {
    }

    public record ChatWholesaleStockFacts(Integer availableQty, StockBand band, Integer orderLimit) {
    }

    public record ChatBackorderFacts(Integer qty, LocalDate expectedInboundDate, String expectedInboundReason) {
    }

    public record ChatReceivableFacts(Long outstandingAmount, Long prepaidAmount, ChatOverdueFacts overdue) {
    }

    public record ChatOverdueFacts(Long amount, Integer count, Integer maxDaysOverdue) {
    }

    /** 되묻기(CLARIFY)의 보기 하나. */
    public record ChatChoice(
            ChatChoiceKind kind,
            String label,
            Long variantId,
            Boolean orderable,
            Long backorderId) {
    }
}
