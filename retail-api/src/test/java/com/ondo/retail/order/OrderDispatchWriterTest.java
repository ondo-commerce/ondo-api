package com.ondo.retail.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.ondo.retail.order.domain.DispatchStatus;
import com.ondo.retail.order.domain.OrderDispatch;
import com.ondo.retail.order.domain.OrderGroup;
import com.ondo.retail.order.dto.PaymentTerm;
import com.ondo.retail.order.dto.PlaceOrderRequest;
import com.ondo.retail.order.dto.ReceiveMethod;
import com.ondo.retail.order.dto.WholesaleOrderCommand;
import com.ondo.retail.order.dto.WholesaleOrderReceipt;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 접수 대기함 쓰기 (MUL-139).
 *
 * <p>여기서 보는 것은 셋이다.
 *
 * <ul>
 *   <li>주문서를 열 때 <b>대기함 줄이 같이 생기는가</b> — 실패했을 때가 아니라
 *   <li>같은 멱등키로 다시 와도 <b>줄이 늘지 않고 기한이 밀리지 않는가</b>
 *   <li>동기 호출 결과에 따라 <b>세 갈래로 갈리는가</b> — 안 될 일을 계속 두드리면 안 된다
 * </ul>
 *
 * <p>시드의 봄봄상회(1번)를 쓴다.
 */
@SpringBootTest
@Transactional
class OrderDispatchWriterTest {

    private static final long 소매처 = 1L;
    private static final long 무드온 = 101L;
    private static final long 라온 = 102L;

    @Autowired OrderGroupWriter writer;
    @Autowired OrderDispatchRepository dispatchRepository;

    @Test
    @DisplayName("주문서를 열면 도매처마다 대기함 줄이 같이 생긴다")
    void 주문서와_함께_대기함이_쓰인다() {
        OrderGroup group = writer.open(소매처, "key-dispatch-1", 요청(), 명령_둘());

        List<OrderDispatch> rows = dispatchRepository.findByOrderGroupId(group.getId());

        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(OrderDispatch::isPending);
        assertThat(rows).extracting(OrderDispatch::getWholesalerId)
                .containsExactlyInAnyOrder(무드온, 라온);
    }

    @Test
    @DisplayName("굳혀 둔 명령이 그대로 읽힌다 — 다시 보낼 때 재조립하지 않는다")
    void 명령이_그대로_보존된다() {
        OrderGroup group = writer.open(소매처, "key-dispatch-2", 요청(), 명령_둘());

        OrderDispatch row = dispatchRepository
                .findByOrderGroupIdAndWholesalerId(group.getId(), 무드온).orElseThrow();

        assertThat(row.getPayload().wholesalerId()).isEqualTo(무드온);
        assertThat(row.getPayload().items()).hasSize(1);
        assertThat(row.getPayload().items().getFirst().expectedUnitPrice()).isEqualTo(12500);
    }

    @Test
    @DisplayName("같은 멱등키로 다시 와도 줄이 늘지 않고 기한도 밀리지 않는다")
    void 같은_열쇠는_대기함을_늘리지_않는다() {
        OrderGroup first = writer.open(소매처, "key-dispatch-3", 요청(), 명령_둘());
        var 처음기한 = dispatchRepository
                .findByOrderGroupIdAndWholesalerId(first.getId(), 무드온).orElseThrow()
                .getExpiresAt();

        writer.open(소매처, "key-dispatch-3", 요청(), 명령_둘());

        List<OrderDispatch> rows = dispatchRepository.findByOrderGroupId(first.getId());
        assertThat(rows).hasSize(2);
        // 다시 누를 때마다 기한이 늘어나면 기한을 둔 뜻이 없어진다
        assertThat(rows.stream().filter(r -> r.getWholesalerId().equals(무드온)).findFirst()
                .orElseThrow().getExpiresAt()).isEqualTo(처음기한);
    }

    @Test
    @DisplayName("접수되면 SENT 로 끝난다")
    void 접수되면_끝난다() {
        OrderGroup group = writer.open(소매처, "key-dispatch-4", 요청(), 명령_둘());

        writer.recordAttempt(group.getId(), 무드온, WholesaleOrderReceipt.accepted(9L, 3, 37500));

        assertThat(상태(group, 무드온)).isEqualTo(DispatchStatus.SENT);
    }

    @Test
    @DisplayName("도매가 거절하면 REJECTED 로 끝낸다 — 다시 해도 같은 답이 온다")
    void 사업상_거절은_재시도하지_않는다() {
        OrderGroup group = writer.open(소매처, "key-dispatch-5", 요청(), 명령_둘());

        writer.recordAttempt(group.getId(), 무드온,
                WholesaleOrderReceipt.rejected("INSUFFICIENT_STOCK", "재고가 모자라요"));

        assertThat(상태(group, 무드온)).isEqualTo(DispatchStatus.REJECTED);
    }

    @Test
    @DisplayName("도매가 안 뜨면 PENDING 으로 남아 워커에게 넘어간다")
    void 도매_장애는_대기로_남는다() {
        OrderGroup group = writer.open(소매처, "key-dispatch-6", 요청(), 명령_둘());

        writer.recordAttempt(group.getId(), 무드온,
                WholesaleOrderReceipt.unreachable("UPSTREAM_UNAVAILABLE", "접수하지 못했어요"));

        OrderDispatch row = dispatchRepository
                .findByOrderGroupIdAndWholesalerId(group.getId(), 무드온).orElseThrow();

        assertThat(row.getStatus()).isEqualTo(DispatchStatus.PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).isEqualTo("UPSTREAM_UNAVAILABLE");
    }

    // ── 거들기 ─────────────────────────────────────────────────

    private DispatchStatus 상태(OrderGroup group, long wholesalerId) {
        return dispatchRepository
                .findByOrderGroupIdAndWholesalerId(group.getId(), wholesalerId).orElseThrow()
                .getStatus();
    }

    private static PlaceOrderRequest 요청() {
        return new PlaceOrderRequest(List.of(1L, 2L), null, null, List.of(
                new PlaceOrderRequest.WholesalerOption(무드온, PaymentTerm.CASH, ReceiveMethod.RETAILER),
                new PlaceOrderRequest.WholesalerOption(라온, PaymentTerm.CASH, ReceiveMethod.RETAILER)));
    }

    /** 도매처 둘에 각각 한 줄씩. */
    private static java.util.function.Function<OrderGroup, List<WholesaleOrderCommand>> 명령_둘() {
        return group -> List.of(명령(group, 무드온, 9001L, 3, 12500),
                                명령(group, 라온, 9002L, 2, 31000));
    }

    private static WholesaleOrderCommand 명령(OrderGroup group, long wholesalerId,
                                            long variantId, int qty, int price) {
        return new WholesaleOrderCommand(group.getId(), 소매처, wholesalerId, "봄봄상회", null,
                "CASH", "RETAILER", null, null,
                List.of(new WholesaleOrderCommand.Line(variantId, qty, price)));
    }
}
