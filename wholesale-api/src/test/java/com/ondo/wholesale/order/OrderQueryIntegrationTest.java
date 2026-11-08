package com.ondo.wholesale.order;

import com.ondo.wholesale.security.support.TestSecuritySupport;
import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.OrderFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주문 목록·칩·상세 API 통합 검증 (MUL-47) — 실제 스키마 위에서 조립 결과를 본다.
 * 검색 조건·집계의 세부 규칙은 OrderSpecsTest·OrderSummaryReaderTest 가 이미 본다.
 * q 의 주문 번호 검색(MUL-150)은 여기서 HTTP 까지 — 넘치는 숫자가 500 이 아닌지 — 만 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OrderQueryIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private long wholesalerId;
    private long partnerId;
    private long 니트;
    private long 슬랙스;

    @BeforeEach
    void 재료를_심는다() {
        wholesalerId = MasterDataFixture.도매처를_넣는다(jdbc, "order-query@ondo.test", "9500000006");
        long leafId = MasterDataFixture.카테고리_리프를_넣는다(jdbc, 9100);
        long colorId = MasterDataFixture.색상을_넣는다(jdbc, 9200, 9201);
        니트 = OrderFixture.상품_변형을_넣는다(jdbc, wholesalerId, leafId, colorId, "니트", 1);
        슬랙스 = OrderFixture.상품_변형을_넣는다(jdbc, wholesalerId, leafId, colorId, "슬랙스", 2);
        partnerId = OrderFixture.거래처를_넣는다(jdbc, wholesalerId, 701L, "행복상회");
    }

    @Test
    void 목록은_최신_주문부터_요약_필드를_조립해_내린다() throws Exception {
        long 신규 = 주문(1, "NEW", 시각(10, 0));
        OrderFixture.라인을_넣는다(jdbc, 신규, 니트, 3, 1000, 0, 0);
        OrderFixture.라인을_넣는다(jdbc, 신규, 슬랙스, 5, 2000, 0, 0);
        long 확정 = 주문(2, "CONFIRMED", 시각(11, 0));
        OrderFixture.라인을_넣는다(jdbc, 확정, 니트, 4, 1500, 2, 0);

        mvc.perform(get("/api/wholesale/orders")
                        .with(TestSecuritySupport.approvedAs(wholesalerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].orderNumber").value(2))
                .andExpect(jsonPath("$.data[0].retailerId").value(701))
                .andExpect(jsonPath("$.data[0].retailerName").value("행복상회"))
                .andExpect(jsonPath("$.data[0].status.key").value("CONFIRMED"))
                .andExpect(jsonPath("$.data[0].status.label").value("주문 확정"))
                .andExpect(jsonPath("$.data[0].isPackable").value(true))
                .andExpect(jsonPath("$.data[0].settlementStatus").value("UNPAID"))
                .andExpect(jsonPath("$.data[0].outstandingAmount").value(0))
                .andExpect(jsonPath("$.data[1].summaryProductName").value("니트 (블랙)"))
                .andExpect(jsonPath("$.data[1].additionalItemCount").value(1))
                .andExpect(jsonPath("$.data[1].orderAmount").value(13000))
                .andExpect(jsonPath("$.data[1].isConfirmable").value(true))
                .andExpect(jsonPath("$.meta.totalElements").value(2));
    }

    @Test
    void filter와_retailerId와_정산_필터가_목록을_거른다() throws Exception {
        long 신규 = 주문(1, "NEW", 시각(10, 0));
        OrderFixture.라인을_넣는다(jdbc, 신규, 니트, 3, 1000, 0, 0);
        long 확정 = 주문(2, "CONFIRMED", 시각(11, 0));
        OrderFixture.라인을_넣는다(jdbc, 확정, 니트, 4, 1500, 4, 0);
        OrderFixture.원장_출고를_넣는다(jdbc, partnerId, 확정, 6000);
        OrderFixture.원장_입금을_넣는다(jdbc, partnerId, 확정, 6000);

        mvc.perform(get("/api/wholesale/orders?filter=NEW")
                        .with(TestSecuritySupport.approvedAs(wholesalerId)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].orderNumber").value(1));

        mvc.perform(get("/api/wholesale/orders?retailerId=701")
                        .with(TestSecuritySupport.approvedAs(wholesalerId)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].orderNumber").value(2));

        mvc.perform(get("/api/wholesale/orders?settlementStatus=UNPAID")
                        .with(TestSecuritySupport.approvedAs(wholesalerId)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].orderNumber").value(1));

        mvc.perform(get("/api/wholesale/orders?filter=ALL")
                        .with(TestSecuritySupport.approvedAs(wholesalerId)))
                .andExpect(jsonPath("$.data.length()").value(2));

        mvc.perform(get("/api/wholesale/orders?filter=CANCELLED")
                        .with(TestSecuritySupport.approvedAs(wholesalerId)))
                .andExpect(jsonPath("$.data.length()").value(0));

        mvc.perform(get("/api/wholesale/orders?q=니트")
                        .with(TestSecuritySupport.approvedAs(wholesalerId)))
                .andExpect(jsonPath("$.data.length()").value(2));

        mvc.perform(get("/api/wholesale/orders?from=2026-09-05&to=2026-09-06")
                        .with(TestSecuritySupport.approvedAs(wholesalerId)))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void q에_주문_번호를_주면_그_주문이_나온다() throws Exception {
        long 찾는주문 = 주문(1501, "NEW", 시각(10, 0));
        OrderFixture.라인을_넣는다(jdbc, 찾는주문, 니트, 1, 1000, 0, 0);
        long 다른주문 = 주문(1502, "NEW", 시각(11, 0));
        OrderFixture.라인을_넣는다(jdbc, 다른주문, 니트, 1, 1000, 0, 0);

        mvc.perform(get("/api/wholesale/orders?q=1501")
                        .with(TestSecuritySupport.approvedAs(wholesalerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].orderNumber").value(1501));
    }

    @Test
    void q가_int_범위를_넘는_숫자여도_200이고_빈_목록이다() throws Exception {
        long 신규 = 주문(1, "NEW", 시각(10, 0));
        OrderFixture.라인을_넣는다(jdbc, 신규, 니트, 1, 1000, 0, 0);

        mvc.perform(get("/api/wholesale/orders?q=99999999999")
                        .with(TestSecuritySupport.approvedAs(wholesalerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void 칩도_from이_to보다_뒤면_400이다() throws Exception {
        mvc.perform(get("/api/wholesale/orders/filters?from=2026-09-05&to=2026-09-01")
                        .with(TestSecuritySupport.approvedAs(wholesalerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("from"));
    }

    @Test
    void 칩은_표시_순서대로_라벨과_건수를_내린다() throws Exception {
        long 신규 = 주문(1, "NEW", 시각(10, 0));
        OrderFixture.라인을_넣는다(jdbc, 신규, 니트, 3, 1000, 0, 0);
        long 확정 = 주문(2, "CONFIRMED", 시각(11, 0));
        OrderFixture.라인을_넣는다(jdbc, 확정, 니트, 4, 1500, 4, 0);
        long 부분 = 주문(3, "CONFIRMED", 시각(12, 0));
        OrderFixture.라인을_넣는다(jdbc, 부분, 니트, 4, 1500, 4, 2);

        mvc.perform(get("/api/wholesale/orders/filters")
                        .with(TestSecuritySupport.approvedAs(wholesalerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data[0].key").value("ALL"))
                .andExpect(jsonPath("$.data[0].label").value("전체"))
                .andExpect(jsonPath("$.data[0].count").value(3))
                .andExpect(jsonPath("$.data[1].key").value("NEW"))
                .andExpect(jsonPath("$.data[1].count").value(1))
                .andExpect(jsonPath("$.data[2].key").value("CONFIRMED"))
                .andExpect(jsonPath("$.data[2].label").value("주문 확정"))
                .andExpect(jsonPath("$.data[2].count").value(1))
                .andExpect(jsonPath("$.data[3].key").value("PARTIALLY_SHIPPED"))
                .andExpect(jsonPath("$.data[3].count").value(1))
                .andExpect(jsonPath("$.data[4].key").value("SHIPPED"))
                .andExpect(jsonPath("$.data[4].count").value(0))
                .andExpect(jsonPath("$.data[5].key").value("CANCELLED"))
                .andExpect(jsonPath("$.data[5].count").value(0));
    }

    @Test
    void 상세는_라인_파생값과_SKU_가용재고를_내린다() throws Exception {
        long 확정 = 주문(1, "CONFIRMED", 시각(10, 0));
        jdbc.update("update wholesale.orders set confirmed_at = now() where id = ?", 확정);
        long 라인 = OrderFixture.라인을_넣는다(jdbc, 확정, 니트, 5, 1500, 2, 0);
        OrderFixture.미송을_넣는다(jdbc, 라인, 3, "OPEN");
        jdbc.update("update wholesale.variant set stock_qty = 10, reserved_qty = 3 where id = ?", 니트);

        mvc.perform(get("/api/wholesale/orders/" + 확정)
                        .with(TestSecuritySupport.approvedAs(wholesalerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNumber").value(1))
                .andExpect(jsonPath("$.data.retailerName").value("행복상회"))
                .andExpect(jsonPath("$.data.retailerPhone").isEmpty())
                .andExpect(jsonPath("$.data.expectedPaymentMethod").value("CASH"))
                .andExpect(jsonPath("$.data.receiveBy").value("RETAILER"))
                .andExpect(jsonPath("$.data.confirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.status.key").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.isPackable").value(true))
                .andExpect(jsonPath("$.data.orderAmount").value(7500))
                .andExpect(jsonPath("$.data.totalQty").value(5))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].productNumber").value(1))
                .andExpect(jsonPath("$.data.items[0].variantNumber").value(1))
                .andExpect(jsonPath("$.data.items[0].productName").value("니트"))
                .andExpect(jsonPath("$.data.items[0].color").value("블랙"))
                .andExpect(jsonPath("$.data.items[0].size").value("FREE"))
                .andExpect(jsonPath("$.data.items[0].qty").value(5))
                .andExpect(jsonPath("$.data.items[0].allocatedQty").value(2))
                .andExpect(jsonPath("$.data.items[0].unallocatedQty").value(3))
                .andExpect(jsonPath("$.data.items[0].variantAvailableQty").value(7))
                .andExpect(jsonPath("$.data.items[0].backorderQty").value(3));
    }

    @Test
    void 상세는_2XL_라벨_사이즈도_그대로_내린다() throws Exception {
        // DB 는 '2XL' 라벨로 저장한다 (SizeConverter) — enum 상수명(X2L)으로 읽으면 깨진다
        long leaf2 = MasterDataFixture.카테고리_리프를_넣는다(jdbc, 9165);
        long color2 = MasterDataFixture.색상을_넣는다(jdbc, 9265, 9266);
        long 패딩 = OrderFixture.상품_변형을_넣는다(jdbc, wholesalerId, leaf2, color2, "패딩", 3);
        jdbc.update("update wholesale.variant set size = '2XL' where id = ?", 패딩);
        long 확정 = 주문(1, "CONFIRMED", 시각(10, 0));
        OrderFixture.라인을_넣는다(jdbc, 확정, 패딩, 2, 1000, 0, 0);

        mvc.perform(get("/api/wholesale/orders/" + 확정)
                        .with(TestSecuritySupport.approvedAs(wholesalerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].size").value("2XL"));
    }

    @Test
    void 남의_주문_상세는_404다() throws Exception {
        long 남 = MasterDataFixture.도매처를_넣는다(jdbc, "other-query@ondo.test", "9500000007");
        long 남거래처 = OrderFixture.거래처를_넣는다(jdbc, 남, 702L, "남의상회");
        long 남의주문 = OrderFixture.주문을_넣는다(jdbc, 남, 남거래처, 1, "NEW", 시각(10, 0));

        mvc.perform(get("/api/wholesale/orders/" + 남의주문)
                        .with(TestSecuritySupport.approvedAs(wholesalerId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void 페이징은_meta와_함께_두번째_페이지를_내린다() throws Exception {
        long 첫째 = 주문(1, "NEW", 시각(10, 0));
        OrderFixture.라인을_넣는다(jdbc, 첫째, 니트, 1, 1000, 0, 0);
        long 둘째 = 주문(2, "NEW", 시각(11, 0));
        OrderFixture.라인을_넣는다(jdbc, 둘째, 니트, 1, 1000, 0, 0);

        mvc.perform(get("/api/wholesale/orders?size=1&page=1")
                        .with(TestSecuritySupport.approvedAs(wholesalerId)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].orderNumber").value(1))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.totalElements").value(2))
                .andExpect(jsonPath("$.meta.totalPages").value(2));
    }

    private long 주문(int orderNumber, String status, OffsetDateTime orderedAt) {
        return OrderFixture.주문을_넣는다(jdbc, wholesalerId, partnerId, orderNumber, status, orderedAt);
    }

    private OffsetDateTime 시각(int hour, int minute) {
        return OffsetDateTime.of(2026, 9, 4, hour, minute, 0, 0, ZoneOffset.UTC);
    }
}
