package com.ondo.wholesale.chat.inquiry;

import com.ondo.wholesale.chat.domain.ChatIntent;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    /** 한글 별칭 바로 뒤에 붙어도 되는 말. */
    private static final String PARTICLE = "(?:사이즈|이에요|에요|예요|이요|요|으로|로|은|는|이|가|을|를|도|랑|이랑|만)";
    /** 조사 뒤는 끝, 공백, 하이픈이 아닌 문장부호만. "프리미엄", "프리-미엄", "엠-사이즈"는 근거가 아니다. */
    private static final String ALIAS_TAIL = PARTICLE + "*(?![\\p{L}\\p{N}\\p{Pd}])";
    /** 열린 재고 되묻기에 사이즈만 답할 때 같이 와도 되는 낱말. 여기 없는 말이 섞이면 다른 질문으로 본다. */
    private static final List<String> REPLY_FILLERS = List.of("사이즈", "사이즈요", "주세요", "줘요", "요", "할게요",
            "부탁드려요", "부탁해요", "으로요", "로요", "으로", "로", "이요", "네", "넵");
    private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

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
        // 본문에 그대로 이어져 있지 않은 상품명은 흩어진 낱말을 이어 붙였거나 지어낸 이름이다("빈티지. 셔츠 말고")
        if (!tidy(body).contains(tidy(fields.productName()))) {
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
        String word = InquiryText.compact(InquiryText.normalize(evidence));
        String normalized = InquiryText.normalize(body);
        // 영문은 기존 토큰 경계로, 한글은 긴 별칭부터 읽는다. XL의 L이나 엑스라지의 라지는 근거가 아니다.
        List<String> english = BackorderCandidates.sizesIn(normalized);
        List<String> korean = BackorderCandidates.takeLongest(InquiryText.compact(normalized), SIZE_ALIASES).found();
        boolean present = size.equals(word) && english.contains(word)
                || size.equals(KOREAN_SIZES.get(word)) && saidAsWord(tidy(body), word);
        return present && english.stream().allMatch(size::equals)
                && korean.stream().map(KOREAN_SIZES::get).allMatch(size::equals);
    }

    /** 문장부호를 지우지 않은 본문에서 별칭이 독립 토큰이어야 근거다. 앞은 처음이나 공백, 뒤는 조사와 ALIAS_TAIL. */
    private static boolean saidAsWord(String tidy, String alias) {
        // 근거는 공백을 지운 글자로 오므로("엑스 라지요"의 엑스라지) 별칭 글자 사이 공백 한 칸은 허용한다
        return Pattern.compile("(?<!\\S)" + spaced(alias) + ALIAS_TAIL).matcher(tidy).find();
    }

    /**
     * 본문이 그 사이즈 하나와 허용한 낱말뿐인 짧은 답인가. "엠 사이즈 기장 어떻게 되나요?", "엠 = 사이즈?"는 아니다.
     * colors를 주면 그 색 이름도 낱말로 받는다("레드 M이요"). 비어 있으면 사이즈만 받는다.
     */
    static boolean sizeOnlyReply(String body, String size, List<String> colors) {
        String sizeWord = Stream.concat(KOREAN_SIZES.entrySet().stream().filter(e -> e.getValue().equals(size))
                        .map(e -> spaced(e.getKey())), Stream.of(Pattern.quote(size.toLowerCase(Locale.ROOT))))
                .collect(Collectors.joining("|"));
        String color = colors.stream().map(c -> Pattern.quote(tidy(c))).collect(Collectors.joining("|"));
        String word = "(?:" + String.join("|", REPLY_FILLERS)
                + (color.isEmpty() ? "" : "|(?:" + color + ")" + PARTICLE + "*") + ")";
        // 색과 사이즈를 붙여 쓴 "레드M이요"도 받는다
        String glued = color.isEmpty() ? "" : "(?:" + color + ")?";
        // 문장부호를 지우면 "엠 = 사이즈?"도 짧은 답이 된다. 문장 끝의 ? ! ~ . 만 허용한다
        return Pattern.compile("(?:" + word + " )*" + glued + "(?:" + sizeWord + ")" + PARTICLE + "*(?: " + word
                + ")*[?!~.]*").matcher(tidy(body)).matches();
    }

    private static String spaced(String alias) {
        return String.join(" ?", alias.split(""));
    }

    /** NFKC, 소문자, 공백 정리만 한다. 문장부호를 지우면 "빈티지. 셔츠"가 "빈티지 셔츠"로 읽힌다. */
    static String tidy(String text) {
        return SPACES.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT))
                .replaceAll(" ").strip();
    }
}
