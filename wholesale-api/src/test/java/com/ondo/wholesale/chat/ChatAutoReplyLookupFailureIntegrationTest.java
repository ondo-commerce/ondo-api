package com.ondo.wholesale.chat;

import com.ondo.wholesale.chat.inquiry.InquirySourceReader;
import com.ondo.wholesale.support.ChatFixture;
import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 판정 원본 조회가 SQL 에서 실패해도 소매 보내기는 201 이고 넘김 줄이 남는다 (MUL-157).
 *
 * <p>잡고 싶은 사고: Postgres 는 SQL 하나가 실패하면 트랜잭션 전체가 막힌다. 판정이 예외를 잡아 LOOKUP_FAILED 로
 * 바꿔도, 세이브포인트로 되감지 않으면 뒤의 줄 붙이기(INSERT)가 "current transaction is aborted" 로 죽고 소매 글까지
 * 사라진다. 그래서 원본 조회가 <b>진짜 SQL 오류</b>를 내게 만든다. 판정 규칙(어떤 문장이 미수인지)은 골든셋이 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChatAutoReplyLookupFailureIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean InquirySourceReader sources;

    @Test
    void 미수_원본이_SQL에서_실패하면_LOOKUP_FAILED_넘김이고_보내기는_201이다() throws Exception {
        long 무드온 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-lookup@ondo.test", "9500000159");
        long 방 = ChatFixture.방을_넣는다(jdbc, 무드온, 8401L, "봄봄상회");
        doAnswer(invocation -> jdbc.queryForObject("select 1 / 0", Integer.class))
                .when(sources).receivables(anyLong(), anyLong(), any());

        mvc.perform(post("/api/retail-gateway/chat/rooms/" + 방 + "/messages").param("retailerId", "8401")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"미수 얼마예요?\",\"inquiry\":null}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.generatedMessages.length()").value(1))
                .andExpect(jsonPath("$.data.generatedMessages[0].kind").value("HANDOFF"));

        assertThat(jdbc.queryForObject("select handoff_reason from wholesale.chat_message where room_id = ? and sequence = 2",
                String.class, 방)).isEqualTo("LOOKUP_FAILED");
        assertThat(jdbc.queryForObject("select count(*) from wholesale.chat_send_idempotency where room_id = ?",
                Integer.class, 방)).isEqualTo(1);
    }
}
