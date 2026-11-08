package com.ondo.wholesale.retailgateway;

import com.ondo.wholesale.common.error.ApiException;
import com.ondo.wholesale.common.error.ErrorCode;
import com.ondo.wholesale.notification.OrderNotifier;
import com.ondo.wholesale.order.OrderStatusKey;
import com.ondo.wholesale.order.domain.Order;
import com.ondo.wholesale.order.domain.OrderItem;
import com.ondo.wholesale.order.repository.OrderRepository;
import com.ondo.wholesale.retailgateway.dto.RetailOrderCreateRequest;
import com.ondo.wholesale.retailgateway.dto.RetailOrderCreatedResponse;
import com.ondo.wholesale.retailgateway.dto.RetailOrderItemRequest;
import com.ondo.wholesale.retailgateway.dto.RetailOrderItemResponse;
import com.ondo.wholesale.retailgateway.dto.RetailWholesalerResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 소매 주문 접수 (MUL-98) — 소매 백엔드가 부른다.
 *
 * <p><b>재고를 안 건드린다.</b> 모자라도 그냥 받는다 (D-062). 재고 반영과 미송 생성은
 * 확정 시점의 수동 배분이 하고, 그건 채빈의 {@code AllocationWriter} 몫이다.
 * 여기가 하는 일은 <b>소매가 보낸 주문서를 도매 장부에 받아 적는 것</b>뿐이다.
 * 같은 트랜잭션에서 그 도매처에 새 주문 알림 1건을 남긴다 (MUL-154).
 *
 * <p>도매처 하나당 주문 하나다. 소매의 통합 주문서는 도매처별로 잘려서 이 API 를 N 번
 * 부른다 — 원자성 단위가 도매처별 주문이라 한 도매처가 실패해도 나머지는 접수된다.
 *
 * <p>대조를 먼저 다 하고 그다음에 쓴다. 섞으면 뒤쪽 라인에서 걸렸을 때 앞쪽 라인이
 * 이미 들어간 상태가 되고, 트랜잭션이 되돌려도 채번은 이미 올라가 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RetailGatewayOrderService {

    private final RetailGatewayOrderQuery query;
    private final OrderRepository orderRepository;
    private final OrderNotifier orderNotifier;

    public RetailOrderCreatedResponse create(RetailOrderCreateRequest request) {
        validateShape(request);

        // 이미 받은 주문이면 채번을 올리기 전에 끊는다 (D-052)
        query.findExistingOrderId(request.retailOrderId(), request.wholesalerId())
                .ifPresent(id -> {
                    throw new ApiException(ErrorCode.ORDER_ALREADY_CREATED,
                            "이미 접수된 주문입니다. orderId=" + id);
                });

        List<Long> variantIds = request.items().stream().map(RetailOrderItemRequest::variantId).toList();
        Map<Long, RetailGatewayOrderQuery.VariantSnapshot> snapshots = query.snapshots(variantIds);
        request.items().forEach(item -> validateLine(item, snapshots.get(item.variantId()), request.wholesalerId()));

        long partnerId = query.resolvePartnerId(
                request.wholesalerId(), request.retailerId(),
                request.retailerName(), request.retailerPhone());

        Order order = Order.builder()
                .orderNumber(query.nextOrderNumber(request.wholesalerId()))
                .partnerId(partnerId)
                .wholesalerId(request.wholesalerId())
                .retailOrderId(request.retailOrderId())
                .paymentTerm(request.expectedPaymentMethod())
                .receiveMethod(request.receiveBy())
                .agentName(request.agentName())
                .agentPhone(request.agentPhone())
                .orderedAt(OffsetDateTime.now())
                .build();

        // 가격은 요청값이 아니라 방금 읽은 판매가를 쓴다. 위에서 같은지 대조했으므로
        // 값은 같지만, 출처를 도매로 두어야 소매가 보낸 값이 장부에 직접 들어가지 않는다
        request.items().forEach(item ->
                order.addItem(item.variantId(), item.qty(), snapshots.get(item.variantId()).salePrice()));

        orderRepository.save(order);
        // 같은 트랜잭션이다 — 주문이 롤백되면 알림도 없다 (MUL-154)
        orderNotifier.newOrder(order);
        return toResponse(order);
    }

    // ── 대조 ────────────────────────────────────────────────────

    /** 요청 자체가 성립하는지. 도매 상태를 안 봐도 알 수 있는 것들이다. */
    private static void validateShape(RetailOrderCreateRequest request) {
        if (request.retailOrderId() == null || request.retailerId() == null
                || request.wholesalerId() == null || request.expectedPaymentMethod() == null
                || request.receiveBy() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "retailOrderId · retailerId · wholesalerId · expectedPaymentMethod · receiveBy 는 필수입니다.");
        }
        // partner.retailer_name 이 NOT NULL 이다. 첫 거래면 이게 없으면 거래처를 못 만든다
        if (request.retailerName() == null || request.retailerName().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "retailerName 은 필수입니다.");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "주문 라인이 비었습니다.");
        }

        Set<Long> seen = new HashSet<>();
        for (RetailOrderItemRequest item : request.items()) {
            if (item.variantId() == null || item.qty() == null || item.expectedUnitPrice() == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "주문 라인에는 variantId · qty · expectedUnitPrice 가 모두 필요합니다.");
            }
            if (item.qty() < 1) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "수량은 1 이상이어야 합니다.");
            }
            // 같은 옵션이 두 줄로 오면 합칠지 거절할지 정해야 한다. 거절한다 —
            // 합치면 소매가 보낸 줄 수와 도매 장부의 줄 수가 달라져 대조가 어려워진다
            if (!seen.add(item.variantId())) {
                throw new ApiException(ErrorCode.DUPLICATE_ORDER_ITEM,
                        "같은 옵션이 두 번 들어왔습니다. variantId=" + item.variantId());
            }
        }
    }

    /**
     * 라인 하나가 지금 도매 상태와 맞는지.
     *
     * <p>순서가 의미를 갖는다. 남의 상품인지를 먼저 본다 — 그건 400 이고, 소매가 다시
     * 눌러도 소용없는 버그다. 나머지는 409 로 "장바구니를 새로 고쳐주세요" 가 된다.
     */
    private static void validateLine(RetailOrderItemRequest item,
                                     RetailGatewayOrderQuery.VariantSnapshot snapshot,
                                     Long wholesalerId) {
        if (snapshot == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "존재하지 않는 옵션입니다. variantId=" + item.variantId());
        }
        // 소매가 도매처를 잘못 잘라 보낸 것이다. 조용히 삼키면 남의 주문서에 남는다
        if (!snapshot.wholesalerId().equals(wholesalerId)) {
            throw new ApiException(ErrorCode.VARIANT_WHOLESALER_MISMATCH,
                    "이 도매처의 상품이 아닙니다. variantId=" + item.variantId());
        }
        if (!snapshot.onSale()) {
            throw new ApiException(ErrorCode.LISTING_NOT_ON_SALE,
                    "판매 중인 상품이 아닙니다. variantId=" + item.variantId());
        }
        if (snapshot.salePrice() == null) {
            throw new ApiException(ErrorCode.PRICE_NOT_SET,
                    "판매가가 없습니다. variantId=" + item.variantId());
        }
        // 소매가 화면에 띄운 값과 지금 값이 다르면 되돌린다. 말없이 진행하면
        // 소매처가 본 금액과 청구 금액이 달라진다
        if (!snapshot.salePrice().equals(item.expectedUnitPrice())) {
            throw new ApiException(ErrorCode.PRICE_CHANGED,
                    "판매가가 바뀌었습니다. variantId=%d 화면=%d 현재=%d"
                            .formatted(item.variantId(), item.expectedUnitPrice(), snapshot.salePrice()));
        }
        // 0 은 무제한이다 (D-057)
        if (snapshot.orderLimit() != null && snapshot.orderLimit() > 0 && item.qty() > snapshot.orderLimit()) {
            throw new ApiException(ErrorCode.ORDER_LIMIT_EXCEEDED,
                    "1회 주문 한도를 넘었습니다. variantId=%d 한도=%d 요청=%d"
                            .formatted(item.variantId(), snapshot.orderLimit(), item.qty()));
        }
    }

    // ── 응답 ────────────────────────────────────────────────────

    private static RetailOrderCreatedResponse toResponse(Order order) {
        List<RetailOrderItemResponse> items = order.getItems().stream()
                .map(i -> new RetailOrderItemResponse(i.getId(), i.getVariantId(), i.getQty(), i.getUnitPrice()))
                .toList();
        int orderAmount = order.getItems().stream()
                .mapToInt(i -> i.getQty() * i.getUnitPrice())
                .sum();

        return new RetailOrderCreatedResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getRetailOrderId(),
                OrderStatusKey.valueOf(order.getStatus().name()),
                orderAmount,
                order.getOrderedAt(),
                items);
    }

    // ── 도매처 ──────────────────────────────────────────────────

    /** 주문서에 쓸 도매처 정보. 읽기라 트랜잭션이 따로 필요 없다. */
    @Transactional(readOnly = true)
    public List<RetailWholesalerResponse> wholesalers(List<Long> ids) {
        return query.wholesalers(ids);
    }
}
