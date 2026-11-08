package com.ondo.retail.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ondo.retail.chat.dto.ChatMessageResponses.ChatAutoReply;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatInquiry;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatMessage;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatMessagePage;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatMessagePageMeta;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatReadResult;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatRetailFacts;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatRetailStockFacts;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatSendResult;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatSubject;
import com.ondo.retail.chat.dto.ChatRoomResponses.ChatCounterpart;
import com.ondo.retail.chat.dto.ChatRoomResponses.ChatRoomDetail;
import com.ondo.retail.common.error.BusinessException;
import com.ondo.retail.common.error.ErrorCode;
import com.ondo.retail.wholesale.WholesaleApiException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 소매 채팅 6 op 의 HTTP 계약 (MUL-157).
 *
 * <p>여기서 보는 것: 프론트 400(errors[{field}]) · 커서로 다음 장 잇기 · 201/200 가르기 · 도매 거절(404·409)을 소매 코드로 ·
 * 소매처 id·상호를 세션·소매 DB 에서 꺼내는지 · 응답에 넘김 이유·정확 수량 칸이 없는지. 도매 호출 자체는
 * WholesaleChatAdapterTest 가, 판정과 저장은 도매 테스트(RetailGatewayChatIntegrationTest, 골든셋)가 본다.
 *
 * <p>{@code @WithMockUser(username = "1")} — 시드의 봄봄상회(승인됨). 도매는 가짜로 세운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(username = "1")
class ChatControllerTest {

    private static final long 봄봄 = 1L;
    private static final long 방 = 501L;
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 8, 1, 0, 0, 123_456_000, ZoneOffset.UTC);

    @Autowired MockMvc mvc;

    @MockitoBean ChatClient client;

    @Test
    @DisplayName("목록 — size 를 안 주면 30, 31개를 물어 하나가 남으면 nextCursor. 목록 행에는 상대 읽음 위치가 없다")
    void 목록_기본_size_와_다음_커서() throws Exception {
        when(client.rooms(eq(봄봄), eq(null), eq(31))).thenReturn(방들(31));

        String next = ChatRoomCursor.of(T0.minusMinutes(29), 471).encode();
        mvc.perform(get("/api/retail/chat/rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(30))
                .andExpect(jsonPath("$.meta.nextCursor").value(next))
                .andExpect(jsonPath("$.data[0].counterpart.name").value("무드온"))
                .andExpect(jsonPath("$.data[0].counterpartLastReadSequence").doesNotExist())
                .andExpect(jsonPath("$.data[0].lastMessage").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void 커서를_주면_그_지점_뒤를_묻고_마지막_장이면_nextCursor는_null이다() throws Exception {
        ChatRoomCursor cursor = ChatRoomCursor.of(T0, 480);
        when(client.rooms(eq(봄봄), eq(cursor), eq(3))).thenReturn(방들(2));

        mvc.perform(get("/api/retail/chat/rooms").param("cursor", cursor.encode()).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.meta.nextCursor").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void 잘못된_cursor나_size는_400_VALIDATION_FAILED이고_도매를_부르지_않는다() throws Exception {
        mvc.perform(get("/api/retail/chat/rooms").param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("cursor"));
        mvc.perform(get("/api/retail/chat/rooms").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
        verify(client, never()).rooms(anyLong(), any(), anyInt());
    }

    @Test
    @DisplayName("방 열기 — 새 방 201, 있던 방 200. 소매처 상호는 소매 DB 에서 읽어 도매에 싣는다")
    void 방_열기는_새_방_201_있던_방_200이다() throws Exception {
        when(client.open(봄봄, "봄봄상회", 101L, 2001L)).thenReturn(new ChatClient.Opened(true, 방머리(방)));
        when(client.open(봄봄, "봄봄상회", 101L, null)).thenReturn(new ChatClient.Opened(false, 방머리(방)));

        mvc.perform(json(post("/api/retail/chat/rooms"), "{\"wholesalerId\":101,\"listingId\":2001}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.roomId").value(방))
                .andExpect(jsonPath("$.data.counterpartLastReadSequence").value(0));
        mvc.perform(json(post("/api/retail/chat/rooms"), "{\"wholesalerId\":101,\"listingId\":null}"))
                .andExpect(status().isOk());
    }

    @Test
    void 방_열기에_listingId_칸이_없거나_wholesalerId가_없으면_400이다() throws Exception {
        mvc.perform(json(post("/api/retail/chat/rooms"), "{\"wholesalerId\":101}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("listingId"));
        mvc.perform(json(post("/api/retail/chat/rooms"), "{\"listingId\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("wholesalerId"));
    }

    @Test
    void 없는_도매처면_404_RESOURCE_NOT_FOUND다() throws Exception {
        when(client.open(봄봄, "봄봄상회", 999L, null)).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        mvc.perform(json(post("/api/retail/chat/rooms"), "{\"wholesalerId\":999,\"listingId\":null}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("보내기 — 새 키 201 + generatedMessages, 같은 키 replay 200. 넘김 줄에 이유 칸이 없다")
    void 보내기는_새_키_201_replay_200이다() throws Exception {
        String key = UUID.randomUUID().toString();
        ChatSendResult result = 보낸_결과();
        when(client.send(봄봄, 방, key, " 블랙 M 몇 장 남았어요? ", null))
                .thenReturn(new ChatClient.Sent(result, false))
                .thenReturn(new ChatClient.Sent(result, true));

        String body = "{\"body\":\" 블랙 M 몇 장 남았어요? \",\"inquiry\":null}";
        mvc.perform(json(post("/api/retail/chat/rooms/" + 방 + "/messages").header("Idempotency-Key", key), body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.generation").value("DONE"))
                .andExpect(jsonPath("$.data.generatedMessages.length()").value(2))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.facts.stock.band").value("UNDER_50"))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.facts.stock.availableQty").doesNotExist())
                .andExpect(jsonPath("$.data.generatedMessages[1].kind").value("HANDOFF"))
                .andExpect(jsonPath("$.data.generatedMessages[1].handoff").doesNotExist());
        mvc.perform(json(post("/api/retail/chat/rooms/" + 방 + "/messages").header("Idempotency-Key", key), body))
                .andExpect(status().isOk());
    }

    @Test
    void 칩_문맥은_그대로_도매에_넘긴다() throws Exception {
        String key = UUID.randomUUID().toString();
        ChatInquiry inquiry = new ChatInquiry("BACKORDER", null, 3003L, 9001L, null, null);
        when(client.send(봄봄, 방, key, "린넨 셔츠", inquiry)).thenReturn(new ChatClient.Sent(보낸_결과(), false));

        mvc.perform(json(post("/api/retail/chat/rooms/" + 방 + "/messages").header("Idempotency-Key", key),
                        "{\"body\":\"린넨 셔츠\",\"inquiry\":{\"intent\":\"BACKORDER\",\"listingId\":null,"
                                + "\"variantId\":3003,\"backorderId\":9001,\"label\":null}}"))
                .andExpect(status().isCreated());
    }

    @Test
    void 보내기에_키가_없거나_형식이_틀리면_400_Idempotency_Key다() throws Exception {
        mvc.perform(json(post("/api/retail/chat/rooms/" + 방 + "/messages"), "{\"body\":\"안녕\",\"inquiry\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
        mvc.perform(json(post("/api/retail/chat/rooms/" + 방 + "/messages").header("Idempotency-Key", "1-1-1-1-1"),
                        "{\"body\":\"안녕\",\"inquiry\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
        verify(client, never()).send(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void 본문이_글자가_아니거나_문의_종류가_틀리면_400이다() throws Exception {
        String key = UUID.randomUUID().toString();
        mvc.perform(json(post("/api/retail/chat/rooms/" + 방 + "/messages").header("Idempotency-Key", key),
                        "{\"body\":123,\"inquiry\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("body"));
        mvc.perform(json(post("/api/retail/chat/rooms/" + 방 + "/messages").header("Idempotency-Key", key),
                        "{\"body\":\"안녕\",\"inquiry\":{\"intent\":\"PRICE\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("inquiry.intent"));
    }

    @Test
    @DisplayName("남의 방 404 · 같은 키 다른 본문 409 는 도매 거절을 그대로 옮긴다 — 둘 다 영구 거절(D49)")
    void 도매_거절을_소매_코드로_옮긴다() throws Exception {
        String key = UUID.randomUUID().toString();
        when(client.send(eq(봄봄), eq(방), eq(key), eq("남의 방"), any()))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        when(client.send(eq(봄봄), eq(방), eq(key), eq("다른 본문"), any()))
                .thenThrow(new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED));

        mvc.perform(json(post("/api/retail/chat/rooms/" + 방 + "/messages").header("Idempotency-Key", key),
                        "{\"body\":\"남의 방\",\"inquiry\":null}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(json(post("/api/retail/chat/rooms/" + 방 + "/messages").header("Idempotency-Key", key),
                        "{\"body\":\"다른 본문\",\"inquiry\":null}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    @WithMockUser(username = "2")
    void 승인_전_계정은_보내기가_403_ACCOUNT_NOT_APPROVED다() throws Exception {
        mvc.perform(json(post("/api/retail/chat/rooms/" + 방 + "/messages")
                                .header("Idempotency-Key", UUID.randomUUID().toString()),
                        "{\"body\":\"안녕\",\"inquiry\":null}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_APPROVED"));
        verify(client, never()).send(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void 도매에_닿지_못하면_500_INTERNAL_ERROR다() throws Exception {
        when(client.room(봄봄, 방)).thenThrow(new WholesaleApiException("도매 없음", null));

        mvc.perform(get("/api/retail/chat/rooms/" + 방))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void 메시지_조회는_after와_before를_같이_못_쓰고_size는_1에서_100이다() throws Exception {
        mvc.perform(get("/api/retail/chat/rooms/" + 방 + "/messages").param("afterSequence", "3")
                        .param("beforeSequence", "9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("afterSequence"));
        mvc.perform(get("/api/retail/chat/rooms/" + 방 + "/messages").param("beforeSequence", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("beforeSequence"));
        mvc.perform(get("/api/retail/chat/rooms/" + 방 + "/messages").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
        verify(client, never()).messages(anyLong(), anyLong(), any(), any(), anyInt());
    }

    @Test
    void 메시지_조회는_기본_50개로_도매에_묻고_meta를_그대로_싣는다() throws Exception {
        when(client.messages(봄봄, 방, 4L, null, 50)).thenReturn(new ChatMessagePage(
                List.of(보낸_결과().message()), new ChatMessagePageMeta(false, 7L, 0L)));

        mvc.perform(get("/api/retail/chat/rooms/" + 방 + "/messages").param("afterSequence", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.hasMore").value(false))
                .andExpect(jsonPath("$.meta.maxSequence").value(7))
                .andExpect(jsonPath("$.meta.counterpartLastReadSequence").value(0));
    }

    @Test
    void 읽음은_0_이상_정수만_받고_결과를_그대로_싣는다() throws Exception {
        when(client.markRead(봄봄, 방, 3L)).thenReturn(new ChatReadResult(방, 3L, 0));

        mvc.perform(json(post("/api/retail/chat/rooms/" + 방 + "/read"), "{\"lastReadSequence\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastReadSequence").value(3))
                .andExpect(jsonPath("$.data.unreadCount").value(0));
        mvc.perform(json(post("/api/retail/chat/rooms/" + 방 + "/read"), "{\"lastReadSequence\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lastReadSequence"));
    }

    // ── 픽스처 ──────────────────────────────────────────────

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static ChatRoomDetail 방머리(long roomId) {
        return new ChatRoomDetail(roomId, new ChatCounterpart(101L, "무드온"), null, T0, 0, 0L, false, false, 0L);
    }

    /** 최신순 n개. i 번째 방은 T0 - i분, id 500 - i. */
    private static List<ChatRoomDetail> 방들(int n) {
        List<ChatRoomDetail> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(new ChatRoomDetail(500L - i, new ChatCounterpart(101L, "무드온"), null, T0.minusMinutes(i),
                    0, 0L, false, false, 0L));
        }
        return rows;
    }

    private static ChatSendResult 보낸_결과() {
        ChatMessage mine = new ChatMessage(1L, 방, 1L, UUID.randomUUID(), "RETAILER", "TEXT", "블랙 M 몇 장 남았어요?",
                T0, null, null);
        ChatMessage reply = new ChatMessage(2L, 방, 2L, null, "WHOLESALER", "AUTO_REPLY",
                "블랙 M은 10~49장 있어요. 지금 주문할 수 있어요", T0,
                null, new ChatAutoReply("STOCK", 1L, "RULE", T0, new ChatSubject(2001L, 3002L, null, "셔츠 블랙 / M"),
                new ChatRetailFacts(new ChatRetailStockFacts("UNDER_50", null, true), null, null), null));
        ChatMessage handoff = new ChatMessage(3L, 방, 3L, null, "SYSTEM", "HANDOFF",
                "자동 답변이 답하지 못했어요. 직접 답해 주세요", T0, null, null);
        return new ChatSendResult(mine, List.of(reply, handoff), "DONE");
    }
}
