package com.ondo.wholesale.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 입고 라인 = 로트 하나 (MUL-72). 단가가 다르면 다른 로트다.
 *
 * <p>{@code qty}(입고량)는 불변이고 {@code remainingQty}(잔량)만 출고·음수 조정의
 * FIFO 소진({@link #consume})으로 깎인다. 잔량가중 평균원가의 진실은 이 잔량이다 —
 * variant.avg_cost 는 캐시일 뿐이다.
 *
 * <p>variant 는 상품 애그리거트 밖이라 Long 으로만 든다.
 */
@Entity
@Table(name = "inbound_item", schema = "wholesale")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InboundItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inbound_id", nullable = false, updatable = false)
    private Inbound inbound;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private Long variantId;

    /** 입고량. 불변 — 소진돼도 이 값은 남아 감사 근거가 된다. */
    @Column(nullable = false, updatable = false)
    private int qty;

    /** 로트 잔량. 오래된 로트부터 차감된다 (FIFO). */
    @Column(name = "remaining_qty", nullable = false)
    private int remainingQty;

    /** 로트별 매입단가. */
    @Column(name = "unit_cost", nullable = false, precision = 14, scale = 4, updatable = false)
    private BigDecimal unitCost;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    InboundItem(Inbound inbound, Long variantId, int qty, BigDecimal unitCost) {
        this.inbound = inbound;
        this.variantId = variantId;
        this.qty = qty;
        this.remainingQty = qty;   // 생성 직후 잔량 = 입고량
        this.unitCost = unitCost;
    }

    /** FIFO 소진 — 잔량 한도 검증은 호출부({@code StockLedger})가 끝낸다. */
    public void consume(int qty) {
        this.remainingQty -= qty;
    }
}
