package com.ondo.wholesale.chat;

import com.jayway.jsonpath.JsonPath;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.ondo.wholesale.security.support.TestSecuritySupport.approvedAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 도매 채팅방 목록·머리 통합 검증 (MUL-158) — chatRooms · chatRoom.
 *
 * <p>정렬·커서(키셋)·거르기·검색·소유권 404 와 목록 행의 계산 칸(unreadCount, needsReply, openHandoff, 미리보기)을 본다.
 * 메시지 페이지는 ChatMessageQueryIntegrationTest, 보내기·읽음이 방 머리를 바꾸는 것은 ChatSendIntegrationTest ·
 * ChatReadIntegrationTest 가 본다. 소매 글·자동 답·넘김 줄은 쓰는 API 가 없어(MUL-157) ChatFixture 로 심는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChatRoomQueryIntegrationTest extends PostgresTestSupport {

    private static final OffsetDateTime 기준 = OffsetDateTime.parse("2026-10-28T10:00:00+09:00");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private long 무드온;
    private long 라온;

    @BeforeEach
    void 도매처_둘을_심는다() {
        무드온 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-room-moodon@ondo.test", "9500000158");
        라온 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-room-raon@ondo.test", "9500000159");
    }

    @Test
    void 목록은_lastActivityAt_내림차순이고_같으면_roomId_내림차순이다() throws Exception {
        long 오래된방 = ChatFixture.방을_넣는다(jdbc, 무드온, 1L, "봄봄상회", 기준.minusHours(3));
        long 같은시각_앞 = ChatFixture.방을_넣는다(jdbc, 무드온, 2L, "대기상회", 기준.minusHours(1));
        long 같은시각_뒤 = ChatFixture.방을_넣는다(jdbc, 무드온, 3L, "하늘상회", 기준.minusHours(1));
        long 최근방 = ChatFixture.방을_넣는다(jdbc, 무드온, 4L, "바다상회", 기준.minusHours(5));
        ChatFixture.소매_글을_넣는다(jdbc, 최근방, "재고 있어요?", 기준);   // 메시지 시각이 방 생성 시각을 덮는다

        목록("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].roomId").value(최근방))
                .andExpect(jsonPath("$.data[1].roomId").value(같은시각_뒤))
                .andExpect(jsonPath("$.data[2].roomId").value(같은시각_앞))
                .andExpect(jsonPath("$.data[3].roomId").value(오래된방))
                .andExpect(jsonPath("$.meta.nextCursor").value(nullValue()));
    }

    @Test
    void 커서로_끝까지_넘기면_중복도_누락도_없고_마지막_장의_nextCursor는_null이다() throws Exception {
        List<Long> 심은방 = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            // 둘씩 같은 시각 — 장 경계가 같은 시각 안에 떨어지게
            심은방.add(ChatFixture.방을_넣는다(jdbc, 무드온, 10L + i, "소매처" + i, 기준.minusMinutes(i / 2)));
        }

        List<Long> 받은방 = new ArrayList<>();
        String cursor = null;
        int 장수 = 0;
        do {
            String body = 목록("?size=2" + (cursor == null ? "" : "&cursor=" + cursor))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            List<Number> ids = JsonPath.read(body, "$.data[*].roomId");
            ids.forEach(id -> 받은방.add(id.longValue()));
            cursor = JsonPath.read(body, "$.meta.nextCursor");
            장수++;
        } while (cursor != null);

        assertThat(장수).isEqualTo(3);
        assertThat(받은방).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(심은방);
    }

    @Test
    void 장을_넘기는_사이_방이_맨_위로_올라와도_다음_장에_다시_나오지_않는다() throws Exception {
        long 가 = ChatFixture.방을_넣는다(jdbc, 무드온, 1L, "가상회", 기준.minusMinutes(1));
        long 나 = ChatFixture.방을_넣는다(jdbc, 무드온, 2L, "나상회", 기준.minusMinutes(2));
        long 다 = ChatFixture.방을_넣는다(jdbc, 무드온, 3L, "다상회", 기준.minusMinutes(3));

        String first = 목록("?size=1").andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(first, "$.data[0].roomId")).longValue()).isEqualTo(가);
        String cursor = JsonPath.read(first, "$.meta.nextCursor");

        // 첫 장을 받은 뒤 다 방에 새 글 — 정렬상 맨 앞으로 간다. 키셋은 커서 뒤만 보므로 가를 다시 주지 않는다
        ChatFixture.소매_글을_넣는다(jdbc, 다, "지금 문의", 기준.plusMinutes(1));

        목록("?size=5&cursor=" + cursor).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].roomId").value(나));
    }

    @Test
    void 남의_도매처_방은_목록에_나오지_않는다() throws Exception {
        long 내방 = ChatFixture.방을_넣는다(jdbc, 무드온, 1L, "봄봄상회");
        ChatFixture.방을_넣는다(jdbc, 라온, 1L, "봄봄상회");

        목록("").andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].roomId").value(내방));
    }

    @Test
    void 행은_상대_미확인수_마지막_메시지_미리보기_답필요_여부를_싣는다() throws Exception {
        long 방 = ChatFixture.방을_넣는다(jdbc, 무드온, 7L, "봄봄상회");
        ChatFixture.소매_글을_넣는다(jdbc, 방, "첫 글");
        ChatFixture.도매_글을_넣는다(jdbc, 방, "네 안녕하세요");
        ChatFixture.도매가_읽는다(jdbc, 방, 2);
        ChatFixture.소매_글을_넣는다(jdbc, 방, "둘째 줄");
        String 긴글 = "레드 M 재고\n  있나요?" + "가".repeat(100);
        long 마지막 = ChatFixture.소매_글을_넣는다(jdbc, 방, 긴글);

        목록("").andExpect(jsonPath("$.data[0].roomId").value(방))
                .andExpect(jsonPath("$.data[0].counterpart.id").value(7))
                .andExpect(jsonPath("$.data[0].counterpart.name").value("봄봄상회"))
                .andExpect(jsonPath("$.data[0].unreadCount").value(2))
                .andExpect(jsonPath("$.data[0].myLastReadSequence").value(2))
                .andExpect(jsonPath("$.data[0].lastMessage.sequence").value(마지막))
                .andExpect(jsonPath("$.data[0].lastMessage.sender").value("RETAILER"))
                .andExpect(jsonPath("$.data[0].lastMessage.kind").value("TEXT"))
                // 줄바꿈(둘레 공백 포함)은 공백 한 칸, 앞 80 코드 포인트
                .andExpect(jsonPath("$.data[0].lastMessage.preview")
                        .value(("레드 M 재고 있나요?" + "가".repeat(100)).substring(0, 80)))
                .andExpect(jsonPath("$.data[0].needsReply").value(true))
                .andExpect(jsonPath("$.data[0].openHandoff").value(false));
    }

    @Test
    void 넘김_뒤_자동_답이_와도_열린_넘김은_닫히지_않고_답필요다() throws Exception {
        long 방 = ChatFixture.방을_넣는다(jdbc, 무드온, 7L, "봄봄상회");
        long 문의 = ChatFixture.소매_글을_넣는다(jdbc, 방, "사장님이랑 통화하고 싶어요");
        long 넘김 = ChatFixture.넘김_줄을_넣는다(jdbc, 방, "사장님께 전달했어요", "HUMAN_REQUEST", null, 문의);
        long 재고문의 = ChatFixture.소매_글을_넣는다(jdbc, 방, "레드 M 재고 있어요?");
        ChatFixture.자동_답을_넣는다(jdbc, 방, "레드 / M 재고 있어요", "STOCK", 재고문의, 자동답_근거());

        목록("").andExpect(jsonPath("$.data[0].openHandoff").value(true))
                .andExpect(jsonPath("$.data[0].needsReply").value(true))
                .andExpect(jsonPath("$.data[0].lastMessage.kind").value("AUTO_REPLY"))
                .andExpect(jsonPath("$.data[0].lastMessage.sender").value("WHOLESALER"))
                // SYSTEM 넘김 줄·자동 답은 미확인에 안 센다 — 소매 글 둘뿐
                .andExpect(jsonPath("$.data[0].unreadCount").value(2));
        assertThat(넘김).isEqualTo(2);
    }

    @Test
    void 마지막이_도매_글이고_넘김이_없으면_답필요가_아니다() throws Exception {
        long 방 = ChatFixture.방을_넣는다(jdbc, 무드온, 7L, "봄봄상회");
        long 문의 = ChatFixture.소매_글을_넣는다(jdbc, 방, "얼마예요? 깎아주세요");
        ChatFixture.넘김_줄을_넣는다(jdbc, 방, "사장님께 전달했어요", "NEGOTIATION", null, 문의);
        ChatFixture.도매_글을_넣는다(jdbc, 방, "그건 어렵습니다");

        목록("").andExpect(jsonPath("$.data[0].openHandoff").value(false))
                .andExpect(jsonPath("$.data[0].needsReply").value(false));
    }

    @Test
    void UNREAD는_미확인이_있는_방만_NEEDS_REPLY는_답필요_방만_거른다() throws Exception {
        long 안읽음 = ChatFixture.방을_넣는다(jdbc, 무드온, 1L, "가상회", 기준.minusMinutes(1));
        ChatFixture.소매_글을_넣는다(jdbc, 안읽음, "문의", 기준.minusMinutes(1));
        long 읽었지만_답필요 = ChatFixture.방을_넣는다(jdbc, 무드온, 2L, "나상회", 기준.minusMinutes(2));
        long seq = ChatFixture.소매_글을_넣는다(jdbc, 읽었지만_답필요, "문의", 기준.minusMinutes(2));
        ChatFixture.도매가_읽는다(jdbc, 읽었지만_답필요, seq);
        long 답함 = ChatFixture.방을_넣는다(jdbc, 무드온, 3L, "다상회", 기준.minusMinutes(3));
        ChatFixture.소매_글을_넣는다(jdbc, 답함, "문의", 기준.minusMinutes(3));
        ChatFixture.도매가_읽는다(jdbc, 답함, 1);
        ChatFixture.도매_글을_넣는다(jdbc, 답함, "답");
        ChatFixture.방을_넣는다(jdbc, 무드온, 4L, "빈방상회", 기준.minusMinutes(9));

        목록("?filter=UNREAD").andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].roomId").value(안읽음));
        목록("?filter=NEEDS_REPLY").andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].roomId").value(안읽음))
                .andExpect(jsonPath("$.data[1].roomId").value(읽었지만_답필요));
        // 빈 filter 는 없는 것과 같다
        목록("?filter=").andExpect(jsonPath("$.data.length()").value(4));
    }

    @Test
    void q는_앞뒤_공백을_지우고_영문_대소문자를_무시한_부분_일치다() throws Exception {
        long 봄봄 = ChatFixture.방을_넣는다(jdbc, 무드온, 1L, "봄봄 Shop", 기준.minusMinutes(1));
        ChatFixture.방을_넣는다(jdbc, 무드온, 2L, "대기상회", 기준.minusMinutes(2));

        검색("  sHOP ").andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].roomId").value(봄봄));
        검색("봄봄 S").andExpect(jsonPath("$.data.length()").value(1));
        검색("봄봄S").andExpect(jsonPath("$.data.length()").value(0));   // 안쪽 공백은 그대로 비교한다
        검색("   ").andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void 잘못된_size_cursor_filter_q는_VALIDATION_FAILED_400이다() throws Exception {
        목록("?size=0").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("size"));
        목록("?size=101").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
        목록("?cursor=not-a-cursor").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("cursor"));
        목록("?filter=ALL").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("filter"));
        검색(" " + "가".repeat(51) + " ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("q"));
        검색(" " + "가".repeat(50) + " ").andExpect(status().isOk());   // 앞뒤 공백을 지운 뒤 센다
    }

    @Test
    void 메시지_없는_방은_lastMessage가_null이고_lastActivityAt이_생성_시각이다() throws Exception {
        long 방 = ChatFixture.방을_넣는다(jdbc, 무드온, 1L, "봄봄상회", 기준);

        mvc.perform(get("/api/wholesale/chat/rooms/" + 방).with(approvedAs(무드온)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(방))
                .andExpect(jsonPath("$.data.lastMessage").value(nullValue()))
                .andExpect(jsonPath("$.data.lastActivityAt").value("2026-10-28T01:00:00Z"))
                .andExpect(jsonPath("$.data.unreadCount").value(0))
                .andExpect(jsonPath("$.data.myLastReadSequence").value(0))
                .andExpect(jsonPath("$.data.needsReply").value(false))
                .andExpect(jsonPath("$.data.counterpartLastReadSequence").value(nullValue()));
    }

    @Test
    void 방_머리는_목록_칸에_상대_읽음_위치를_더한다() throws Exception {
        long 방 = ChatFixture.방을_넣는다(jdbc, 무드온, 1L, "봄봄상회");
        ChatFixture.소매_글을_넣는다(jdbc, 방, "문의");
        ChatFixture.도매_글을_넣는다(jdbc, 방, "답");
        ChatFixture.소매가_읽는다(jdbc, 방, 2);

        mvc.perform(get("/api/wholesale/chat/rooms/" + 방).with(approvedAs(무드온)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counterpartLastReadSequence").value(2))
                .andExpect(jsonPath("$.data.unreadCount").value(1))
                .andExpect(jsonPath("$.data.lastMessage.sender").value("WHOLESALER"));
    }

    @Test
    void 남의_방과_없는_방은_둘_다_404_RESOURCE_NOT_FOUND다() throws Exception {
        long 라온방 = ChatFixture.방을_넣는다(jdbc, 라온, 1L, "봄봄상회");

        mvc.perform(get("/api/wholesale/chat/rooms/" + 라온방).with(approvedAs(무드온)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/wholesale/chat/rooms/" + (라온방 + 1000)).with(approvedAs(무드온)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private ResultActions 목록(String query) throws Exception {
        return mvc.perform(get("/api/wholesale/chat/rooms" + query).with(approvedAs(무드온)));
    }

    private ResultActions 검색(String q) throws Exception {
        return mvc.perform(get("/api/wholesale/chat/rooms").param("q", q).with(approvedAs(무드온)));
    }

    static String 자동답_근거() {
        return """
                {"generatedBy": "RULE", "checkedAt": "2026-10-28T01:00:00Z",
                 "subject": {"listingId": 31, "variantId": 3101, "backorderId": null, "label": "빈티지 셔츠 레드 / M"},
                 "facts": {"stock": {"availableQty": 7, "band": "UNDER_10", "orderLimit": null},
                           "backorder": null, "receivable": null},
                 "choices": null}
                """;
    }
}
