package com.ondo.retail.order.fault;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 장애 주입 비율을 들고 있는다 (MUL-142).
 *
 * <p><b>왜 돌아가는 중에 바꿀 수 있어야 하나</b> — 복구 시간을 재려면 "도매가 살아나는
 * 순간" 이 있어야 한다. 앱을 다시 띄우면 그 순간이 재시작 시간에 묻히고, 워커가 들고
 * 있던 상태도 매번 달라진다. 값 하나만 바꾸면 도매가 정확히 그 시각에 살아난 것과 같다.
 *
 * <p>배포에는 이 빈이 안 생긴다({@code @Profile("!deploy")}).
 */
@Slf4j
@Component
@Profile("!deploy")
public class FaultSwitch {

    private final AtomicReference<Double> failRate;

    /** 주입으로 막은 접수 호출 누적 수. 측정이 이 값을 읽는다. */
    private final AtomicLong blocked = new AtomicLong();

    public FaultSwitch(@Value("${ondo.fault.wholesale-order.fail-rate:0}") double initial) {
        this.failRate = new AtomicReference<>(clamp(initial));
    }

    public double failRate() {
        return failRate.get();
    }

    public void set(double rate) {
        double old = failRate.getAndSet(clamp(rate));
        log.info("도매 접수 장애 주입 비율을 바꿨다. {} → {}", old, failRate.get());
    }

    public long blockedCount() {
        return blocked.get();
    }

    public long countBlocked() {
        return blocked.incrementAndGet();
    }

    private static double clamp(double rate) {
        return Math.max(0, Math.min(1, rate));
    }
}
