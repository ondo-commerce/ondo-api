package com.ondo.wholesale.inventory;

import com.ondo.wholesale.security.support.TestSecuritySupport;
import com.ondo.wholesale.support.InventoryFixture;
import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.OrderFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 재고 조정 API 통합 검증 (MUL-72).
 *
 * <p>FIFO 소진·평균원가 재계산의 계산 규칙은 StockLedgerTest 가 이미 본다. 여기는
 * HTTP 계약(응답 = 이력 항목·에러 코드)과 예약 하한 409 의 취소 후보 목록을 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StockAdjustmentIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    private long wholesalerId;
    private long 바지;

    @BeforeEach
    void 재료를_심는다() {
        wholesalerId = MasterDataFixture.도매처를_넣는다(jdbc, "adjust@ondo.test", "9500000021");
        long leafId = MasterDataFixture.카테고리_리프를_넣는다(jdbc, 9149);
        long colorId = MasterDataFixture.색상을_넣는다(jdbc, 9246, 9247);
        바지 = InventoryFixture.변형을_넣는다(jdbc, wholesalerId, leafId, colorId, "바지", 1);
    }

    @Test
    void 조정은_ADJUST_이력_한줄을_만들고_그_항목으로_답한다_ref는_null() throws Exception {
        InventoryFixture.재고를_둔다(jdbc, 바지, 96, 0);

        조정요청(바지, -5)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.variantId").value(바지))
                .andExpect(jsonPath("$.data.type").value("ADJUST"))
                .andExpect(jsonPath("$.data.qtyBefore").value(96))
                .andExpect(jsonPath("$.data.qtyChange").value(-5))
                .andExpect(jsonPath("$.data.qtyAfter").value(91))
                .andExpect(jsonPath("$.data.refType").value((Object) null))
                .andExpect(jsonPath("$.data.refId").value((Object) null))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty());

        em.flush();
        assertThat(정수("select stock_qty from wholesale.variant where id = " + 바지)).isEqualTo(91);
        assertThat(jdbc.queryForList(
                "select type, qty_change, qty_after, ref_type, ref_id from wholesale.stock_movement"
                        + " where variant_id = " + 바지))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("type")).isEqualTo("ADJUST");
                    assertThat(row.get("qty_change")).isEqualTo(-5);
                    assertThat(row.get("qty_after")).isEqualTo(91);
                    assertThat(row.get("ref_type")).isNull();
                    assertThat(row.get("ref_id")).isNull();
                });
    }

    @Test
    void qtyChange_0은_400_INVARIANT_VIOLATED() throws Exception {
        조정요청(바지, 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVARIANT_VIOLATED"));
    }

    @Test
    void 재고를_0_밑으로_내리면_409_STOCK_BELOW_ZERO() throws Exception {
        InventoryFixture.재고를_둔다(jdbc, 바지, 3, 0);

        조정요청(바지, -5)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_BELOW_ZERO"));

        assertThat(정수("select count(*) from wholesale.stock_movement where variant_id = " + 바지)).isZero();
        assertThat(정수("select stock_qty from wholesale.variant where id = " + 바지)).isEqualTo(3);
    }

    @Test
    void 예약_밑으로_내리면_409와_취소_후보_포장_목록을_내린다() throws Exception {
        InventoryFixture.재고를_둔다(jdbc, 바지, 10, 8);
        long partnerId = OrderFixture.거래처를_넣는다(jdbc, wholesalerId, 720L, "조정상회");
        long orderId = OrderFixture.주문을_넣는다(jdbc, wholesalerId, partnerId, 42, "CONFIRMED", OffsetDateTime.now());
        long 라인 = OrderFixture.라인을_넣는다(jdbc, orderId, 바지, 8, 1000, 8, 0);
        long batchId = OrderFixture.배분_배치를_넣는다(jdbc, wholesalerId);

        long 대기포장 = OrderFixture.포장을_넣는다(jdbc, orderId, "READY");
        OrderFixture.포장항목을_넣는다(jdbc, 대기포장, 라인, null, batchId, 5, false);

        long outboundId = jdbc.queryForObject("""
                insert into wholesale.outbound (wholesaler_id, partner_id, outbound_number)
                values (?, ?, 'PKG-001') returning id
                """, Long.class, wholesalerId, partnerId);
        long 출고포장 = OrderFixture.포장을_넣는다(jdbc, orderId, "PACKED");
        jdbc.update("update wholesale.packing set outbound_id = ? where id = ?", outboundId, 출고포장);
        OrderFixture.포장항목을_넣는다(jdbc, 출고포장, 라인, null, batchId, 3, false);

        // 배분취소된 항목은 후보에 나오면 안 된다
        long 취소포장 = OrderFixture.포장을_넣는다(jdbc, orderId, "READY");
        OrderFixture.포장항목을_넣는다(jdbc, 취소포장, 라인, null, batchId, 2, true);

        조정요청(바지, -5)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_BELOW_ALLOCATED"))
                .andExpect(jsonPath("$.errors[0].field").value("qtyChange"))
                .andExpect(jsonPath("$.errors[0].data.stockQty").value(10))
                .andExpect(jsonPath("$.errors[0].data.allocatedQty").value(8))
                .andExpect(jsonPath("$.errors[0].data.requestedQtyChange").value(-5))
                .andExpect(jsonPath("$.errors[0].data.requiredCancelQty").value(3))
                .andExpect(jsonPath("$.errors[0].data.blockingPackings.length()").value(2))
                .andExpect(jsonPath("$.errors[0].data.blockingPackings[0].packingId").value(대기포장))
                .andExpect(jsonPath("$.errors[0].data.blockingPackings[0].orderNumber").value(42))
                .andExpect(jsonPath("$.errors[0].data.blockingPackings[0].qty").value(5))
                .andExpect(jsonPath("$.errors[0].data.blockingPackings[0].status").value("READY"))
                .andExpect(jsonPath("$.errors[0].data.blockingPackings[0].outboundId").value((Object) null))
                .andExpect(jsonPath("$.errors[0].data.blockingPackings[0].isCancellable").value(true))
                .andExpect(jsonPath("$.errors[0].data.blockingPackings[1].packingId").value(출고포장))
                .andExpect(jsonPath("$.errors[0].data.blockingPackings[1].status").value("PACKED"))
                .andExpect(jsonPath("$.errors[0].data.blockingPackings[1].outboundId").value(outboundId))
                .andExpect(jsonPath("$.errors[0].data.blockingPackings[1].isCancellable").value(false));

        assertThat(정수("select stock_qty from wholesale.variant where id = " + 바지)).isEqualTo(10);
    }

    @Test
    void 음수_조정이_로트를_소진하고_평균원가를_재계산한다() throws Exception {
        long inboundId = InventoryFixture.입고를_넣는다(jdbc, wholesalerId);
        long 오래된로트 = InventoryFixture.로트를_넣는다(jdbc, inboundId, 바지, 50, 50, "8500.00");
        long 새로트 = InventoryFixture.로트를_넣는다(jdbc, inboundId, 바지, 30, 30, "7800.00");
        InventoryFixture.재고를_둔다(jdbc, 바지, 80, 0);

        조정요청(바지, -60)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.qtyAfter").value(20));

        em.flush();
        assertThat(정수("select remaining_qty from wholesale.inbound_item where id = " + 오래된로트)).isZero();
        assertThat(정수("select remaining_qty from wholesale.inbound_item where id = " + 새로트)).isEqualTo(20);
        assertThat(jdbc.queryForObject(
                "select avg_cost from wholesale.variant where id = " + 바지, BigDecimal.class))
                .isEqualByComparingTo("7800");
    }

    private ResultActions 조정요청(long variantId, int qtyChange) throws Exception {
        return mvc.perform(post("/api/wholesale/variants/" + variantId + "/stock-adjustments")
                .with(TestSecuritySupport.approvedAs(wholesalerId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"qtyChange\": %d }".formatted(qtyChange)));
    }

    private int 정수(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
