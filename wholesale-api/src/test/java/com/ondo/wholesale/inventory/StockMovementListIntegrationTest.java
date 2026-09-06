package com.ondo.wholesale.inventory;

import com.ondo.wholesale.security.support.TestSecuritySupport;
import com.ondo.wholesale.support.InventoryFixture;
import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 재고 변동 이력 API 통합 검증 (MUL-72) — 시간 역순 페이징·KST 반개구간·type 필터·소유 스코프.
 * 파라미터 형식 400 은 StockMovementListQueryTest 가 단위로 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StockMovementListIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private long wholesalerId;
    private long 코트;

    @BeforeEach
    void 재료를_심는다() {
        wholesalerId = MasterDataFixture.도매처를_넣는다(jdbc, "movements@ondo.test", "9500000022");
        long leafId = MasterDataFixture.카테고리_리프를_넣는다(jdbc, 9152);
        long colorId = MasterDataFixture.색상을_넣는다(jdbc, 9248, 9249);
        코트 = InventoryFixture.변형을_넣는다(jdbc, wholesalerId, leafId, colorId, "코트", 1);
    }

    @Test
    void 이력은_시간_역순으로_페이징되어_내려온다() throws Exception {
        long 첫째 = InventoryFixture.원장을_넣는다(jdbc, 코트, "IN", 50, 50, "INBOUND_ITEM", 701L,
                OffsetDateTime.parse("2026-08-17T10:00:00+09:00"));
        long 둘째 = InventoryFixture.원장을_넣는다(jdbc, 코트, "ADJUST", -5, 45, null, null,
                OffsetDateTime.parse("2026-08-18T10:00:00+09:00"));
        long 셋째 = InventoryFixture.원장을_넣는다(jdbc, 코트, "OUT", -3, 42, "OUTBOUND", 801L,
                OffsetDateTime.parse("2026-08-19T10:00:00+09:00"));

        mvc.perform(get("/api/wholesale/variants/" + 코트 + "/stock-movements")
                        .with(TestSecuritySupport.approvedAs(wholesalerId))
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(셋째))
                .andExpect(jsonPath("$.data[0].type").value("OUT"))
                .andExpect(jsonPath("$.data[0].qtyBefore").value(45))   // qtyAfter − qtyChange 파생
                .andExpect(jsonPath("$.data[0].qtyChange").value(-3))
                .andExpect(jsonPath("$.data[0].qtyAfter").value(42))
                .andExpect(jsonPath("$.data[0].refType").value("OUTBOUND"))
                .andExpect(jsonPath("$.data[0].refId").value(801))
                .andExpect(jsonPath("$.data[1].id").value(둘째))
                .andExpect(jsonPath("$.data[1].refType").value((Object) null))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(2))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.totalPages").value(2));

        mvc.perform(get("/api/wholesale/variants/" + 코트 + "/stock-movements")
                        .with(TestSecuritySupport.approvedAs(wholesalerId))
                        .param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(첫째));
    }

    @Test
    void 기간과_type_필터가_KST_하루_경계로_먹는다() throws Exception {
        InventoryFixture.원장을_넣는다(jdbc, 코트, "IN", 10, 10, "INBOUND_ITEM", 702L,
                OffsetDateTime.parse("2026-08-18T23:59:59+09:00"));   // from 직전 — 빠져야 한다
        long 경계안 = InventoryFixture.원장을_넣는다(jdbc, 코트, "IN", 20, 30, "INBOUND_ITEM", 703L,
                OffsetDateTime.parse("2026-08-19T00:00:00+09:00"));   // from 자정 정각 — 포함
        long 조정 = InventoryFixture.원장을_넣는다(jdbc, 코트, "ADJUST", -1, 29, null, null,
                OffsetDateTime.parse("2026-08-19T12:00:00+09:00"));
        InventoryFixture.원장을_넣는다(jdbc, 코트, "IN", 5, 34, "INBOUND_ITEM", 704L,
                OffsetDateTime.parse("2026-08-20T00:00:00+09:00"));   // to 다음날 자정 — 반개구간 밖

        mvc.perform(get("/api/wholesale/variants/" + 코트 + "/stock-movements")
                        .with(TestSecuritySupport.approvedAs(wholesalerId))
                        .param("from", "2026-08-19").param("to", "2026-08-19").param("type", "IN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(경계안));

        // 다중 type 은 콤마 — IN,ADJUST 면 조정도 함께 나온다
        mvc.perform(get("/api/wholesale/variants/" + 코트 + "/stock-movements")
                        .with(TestSecuritySupport.approvedAs(wholesalerId))
                        .param("from", "2026-08-19").param("to", "2026-08-19").param("type", "IN,ADJUST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(조정))
                .andExpect(jsonPath("$.data[1].id").value(경계안));
    }

    @Test
    void 남의_variant는_404() throws Exception {
        long 남 = MasterDataFixture.도매처를_넣는다(jdbc, "other-movements@ondo.test", "9500000023");

        mvc.perform(get("/api/wholesale/variants/" + 코트 + "/stock-movements")
                        .with(TestSecuritySupport.approvedAs(남)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
