package com.ondo.retail.order;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 대기 중인 주문을 언제까지 보낼 수 있는가 (MUL-139).
 *
 * <p><b>왜 기한이 필요한가</b> — 메시지는 늦게 처리돼도 가치가 남지만 주문은 아니다.
 * 접수 실패를 본 소매처는 다른 도매에서 이미 샀을 수 있고, 그 뒤에 자동 접수되면
 * 같은 물건을 두 번 사게 된다. 늦은 성공이 실패보다 나쁜 경우다.
 *
 * <p>두 선 중 이른 쪽을 쓴다.
 *
 * <ul>
 *   <li><b>대기 한도</b> — 소매처를 묶어둘 수 있는 시간. 길게 잡을수록 그동안 다른
 *       곳에서 살 기회를 뺏는 셈이라, 결국 못 넣으면 손해가 커진다.
 *   <li><b>영업일 경계</b> — 넘기면 안 되는 선. 11:50 에 누른 주문이 12:10 에 접수되면
 *       어제 주문이 오늘 주문으로 집계된다. 소매 화면의 날짜와 도매 장부의 날짜가
 *       어긋나고, 도매 대시보드의 "오늘 주문" 에도 다른 날로 잡힌다.
 * </ul>
 *
 * <p>영업일 경계는 자정이 아니라 <b>KST 낮 12시</b>다 — 동대문 도매는 저녁에 열어
 * 새벽에 마치므로 자정으로 끊으면 하룻밤 주문이 이틀로 갈린다. 도매 쪽
 * {@code BusinessDay} 와 같은 규칙이어야 한다. DB 가 갈라져 있어 코드를 공유하지
 * 못하므로 규칙을 양쪽에 각각 적어 둔다.
 */
public final class DispatchDeadline {

    private DispatchDeadline() {}

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime OPENING = LocalTime.NOON;

    /**
     * @param orderedAt 주문을 누른 시각
     * @param maxWait   대기 한도. 이만큼 지나면 영업일이 남았어도 포기한다
     */
    public static OffsetDateTime of(OffsetDateTime orderedAt, Duration maxWait) {
        OffsetDateTime byWait = orderedAt.plus(maxWait);
        OffsetDateTime byBusinessDay = nextBoundaryAfter(orderedAt);
        return byWait.isBefore(byBusinessDay) ? byWait : byBusinessDay;
    }

    /** 이 시각이 속한 영업일이 끝나는 때 = 다음 낮 12시(KST). */
    static OffsetDateTime nextBoundaryAfter(OffsetDateTime at) {
        ZonedDateTime kst = at.atZoneSameInstant(KST);
        // 낮 12시 전이면 오늘 12시가 경계다. 12시 이후면 내일 12시.
        LocalDate boundaryDay = kst.toLocalTime().isBefore(OPENING)
                ? kst.toLocalDate()
                : kst.toLocalDate().plusDays(1);
        return boundaryDay.atTime(OPENING).atZone(KST).toOffsetDateTime();
    }
}
