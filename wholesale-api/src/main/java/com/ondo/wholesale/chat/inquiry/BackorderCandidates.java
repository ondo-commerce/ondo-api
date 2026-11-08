package com.ondo.wholesale.chat.inquiry;

import com.ondo.wholesale.chat.inquiry.InquiryInput.Backorder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 미송 자동 답의 후보·날짜·본문 좁히기 (MUL-157). 웹 목 {@code backorderCandidates.ts} 를 옮겼다.
 *
 * <p>후보 원본은 거르지 않은 것을 받는다. 이 방 도매처와 이 소매처 것만 고르는 줄({@link #candidatesOf})이 핵심이다 —
 * 지우면 남의 미송이 칩에 뜬다.
 */
final class BackorderCandidates {

    private BackorderCandidates() {
    }

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 미송 되묻기 보기 상한. 넘치면 오래된 것부터 이만큼. */
    static final int CHOICE_MAX = 8;

    /** 이 방 도매처 + 이 소매처의 열린 미송(남은 수량 > 0). 주문 시각 오름차순, 같으면 id 오름차순. */
    static List<Backorder> candidatesOf(List<Backorder> source, long wholesalerId, long retailerId) {
        return source.stream()
                .filter(b -> b.wholesalerId() == wholesalerId && b.retailerId() == retailerId)
                .filter(b -> b.remainingQty() > 0)
                .sorted(Comparator.comparing(Backorder::orderedAt).thenComparingLong(Backorder::backorderId))
                .toList();
    }

    /** "11월 5일", 다른 해면 "2027년 1월 5일". */
    static String koreanDate(LocalDate date, LocalDate today) {
        String head = date.getYear() == today.getYear() ? "" : date.getYear() + "년 ";
        return head + date.getMonthValue() + "월 " + date.getDayOfMonth() + "일";
    }

    /** 칩 주문일 "9/7", 다른 해면 "2025/12/30". 주문 시각은 서울 날짜로 읽는다. */
    static String slashDate(Instant at, LocalDate today) {
        LocalDate d = LocalDate.ofInstant(at, SEOUL);
        String head = d.getYear() == today.getYear() ? "" : d.getYear() + "/";
        return head + d.getMonthValue() + "/" + d.getDayOfMonth();
    }

    /** 칩 글자. "린넨 셋업 자켓 베이지/L, 남은 2장, 9/7 주문 #7003". 고르면 이 글자가 본문이다. */
    static String choiceLabel(Backorder b, LocalDate today) {
        return "%s %s/%s, 남은 %d장, %s 주문 #%d".formatted(
                b.title(), b.color(), b.size(), b.remainingQty(), slashDate(b.orderedAt(), today), b.orderId());
    }

    /* --- 본문 좁히기 -------------------------------------------------------- */

    /** 색상 사전. 후보 색상만으로는 "블랙 셔츠" 의 블랙을 못 읽는다(셔츠 미송이 화이트뿐이면). 별칭은 안 받는다. */
    private static final List<String> COLOR_WORDS = List.of(
            "블랙", "화이트", "아이보리", "크림", "베이지", "그레이", "차콜", "네이비", "블루", "소라", "스카이",
            "레드", "와인", "핑크", "오렌지", "옐로우", "카키", "그린", "민트", "브라운", "카멜", "퍼플", "라벤더");

    /** 미송 질문에 흔히 붙는 말. 상품명·색상·사이즈를 지우고 남은 낱말이 여기 없으면 <b>모르는 말</b>이다. */
    private static final Set<String> FILLER_WORDS = Set.of(
            "미송", "미송건", "언제", "언제쯤", "와요", "오나요", "와", "들어와요", "들어오나요", "입고", "입고일",
            "예정", "날짜", "주문", "주문한", "남은", "거", "건", "그거", "이거", "사이즈", "장", "혹시", "아직");

    /** 낱말 끝 조사 하나. */
    private static final Pattern TRAILING_PARTICLE = Pattern.compile("(이에요|에요|예요|이요|요|은|는|이|가|을|를|도|랑|이랑)$");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final Pattern ANY_DIGIT = Pattern.compile("[0-9]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    record Taken(List<String> found, String rest) {
    }

    /** 겹치는 말은 긴 것부터 지운다. 지운 자리는 공백 — 앞뒤 글자가 붙어 새 낱말이 되지 않게. */
    static Taken takeLongest(String text, List<String> vocab) {
        List<String> words = new ArrayList<>(new LinkedHashSet<>(vocab));
        words.sort(Comparator.comparingInt(String::length).reversed());
        List<String> found = new ArrayList<>();
        String rest = text;
        for (String w : words) {
            if (w.isEmpty() || !rest.contains(w)) {
                continue;
            }
            found.add(w);
            rest = rest.replace(w, " ");
        }
        return new Taken(found, rest);
    }

    /** 상품명·색상·사이즈를 지운 뒤 남은 낱말 중 흔한 말도 조사도 아닌 것. */
    static List<String> unknownWords(String rest) {
        String stripped = DIGITS.matcher(InquiryText.SIZE_TOKEN.matcher(rest).replaceAll(" ")).replaceAll(" ");
        return Arrays.stream(WHITESPACE.split(stripped))
                .filter(t -> !t.isEmpty())
                .map(t -> FILLER_WORDS.contains(t) ? "" : TRAILING_PARTICLE.matcher(t).replaceFirst(""))
                .filter(t -> !t.isEmpty() && !FILLER_WORDS.contains(t))
                .toList();
    }

    static List<String> sizesIn(String normalized) {
        List<String> sizes = new ArrayList<>();
        Matcher m = InquiryText.SIZE_TOKEN.matcher(normalized);
        while (m.find()) {
            sizes.add(m.group(1));
        }
        return sizes;
    }

    /**
     * 본문이 대상(색상·사이즈·숫자·모르는 말)을 말했는가. 미송 후보가 0일 때 포장 답을 낼지 가른다 —
     * 대상을 말한 질문에 출고 준비 중 주문 전부를 답하면 소매가 묻지 않은 주문까지 안내한다.
     */
    static boolean mentionsTarget(String normalized) {
        Taken byColor = takeLongest(normalized, COLOR_WORDS);
        return !byColor.found().isEmpty()
                || !sizesIn(normalized).isEmpty()
                || ANY_DIGIT.matcher(normalized).find()
                || !unknownWords(byColor.rest()).isEmpty();
    }

    /**
     * @param said      상품명 낱말·색상·사이즈 중 하나라도 나왔다
     * @param left      나온 말을 전부 만족하는 후보(입력 순서 그대로)
     * @param confirmed 남은 게 1개이고 색상·사이즈가 하나씩이고 모르는 말이 없다
     * @param blocked   확정 조건은 맞는데 모르는 말 때문에 확정하지 않았다 → 부르는 쪽이 넘긴다(NO_MATCH)
     */
    record Narrowed(boolean said, List<Backorder> left, Backorder confirmed, boolean blocked) {
    }

    /** {@code normalized} 는 정규화를 거친 글. 상품명 낱말은 후보 상품명을 공백으로 자른 2글자 이상 낱말이다. */
    static Narrowed narrow(String normalized, List<Backorder> pool) {
        List<String> titleWords = pool.stream()
                .flatMap(b -> Arrays.stream(WHITESPACE.split(b.title())).filter(w -> w.length() >= 2))
                .toList();
        Taken byTitle = takeLongest(normalized, titleWords);
        List<String> colorVocab = new ArrayList<>(COLOR_WORDS);
        pool.forEach(b -> colorVocab.add(b.color()));
        Taken byColor = takeLongest(byTitle.rest(), colorVocab);
        List<String> words = byTitle.found();
        List<String> colors = byColor.found();
        List<String> sizes = List.copyOf(new LinkedHashSet<>(sizesIn(normalized)));
        boolean said = words.size() + colors.size() + sizes.size() > 0;
        List<Backorder> left = pool.stream()
                .filter(b -> {
                    List<String> titleParts = Arrays.asList(WHITESPACE.split(b.title()));
                    return words.stream().allMatch(titleParts::contains)
                            && (colors.isEmpty() || colors.contains(b.color()))
                            && (sizes.isEmpty() || sizes.contains(b.size()));
                })
                .toList();
        // 색상이나 사이즈가 둘 이상이면 어느 색에 어느 사이즈인지(쌍)를 모른다 — 각각 하나일 때만 확정한다
        Backorder pair = left.size() == 1 && colors.size() == 1 && sizes.size() == 1 ? left.getFirst() : null;
        boolean blocked = pair != null && !unknownWords(byColor.rest()).isEmpty();
        return new Narrowed(said, left, blocked ? null : pair, blocked);
    }
}
