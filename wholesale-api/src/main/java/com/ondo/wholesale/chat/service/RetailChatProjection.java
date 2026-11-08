package com.ondo.wholesale.chat.service;

import com.ondo.wholesale.chat.dto.ChatAutoReply;
import com.ondo.wholesale.chat.dto.ChatAutoReply.ChatOverdueFacts;
import com.ondo.wholesale.chat.dto.ChatAutoReply.ChatReceivableFacts;
import com.ondo.wholesale.chat.dto.ChatAutoReply.ChatWholesaleFacts;
import com.ondo.wholesale.chat.dto.ChatAutoReply.ChatBackorderFacts;
import com.ondo.wholesale.chat.dto.ChatMessage;
import com.ondo.wholesale.chat.dto.ChatSendResult;
import com.ondo.wholesale.retailgateway.dto.RetailChatDtos.SendResponse;
import com.ondo.wholesale.retailgateway.dto.RetailChatMessageResponse;
import com.ondo.wholesale.retailgateway.dto.RetailChatMessageResponse.AutoReply;
import com.ondo.wholesale.retailgateway.dto.RetailChatMessageResponse.Facts;
import com.ondo.wholesale.retailgateway.dto.RetailChatMessageResponse.StockFacts;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 도매 모양 메시지 → 소매 모양 (MUL-157, D31②). 웹 목 {@code toRetailMessage} · {@code toRetailFacts} 와 같다.
 *
 * <p><b>키를 골라 새 객체를 만든다</b> — 지우는 방식이면 도매 facts 에 칸이 늘 때 소매로 샌다. 그래서 재고 facts 의
 * {@code availableQty}(정확 수량)와 메시지의 {@code handoff}(넘김 이유)는 소매 레코드에 칸 자체가 없다.
 *
 * <p>소매 재고 facts 의 {@code orderable} 은 저장된 도매 facts 에 없어 대상을 다시 찾아 계산한다(게시 중 + 게시된 옵션 +
 * 지워지지 않음). 찾지 못하면 false — 모르면 주문 가능이라고 말하지 않는다. 한 장의 메시지를 한 번에 찾는다.
 */
@Component
@RequiredArgsConstructor
public class RetailChatProjection {

    private final JdbcClient jdbc;

    public SendResponse send(ChatSendResult result) {
        List<ChatMessage> all = new ArrayList<>(result.generatedMessages().size() + 1);
        all.add(result.message());
        all.addAll(result.generatedMessages());
        Set<String> orderable = orderable(all);
        return new SendResponse(message(result.message(), orderable),
                result.generatedMessages().stream().map(m -> message(m, orderable)).toList(),
                result.generation());
    }

    public List<RetailChatMessageResponse> messages(List<ChatMessage> messages) {
        Set<String> orderable = orderable(messages);
        return messages.stream().map(m -> message(m, orderable)).toList();
    }

    private static RetailChatMessageResponse message(ChatMessage m, Set<String> orderable) {
        ChatAutoReply a = m.autoReply();
        return new RetailChatMessageResponse(m.id(), m.roomId(), m.sequence(), m.clientMessageId(), m.sender(),
                m.kind(), m.body(), m.sentAt(), m.inquiry(),
                a == null ? null : new AutoReply(a.intent(), a.replyToSequence(), a.generatedBy(), a.checkedAt(),
                        a.subject(), facts(a, orderable), a.choices()));
    }

    private static Facts facts(ChatAutoReply a, Set<String> orderable) {
        ChatWholesaleFacts f = a.facts();
        if (f == null) {
            return new Facts(null, null, null);
        }
        StockFacts stock = f.stock() == null ? null : new StockFacts(f.stock().band(), f.stock().orderLimit(),
                a.subject() != null && orderable.contains(key(a.subject().listingId(), a.subject().variantId())));
        ChatBackorderFacts b = f.backorder();
        ChatBackorderFacts backorder = b == null ? null
                : new ChatBackorderFacts(b.qty(), b.expectedInboundDate(), b.expectedInboundReason());
        ChatReceivableFacts r = f.receivable();
        ChatReceivableFacts receivable = r == null ? null : new ChatReceivableFacts(r.outstandingAmount(),
                r.prepaidAmount(), r.overdue() == null ? null : new ChatOverdueFacts(r.overdue().amount(),
                        r.overdue().count(), r.overdue().maxDaysOverdue()));
        return new Facts(stock, backorder, receivable);
    }

    /** 재고 답 줄들의 (게시글, 옵션) 중 지금 주문 가능한 것. */
    private Set<String> orderable(List<ChatMessage> messages) {
        Set<Long> variantIds = new HashSet<>();
        for (ChatMessage m : messages) {
            ChatAutoReply a = m.autoReply();
            if (a != null && a.facts() != null && a.facts().stock() != null && a.subject() != null
                    && a.subject().listingId() != null && a.subject().variantId() != null) {
                variantIds.add(a.subject().variantId());
            }
        }
        if (variantIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                        select lv.listing_id, lv.variant_id
                        from wholesale.listing_variant lv
                        join wholesale.listing l on l.id = lv.listing_id
                        join wholesale.product p on p.id = l.product_id
                        join wholesale.variant v on v.id = lv.variant_id
                        where lv.variant_id in (:ids)
                          and l.status = 'ON_SALE' and l.deleted_at is null
                          and p.deleted_at is null and v.deleted_at is null
                        """)
                .param("ids", variantIds)
                .query((rs, i) -> key(rs.getLong("listing_id"), rs.getLong("variant_id")))
                .list());
    }

    private static String key(Long listingId, Long variantId) {
        return listingId + ":" + variantId;
    }
}
