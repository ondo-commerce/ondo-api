package com.ondo.retail.order;

import com.ondo.retail.cart.CartItemRepository;
import com.ondo.retail.cart.domain.CartItem;
import com.ondo.retail.common.error.BusinessException;
import com.ondo.retail.listing.ListingClient;
import com.ondo.retail.listing.dto.VariantInfo;
import com.ondo.retail.order.domain.OrderGroup;
import com.ondo.retail.order.dto.PaymentTerm;
import com.ondo.retail.order.dto.PlaceOrderRequest;
import com.ondo.retail.order.dto.PlaceOrderResponse;
import com.ondo.retail.order.dto.ReceiveMethod;
import com.ondo.retail.order.dto.WholesaleOrderCommand;
import com.ondo.retail.order.dto.WholesaleOrderReceipt;
import com.ondo.retail.retailer.RetailerRepository;
import com.ondo.retail.retailer.domain.Retailer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 소매 주문 접수 조립 (MUL-98).
 *
 * <p>도매 호출과 DB 는 각자 자기 테스트에서 보고, 여기서는 <b>여러 도매처의 결과를
 * 어떻게 합치는지</b>만 확인한다. 부분 성공이 이 기능의 핵심이라 대부분 그걸 본다.
 */
class OrderPlaceServiceTest {

    private static final long 소매처 = 42L;
    private static final long 무드온 = 101L;
    private static final long 라온 = 102L;

    private final OrderGroupWriter writer = mock(OrderGroupWriter.class);
    private final CartItemRepository cartItemRepository = mock(CartItemRepository.class);
    private final RetailerRepository retailerRepository = mock(RetailerRepository.class);
    private final ListingClient listingClient = mock(ListingClient.class);
    private final OrderClient orderClient = mock(OrderClient.class);
    private final OrderDispatchStore dispatchStore = mock(OrderDispatchStore.class);

    private final OrderPlaceService service = new OrderPlaceService(
            writer, cartItemRepository, retailerRepository, listingClient, orderClient, dispatchStore);

    /** 장바구니 2줄 — 무드온 1줄(3장×12500) · 라온 1줄(2장×31000). */
    private final CartItem 무드온줄 = 장바구니줄(1L, 9001L, 3);
    private final CartItem 라온줄 = 장바구니줄(2L, 9002L, 2);

    @BeforeEach
    void 기본_설정() {
        given(cartItemRepository.findAllById(any())).willReturn(List.of(무드온줄, 라온줄));
        given(listingClient.findVariants(any())).willReturn(Map.of(
                9001L, 옵션(9001L, 무드온, "무드온", 12500),
                9002L, 옵션(9002L, 라온, "라온", 31000)));
        given(retailerRepository.findById(소매처)).willReturn(java.util.Optional.of(소매처_객체()));
        given(writer.open(anyLong(), any(), any(), any())).willReturn(주문서(5001L, "20260906-1420-0001"));
        given(writer.settle(anyLong(), anyLong(), anyBoolean(), any(), any()))
                .willAnswer(inv -> 주문서(5001L, "20260906-1420-0001"));
        given(writer.findAccepted(any())).willReturn(java.util.Optional.empty());
    }

    @Test
    @DisplayName("도매처마다 한 번씩 부르고 결과를 합친다")
    void 도매처별로_부른다() {
        given(orderClient.place(any())).willAnswer(inv -> {
            WholesaleOrderCommand c = inv.getArgument(0);
            return WholesaleOrderReceipt.accepted(880L + c.wholesalerId(), 1,
                    c.items().getFirst().qty() * c.items().getFirst().expectedUnitPrice());
        });

        PlaceOrderResponse response = service.place(소매처, "key-1", 요청());

        assertThat(response.results()).hasSize(2);
        assertThat(response.results()).allMatch(PlaceOrderResponse.Result::isAccepted);
        // 3×12500 + 2×31000
        assertThat(response.totalAmount()).isEqualTo(99500);
        assertThat(response.orderNo()).isEqualTo("20260906-1420-0001");
    }

    @Test
    @DisplayName("한 곳이 거절해도 나머지는 접수된다 — 부분 성공은 에러가 아니다")
    void 부분_성공은_성공이다() {
        given(orderClient.place(any())).willAnswer(inv -> {
            WholesaleOrderCommand c = inv.getArgument(0);
            return c.wholesalerId() == 무드온
                    ? WholesaleOrderReceipt.accepted(8801L, 1, 37500)
                    : WholesaleOrderReceipt.rejected("PRICE_CHANGED", "판매가가 바뀌었습니다.");
        });

        PlaceOrderResponse response = service.place(소매처, "key-2", 요청());

        assertThat(response.results()).extracting(PlaceOrderResponse.Result::isAccepted)
                .containsExactly(true, false);
        // 실패한 도매처 금액은 빠진다
        assertThat(response.totalAmount()).isEqualTo(37500);

        PlaceOrderResponse.Result 라온결과 = response.results().get(1);
        assertThat(라온결과.reason()).isEqualTo("PRICE_CHANGED");
        // 도매가 만든 문구를 우리가 다시 쓰지 않는다
        assertThat(라온결과.message()).isEqualTo("판매가가 바뀌었습니다.");
    }

    @Test
    @DisplayName("도매가 거절한 줄은 장바구니에 그대로 둔다")
    void 거절된_줄은_장바구니에_남는다() {
        given(orderClient.place(any())).willAnswer(inv -> {
            WholesaleOrderCommand c = inv.getArgument(0);
            return c.wholesalerId() == 무드온
                    ? WholesaleOrderReceipt.accepted(8801L, 1, 37500)
                    : WholesaleOrderReceipt.rejected("PRICE_CHANGED", "판매가가 바뀌었습니다.");
        });

        service.place(소매처, "key-3", 요청());

        // 다시 해도 같은 답이 오니 서버가 맡지 않는다. 사장님이 직접 정해야 한다
        verify(writer).settle(5001L, 37500L, true, List.of(무드온줄), List.of());
    }

    @Test
    @DisplayName("도매가 안 떠서 서버가 맡은 줄도 장바구니에서 뺀다 — 둘이 각자 주문하면 안 된다")
    void 서버가_맡은_줄도_뺀다() {
        given(orderClient.place(any())).willAnswer(inv -> {
            WholesaleOrderCommand c = inv.getArgument(0);
            return c.wholesalerId() == 무드온
                    ? WholesaleOrderReceipt.accepted(8801L, 1, 37500)
                    : WholesaleOrderReceipt.unreachable("UPSTREAM_UNAVAILABLE", "다시 시도하고 있어요");
        });

        PlaceOrderResponse response = service.place(소매처, "key-3b", 요청());

        verify(writer).settle(5001L, 37500L, true, List.of(무드온줄), List.of(라온줄));
        // 끝난 실패가 아니라는 걸 화면이 알아야 한다
        assertThat(response.results().get(1).isPending()).isTrue();
    }

    @Test
    @DisplayName("전부 거절되면 502 다. 장바구니는 그대로 남는다")
    void 전부_거절되면_502다() {
        given(orderClient.place(any())).willReturn(
                WholesaleOrderReceipt.rejected("PRICE_CHANGED", "판매가가 바뀌었습니다."));

        assertThatThrownBy(() -> service.place(소매처, "key-4", 요청()))
                .isInstanceOf(BusinessException.class);

        // 주문서는 FAILED 로 남긴다 — 지우면 왜 실패했는지도 멱등키도 사라진다
        verify(writer).settle(5001L, 0L, false, List.of(), List.of());
    }

    @Test
    @DisplayName("전부 못 들어가면 재시도를 켜지 않는다 — 안 보이는 주문이 나중에 생기면 안 된다")
    void 전부_실패면_대기함을_접는다() {
        given(orderClient.place(any())).willReturn(
                WholesaleOrderReceipt.unreachable("UPSTREAM_UNAVAILABLE", "다시 시도하고 있어요"));

        assertThatThrownBy(() -> service.place(소매처, "key-4b", 요청()))
                .isInstanceOf(BusinessException.class);

        // 주문서가 FAILED 라 내역에 안 보인다. 그 상태로 서버가 몰래 넣으면 안 된다
        verify(dispatchStore).abandonAll(5001L);
        verify(writer).settle(5001L, 0L, false, List.of(), List.of());
    }

    @Test
    @DisplayName("도매가 이미 받았다고 하면 성공으로 치고 금액은 우리가 센다")
    void 재시도로_이미_접수된_것은_성공이다() {
        // 도매는 409 만 주고 그 주문의 번호·금액은 안 준다
        given(orderClient.place(any())).willReturn(
                new WholesaleOrderReceipt(true, null, null, null, null, "이미 접수된 주문이에요", false));

        PlaceOrderResponse response = service.place(소매처, "key-5", 요청());

        assertThat(response.results()).allMatch(PlaceOrderResponse.Result::isAccepted);
        // 도매가 금액을 안 줬으니 장바구니 값으로 센다
        assertThat(response.totalAmount()).isEqualTo(99500);
        assertThat(response.results().getFirst().orderNumber()).isNull();
    }

    @Test
    @DisplayName("못 파는 옵션이 섞여도 나머지는 접수된다 — 그 도매처만 거절이다")
    void 못파는_옵션은_그_도매처만_거절한다() {
        // 라온 상품이 담아둔 사이 시즌 종료됐다. 무드온까지 죽으면 안 된다
        given(listingClient.findVariants(any())).willReturn(Map.of(
                9001L, 옵션(9001L, 무드온, "무드온", 12500),
                9002L, 못파는옵션(9002L, 라온, "라온", 31000)));
        given(orderClient.place(any())).willReturn(WholesaleOrderReceipt.accepted(8801L, 1, 37500));

        PlaceOrderResponse response = service.place(소매처, "key-6", 요청());

        assertThat(response.results()).hasSize(2);
        assertThat(response.results()).filteredOn(PlaceOrderResponse.Result::isAccepted)
                .extracting(PlaceOrderResponse.Result::wholesalerName).containsExactly("무드온");
        assertThat(response.results()).filteredOn(r -> !r.isAccepted())
                .extracting(PlaceOrderResponse.Result::reason).containsExactly("LISTING_NOT_ON_SALE");

        // 못 파는 도매처는 부를 필요가 없다. 도매가 어차피 같은 이유로 거절한다
        verify(orderClient, org.mockito.Mockito.times(1)).place(any());
        assertThat(response.totalAmount()).isEqualTo(37500);
    }

    @Test
    @DisplayName("도매가 아예 모르는 옵션이면 막는다 — 도매처를 몰라 결과 줄을 못 만든다")
    void 모르는_옵션은_막는다() {
        given(listingClient.findVariants(any())).willReturn(Map.of(
                9001L, 옵션(9001L, 무드온, "무드온", 12500)));

        assertThatThrownBy(() -> service.place(소매처, "key-6b", 요청()))
                .isInstanceOf(BusinessException.class);

        verify(orderClient, never()).place(any());
        verify(writer, never()).open(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("도매처 결제·수령 방법이 빠지면 막는다")
    void 도매처_옵션이_빠지면_막는다() {
        PlaceOrderRequest 라온이_빠진_요청 = new PlaceOrderRequest(
                List.of(1L, 2L), "박삼촌", "01033330001",
                List.of(new PlaceOrderRequest.WholesalerOption(무드온, PaymentTerm.CASH, ReceiveMethod.AGENT)));

        assertThatThrownBy(() -> service.place(소매처, "key-7", 라온이_빠진_요청))
                .isInstanceOf(BusinessException.class);

        verify(orderClient, never()).place(any());
    }

    @Test
    @DisplayName("남의 장바구니 줄이 섞이면 막는다")
    void 남의_줄은_막는다() {
        // 소유자 검사에서 걸러져 개수가 안 맞는다
        given(cartItemRepository.findAllById(any())).willReturn(List.of(무드온줄));

        assertThatThrownBy(() -> service.place(소매처, "key-8", 요청()))
                .isInstanceOf(BusinessException.class);

        verify(orderClient, never()).place(any());
    }

    @Test
    @DisplayName("도매에 소매 상호를 실어 보낸다 — 없으면 도매가 거래처를 못 만든다")
    void 소매_상호를_실어_보낸다() {
        List<WholesaleOrderCommand> 보낸것 = new ArrayList<>();
        given(orderClient.place(any())).willAnswer(inv -> {
            보낸것.add(inv.getArgument(0));
            return WholesaleOrderReceipt.accepted(8801L, 1, 37500);
        });

        service.place(소매처, "key-9", 요청());

        assertThat(보낸것).allSatisfy(c -> {
            assertThat(c.retailerName()).isEqualTo("봄봄상회");
            assertThat(c.retailOrderId()).isEqualTo(5001L);
            assertThat(c.agentName()).isEqualTo("박삼촌");
        });
        // 도매처별로 자기 줄만 간다
        assertThat(보낸것).extracting(c -> c.items().size()).containsExactly(1, 1);
    }

    @Test
    @DisplayName("이미 접수된 열쇠로 다시 오면 409 다 — 400 이 아니다")
    void 연타는_409로_끊는다() {
        // 첫 요청이 끝나면 접수된 줄이 장바구니에서 빠진다. 그냥 두면 두 번째 요청이
        // "없는 줄" 로 걸려 400 이 나가는데, 사용자는 잘못한 게 없고 주문은 이미 됐다
        given(writer.findAccepted("key-done"))
                .willReturn(java.util.Optional.of(주문서(5001L, "20260906-1420-0001")));

        assertThatThrownBy(() -> service.place(소매처, "key-done", 요청()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("20260906-1420-0001");

        verify(orderClient, never()).place(any());
    }

    // ── 가짜 ────────────────────────────────────────────────────

    private static PlaceOrderRequest 요청() {
        return new PlaceOrderRequest(
                List.of(1L, 2L), "박삼촌", "01033330001",
                List.of(new PlaceOrderRequest.WholesalerOption(무드온, PaymentTerm.CASH, ReceiveMethod.AGENT),
                        new PlaceOrderRequest.WholesalerOption(라온, PaymentTerm.BANK_TRANSFER, ReceiveMethod.RETAILER)));
    }

    private static CartItem 장바구니줄(long id, long variantId, int qty) {
        CartItem item = CartItem.of(소매처, variantId, qty);
        org.springframework.test.util.ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    private static VariantInfo 옵션(long variantId, long wholesalerId, String name, int price) {
        return new VariantInfo(variantId, 1000L, "상품", null, "블랙", "M", price, 0,
                wholesalerId, name, true);
    }

    private static VariantInfo 못파는옵션(long variantId, long wholesalerId, String name, int price) {
        return new VariantInfo(variantId, 1000L, "상품", null, "블랙", "M", price, 0,
                wholesalerId, name, false);
    }

    private static Retailer 소매처_객체() {
        Retailer retailer = Retailer.signUp("bombom@ondo.test", "x", "봄봄상회");
        org.springframework.test.util.ReflectionTestUtils.setField(retailer, "id", 소매처);
        return retailer;
    }

    private static OrderGroup 주문서(long id, String orderNo) {
        OrderGroup group = OrderGroup.builder()
                .retailerId(소매처).requestId("key").orderNo(orderNo)
                .agentName("박삼촌").agentPhone("01033330001")
                .orderedAt(OffsetDateTime.now())
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(group, "id", id);
        return group;
    }
}
