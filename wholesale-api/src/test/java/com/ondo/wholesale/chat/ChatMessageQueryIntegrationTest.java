package com.ondo.wholesale.chat;

import com.ondo.wholesale.support.ChatFixture;
import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static com.ondo.wholesale.security.support.TestSecuritySupport.approvedAs;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 도매 메시지 조회 통합 검증 (MUL-158) — chatMessages 의 sequence 커서와 줄 모양.
 *
 * <p>after(polling) · before(이전 보기) · 인자 없음(최신) 세 갈래의 범위와 hasMore 뜻, meta, 400 규칙,
 * 그리고 자동 답·넘김 줄이 도매 모양(autoReply 근거, handoff 이유)으로 나오는지를 본다. 방 목록 칸은
 * ChatRoomQueryIntegrationTest 가, 보낸 글이 이 조회에 보이는 것은 ChatSendIntegrationTest 가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChatMessageQueryIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private long 무드온;
    private long 방;

    @BeforeEach
    void 글_다섯_줄_방을_심는다() {
        무드온 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-msg@ondo.test", "9500000160");
        방 = ChatFixture.방을_넣는다(jdbc, 무드온, 1L, "봄봄상회");
        for (int i = 1; i <= 5; i++) {
            ChatFixture.소매_글을_넣는다(jdbc, 방, "문의 " + i);
        }
    }

    @Test
    void 인자가_없으면_최신_size개를_오름차순으로_주고_hasMore는_더_오래된_것이_남았다는_뜻이다() throws Exception {
        조회("?size=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].sequence", contains(4, 5)))
                .andExpect(jsonPath("$.meta.hasMore").value(true))
                .andExpect(jsonPath("$.meta.maxSequence").value(5))
                .andExpect(jsonPath("$.meta.counterpartLastReadSequence").value(nullValue()));
        조회("").andExpect(jsonPath("$.data[*].sequence", contains(1, 2, 3, 4, 5)))
                .andExpect(jsonPath("$.meta.hasMore").value(false));
    }

    @Test
    void afterSequence는_그_다음부터_size개이고_hasMore는_더_새_것이_남았다는_뜻이다() throws Exception {
        조회("?afterSequence=1&size=2").andExpect(jsonPath("$.data[*].sequence", contains(2, 3)))
                .andExpect(jsonPath("$.meta.hasMore").value(true));
        조회("?afterSequence=3&size=2").andExpect(jsonPath("$.data[*].sequence", contains(4, 5)))
                .andExpect(jsonPath("$.meta.hasMore").value(false));
        // polling — 새 것이 없으면 빈 배열
        조회("?afterSequence=5").andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.hasMore").value(false))
                .andExpect(jsonPath("$.meta.maxSequence").value(5));
        조회("?afterSequence=0&size=1").andExpect(jsonPath("$.data[*].sequence", contains(1)));
    }

    @Test
    void beforeSequence는_바로_앞의_size개를_오름차순으로_준다() throws Exception {
        조회("?beforeSequence=5&size=2").andExpect(jsonPath("$.data[*].sequence", contains(3, 4)))
                .andExpect(jsonPath("$.meta.hasMore").value(true));
        조회("?beforeSequence=3&size=2").andExpect(jsonPath("$.data[*].sequence", contains(1, 2)))
                .andExpect(jsonPath("$.meta.hasMore").value(false));
        조회("?beforeSequence=1").andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.hasMore").value(false));
    }

    @Test
    void meta는_상대_읽음_위치를_매번_싣는다() throws Exception {
        ChatFixture.소매가_읽는다(jdbc, 방, 5);

        조회("?afterSequence=5").andExpect(jsonPath("$.meta.counterpartLastReadSequence").value(5));
    }

    @Test
    void 사람_글은_TEXT_모양이고_서버_줄_칸은_null이다() throws Exception {
        조회("?afterSequence=0&size=1")
                .andExpect(jsonPath("$.data[0].roomId").value(방))
                .andExpect(jsonPath("$.data[0].sender").value("RETAILER"))
                .andExpect(jsonPath("$.data[0].kind").value("TEXT"))
                .andExpect(jsonPath("$.data[0].body").value("문의 1"))
                .andExpect(jsonPath("$.data[0].clientMessageId").isString())
                .andExpect(jsonPath("$.data[0].sentAt").isString())
                .andExpect(jsonPath("$.data[0].inquiry").value(nullValue()))
                .andExpect(jsonPath("$.data[0].autoReply").value(nullValue()))
                .andExpect(jsonPath("$.data[0].handoff").value(nullValue()));
    }

    @Test
    void 칩_문맥이_있는_소매_글은_inquiry를_싣는다() throws Exception {
        long seq = ChatFixture.소매_글을_넣는다(jdbc, 방, "레드 / M");
        jdbc.update("""
                update wholesale.chat_message
                   set inquiry_intent = 'STOCK', inquiry_listing_id = 31, inquiry_variant_id = 3101,
                       inquiry_label = '빈티지 셔츠 레드 / M'
                 where room_id = ? and sequence = ?
                """, 방, seq);

        조회("?afterSequence=5")
                .andExpect(jsonPath("$.data[0].inquiry.intent").value("STOCK"))
                .andExpect(jsonPath("$.data[0].inquiry.listingId").value(31))
                .andExpect(jsonPath("$.data[0].inquiry.variantId").value(3101))
                .andExpect(jsonPath("$.data[0].inquiry.backorderId").value(nullValue()))
                .andExpect(jsonPath("$.data[0].inquiry.label").value("빈티지 셔츠 레드 / M"))
                .andExpect(jsonPath("$.data[0].inquiry.notListed").value(false));
    }

    @Test
    void 자동_답과_넘김_줄이_근거와_이유를_달고_도매_방에_보인다() throws Exception {
        ChatFixture.자동_답을_넣는다(jdbc, 방, "레드 / M 재고 있어요", "STOCK", 5,
                ChatRoomQueryIntegrationTest.자동답_근거());
        ChatFixture.넘김_줄을_넣는다(jdbc, 방, "사장님께 전달했어요", "STOCK_ZERO", "STOCK", 5);

        조회("?afterSequence=5")
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].sequence").value(6))
                .andExpect(jsonPath("$.data[0].sender").value("WHOLESALER"))
                .andExpect(jsonPath("$.data[0].kind").value("AUTO_REPLY"))
                .andExpect(jsonPath("$.data[0].clientMessageId").value(nullValue()))
                .andExpect(jsonPath("$.data[0].handoff").value(nullValue()))
                .andExpect(jsonPath("$.data[0].autoReply.intent").value("STOCK"))
                .andExpect(jsonPath("$.data[0].autoReply.replyToSequence").value(5))
                .andExpect(jsonPath("$.data[0].autoReply.generatedBy").value("RULE"))
                .andExpect(jsonPath("$.data[0].autoReply.checkedAt").value("2026-10-28T01:00:00Z"))
                .andExpect(jsonPath("$.data[0].autoReply.subject.variantId").value(3101))
                .andExpect(jsonPath("$.data[0].autoReply.subject.label").value("빈티지 셔츠 레드 / M"))
                // 도매 응답엔 정확 수량이 있다
                .andExpect(jsonPath("$.data[0].autoReply.facts.stock.availableQty").value(7))
                .andExpect(jsonPath("$.data[0].autoReply.facts.stock.band").value("UNDER_10"))
                .andExpect(jsonPath("$.data[0].autoReply.facts.backorder").value(nullValue()))
                .andExpect(jsonPath("$.data[0].autoReply.choices").value(nullValue()))
                .andExpect(jsonPath("$.data[1].sequence").value(7))
                .andExpect(jsonPath("$.data[1].sender").value("SYSTEM"))
                .andExpect(jsonPath("$.data[1].kind").value("HANDOFF"))
                .andExpect(jsonPath("$.data[1].autoReply").value(nullValue()))
                .andExpect(jsonPath("$.data[1].handoff.reason").value("STOCK_ZERO"))
                .andExpect(jsonPath("$.data[1].handoff.intent").value("STOCK"))
                .andExpect(jsonPath("$.data[1].handoff.sourceSequence").value(5));
    }

    @Test
    void 의도를_못_읽은_넘김은_handoff_intent가_null이다() throws Exception {
        ChatFixture.넘김_줄을_넣는다(jdbc, 방, "사장님께 전달했어요", "HUMAN_REQUEST", null, 5);

        조회("?afterSequence=5")
                .andExpect(jsonPath("$.data[0].handoff.reason").value("HUMAN_REQUEST"))
                .andExpect(jsonPath("$.data[0].handoff.intent").value(nullValue()));
    }

    @Test
    void 되묻기_보기는_choices로_나온다() throws Exception {
        ChatFixture.자동_답을_넣는다(jdbc, 방, "어떤 옵션이요?", "STOCK", 5, """
                {"generatedBy": "RULE", "checkedAt": "2026-10-28T01:00:00Z",
                 "subject": {"listingId": 31, "variantId": null, "backorderId": null, "label": null},
                 "facts": {"stock": null, "backorder": null, "receivable": null},
                 "choices": [{"kind": "VARIANT", "label": "레드 / M", "variantId": 3101, "orderable": true,
                              "backorderId": null}]}
                """);
        jdbc.update("update wholesale.chat_message set kind = 'CLARIFY' where room_id = ? and sequence = 6", 방);

        조회("?afterSequence=5")
                .andExpect(jsonPath("$.data[0].kind").value("CLARIFY"))
                .andExpect(jsonPath("$.data[0].autoReply.choices[0].kind").value("VARIANT"))
                .andExpect(jsonPath("$.data[0].autoReply.choices[0].label").value("레드 / M"))
                .andExpect(jsonPath("$.data[0].autoReply.choices[0].variantId").value(3101))
                .andExpect(jsonPath("$.data[0].autoReply.choices[0].orderable").value(true));
    }

    @Test
    void after와_before를_같이_주거나_범위를_벗어나면_VALIDATION_FAILED_400이다() throws Exception {
        조회("?afterSequence=1&beforeSequence=3").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        조회("?afterSequence=-1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("afterSequence"));
        조회("?beforeSequence=0").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("beforeSequence"));
        조회("?size=101").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
        조회("?afterSequence=abc").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 남의_방_메시지는_404_RESOURCE_NOT_FOUND다() throws Exception {
        long 라온 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-msg-raon@ondo.test", "9500000161");

        mvc.perform(get("/api/wholesale/chat/rooms/" + 방 + "/messages").with(approvedAs(라온)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private ResultActions 조회(String query) throws Exception {
        return mvc.perform(get("/api/wholesale/chat/rooms/" + 방 + "/messages" + query).with(approvedAs(무드온)));
    }
}
