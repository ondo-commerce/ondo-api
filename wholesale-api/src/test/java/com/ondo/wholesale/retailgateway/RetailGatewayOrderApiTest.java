package com.ondo.wholesale.retailgateway;

import com.ondo.wholesale.support.MasterDataFixture;
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
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 소매 주문 접수 API (MUL-98).
 *
 * <p>{@code MUL-82} 스텁을 걷어낸 자리다. 예전 {@code RetailGatewayStubApiTest} 는
 * 하드코딩된 값을 고정하던 것이라 지웠다 — 문이 열려 있다는 성질은 MUL-87 이후
 * {@code GatewaySecretTest} 가 본다.
 *
 * <p><b>절반이 "안 들어가는지" 를 보는 테스트다.</b> 접수는 도매 장부에 쓰는 일이라,
 * 남의 상품이나 바뀐 가격이 조용히 통과하면 되돌리기 어렵다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RetailGatewayOrderApiTest extends PostgresTestSupport {

    private static final long 도매처 = 921L;
    private static final long 남의도매처 = 922L;
    private static final long 소매처 = 8001L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void 데이터를_넣는다() {
        jdbc.update("INSERT INTO common.category (id, parent_id, name, depth, sort_order) VALUES (921, NULL, '여성', 1, 1)");
        jdbc.update("INSERT INTO common.color_group (id, name, sort_order) VALUES (921, '무채색', 1)");
        jdbc.update("INSERT INTO common.color (id, group_id, name, hex, sort_order) VALUES (921, 921, '블랙', '#111111', 1)");

        도매처(도매처, "무드온", "9990000921");
        도매처(남의도매처, "라온", "9990000922");

        // 상품 921 — 게시 중. 옵션 셋
        상품(921, 도매처, 1, "빈티지 셔츠");
        옵션(9211, 921, "S");
        옵션(9212, 921, "M");
        옵션(9213, 921, "L");
        게시(921, 921, "ON_SALE");
        가격(921, 9211, 12500, 0);    // 한도 없음
        가격(921, 9212, 12500, 5);    // 1회 5장까지
        // 9213 은 일부러 게시에서 뺀다 — 판매가가 없는 옵션이다

        // 상품 922 — 시즌 종료
        상품(922, 도매처, 2, "지난 원피스");
        옵션(9221, 922, "M");
        게시(922, 922, "SEASON_ENDED");
        가격(922, 9221, 19000, 0);

        // 상품 923 — 남의 도매처 것
        상품(923, 남의도매처, 1, "남의 셔츠");
        옵션(9231, 923, "M");
        게시(923, 923, "ON_SALE");
        가격(923, 9231, 9900, 0);
    }

    @Test
    @DisplayName("접수하면 주문과 라인이 들어가고 거래처가 생긴다")
    void 접수가_된다() throws Exception {
        mvc.perform(post("/api/retail-gateway/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청(7001, """
                                { "variantId": 9211, "qty": 3, "expectedUnitPrice": 12500 },
                                { "variantId": 9212, "qty": 2, "expectedUnitPrice": 12500 }
                                """)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.retailOrderId").value(7001))
                .andExpect(jsonPath("$.data.status").value("NEW"))
                // 3×12500 + 2×12500
                .andExpect(jsonPath("$.data.orderAmount").value(62500))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.orderNumber").value(1));

        assertThat(count("SELECT count(*) FROM wholesale.orders WHERE retail_order_id = 7001")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM wholesale.order_item")).isEqualTo(2);
        // 첫 거래라 거래처가 새로 생긴다. 상호가 없으면 NOT NULL 에 걸린다
        assertThat(count("SELECT count(*) FROM wholesale.partner"
                + " WHERE wholesaler_id = " + 도매처 + " AND retailer_id = " + 소매처
                + " AND retailer_name = '봄봄상회' AND retailer_phone = '01012345678'")).isEqualTo(1);
    }

    @Test
    @DisplayName("접수는 재고를 안 건드린다 — 모자라도 받는다")
    void 재고를_안_건드린다() throws Exception {
        jdbc.update("UPDATE wholesale.variant SET stock_qty = 1, reserved_qty = 0 WHERE id = 9211");

        mvc.perform(post("/api/retail-gateway/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청(7002, """
                                { "variantId": 9211, "qty": 50, "expectedUnitPrice": 12500 }
                                """)))
                .andExpect(status().isCreated());

        // D-062 — 재고 반영은 확정 시점의 수동 배분이다. 접수는 안 깎고 미송도 안 만든다
        assertThat(count("SELECT stock_qty FROM wholesale.variant WHERE id = 9211")).isEqualTo(1);
        assertThat(count("SELECT reserved_qty FROM wholesale.variant WHERE id = 9211")).isZero();
        assertThat(count("SELECT count(*) FROM wholesale.backorder")).isZero();
    }

    @Test
    @DisplayName("도매처마다 주문번호가 1 부터 따로 센다")
    void 도매처별로_채번한다() throws Exception {
        접수(7003, 도매처, "{ \"variantId\": 9211, \"qty\": 1, \"expectedUnitPrice\": 12500 }");
        접수(7004, 도매처, "{ \"variantId\": 9211, \"qty\": 1, \"expectedUnitPrice\": 12500 }");
        접수(7005, 남의도매처, "{ \"variantId\": 9231, \"qty\": 1, \"expectedUnitPrice\": 9900 }", 남의도매처);

        assertThat(count("SELECT order_number FROM wholesale.orders WHERE retail_order_id = 7003")).isEqualTo(1);
        assertThat(count("SELECT order_number FROM wholesale.orders WHERE retail_order_id = 7004")).isEqualTo(2);
        // 남의 도매처는 자기 연번으로 다시 1 부터다
        assertThat(count("SELECT order_number FROM wholesale.orders WHERE retail_order_id = 7005")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 주문서를 두 번 보내면 409 다 — 주문이 둘 생기지 않는다")
    void 중복_접수는_막는다() throws Exception {
        접수(7006, 도매처, "{ \"variantId\": 9211, \"qty\": 1, \"expectedUnitPrice\": 12500 }");

        mvc.perform(post("/api/retail-gateway/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청(7006, "{ \"variantId\": 9211, \"qty\": 1, \"expectedUnitPrice\": 12500 }")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_CREATED"));

        assertThat(count("SELECT count(*) FROM wholesale.orders WHERE retail_order_id = 7006")).isEqualTo(1);
        // 막힌 요청이 채번을 올리면 도매처 장부의 주문번호에 구멍이 생긴다
        assertThat(count("SELECT last_order_seq FROM wholesale.wholesaler WHERE id = " + 도매처)).isEqualTo(1);
    }

    @Test
    @DisplayName("남의 도매처 상품을 섞어 보내면 400 이다")
    void 남의_상품은_막는다() throws Exception {
        mvc.perform(post("/api/retail-gateway/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청(7007, """
                                { "variantId": 9211, "qty": 1, "expectedUnitPrice": 12500 },
                                { "variantId": 9231, "qty": 1, "expectedUnitPrice": 9900 }
                                """)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VARIANT_WHOLESALER_MISMATCH"));

        // 한 줄이 걸리면 주문 전체가 안 들어간다
        assertThat(count("SELECT count(*) FROM wholesale.orders")).isZero();
    }

    @Test
    @DisplayName("시즌이 끝난 상품은 409 다")
    void 게시가_내려간_상품은_막는다() throws Exception {
        mvc.perform(post("/api/retail-gateway/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청(7008, "{ \"variantId\": 9221, \"qty\": 1, \"expectedUnitPrice\": 19000 }")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LISTING_NOT_ON_SALE"));
    }

    @Test
    @DisplayName("게시에 없는 옵션은 판매가가 없어 409 다")
    void 판매가가_없으면_막는다() throws Exception {
        mvc.perform(post("/api/retail-gateway/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청(7009, "{ \"variantId\": 9213, \"qty\": 1, \"expectedUnitPrice\": 12500 }")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_NOT_SET"));
    }

    @Test
    @DisplayName("담아둔 사이 가격이 바뀌었으면 409 다")
    void 가격이_바뀌면_막는다() throws Exception {
        mvc.perform(post("/api/retail-gateway/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        // 소매 화면은 11000 이라고 알고 있는데 도매는 12500 이다
                        .content(요청(7010, "{ \"variantId\": 9211, \"qty\": 1, \"expectedUnitPrice\": 11000 }")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"));
    }

    @Test
    @DisplayName("1회 주문 한도를 넘으면 409 다. 한도 0 은 무제한이다")
    void 주문_한도를_막는다() throws Exception {
        mvc.perform(post("/api/retail-gateway/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청(7011, "{ \"variantId\": 9212, \"qty\": 6, \"expectedUnitPrice\": 12500 }")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_LIMIT_EXCEEDED"));

        // 9211 은 한도가 0 이라 얼마든지 된다
        접수(7012, 도매처, "{ \"variantId\": 9211, \"qty\": 999, \"expectedUnitPrice\": 12500 }");
    }

    @Test
    @DisplayName("같은 옵션이 두 줄로 오면 400 이다")
    void 같은_옵션이_두번이면_막는다() throws Exception {
        mvc.perform(post("/api/retail-gateway/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청(7013, """
                                { "variantId": 9211, "qty": 1, "expectedUnitPrice": 12500 },
                                { "variantId": 9211, "qty": 2, "expectedUnitPrice": 12500 }
                                """)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ORDER_ITEM"));
    }

    @Test
    @DisplayName("소매 상호가 없으면 400 이다 — 거래처를 못 만든다")
    void 상호가_없으면_막는다() throws Exception {
        mvc.perform(post("/api/retail-gateway/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "retailOrderId": 7014, "retailerId": %d, "wholesalerId": %d,
                                  "retailerName": null, "retailerPhone": "01012345678",
                                  "expectedPaymentMethod": "CASH", "receiveBy": "AGENT",
                                  "agentName": "박삼촌", "agentPhone": "01033330001",
                                  "items": [ { "variantId": 9211, "qty": 1, "expectedUnitPrice": 12500 } ]
                                }
                                """.formatted(소매처, 도매처)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("두 번째 주문은 거래처를 새로 안 만든다")
    void 거래처는_한_번만_만든다() throws Exception {
        접수(7015, 도매처, "{ \"variantId\": 9211, \"qty\": 1, \"expectedUnitPrice\": 12500 }");
        접수(7016, 도매처, "{ \"variantId\": 9211, \"qty\": 1, \"expectedUnitPrice\": 12500 }");

        assertThat(count("SELECT count(*) FROM wholesale.partner"
                + " WHERE wholesaler_id = " + 도매처 + " AND retailer_id = " + 소매처)).isEqualTo(1);
    }

    // ── 조회 (MUL-98) ───────────────────────────────────────────

    @Test
    @DisplayName("도매처 정보에 입금 계좌(주계좌)가 같이 온다")
    void 도매처_정보를_준다() throws Exception {
        // 소매에 나가는 건 주계좌다 (MUL-130) — 먼저 등록한 일반 계좌가 있어도
        MasterDataFixture.계좌를_넣는다(jdbc, 도매처, "신한", "999-999", "부계좌", false);
        MasterDataFixture.계좌를_넣는다(jdbc, 도매처, "국민", "123-456", "무드온", true);

        mvc.perform(get("/api/retail-gateway/wholesalers").param("ids", String.valueOf(도매처)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("무드온"))
                .andExpect(jsonPath("$.data[0].bankName").value("국민"))
                .andExpect(jsonPath("$.data[0].bankAccountNo").value("123-456"));
    }

    @Test
    @DisplayName("계좌를 안 넣은 도매처는 계좌가 비어 온다 — 소매가 계좌이체를 막는 근거다")
    void 계좌가_없으면_빈다() throws Exception {
        mvc.perform(get("/api/retail-gateway/wholesalers").param("ids", String.valueOf(도매처)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].bankName").doesNotExist());
    }

    @Test
    @DisplayName("접수한 주문을 되읽으면 상태와 라인이 온다")
    void 주문을_되읽는다() throws Exception {
        접수(7101, 도매처, "{ \"variantId\": 9211, \"qty\": 3, \"expectedUnitPrice\": 12500 }");

        mvc.perform(get("/api/retail-gateway/orders")
                        .param("retailerId", String.valueOf(소매처))
                        .param("retailOrderIds", "7101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].retailOrderId").value(7101))
                // 상태를 도매가 만들어 내린다. 소매가 같은 규칙을 또 짜면 두 화면이 갈린다
                .andExpect(jsonPath("$.data[0].statusKey").value("NEW"))
                .andExpect(jsonPath("$.data[0].statusLabel").value("신규 주문"))
                .andExpect(jsonPath("$.data[0].cancellable").value(true))
                .andExpect(jsonPath("$.data[0].amount").value(37500))
                .andExpect(jsonPath("$.data[0].agentName").value("박삼촌"))
                .andExpect(jsonPath("$.data[0].wholesaler.storeBuilding").doesNotExist())
                .andExpect(jsonPath("$.data[0].items[0].qty").value(3))
                // 아무것도 안 나갔으니 전부 아직 못 받은 것이다
                .andExpect(jsonPath("$.data[0].items[0].receivedQty").value(0))
                .andExpect(jsonPath("$.data[0].items[0].backorderQty").value(3));
    }

    @Test
    @DisplayName("되읽은 주문의 도매처 계좌는 주계좌다 — 계좌가 여럿이어도 주문이 불어나지 않는다")
    void 되읽은_주문의_계좌는_주계좌다() throws Exception {
        MasterDataFixture.계좌를_넣는다(jdbc, 도매처, "신한", "999-999", "부계좌", false);
        MasterDataFixture.계좌를_넣는다(jdbc, 도매처, "국민", "123-456", "무드온", true);
        MasterDataFixture.계좌를_넣는다(jdbc, 도매처, "기업", "888-888", "부계좌", false);
        접수(7102, 도매처, "{ \"variantId\": 9211, \"qty\": 1, \"expectedUnitPrice\": 12500 }");

        mvc.perform(get("/api/retail-gateway/orders")
                        .param("retailerId", String.valueOf(소매처))
                        .param("retailOrderIds", "7102"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].items.length()").value(1))
                .andExpect(jsonPath("$.data[0].wholesaler.bankName").value("국민"))
                .andExpect(jsonPath("$.data[0].wholesaler.bankAccountNo").value("123-456"))
                .andExpect(jsonPath("$.data[0].wholesaler.bankAccountHolder").value("무드온"));
    }

    @Test
    @DisplayName("남의 소매처 주문서 번호를 넣어도 안 나온다")
    void 남의_주문은_안_준다() throws Exception {
        접수(7102, 도매처, "{ \"variantId\": 9211, \"qty\": 1, \"expectedUnitPrice\": 12500 }");

        // 주문서 id 는 소매가 보낸 값이라 그것만 믿으면 남의 주문을 읽을 수 있다
        mvc.perform(get("/api/retail-gateway/orders")
                        .param("retailerId", "9999")
                        .param("retailOrderIds", "7102"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("주문서 여럿을 한 번에 되읽는다")
    void 여러_주문서를_한번에_읽는다() throws Exception {
        접수(7103, 도매처, "{ \"variantId\": 9211, \"qty\": 1, \"expectedUnitPrice\": 12500 }");
        접수(7104, 도매처, "{ \"variantId\": 9212, \"qty\": 1, \"expectedUnitPrice\": 12500 }");

        mvc.perform(get("/api/retail-gateway/orders")
                        .param("retailerId", String.valueOf(소매처))
                        .param("retailOrderIds", "7103,7104"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    // ── 거들기 ──────────────────────────────────────────────────

    private void 접수(long retailOrderId, long wholesalerId, String items) throws Exception {
        접수(retailOrderId, wholesalerId, items, wholesalerId);
    }

    private void 접수(long retailOrderId, long wholesalerId, String items, long 보낼도매처) throws Exception {
        mvc.perform(post("/api/retail-gateway/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청(retailOrderId, items, 보낼도매처)))
                .andExpect(status().isCreated());
    }

    private String 요청(long retailOrderId, String items) {
        return 요청(retailOrderId, items, 도매처);
    }

    private String 요청(long retailOrderId, String items, long wholesalerId) {
        return """
                {
                  "retailOrderId": %d, "retailerId": %d, "wholesalerId": %d,
                  "retailerName": "봄봄상회", "retailerPhone": "01012345678",
                  "expectedPaymentMethod": "CASH", "receiveBy": "AGENT",
                  "agentName": "박삼촌", "agentPhone": "01033330001",
                  "items": [ %s ]
                }
                """.formatted(retailOrderId, 소매처, wholesalerId, items);
    }

    private long count(String sql) {
        Long v = jdbc.queryForObject(sql, Long.class);
        return v == null ? 0 : v;
    }

    // ── 데이터 심기 ─────────────────────────────────────────────

    private void 도매처(long id, String name, String bizRegNo) {
        jdbc.update("""
                INSERT INTO wholesale.wholesaler
                    (id, email, password_hash, biz_reg_no, biz_name, biz_owner_name,
                     approval_status, last_product_seq, last_order_seq)
                VALUES (?, ?, 'x', ?, ?, '테스트', 'APPROVED', 2, 0)
                """, id, "gw-order-" + id + "@ondo.test", bizRegNo, name);
    }

    private void 상품(long id, long wholesalerId, int productNumber, String name) {
        jdbc.update("INSERT INTO wholesale.product (id, wholesaler_id, product_number, name, category_id, last_variant_seq)"
                + " VALUES (?, ?, ?, ?, 921, 3)", id, wholesalerId, productNumber, name);
        jdbc.update("INSERT INTO wholesale.color_option (id, product_id, color_id) VALUES (?, ?, 921)", id, id);
    }

    private void 옵션(long variantId, long productId, String size) {
        jdbc.update("INSERT INTO wholesale.variant (id, color_option_id, product_id, size, variant_seq, stock_qty)"
                + " VALUES (?, ?, ?, ?, ?, 100)", variantId, productId, productId, size, (int) (variantId % 10));
    }

    private void 게시(long listingId, long productId, String status) {
        jdbc.update("INSERT INTO wholesale.listing (id, product_id, title, single_piece_allowed, status, season_started_at)"
                + " VALUES (?, ?, '게시글', false, ?, now())", listingId, productId, status);
    }

    private void 가격(long listingId, long variantId, int salePrice, int orderLimit) {
        jdbc.update("INSERT INTO wholesale.listing_variant (listing_id, variant_id, sale_price, order_limit)"
                + " VALUES (?, ?, ?, ?)", listingId, variantId, salePrice, orderLimit);
    }
}
