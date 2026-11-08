package com.ondo.wholesale.retailgateway;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static com.ondo.wholesale.security.support.TestSecuritySupport.approvedAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 소매 채팅 게이트웨이 통합 검증 (MUL-157) — 방 열기 · 보내기 + 자동 답 · 조회 · 읽음 · 배지.
 *
 * <p>판정 규칙 자체(어떤 문장에 어떤 답)는 InquiryGoldenScenarioTest 가 골든셋 167 단계로 본다. 여기는 그 판정이
 * 실제 DB 원본(재고·미송·미수)을 읽어 줄로 붙고, <b>소매 모양</b>으로 나가는지 본다:
 * 정확 수량(availableQty)과 넘김 이유(handoff)가 응답에 칸째 없는지, 멱등 replay 가 자동 답을 다시 만들지 않는지,
 * 남의 방이 404 인지, 읽음·미확인이 양쪽에서 맞는지.
 *
 * <p>도매 쪽 5 op 의 모양은 chat 패키지 테스트(MUL-158)가 본다. 소매 프론트 400·커서 문자열은 소매 테스트 몫이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RetailGatewayChatIntegrationTest extends PostgresTestSupport {

    private static final String 방들 = "/api/retail-gateway/chat/rooms";
    private static final long 봄봄 = 8301L;
    private static final long 남 = 8302L;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private long 무드온;
    private long 셔츠;
    private long 블랙M;
    private long 블랙L;

    @BeforeEach
    void 도매처와_게시글을_심는다() {
        무드온 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-retail@ondo.test", "9500000157");
        jdbc.update("update wholesale.wholesaler set approval_status = 'APPROVED', biz_name = '무드온' where id = ?",
                무드온);
        long 리프 = MasterDataFixture.카테고리_리프를_넣는다(jdbc, 9570);
        long 블랙 = MasterDataFixture.색상을_넣는다(jdbc, 9570, 9570);
        long product = jdbc.queryForObject("""
                insert into wholesale.product (wholesaler_id, product_number, name, category_id)
                values (?, 1, '린넨 셔츠', ?) returning id
                """, Long.class, 무드온, 리프);
        long colorOption = jdbc.queryForObject(
                "insert into wholesale.color_option (product_id, color_id) values (?, ?) returning id",
                Long.class, product, 블랙);
        블랙M = 변형(colorOption, product, "M", 1, 45, 5);   // 판매가능 40 → 10~49장
        블랙L = 변형(colorOption, product, "L", 2, 0, 0);    // 판매가능 0 → 재고 없음 + 넘김
        셔츠 = jdbc.queryForObject("""
                insert into wholesale.listing (product_id, title) values (?, '린넨 셔츠') returning id
                """, Long.class, product);
        jdbc.update("insert into wholesale.listing_variant (listing_id, variant_id, sale_price, order_limit)"
                + " values (?, ?, 10000, 500), (?, ?, 10000, 0)", 셔츠, 블랙M, 셔츠, 블랙L);
    }

    @Test
    void 방_열기는_쌍에_하나고_두번째는_created_false이며_상품_문맥을_바꾼다() throws Exception {
        long roomId = 방을_연다(봄봄, 셔츠, true);

        열기(봄봄, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(false))
                .andExpect(jsonPath("$.data.room.roomId").value(roomId))
                .andExpect(jsonPath("$.data.room.counterpart.id").value(무드온))
                .andExpect(jsonPath("$.data.room.counterpart.name").value("무드온"))
                .andExpect(jsonPath("$.data.room.myLastReadSequence").value(0))
                .andExpect(jsonPath("$.data.room.lastMessage").value(nullValue()));

        assertThat(jdbc.queryForObject("select count(*) from wholesale.chat_room where wholesaler_id = ?",
                Integer.class, 무드온)).isEqualTo(1);
        // listingId null 은 문맥을 지운다(이미 있는 방도)
        assertThat(jdbc.queryForObject("select context_listing_id from wholesale.chat_room where id = ?",
                Long.class, roomId)).isNull();
        assertThat(jdbc.queryForObject("select retailer_name from wholesale.chat_room where id = ?",
                String.class, roomId)).isEqualTo("봄봄상회");
    }

    @Test
    void 승인_전_도매처와는_방을_열_수_없어_404_RESOURCE_NOT_FOUND다() throws Exception {
        jdbc.update("update wholesale.wholesaler set approval_status = 'PENDING' where id = ?", 무드온);

        열기(봄봄, 셔츠)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("재고 질문 → 자동 답이 generatedMessages 로 오고, 구간·주문 가능만 있고 정확 수량 칸은 없다")
    void 재고_자동_답은_구간만_싣는다() throws Exception {
        long roomId = 방을_연다(봄봄, 셔츠, true);

        String raw = 보낸다(봄봄, roomId, UUID.randomUUID().toString(), "블랙 M 몇 장 남았어요?")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.message.sender").value("RETAILER"))
                .andExpect(jsonPath("$.data.message.sequence").value(1))
                .andExpect(jsonPath("$.data.generation").value("DONE"))
                .andExpect(jsonPath("$.data.generatedMessages.length()").value(1))
                .andExpect(jsonPath("$.data.generatedMessages[0].sequence").value(2))
                .andExpect(jsonPath("$.data.generatedMessages[0].sender").value("WHOLESALER"))
                .andExpect(jsonPath("$.data.generatedMessages[0].kind").value("AUTO_REPLY"))
                .andExpect(jsonPath("$.data.generatedMessages[0].body")
                        .value("블랙 M은 10~49장 있어요. 지금 주문할 수 있어요(한 번에 최대 500장)"))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.intent").value("STOCK"))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.replyToSequence").value(1))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.subject.variantId").value(블랙M))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.facts.stock.band").value("UNDER_50"))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.facts.stock.orderLimit").value(500))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.facts.stock.orderable").value(true))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.facts.stock.availableQty").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // 도매 DB 에는 정확 수량이 남는다(도매 화면 근거). 소매 응답 어디에도 그 칸이 없다
        assertThat(raw).doesNotContain("availableQty");
        assertThat(jdbc.queryForObject("select auto_reply ->'facts'->'stock'->>'availableQty' from wholesale.chat_message"
                + " where room_id = ? and sequence = 2", Integer.class, roomId)).isEqualTo(40);
    }

    @Test
    @DisplayName("재고 0 → 답 + 넘김 두 줄. 넘김 이유는 도매에만 남고 소매 응답에는 handoff 칸이 없다")
    void 재고_0은_답과_넘김이고_이유는_소매에_없다() throws Exception {
        long roomId = 방을_연다(봄봄, 셔츠, true);

        String raw = 보낸다(봄봄, roomId, UUID.randomUUID().toString(), "블랙 L 재고 있나요?")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.generatedMessages.length()").value(2))
                .andExpect(jsonPath("$.data.generatedMessages[0].body").value("블랙 L은 지금은 재고가 없어요"))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.facts.stock.band").value("NONE"))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.facts.stock.orderLimit").value(nullValue()))
                .andExpect(jsonPath("$.data.generatedMessages[1].sender").value("SYSTEM"))
                .andExpect(jsonPath("$.data.generatedMessages[1].kind").value("HANDOFF"))
                .andExpect(jsonPath("$.data.generatedMessages[1].handoff").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(raw).doesNotContain("STOCK_ZERO").doesNotContain("handoff");
        assertThat(jdbc.queryForObject("select handoff_reason from wholesale.chat_message where room_id = ? and sequence = 3",
                String.class, roomId)).isEqualTo("STOCK_ZERO");
        // 도매 화면에는 이유가 보인다 — 같은 테이블이다
        mvc.perform(get("/api/wholesale/chat/rooms/" + roomId + "/messages").with(approvedAs(무드온)))
                .andExpect(jsonPath("$.data[2].handoff.reason").value("STOCK_ZERO"))
                .andExpect(jsonPath("$.data[1].autoReply.facts.stock.availableQty").value(0));
    }

    @Test
    void 같은_키_같은_본문은_200_replay이고_자동_답을_다시_만들지_않는다() throws Exception {
        long roomId = 방을_연다(봄봄, 셔츠, true);
        String key = UUID.randomUUID().toString();
        JsonNode first = JSON.readTree(보낸다(봄봄, roomId, key, "블랙 M 몇 장 남았어요?")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        JsonNode again = JSON.readTree(보낸다(봄봄, roomId, key, "블랙 M 몇 장 남았어요?")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(again).isEqualTo(first);
        assertThat(jdbc.queryForObject("select last_sequence from wholesale.chat_room where id = ?", Long.class, roomId))
                .isEqualTo(2L);
    }

    @Test
    void 같은_키_다른_본문은_409_IDEMPOTENCY_KEY_REUSED다() throws Exception {
        long roomId = 방을_연다(봄봄, 셔츠, true);
        String key = UUID.randomUUID().toString();
        보낸다(봄봄, roomId, key, "블랙 M 몇 장 남았어요?").andExpect(status().isCreated());

        보낸다(봄봄, roomId, key, "블랙 L 몇 장 남았어요?")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 키가_없거나_본문이_비면_400_VALIDATION_FAILED다() throws Exception {
        long roomId = 방을_연다(봄봄, 셔츠, true);

        mvc.perform(post(방들 + "/" + roomId + "/messages").param("retailerId", String.valueOf(봄봄))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"안녕\",\"inquiry\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
        보낸다(봄봄, roomId, UUID.randomUUID().toString(), "   ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("body"));
    }

    @Test
    @DisplayName("남의 방은 머리·메시지·보내기·읽음 전부 404 — 존재를 숨긴다")
    void 남의_방은_404_RESOURCE_NOT_FOUND다() throws Exception {
        long roomId = 방을_연다(봄봄, 셔츠, true);
        String 남이 = String.valueOf(남);

        mvc.perform(get(방들 + "/" + roomId).param("retailerId", 남이))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get(방들 + "/" + roomId + "/messages").param("retailerId", 남이))
                .andExpect(status().isNotFound());
        보낸다(남, roomId, UUID.randomUUID().toString(), "블랙 M 몇 장 남았어요?")
                .andExpect(status().isNotFound());
        mvc.perform(post(방들 + "/" + roomId + "/read").param("retailerId", 남이)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lastReadSequence\":0}"))
                .andExpect(status().isNotFound());
        mvc.perform(get(방들).param("retailerId", 남이))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("메시지 조회도 소매 모양 — 넘김 줄은 kind 만, 재고 facts 에 정확 수량이 없다")
    void 메시지_조회는_소매_모양이다() throws Exception {
        long roomId = 방을_연다(봄봄, 셔츠, true);
        보낸다(봄봄, roomId, UUID.randomUUID().toString(), "블랙 L 재고 있나요?");

        String raw = mvc.perform(get(방들 + "/" + roomId + "/messages").param("retailerId", String.valueOf(봄봄))
                        .param("afterSequence", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[2].kind").value("HANDOFF"))
                .andExpect(jsonPath("$.meta.hasMore").value(false))
                .andExpect(jsonPath("$.meta.maxSequence").value(3))
                .andExpect(jsonPath("$.meta.counterpartLastReadSequence").value(0))
                .andReturn().getResponse().getContentAsString();

        assertThat(raw).doesNotContain("availableQty").doesNotContain("handoff").doesNotContain("STOCK_ZERO");
    }

    @Test
    @DisplayName("읽음·미확인: 소매는 도매 쪽 줄(자동 답 포함, SYSTEM 제외)을, 도매는 소매 글을 센다")
    void 읽음과_미확인이_양쪽에서_맞다() throws Exception {
        long roomId = 방을_연다(봄봄, 셔츠, true);
        보낸다(봄봄, roomId, UUID.randomUUID().toString(), "블랙 L 재고 있나요?");   // 1 소매 · 2 자동 답 · 3 넘김

        // 내 글(1)까지는 읽었다. 자동 답(2)이 미확인 1, 넘김(SYSTEM)은 세지 않는다
        mvc.perform(get("/api/retail-gateway/chat/unread-count").param("retailerId", String.valueOf(봄봄)))
                .andExpect(jsonPath("$.data.chats").value(1));
        mvc.perform(get(방들 + "/" + roomId).param("retailerId", String.valueOf(봄봄)))
                .andExpect(jsonPath("$.data.unreadCount").value(1))
                .andExpect(jsonPath("$.data.myLastReadSequence").value(1))
                .andExpect(jsonPath("$.data.openHandoff").value(true))
                .andExpect(jsonPath("$.data.needsReply").value(true));
        mvc.perform(get("/api/wholesale/chat/rooms/" + roomId).with(approvedAs(무드온)))
                .andExpect(jsonPath("$.data.unreadCount").value(1))
                .andExpect(jsonPath("$.data.counterpartLastReadSequence").value(1));

        mvc.perform(post(방들 + "/" + roomId + "/read").param("retailerId", String.valueOf(봄봄))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lastReadSequence\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastReadSequence").value(3))
                .andExpect(jsonPath("$.data.unreadCount").value(0));
        // 늦게 온 작은 값은 되돌리지 않는다
        mvc.perform(post(방들 + "/" + roomId + "/read").param("retailerId", String.valueOf(봄봄))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lastReadSequence\":1}"))
                .andExpect(jsonPath("$.data.lastReadSequence").value(3));
        mvc.perform(post(방들 + "/" + roomId + "/read").param("retailerId", String.valueOf(봄봄))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lastReadSequence\":4}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lastReadSequence"));
        mvc.perform(get("/api/retail-gateway/chat/unread-count").param("retailerId", String.valueOf(봄봄)))
                .andExpect(jsonPath("$.data.chats").value(0));
    }

    @Test
    void 열린_넘김과_같은_이유면_넘김_줄을_다시_붙이지_않아_generation_NONE이다() throws Exception {
        long roomId = 방을_연다(봄봄, 셔츠, true);
        보낸다(봄봄, roomId, UUID.randomUUID().toString(), "좀 깎아 주세요")
                .andExpect(jsonPath("$.data.generatedMessages[0].kind").value("HANDOFF"))
                .andExpect(jsonPath("$.data.generation").value("DONE"));

        보낸다(봄봄, roomId, UUID.randomUUID().toString(), "할인 안 돼요?")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.generatedMessages.length()").value(0))
                .andExpect(jsonPath("$.data.generation").value("NONE"));
    }

    @Test
    void 미수_질문은_이_쌍의_원장으로_금액을_답한다() throws Exception {
        long partner = OrderFixture.거래처를_넣는다(jdbc, 무드온, 봄봄, "봄봄상회");
        long order = OrderFixture.주문을_넣는다(jdbc, 무드온, partner, 1, "CONFIRMED", OffsetDateTime.now());
        OrderFixture.원장_출고를_넣는다(jdbc, partner, order, 37_500);
        jdbc.update("update wholesale.partner set receivable_balance = 37500 where id = ?", partner);
        long roomId = 방을_연다(봄봄, null, true);

        보낸다(봄봄, roomId, UUID.randomUUID().toString(), "미수 얼마예요?")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.generatedMessages.length()").value(1))
                .andExpect(jsonPath("$.data.generatedMessages[0].body").value("갚을 돈은 37,500원이에요. 기한 지난 것은 없어요"))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.intent").value("RECEIVABLE"))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.facts.receivable.outstandingAmount")
                        .value(37500))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.facts.receivable.overdue.count").value(0));
    }

    @Test
    @DisplayName("미송 질문 → 내 미송만 칩으로 되묻고, 칩 글자의 주문 번호는 소매 주문서 번호다")
    void 미송_질문은_내_미송으로_되묻는다() throws Exception {
        long partner = OrderFixture.거래처를_넣는다(jdbc, 무드온, 봄봄, "봄봄상회");
        OffsetDateTime 주문시각 = OffsetDateTime.now(ZoneId.of("Asia/Seoul")).minusDays(3);
        long order = OrderFixture.주문을_넣는다(jdbc, 무드온, partner, 1, "CONFIRMED", 주문시각);
        jdbc.update("update wholesale.orders set retail_order_id = 77001 where id = ?", order);
        long line = OrderFixture.라인을_넣는다(jdbc, order, 블랙M, 5, 10000, 2, 0);
        long backorder = OrderFixture.미송을_넣는다(jdbc, line, 3, "OPEN");
        long 남의거래처 = OrderFixture.거래처를_넣는다(jdbc, 무드온, 남, "남의상회");
        long 남의주문 = OrderFixture.주문을_넣는다(jdbc, 무드온, 남의거래처, 2, "CONFIRMED", OffsetDateTime.now());
        jdbc.update("update wholesale.orders set retail_order_id = 77002 where id = ?", 남의주문);
        OrderFixture.미송을_넣는다(jdbc, OrderFixture.라인을_넣는다(jdbc, 남의주문, 블랙L, 4, 10000, 0, 0), 4, "OPEN");
        long roomId = 방을_연다(봄봄, null, true);

        보낸다(봄봄, roomId, UUID.randomUUID().toString(), "미송 언제 와요?")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.generatedMessages[0].kind").value("CLARIFY"))
                .andExpect(jsonPath("$.data.generatedMessages[0].body").value("어느 주문이요?"))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.choices.length()").value(2))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.choices[0].kind").value("BACKORDER"))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.choices[0].backorderId").value(backorder))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.choices[0].label")
                        .value("린넨 셔츠 블랙/M, 남은 3장, %d/%d 주문 #77001"
                                .formatted(주문시각.getMonthValue(), 주문시각.getDayOfMonth())))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.choices[1].kind").value("NOT_LISTED"));
    }

    @Test
    void 방_목록은_최근_활동순이고_keyset으로_이어진다() throws Exception {
        long 첫방 = 방을_연다(봄봄, null, true);
        long 라온 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-retail-2@ondo.test", "9500000158");
        jdbc.update("update wholesale.wholesaler set approval_status = 'APPROVED' where id = ?", 라온);
        long 둘째방 = JSON.readTree(mvc.perform(post(방들).contentType(MediaType.APPLICATION_JSON).content(
                        "{\"retailerId\":%d,\"retailerName\":\"봄봄상회\",\"wholesalerId\":%d,\"listingId\":null}"
                                .formatted(봄봄, 라온)))
                .andReturn().getResponse().getContentAsString()).get("data").get("room").get("roomId").asLong();
        보낸다(봄봄, 첫방, UUID.randomUUID().toString(), "안녕하세요");   // 첫방이 위로 올라간다

        String raw = mvc.perform(get(방들).param("retailerId", String.valueOf(봄봄)).param("limit", "1"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].roomId").value(첫방))
                .andReturn().getResponse().getContentAsString();
        String at = JSON.readTree(raw).get("data").get(0).get("lastActivityAt").asString();
        long micros = OffsetDateTime.parse(at).toInstant().getEpochSecond() * 1_000_000L
                + OffsetDateTime.parse(at).toInstant().getNano() / 1_000L;

        mvc.perform(get(방들).param("retailerId", String.valueOf(봄봄)).param("limit", "5")
                        .param("beforeMicros", String.valueOf(micros)).param("beforeId", String.valueOf(첫방)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].roomId").value(둘째방));
        mvc.perform(get(방들).param("retailerId", String.valueOf(봄봄)).param("beforeId", "1"))
                .andExpect(status().isBadRequest());
    }

    /* --- 도우미 ---------------------------------------------------------------- */

    private long 변형(long colorOption, long product, String size, int seq, int stock, int reserved) {
        return jdbc.queryForObject("""
                insert into wholesale.variant (color_option_id, product_id, size, variant_seq, stock_qty, reserved_qty)
                values (?, ?, ?, ?, ?, ?) returning id
                """, Long.class, colorOption, product, size, seq, stock, reserved);
    }

    private ResultActions 열기(long retailerId, Long listingId) throws Exception {
        return mvc.perform(post(방들).contentType(MediaType.APPLICATION_JSON).content(
                "{\"retailerId\":%d,\"retailerName\":\"봄봄상회\",\"wholesalerId\":%d,\"listingId\":%s}"
                        .formatted(retailerId, 무드온, listingId == null ? "null" : listingId.toString())));
    }

    private long 방을_연다(long retailerId, Long listingId, boolean expectCreated) throws Exception {
        String raw = 열기(retailerId, listingId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(expectCreated))
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(raw).get("data").get("room").get("roomId").asLong();
    }

    private ResultActions 보낸다(long retailerId, long roomId, String key, String body) throws Exception {
        return mvc.perform(post(방들 + "/" + roomId + "/messages").param("retailerId", String.valueOf(retailerId))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"%s\",\"inquiry\":null}".formatted(body)));
    }
}
