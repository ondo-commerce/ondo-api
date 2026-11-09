package com.ondo.wholesale.chat;

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
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static com.ondo.wholesale.security.support.TestSecuritySupport.approvedAs;
import static com.ondo.wholesale.security.support.TestSecuritySupport.pending;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 자동 답 설정 GET·PUT 과 그 설정이 다음 소매 보내기의 판정에 걸리는지 (MUL-159).
 *
 * <p>설정별 판정 규칙(꺼짐·의도별 꺼짐이 어떤 문장에서 어떤 넘김이 되는지)은 InquiryGoldenScenarioTest 가 골든셋
 * (H15, U08, O01~O07, K15, B14, R15)으로 본다. 여기는 V19 행이 실제로 저장되고, 소매 게이트웨이 보내기 트랜잭션이
 * 그 행을 읽어 판정에 넣는지 본다 — 잡고 싶은 사고: 설정을 꺼도 기본값(전부 켜짐) 구현이 그대로 남아 자동 답이 나간다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChatAutoReplySettingsIntegrationTest extends PostgresTestSupport {

    private static final String 설정 = "/api/wholesale/chat/auto-reply-settings";
    private static final String 방들 = "/api/retail-gateway/chat/rooms";
    private static final long 봄봄 = 8501L;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private long 무드온;
    private long 라온;
    private long 셔츠;

    @BeforeEach
    void 도매처와_게시글을_심는다() {
        무드온 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-settings@ondo.test", "9500000171");
        라온 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-settings-2@ondo.test", "9500000172");
        jdbc.update("update wholesale.wholesaler set approval_status = 'APPROVED', biz_name = '무드온' where id = ?",
                무드온);
        long 리프 = MasterDataFixture.카테고리_리프를_넣는다(jdbc, 9590);
        long 블랙 = MasterDataFixture.색상을_넣는다(jdbc, 9590, 9590);
        long product = jdbc.queryForObject("""
                insert into wholesale.product (wholesaler_id, product_number, name, category_id)
                values (?, 1, '린넨 셔츠', ?) returning id
                """, Long.class, 무드온, 리프);
        long colorOption = jdbc.queryForObject(
                "insert into wholesale.color_option (product_id, color_id) values (?, ?) returning id",
                Long.class, product, 블랙);
        long 블랙M = jdbc.queryForObject("""
                insert into wholesale.variant (color_option_id, product_id, size, variant_seq, stock_qty, reserved_qty)
                values (?, ?, 'M', 1, 45, 5) returning id
                """, Long.class, colorOption, product);
        셔츠 = jdbc.queryForObject("insert into wholesale.listing (product_id, title) values (?, '린넨 셔츠') returning id",
                Long.class, product);
        jdbc.update("insert into wholesale.listing_variant (listing_id, variant_id, sale_price, order_limit)"
                + " values (?, ?, 10000, 500)", 셔츠, 블랙M);
        // 미수 답이 나오게 이 쌍의 원장을 깐다
        long partner = OrderFixture.거래처를_넣는다(jdbc, 무드온, 봄봄, "봄봄상회");
        long order = OrderFixture.주문을_넣는다(jdbc, 무드온, partner, 1, "CONFIRMED", OffsetDateTime.now());
        OrderFixture.원장_출고를_넣는다(jdbc, partner, order, 37_500);
        jdbc.update("update wholesale.partner set receivable_balance = 37500 where id = ?", partner);
    }

    // ── GET ──────────────────────────────────────────────

    @Test
    void 저장한_적이_없으면_전부_true다() throws Exception {
        mvc.perform(get(설정).with(approvedAs(무드온)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.intents.STOCK").value(true))
                .andExpect(jsonPath("$.data.intents.BACKORDER").value(true))
                .andExpect(jsonPath("$.data.intents.RECEIVABLE").value(true))
                .andExpect(jsonPath("$.data.intents.OTHER").doesNotExist());
    }

    @Test
    void 승인_전_계정은_403_NOT_APPROVED다() throws Exception {
        mvc.perform(get(설정).with(pending()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_APPROVED"));
    }

    @Test
    void 로그인_안_하면_401_UNAUTHENTICATED다() throws Exception {
        mvc.perform(get(설정))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ── PUT ──────────────────────────────────────────────

    @Test
    void 통째로_저장하고_저장된_값을_돌려주며_다른_도매처는_그대로다() throws Exception {
        바꾼다(무드온, true, false, true, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.intents.STOCK").value(false))
                .andExpect(jsonPath("$.data.intents.BACKORDER").value(true))
                .andExpect(jsonPath("$.data.intents.RECEIVABLE").value(false));
        바꾼다(무드온, false, false, true, false).andExpect(status().isOk());

        mvc.perform(get(설정).with(approvedAs(무드온)))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.intents.STOCK").value(false))
                .andExpect(jsonPath("$.data.intents.BACKORDER").value(true));
        assertThat(jdbc.queryForObject("select count(*) from wholesale.chat_auto_reply_setting where wholesaler_id = ?",
                Integer.class, 무드온)).isEqualTo(1);
        mvc.perform(get(설정).with(approvedAs(라온)))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.intents.STOCK").value(true));
    }

    @Test
    void 칸이_빠지면_400_VALIDATION_FAILED이고_빠진_칸을_전부_싣는다() throws Exception {
        mvc.perform(put(설정).with(approvedAs(무드온)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intents\":{\"STOCK\":true}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(containsInAnyOrder("enabled", "intents.BACKORDER", "intents.RECEIVABLE")));

        assertThat(jdbc.queryForObject("select count(*) from wholesale.chat_auto_reply_setting where wholesaler_id = ?",
                Integer.class, 무드온)).isZero();
    }

    @Test
    void intents가_없거나_객체가_아니면_400_field_intents다() throws Exception {
        mvc.perform(put(설정).with(approvedAs(무드온)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("intents"));
        mvc.perform(put(설정).with(approvedAs(무드온)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"intents\":[true,true,true]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("intents"));
    }

    @Test
    @DisplayName("불린이 아닌 값(\"true\" 문자열, 1, null)은 불린으로 바꿔 받지 않고 400 이다")
    void 불린이_아니면_400_VALIDATION_FAILED다() throws Exception {
        mvc.perform(put(설정).with(approvedAs(무드온)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":\"true\",\"intents\":{\"STOCK\":1,\"BACKORDER\":null,\"RECEIVABLE\":false}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(containsInAnyOrder("enabled", "intents.STOCK", "intents.BACKORDER")));
    }

    @Test
    void PUT도_승인_전_계정은_403_NOT_APPROVED다() throws Exception {
        mvc.perform(put(설정).with(pending()).contentType(MediaType.APPLICATION_JSON)
                        .content(본문(true, true, true, true)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_APPROVED"));
    }

    // ── 판정에 걸리는지 (소매 게이트웨이 보내기) ─────────────────────

    @Test
    @DisplayName("전체를 끄면 판정 없이 사장 넘김 한 줄(OFF)이고, intent 는 켜져 있었다면 나갔을 의도다")
    void 전체를_끄면_자동_답_없이_OFF_넘김이다() throws Exception {
        long roomId = 방을_연다();
        보낸다(roomId, "블랙 M 몇 장 남았어요?")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.generatedMessages[0].kind").value("AUTO_REPLY"));

        바꾼다(무드온, false, true, true, true).andExpect(status().isOk());

        보낸다(roomId, "블랙 M 몇 장 남았어요?")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.generation").value("DONE"))
                .andExpect(jsonPath("$.data.generatedMessages.length()").value(1))
                .andExpect(jsonPath("$.data.generatedMessages[0].sender").value("SYSTEM"))
                .andExpect(jsonPath("$.data.generatedMessages[0].kind").value("HANDOFF"));

        assertThat(jdbc.queryForMap("select kind, handoff_reason, intent, source_sequence from wholesale.chat_message"
                + " where room_id = ? and sequence = 4", roomId))
                .containsEntry("kind", "HANDOFF")
                .containsEntry("handoff_reason", "OFF")
                .containsEntry("intent", "STOCK")
                .containsEntry("source_sequence", 3L);
        // 이미 보낸 자동 답은 그대로다
        assertThat(jdbc.queryForObject("select kind from wholesale.chat_message where room_id = ? and sequence = 2",
                String.class, roomId)).isEqualTo("AUTO_REPLY");
        // 사장 화면에서도 답이 필요한 방이다
        mvc.perform(get("/api/wholesale/chat/rooms/" + roomId).with(approvedAs(무드온)))
                .andExpect(jsonPath("$.data.needsReply").value(true))
                .andExpect(jsonPath("$.data.openHandoff").value(true));
    }

    @Test
    @DisplayName("의도 하나만 끄면 그 의도만 OFF 넘김이고 다른 의도는 계속 자동 답이다")
    void 의도별로_끄면_그_의도만_넘긴다() throws Exception {
        바꾼다(무드온, true, false, true, true).andExpect(status().isOk());
        long roomId = 방을_연다();

        보낸다(roomId, "블랙 M 몇 장 남았어요?")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.generatedMessages.length()").value(1))
                .andExpect(jsonPath("$.data.generatedMessages[0].kind").value("HANDOFF"));
        assertThat(jdbc.queryForMap("select handoff_reason, intent from wholesale.chat_message"
                + " where room_id = ? and sequence = 2", roomId))
                .containsEntry("handoff_reason", "OFF")
                .containsEntry("intent", "STOCK");

        보낸다(roomId, "미수 얼마예요?")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.generatedMessages.length()").value(1))
                .andExpect(jsonPath("$.data.generatedMessages[0].kind").value("AUTO_REPLY"))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.intent").value("RECEIVABLE"));
    }

    @Test
    void 미수만_끄면_미수_질문은_OFF_넘김이고_재고는_자동_답이다() throws Exception {
        바꾼다(무드온, true, true, true, false).andExpect(status().isOk());
        long roomId = 방을_연다();

        보낸다(roomId, "미수 얼마예요?")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.generatedMessages[0].kind").value("HANDOFF"));
        assertThat(jdbc.queryForMap("select handoff_reason, intent from wholesale.chat_message"
                + " where room_id = ? and sequence = 2", roomId))
                .containsEntry("handoff_reason", "OFF")
                .containsEntry("intent", "RECEIVABLE");

        보낸다(roomId, "블랙 M 몇 장 남았어요?")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.generatedMessages[0].kind").value("AUTO_REPLY"));
    }

    @Test
    void 다른_도매처_설정은_내_방_판정에_걸리지_않는다() throws Exception {
        바꾼다(라온, false, false, false, false).andExpect(status().isOk());
        long roomId = 방을_연다();

        보낸다(roomId, "블랙 M 몇 장 남았어요?")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.generatedMessages[0].kind").value("AUTO_REPLY"));
    }

    /* --- 도우미 ---------------------------------------------------------------- */

    private static String 본문(boolean enabled, boolean stock, boolean backorder, boolean receivable) {
        return JSON.writeValueAsString(Map.of("enabled", enabled,
                "intents", Map.of("STOCK", stock, "BACKORDER", backorder, "RECEIVABLE", receivable)));
    }

    private ResultActions 바꾼다(long wholesalerId, boolean enabled, boolean stock, boolean backorder,
                              boolean receivable) throws Exception {
        return mvc.perform(put(설정).with(approvedAs(wholesalerId)).contentType(MediaType.APPLICATION_JSON)
                .content(본문(enabled, stock, backorder, receivable)));
    }

    private long 방을_연다() throws Exception {
        String raw = mvc.perform(post(방들).contentType(MediaType.APPLICATION_JSON).content(
                        "{\"retailerId\":%d,\"retailerName\":\"봄봄상회\",\"wholesalerId\":%d,\"listingId\":%d}"
                                .formatted(봄봄, 무드온, 셔츠)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(raw).get("data").get("room").get("roomId").asLong();
    }

    private ResultActions 보낸다(long roomId, String body) throws Exception {
        return mvc.perform(post(방들 + "/" + roomId + "/messages").param("retailerId", String.valueOf(봄봄))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"%s\",\"inquiry\":null}".formatted(body)));
    }
}
