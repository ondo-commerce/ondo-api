package com.ondo.retail.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.ondo.retail.order.domain.DispatchStatus;
import com.ondo.retail.order.domain.OrderDispatch;
import com.ondo.retail.order.domain.OrderGroup;
import com.ondo.retail.order.dto.PaymentTerm;
import com.ondo.retail.order.dto.PlaceOrderRequest;
import com.ondo.retail.order.dto.ReceiveMethod;
import com.ondo.retail.order.dto.WholesaleOrderCommand;
import com.ondo.retail.order.dto.WholesaleOrderReceipt;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 밀린 접수를 대신 보내는 워커 (MUL-140).
 *
 * <p><b>{@code @Transactional} 을 안 붙였다.</b> 워커는 집기 · 부르기 · 적기를 서로 다른
 * 트랜잭션으로 끊는데, 테스트를 트랜잭션으로 감싸면 그게 전부 한 트랜잭션에 말려들어
 * 실제 경로가 아닌 것을 통과시킨다. 도매 대시보드에서 같은 이유로 결함을 놓쳤다.
 * 그래서 커밋하고 돌린 뒤 직접 치운다.
 *
 * <p>도매는 가짜로 세운다. 여기서 볼 것은 대기함의 상태 전이지 도매 연동이 아니다.
 *
 * <p>스케줄러는 테스트 전체에서 꺼져 있다(test 리소스의 application-local.yml).
 * 배경으로 도는 워커와 테스트가 부르는 워커가 같은 줄을 두고 경합하면 어느 쪽이 잡는지가
 * 그때그때 달라진다.
 */
@SpringBootTest
class OrderDispatchWorkerTest {

    private static final long 소매처 = 1L;
    private static final long 무드온 = 101L;

    @Autowired OrderDispatchWorker worker;
    @Autowired OrderGroupWriter writer;
    @Autowired OrderDispatchRepository dispatchRepository;
    @Autowired OrderGroupRepository orderGroupRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired com.ondo.retail.cart.CartItemRepository cartItemRepository;

    @MockitoBean OrderClient orderClient;

    private OrderGroup group;

    /**
     * 트랜잭션 없이 돌아서 커밋된다. 다음 테스트에 남으면 안 된다.
     *
     * <p>장바구니까지 치우는 이유 — 거절·만료는 맡았던 물건을 장바구니로 되돌린다
     * (MUL-141). 대기함만 치우면 장바구니 행이 쌓여 다른 테스트를 흔든다.
     */
    @org.junit.jupiter.api.AfterEach
    void 치운다() {
        if (group != null) {
            dispatchRepository.deleteAll(dispatchRepository.findByOrderGroupId(group.getId()));
            orderGroupRepository.deleteById(group.getId());
            group = null;
        }
        cartItemRepository.findByRetailerIdAndVariantId(소매처, 9001L)
                .ifPresent(cartItemRepository::delete);
    }

    @Test
    @DisplayName("도매가 살아나면 밀린 건을 대신 보낸다")
    void 밀린_건을_보낸다() {
        given(orderClient.place(any())).willReturn(WholesaleOrderReceipt.accepted(9L, 3, 37500));
        group = 대기_주문을_만든다("worker-1");

        worker.run();

        assertThat(상태()).isEqualTo(DispatchStatus.SENT);
    }

    @Test
    @DisplayName("도매가 여전히 안 뜨면 다음 시각으로 미루고 시도 횟수를 올린다")
    void 실패하면_미룬다() {
        given(orderClient.place(any())).willReturn(
                WholesaleOrderReceipt.unreachable("UPSTREAM_UNAVAILABLE", "접수하지 못했어요"));
        group = 대기_주문을_만든다("worker-2");

        worker.run();

        OrderDispatch row = 대기줄();
        assertThat(row.getStatus()).isEqualTo(DispatchStatus.PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getNextAttemptAt()).isAfter(OffsetDateTime.now());
    }

    @Test
    @DisplayName("도매가 거절하면 다시 보내지 않는다")
    void 사업상_거절은_끝낸다() {
        given(orderClient.place(any())).willReturn(
                WholesaleOrderReceipt.rejected("INSUFFICIENT_STOCK", "재고가 모자라요"));
        group = 대기_주문을_만든다("worker-3");

        worker.run();

        assertThat(상태()).isEqualTo(DispatchStatus.REJECTED);
    }

    @Test
    @DisplayName("기한을 넘긴 건은 도매를 부르지도 않고 끝낸다 — 늦은 접수는 사고다")
    void 기한을_넘기면_부르지_않는다() {
        group = 대기_주문을_만든다("worker-4");
        기한을_과거로_민다();

        worker.run();

        OrderDispatch row = 대기줄();
        assertThat(row.getStatus()).isEqualTo(DispatchStatus.EXPIRED);
        assertThat(row.getAttempts()).isZero();          // 부르지 않았다
        assertThat(row.getLastError()).contains("기한");
    }

    @Test
    @DisplayName("집는 동안 다음 시각을 밀어 둬 다른 워커가 같은 줄을 못 가져간다")
    void 집은_줄은_잠깐_안_보인다() {
        // 도매를 부르는 사이 다른 워커가 도는 상황을 흉내 낸다
        given(orderClient.place(any())).willAnswer(invocation -> {
            worker.run();                                 // 두 번째 워커
            return WholesaleOrderReceipt.accepted(9L, 3, 37500);
        });
        group = 대기_주문을_만든다("worker-5");

        worker.run();

        // 두 번째 워커가 같은 줄을 집었다면 도매를 두 번 불렀을 것이다
        org.mockito.Mockito.verify(orderClient, org.mockito.Mockito.times(1)).place(any());
        assertThat(상태()).isEqualTo(DispatchStatus.SENT);
    }

    // ── 거들기 ─────────────────────────────────────────────────

    private OrderGroup 대기_주문을_만든다(String key) {
        return writer.open(소매처, key, 요청(), 명령());
    }

    private OrderDispatch 대기줄() {
        return dispatchRepository
                .findByOrderGroupIdAndWholesalerId(group.getId(), 무드온).orElseThrow();
    }

    private DispatchStatus 상태() {
        return 대기줄().getStatus();
    }

    /** 기한이 지난 상태를 만든다. 30분을 기다릴 수 없으니 DB 를 직접 민다. */
    private void 기한을_과거로_민다() {
        jdbc.update("update retail.order_dispatch set expires_at = ? where id = ?",
                OffsetDateTime.now().minusMinutes(1), 대기줄().getId());
    }

    private static PlaceOrderRequest 요청() {
        return new PlaceOrderRequest(List.of(1L), null, null, List.of(
                new PlaceOrderRequest.WholesalerOption(무드온, PaymentTerm.CASH, ReceiveMethod.RETAILER)));
    }

    private static Function<OrderGroup, List<WholesaleOrderCommand>> 명령() {
        return group -> List.of(new WholesaleOrderCommand(
                group.getId(), 소매처, 무드온, "봄봄상회", null, "CASH", "RETAILER", null, null,
                List.of(new WholesaleOrderCommand.Line(9001L, 3, 12500))));
    }
}
