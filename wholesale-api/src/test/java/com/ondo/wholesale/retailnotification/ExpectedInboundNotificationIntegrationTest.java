package com.ondo.wholesale.retailnotification;

import com.ondo.wholesale.security.support.TestSecuritySupport;
import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.OrderFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 입고 예정일 변경 → 소매 알림 (MUL-155).
 *
 * <p>여기서 보는 것: 누가 몇 건을 받는지, 문구에 자기 몫만 들어가는지, 날짜가 안 바뀌면 0건인지.
 * PUT 자체의 대체 규칙은 ExpectedInboundIntegrationTest 가, 문구 글자는 BackorderDateChangeTextTest 가,
 * 소매가 읽어 가는 길은 RetailGatewayNotificationApiTest 가 본다.
 *
 * <p>잡고 싶은 사고 — 남의 소매처 수량이나 SKU 합계가 문구에 섞여 나가는 것.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExpectedInboundNotificationIntegrationTest extends PostgresTestSupport {

    private static final long 봄봄 = 8101L;
    private static final long 라온 = 8102L;
    private static final long 다솜 = 8103L;
    private static final long 직거래처 = 8104L;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private long wholesalerId;
    private long 자켓;

    @BeforeEach
    void 미송을_심는다() {
        wholesalerId = MasterDataFixture.도매처를_넣는다(jdbc, "notice@ondo.test", "9500000155");
        jdbc.update("update wholesale.wholesaler set biz_name = '무드온' where id = ?", wholesalerId);
        long leafId = MasterDataFixture.카테고리_리프를_넣는다(jdbc, 9155);
        long colorId = MasterDataFixture.색상을_넣는다(jdbc, 9156, 9157);
        자켓 = OrderFixture.상품_변형을_넣는다(jdbc, wholesalerId, leafId, colorId, "린넨 셋업 자켓", 55);
        jdbc.update("update wholesale.variant set expected_inbound_date = '2026-11-05' where id = ?", 자켓);

        long 봄봄거래처 = OrderFixture.거래처를_넣는다(jdbc, wholesalerId, 봄봄, "봄봄상회");
        long 라온거래처 = OrderFixture.거래처를_넣는다(jdbc, wholesalerId, 라온, "라온");
        long 다솜거래처 = OrderFixture.거래처를_넣는다(jdbc, wholesalerId, 다솜, "다솜");
        long 직거래 = OrderFixture.거래처를_넣는다(jdbc, wholesalerId, 직거래처, "직거래");

        // 봄봄 — 주문 둘에 걸쳐 남은 2 + 1 = 3장
        미송(봄봄거래처, 1, 7001L, 5, 3, "OPEN");
        미송(봄봄거래처, 2, 7002L, 4, 3, "OPEN");
        // 라온 — 남은 6장 + 해소된 미송(안 센다)
        미송(라온거래처, 3, 7003L, 10, 4, "OPEN");
        미송(라온거래처, 4, 7004L, 9, 0, "RESOLVED");
        // 다솜 — OPEN 이지만 배분이 먼저 끝나 남은 게 0 이다 → 받지 않는다
        미송(다솜거래처, 5, 7005L, 3, 3, "OPEN");
        // 직거래 — 도매가 자기 화면에서 넣은 주문. 소매 화면에 없는 미송이라 받지 않는다
        미송(직거래, 6, null, 5, 0, "OPEN");
    }

    @Test
    @DisplayName("OPEN 미송이 남은 소매처마다 1건, 문구엔 자기 몫만")
    void 소매처마다_자기_몫만_받는다() throws Exception {
        mvc.perform(등록("{ \"expectedInboundDate\": \"2026-11-08\", \"expectedInboundReason\": \"원단 입고 지연\" }"))
                .andExpect(status().isOk());

        List<Map<String, Object>> rows = 알림();
        assertThat(rows).extracting(r -> r.get("retailer_id")).containsExactly(봄봄, 라온);

        LocalDate 오늘 = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Map<String, Object> 봄봄알림 = rows.get(0);
        assertThat(봄봄알림.get("type")).isEqualTo("BACKORDER_DATE_CHANGED");
        assertThat(봄봄알림.get("title")).isEqualTo("무드온 미송 입고일 변경");
        assertThat(봄봄알림.get("body")).isEqualTo(BackorderDateChangeText.body("린넨 셋업 자켓", "블랙", "FREE", 3,
                LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 8), 오늘));
        assertThat(봄봄알림.get("target_kind")).isEqualTo("BACKORDERS");
        assertThat(봄봄알림.get("target_wholesaler_id")).isEqualTo(wholesalerId);
        assertThat(봄봄알림.get("read_at")).isNull();

        String 라온본문 = (String) rows.get(1).get("body");
        assertThat(라온본문).contains("남은 6장");
        // 남의 이름 · 남의 수량 · SKU 합계(3 + 6 = 9) 가 없다
        assertThat(rows).allSatisfy(r -> {
            String text = r.get("title") + " " + r.get("body");
            assertThat(text).doesNotContain("봄봄상회", "라온", "다솜", "직거래", "9장");
        });
        assertThat((String) 봄봄알림.get("body")).doesNotContain("6장");
    }

    @Test
    @DisplayName("사유만 바뀌면 0건이다")
    void 사유만_바뀌면_알림이_없다() throws Exception {
        mvc.perform(등록("{ \"expectedInboundDate\": \"2026-11-05\", \"expectedInboundReason\": \"새 사유\" }"))
                .andExpect(status().isOk());

        assertThat(알림()).isEmpty();
    }

    @Test
    @DisplayName("해제(날짜 → 미정)도 바뀐 것이다")
    void 해제도_알린다() throws Exception {
        mvc.perform(등록("{ \"expectedInboundDate\": null, \"expectedInboundReason\": null }"))
                .andExpect(status().isOk());

        assertThat(알림()).hasSize(2)
                .allSatisfy(r -> assertThat((String) r.get("body")).endsWith("→ 입고일 미정"));
    }

    @Test
    @DisplayName("미정 → 날짜도 바뀐 것이다")
    void 미정에서_날짜로도_알린다() throws Exception {
        jdbc.update("update wholesale.variant set expected_inbound_date = null where id = ?", 자켓);

        mvc.perform(등록("{ \"expectedInboundDate\": \"2026-11-08\", \"expectedInboundReason\": null }"))
                .andExpect(status().isOk());

        assertThat(알림()).hasSize(2)
                .allSatisfy(r -> assertThat((String) r.get("body")).contains("입고일 미정 → "));
    }

    @Test
    @DisplayName("남의 SKU 면 404 이고 알림도 없다")
    void 없는_SKU는_알림_없이_404() throws Exception {
        long 남의도매 = MasterDataFixture.도매처를_넣는다(jdbc, "other-notice@ondo.test", "9500000156");

        mvc.perform(put("/api/wholesale/variants/" + 자켓 + "/expected-inbound")
                        .with(TestSecuritySupport.approvedAs(남의도매))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"expectedInboundDate\": \"2026-11-08\", \"expectedInboundReason\": null }"))
                .andExpect(status().isNotFound());

        assertThat(알림()).isEmpty();
    }

    // ── 픽스처 ──────────────────────────────────────────────

    private void 미송(long partnerId, int orderNumber, Long retailOrderId, int qty, int allocated, String status) {
        long orderId = OrderFixture.주문을_넣는다(jdbc, wholesalerId, partnerId, 9550 + orderNumber, "CONFIRMED",
                OffsetDateTime.now().minusDays(orderNumber));
        jdbc.update("update wholesale.orders set retail_order_id = ? where id = ?", retailOrderId, orderId);
        long itemId = OrderFixture.라인을_넣는다(jdbc, orderId, 자켓, qty, 10000, allocated, 0);
        OrderFixture.미송을_넣는다(jdbc, itemId, qty - allocated, status);
    }

    private List<Map<String, Object>> 알림() {
        return jdbc.queryForList("""
                select retailer_id, type, title, body, target_kind, target_wholesaler_id, read_at
                from wholesale.retail_notification
                where retailer_id in (?, ?, ?, ?)
                order by retailer_id
                """, 봄봄, 라온, 다솜, 직거래처);
    }

    private MockHttpServletRequestBuilder 등록(String body) {
        return put("/api/wholesale/variants/" + 자켓 + "/expected-inbound")
                .with(TestSecuritySupport.approvedAs(wholesalerId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
