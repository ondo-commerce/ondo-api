package com.ondo.wholesale.inventory.repository;

import com.ondo.wholesale.inventory.domain.InboundItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InboundItemRepository extends JpaRepository<InboundItem, Long> {

    /** 잔량이 남은 로트를 오래된 순으로 — FIFO 소진·평균원가 재계산의 재료. */
    List<InboundItem> findByVariantIdAndRemainingQtyGreaterThanOrderByIdAsc(Long variantId, int remainingQty);
}
