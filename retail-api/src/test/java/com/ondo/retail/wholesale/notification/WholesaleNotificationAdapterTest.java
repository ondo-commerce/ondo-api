package com.ondo.retail.wholesale.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ondo.retail.common.error.BusinessException;
import com.ondo.retail.common.error.ErrorCode;
import com.ondo.retail.notification.NotificationCursor;
import com.ondo.retail.notification.dto.NotificationResponse;
import com.ondo.retail.wholesale.WholesaleApiException;
import java.util.List;
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

/**
 * 도매 알림 API 어댑터 (MUL-155).
 *
 * <p>{@code WholesaleBackorderAdapterTest} 와 같은 방식이다 — 가짜 도매를 세우고 주소 · 옮기기 ·
 * 실패를 본다. 프론트 검증과 커서 문자열은 NotificationControllerTest 가 본다.
 */
class WholesaleNotificationAdapterTest {

    private static final String BASE = "http://wholesale.test";
    private static final String NOTIFICATIONS = BASE + "/api/retail-gateway/notifications";

    private MockRestServiceServer 도매;
    private WholesaleNotificationAdapter adapter;

    @BeforeEach
    void 가짜_도매를_세운다() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        도매 = MockRestServiceServer.bindTo(builder).build();
        WholesaleNotificationApi api = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(builder.build()))
                .build()
                .createClient(WholesaleNotificationApi.class);
        adapter = new WholesaleNotificationAdapter(api);
    }

    @Test
    @DisplayName("첫 장은 keyset 없이, 다음 장은 마이크로초·id 를 싣는다")
    void keyset_을_주소에_싣는다() {
        도매.expect(requestTo(NOTIFICATIONS + "?retailerId=42&limit=21"))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
        도매.expect(requestTo(NOTIFICATIONS + "?retailerId=42&limit=21&beforeMicros=1762477200123456&beforeId=7"))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        adapter.find(42L, null, 21);
        adapter.find(42L, new NotificationCursor(1762477200123456L, 7L), 21);

        도매.verify();
    }

    @Test
    @DisplayName("도매 알림을 그대로 옮긴다 — 문구를 고치지 않는다")
    void 알림을_옮긴다() {
        도매.expect(requestTo(NOTIFICATIONS + "?retailerId=42&limit=2"))
                .andRespond(withSuccess("""
                        {"data":[{
                          "id": 2001, "type": "BACKORDER_DATE_CHANGED",
                          "title": "무드온 미송 입고일 변경",
                          "body": "린넨 셋업 자켓 베이지/L 남은 2장, 11/5(목) → 11/8(일)",
                          "target": {"kind": "BACKORDERS", "wholesalerId": 101},
                          "createdAt": "2026-11-07T01:00:00.123456Z", "readAt": null
                        }]}
                        """, MediaType.APPLICATION_JSON));

        List<NotificationResponse> rows = adapter.find(42L, null, 2);

        NotificationResponse row = rows.getFirst();
        assertThat(row.title()).isEqualTo("무드온 미송 입고일 변경");
        assertThat(row.body()).isEqualTo("린넨 셋업 자켓 베이지/L 남은 2장, 11/5(목) → 11/8(일)");
        assertThat(row.target().kind()).isEqualTo("BACKORDERS");
        assertThat(row.target().wholesalerId()).isEqualTo(101L);
        assertThat(row.target().orderId()).isNull();
        assertThat(row.createdAt().getNano()).isEqualTo(123_456_000);
        assertThat(row.readAt()).isNull();
    }

    @Test
    @DisplayName("모두 읽음 — 도매 409 UNREAD_CHANGED 는 소매 409 로 옮긴다")
    void 모두_읽음의_409를_옮긴다() {
        도매.expect(requestTo(NOTIFICATIONS + "/read"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"retailerId\":42,\"all\":true,\"expectedUnread\":3}"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"UNREAD_CHANGED\",\"message\":\"x\",\"errors\":[],\"traceId\":null}"));

        assertThatThrownBy(() -> adapter.markAllRead(42L, 3))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNREAD_CHANGED));
    }

    @Test
    @DisplayName("도매가 죽으면 소매 예외로 바뀐다 — 409 가 아닌 건 장애로 본다")
    void 도매_장애는_소매_예외가_된다() {
        도매.expect(requestTo(NOTIFICATIONS + "/unread-count?retailerId=42"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        도매.expect(requestTo(NOTIFICATIONS + "/read"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> adapter.unread(42L)).isInstanceOf(WholesaleApiException.class);
        assertThatThrownBy(() -> adapter.markAllRead(42L, 1)).isInstanceOf(WholesaleApiException.class);
    }

    @Test
    @DisplayName("ids 읽음은 retailerId 와 ids 만 싣는다")
    void ids_읽음() {
        도매.expect(requestTo(NOTIFICATIONS + "/read"))
                .andExpect(content().json("{\"retailerId\":42,\"ids\":[1,2]}"))
                .andRespond(withSuccess("{\"data\":{\"notifications\":4}}", MediaType.APPLICATION_JSON));

        assertThat(adapter.markRead(42L, List.of(1L, 2L))).isEqualTo(4);
    }
}
