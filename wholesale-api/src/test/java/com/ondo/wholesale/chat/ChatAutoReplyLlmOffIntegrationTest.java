package com.ondo.wholesale.chat;

import com.ondo.wholesale.chat.inquiry.llm.InquiryLlmGate;
import com.ondo.wholesale.chat.inquiry.llm.InquiryReader;
import com.ondo.wholesale.support.ChatFixture;
import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 기본 설정(LLM 꺼짐)에서는 보내기가 지금 경로 그대로인지 본다 (MUL-157).
 *
 * <p>일부러 다른 통합 테스트와 같은 설정(@MockitoBean 도 속성 덮어쓰기도 없음)이라 컨텍스트를 새로 만들지 않는다.
 * 그래서 가짜 InquiryReader 를 끼워 "한 번도 안 불렸다"를 verify 할 수 없다. 대신 게이트가 꺼져 있고 실제 읽기 빈은
 * 가짜가 아니며, 규칙이 못 읽는 자유 문장이 규칙 결과(넘김)로 끝나는 것을 본다.
 * 켠 상태(가짜 호출 verify 포함)는 ChatAutoReplyLlmIntegrationTest 몫이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChatAutoReplyLlmOffIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired InquiryLlmGate gate;
    @Autowired InquiryReader reader;

    @Test
    void 기본_설정에서는_게이트가_꺼져_있고_자유_문장은_규칙_결과로_끝난다() throws Exception {
        assertThat(gate.isOn()).isFalse();
        assertThat(Mockito.mockingDetails(reader).isMock()).isFalse();
        long 도매처 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-llm-off@ondo.test", "9500000172");
        long 방 = ChatFixture.방을_넣는다(jdbc, 도매처, 8603L, "꺼짐상회");

        mvc.perform(post("/api/retail-gateway/chat/rooms/" + 방 + "/messages").param("retailerId", "8603")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"블랙 M 구할 수 있을까요?\",\"inquiry\":null}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.generatedMessages.length()").value(1))
                .andExpect(jsonPath("$.data.generatedMessages[0].kind").value("HANDOFF"));

        assertThat(jdbc.queryForObject("select count(*) from wholesale.chat_message where room_id = ? and sequence = 2",
                Integer.class, 방)).isEqualTo(1);
    }
}
