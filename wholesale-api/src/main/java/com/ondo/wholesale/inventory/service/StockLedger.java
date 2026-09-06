package com.ondo.wholesale.inventory.service;

import com.ondo.wholesale.inventory.domain.InboundItem;
import com.ondo.wholesale.inventory.domain.StockMovement;
import com.ondo.wholesale.inventory.domain.StockMovementType;
import com.ondo.wholesale.inventory.repository.InboundItemRepository;
import com.ondo.wholesale.inventory.repository.StockMovementRepository;
import com.ondo.wholesale.product.domain.Variant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 재고 쓰기 부품 (MUL-72) — 로트 FIFO 소진 · 잔량가중 평균원가 재계산 · 원장 append 을
 * 한 몸으로 처리한다. 입고·조정(재고 티켓)과 출고 확정(MUL-49)이 같이 쓰는 유일한
 * 동기화 지점이다.
 *
 * <p>트랜잭션 경계와 variant 락({@code VariantRepository.lockAllByIdIn}, id 오름차순)은
 * 호출하는 서비스가 잡는다. 재고 하한 검증도 호출부가 락 아래서 끝낸다 — 여기는
 * stock_qty 기준으로 이미 허용된 변동을 로트·원장·캐시에 반영만 한다.
 *
 * <p>평균원가는 매번 {@code Σ(잔량×단가)/Σ(잔량)}(scale 6)으로 재계산한다 — 로트가
 * 진실이고 variant.avg_cost 는 캐시다. 증분 이동평균식은 양수 조정(로트 없는 재고)이
 * 끼면 어긋나서 쓰지 않는다.
 */
@Component
@RequiredArgsConstructor
public class StockLedger {

    private static final int AVG_COST_SCALE = 6;

    private final InboundItemRepository inboundItemRepository;
    private final StockMovementRepository stockMovementRepository;

    /** 입고 한 줄 — 저장된 로트를 재고에 반영하고 IN 원장을 남긴다. */
    public StockMovement recordInbound(Variant variant, InboundItem lot) {
        variant.receive(lot.getQty());
        variant.repriceAvgCost(recalculateAvgCost(variant.getId()));
        return append(variant, StockMovementType.IN, lot.getQty(), "INBOUND_ITEM", lot.getId());
    }

    /**
     * 재고 조정 — 음수는 오래된 로트부터 FIFO 소진(로트가 부족해도 잔량까지만 깎고 성공 —
     * 검증은 stock_qty 기준으로 끝났다), 양수는 로트를 만들지 않고 평균원가도 그대로 둔다.
     * 원장 ADJUST 는 출처가 없다(ref null).
     */
    public StockMovement recordAdjustment(Variant variant, int qtyChange) {
        variant.adjust(qtyChange);
        if (qtyChange < 0) {
            consumeLotsFifo(variant.getId(), -qtyChange);
            variant.repriceAvgCost(recalculateAvgCost(variant.getId()));
        }
        return append(variant, StockMovementType.ADJUST, qtyChange, null, null);
    }

    /**
     * 출고 확정(MUL-49)용 — 실재고·예약을 함께 줄이고({@link Variant#ship}) 로트를 FIFO 로
     * 소진한 뒤 OUT 원장을 남긴다. refType/refId 는 출고 문서를 가리킨다 (예: "OUTBOUND").
     */
    public StockMovement recordOutbound(Variant variant, int qty, String refType, Long refId) {
        variant.ship(qty);
        consumeLotsFifo(variant.getId(), qty);
        variant.repriceAvgCost(recalculateAvgCost(variant.getId()));
        return append(variant, StockMovementType.OUT, -qty, refType, refId);
    }

    private void consumeLotsFifo(Long variantId, int qty) {
        int toConsume = qty;
        for (InboundItem lot : remainingLots(variantId)) {
            if (toConsume <= 0) {
                return;
            }
            int take = Math.min(lot.getRemainingQty(), toConsume);
            lot.consume(take);
            toConsume -= take;
        }
    }

    private BigDecimal recalculateAvgCost(Long variantId) {
        List<InboundItem> lots = remainingLots(variantId);
        int totalRemaining = lots.stream().mapToInt(InboundItem::getRemainingQty).sum();
        if (totalRemaining == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal totalValue = lots.stream()
                .map(lot -> lot.getUnitCost().multiply(BigDecimal.valueOf(lot.getRemainingQty())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return totalValue.divide(BigDecimal.valueOf(totalRemaining), AVG_COST_SCALE, RoundingMode.HALF_UP);
    }

    private List<InboundItem> remainingLots(Long variantId) {
        // JPQL 이라 실행 전에 영속성 컨텍스트가 flush 된다 — 방금 소진한 로트도 반영된 결과를 본다
        return inboundItemRepository.findByVariantIdAndRemainingQtyGreaterThanOrderByIdAsc(variantId, 0);
    }

    private StockMovement append(Variant variant, StockMovementType type, int qtyChange,
                                 String refType, Long refId) {
        return stockMovementRepository.save(StockMovement.builder()
                .variantId(variant.getId())
                .type(type)
                .qtyChange(qtyChange)
                .qtyAfter(variant.getStockQty())
                .refType(refType)
                .refId(refId)
                .build());
    }
}
