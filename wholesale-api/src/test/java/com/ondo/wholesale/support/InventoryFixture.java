package com.ondo.wholesale.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 재고 계열 통합 테스트가 공유하는 jdbc 픽스처 (MUL-72).
 *
 * <p>마스터(도매처·카테고리·색상)는 {@link MasterDataFixture}, 상품·변형 직조는
 * {@link OrderFixture}를 그대로 쓴다 — 여기는 재고 전용 행(로트·원장·재고값)만 담당한다.
 */
public final class InventoryFixture {

    private InventoryFixture() {
    }

    /** 상품 1 + 색상옵션 1 + FREE 변형 1 — 주문 픽스처와 같은 직조를 재사용한다. */
    public static long 변형을_넣는다(JdbcTemplate jdbc, long wholesalerId, long leafCategoryId,
                               long colorId, String name, int productNumber) {
        return OrderFixture.상품_변형을_넣는다(jdbc, wholesalerId, leafCategoryId, colorId, name, productNumber);
    }

    /** 실재고·예약을 직접 심는다 — API 를 안 거치는 사전 상태 세팅용. */
    public static void 재고를_둔다(JdbcTemplate jdbc, long variantId, int stockQty, int reservedQty) {
        jdbc.update("update wholesale.variant set stock_qty = ?, reserved_qty = ? where id = ?",
                stockQty, reservedQty, variantId);
    }
}
