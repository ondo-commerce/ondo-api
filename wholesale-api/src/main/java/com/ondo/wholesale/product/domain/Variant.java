package com.ondo.wholesale.product.domain;

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
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * variant = 색상 × 사이즈 한 칸 (SKU). soft delete 로만 지운다 —
 * 부분 유니크(variant_size_uk)가 살아있는 행에서만 걸려 사이즈 재추가를 허용한다 (D-053).
 *
 * <p>product 참조는 스키마의 중복 보유(D-055)를 그대로 매핑한 것으로 불변이다.
 * stock_qty·avg_cost 는 재고 티켓(MUL-72)의 도메인 메서드로만 바뀐다. reserved_qty 는
 * 주문의 배분·배분취소(MUL-47)가 {@link #reserve}/{@link #release}로만 바꾼다.
 * 출고 확정(MUL-49)은 {@link #ship}으로만 둘을 함께 줄인다.
 */
@Entity
@Table(name = "variant", schema = "wholesale")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Variant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "color_option_id", nullable = false, updatable = false)
    private ColorOption colorOption;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    /** DB 에는 '2XL' 라벨로 저장된다 — {@link SizeConverter} 가 autoApply 로 변환. */
    @Column(nullable = false, length = 10)
    private Size size;

    /** 상품 내 연번. 영구 결번 (variant_seq_uk). */
    @Column(name = "variant_seq", nullable = false, updatable = false)
    private int variantSeq;

    @Column(name = "stock_qty", nullable = false)
    private int stockQty;

    @Column(name = "reserved_qty", nullable = false)
    private int reservedQty;

    /** FIFO 잔량 실원가 캐시. lot 이 진실이고 이 값은 재고 티켓이 관리한다. */
    @Column(name = "avg_cost", nullable = false, precision = 16, scale = 6)
    private BigDecimal avgCost;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    Variant(ColorOption colorOption, Product product, Size size, int variantSeq) {
        this.colorOption = colorOption;
        this.product = product;
        this.size = size;
        this.variantSeq = variantSeq;
        this.avgCost = BigDecimal.ZERO;
    }

    /** 배분이 잡은 예약 — 가용재고(stock − reserved) 검증은 호출부(AllocationWriter)가 락 아래서 끝낸다. */
    public void reserve(int qty) {
        this.reservedQty += qty;
    }

    /** 배분취소가 예약을 되돌린다 — 가용재고가 돌아온다. */
    public void release(int qty) {
        this.reservedQty -= qty;
    }

    /** 출고 확정이 실물을 내보낸다 — 실재고와 예약이 함께 줄어든다. 검증은 호출부가 락 아래서 끝낸다. */
    public void ship(int qty) {
        this.stockQty -= qty;
        this.reservedQty -= qty;
    }

    /** 입고(MUL-72)가 실물을 들인다 — 실재고만 오른다. 평균원가는 {@link #repriceAvgCost}가 따로 맞춘다. */
    public void receive(int qty) {
        this.stockQty += qty;
    }

    /** 재고 조정(MUL-72) — 부호 포함 증감. 0 미만·예약 미만 검증은 호출부가 락 아래서 끝낸다. */
    public void adjust(int qtyChange) {
        this.stockQty += qtyChange;
    }

    /** 평균원가 캐시 갱신 — 진실은 로트 잔량이고, 재계산은 StockLedger 가 한다 (MUL-72). */
    public void repriceAvgCost(BigDecimal avgCost) {
        this.avgCost = avgCost;
    }

    public void softDelete() {
        this.deletedAt = OffsetDateTime.now();
    }

    public boolean isAlive() {
        return deletedAt == null;
    }
}
