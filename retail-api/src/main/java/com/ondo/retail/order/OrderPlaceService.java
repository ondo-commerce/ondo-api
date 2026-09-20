package com.ondo.retail.order;

import com.ondo.retail.cart.CartItemRepository;
import com.ondo.retail.cart.domain.CartItem;
import com.ondo.retail.common.error.BusinessException;
import com.ondo.retail.common.error.ErrorCode;
import com.ondo.retail.listing.ListingClient;
import com.ondo.retail.listing.dto.VariantInfo;
import com.ondo.retail.order.domain.OrderGroup;
import com.ondo.retail.order.dto.PlaceOrderRequest;
import com.ondo.retail.order.dto.PlaceOrderResponse;
import com.ondo.retail.order.dto.WholesaleOrderCommand;
import com.ondo.retail.order.dto.WholesaleOrderReceipt;
import com.ondo.retail.retailer.RetailerRepository;
import com.ondo.retail.retailer.domain.Retailer;
import com.ondo.retail.wholesale.WholesaleApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 소매 주문 접수 (MUL-98).
 *
 * <p>도매처가 여럿이어도 사용자는 한 번 누른다. 그걸 도매처별로 잘라 도매에 N 번 넣고,
 * 결과를 한 화면 몫으로 다시 합친다.
 *
 * <p><b>일부만 접수돼도 성공이다.</b> 넷 중 셋만 받아진 것은 에러가 아니라 결과다.
 * 접수된 도매처의 장바구니 줄만 빠지고, 실패한 줄은 그대로 남아 다시 시도할 수 있다.
 * 전부 거절됐을 때만 502 다.
 *
 * <p><b>이 클래스에 {@code @Transactional} 이 없다.</b> 도매 호출을 트랜잭션으로 감싸면
 * 응답을 기다리는 내내 DB 커넥션을 붙들고, 도매처가 셋이면 그 시간이 세 배가 된다.
 * 도매가 느려지면 소매 커넥션 풀이 말라 로그인까지 죽는다 — 미송(MUL-97)과 같은 이유다.
 * DB 쓰기는 {@link OrderGroupWriter} 가 앞뒤로 나눠 맡는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPlaceService {

    private final OrderGroupWriter writer;
    private final CartItemRepository cartItemRepository;
    private final RetailerRepository retailerRepository;
    private final ListingClient listingClient;
    private final OrderClient orderClient;
    private final OrderDispatchStore dispatchStore;

    public PlaceOrderResponse place(Long retailerId, String idempotencyKey, PlaceOrderRequest request) {
        // 연타의 두 번째 요청을 여기서 끊는다. 첫 요청이 이미 끝났으면 접수된 줄이
        // 장바구니에서 빠져 있어서, 그냥 두면 아래 검사가 "없는 줄" 로 보고 400 을 낸다.
        // 사용자는 잘못한 게 없고 주문은 이미 됐는데 "입력을 확인해주세요" 가 뜬다.
        writer.findAccepted(idempotencyKey).ifPresent(placed -> {
            throw new BusinessException(ErrorCode.ORDER_ALREADY_PLACED,
                    "이미 접수된 주문이에요. 주문번호 " + placed.getOrderNo());
        });

        List<CartItem> items = loadCartItems(retailerId, request.cartItemIds());
        Map<Long, VariantInfo> variants = readVariants(items);

        Grouped grouped = groupByWholesaler(items, variants);
        validateOptions(grouped.orderable().keySet(), request.wholesalerOptions());

        Retailer retailer = retailerRepository.findById(retailerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        // 주문서와 접수 대기함을 한 트랜잭션에 쓴다 (MUL-139). 도매를 부르는 도중에
        // 태스크가 내려가도 주문 의사가 남는다
        OrderGroup group = writer.open(retailerId, idempotencyKey, request,
                saved -> grouped.orderable().entrySet().stream()
                        .map(e -> toCommand(saved, retailer, request, e.getKey(), e.getValue(), variants))
                        .toList());

        // ── 트랜잭션 밖. 도매처마다 한 번씩 부른다 ──
        Map<Long, WholesaleOrderReceipt> receipts = new LinkedHashMap<>();
        grouped.orderable().forEach((wholesalerId, lines) -> {
            WholesaleOrderReceipt receipt =
                    orderClient.place(toCommand(group, retailer, request, wholesalerId, lines, variants));
            receipts.put(wholesalerId, receipt);
            writer.recordAttempt(group.getId(), wholesalerId, receipt);
        });

        // 담아둔 사이 못 팔게 된 줄은 도매를 안 부르고 거절로 둔다. 부를 필요가 없다 —
        // 도매가 어차피 LISTING_NOT_ON_SALE 을 준다
        // 대기함에도 안 넣는다 — 다시 보낼 이유가 없으니 처음부터 없는 줄이다
        grouped.unorderable().forEach((wholesalerId, lines) -> receipts.put(wholesalerId,
                WholesaleOrderReceipt.rejected("LISTING_NOT_ON_SALE",
                        "판매가 끝난 상품이 있어요. 장바구니에 그대로 있어요")));

        Map<Long, List<CartItem>> all = new LinkedHashMap<>(grouped.orderable());
        all.putAll(grouped.unorderable());
        return settle(group, receipts, all, variants);
    }

    /** 도매 결과를 합쳐 화면 몫으로 만든다. */
    private PlaceOrderResponse settle(OrderGroup group,
                                      Map<Long, WholesaleOrderReceipt> receipts,
                                      Map<Long, List<CartItem>> byWholesaler,
                                      Map<Long, VariantInfo> variants) {

        List<PlaceOrderResponse.Result> results = new ArrayList<>();
        List<CartItem> acceptedItems = new ArrayList<>();
        // 서버가 맡은 줄. 사장님이 다시 못 누르게 장바구니에서 뺀다 (MUL-141)
        List<CartItem> pendingItems = new ArrayList<>();
        long acceptedAmount = 0;

        for (Map.Entry<Long, WholesaleOrderReceipt> entry : receipts.entrySet()) {
            Long wholesalerId = entry.getKey();
            WholesaleOrderReceipt receipt = entry.getValue();
            List<CartItem> lines = byWholesaler.get(wholesalerId);
            String name = wholesalerName(lines, variants);

            if (!receipt.accepted()) {
                // 도매가 안 떠서 못 넣은 것은 끝난 실패가 아니다. 서버가 다시 보낸다
                if (receipt.retryable()) {
                    pendingItems.addAll(lines);
                }
                results.add(new PlaceOrderResponse.Result(wholesalerId, name, false,
                        null, null, null, receipt.reason(), receipt.message(), receipt.retryable()));
                continue;
            }

            // 도매가 금액을 안 준 경우(재시도라 409 를 받은 경우)는 우리가 센 값을 쓴다
            int amount = receipt.amount() != null ? receipt.amount() : subtotal(lines, variants);
            acceptedAmount += amount;
            acceptedItems.addAll(lines);
            results.add(new PlaceOrderResponse.Result(wholesalerId, name, true,
                    receipt.wholesaleOrderId(), receipt.orderNumber(), amount, null,
                    receipt.message(), false));
        }

        if (acceptedItems.isEmpty()) {
            // 계약대로 "통합 주문을 안 만든 것" 으로 본다. 행은 FAILED 로 남겨
            // 왜 실패했는지 볼 수 있게 하고 멱등키도 살린다.
            //
            // 대기함도 같이 접는다 (MUL-141). 주문서가 FAILED 라 내역에 안 보이는데
            // 서버가 나중에 몰래 넣으면 사용자가 볼 수 없는 주문이 생긴다.
            // 장바구니도 그대로 둔다 — 사용자가 직접 다시 누르는 게 지금 계약이다
            dispatchStore.abandonAll(group.getId());
            OrderGroup failed = writer.settle(group.getId(), 0, false, List.of(), List.of());
            log.warn("도매처 전부가 거절했다. orderId={} 도매처={}", failed.getId(), receipts.keySet());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
        }

        OrderGroup settled = writer.settle(group.getId(), acceptedAmount, true,
                acceptedItems, pendingItems);

        return new PlaceOrderResponse(settled.getId(), settled.getOrderNo(), settled.getOrderedAt(),
                (int) acceptedAmount, results);
    }

    // ── 거들기 ─────────────────────────────────────────────────

    /**
     * 주문에 필요한 옵션 정보를 도매에서 읽는다.
     *
     * <p>도매가 아예 안 뜨면 여기서 먼저 터진다 — 도매처마다 부르기도 전이다.
     * 그대로 두면 500 "잠시 후 다시 시도해주세요" 가 나가는데, 주문 화면에서는
     * <b>장바구니가 그대로라는 말</b>이 훨씬 중요하다. 전부 거절됐을 때와 같은
     * 응답으로 맞춘다 — 사용자 입장에서 결과가 같다.
     */
    private Map<Long, VariantInfo> readVariants(List<CartItem> items) {
        try {
            return listingClient.findVariants(items.stream().map(CartItem::getVariantId).toList());
        } catch (WholesaleApiException e) {
            log.warn("주문 전 옵션 조회에 실패했다. 도매가 안 뜬 것으로 본다", e);
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
        }
    }

    private List<CartItem> loadCartItems(Long retailerId, List<Long> cartItemIds) {
        List<CartItem> items = cartItemRepository.findAllById(cartItemIds).stream()
                .filter(item -> item.isOwnedBy(retailerId))
                .toList();

        // 남의 줄이나 이미 지워진 줄이 섞이면 주문 금액이 사용자가 본 것과 달라진다
        if (items.size() != cartItemIds.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return items;
    }

    /**
     * 도매처별로 자른다. 도매의 원자성 단위가 도매처별 주문이라 여기서 갈라야 한다.
     *
     * <p>담아둔 사이 못 팔게 된 줄은 <b>주문 전체를 막지 않는다.</b> 그 도매처만 거절로
     * 두고 나머지는 그대로 넣는다 — 라온 상품 하나가 시즌 종료됐다고 무드온 주문까지
     * 죽으면, "부분 성공은 정상" 이라는 계약과 어긋난다.
     */
    private static Grouped groupByWholesaler(List<CartItem> items, Map<Long, VariantInfo> variants) {
        Map<Long, List<CartItem>> orderable = new LinkedHashMap<>();
        Map<Long, List<CartItem>> unorderable = new LinkedHashMap<>();

        for (CartItem item : items) {
            VariantInfo v = variants.get(item.getVariantId());
            // 도매가 아예 모르는 옵션이다. 도매처를 모르니 결과 줄조차 못 만든다
            if (v == null) {
                throw new BusinessException(ErrorCode.UNORDERABLE_ITEM_INCLUDED);
            }
            Map<Long, List<CartItem>> target = v.orderable() ? orderable : unorderable;
            target.computeIfAbsent(v.wholesalerId(), k -> new ArrayList<>()).add(item);
        }
        return new Grouped(orderable, unorderable);
    }

    /** 도매를 부를 것과 부르지 않고 거절할 것. */
    private record Grouped(Map<Long, List<CartItem>> orderable,
                           Map<Long, List<CartItem>> unorderable) {}

    /** 도매처마다 결제·수령 방법이 와야 한다. 하나라도 빠지면 그 도매처 주문을 못 만든다. */
    private static void validateOptions(Set<Long> wholesalerIds,
                                        List<PlaceOrderRequest.WholesalerOption> options) {
        Set<Long> given = options.stream()
                .map(PlaceOrderRequest.WholesalerOption::wholesalerId)
                .collect(Collectors.toSet());
        if (!given.containsAll(wholesalerIds)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private static WholesaleOrderCommand toCommand(OrderGroup group, Retailer retailer,
                                                   PlaceOrderRequest request, Long wholesalerId,
                                                   List<CartItem> lines, Map<Long, VariantInfo> variants) {
        PlaceOrderRequest.WholesalerOption option = request.wholesalerOptions().stream()
                .filter(o -> o.wholesalerId().equals(wholesalerId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));

        return new WholesaleOrderCommand(
                group.getId(),
                group.getRetailerId(),
                wholesalerId,
                retailer.getShopName(),
                // 소매 연락처는 retailer_private 에 있다. 지금 평문이라 도매로 보내면
                // 평문 개인정보가 하나 더 늘어난다 — 암호화(MUL-101)와 같이 붙인다
                null,
                option.paymentTerm().name(),
                option.receiveMethod().name(),
                request.agentName(),
                request.agentPhone(),
                lines.stream()
                        .map(item -> new WholesaleOrderCommand.Line(
                                item.getVariantId(), item.getQty(),
                                variants.get(item.getVariantId()).salePrice()))
                        .toList());
    }

    private static String wholesalerName(List<CartItem> lines, Map<Long, VariantInfo> variants) {
        return variants.get(lines.getFirst().getVariantId()).wholesalerName();
    }

    private static int subtotal(List<CartItem> lines, Map<Long, VariantInfo> variants) {
        return lines.stream()
                .mapToInt(item -> item.getQty() * variants.get(item.getVariantId()).salePrice())
                .sum();
    }
}
