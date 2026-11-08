package com.ondo.wholesale.retailnotification;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * 미송 입고일 변경 알림 문구 (MUL-155). 순수 함수만 둔다.
 *
 * <p>웹 목({@code ondo-web packages/api/src/mocks/retail/dateChangeNotice.ts})이 "목 규칙 = BE 규칙"
 * 이라 적어 둔 것과 글자까지 같게 만든다.
 *
 * <ul>
 *   <li>title — {@code "무드온 미송 입고일 변경"}. 도매처 이름만 들어간다</li>
 *   <li>body — {@code "린넨 셋업 자켓 베이지/L 남은 2장, 11/5(목) → 11/8(일)"}.
 *       수량은 <b>받는 소매처 자기 몫</b>의 남은 합이다. 남의 소매처 이름·수량, SKU 합계,
 *       판매가능 수량을 넣을 자리가 애초에 없다(인자에 없다)</li>
 *   <li>날짜는 {@code M/D(요일)}, 오늘과 해가 다르면 {@code YYYY/M/D(요일)}, 없으면 {@code 입고일 미정}</li>
 * </ul>
 */
public final class BackorderDateChangeText {

    private BackorderDateChangeText() {
    }

    public static String title(String wholesalerName) {
        return wholesalerName + " 미송 입고일 변경";
    }

    /**
     * @param remainingQty 이 소매처의 그 SKU OPEN 미송 남은 합
     * @param today        서울 날짜. 해가 다른지만 가른다 — 시계를 여기서 읽지 않는다
     */
    public static String body(String title, String colorName, String size, int remainingQty,
                              LocalDate before, LocalDate after, LocalDate today) {
        return "%s %s/%s 남은 %d장, %s → %s".formatted(
                title, colorName, size, remainingQty, etaLabel(before, today), etaLabel(after, today));
    }

    static String etaLabel(LocalDate date, LocalDate today) {
        if (date == null) {
            return "입고일 미정";
        }
        String head = date.getYear() == today.getYear() ? "" : date.getYear() + "/";
        String weekday = date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN);
        return "%s%d/%d(%s)".formatted(head, date.getMonthValue(), date.getDayOfMonth(), weekday);
    }
}
