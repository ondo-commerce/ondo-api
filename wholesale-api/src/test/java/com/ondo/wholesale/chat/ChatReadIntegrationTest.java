package com.ondo.wholesale.chat;

import com.ondo.wholesale.support.ChatFixture;
import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static com.ondo.wholesale.security.support.TestSecuritySupport.approvedAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 읽음 처리 통합 검증 (MUL-158) — markChatRead.
 *
 * <p>max(기존, 요청) 저장, 저장 뒤 미확인 수(RETAILER 만, SYSTEM·자동 답 제외), 범위 400, 소유권 404 를 본다.
 * 목록·머리의 unreadCount 계산은 ChatRoomQueryIntegrationTest 가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChatReadIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private long 무드온;
    private long 방;

    @BeforeEach
    void 소매_글_셋과_서버_줄이_섞인_방을_심는다() {
        무드온 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-read@ondo.test", "9500000164");
        방 = ChatFixture.방을_넣는다(jdbc, 무드온, 1L, "봄봄상회");
        ChatFixture.소매_글을_넣는다(jdbc, 방, "문의 1");                                    // 1
        long 문의2 = ChatFixture.소매_글을_넣는다(jdbc, 방, "사장님 바꿔주세요");               // 2
        ChatFixture.넘김_줄을_넣는다(jdbc, 방, "사장님께 전달했어요", "HUMAN_REQUEST", null, 문의2); // 3 SYSTEM
        long 문의4 = ChatFixture.소매_글을_넣는다(jdbc, 방, "레드 M 재고?");                     // 4
        ChatFixture.자동_답을_넣는다(jdbc, 방, "있어요", "STOCK", 문의4,
                ChatRoomQueryIntegrationTest.자동답_근거());                                  // 5 WHOLESALER
    }

    @Test
    void 읽음을_올리면_저장하고_그_뒤의_소매_글만_센다() throws Exception {
        읽는다("{\"lastReadSequence\": 2}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(방))
                .andExpect(jsonPath("$.data.lastReadSequence").value(2))
                // 3 넘김(SYSTEM)·5 자동 답은 안 센다 — 4 하나
                .andExpect(jsonPath("$.data.unreadCount").value(1));

        읽는다("{\"lastReadSequence\": 5}").andExpect(jsonPath("$.data.unreadCount").value(0));
        mvc.perform(get("/api/wholesale/chat/rooms/" + 방).with(approvedAs(무드온)))
                .andExpect(jsonPath("$.data.myLastReadSequence").value(5))
                .andExpect(jsonPath("$.data.unreadCount").value(0));
    }

    @Test
    void 늦게_온_작은_값은_읽음_위치를_내리지_않는다() throws Exception {
        읽는다("{\"lastReadSequence\": 4}").andExpect(status().isOk());

        읽는다("{\"lastReadSequence\": 1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastReadSequence").value(4))
                .andExpect(jsonPath("$.data.unreadCount").value(0));
        assertThat(jdbc.queryForObject("select wholesaler_last_read_sequence from wholesale.chat_room where id = ?",
                Long.class, 방)).isEqualTo(4L);
    }

    @Test
    void 영은_받고_음수나_maxSequence_초과나_빈_값은_VALIDATION_FAILED_400이다() throws Exception {
        읽는다("{\"lastReadSequence\": 0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(3));
        읽는다("{\"lastReadSequence\": -1}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("lastReadSequence"));
        읽는다("{\"lastReadSequence\": 6}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lastReadSequence"));
        읽는다("{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lastReadSequence"));
    }

    @Test
    void 남의_방_읽음은_404_RESOURCE_NOT_FOUND다() throws Exception {
        long 라온 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-read-raon@ondo.test", "9500000165");

        mvc.perform(post("/api/wholesale/chat/rooms/" + 방 + "/read").with(approvedAs(라온))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastReadSequence\": 1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(jdbc.queryForObject("select wholesaler_last_read_sequence from wholesale.chat_room where id = ?",
                Long.class, 방)).isZero();
    }

    private ResultActions 읽는다(String json) throws Exception {
        return mvc.perform(post("/api/wholesale/chat/rooms/" + 방 + "/read").with(approvedAs(무드온))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }
}
