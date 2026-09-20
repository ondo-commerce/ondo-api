package com.ondo.retail.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.ondo.retail.cart.CartItemRepository;
import com.ondo.retail.cart.domain.CartItem;
import com.ondo.retail.order.domain.DispatchStatus;
import com.ondo.retail.order.domain.OrderGroup;
import com.ondo.retail.order.dto.PaymentTerm;
import com.ondo.retail.order.dto.PlaceOrderRequest;
import com.ondo.retail.order.dto.ReceiveMethod;
import com.ondo.retail.order.dto.WholesaleOrderCommand;
import com.ondo.retail.order.dto.WholesaleOrderReceipt;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 서버가 손을 뗄 때 장바구니로 돌려놓는지 (MUL-141).
 *
 * <p>규칙은 하나다 — <b>성공 말고는 다 돌려준다.</b> 대기함이 맡은 줄은 장바구니에서
 * 빠져 있으므로, 서버가 포기하고도 안 돌려주면 물건이 증발한다.
 *
 * <p>스케줄러는 테스트 전체에서 꺼져 있다(test 리소스의 application-local.yml).
 * 배경 워커와 경합하면 결과가 흔들린다.
 */
@SpringBootTest
class DispatchCartReturnTest {

    private static final long 소매처 = 1L;
    private static final long 무드온 = 101L;
    private static final long 옵션 = 9001L;

    @Autowired OrderDispatchWorker worker;
    @Autowired OrderDispatchStore store;
    @Autowired OrderGroupWriter writer;
    @Autowired OrderDispatchRepository dispatchRepository;
    @Autowired OrderGroupRepository orderGroupRepository;
    @Autowired CartItemRepository cartItemRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @MockitoBean OrderClient orderClient;

    private OrderGroup group;

    @AfterEach
    void 치운다() {
        if (group != null) {
            dispatchRepository.deleteAll(dispatchRepository.findByOrderGroupId(group.getId()));
            orderGroupRepository.deleteById(group.getId());
            group = null;
        }
        cartItemRepository.findByRetailerIdAndVariantId(소매처, 옵션)
                .ifPresent(cartItemRepository::delete);
    }

    @Test
    @DisplayName("기한을 넘기면 장바구니로 돌려놓는다")
    void 기한_만료면_돌려준다() {
        group = 대기_주문을_만든다("return-1");
        기한을_과거로_민다();

        worker.run();

        assertThat(상태()).isEqualTo(DispatchStatus.EXPIRED);
        assertThat(장바구니수량()).isEqualTo(3);
    }

    @Test
    @DisplayName("도매가 거절해도 돌려놓는다 — 사장님이 직접 정해야 한다")
    void 거절되면_돌려준다() {
        given(orderClient.place(any())).willReturn(
                WholesaleOrderReceipt.rejected("INSUFFICIENT_STOCK", "재고가 모자라요"));
        group = 대기_주문을_만든다("return-2");

        worker.run();

        assertThat(상태()).isEqualTo(DispatchStatus.REJECTED);
        assertThat(장바구니수량()).isEqualTo(3);
    }

    @Test
    @DisplayName("접수되면 돌려주지 않는다 — 이미 샀다")
    void 접수되면_안_돌려준다() {
        given(orderClient.place(any())).willReturn(WholesaleOrderReceipt.accepted(9L, 3, 37500));
        group = 대기_주문을_만든다("return-3");

        worker.run();

        assertThat(상태()).isEqualTo(DispatchStatus.SENT);
        assertThat(cartItemRepository.findByRetailerIdAndVariantId(소매처, 옵션)).isEmpty();
    }

    @Test
    @DisplayName("한 번 실패로는 안 돌려준다 — 아직 서버가 들고 있다")
    void 한_번_실패로는_안_돌려준다() {
        given(orderClient.place(any())).willReturn(
                WholesaleOrderReceipt.unreachable("UPSTREAM_UNAVAILABLE", "다시 시도하고 있어요"));
        group = 대기_주문을_만든다("return-4");

        worker.run();

        assertThat(상태()).isEqualTo(DispatchStatus.PENDING);
        assertThat(cartItemRepository.findByRetailerIdAndVariantId(소매처, 옵션)).isEmpty();
    }

    @Test
    @DisplayName("기다리는 사이 같은 옵션을 새로 담았으면 수량을 합친다")
    void 수량을_합친다() {
        group = 대기_주문을_만든다("return-5");
        cartItemRepository.save(CartItem.of(소매처, 옵션, 5));   // 사장님이 새로 담았다
        기한을_과거로_민다();

        worker.run();

        // 새로 담은 5 장이 사라져도, 못 보낸 3 장이 사라져도 안 된다
        assertThat(장바구니수량()).isEqualTo(8);
    }

    @Test
    @DisplayName("사장님이 대기를 취소하면 돌려놓고 다시 보내지 않는다")
    void 취소하면_돌려준다() {
        group = 대기_주문을_만든다("return-6");

        boolean cancelled = store.cancel(group.getId(), 무드온, 소매처);

        assertThat(cancelled).isTrue();
        assertThat(상태()).isEqualTo(DispatchStatus.CANCELLED);
        assertThat(장바구니수량()).isEqualTo(3);

        worker.run();
        org.mockito.Mockito.verify(orderClient, org.mockito.Mockito.never()).place(any());
    }

    @Test
    @DisplayName("남의 대기 건은 취소되지 않는다")
    void 남의_것은_못_취소한다() {
        group = 대기_주문을_만든다("return-7");

        assertThat(store.cancel(group.getId(), 무드온, 999L)).isFalse();
        assertThat(상태()).isEqualTo(DispatchStatus.PENDING);
    }

    // ── 거들기 ─────────────────────────────────────────────────

    private OrderGroup 대기_주문을_만든다(String key) {
        return writer.open(소매처, key, 요청(), 명령());
    }

    private DispatchStatus 상태() {
        return dispatchRepository
                .findByOrderGroupIdAndWholesalerId(group.getId(), 무드온).orElseThrow().getStatus();
    }

    private int 장바구니수량() {
        return cartItemRepository.findByRetailerIdAndVariantId(소매처, 옵션)
                .map(CartItem::getQty).orElse(0);
    }

    private void 기한을_과거로_민다() {
        jdbc.update("update retail.order_dispatch set expires_at = ? where order_group_id = ?",
                OffsetDateTime.now().minusMinutes(1), group.getId());
    }

    private static PlaceOrderRequest 요청() {
        return new PlaceOrderRequest(List.of(1L), null, null, List.of(
                new PlaceOrderRequest.WholesalerOption(무드온, PaymentTerm.CASH, ReceiveMethod.RETAILER)));
    }

    private static Function<OrderGroup, List<WholesaleOrderCommand>> 명령() {
        return g -> List.of(new WholesaleOrderCommand(
                g.getId(), 소매처, 무드온, "봄봄상회", null, "CASH", "RETAILER", null, null,
                List.of(new WholesaleOrderCommand.Line(옵션, 3, 12500))));
    }
}
