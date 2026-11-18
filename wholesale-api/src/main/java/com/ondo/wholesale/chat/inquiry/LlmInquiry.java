package com.ondo.wholesale.chat.inquiry;

import com.ondo.wholesale.chat.domain.ChatGeneratedBy;
import com.ondo.wholesale.chat.domain.ChatHandoffReason;
import com.ondo.wholesale.chat.domain.ChatIntent;
import com.ondo.wholesale.chat.dto.ChatAutoReply.Detail;
import com.ondo.wholesale.chat.dto.ChatInquiry;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.BackorderAnswer;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.BackorderClarify;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.Handoff;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.StockAnswer;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.StockClarify;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Backorder;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Listing;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Packing;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Receivable;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Variant;
import com.ondo.wholesale.chat.repository.ChatMessageRepository.NewMessage;

import java.text.Normalizer;
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
        var open = InquiryDecider.openClarify(input.history(), input.now());
        String hint = switch (reading.intent()) {
            case STOCK -> size.isEmpty() ? "재고" : "재고 " + size;
            case BACKORDER -> "미송";
            case RECEIVABLE -> "미수";
            // 열린 재고 되묻기에 사이즈만 짧게 답했을 때만 붙인다. 그 밖에 붙인 사이즈는 다른 질문을 재고 질문으로 바꾼다
            case OTHER -> open != null && open.intent() == ChatIntent.STOCK
                    && InquiryReading.sizeOnlyReply(input.body(), size) ? size : "";
        };
        if (hint.isEmpty()) {
            return Decided.ruleOnly(rule);
        }

        InquiryDecision amended;
        try {
            ChatInquiry chip = reading.intent() == ChatIntent.OTHER ? onlyChoice(cached, open, size) : null;
            amended = InquiryDecider.decide(copy(cached, chip, cached.body() + " " + hint,
                    cached.backorders(), cached.receivables(), cached.packings()));
        } catch (RuntimeException e) {
            return Decided.ruleOnly(rule);
        }
        if (!matchesProduct(amended, reading.productName()) || answersOtherSize(amended, size)) {
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

    /** 게시 옵션이 하나뿐이면 판정기는 그 옵션으로 답한다. 읽은 사이즈와 다르면 소매가 묻지 않은 옵션이다. */
    private static boolean answersOtherSize(InquiryDecision decision, String size) {
        String answered = switch (decision) {
            case StockAnswer answer -> answer.variant().size();
            case BackorderAnswer answer -> answer.backorder().size();
            default -> null;
        };
        return !size.isEmpty() && answered != null && !size.equals(answered);
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
        // 일부만 겹치는 이름("셔츠", "린넨 셔츠")은 다른 상품일 수 있어 문맥 상품 제목과 완전히 같을 때만 받는다
        return InquiryReading.tidy(productName).equals(InquiryReading.tidy(listing.title()));
    }

    /**
     * 판정기는 보기의 색이 둘 이상이면 사이즈만으로 고르지 않는다. 본문에 못 읽은 색 낱말("빨강 L")이 있을 수 있어서다.
     * 짧은 답은 사이즈 말고 다른 낱말이 없으니, 열린 되묻기 보기 중 그 사이즈가 하나뿐이면 그 보기를 칩으로 고른 것과 같다.
     */
    private static ChatInquiry onlyChoice(InquiryInput input, InquiryDecider.OpenClarify open, String size) {
        Listing listing = input.catalog().find(open.listingId());
        List<Variant> shown = listing == null ? List.of() : listing.variants().stream()
                .filter(v -> open.variantIds().contains(v.variantId())).toList();
        // 보여 준 보기 중 지금 못 찾는 옵션(삭제, 다른 상품)이 있으면 그 사이즈가 보기에서 하나뿐이었는지 알 수 없다
        if (!shown.stream().map(Variant::variantId).toList().containsAll(open.variantIds())) {
            return null;
        }
        List<Long> hits = shown.stream().filter(v -> v.size().equals(size)).map(Variant::variantId).toList();
        return hits.size() == 1
                ? new ChatInquiry(ChatIntent.STOCK, open.listingId(), hits.getFirst(), null, null, null) : null;
    }

    /** 한 문의 안에서 규칙 판정과 보완 판정이 같은 값 또는 같은 조회 실패를 본다. */
    public static InquiryInput withCachedSources(InquiryInput input) {
        return copy(input, input.inquiry(), input.body(), once(input.backorders()), once(input.receivables()),
                once(input.packings()));
    }

    private static InquiryInput copy(InquiryInput input, ChatInquiry inquiry, String body,
                                     Supplier<List<Backorder>> backorders, Supplier<List<Receivable>> receivables,
                                     Supplier<List<Packing>> packings) {
        return new InquiryInput(body, inquiry, input.now(), input.today(), input.wholesalerId(),
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
