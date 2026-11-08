package com.ondo.wholesale.chat;

import com.ondo.wholesale.support.ChatFixture;
import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static com.ondo.wholesale.security.support.TestSecuritySupport.approvedAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 알림 배지 {@code chatsNeedingReply} = 방 목록 needsReply 방 수 (MUL-159).
 *
 * <p>잡고 싶은 사고: 배지와 목록이 다른 정의로 세서 "답할 방 3" 배지를 눌렀는데 NEEDS_REPLY 목록에 2개가 보인다.
 * 그래서 숫자를 손으로 적는 것과 함께 <b>같은 데이터의 목록(filter=NEEDS_REPLY)과 행 수가 같은지</b> 본다.
 * needsReply 규칙 자체(열린 넘김, 마지막 RETAILER TEXT)는 ChatRoomQueryIntegrationTest 가 본다. 알림 수는
 * NotificationIntegrationTest 가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChatsNeedingReplyIntegrationTest extends PostgresTestSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 답이_필요한_방_수는_방_목록_NEEDS_REPLY와_같다() throws Exception {
        long 무드온 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-badge@ondo.test", "9500000191");
        long 라온 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-badge-2@ondo.test", "9500000192");

        long 소매_글_마지막 = ChatFixture.방을_넣는다(jdbc, 무드온, 1L, "봄봄상회");
        ChatFixture.소매_글을_넣는다(jdbc, 소매_글_마지막, "있어요?");

        // 넘김이 열린 채 자동 답이 마지막 — 자동 답으로는 넘김이 닫히지 않는다
        long 열린_넘김 = ChatFixture.방을_넣는다(jdbc, 무드온, 2L, "라라상회");
        long s = ChatFixture.소매_글을_넣는다(jdbc, 열린_넘김, "깎아 주세요");
        ChatFixture.넘김_줄을_넣는다(jdbc, 열린_넘김, "전달했어요", "NEGOTIATION", null, s);
        long s2 = ChatFixture.소매_글을_넣는다(jdbc, 열린_넘김, "미수 얼마예요?");
        ChatFixture.자동_답을_넣는다(jdbc, 열린_넘김, "없어요", "RECEIVABLE", s2, null);

        // 자동 답이 마지막이고 넘김 없음 — 답할 필요 없다
        long 자동_답_끝 = ChatFixture.방을_넣는다(jdbc, 무드온, 3L, "다온상회");
        long s3 = ChatFixture.소매_글을_넣는다(jdbc, 자동_답_끝, "재고요");
        ChatFixture.자동_답을_넣는다(jdbc, 자동_답_끝, "있어요", "STOCK", s3, null);

        // 사장이 답해 넘김이 닫혔다
        long 사장_답 = ChatFixture.방을_넣는다(jdbc, 무드온, 4L, "해담상회");
        long s4 = ChatFixture.소매_글을_넣는다(jdbc, 사장_답, "깎아 주세요");
        ChatFixture.넘김_줄을_넣는다(jdbc, 사장_답, "전달했어요", "NEGOTIATION", null, s4);
        ChatFixture.도매_글을_넣는다(jdbc, 사장_답, "안 돼요");

        ChatFixture.방을_넣는다(jdbc, 무드온, 5L, "빈방상회");   // 메시지 없음
        long 남의_방 = ChatFixture.방을_넣는다(jdbc, 라온, 1L, "봄봄상회");
        ChatFixture.소매_글을_넣는다(jdbc, 남의_방, "있어요?");

        mvc.perform(get("/api/wholesale/notifications/unread-count").with(approvedAs(무드온)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chatsNeedingReply").value(2));
        assertThat(needsReply목록(무드온)).containsExactlyInAnyOrder(소매_글_마지막, 열린_넘김);

        mvc.perform(get("/api/wholesale/notifications/unread-count").with(approvedAs(라온)))
                .andExpect(jsonPath("$.data.chatsNeedingReply").value(1));
    }

    @Test
    void 방이_없으면_0이다() throws Exception {
        long 무드온 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-badge-3@ondo.test", "9500000193");

        mvc.perform(get("/api/wholesale/notifications/unread-count").with(approvedAs(무드온)))
                .andExpect(jsonPath("$.data.chatsNeedingReply").value(0));
    }

    private List<Long> needsReply목록(long wholesalerId) throws Exception {
        String raw = mvc.perform(get("/api/wholesale/chat/rooms").param("filter", "NEEDS_REPLY").param("size", "100")
                        .with(approvedAs(wholesalerId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Long> ids = new ArrayList<>();
        for (JsonNode room : JSON.readTree(raw).get("data")) {
            ids.add(room.get("roomId").asLong());
        }
        return ids;
    }
}
