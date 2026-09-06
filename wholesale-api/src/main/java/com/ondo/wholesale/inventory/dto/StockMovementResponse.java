package com.ondo.wholesale.inventory.dto;

import com.ondo.wholesale.inventory.domain.StockMovementType;

import java.time.OffsetDateTime;

/**
 * 재고 변동 이력 한 줄. 조정 201 응답(+{@code variantId})과 이력 목록이 같은 필드 집합을 쓴다.
 * {@code refType}/{@code refId}는 출처 로트 — 조정은 항상 {@code null}.
 */
public record StockMovementResponse(
        Long id,
        Long variantId,
        StockMovementType type,
        int qtyBefore,
        int qtyChange,
        int qtyAfter,
        String refType,
        Long refId,
        OffsetDateTime createdAt
) {}
