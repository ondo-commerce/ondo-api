package com.ondo.wholesale.chat.inquiry;

import com.ondo.wholesale.chat.domain.ChatGeneratedBy;
import com.ondo.wholesale.chat.domain.ChatHandoffReason;
import com.ondo.wholesale.chat.domain.ChatIntent;
import com.ondo.wholesale.chat.dto.ChatAutoReply.Detail;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.BackorderClarify;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.Handoff;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.StockAnswer;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.StockClarify;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Backorder;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Listing;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Packing;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Receivable;
import com.ondo.wholesale.chat.repository.ChatMessageRepository.NewMessage;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** 검증된 분류값으로 규칙 입력을 보완한다. 조회 권한, 숫자, 답 문장은 기존 판정기와 템플릿이 맡는다. */
public final class LlmInquiry {

    private static final Pattern LONG_NUMBER = Pattern.compile("\\p{Nd}(?:[\\p{Pd}.\\s]*\\p{Nd}){6}",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final List<String> PRICE_WORDS = List.of("얼마", "가격", "단가");

    private LlmInquiry() {
    }

    /** 호출 전에 DB 없이 확인할 수 있는 조건. 일반 보내기와 측정이 함께 쓴다. */
    public static boolean worthAsking(String body, boolean hasChip) {
        if (hasChip || body == null || body.isBlank() || body.codePointCount(0, body.length()) > 1000) {
            return false;
        }
        String normalized = InquiryText.normalize(body);
        String flat = InquiryText.compact(normalized);
        return !InquiryText.isHumanRequest(normalized, flat) && !InquiryText.isNegotiation(normalized, flat)
                && !InquiryText.containsAny(flat, InquiryText.PAYMENT_REPORT_WORDS)
                && !LONG_NUMBER.matcher(Normalizer.normalize(body, Normalizer.Form.NFKC)).find();
    }

    public enum Adoption { SAME, ADOPTED, RULE_KEPT }

    public record Decided(InquiryDecision rule, InquiryDecision decision, ChatGeneratedBy generatedBy,
                          Adoption adoption) {
        public static Decided ruleOnly(InquiryDecision rule) {
            return new Decided(rule, rule, ChatGeneratedBy.RULE, Adoption.RULE_KEPT);
        }
    }

    public static Decided decide(InquiryInput input, InquiryReading reading) {
        if (reading == null || input.inquiry() != null) {
            return Decided.ruleOnly(InquiryDecider.decide(input));
        }
        InquiryInput cached = withCachedSources(input);
        InquiryDecision rule = InquiryDecider.decide(cached);
        String size = InquiryText.SIZE_ORDER.contains(reading.size()) ? reading.size() : "";
        String hint = switch (reading.intent()) {
            case STOCK -> size.isEmpty() ? "재고" : "재고 " + size;
            case BACKORDER -> "미송";
            case RECEIVABLE -> "미수";
            case OTHER -> size;
        };
        if (hint.isEmpty()) {
            return Decided.ruleOnly(rule);
        }

        InquiryDecision amended;
        try {
            amended = InquiryDecider.decide(copy(cached, cached.body() + " " + hint,
                    cached.backorders(), cached.receivables(), cached.packings()));
        } catch (RuntimeException e) {
            return Decided.ruleOnly(rule);
        }
        if (!matchesProduct(amended, reading.productName())) {
            return Decided.ruleOnly(rule);
        }
        boolean same = rule.equals(amended);
        if (!same && !mayAdopt(input, rule, amended, reading)) {
            return Decided.ruleOnly(rule);
        }
        ChatGeneratedBy by = amended instanceof Handoff ? ChatGeneratedBy.RULE : ChatGeneratedBy.LLM;
        return new Decided(rule, same ? rule : amended, by, same ? Adoption.SAME : Adoption.ADOPTED);
    }

    private static boolean mayAdopt(InquiryInput input, InquiryDecision rule, InquiryDecision amended,
                                    InquiryReading reading) {
        if (amended.intent() == ChatIntent.STOCK) {
            String flat = InquiryText.compact(InquiryText.normalize(input.body()));
            if (InquiryText.containsAny(flat, PRICE_WORDS)) {
                return false;
            }
            if (amended instanceof StockAnswer answer) {
                var open = InquiryDecider.openClarify(input.history(), input.now());
                if (open != null && open.intent() == ChatIntent.STOCK
                        && (!open.listingId().equals(answer.listing().listingId())
                        || !open.variantIds().contains(answer.variant().variantId()))) {
                    return false;
                }
            }
        }
        if (rule instanceof Handoff handoff) {
            if (handoff.reason() == ChatHandoffReason.NO_INTENT) {
                return true;
            }
            if (handoff.reason() != ChatHandoffReason.CLARIFY_AGAIN) {
                return false;
            }
        } else if (!(rule instanceof StockClarify || rule instanceof BackorderClarify)) {
            return false;
        }
        boolean compatible = rule.intent() == reading.intent()
                || reading.intent() == ChatIntent.OTHER && !InquiryReading.NO_SIZE.equals(reading.size());
        return compatible && amended.intent() == rule.intent()
                && !(amended instanceof Handoff || amended instanceof StockClarify || amended instanceof BackorderClarify);
    }

    private static boolean matchesProduct(InquiryDecision decision, String productName) {
        Listing listing = switch (decision) {
            case StockAnswer answer -> answer.listing();
            case StockClarify clarify -> clarify.listing();
            default -> null;
        };
        if (listing == null || productName.isBlank()) {
            return true;
        }
        String title = InquiryText.normalize(listing.title());
        return Arrays.stream(InquiryText.normalize(productName).split(" "))
                .filter(word -> word.codePointCount(0, word.length()) >= 2).anyMatch(title::contains);
    }

    /** 한 문의 안에서 규칙 판정과 보완 판정이 같은 값 또는 같은 조회 실패를 본다. */
    public static InquiryInput withCachedSources(InquiryInput input) {
        return copy(input, input.body(), once(input.backorders()), once(input.receivables()), once(input.packings()));
    }

    private static InquiryInput copy(InquiryInput input, String body, Supplier<List<Backorder>> backorders,
                                     Supplier<List<Receivable>> receivables, Supplier<List<Packing>> packings) {
        return new InquiryInput(body, input.inquiry(), input.now(), input.today(), input.wholesalerId(),
                input.retailerId(), input.settings(), input.context(), input.history(), input.catalog(),
                backorders, receivables, packings);
    }

    private static <T> Supplier<T> once(Supplier<T> source) {
        return new Supplier<>() {
            private boolean loaded;
            private T value;
            private RuntimeException failure;

            @Override
            public T get() {
                if (!loaded) {
                    try {
                        value = source.get();
                    } catch (RuntimeException e) {
                        failure = e;
                    }
                    loaded = true;
                }
                if (failure != null) {
                    throw failure;
                }
                return value;
            }
        };
    }

    /** 자동 답·되묻기의 출처만 바꾼다. 넘김에는 출처 칸이 없다. */
    public static List<NewMessage> label(List<NewMessage> lines, ChatGeneratedBy by) {
        if (by == ChatGeneratedBy.RULE) {
            return lines;
        }
        return lines.stream().map(line -> {
            Detail detail = line.autoReply();
            if (detail == null) {
                return line;
            }
            return new NewMessage(line.sender(), line.kind(), line.body(), line.clientMessageId(), line.inquiry(),
                    line.intent(), line.sourceSequence(), line.handoffReason(),
                    new Detail(by, detail.checkedAt(), detail.subject(), detail.facts(), detail.choices()));
        }).toList();
    }
}
