package com.ondo.wholesale.inventory.domain;

import com.ondo.wholesale.inventory.repository.InboundItemRepository;
import com.ondo.wholesale.inventory.repository.InboundRepository;
import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.OrderFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 입고 헤더·로트 엔티티가 V1 스키마와 맞물려 저장·재조회되는지 확인한다 (MUL-72).
 * 로트 잔량(remaining_qty)은 출고·음수 조정의 FIFO 소진이 깎는 값이라 입고량과 따로 든다.
 */
@SpringBootTest
@Transactional
class InboundMappingTest extends PostgresTestSupport {

    @Autowired InboundRepository inboundRepository;
    @Autowired InboundItemRepository inboundItemRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    @Test
    void 입고_헤더와_로트를_저장하고_다시_읽는다() {
        long wholesalerId = MasterDataFixture.도매처를_넣는다(jdbc, "inbound-mapping@ondo.test", "9500000017");
        long leafId = MasterDataFixture.카테고리_리프를_넣는다(jdbc, 9140);
        long colorId = MasterDataFixture.색상을_넣는다(jdbc, 9240, 9241);
        long variantId = OrderFixture.상품_변형을_넣는다(jdbc, wholesalerId, leafId, colorId, "맨투맨", 1);
        OffsetDateTime receivedAt = OffsetDateTime.parse("2026-08-19T14:30:00+09:00");

        Inbound inbound = inboundRepository.save(Inbound.builder()
                .wholesalerId(wholesalerId).receivedAt(receivedAt).build());
        InboundItem lot = inboundItemRepository.save(
                inbound.addLot(variantId, 50, new BigDecimal("8500.00")));
        em.flush();
        em.clear();

        Inbound foundInbound = inboundRepository.findById(inbound.getId()).orElseThrow();
        InboundItem foundLot = inboundItemRepository.findById(lot.getId()).orElseThrow();

        assertThat(foundInbound.getWholesalerId()).isEqualTo(wholesalerId);
        assertThat(foundInbound.getReceivedAt()).isEqualTo(receivedAt);
        assertThat(foundInbound.getCreatedAt()).isNotNull();
        assertThat(foundLot.getInbound().getId()).isEqualTo(inbound.getId());
        assertThat(foundLot.getVariantId()).isEqualTo(variantId);
        assertThat(foundLot.getQty()).isEqualTo(50);
        assertThat(foundLot.getRemainingQty()).isEqualTo(50);  // 생성 직후 잔량 = 입고량
        assertThat(foundLot.getUnitCost()).isEqualByComparingTo("8500.00");
        assertThat(foundLot.getCreatedAt()).isNotNull();
    }
}
