package com.ondo.retail.order;

import com.ondo.retail.order.dto.ActionBadge;
import com.ondo.retail.order.dto.CancelOrderResponse;
import com.ondo.retail.order.dto.CheckoutResponse;
import com.ondo.retail.order.dto.OrderDetailResponse;
import com.ondo.retail.order.dto.OrderSummaryResponse;
import com.ondo.retail.order.dto.PaymentTerm;
import com.ondo.retail.order.dto.PlaceOrderResponse;
import com.ondo.retail.order.dto.ReceiveMethod;
import com.ondo.retail.order.dto.WholesalerWithBank;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 주문 API 의 가짜 응답. <b>껍데기다 — 로직이 없다.</b>
 *
 * <p>주문 접수는 멱등키 · 부분 접수 · 금액 집계가 얽혀 있어서 목으로 만들면 나중에 통째로
 * 다시 쓴다. 프론트가 화면 구조를 잡을 수 있게 응답 모양만 낸다. 로직은 W3 에.
 *
 * <p>클래스 이름에 Mock 을 박아둔 건 그때 검색으로 찾으려는 것이다.
 *
 * <p>일부러 "일부만 접수됨" 과 "일부만 취소됨" 을 섞어뒀다 — 프론트가 그 분기를 그려봐야 한다.
 */
@Component
public class MockOrderData {

    private static final WholesalerWithBank MOODON = new WholesalerWithBank(
            3L, "무드온", "청평화패션몰", "2층 24호", "국민", "123456-01-234567", "무드온");
    private static final WholesalerWithBank RAON = new WholesalerWithBank(
            9L, "라온", "APM", "3층 C-25", null, null, null);   // 계좌 미등록 — 현금만 고를 수 있다

    public CheckoutResponse checkout() {
        return new CheckoutResponse(
                List.of(
                        new CheckoutResponse.Group(MOODON, List.of(
                                new CheckoutResponse.Item(771L, 90231L, "빈티지 플라워 셔츠",
                                        "체리레드", "S", 5, 12500, 62500)), 62500),
                        new CheckoutResponse.Group(RAON, List.of(
                                new CheckoutResponse.Item(772L, 90251L, "와이드 데님 팬츠",
                                        "네이비", "M", 2, 31000, 62000)), 62000)),
                7, 124500);
    }

    /** 무드온은 접수되고 라온은 안 된 경우. 부분 접수 화면을 그려볼 수 있게 했다. */
    public PlaceOrderResponse place() {
        return new PlaceOrderResponse(
                5012L, "20260902-1420-0088", OffsetDateTime.now(), 62500,
                List.of(
                        new PlaceOrderResponse.Result(3L, "무드온", true, 88213L, 1, 62500,
                                null, null, false),
                        // 도매가 안 떠서 서버가 맡아 둔 줄. 장바구니에서 빠져 있다 (MUL-141)
                        new PlaceOrderResponse.Result(9L, "라온", false, null, null, null,
                                "UPSTREAM_UNAVAILABLE",
                                "도매처에 접수하지 못했어요. 다시 시도하고 있어요", true)));
    }

    public List<OrderSummaryResponse> orders() {
        return List.of(
                new OrderSummaryResponse(5012L, "20260902-1420-0088", OffsetDateTime.now(),
                        148000, 2, List.of("무드온", "라온"), 12, 3, 2, ActionBadge.READY_TO_PICK_UP),
                new OrderSummaryResponse(5008L, "20260901-1110-0087",
                        OffsetDateTime.now().minusDays(1), 62000, 1, List.of("라온"),
                        4, 0, 0, ActionBadge.PENDING_ACCEPT),
                new OrderSummaryResponse(5001L, "20260830-0930-0085",
                        OffsetDateTime.now().minusDays(3), 89000, 1, List.of("코튼클럽"),
                        6, 6, 0, ActionBadge.DONE));
    }

    public OrderDetailResponse detail(Long orderId) {
        return new OrderDetailResponse(
                orderId, "20260902-1420-0088", OffsetDateTime.now(), 148000,
                "김삼촌", "01098765432",
                List.of(new OrderDetailResponse.WholesalerOrder(
                        88213L, 1, MOODON,
                        new OrderDetailResponse.Status("PARTIALLY_SHIPPED", "부분 출고"),
                        PaymentTerm.CASH, ReceiveMethod.AGENT, 62500,
                        false,   // NEW 가 아니라 취소 불가
                        List.of(new OrderDetailResponse.Item(4410L, "빈티지 플라워 셔츠",
                                "체리레드", "S", 5, 12500, 62500, 3, 2,
                                LocalDate.now().plusDays(3))),
                        List.of(new OrderDetailResponse.Outbound(331L, "JG-20260902-001",
                                OffsetDateTime.now().minusHours(6),
                                List.of(new OrderDetailResponse.OutboundItem(
                                        "빈티지 플라워 셔츠", "체리레드", "S", 3)))))));
    }

    /** 하나는 취소되고 하나는 이미 확정된 경우. */
    public CancelOrderResponse cancel() {
        return new CancelOrderResponse(List.of(
                new CancelOrderResponse.Result(88219L, true, null, null),
                new CancelOrderResponse.Result(88213L, false, "ALREADY_CONFIRMED",
                        "이미 확정돼서 취소할 수 없어요. 도매처에 연락해 주세요")));
    }
}
