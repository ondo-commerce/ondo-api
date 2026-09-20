package com.ondo.retail.wholesale.order;

import com.ondo.retail.order.dto.WholesaleOrderCommand;
import com.ondo.retail.order.dto.WholesaleOrderReceipt;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 도매 주문 접수 어댑터 (MUL-98).
 *
 * <p><b>여기서 확인할 건 "예외를 안 던지는 것" 이다.</b> 상품·미송 어댑터는 실패를
 * 예외로 바꾸는데 주문은 그러면 안 된다 — 도매처 하나가 거절해도 나머지는 접수돼야
 * 하고, 거절 사유가 그대로 화면에 뜬다.
 */
class WholesaleOrderAdapterTest {

    private static final String BASE = "http://wholesale.test";
    private static final String ORDERS = BASE + "/api/retail-gateway/orders";

    private MockRestServiceServer 도매;
    private WholesaleOrderAdapter adapter;

    @BeforeEach
    void 가짜_도매를_세운다() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        도매 = MockRestServiceServer.bindTo(builder).build();

        WholesaleOrderApi api = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(builder.build()))
                .build()
                .createClient(WholesaleOrderApi.class);
        adapter = new WholesaleOrderAdapter(api);
    }

    @Test
    @DisplayName("접수되면 도매 주문 id · 연번 · 금액을 옮긴다")
    void 접수_결과를_옮긴다() {
        도매.expect(requestTo(ORDERS))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.retailOrderId").value(5001))
                .andExpect(jsonPath("$.retailerName").value("봄봄상회"))
                .andExpect(jsonPath("$.items[0].expectedUnitPrice").value(12500))
                .andRespond(withSuccess("""
                        {
                          "data": {
                            "id": 8801, "orderNumber": 42, "retailOrderId": 5001,
                            "status": "NEW", "orderAmount": 37500,
                            "orderedAt": "2026-09-06T14:20:00+09:00",
                            "items": [ { "id": 1, "variantId": 9001, "qty": 3, "unitPrice": 12500 } ]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        WholesaleOrderReceipt receipt = adapter.place(명령());

        assertThat(receipt.accepted()).isTrue();
        assertThat(receipt.wholesaleOrderId()).isEqualTo(8801L);
        assertThat(receipt.orderNumber()).isEqualTo(42);
        assertThat(receipt.amount()).isEqualTo(37500);
        도매.verify();
    }

    @Test
    @DisplayName("도매가 거절하면 예외가 아니라 결과로 옮긴다")
    void 거절은_예외가_아니다() {
        도매.expect(requestTo(ORDERS))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "code": "PRICE_CHANGED",
                                  "message": "판매가가 바뀌었습니다. variantId=9001 화면=11000 현재=12500",
                                  "errors": [], "traceId": null
                                }
                                """));

        WholesaleOrderReceipt receipt = adapter.place(명령());

        assertThat(receipt.accepted()).isFalse();
        assertThat(receipt.reason()).isEqualTo("PRICE_CHANGED");
        // 도매가 만든 문구를 우리가 다시 쓰지 않는다. 자기 상태를 보고 쓴 말이라 더 정확하다
        assertThat(receipt.message()).contains("판매가가 바뀌었습니다");
    }

    @Test
    @DisplayName("이미 접수된 주문은 성공으로 친다 — 소매가 재시도한 것이다")
    void 중복_접수는_성공으로_친다() {
        도매.expect(requestTo(ORDERS))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                { "code": "ORDER_ALREADY_CREATED",
                                  "message": "이미 접수된 주문입니다. orderId=8801",
                                  "errors": [], "traceId": null }
                                """));

        WholesaleOrderReceipt receipt = adapter.place(명령());

        assertThat(receipt.accepted()).isTrue();
        // 도매가 409 만 주고 그 주문 내용은 안 줘서 번호·금액을 못 채운다
        assertThat(receipt.wholesaleOrderId()).isNull();
        assertThat(receipt.amount()).isNull();
        assertThat(receipt.message()).contains("이미 접수된");
    }

    @Test
    @DisplayName("도매가 죽어도 예외를 안 던진다 — 나머지 도매처는 접수돼야 한다")
    void 도매_장애도_결과로_옮긴다() {
        도매.expect(requestTo(ORDERS)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        WholesaleOrderReceipt receipt = adapter.place(명령());

        assertThat(receipt.accepted()).isFalse();
        assertThat(receipt.reason()).isNotNull();
        // 도매가 아파서 못 받은 것이다. 우리 요청이 잘못된 게 아니라 다시 해볼 만하다 (MUL-139)
        assertThat(receipt.retryable()).isTrue();
        assertThat(receipt.message()).contains("다시 시도");
    }

    @Test
    @DisplayName("우리 규약이 아닌 응답이 와도 안 터진다 — 앞단이 대답했을 수 있다")
    void 규약_밖_응답도_견딘다() {
        // ALB 나 프록시가 HTML 을 뱉는 경우다 (MUL-106)
        도매.expect(requestTo(ORDERS))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html><body>502 Bad Gateway</body></html>"));

        WholesaleOrderReceipt receipt = adapter.place(명령());

        assertThat(receipt.accepted()).isFalse();
        assertThat(receipt.reason()).isEqualTo("UPSTREAM_ERROR");
        // 502 도 5xx 다. 앞단이 대답했든 도매가 대답했든 다시 해볼 만하다
        assertThat(receipt.retryable()).isTrue();
    }

    private static WholesaleOrderCommand 명령() {
        return new WholesaleOrderCommand(
                5001L, 42L, 101L, "봄봄상회", null, "CASH", "AGENT", "박삼촌", "01033330001",
                List.of(new WholesaleOrderCommand.Line(9001L, 3, 12500)));
    }
}
