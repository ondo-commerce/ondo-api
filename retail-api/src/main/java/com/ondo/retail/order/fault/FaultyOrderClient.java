package com.ondo.retail.order.fault;

import com.ondo.retail.order.OrderClient;
import com.ondo.retail.order.dto.OrderView;
import com.ondo.retail.order.dto.WholesaleOrderCommand;
import com.ondo.retail.order.dto.WholesaleOrderReceipt;
import com.ondo.retail.wholesale.order.WholesaleOrderAdapter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 도매 장애를 흉내 내는 스위치 (MUL-142).
 *
 * <p><b>왜 서버를 내리지 않고 주입하나</b> — 내리면 0% 와 100% 만 만들 수 있다. 50% 는
 * 도매가 살아는 있는데 불안정한 상태이고, 실제 장애는 그 모양인 경우가 많다. 실패율을
 * 숫자로 주면 같은 조건을 몇 번이고 다시 만들 수 있다 — 정책 셋을 비교하려면 조건이
 * 같아야 한다.
 *
 * <p><b>배포에는 이 빈이 아예 안 생긴다.</b> {@code @Profile("!deploy")} 라 dev·prod
 * 어느 쪽에도 올라가지 않는다. 설정 실수로 운영에서 주문이 실패하는 일은 없다.
 *
 * <p>실패를 만들 때 HTTP 를 아예 안 부르고 {@code unreachable} 을 돌려준다. 진짜 연결
 * 실패와 같은 결과다 — 재시도 기계가 이걸 어떻게 다루는지가 재려는 것이고, HTTP 클라이언트
 * 동작은 {@code WholesaleOrderAdapterTest} 가 따로 본다.
 */
@Slf4j
@Primary
@Component
@Profile("!deploy")
public class FaultyOrderClient implements OrderClient {

    private final OrderClient delegate;
    private final FaultSwitch faults;

    /**
     * 진짜 어댑터를 구체 타입으로 받는다. {@code OrderClient} 로 받으면 자기 자신이
     * {@code @Primary} 라 스스로를 주입받으려 든다.
     */
    public FaultyOrderClient(WholesaleOrderAdapter delegate, FaultSwitch faults) {
        this.delegate = delegate;
        this.faults = faults;
    }

    @Override
    public WholesaleOrderReceipt place(WholesaleOrderCommand command) {
        double rate = faults.failRate();
        if (rate > 0 && ThreadLocalRandom.current().nextDouble() < rate) {
            long n = faults.countBlocked();
            log.debug("장애 주입으로 접수를 막았다. 누적={} 도매처={}", n, command.wholesalerId());
            return WholesaleOrderReceipt.unreachable("UPSTREAM_UNAVAILABLE",
                    "도매처에 접수하지 못했어요. 다시 시도하고 있어요");
        }
        return delegate.place(command);
    }

    /** 조회는 막지 않는다. 주문 접수만 흔들어야 측정이 깨끗하다. */
    @Override
    public List<OrderView> findOrders(Long retailerId, List<Long> retailOrderIds) {
        return delegate.findOrders(retailerId, retailOrderIds);
    }
}
