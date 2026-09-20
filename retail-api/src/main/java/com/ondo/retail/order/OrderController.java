package com.ondo.retail.order;

import com.ondo.retail.common.error.BusinessException;
import com.ondo.retail.common.error.ErrorCode;
import com.ondo.retail.common.response.ApiResponse;
import com.ondo.retail.common.response.PageResponse;
import com.ondo.retail.order.dto.CancelOrderRequest;
import com.ondo.retail.order.dto.CancelOrderResponse;
import com.ondo.retail.order.dto.CheckoutResponse;
import com.ondo.retail.order.dto.OrderDetailResponse;
import com.ondo.retail.order.dto.OrderSummaryResponse;
import com.ondo.retail.order.dto.PlaceOrderRequest;
import com.ondo.retail.order.dto.PlaceOrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문. <b>지금은 껍데기다 — 응답 모양만 내고 로직이 없다.</b>
 *
 * <p>주문 접수는 멱등키 · 부분 접수 · 금액 집계가 얽혀 있어 목으로 만들면 나중에 통째로
 * 다시 쓴다. 프론트가 화면 구조를 잡을 수 있게 시그니처와 응답 모양만 확정한다. 로직은 W3.
 */
@Tag(name = "주문", description = "주문서 · 접수 · 내역 · 상세 · 취소. 지금은 껍데기다 — 응답 모양만 낸다.")
@RestController
@RequestMapping("/api/retail")
@RequiredArgsConstructor
public class OrderController {

    private static final int MAX_PAGE_SIZE = 100;

    private final MockOrderData mock;
    private final OrderPlaceService placeService;
    private final CheckoutService checkoutService;
    private final OrderQueryService queryService;
    private final OrderDispatchStore dispatchStore;

    /**
     * 주문서. 장바구니에서 고른 것만 넘긴다.
     *
     * <p>단가를 여기서 다시 받는다 — 담아둔 사이에 가격이 올랐으면 반영돼야 한다.
     */
    @Operation(summary = "주문서",
               description = "장바구니에서 고른 것만 넘긴다. 단가를 여기서 다시 받는다.")
    @GetMapping("/checkout")
    public ApiResponse<CheckoutResponse> checkout(@RequestParam List<Long> cartItemIds,
                                                  Authentication authentication) {
        if (cartItemIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return ApiResponse.of(checkoutService.checkout(retailerId(authentication), cartItemIds));
    }

    /**
     * 주문 접수 (MUL-98).
     *
     * <p>일부만 접수돼도 201 이다. 에러가 아니라 결과다. 전부 안 되면 502 고, 그때는
     * 장바구니가 그대로 남는다.
     *
     * <p>{@code Idempotency-Key} 는 브라우저가 화면을 열 때 만들어 보낸다. 같은 키로 다시
     * 오면 주문서를 새로 만들지 않고 <b>도매를 다시 부른다</b> — 이미 받은 곳은 도매가
     * 막고 실패했던 곳만 들어간다. 「처음 결과 그대로」는 아직 아니다(숙제 7번).
     */
    @Operation(summary = "주문 접수",
               description = "일부만 접수돼도 201 이다. 부분 성공은 에러가 아니라 결과다.")
    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PlaceOrderResponse> place(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody @Valid PlaceOrderRequest request,
            Authentication authentication) {
        return ApiResponse.of(placeService.place(retailerId(authentication), idempotencyKey, request));
    }

    /** 주문 내역. 통합 주문서 하나가 한 줄이다. */
    @Operation(summary = "주문 내역",
               description = "통합 주문서 하나가 한 줄이다.")
    @GetMapping("/orders")
    public PageResponse<OrderSummaryResponse> orders(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {

        if (size > MAX_PAGE_SIZE || (from != null && to != null && from.isAfter(to))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        return PageResponse.of(queryService.orders(
                retailerId(authentication), from, to, PageRequest.of(page, size)));
    }

    /** 주문 상세. 도매처별 주문 · 품목 줄 · 출고 기록. */
    @Operation(summary = "주문 상세",
               description = "도매처별 주문 · 품목 줄 · 출고 기록.")
    @GetMapping("/orders/{orderId}")
    public ApiResponse<OrderDetailResponse> order(@PathVariable Long orderId,
                                                  Authentication authentication) {
        return ApiResponse.of(queryService.detail(retailerId(authentication), orderId));
    }

    /**
     * 주문 취소. 도매처별로 취소한다 — 통째 취소는 없다.
     *
     * <p>일부만 취소돼도 200 이다. NEW 인 것만 취소된다.
     */
    @Operation(summary = "주문 취소",
               description = "도매처별로 취소한다. NEW 인 것만 된다.")
    @PostMapping("/orders/{orderId}/cancel")
    public ApiResponse<CancelOrderResponse> cancel(@PathVariable Long orderId,
                                                   @RequestBody(required = false) CancelOrderRequest request) {
        return ApiResponse.of(mock.cancel());
    }

    /**
     * 접수를 기다리는 건을 취소한다 (MUL-141).
     *
     * <p>이미 도매에 들어간 주문을 무르는 {@code /cancel} 과 다르다. 이건 <b>아직 도매에
     * 안 들어간</b> 건이다 — 도매가 안 떠서 서버가 대신 다시 보내는 중인 줄을 접는다.
     *
     * <p>기다리라고 해놓고 빠져나갈 길이 없으면 안 된다. 동대문은 주문 시점이 중요해서
     * 사장님이 다른 도매에서 사기로 할 수 있다. 접으면 물건은 장바구니로 돌아온다.
     *
     * <p>이미 접수됐거나 끝난 건이면 409 다 — 조용히 200 을 주면 취소된 줄 알고 딴 데서
     * 또 산다.
     */
    @Operation(summary = "접수 대기 취소",
               description = "도매가 안 떠서 대기 중인 건을 접는다. 물건은 장바구니로 돌아온다.")
    @PostMapping("/orders/{orderId}/dispatches/{wholesalerId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelDispatch(@PathVariable Long orderId,
                               @PathVariable Long wholesalerId,
                               Authentication authentication) {
        boolean cancelled = dispatchStore.cancel(orderId, wholesalerId, retailerId(authentication));
        if (!cancelled) {
            throw new BusinessException(ErrorCode.DISPATCH_NOT_PENDING);
        }
    }

    private static Long retailerId(Authentication authentication) {
        return Long.valueOf(authentication.getName());
    }
}
