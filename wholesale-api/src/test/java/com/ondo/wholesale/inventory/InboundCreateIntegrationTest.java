package com.ondo.wholesale.inventory;

import com.ondo.wholesale.security.support.TestSecuritySupport;
import com.ondo.wholesale.support.InventoryFixture;
import com.ondo.wholesale.support.MasterDataFixture;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 입고 등록 API 통합 검증 (MUL-72) — Idempotency-Key 멱등과 로트 생성, 에러 매핑.
 *
 * <p>FIFO·평균원가의 계산 규칙은 StockLedgerTest 가 이미 본다. 여기는 HTTP 계약
 * (라인 순서 누적 응답·replay·에러 코드)과 한 트랜잭션의 부수효과를 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InboundCreateIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    private long wholesalerId;
    private long 티셔츠;
    private long 셔츠;

    @BeforeEach
    void 재료를_심는다() {
        wholesalerId = MasterDataFixture.도매처를_넣는다(jdbc, "inbound@ondo.test", "9500000019");
        long leafId = MasterDataFixture.카테고리_리프를_넣는다(jdbc, 9146);
        long colorId = MasterDataFixture.색상을_넣는다(jdbc, 9244, 9245);
        티셔츠 = InventoryFixture.변형을_넣는다(jdbc, wholesalerId, leafId, colorId, "티셔츠", 1);
        셔츠 = InventoryFixture.변형을_넣는다(jdbc, wholesalerId, leafId, colorId, "셔츠", 2);
    }

    @Test
    void 입고는_라인_순서대로_누적된_변동후_재고와_평균원가를_내린다() throws Exception {
        입고요청("K-lines", """
                { "receivedAt": "2026-08-19T14:30:00+09:00", "items": [
                  { "variantId": %d, "qty": 50, "unitCost": 8500 },
                  { "variantId": %d, "qty": 30, "unitCost": 7800 } ] }""".formatted(티셔츠, 티셔츠))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.receivedAt").value("2026-08-19T14:30:00+09:00"))
                .andExpect(jsonPath("$.data.items[0].variantId").value(티셔츠))
                .andExpect(jsonPath("$.data.items[0].productNumber").value(1))
                .andExpect(jsonPath("$.data.items[0].variantNumber").value(1))
                .andExpect(jsonPath("$.data.items[0].remainingQty").value(50))
                .andExpect(jsonPath("$.data.items[0].qtyAfter").value(50))
                .andExpect(jsonPath("$.data.items[0].avgCostAfter").value(8500.00))
                .andExpect(jsonPath("$.data.items[1].qtyAfter").value(80))
                .andExpect(jsonPath("$.data.items[1].avgCostAfter").value(8237.50));

        em.flush();
        assertThat(정수("select stock_qty from wholesale.variant where id = " + 티셔츠)).isEqualTo(80);
        assertThat(jdbc.queryForObject(
                "select avg_cost from wholesale.variant where id = " + 티셔츠, java.math.BigDecimal.class))
                .isEqualByComparingTo("8237.5");
        assertThat(정수("select count(*) from wholesale.inbound_item where variant_id = " + 티셔츠)).isEqualTo(2);
        assertThat(정수("select count(*) from wholesale.stock_movement where variant_id = " + 티셔츠)).isEqualTo(2);
    }

    @Test
    void 같은_키_같은_본문_재요청은_200_동일_본문이고_재고가_두번_오르지_않는다() throws Exception {
        String body = """
                { "receivedAt": "2026-08-19T14:30:00+09:00", "items": [
                  { "variantId": %d, "qty": 50, "unitCost": 8500 } ] }""".formatted(티셔츠);

        String first = 입고요청("K-replay", body)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = 입고요청("K-replay", body)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        em.flush();
        assertThat(정수("select stock_qty from wholesale.variant where id = " + 티셔츠)).isEqualTo(50);
        assertThat(정수("select count(*) from wholesale.inbound where wholesaler_id = " + wholesalerId)).isEqualTo(1);
        assertThat(정수("select count(*) from wholesale.stock_movement where variant_id = " + 티셔츠)).isEqualTo(1);
    }

    @Test
    void 같은_키_다른_본문은_409_IDEMPOTENCY_KEY_REUSED() throws Exception {
        입고요청("K-reuse", """
                { "items": [ { "variantId": %d, "qty": 50, "unitCost": 8500 } ] }""".formatted(티셔츠))
                .andExpect(status().isCreated());

        입고요청("K-reuse", """
                { "items": [ { "variantId": %d, "qty": 60, "unitCost": 8500 } ] }""".formatted(티셔츠))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        em.flush();
        assertThat(정수("select stock_qty from wholesale.variant where id = " + 티셔츠)).isEqualTo(50);
    }

    @Test
    void 같은_로트_중복은_400_DUPLICATE_LOT() throws Exception {
        // 8500 과 8500.00 은 같은 단가 — 표기만 다른 중복도 걸러야 로트가 두 벌 남지 않는다
        입고요청("K-dup", """
                { "items": [
                  { "variantId": %d, "qty": 50, "unitCost": 8500 },
                  { "variantId": %d, "qty": 30, "unitCost": 8500.00 } ] }""".formatted(티셔츠, 티셔츠))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_LOT"));

        assertThat(정수("select count(*) from wholesale.inbound_item where variant_id = " + 티셔츠)).isZero();
    }

    @Test
    void 같은_variant_다른_단가는_별도_로트다() throws Exception {
        입고요청("K-lots", """
                { "items": [
                  { "variantId": %d, "qty": 50, "unitCost": 8500 },
                  { "variantId": %d, "qty": 30, "unitCost": 7800 },
                  { "variantId": %d, "qty": 60, "unitCost": 8500 } ] }""".formatted(티셔츠, 티셔츠, 셔츠))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.items.length()").value(3));

        em.flush();
        assertThat(정수("select count(*) from wholesale.inbound_item where variant_id = " + 티셔츠)).isEqualTo(2);
        assertThat(정수("select count(*) from wholesale.inbound_item where variant_id = " + 셔츠)).isEqualTo(1);
    }

    @Test
    void 남의_variant는_404() throws Exception {
        long 남 = MasterDataFixture.도매처를_넣는다(jdbc, "other-inbound@ondo.test", "9500000020");
        long 남카테고리 = MasterDataFixture.카테고리_리프를_넣는다(jdbc, 9155);
        long 남의변형 = InventoryFixture.변형을_넣는다(jdbc, 남, 남카테고리, 9245, "남의옷", 1);

        입고요청("K-notmine", """
                { "items": [ { "variantId": %d, "qty": 10, "unitCost": 1000 } ] }""".formatted(남의변형))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void 삭제된_variant는_409_STATE_CONFLICT() throws Exception {
        jdbc.update("update wholesale.variant set deleted_at = now() where id = ?", 티셔츠);

        입고요청("K-deleted", """
                { "items": [ { "variantId": %d, "qty": 10, "unitCost": 1000 } ] }""".formatted(티셔츠))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void 빈_라인이나_0이하_수량은_400_INVARIANT_VIOLATED() throws Exception {
        입고요청("K-empty", "{ \"items\": [] }")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVARIANT_VIOLATED"));

        입고요청("K-zero", """
                { "items": [ { "variantId": %d, "qty": 0, "unitCost": 1000 } ] }""".formatted(티셔츠))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVARIANT_VIOLATED"));

        입고요청("K-negcost", """
                { "items": [ { "variantId": %d, "qty": 10, "unitCost": -1 } ] }""".formatted(티셔츠))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVARIANT_VIOLATED"));
    }

    @Test
    void Idempotency_Key가_없으면_400() throws Exception {
        mvc.perform(post("/api/wholesale/inbounds")
                        .with(TestSecuritySupport.approvedAs(wholesalerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "items": [ { "variantId": %d, "qty": 10, "unitCost": 1000 } ] }"""
                                .formatted(티셔츠)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private ResultActions 입고요청(String idempotencyKey, String body) throws Exception {
        return mvc.perform(post("/api/wholesale/inbounds")
                .with(TestSecuritySupport.approvedAs(wholesalerId))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private int 정수(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
