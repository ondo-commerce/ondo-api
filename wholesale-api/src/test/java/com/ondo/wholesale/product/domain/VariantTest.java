package com.ondo.wholesale.product.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * variant 의 수량 도메인 메서드를 못박는다. 가용재고 검증은 호출부가 락 아래서
 * 끝내는 설계라(자바독 참조), 여기서는 델타 반영만 본다 — 새 variant 는 0에서 시작한다.
 */
class VariantTest {

    @Test
    void ship은_실재고와_예약을_함께_줄인다() {
        Variant variant = new Variant(null, null, null, 1);
        variant.reserve(5);

        variant.ship(3);

        assertThat(variant.getReservedQty()).isEqualTo(2);
        assertThat(variant.getStockQty()).isEqualTo(-3);
    }

    @Test
    void receive는_실재고만_올린다() {
        Variant variant = new Variant(null, null, null, 1);
        variant.reserve(2);

        variant.receive(50);

        assertThat(variant.getStockQty()).isEqualTo(50);
        assertThat(variant.getReservedQty()).isEqualTo(2);
    }

    @Test
    void adjust는_부호대로_실재고를_바꾼다() {
        Variant variant = new Variant(null, null, null, 1);
        variant.receive(10);

        variant.adjust(-4);
        assertThat(variant.getStockQty()).isEqualTo(6);

        variant.adjust(3);
        assertThat(variant.getStockQty()).isEqualTo(9);
    }

    @Test
    void repriceAvgCost는_평균원가_캐시만_바꾼다() {
        Variant variant = new Variant(null, null, null, 1);
        variant.receive(10);

        variant.repriceAvgCost(new java.math.BigDecimal("8412.350000"));

        assertThat(variant.getAvgCost()).isEqualByComparingTo("8412.35");
        assertThat(variant.getStockQty()).isEqualTo(10);
        assertThat(variant.getReservedQty()).isZero();
    }
}
