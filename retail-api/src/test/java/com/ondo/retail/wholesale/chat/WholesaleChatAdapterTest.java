package com.ondo.retail.wholesale.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ondo.retail.chat.ChatClient;
import com.ondo.retail.chat.ChatRoomCursor;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatMessagePage;
import com.ondo.retail.common.error.BusinessException;
import com.ondo.retail.common.error.ErrorCode;
import com.ondo.retail.wholesale.WholesaleApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * 도매 채팅 API 어댑터 (MUL-157).
 *
 * <p>WholesaleNotificationAdapterTest 와 같은 방식이다 — 가짜 도매를 세우고 주소 · 옮기기 · 실패를 본다. 프론트 검증·커서
 * 문자열은 ChatControllerTest 가, 판정·저장·소매 모양 투영은 도매 RetailGatewayChatIntegrationTest 가 본다.
 *
 * <p>잡고 싶은 사고 하나: 도매가 실수로 넘김 이유나 정확 수량을 실어 보내도 소매 응답으로 새지 않아야 한다.
 */
class WholesaleChatAdapterTest {

    private static final String BASE = "http://wholesale.test";
    private static final String CHAT = BASE + "/api/retail-gateway/chat";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MockRestServiceServer 도매;
    private WholesaleChatAdapter adapter;

    @BeforeEach
    void 가짜_도매를_세운다() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        도매 = MockRestServiceServer.bindTo(builder).build();
        WholesaleChatApi api = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(builder.build()))
                .build()
                .createClient(WholesaleChatApi.class);
        adapter = new WholesaleChatAdapter(api);
    }

    @Test
    @DisplayName("목록 — 첫 장은 keyset 없이, 다음 장은 마이크로초·roomId 를 싣는다")
    void 목록_keyset을_주소에_싣는다() {
        도매.expect(requestTo(CHAT + "/rooms?retailerId=42&limit=31"))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
        도매.expect(requestTo(CHAT + "/rooms?retailerId=42&limit=31&beforeMicros=1762477200123456&beforeId=7"))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        adapter.rooms(42L, null, 31);
        adapter.rooms(42L, new ChatRoomCursor(1762477200123456L, 7L), 31);

        도매.verify();
    }

    @Test
    void 방_열기는_상호를_실어_보내고_created를_옮긴다() {
        도매.expect(requestTo(CHAT + "/rooms"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"retailerId":42,"retailerName":"봄봄상회","wholesalerId":101,"listingId":2001}"""))
                .andRespond(withSuccess("""
                        {"data":{"created":true,"room":{"roomId":5,"counterpart":{"id":101,"name":"무드온"},
                          "lastMessage":null,"lastActivityAt":"2026-11-08T01:00:00Z","unreadCount":0,
                          "myLastReadSequence":0,"needsReply":false,"openHandoff":false,
                          "counterpartLastReadSequence":0}}}
                        """, MediaType.APPLICATION_JSON));

        ChatClient.Opened opened = adapter.open(42L, "봄봄상회", 101L, 2001L);

        assertThat(opened.created()).isTrue();
        assertThat(opened.room().roomId()).isEqualTo(5L);
        assertThat(opened.room().counterpart().name()).isEqualTo("무드온");
    }

    @Test
    @DisplayName("보내기 — 키를 헤더로, 본문은 받은 그대로. 201 은 새로, 200 은 replay")
    void 보내기는_201과_200을_가른다() {
        String key = "6f1c1a52-6a3e-4a8e-9a43-7d0f3f6f7a10";
        도매.expect(requestTo(CHAT + "/rooms/5/messages?retailerId=42"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", key))
                .andExpect(content().json("{\"body\":\" 안녕 \",\"inquiry\":null}"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body(보낸_응답()));
        도매.expect(requestTo(CHAT + "/rooms/5/messages?retailerId=42"))
                .andRespond(withSuccess(보낸_응답(), MediaType.APPLICATION_JSON));

        ChatClient.Sent first = adapter.send(42L, 5L, key, " 안녕 ", null);
        ChatClient.Sent again = adapter.send(42L, 5L, key, " 안녕 ", null);

        assertThat(first.replayed()).isFalse();
        assertThat(again.replayed()).isTrue();
        assertThat(first.result().generatedMessages()).hasSize(2);
        assertThat(first.result().generation()).isEqualTo("DONE");
    }

    @Test
    @DisplayName("도매가 넘김 이유·정확 수량을 실어 보내도 소매 모양에는 그 칸이 없어 버려진다")
    void 소매_계약에_없는_칸은_버려진다() {
        도매.expect(requestTo(CHAT + "/rooms/5/messages?retailerId=42&size=50"))
                .andRespond(withSuccess("""
                        {"data":[
                          {"id":2,"roomId":5,"sequence":2,"clientMessageId":null,"sender":"WHOLESALER","kind":"AUTO_REPLY",
                           "body":"블랙 L은 지금은 재고가 없어요","sentAt":"2026-11-08T01:00:00Z","inquiry":null,
                           "autoReply":{"intent":"STOCK","replyToSequence":1,"generatedBy":"RULE",
                             "checkedAt":"2026-11-08T01:00:00Z",
                             "subject":{"listingId":2001,"variantId":3004,"backorderId":null,"label":"셔츠 블랙 / L"},
                             "facts":{"stock":{"band":"NONE","orderLimit":null,"orderable":true,"availableQty":0},
                                      "backorder":null,"receivable":null},
                             "choices":null}},
                          {"id":3,"roomId":5,"sequence":3,"clientMessageId":null,"sender":"SYSTEM","kind":"HANDOFF",
                           "body":"자동 답변이 답하지 못했어요. 직접 답해 주세요","sentAt":"2026-11-08T01:00:00Z",
                           "inquiry":null,"autoReply":null,
                           "handoff":{"intent":"STOCK","reason":"STOCK_ZERO","sourceSequence":1}}
                        ],"meta":{"hasMore":false,"maxSequence":3,"counterpartLastReadSequence":0}}
                        """, MediaType.APPLICATION_JSON));

        ChatMessagePage page = adapter.messages(42L, 5L, null, null, 50);
        String out = JSON.writeValueAsString(page);

        assertThat(page.data()).hasSize(2);
        assertThat(page.data().getFirst().autoReply().facts().stock().band()).isEqualTo("NONE");
        assertThat(out).doesNotContain("availableQty").doesNotContain("handoff").doesNotContain("STOCK_ZERO");
        assertThat(page.meta().maxSequence()).isEqualTo(3L);
    }

    @Test
    void 도매_404는_RESOURCE_NOT_FOUND다() {
        도매.expect(requestTo(CHAT + "/rooms/9?retailerId=42"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"대화를 찾을 수 없습니다.\",\"errors\":[]}"));

        assertThatThrownBy(() -> adapter.room(42L, 9L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void 도매_409_IDEMPOTENCY_KEY_REUSED는_같은_코드다() {
        도매.expect(requestTo(CHAT + "/rooms/5/messages?retailerId=42"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"IDEMPOTENCY_KEY_REUSED\",\"message\":\"x\",\"errors\":[]}"));

        assertThatThrownBy(() -> adapter.send(42L, 5L, "6f1c1a52-6a3e-4a8e-9a43-7d0f3f6f7a10", "다른 본문", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void 도매_400은_칸_이름을_그대로_VALIDATION_FAILED로_옮긴다() {
        도매.expect(requestTo(CHAT + "/rooms/5/read?retailerId=42"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"VALIDATION_FAILED","message":"x",
                                 "errors":[{"field":"lastReadSequence","reason":"방에 없는 sequence 입니다."}]}"""));

        assertThatThrownBy(() -> adapter.markRead(42L, 5L, 99L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.errors()).singleElement()
                            .satisfies(f -> assertThat(f.field()).isEqualTo("lastReadSequence"));
                });
    }

    @Test
    @DisplayName("도매 5xx·모르는 4xx 는 장애다 — WholesaleApiException(→ 500)")
    void 도매_장애는_WholesaleApiException이다() {
        도매.expect(requestTo(CHAT + "/unread-count?retailerId=42"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        도매.expect(requestTo(CHAT + "/rooms/5?retailerId=42"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> adapter.unreadChats(42L)).isInstanceOf(WholesaleApiException.class);
        assertThatThrownBy(() -> adapter.room(42L, 5L)).isInstanceOf(WholesaleApiException.class);
    }

    @Test
    void 배지는_chats_를_옮긴다() {
        도매.expect(requestTo(CHAT + "/unread-count?retailerId=42"))
                .andRespond(withSuccess("{\"data\":{\"chats\":4}}", MediaType.APPLICATION_JSON));

        assertThat(adapter.unreadChats(42L)).isEqualTo(4);
    }

    private static String 보낸_응답() {
        return """
                {"data":{
                  "message":{"id":1,"roomId":5,"sequence":1,"clientMessageId":"6f1c1a52-6a3e-4a8e-9a43-7d0f3f6f7a10",
                    "sender":"RETAILER","kind":"TEXT","body":"안녕","sentAt":"2026-11-08T01:00:00Z","inquiry":null,
                    "autoReply":null},
                  "generatedMessages":[
                    {"id":2,"roomId":5,"sequence":2,"clientMessageId":null,"sender":"SYSTEM","kind":"HANDOFF",
                     "body":"자동 답변이 답하지 못했어요. 직접 답해 주세요","sentAt":"2026-11-08T01:00:00Z",
                     "inquiry":null,"autoReply":null},
                    {"id":3,"roomId":5,"sequence":3,"clientMessageId":null,"sender":"SYSTEM","kind":"HANDOFF",
                     "body":"자동 답변이 답하지 못했어요. 직접 답해 주세요","sentAt":"2026-11-08T01:00:00Z",
                     "inquiry":null,"autoReply":null}],
                  "generation":"DONE"}}
                """;
    }
}
