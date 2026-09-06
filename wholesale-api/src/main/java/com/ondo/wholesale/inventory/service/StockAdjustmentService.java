package com.ondo.wholesale.inventory.service;

import com.ondo.wholesale.common.error.ApiException;
import com.ondo.wholesale.common.error.ErrorCode;
import com.ondo.wholesale.common.error.ErrorResponse;
import com.ondo.wholesale.common.error.ResourceNotFoundException;
import com.ondo.wholesale.inventory.domain.StockMovement;
import com.ondo.wholesale.inventory.dto.StockAdjustmentRequest;
import com.ondo.wholesale.inventory.dto.StockBelowAllocatedData;
import com.ondo.wholesale.inventory.dto.StockMovementResponse;
import com.ondo.wholesale.product.domain.Variant;
import com.ondo.wholesale.product.repository.VariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 재고 조정 (MUL-72). 한 트랜잭션 = adjust 한 번.
 *
 * <p>부호 포함 증감만 받는다 — 절대값은 화면과 서버 사이에 낀 다른 트랜잭션의 변동을
 * 덮어쓴다. 하한은 락 아래서 두 겹으로 본다: 0 미만(409 STOCK_BELOW_ZERO)이 먼저,
 * 그다음 예약 미만(409 STOCK_BELOW_ALLOCATED — 취소 후보 포장 목록을 data 로 싣는다).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class StockAdjustmentService {

    private final VariantRepository variantRepository;
    private final StockLedger stockLedger;
    private final BlockingPackingReader blockingPackingReader;

    public StockMovementResponse adjust(Long wholesalerId, Long variantId, StockAdjustmentRequest request) {
        Integer qtyChange = (request == null) ? null : request.qtyChange();
        if (qtyChange == null || qtyChange == 0) {
            throw new ApiException(ErrorCode.INVARIANT_VIOLATED, "qtyChange 는 0 이 아닌 정수여야 합니다.");
        }
        Variant variant = lockOwnedVariant(wholesalerId, variantId);

        int qtyBefore = variant.getStockQty();
        int newStock = qtyBefore + qtyChange;
        if (newStock < 0) {
            throw new ApiException(ErrorCode.STOCK_BELOW_ZERO);
        }
        if (newStock < variant.getReservedQty()) {
            throw stockBelowAllocated(variant, qtyChange, newStock);
        }

        StockMovement movement = stockLedger.recordAdjustment(variant, qtyChange);
        return new StockMovementResponse(
                movement.getId(), variantId, movement.getType(), qtyBefore,
                movement.getQtyChange(), movement.getQtyAfter(), null, null, movement.getCreatedAt());
    }

    /** 소유·삭제 검증(404) 후 id 오름차순 비관 락 — 배분·출고와 같은 락 순서라 교착이 없다. */
    private Variant lockOwnedVariant(Long wholesalerId, Long variantId) {
        Variant variant = variantRepository.findById(variantId)
                .filter(v -> v.getProduct().getWholesalerId().equals(wholesalerId))
                .filter(Variant::isAlive)
                .orElseThrow(() -> new ResourceNotFoundException("옵션이 없거나 접근할 수 없습니다."));
        variantRepository.lockAllByIdIn(List.of(variant.getId()));
        return variant;
    }

    private ApiException stockBelowAllocated(Variant variant, int qtyChange, int newStock) {
        int requiredCancelQty = variant.getReservedQty() - newStock;
        StockBelowAllocatedData data = new StockBelowAllocatedData(
                variant.getStockQty(), variant.getReservedQty(), qtyChange, requiredCancelQty,
                blockingPackingReader.read(variant.getId()));
        return new ApiException(ErrorCode.STOCK_BELOW_ALLOCATED,
                ErrorCode.STOCK_BELOW_ALLOCATED.defaultMessage(),
                List.of(new ErrorResponse.FieldError("qtyChange",
                        "포장대기 " + requiredCancelQty + "개를 먼저 취소해 주세요.", data)));
    }
}
