package com.ondo.retail.order;

import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 지금 쓰는 간격 정책을 들고 있는다 (MUL-142).
 *
 * <p>설정값에서 시작하되 <b>돌아가는 중에 바꿀 수 있다.</b> 이유가 둘이다.
 *
 * <p>첫째, 셋을 비교하려면 <b>같은 조건</b>에서 돌려야 한다. 정책마다 앱을 다시 띄우면
 * 데이터도 워커 상태도 매번 달라져 무엇 때문에 숫자가 달라졌는지 알 수 없다.
 *
 * <p>둘째, 운영에서도 쓸모가 있다. 도매가 오래 불안정할 때 간격을 늘리는 건 배포 없이
 * 지금 해야 하는 일이다.
 */
@Slf4j
@Component
public class RetryPolicySwitch {

    private final AtomicReference<RetryPolicy> current;

    public RetryPolicySwitch(OrderDispatchProperties properties) {
        this.current = new AtomicReference<>(properties.retry().policy());
    }

    public RetryPolicy current() {
        return current.get();
    }

    public void set(RetryPolicy policy) {
        RetryPolicy old = current.getAndSet(policy);
        log.info("재시도 간격 정책을 바꿨다. {} → {}", old, policy);
    }
}
