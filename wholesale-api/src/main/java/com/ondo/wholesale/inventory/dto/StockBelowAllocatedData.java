package com.ondo.wholesale.inventory.dto;

import java.util.List;

/**
 * 409 {@code STOCK_BELOW_ALLOCATED}의 {@code errors[].data} — 화면이 "포장대기 N개를
 * 먼저 취소해 주세요" 안내와 취소 후보 목록을 그리는 재료.
 *
 * <p>{@code blockingPackings}는 취소 후보 전량이다 — {@code requiredCancelQty}(최소 취소
 * 수량)만큼만 걸러 주지 않는다. 어느 소매처 포장을 뺄지는 사용자가 고른다.
 */
public record StockBelowAllocatedData(
        int stockQty,
        int allocatedQty,
        int requestedQtyChange,
        int requiredCancelQty,
        List<BlockingPacking> blockingPackings
) {

    /**
     * 취소 후보 포장 한 건. {@code isCancellable}은 READY(아직 매장)만 참 —
     * PACKED 는 먼저 포장 해제(출고 {@code outboundId})를 거쳐야 한다.
     */
    public record BlockingPacking(
            Long packingId,
            Long packingItemId,
            int orderNumber,
            int qty,
            String status,
            Long outboundId,
            boolean isCancellable
    ) {}
}
