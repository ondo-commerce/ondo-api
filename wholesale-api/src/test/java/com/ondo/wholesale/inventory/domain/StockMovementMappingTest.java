package com.ondo.wholesale.inventory.domain;

import com.ondo.wholesale.inventory.domain.StockMovementType;
import com.ondo.wholesale.inventory.repository.StockMovementRepository;
import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재고 변동 원장 엔티티가 V1 스키마와 맞물려 저장·재조회되는지 확인한다.
 * 재고(MUL-72)·출고(MUL-49)가 같이 쓰는 부품이라 매핑을 먼저 못박아 둔다.
 */
@SpringBootTest
@Transactional
class StockMovementMappingTest extends PostgresTestSupport {

    @Autowired StockMovementRepository stockMovementRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    @Test
    void 변동_한_줄을_저장하고_다시_읽는다() {
        long wholesalerId = MasterDataFixture.도매처를_넣는다(jdbc, "stock-movement@ondo.test", "9500000016");
        long leafId = MasterDataFixture.카테고리_리프를_넣는다(jdbc, 9130);
        long colorId = MasterDataFixture.색상을_넣는다(jdbc, 9230, 9231);
        long productId = jdbc.queryForObject("""
                insert into wholesale.product (wholesaler_id, product_number, name, category_id)
                values (?, 1, '원장상품', ?) returning id
                """, Long.class, wholesalerId, leafId);
        long colorOptionId = jdbc.queryForObject("""
                insert into wholesale.color_option (product_id, color_id)
                values (?, ?) returning id
                """, Long.class, productId, colorId);
        long variantId = jdbc.queryForObject("""
                insert into wholesale.variant (color_option_id, product_id, size, variant_seq)
                values (?, ?, 'FREE', 1) returning id
                """, Long.class, colorOptionId, productId);

        StockMovement saved = stockMovementRepository.save(StockMovement.builder()
                .variantId(variantId)
                .type(StockMovementType.IN)
                .qtyChange(7)
                .qtyAfter(7)
                .refType("INBOUND_ITEM")
                .refId(42L)
                .build());
        em.flush();
        em.clear();

        StockMovement found = stockMovementRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getVariantId()).isEqualTo(variantId);
        assertThat(found.getType()).isEqualTo(StockMovementType.IN);
        assertThat(found.getQtyChange()).isEqualTo(7);
        assertThat(found.getQtyAfter()).isEqualTo(7);
        assertThat(found.getRefType()).isEqualTo("INBOUND_ITEM");
        assertThat(found.getRefId()).isEqualTo(42L);
        assertThat(found.getCreatedAt()).isNotNull();
    }
}
