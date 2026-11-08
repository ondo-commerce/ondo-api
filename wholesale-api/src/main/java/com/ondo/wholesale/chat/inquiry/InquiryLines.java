package com.ondo.wholesale.chat.inquiry;

import com.ondo.wholesale.chat.domain.ChatChoiceKind;
import com.ondo.wholesale.chat.domain.ChatGeneratedBy;
import com.ondo.wholesale.chat.domain.ChatHandoffReason;
import com.ondo.wholesale.chat.domain.ChatIntent;
import com.ondo.wholesale.chat.domain.ChatMessageKind;
import com.ondo.wholesale.chat.domain.ChatSender;
import com.ondo.wholesale.chat.domain.StockBand;
import com.ondo.wholesale.chat.dto.ChatAutoReply;
import com.ondo.wholesale.chat.dto.ChatAutoReply.ChatBackorderFacts;
import com.ondo.wholesale.chat.dto.ChatAutoReply.ChatChoice;
import com.ondo.wholesale.chat.dto.ChatAutoReply.ChatSubject;
import com.ondo.wholesale.chat.dto.ChatAutoReply.ChatWholesaleFacts;
import com.ondo.wholesale.chat.dto.ChatAutoReply.ChatWholesaleStockFacts;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.BackorderAnswer;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.BackorderClarify;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.EtaState;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.Handoff;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.PackingAnswer;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.ReceivableAnswer;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.StockAnswer;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.StockClarify;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Backorder;
import com.ondo.wholesale.chat.inquiry.InquiryInput.HistoryItem;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Variant;
import com.ondo.wholesale.chat.repository.ChatMessageRepository.NewMessage;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 판정 → 방에 넣을 줄 (MUL-157). 웹 목 {@code generatedLines} · {@code linesToAppend} · {@code openHandoffOf} 와
 * 문장 템플릿을 옮겼다. 줄은 {@link NewMessage} 모양 그대로 나온다 — 저장은 {@code ChatMessageAppender} 만 한다.
 *
 * <p>facts 는 <b>도매 모양</b>(정확 수량 포함)이다. 소매 응답으로 옮기는 건 게이트웨이의 소매 투영이 한다.
 * 소매에게 나가는 글(본문, 칩 글자)에는 정확 재고 수를 쓰지 않는다 — 구간만 쓴다.
 */
public final class InquiryLines {

    private InquiryLines() {
    }

    public static final String CLARIFY_BODY = "어떤 색상, 사이즈요?";
    public static final String HANDOFF_BODY = "자동 답변이 답하지 못했어요. 직접 답해 주세요";
    public static final String QTY_NOTE = "수량은 주문서에서 확인돼요";
    static final String BACKORDER_CLARIFY_BODY = "어느 주문이요?";
    static final String BACKORDER_TRUNCATED_NOTE = "오래된 8개만 보여요";
    static final String NOT_LISTED_LABEL = "다른 주문이에요";

    /** 열린 넘김. 닫혀 있으면 이 값 자체가 null 이다. {@code reason} 은 마지막 넘김 줄의 이유(모르면 null). */
    public record OpenHandoff(ChatHandoffReason reason) {
    }

    /**
     * 판정 → 줄. 재고 0 · 입고일 미정/지남 · 포장 답은 AUTO_REPLY + HANDOFF 두 줄. checkedAt 은 {@code now},
     * 미수만 원본 asOf.
     */
    public static List<NewMessage> generated(InquiryDecision d, long replyToSequence, Instant now) {
        OffsetDateTime checkedAt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        return switch (d) {
            case Handoff h -> List.of(handoff(h.reason(), h.intent(), replyToSequence));
            case StockClarify c -> List.of(autoLine(ChatMessageKind.CLARIFY, CLARIFY_BODY, ChatIntent.STOCK,
                    replyToSequence, new ChatAutoReply.Detail(ChatGeneratedBy.RULE, checkedAt,
                            new ChatSubject(c.listing().listingId(), null, null, c.listing().title()),
                            emptyFacts(),
                            c.choices().stream().map(v -> new ChatChoice(ChatChoiceKind.VARIANT, optionLabel(v),
                                    v.variantId(), c.listing().published() && v.published(), null)).toList())));
            case StockAnswer a -> stockLines(a, replyToSequence, checkedAt);
            case BackorderClarify c -> List.of(backorderClarify(c, replyToSequence, checkedAt));
            case BackorderAnswer a -> backorderLines(a, replyToSequence, checkedAt);
            case PackingAnswer p -> List.of(
                    autoLine(ChatMessageKind.AUTO_REPLY, PackingOrders.replyBody(p.orderIds()), ChatIntent.BACKORDER,
                            replyToSequence, new ChatAutoReply.Detail(ChatGeneratedBy.RULE, checkedAt,
                                    new ChatSubject(null, null, null, null), emptyFacts(), null)),
                    handoff(ChatHandoffReason.NO_BACKORDER, ChatIntent.BACKORDER, replyToSequence));
            case ReceivableAnswer r -> List.of(autoLine(ChatMessageKind.AUTO_REPLY,
                    ReceivableFacts.replyBody(r.facts()), ChatIntent.RECEIVABLE, replyToSequence,
                    new ChatAutoReply.Detail(ChatGeneratedBy.RULE, r.checkedAt(),
                            new ChatSubject(null, null, null, null),
                            new ChatWholesaleFacts(null, null, r.facts()), null)));
        };
    }

    /**
     * 생성 줄 중 실제로 방에 넣을 것. 열린 넘김이 있으면 넘김 줄은 <b>이유가 같을 때</b> 넣지 않는다 — 사장이 답하기 전까지
     * 소매 글마다 넘김 줄이 쌓이지 않게. 이유가 다르면 새 줄을 넣는다(입금 알림을 놓치지 않게). 이유를 모르는 열린 넘김은
     * 어떤 이유와도 다르다. 답 + 넘김 두 줄의 넘김은 답의 일부라 늘 넣는다.
     *
     * @param open 지금 열린 넘김. 닫혀 있으면 null
     */
    public static List<NewMessage> toAppend(List<NewMessage> lines, OpenHandoff open) {
        boolean answerWithHandoff = lines.stream().anyMatch(l -> l.kind() == ChatMessageKind.AUTO_REPLY)
                && lines.stream().anyMatch(l -> l.kind() == ChatMessageKind.HANDOFF);
        return lines.stream()
                .filter(l -> !(l.kind() == ChatMessageKind.HANDOFF && open != null && !answerWithHandoff
                        && l.handoffReason() == open.reason()))
                .toList();
    }

    /** 마지막 HANDOFF 뒤에 사장이 직접 쓴 TEXT 가 없으면 열려 있다. AUTO_REPLY·CLARIFY 로는 닫히지 않는다. */
    public static OpenHandoff openHandoffOf(List<HistoryItem> history) {
        OpenHandoff open = null;
        for (HistoryItem m : history) {
            if (m.kind() == ChatMessageKind.HANDOFF) {
                open = new OpenHandoff(m.handoffReason());
            } else if (m.sender() == ChatSender.WHOLESALER && m.kind() == ChatMessageKind.TEXT) {
                open = null;
            }
        }
        return open;
    }

    /* --- 문장 ----------------------------------------------------------------- */

    static String optionLabel(Variant v) {
        return v.color() + " / " + v.size();
    }

    /** "레드 M은", "네이비 S는". 사이즈를 읽는 소리의 받침으로 고른다(에스·프리는 받침 없음). */
    private static String topicOf(Variant v) {
        String particle = switch (v.size()) {
            case "S", "XS", "FREE" -> "는";
            default -> "은";
        };
        return v.color() + " " + v.size() + particle;
    }

    private static String bandText(StockBand band) {
        return switch (band) {
            case UNDER_10 -> "1~9장";
            case UNDER_50 -> "10~49장";
            case FIFTY_PLUS -> "50장 이상";
            case NONE -> throw new IllegalArgumentException("NONE 은 구간 글자가 없다");
        };
    }

    /** 재고 답 본문. <b>구간만</b> 쓴다 — 이 글이 소매에게 그대로 간다. */
    static String stockReplyBody(StockAnswer d) {
        String body;
        if (d.band() == StockBand.NONE) {
            body = topicOf(d.variant()) + " 지금은 재고가 없어요";
        } else if (!d.orderable()) {
            body = topicOf(d.variant()) + " " + bandText(d.band()) + " 있어요. 지금은 주문할 수 없어요";
        } else {
            body = topicOf(d.variant()) + " " + bandText(d.band()) + " 있어요. 지금 주문할 수 있어요"
                    + (d.orderLimit() == null ? "" : "(한 번에 최대 " + d.orderLimit() + "장)");
        }
        return d.qtyNote() ? body + ". " + QTY_NOTE : body;
    }

    /**
     * 미송 답 본문. 수량은 그 소매처 자기 미송의 <b>남은</b> 수량뿐이다. "도매 입고" 를 박아 출고·도착일로 읽히지 않게 한다.
     * 지난 날짜를 "지연" 이라 쓰지 않는다. 사유는 예정·오늘일 때만 붙인다.
     */
    static String backorderReplyBody(BackorderAnswer d) {
        Backorder b = d.backorder();
        String head = "%s %s %s 남은 미송 %d장은".formatted(b.title(), b.color(), b.size(), b.remainingQty());
        String reason = b.expectedInboundReason() == null || b.expectedInboundReason().isEmpty()
                ? "" : "(" + b.expectedInboundReason() + ")";
        if (d.eta() == EtaState.NONE || b.expectedInboundDate() == null) {
            return head + " 아직 도매 입고일이 정해지지 않았어요";
        }
        if (d.eta() == EtaState.PAST) {
            return head + " 등록된 입고 예정일 " + BackorderCandidates.koreanDate(b.expectedInboundDate(), d.today())
                    + "이 지났어요";
        }
        String when = d.eta() == EtaState.TODAY ? "오늘" : BackorderCandidates.koreanDate(b.expectedInboundDate(), d.today());
        return head + " " + when + " 도매 입고 예정이에요" + reason;
    }

    /* --- 줄 ------------------------------------------------------------------- */

    private static List<NewMessage> stockLines(StockAnswer d, long replyTo, OffsetDateTime checkedAt) {
        NewMessage reply = autoLine(ChatMessageKind.AUTO_REPLY, stockReplyBody(d), ChatIntent.STOCK, replyTo,
                new ChatAutoReply.Detail(ChatGeneratedBy.RULE, checkedAt,
                        new ChatSubject(d.listing().listingId(), d.variant().variantId(), null,
                                d.listing().title() + " " + optionLabel(d.variant())),
                        new ChatWholesaleFacts(new ChatWholesaleStockFacts(
                                Math.max(0, d.variant().availableQty()), d.band(), d.orderLimit()), null, null),
                        null));
        return d.band() == StockBand.NONE
                ? List.of(reply, handoff(ChatHandoffReason.STOCK_ZERO, ChatIntent.STOCK, replyTo))
                : List.of(reply);
    }

    /**
     * 미송 되묻기. 보기 끝에 늘 「다른 주문이에요」(NOT_LISTED)를 싣는다 — 빠져나갈 보기가 없으면 소매가 아무 칩이나 눌러
     * 틀린 답을 받는다. 미송·NOT_LISTED 보기의 orderable 은 false, NOT_LISTED 의 variantId 는 required 라 0.
     */
    private static NewMessage backorderClarify(BackorderClarify d, long replyTo, OffsetDateTime checkedAt) {
        List<ChatChoice> choices = new ArrayList<>();
        for (Backorder b : d.choices()) {
            choices.add(new ChatChoice(ChatChoiceKind.BACKORDER, BackorderCandidates.choiceLabel(b, d.today()),
                    b.variantId(), false, b.backorderId()));
        }
        choices.add(new ChatChoice(ChatChoiceKind.NOT_LISTED, NOT_LISTED_LABEL, 0L, false, null));
        String body = d.truncated() ? BACKORDER_CLARIFY_BODY + " " + BACKORDER_TRUNCATED_NOTE : BACKORDER_CLARIFY_BODY;
        return autoLine(ChatMessageKind.CLARIFY, body, ChatIntent.BACKORDER, replyTo,
                new ChatAutoReply.Detail(ChatGeneratedBy.RULE, checkedAt,
                        new ChatSubject(null, null, null, null), emptyFacts(), List.copyOf(choices)));
    }

    /** 미송 답. 입고일 미정·지남은 AUTO_REPLY + HANDOFF 두 줄. facts.backorder.qty 는 <b>남은</b> 수량이다. */
    private static List<NewMessage> backorderLines(BackorderAnswer d, long replyTo, OffsetDateTime checkedAt) {
        Backorder b = d.backorder();
        NewMessage reply = autoLine(ChatMessageKind.AUTO_REPLY, backorderReplyBody(d), ChatIntent.BACKORDER, replyTo,
                new ChatAutoReply.Detail(ChatGeneratedBy.RULE, checkedAt,
                        new ChatSubject(null, b.variantId(), b.backorderId(),
                                b.title() + " " + b.color() + " / " + b.size()),
                        new ChatWholesaleFacts(null, new ChatBackorderFacts(b.remainingQty(),
                                b.expectedInboundDate(), b.expectedInboundReason()), null),
                        null));
        return switch (d.eta()) {
            case NONE -> List.of(reply, handoff(ChatHandoffReason.ETA_UNKNOWN, ChatIntent.BACKORDER, replyTo));
            case PAST -> List.of(reply, handoff(ChatHandoffReason.ETA_PAST, ChatIntent.BACKORDER, replyTo));
            default -> List.of(reply);
        };
    }

    private static ChatWholesaleFacts emptyFacts() {
        return new ChatWholesaleFacts(null, null, null);
    }

    private static NewMessage autoLine(ChatMessageKind kind, String body, ChatIntent intent, long replyTo,
                                       ChatAutoReply.Detail detail) {
        return new NewMessage(ChatSender.WHOLESALER, kind, body, null, null, intent, replyTo, null, detail);
    }

    /** 넘김 줄. 이유, 그때 읽은 의도, 원인 소매 메시지를 싣는다. */
    private static NewMessage handoff(ChatHandoffReason reason, ChatIntent intent, long sourceSequence) {
        return new NewMessage(ChatSender.SYSTEM, ChatMessageKind.HANDOFF, HANDOFF_BODY, null, null, intent,
                sourceSequence, reason, null);
    }
}
