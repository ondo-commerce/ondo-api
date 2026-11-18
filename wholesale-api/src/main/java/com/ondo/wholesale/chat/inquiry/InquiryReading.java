package com.ondo.wholesale.chat.inquiry;

import com.ondo.wholesale.chat.domain.ChatIntent;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static java.util.Map.entry;

/** 문의 한 문장에서 확인한 분류값. 답 문장, 수량, 금액, 내부 식별자는 받지 않는다. */
public record InquiryReading(ChatIntent intent, String size, String productName, boolean hasOrderHint,
                             boolean asksOwnOutstandingTotal) {

    public static final String NO_SIZE = "NONE";
    private static final Map<String, String> KOREAN_SIZES = Map.ofEntries(
            entry("엑스스몰", "XS"), entry("스몰", "S"), entry("에스", "S"),
            entry("미디움", "M"), entry("미디엄", "M"), entry("엠", "M"),
            entry("라지", "L"), entry("엘", "L"), entry("엑스라지", "XL"), entry("엑스엘", "XL"),
            entry("투엑스라지", "2XL"), entry("투엑스엘", "2XL"), entry("프리", "FREE"), entry("프리사이즈", "FREE"));
    private static final List<String> SIZE_ALIASES = List.copyOf(KOREAN_SIZES.keySet());

    /** 구조화 응답의 여덟 필수 칸. Boolean의 null은 칸 누락이라 false와 구분한다. */
    public record Fields(String intent, Boolean uncertain, String size, String sizeEvidence,
                         String color, String productName, String orderHint, Boolean asksOwnOutstandingTotal) {
    }

    public static InquiryReading validate(String body, Fields fields) {
        if (body == null || fields == null || !Boolean.FALSE.equals(fields.uncertain())
                || fields.asksOwnOutstandingTotal() == null || fields.size() == null || fields.sizeEvidence() == null
                || !validText(fields.color()) || !validText(fields.productName()) || !validText(fields.orderHint())) {
            return null;
        }
        ChatIntent intent = switch (fields.intent()) {
            case "STOCK" -> ChatIntent.STOCK;
            case "BACKORDER" -> ChatIntent.BACKORDER;
            case "RECEIVABLE" -> ChatIntent.RECEIVABLE;
            case "OTHER" -> ChatIntent.OTHER;
            case null, default -> null;
        };
        if (intent == null || intent == ChatIntent.OTHER && NO_SIZE.equals(fields.size())) {
            return null;
        }
        if (!supportedSize(body, fields.size(), fields.sizeEvidence())) {
            return null;
        }
        return new InquiryReading(intent, fields.size(), fields.productName(), !fields.orderHint().isBlank(),
                fields.asksOwnOutstandingTotal());
    }

    private static boolean validText(String value) {
        return value != null && value.codePointCount(0, value.length()) <= 40
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean supportedSize(String body, String size, String evidence) {
        if (NO_SIZE.equals(size)) {
            return evidence.isEmpty();
        }
        if (!InquiryText.SIZE_ORDER.contains(size)) {
            return false;
        }
        String word = InquiryText.compact(fold(evidence));
        String normalized = fold(body);
        // 영문은 기존 토큰 경계로, 한글은 긴 별칭부터 읽는다. XL의 L이나 엑스라지의 라지는 근거가 아니다.
        List<String> english = BackorderCandidates.sizesIn(normalized);
        List<String> korean = BackorderCandidates.takeLongest(InquiryText.compact(normalized), SIZE_ALIASES).found();
        boolean present = size.equals(word) && english.contains(word)
                || size.equals(KOREAN_SIZES.get(word)) && korean.contains(word);
        return present && english.stream().allMatch(size::equals)
                && korean.stream().map(KOREAN_SIZES::get).allMatch(size::equals);
    }

    private static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
    }
}
