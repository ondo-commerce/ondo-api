package com.ondo.wholesale.inventory.controller;

import com.ondo.wholesale.inventory.domain.StockMovementType;
import com.ondo.wholesale.inventory.dto.InboundCreatedResponse;
import com.ondo.wholesale.inventory.dto.InboundItemResponse;
import com.ondo.wholesale.inventory.dto.StockMovementResponse;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** 계약 스텁 example — api-lite/03_재고 문서의 Response 예시 그대로. 실구현이 서비스 호출로 교체한다. */
final class InventoryStubExamples {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private InventoryStubExamples() {
    }

    static InboundCreatedResponse createdInbound() {
        return new InboundCreatedResponse(
                4102L,
                OffsetDateTime.of(2026, 8, 19, 14, 30, 0, 0, KST),
                List.of(new InboundItemResponse(
                        77301L, 90231L, 18, 1, 50,
                        new BigDecimal("8500.00"), 50, 1284, new BigDecimal("8412.35"))));
    }

    static StockMovementResponse adjustment() {
        return new StockMovementResponse(
                55021L, 90231L, StockMovementType.ADJUST, 96, -5, 91, null, null,
                OffsetDateTime.of(2026, 8, 19, 14, 30, 0, 0, KST));
    }

    static List<StockMovementResponse> movements() {
        return List.of(new StockMovementResponse(
                55021L, 90231L, StockMovementType.IN, 83, 50, 133, "INBOUND_ITEM", 77301L,
                OffsetDateTime.of(2023, 10, 24, 11, 20, 0, 0, KST)));
    }
}
