package com.ondo.wholesale.inventory.service;

import com.ondo.wholesale.common.error.ResourceNotFoundException;
import com.ondo.wholesale.common.response.ApiResponse;
import com.ondo.wholesale.common.time.KstDays;
import com.ondo.wholesale.inventory.domain.StockMovement;
import com.ondo.wholesale.inventory.dto.StockMovementResponse;
import com.ondo.wholesale.inventory.repository.StockMovementRepository;
import com.ondo.wholesale.product.domain.Variant;
import com.ondo.wholesale.product.repository.VariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 재고 변동 이력 조회 (MUL-72) — SKU 하나의 원장을 시간 역순으로 내린다.
 *
 * <p>원장은 append-only 라 수정·삭제 경로가 없다 — 정정은 반대부호 조정이 한 줄 더
 * 쌓이는 것으로 이력에 그대로 남는다. {@code qtyBefore}는 저장하지 않고
 * {@code qtyAfter − qtyChange}로 파생한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockMovementQueryService {

    private final StockMovementRepository stockMovementRepository;
    private final VariantRepository variantRepository;

    public ApiResponse<List<StockMovementResponse>> list(Long wholesalerId, Long variantId,
                                                         StockMovementListQuery query) {
        ensureOwned(wholesalerId, variantId);

        // Specification.allOf 는 null 요소를 거부한다 — 조건이 있을 때만 담는다
        List<Specification<StockMovement>> conditions = new ArrayList<>(List.of(ofVariant(variantId)));
        if (!query.types().isEmpty()) {
            conditions.add(typeIn(query));
        }
        if (query.from() != null || query.to() != null) {
            conditions.add(createdBetween(query.from(), query.to()));
        }

        Page<StockMovement> movements = stockMovementRepository.findAll(Specification.allOf(conditions),
                PageRequest.of(query.page(), query.size(), query.sort()));

        List<StockMovementResponse> rows = movements.getContent().stream()
                .map(m -> new StockMovementResponse(
                        m.getId(), m.getVariantId(), m.getType(),
                        m.getQtyAfter() - m.getQtyChange(), m.getQtyChange(), m.getQtyAfter(),
                        m.getRefType(), m.getRefId(), m.getCreatedAt()))
                .toList();

        return ApiResponse.paged(rows, new ApiResponse.PageMeta(
                query.page(), query.size(), movements.getTotalElements(), movements.getTotalPages()));
    }

    /** 본인 소유가 아니거나 삭제된 variant 는 404 — 원장 존재 여부를 밖에 흘리지 않는다. */
    private void ensureOwned(Long wholesalerId, Long variantId) {
        variantRepository.findById(variantId)
                .filter(v -> v.getProduct().getWholesalerId().equals(wholesalerId))
                .filter(Variant::isAlive)
                .orElseThrow(() -> new ResourceNotFoundException("옵션이 없거나 접근할 수 없습니다."));
    }

    private static Specification<StockMovement> ofVariant(Long variantId) {
        return (root, q, cb) -> cb.equal(root.get("variantId"), variantId);
    }

    private static Specification<StockMovement> typeIn(StockMovementListQuery query) {
        return (root, q, cb) -> root.get("type").in(query.types());
    }

    /** 기록 시각 기간 — KST 로 [from 00:00, to 다음날 00:00) 반개구간. */
    private static Specification<StockMovement> createdBetween(LocalDate from, LocalDate to) {
        return (root, q, cb) -> {
            var path = root.<OffsetDateTime>get("createdAt");
            if (from != null && to != null) {
                return cb.and(
                        cb.greaterThanOrEqualTo(path, KstDays.start(from)),
                        cb.lessThan(path, KstDays.startOfNext(to)));
            }
            if (from != null) {
                return cb.greaterThanOrEqualTo(path, KstDays.start(from));
            }
            return cb.lessThan(path, KstDays.startOfNext(to));
        };
    }
}
