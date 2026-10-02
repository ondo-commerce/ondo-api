package com.ondo.retail.wholesale.order;

import static com.ondo.retail.wholesale.WholesaleCall.call;

import com.ondo.retail.order.OrderClient;
import com.ondo.retail.order.dto.OrderView;
import com.ondo.retail.order.dto.PaymentTerm;
import com.ondo.retail.order.dto.ReceiveMethod;
import com.ondo.retail.order.dto.WholesalerWithBank;
import com.ondo.retail.order.dto.WholesaleOrderCommand;
import com.ondo.retail.order.dto.WholesaleOrderReceipt;
import com.ondo.retail.wholesale.dto.WholesaleEnvelope;
import com.ondo.retail.wholesale.order.dto.WholesaleOrderCreateRequest;
import com.ondo.retail.wholesale.order.dto.WholesaleOrderCreated;
import com.ondo.retail.wholesale.order.dto.WholesaleOrderView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 도매 주문 접수 API 를 소매 말로 옮긴다 (MUL-98).
 *
 * <p><b>여기는 예외를 안 던진다.</b> 상품·미송 어댑터와 다른 점이다.
 *
 * <p>상품이 안 읽히면 화면 전체가 못 그려지니 사고다. 그런데 주문은 도매처 하나가
 * 거절해도 나머지는 접수돼야 하고, 거절 사유가 그대로 화면에 뜬다 — 사고가 아니라
 * 결과다. 예외로 만들면 부르는 쪽이 try/catch 로 결과를 조립하게 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WholesaleOrderAdapter implements OrderClient {

    /**
     * 도매가 "이미 받았다" 고 할 때의 코드.
     *
     * <p>소매가 재시도했다는 뜻이라 <b>성공으로 친다.</b> 주문은 이미 도매 장부에 있다.
     * 다만 도매가 409 만 주고 그 주문의 내용은 안 줘서 번호·금액을 못 채운다 —
     * 「같은 열쇠로 다시 오면 처음 결과 그대로」(숙제 7번)를 하면 그때 채워진다.
     */
    private static final String ALREADY_CREATED = "ORDER_ALREADY_CREATED";

    private final WholesaleOrderApi api;

    @Override
    public WholesaleOrderReceipt place(WholesaleOrderCommand command) {
        try {
            WholesaleEnvelope<WholesaleOrderCreated> response = api.create(toRequest(command));
            WholesaleOrderCreated created = response.data();
            return WholesaleOrderReceipt.accepted(created.id(), created.orderNumber(), created.orderAmount());

        } catch (RestClientResponseException e) {
            // 도매가 우리 규약대로 대답한 경우다. 사유를 그대로 옮긴다
            return fromError(command, e);

        } catch (RestClientException e) {
            // 도매가 아예 안 뜨거나(connect timeout) 제때 대답을 안 한 경우(read timeout).
            //
            // ⚠️ read timeout 이면 요청은 도매에 닿았을 수 있다. 도매가 주문을 만들어 놓고
            //    응답만 못 보냈는지, 만들기 전에 죽었는지 여기서는 알 수 없다. 그래서
            //    다시 보내도 되느냐가 문제인데 — 도매에 UNIQUE (retail_order_id,
            //    wholesaler_id) 가 있어 두 번째 요청은 409 로 막히고, 아래에서 그걸
            //    성공으로 친다. 모르면 다시 물어봐도 된다
            log.warn("도매 접수 호출 실패. wholesalerId={} retailOrderId={}",
                    command.wholesalerId(), command.retailOrderId(), e);
            return WholesaleOrderReceipt.unreachable("UPSTREAM_UNAVAILABLE",
                    "도매처에 접수하지 못했어요. 다시 시도하고 있어요");
        }
    }

    /**
     * 주문 조회.
     *
     * <p>접수와 달리 <b>예외를 던진다.</b> 주문 내역·상세는 도매 값을 못 읽으면 화면
     * 자체를 못 그린다 — 상품·미송과 같다. 접수만 결과로 옮기는 것이고, 그건
     * 도매처 하나가 거절해도 나머지는 접수돼야 해서다.
     */
    @Override
    public List<OrderView> findOrders(Long retailerId, List<Long> retailOrderIds) {
        if (retailOrderIds == null || retailOrderIds.isEmpty()) {
            return List.of();
        }
        return call("주문 조회", () -> api.orders(retailerId, retailOrderIds)).data().stream()
                .map(WholesaleOrderAdapter::toView)
                .toList();
    }

    private static OrderView toView(WholesaleOrderView source) {
        WholesaleOrderView.Wholesaler w = source.wholesaler();
        return new OrderView(
                source.retailOrderId(),
                source.orderId(),
                source.orderNumber(),
                new WholesalerWithBank(w.id(), w.name(), w.storeBuilding(), w.storeUnit(),
                        w.bankName(), w.bankAccountNo(), w.bankAccountHolder()),
                source.statusKey(),
                source.statusLabel(),
                PaymentTerm.valueOf(source.paymentTerm()),
                ReceiveMethod.valueOf(source.receiveMethod()),
                source.amount(),
                source.cancellable(),
                source.items().stream()
                        .map(i -> new OrderView.Item(i.listingId(), i.title(), i.colorName(), i.size(),
                                i.qty(), i.unitPrice(), i.receivedQty(), i.backorderQty(),
                                i.expectedInboundDate()))
                        .toList());
    }

    /**
     * 도매의 에러 응답을 결과로 옮긴다.
     *
     * <p>문구를 우리가 다시 쓰지 않고 도매 것을 그대로 쓴다. "판매가가 바뀌었습니다"
     * 같은 건 도매가 자기 상태를 보고 만든 말이라 우리가 더 정확히 쓸 수 없다.
     */
    private WholesaleOrderReceipt fromError(WholesaleOrderCommand command, RestClientResponseException e) {
        WholesaleError error = readError(e);

        if (ALREADY_CREATED.equals(error.code())) {
            log.info("이미 접수된 주문이다. 소매가 재시도한 것으로 본다. wholesalerId={} retailOrderId={}",
                    command.wholesalerId(), command.retailOrderId());
            return new WholesaleOrderReceipt(true, null, null, null, null, "이미 접수된 주문이에요", false);
        }

        // 도매가 자기 사정으로 못 받은 것(5xx)은 다시 해볼 만하다. 우리 요청이 잘못된 게
        // 아니라 도매가 아픈 거라서다. 4xx 는 재고 부족·판매 종료처럼 다시 해도 같은 답이 온다
        if (e.getStatusCode().is5xxServerError()) {
            log.warn("도매가 5xx 로 답했다. wholesalerId={} status={}",
                    command.wholesalerId(), e.getStatusCode());
            // 문구는 도매 것을 안 쓰고 우리가 쓴다. 5xx 본문은 "서버 오류" 같은 일반 문구라
            // 사용자에게 알려줄 게 없고, 무엇보다 이 줄은 장바구니에서 빠져 서버가 맡는다 —
            // 도매 문구가 "장바구니에 그대로" 라고 하면 거짓이 된다 (MUL-141)
            return WholesaleOrderReceipt.unreachable(error.code(),
                    "도매처에 접수하지 못했어요. 다시 시도하고 있어요");
        }

        log.info("도매가 주문을 거절했다. wholesalerId={} code={}", command.wholesalerId(), error.code());
        return WholesaleOrderReceipt.rejected(error.code(), error.message());
    }

    /**
     * 도매 에러 본문을 읽는다.
     *
     * <p>못 읽어도 던지지 않는다. 도매가 아니라 앞단(ALB·프록시)이 대답했으면 우리
     * 규약이 아닌 게 오는데, 그걸로 주문 전체를 실패시킬 이유가 없다.
     */
    private WholesaleError readError(RestClientResponseException e) {
        try {
            WholesaleError body = e.getResponseBodyAs(WholesaleError.class);
            if (body != null && body.code() != null) {
                return body;
            }
        } catch (RuntimeException ignored) {
            // 아래 기본값으로 떨어진다
        }
        // 장바구니가 어떻게 되는지는 여기서 모른다. 재시도 여부에 따라 갈리므로
        // 부르는 쪽이 정한다 (MUL-141)
        log.warn("도매 에러 본문을 못 읽었다. status={}", e.getStatusCode());
        return new WholesaleError("UPSTREAM_ERROR", "도매처에 접수하지 못했어요");
    }

    private static WholesaleOrderCreateRequest toRequest(WholesaleOrderCommand command) {
        return new WholesaleOrderCreateRequest(
                command.retailOrderId(),
                command.retailerId(),
                command.wholesalerId(),
                command.retailerName(),
                command.retailerPhone(),
                command.paymentTerm(),
                command.receiveMethod(),
                command.agentName(),
                command.agentPhone(),
                command.items().stream()
                        .map(l -> new WholesaleOrderCreateRequest.Item(l.variantId(), l.qty(), l.expectedUnitPrice()))
                        .toList());
    }

    /** 도매 실패 응답의 봉투. 성공 봉투(data)와 모양이 다르다. */
    private record WholesaleError(String code, String message) {}
}
