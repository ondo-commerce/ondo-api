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

import java.util.Map;
import java.util.UUID;

import static com.ondo.wholesale.security.support.TestSecuritySupport.approvedAs;
import static com.ondo.wholesale.security.support.TestSecuritySupport.pending;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사장 보내기 통합 검증 (MUL-158) — sendChatMessage.
 *
 * <p>저장(sequence 채번·방 머리 갱신·내 읽음 올림), 멱등(같은 키+같은 본문 → 200 replay, 다른 본문 → 409),
 * 검사 순서와 400, 소유권 404, 그리고 저장된 사장 글이 같은 테이블에서 바로 보이는 것(소매 polling 이 나중에 읽을 줄)을 본다.
 * 조회 모양 자체는 ChatMessageQueryIntegrationTest 가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChatSendIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private long 무드온;
    private long 방;
    private long 소매문의;

    @BeforeEach
    void 소매_문의가_온_방을_심는다() {
        무드온 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-send@ondo.test", "9500000162");
        방 = ChatFixture.방을_넣는다(jdbc, 무드온, 1L, "봄봄상회");
        소매문의 = ChatFixture.소매_글을_넣는다(jdbc, 방, "레드 M 언제 들어와요?");
    }

    @Test
    void 새_키는_201이고_사장_TEXT로_저장되며_자동_답은_없다() throws Exception {
        String key = UUID.randomUUID().toString();

        보낸다(key, "{\"body\": \"  내일 들어와요  \", \"inquiry\": null}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.message.roomId").value(방))
                .andExpect(jsonPath("$.data.message.sequence").value(2))
                .andExpect(jsonPath("$.data.message.sender").value("WHOLESALER"))
                .andExpect(jsonPath("$.data.message.kind").value("TEXT"))
                .andExpect(jsonPath("$.data.message.body").value("내일 들어와요"))   // 앞뒤 공백을 지운 값
                .andExpect(jsonPath("$.data.message.clientMessageId").value(key))
                .andExpect(jsonPath("$.data.message.inquiry").value(nullValue()))
                .andExpect(jsonPath("$.data.message.autoReply").value(nullValue()))
                .andExpect(jsonPath("$.data.message.handoff").value(nullValue()))
                .andExpect(jsonPath("$.data.generatedMessages.length()").value(0))
                .andExpect(jsonPath("$.data.generation").value("NONE"));

        Map<String, Object> room = jdbc.queryForMap(
                "select last_sequence, wholesaler_last_read_sequence, last_message_id from wholesale.chat_room where id = ?",
                방);
        assertThat(room.get("last_sequence")).isEqualTo(2L);
        // 내가 보낸 것까지는 읽은 것이다 — 그 앞의 소매 글도 읽음 처리된다
        assertThat(room.get("wholesaler_last_read_sequence")).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select sender from wholesale.chat_message where id = ?", String.class,
                room.get("last_message_id"))).isEqualTo("WHOLESALER");
    }

    @Test
    void 보낸_사장_글은_afterSequence_polling에_바로_보이고_방_목록의_마지막_메시지가_된다() throws Exception {
        보낸다(UUID.randomUUID().toString(), "{\"body\": \"내일 들어와요\", \"inquiry\": null}")
                .andExpect(status().isCreated());

        mvc.perform(get("/api/wholesale/chat/rooms/" + 방 + "/messages?afterSequence=" + 소매문의)
                        .with(approvedAs(무드온)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].body").value("내일 들어와요"))
                .andExpect(jsonPath("$.meta.maxSequence").value(2));
        mvc.perform(get("/api/wholesale/chat/rooms").with(approvedAs(무드온)))
                .andExpect(jsonPath("$.data[0].lastMessage.sender").value("WHOLESALER"))
                .andExpect(jsonPath("$.data[0].lastMessage.preview").value("내일 들어와요"))
                .andExpect(jsonPath("$.data[0].unreadCount").value(0))
                .andExpect(jsonPath("$.data[0].needsReply").value(false));
    }

    @Test
    void 사장이_직접_쓰면_열린_넘김이_닫힌다() throws Exception {
        ChatFixture.넘김_줄을_넣는다(jdbc, 방, "사장님께 전달했어요", "ETA_UNKNOWN", "BACKORDER", 소매문의);

        보낸다(UUID.randomUUID().toString(), "{\"body\": \"확인해 볼게요\", \"inquiry\": null}")
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("select open_handoff_sequence from wholesale.chat_room where id = ?",
                Long.class, 방)).isNull();
        mvc.perform(get("/api/wholesale/chat/rooms/" + 방).with(approvedAs(무드온)))
                .andExpect(jsonPath("$.data.openHandoff").value(false))
                .andExpect(jsonPath("$.data.needsReply").value(false));
    }

    @Test
    void 같은_키에_같은_본문은_200으로_처음과_같은_본문을_내리고_메시지는_하나다() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"body\": \"내일 들어와요 \", \"inquiry\": null}";
        String first = 보낸다(key, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String replay = 보낸다(key, body).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from wholesale.chat_message where room_id = ?",
                Integer.class, 방)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select last_sequence from wholesale.chat_room where id = ?",
                Long.class, 방)).isEqualTo(2L);
    }

    @Test
    void inquiry가_빠진_것과_null은_같은_본문이다() throws Exception {
        String key = UUID.randomUUID().toString();
        보낸다(key, "{\"body\": \"내일요\", \"inquiry\": null}").andExpect(status().isCreated());

        보낸다(key, "{\"body\": \"내일요\"}").andExpect(status().isOk());
    }

    @Test
    void 같은_키에_다른_본문은_409_IDEMPOTENCY_KEY_REUSED다() throws Exception {
        String key = UUID.randomUUID().toString();
        보낸다(key, "{\"body\": \"내일 들어와요\", \"inquiry\": null}").andExpect(status().isCreated());

        보낸다(key, "{\"body\": \"모레 들어와요\", \"inquiry\": null}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        // 공백만 달라도 보낸 그대로가 다르면 다른 본문이다
        보낸다(key, "{\"body\": \"내일 들어와요 \", \"inquiry\": null}")
                .andExpect(status().isConflict());
        // 멱등은 글자 수 검사보다 먼저다 — 규칙에 안 맞는 본문이어도 409
        보낸다(key, "{\"body\": \"   \", \"inquiry\": null}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 키_범위는_방_단위라_다른_방에선_같은_키로_새로_만든다() throws Exception {
        long 다른방 = ChatFixture.방을_넣는다(jdbc, 무드온, 2L, "대기상회");
        String key = UUID.randomUUID().toString();
        보낸다(key, "{\"body\": \"안녕하세요\", \"inquiry\": null}").andExpect(status().isCreated());

        mvc.perform(post("/api/wholesale/chat/rooms/" + 다른방 + "/messages").with(approvedAs(무드온))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"안녕하세요\", \"inquiry\": null}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.message.sequence").value(1));
    }

    @Test
    void 키가_없거나_uuid가_아니면_Idempotency_Key_필드의_VALIDATION_FAILED_400이다() throws Exception {
        mvc.perform(post("/api/wholesale/chat/rooms/" + 방 + "/messages").with(approvedAs(무드온))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"안녕\", \"inquiry\": null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));

        보낸다("1-1-1-1-1", "{\"body\": \"안녕\", \"inquiry\": null}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
    }

    @Test
    void 본문이_없거나_공백뿐이거나_1000_코드_포인트를_넘으면_body_필드의_VALIDATION_FAILED_400이다() throws Exception {
        보낸다(UUID.randomUUID().toString(), "{\"inquiry\": null}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("body"));
        보낸다(UUID.randomUUID().toString(), "{\"body\": \" \\n \", \"inquiry\": null}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("body"));
        보낸다(UUID.randomUUID().toString(), "{\"body\": \"" + "가".repeat(1001) + "\", \"inquiry\": null}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("body"));

        assertThat(jdbc.queryForObject("select count(*) from wholesale.chat_send_idempotency where room_id = ?",
                Integer.class, 방)).isZero();
    }

    @Test
    void 글자_수는_코드_포인트로_센다_이모지_1000개는_보낼_수_있다() throws Exception {
        String 이모지천개 = "😀".repeat(1000);   // UTF-16 으로는 2000자

        보낸다(UUID.randomUUID().toString(), "{\"body\": \"" + 이모지천개 + "\", \"inquiry\": null}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.message.body").value(이모지천개));
    }

    @Test
    void 남의_방에_보내면_404_RESOURCE_NOT_FOUND이고_아무것도_저장하지_않는다() throws Exception {
        long 라온 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-send-raon@ondo.test", "9500000163");

        mvc.perform(post("/api/wholesale/chat/rooms/" + 방 + "/messages").with(approvedAs(라온))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"안녕\", \"inquiry\": null}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(jdbc.queryForObject("select count(*) from wholesale.chat_message where room_id = ?",
                Integer.class, 방)).isEqualTo(1);
    }

    @Test
    void 승인_전_계정은_403_NOT_APPROVED이고_비로그인은_401_UNAUTHENTICATED다() throws Exception {
        mvc.perform(post("/api/wholesale/chat/rooms/" + 방 + "/messages").with(pending())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"안녕\", \"inquiry\": null}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_APPROVED"));
        mvc.perform(get("/api/wholesale/chat/rooms"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private ResultActions 보낸다(String key, String json) throws Exception {
        return mvc.perform(post("/api/wholesale/chat/rooms/" + 방 + "/messages").with(approvedAs(무드온))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }
}
