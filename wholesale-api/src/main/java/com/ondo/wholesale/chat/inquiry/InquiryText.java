package com.ondo.wholesale.chat.inquiry;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 문의 본문 정규화와 낱말 표 (MUL-157). 웹 목 {@code packages/api/src/mocks/chat/inquiry.ts} 의 규칙을 그대로 옮겼다
 * (목 규칙 = BE 규칙, 골든셋 {@code inquiry-golden.json} 이 심판이다).
 *
 * <p>정규 표현식은 JS 와 같은 뜻이 되게 맞췄다. JS {@code \d} 는 유니코드 플래그가 있어도 ASCII 숫자뿐이라
 * {@code [0-9]} 로 적고, JS {@code \s} 는 늘 유니코드 공백이라 {@link Pattern#UNICODE_CHARACTER_CLASS} 를 켠다.
 *
 * <p>낱말 표를 넓히는 쪽이 위험하다. 여기 없는 말은 사장에게 넘어갈 뿐이고, 잘못 넣은 말은 틀린 자동 답이 된다.
 */
final class InquiryText {

    private InquiryText() {
    }

    private static final Pattern EMOJI = Pattern.compile("[\\p{IsExtended_Pictographic}\\x{FE0F}\\x{200D}]");
    /** 문장부호는 지우지 않고 공백으로 — "레드/M" 이 "레드M" 한 낱말이 되지 않게. */
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}\\s]", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /** 오타 사전. <b>목록에 있는 것만</b> 고친다. */
    private static final List<String[]> TYPO_FIXES = List.of(
            new String[]{"남앗", "남았"},
            new String[]{"잇어요", "있어요"},
            new String[]{"몇쟝", "몇 장"});

    static final Pattern SIZE_TOKEN = Pattern.compile("(?<![A-Z0-9])(2XL|XS|XL|S|M|L|FREE)(?![A-Z0-9])");
    static final List<String> SIZE_ORDER = List.of("XS", "S", "M", "L", "XL", "2XL", "FREE");
    static final Pattern QTY_IN_TEXT = Pattern.compile("[0-9]+\\s*장", Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * 판정 입력. 정규화 표 6행: NFKC, 이모지 제거, 문장부호 → 공백, 대문자, 오타 사전, 공백 접기.
     * 색상 별칭("빨강")과 한글 수사("열 장")는 받지 않는다 — 고르지 못하면 되묻거나 넘긴다.
     */
    static String normalize(String text) {
        String t = Normalizer.normalize(text, Normalizer.Form.NFKC);
        t = EMOJI.matcher(t).replaceAll("");
        t = NOT_WORD.matcher(t).replaceAll(" ");
        t = t.toUpperCase(Locale.ROOT);
        for (String[] fix : TYPO_FIXES) {
            t = t.replace(fix[0], fix[1]);
        }
        return SPACES.matcher(t).replaceAll(" ").strip();
    }

    /** 정규화 뒤 공백을 지운 글. 정규화가 공백을 한 칸으로 접었으니 ' ' 만 지우면 된다. */
    static String compact(String normalized) {
        return SPACES.matcher(normalized).replaceAll("");
    }

    static boolean containsAny(String flat, List<String> words) {
        for (String w : words) {
            if (flat.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /* --- 협상어 · 사람 요청 · 입금 알림 ------------------------------------------ */

    /**
     * 협상어. 하나라도 있으면 칩이 있어도 넘긴다. "외상"은 통째로 넣지 않는다 — "외상 얼마예요?" 는 미수 금액 질문이다.
     * "미뤄" 도 요청형만 — "미송 미뤄졌나요?" 는 미송 질문이다.
     */
    static final List<String> NEGOTIATION_WORDS = List.of(
            "깎", "할인", "네고", "교환", "반품", "환불", "외상으로", "외상돼",
            "외상되", "외상될", "외상해주", "외상할수", "외상가능",
            "디씨", "에누리", "빼주", "싸게", "낼게", "미뤄주", "미뤄줄", "미뤄도");

    /** "DC". 앞뒤가 라틴 글자나 숫자가 아닐 때만 — 품번 "DC100", 상품명 "ADC 셔츠" 를 피한다. 공백 있는 본문을 본다. */
    static final Pattern NEGOTIATION_DC = Pattern.compile("(?<![\\p{IsLatin}\\p{N}])DC(?![\\p{IsLatin}\\p{N}])");

    /**
     * 사람 요청어. 사장·직원과 직접 이야기하겠다는 <b>요청형</b>만. "직원이 입금했어요"(입금 알림),
     * "사장님, 제 미수 얼마예요?"(호칭)는 안 걸린다.
     */
    private static final List<String> HUMAN_REQUEST_WORDS = List.of(
            "사장님이랑", "사장님과", "직원분이랑", "직원분과", "직원분바꿔", "직원바꿔", "직원분연결", "직원연결",
            "직원분부탁", "직원부탁", "직원분불러", "직원불러", "사장님바꿔", "직원이랑", "직원과",
            "전화주", "전화줘", "전화부탁", "전화가능", "전화해");
    private static final Pattern HUMAN_REQUEST_FILLERS = Pattern.compile("좀|한번");
    /** "상담", "통화" 는 낱말 머리일 때만 — "보통 화이트" 가 "보통화이트" 로 붙어 "통화" 를 만들지 않게. */
    private static final Pattern HUMAN_REQUEST_HEAD = Pattern.compile("(?<![\\p{L}\\p{N}])(?:상담|통화)");

    static boolean isHumanRequest(String text, String flat) {
        if (HUMAN_REQUEST_HEAD.matcher(text).find()) {
            return true;
        }
        String bare = HUMAN_REQUEST_FILLERS.matcher(flat).replaceAll("");
        return containsAny(bare, HUMAN_REQUEST_WORDS);
    }

    static boolean isNegotiation(String text, String flat) {
        return containsAny(flat, NEGOTIATION_WORDS) || NEGOTIATION_DC.matcher(text).find();
    }

    /** 입금 알림. 의도 둘보다, 미수보다 먼저 본다 — 입금 등록 전 잔액을 말하면 틀린 금액이다. */
    static final List<String> PAYMENT_REPORT_WORDS = List.of("입금했", "송금했", "이체했", "입금완료");

    /* --- 의도 키워드 -------------------------------------------------------- */

    static final List<String> STOCK_WORDS = List.of("몇장", "남았", "재고", "수량", "품절");
    /** 옵션어가 같이 있을 때만 재고로 읽는 말. */
    static final List<String> STOCK_WORDS_WITH_OPTION = List.of("있어요", "있나요", "되나요");
    static final List<String> BACKORDER_WORDS = List.of("미송", "언제와", "입고", "언제들어");
    /** "얼마" 만으로는 미수가 아니다("이 셔츠 얼마예요?" 는 값 문의). */
    static final List<String> RECEIVABLE_WORDS = List.of("미수", "외상", "잔금", "갚을", "잔액", "갚아야", "밀린");
    /** 배송어. 미송 답은 "도매 입고 예정" 만 말한다 — 출고·배송·수령은 다른 단계라 같은 날짜로 답하면 틀린다. */
    static final List<String> DELIVERY_WORDS = List.of("출고", "배송", "택배", "도착", "보내", "받");

    /* --- 기간 말 ------------------------------------------------------------ */

    /**
     * 기간 말. 미수 질문에 붙으면 넘긴다 — 달별로 나눌 규칙이 없다. <b>일부러 넓게</b> 잡는다: 기간이 아닌 말을
     * 기간으로 읽으면 넘어갈 뿐이고, 기간을 놓치면 틀린 금액이 나간다. "지금" 은 넣지 않는다.
     */
    private static final List<String> PERIOD_WORDS = List.of(
            "이번달", "요번달", "지난달", "저번달", "다음달", "담달", "월말", "월초", "말일", "초순", "중순", "하순",
            "상순", "월중", "이달", "지지난달", "다다음달",
            "전월", "전전월", "당월", "금월", "익월", "차월",
            "이번주", "요번주", "지난주", "저번주", "다음주", "금주", "차주", "전주", "주초", "주중", "주말", "요일",
            "오늘", "어제", "그제", "그저께", "엊그제", "내일", "모레", "어저께", "금일", "당일", "전일", "작일",
            "익일", "명일",
            "올해", "금년", "당해", "당년", "작년", "재작년", "전년", "지난해", "내년", "명년", "익년", "후년",
            "연말", "연초", "연중", "상반기", "하반기", "분기",
            "지난번", "저번", "요전", "최근", "요즘");
    private static final List<Pattern> PERIOD_PATTERNS = List.of(
            Pattern.compile("[0-9]+(월|일|주|개월|달|년|분기)"),
            Pattern.compile("(한|두|세|석|네|넉|몇|여러)(달|주|개월)"),
            Pattern.compile("(일|이|삼|사|오|유|육|칠|팔|구|시|십|십일|십이)월(달|분|에|미수|외상|잔금|잔액|거)"));
    /** 날짜 모양 "9/7", "9.7", "10-29". 문장부호가 공백이 되기 전 원문(NFKC 만)에서 찾는다. 금액 쉼표는 아니다. */
    private static final Pattern PERIOD_DATE =
            Pattern.compile("[0-9]{1,4}\\s*[/.-]\\s*[0-9]{1,2}(?![0-9])", Pattern.UNICODE_CHARACTER_CLASS);

    static boolean hasPeriodWord(String body) {
        String raw = Normalizer.normalize(body, Normalizer.Form.NFKC);
        String flat = compact(normalize(body));
        if (containsAny(flat, PERIOD_WORDS)) {
            return true;
        }
        for (Pattern p : PERIOD_PATTERNS) {
            if (p.matcher(flat).find()) {
                return true;
            }
        }
        return PERIOD_DATE.matcher(raw).find();
    }

    /* --- 미수 금액 문장 ------------------------------------------------------ */

    /**
     * 미수 금액을 답하는 <b>문장 전체</b>(D41). 정규화해 이 집합에 정확히 있을 때만 금액을 말한다.
     * 소매가 <b>자기가 갚을 돈</b>을 묻는 게 분명한 문장만 적는다. '잔액' 이 든 문장은 넣지 않는다(선수금 잔액으로도 읽힌다).
     */
    static final List<String> RECEIVABLE_AMOUNT_QUESTIONS = List.of(
            "지금 미수 얼마예요?",
            "미수 얼마예요?",
            "외상 얼마 있어요?",
            "제 미수 얼마예요?",
            "사장님, 제 미수 얼마예요?",
            "미수 얼마",
            "미수 금액 알려주세요",
            "제 외상 얼마 있어요?",
            "외상 얼마예요?",
            "지금 외상 얼마예요?",
            "제 외상 얼마예요?",
            "갚을 돈 얼마예요?",
            "제가 갚을 돈 얼마예요?");

    private static final Set<String> RECEIVABLE_AMOUNT_KEYS = RECEIVABLE_AMOUNT_QUESTIONS.stream()
            .map(q -> compact(normalize(q)))
            .collect(Collectors.toUnmodifiableSet());

    static boolean isReceivableAmountQuestion(String body) {
        return RECEIVABLE_AMOUNT_KEYS.contains(compact(normalize(body)));
    }
}
