package com.ondo.retail.chat.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 채팅 메시지 응답 모양 (MUL-157). 웹 초안 계약 {@code retail.json} 「채팅」의 스키마 그대로다.
 *
 * <p>소매 모양에는 <b>칸 자체가 없는 것</b>이 둘 있다 — 넘김 이유({@code handoff})와 재고 정확 수량. 도매 게이트웨이가
 * 이미 소매 모양으로 주지만, 여기 레코드도 그 칸을 갖지 않아 도매가 실수로 실어 보내도 소매 응답으로 새지 않는다.
 */
public final class ChatMessageResponses {

    private ChatMessageResponses() {
    }

    /** 계약 {@code ChatMessage}. 수정·삭제 없음. sentAt 은 UTC. */
    public record ChatMessage(
            Long id,
            Long roomId,
            Long sequence,
            UUID clientMessageId,
            String sender,
            String kind,
            String body,
            OffsetDateTime sentAt,
            ChatInquiry inquiry,
            ChatAutoReply autoReply) {
    }

    /** 계약 {@code ChatInquiry}. notListed 는 required 가 아니다(없으면 false 로 본다). */
    public record ChatInquiry(
            String intent,
            Long listingId,
            Long variantId,
            Long backorderId,
            String label,
            Boolean notListed) {
    }

    /** 계약 {@code ChatAutoReply}(소매) — AUTO_REPLY·CLARIFY 에만. */
    public record ChatAutoReply(
            String intent,
            Long replyToSequence,
            String generatedBy,
            OffsetDateTime checkedAt,
            ChatSubject subject,
            ChatRetailFacts facts,
            List<ChatChoice> choices) {
    }

    public record ChatSubject(Long listingId, Long variantId, Long backorderId, String label) {
    }

    /** 계약 {@code ChatRetailFacts}. intent 에 맞는 칸 하나만 차고 나머지는 null. */
    public record ChatRetailFacts(ChatRetailStockFacts stock, ChatBackorderFacts backorder,
                                  ChatReceivableFacts receivable) {
    }

    /** 계약 {@code ChatRetailStockFacts}. <b>정확 수량 필드가 없다.</b> */
    public record ChatRetailStockFacts(String band, Integer orderLimit, Boolean orderable) {
    }

    public record ChatBackorderFacts(Integer qty, LocalDate expectedInboundDate, String expectedInboundReason) {
    }

    public record ChatReceivableFacts(Long outstandingAmount, Long prepaidAmount, ChatOverdueFacts overdue) {
    }

    public record ChatOverdueFacts(Long amount, Integer count, Integer maxDaysOverdue) {
    }

    /** 계약 {@code ChatChoice}. kind VARIANT · BACKORDER · NOT_LISTED. */
    public record ChatChoice(String kind, String label, Long variantId, Boolean orderable, Long backorderId) {
    }

    /** 계약 {@code ChatSendResult}. generation NONE · DONE · PENDING. */
    public record ChatSendResult(ChatMessage message, List<ChatMessage> generatedMessages, String generation) {
    }

    /** 계약 {@code ChatReadResult}. */
    public record ChatReadResult(Long roomId, Long lastReadSequence, Integer unreadCount) {
    }

    /** 메시지 한 장 — {@code {data, meta}}. data 는 늘 sequence 오름차순. */
    public record ChatMessagePage(List<ChatMessage> data, ChatMessagePageMeta meta) {
    }

    /** 계약 {@code ChatMessagePageMeta}. counterpartLastReadSequence = 도매가 읽은 마지막 sequence. */
    public record ChatMessagePageMeta(Boolean hasMore, Long maxSequence, Long counterpartLastReadSequence) {
    }
}
