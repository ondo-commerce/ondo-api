package com.ondo.wholesale.inventory.service;

import com.ondo.wholesale.inventory.domain.Inbound;
import com.ondo.wholesale.inventory.domain.InboundItem;
import com.ondo.wholesale.inventory.domain.StockMovement;
import com.ondo.wholesale.inventory.repository.InboundItemRepository;
import com.ondo.wholesale.inventory.repository.InboundRepository;
import com.ondo.wholesale.product.domain.Variant;
import com.ondo.wholesale.product.repository.VariantRepository;
import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.OrderFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재고 쓰기 부품 검증 (MUL-72) — 로트 FIFO 소진·잔량가중 평균원가 재계산·원장 append.
 *
 * <p>입고·조정(재고 티켓)과 출고 확정(MUL-49)이 같이 쓰는 유일한 동기화 지점이다.
 * variant 락은 호출부(서비스) 몫이라 여기서는 계산 규칙만 본다.
 */
@SpringBootTest
@Transactional
class StockLedgerTest extends PostgresTestSupport {

    @Autowired StockLedger stockLedger;
    @Autowired InboundRepository inboundRepository;
    @Autowired InboundItemRepository inboundItemRepository;
    @Autowired VariantRepository variantRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    private long wholesalerId;
    private long variantId;
    private Inbound inbound;

    @BeforeEach
    void 재료를_심는다() {
        wholesalerId = MasterDataFixture.도매처를_넣는다(jdbc, "ledger@ondo.test", "9500000018");
        long leafId = MasterDataFixture.카테고리_리프를_넣는다(jdbc, 9143);
        long colorId = MasterDataFixture.색상을_넣는다(jdbc, 9242, 9243);
        variantId = OrderFixture.상품_변형을_넣는다(jdbc, wholesalerId, leafId, colorId, "가디건", 1);
        inbound = inboundRepository.save(Inbound.builder()
                .wholesalerId(wholesalerId).receivedAt(OffsetDateTime.now()).build());
    }

    @Test
    void 입고는_로트를_만들고_잔량가중_평균원가를_갱신한다() {
        Variant variant = variantRepository.findById(variantId).orElseThrow();

        입고를_기록한다(variant, 50, "8500.00");
        입고를_기록한다(variant, 30, "7800.00");

        // (50×8500 + 30×7800) / 80 = 8237.5
        assertThat(variant.getStockQty()).isEqualTo(80);
        assertThat(variant.getAvgCost()).isEqualByComparingTo("8237.500000");
        em.flush();
        assertThat(jdbc.queryForObject(
                "select count(*) from wholesale.inbound_item where variant_id = " + variantId,
                Integer.class)).isEqualTo(2);
    }

    @Test
    void 음수_조정은_오래된_로트부터_소진한다() {
        Variant variant = variantRepository.findById(variantId).orElseThrow();
        long 오래된로트 = 입고를_기록한다(variant, 50, "8500.00").getId();
        long 새로트 = 입고를_기록한다(variant, 30, "7800.00").getId();

        stockLedger.recordAdjustment(variant, -60);

        assertThat(variant.getStockQty()).isEqualTo(20);
        assertThat(잔량(오래된로트)).isZero();
        assertThat(잔량(새로트)).isEqualTo(20);
        // 남은 로트가 7800 짜리뿐이라 평균원가도 7800
        assertThat(variant.getAvgCost()).isEqualByComparingTo("7800.000000");
    }

    @Test
    void 로트가_부족해도_로트_잔량까지만_소진하고_성공한다() {
        Variant variant = variantRepository.findById(variantId).orElseThrow();
        stockLedger.recordAdjustment(variant, 5);   // 로트 없는 재고 (양수 조정은 로트를 안 만든다)
        long 로트 = 입고를_기록한다(variant, 10, "1000.00").getId();

        // 재고 15 에서 -12 — 로트 잔량(10)보다 크지만 stock_qty 검증은 호출부가 끝냈다는 전제
        stockLedger.recordAdjustment(variant, -12);

        assertThat(variant.getStockQty()).isEqualTo(3);
        assertThat(잔량(로트)).isZero();
    }

    @Test
    void 로트가_모두_소진되면_평균원가는_0이_된다() {
        Variant variant = variantRepository.findById(variantId).orElseThrow();
        입고를_기록한다(variant, 10, "1000.00");

        stockLedger.recordAdjustment(variant, -10);

        assertThat(variant.getStockQty()).isZero();
        assertThat(variant.getAvgCost()).isEqualByComparingTo("0");
    }

    @Test
    void 양수_조정은_로트와_평균원가를_건드리지_않는다() {
        Variant variant = variantRepository.findById(variantId).orElseThrow();
        long 로트 = 입고를_기록한다(variant, 10, "1000.00").getId();

        stockLedger.recordAdjustment(variant, 5);

        assertThat(variant.getStockQty()).isEqualTo(15);
        assertThat(잔량(로트)).isEqualTo(10);
        assertThat(variant.getAvgCost()).isEqualByComparingTo("1000.000000");
        em.flush();
        assertThat(jdbc.queryForObject(
                "select count(*) from wholesale.inbound_item where variant_id = " + variantId,
                Integer.class)).isEqualTo(1);
    }

    @Test
    void 변동마다_원장에_한_줄씩_남는다() {
        Variant variant = variantRepository.findById(variantId).orElseThrow();
        long 로트 = 입고를_기록한다(variant, 10, "1000.00").getId();
        stockLedger.recordAdjustment(variant, -3);
        em.flush();

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select type, qty_change, qty_after, ref_type, ref_id
                from wholesale.stock_movement where variant_id = %d order by id
                """.formatted(variantId));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("type", "IN").containsEntry("qty_change", 10)
                .containsEntry("qty_after", 10).containsEntry("ref_type", "INBOUND_ITEM")
                .containsEntry("ref_id", 로트);
        assertThat(rows.get(1)).containsEntry("type", "ADJUST").containsEntry("qty_change", -3)
                .containsEntry("qty_after", 7).containsEntry("ref_type", null)
                .containsEntry("ref_id", null);
    }

    @Test
    void 출고는_실재고와_예약을_줄이고_FIFO_소진과_OUT_원장을_남긴다() {
        jdbc.update("update wholesale.variant set reserved_qty = 4 where id = ?", variantId);
        Variant variant = variantRepository.findById(variantId).orElseThrow();
        long 로트 = 입고를_기록한다(variant, 10, "1000.00").getId();

        StockMovement movement = stockLedger.recordOutbound(variant, 4, "OUTBOUND", 991L);

        assertThat(variant.getStockQty()).isEqualTo(6);
        assertThat(variant.getReservedQty()).isZero();
        assertThat(잔량(로트)).isEqualTo(6);
        assertThat(variant.getAvgCost()).isEqualByComparingTo("1000.000000");
        assertThat(movement.getQtyChange()).isEqualTo(-4);
        assertThat(movement.getQtyAfter()).isEqualTo(6);
        assertThat(movement.getRefType()).isEqualTo("OUTBOUND");
        assertThat(movement.getRefId()).isEqualTo(991L);
    }

    private InboundItem 입고를_기록한다(Variant variant, int qty, String unitCost) {
        InboundItem lot = inboundItemRepository.save(
                inbound.addLot(variant.getId(), qty, new BigDecimal(unitCost)));
        stockLedger.recordInbound(variant, lot);
        return lot;
    }

    private int 잔량(long lotId) {
        em.flush();
        return jdbc.queryForObject(
                "select remaining_qty from wholesale.inbound_item where id = " + lotId, Integer.class);
    }
}
