package com.ondo.wholesale.retailgateway;

import com.ondo.wholesale.security.support.TestSecuritySupport;
import com.ondo.wholesale.settlement.service.ReceivableLedgerWriter;
import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.OrderFixture;
import com.ondo.wholesale.support.OutboundFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 소매 정산 조회 (MUL-129). 소매처별로 잘린 데이터라 <b>무엇이 안 나오는지</b>가 절반이다.
 *
 * <p>바탕 — 우리 소매처(8001)가 무드온 · 라라도매와 거래한다. 남의 소매처(8002)도 무드온과 거래한다.
 * <ul>
 *   <li>무드온 · 우리: 셔츠 주문 3만 사흘 전 출고(장끼 2), 바지 주문 10만 오늘 출고(장끼 5).
 *       이틀 전 8만 입금해 바지에 5만 배분(3만 남음). 어제 1만 입금했다가 취소.</li>
 *   <li>라라도매 · 우리: 주문만 있고 출고 · 입금 없음</li>
 *   <li>무드온 · 남: 7만 출고, 입금 없음</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RetailGatewaySettlementApiTest extends PostgresTestSupport {

    private static final long 우리 = 8001L;
    private static final long 남 = 8002L;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReceivableLedgerWriter ledgerWriter;

    private long 무드온;
    private long 라라도매;
    private long 셔츠주문;
    private long 바지주문;
    private long 입금;

    @BeforeEach
    void 거래를_심는다() throws Exception {
        무드온 = MasterDataFixture.도매처를_넣는다(jdbc, "gw-settle-moodon@ondo.test", "9500000140");
        라라도매 = MasterDataFixture.도매처를_넣는다(jdbc, "gw-settle-lala@ondo.test", "9500000141");
        jdbc.update("update wholesale.wholesaler set biz_name = '무드온' where id = ?", 무드온);
        // 소매에 나가는 건 주계좌다 (MUL-130) — 먼저 등록한 일반 계좌가 있어도
        MasterDataFixture.계좌를_넣는다(jdbc, 무드온, "신한", "999-99-999", "김부계좌", false);
        MasterDataFixture.계좌를_넣는다(jdbc, 무드온, "국민", "123-45-678", "김무드", true);
        jdbc.update("update wholesale.wholesaler set biz_name = '라라도매' where id = ?", 라라도매);

        long 우리무드온 = OrderFixture.거래처를_넣는다(jdbc, 무드온, 우리, "우리소매");
        long 우리라라 = OrderFixture.거래처를_넣는다(jdbc, 라라도매, 우리, "우리소매");
        long 남무드온 = OrderFixture.거래처를_넣는다(jdbc, 무드온, 남, "남의소매");

        OffsetDateTime now = OffsetDateTime.now();
        셔츠주문 = 출고된_주문(무드온, 우리무드온, 1, 9101L, 30000, now.minusDays(3), 2);
        바지주문 = 출고된_주문(무드온, 우리무드온, 2, 9102L, 100000, now, 5);
        OutboundFixture.확정주문을_넣는다(jdbc, 라라도매, 우리라라, 1, "RETAILER", now);
        출고된_주문(무드온, 남무드온, 3, 9201L, 70000, now.minusDays(5), 1);

        입금 = 입금한다(80000, now.minusDays(2),
                "[ {\"orderId\": %d, \"amount\": 50000} ]".formatted(바지주문));
        long 취소할입금 = 입금한다(10000, now.minusDays(1), "[]");
        mvc.perform(post("/api/wholesale/payments/" + 취소할입금 + "/void")
                        .with(TestSecuritySupport.approvedAs(무드온))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"reason\": \"잘못 넣음\" }"))
                .andExpect(status().isOk());
    }

    @Test
    void 요약은_우리_거래처만_빚이_큰_순으로_연체와_최근_입금을_담는다() throws Exception {
        mvc.perform(get("/api/retail-gateway/settlements?retailerId=" + 우리))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                // 무드온: 3만 + 10만 − 8만 = 5만. 취소된 1만은 안 센다
                .andExpect(jsonPath("$.data[0].wholesalerId").value(무드온))
                .andExpect(jsonPath("$.data[0].wholesalerName").value("무드온"))
                .andExpect(jsonPath("$.data[0].balance").value(50000))
                // 셔츠 3만: 사흘 전 출고 → 기한 이틀 전 → D+2. 바지는 오늘 출고라 아직
                .andExpect(jsonPath("$.data[0].overdue.amount").value(30000))
                .andExpect(jsonPath("$.data[0].overdue.count").value(1))
                .andExpect(jsonPath("$.data[0].overdue.maxDays").value(2))
                .andExpect(jsonPath("$.data[0].lastPaidAt").value(LocalDate.now(KST).minusDays(2).toString()))
                .andExpect(jsonPath("$.data[0].paidLast7Days").value(80000))
                .andExpect(jsonPath("$.data[0].bankName").value("국민"))
                .andExpect(jsonPath("$.data[0].bankAccountHolder").value("김무드"))
                // 라라도매: 주문만 있고 돈은 안 오갔다
                .andExpect(jsonPath("$.data[1].wholesalerName").value("라라도매"))
                .andExpect(jsonPath("$.data[1].balance").value(0))
                .andExpect(jsonPath("$.data[1].overdue.amount").value(0))
                .andExpect(jsonPath("$.data[1].lastPaidAt").doesNotExist())
                .andExpect(jsonPath("$.data[1].bankName").doesNotExist());
    }

    @Test
    void 원장은_오래된_순이고_취소된_입금은_빠지며_배분과_남은_돈을_준다() throws Exception {
        mvc.perform(get("/api/retail-gateway/settlements/ledger?retailerId=" + 우리 + "&wholesalerId=" + 무드온))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].kind").value("SHIPMENT"))
                .andExpect(jsonPath("$.data[0].delta").value(30000))
                .andExpect(jsonPath("$.data[0].retailOrderId").value(9101))
                .andExpect(jsonPath("$.data[0].statementNumber").value(2))
                .andExpect(jsonPath("$.data[0].shippedAt").isNotEmpty())
                .andExpect(jsonPath("$.data[1].kind").value("PAYMENT"))
                .andExpect(jsonPath("$.data[1].delta").value(-80000))
                .andExpect(jsonPath("$.data[1].method").value("CASH"))
                .andExpect(jsonPath("$.data[1].allocations.length()").value(1))
                .andExpect(jsonPath("$.data[1].allocations[0].retailOrderId").value(9102))
                .andExpect(jsonPath("$.data[1].allocations[0].amount").value(50000))
                .andExpect(jsonPath("$.data[1].unallocated").value(30000))
                .andExpect(jsonPath("$.data[2].kind").value("SHIPMENT"))
                .andExpect(jsonPath("$.data[2].retailOrderId").value(9102));
    }

    @Test
    void 남의_거래는_안_나온다() throws Exception {
        mvc.perform(get("/api/retail-gateway/settlements?retailerId=" + 남))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].balance").value(70000));
        // 우리와 거래 없는 조합 · 남의 소매처로 우리 원장을 물어도 우리 줄은 안 나온다
        mvc.perform(get("/api/retail-gateway/settlements/ledger?retailerId=" + 남 + "&wholesalerId=" + 무드온))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].retailOrderId").value(9201));
        mvc.perform(get("/api/retail-gateway/settlements/ledger?retailerId=" + 우리 + "&wholesalerId=999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    private long 출고된_주문(long wholesalerId, long partnerId, int orderNumber, long retailOrderId,
                        long amount, OffsetDateTime shippedAt, int statementNumber) {
        long orderId = OutboundFixture.확정주문을_넣는다(jdbc, wholesalerId, partnerId, orderNumber, "AGENT",
                shippedAt.minusDays(1));
        jdbc.update("update wholesale.orders set retail_order_id = ? where id = ?", retailOrderId, orderId);
        long outboundId = OutboundFixture.출고를_넣는다(jdbc, wholesalerId, partnerId, orderNumber);
        OutboundFixture.출고를_확정한다(jdbc, outboundId, statementNumber, shippedAt);
        ledgerWriter.append(partnerId, shippedAt,
                List.of(ReceivableLedgerWriter.Line.outbound(orderId, outboundId, amount)));
        return orderId;
    }

    private long 입금한다(long amount, OffsetDateTime paidAt, String allocations) throws Exception {
        String body = mvc.perform(post("/api/wholesale/payments")
                        .with(TestSecuritySupport.approvedAs(무드온))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "retailerId": %d, "amount": %d, "paidAt": "%s",
                                  "paidBy": "AGENT", "method": "CASH", "allocations": %s }
                                """.formatted(우리, amount, paidAt, allocations)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceFirst("^\\{\"data\":\\{\"id\":(\\d+).*", "$1"));
    }
}
