package com.ondo.wholesale.settlement.service;

import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 지금 쓰는 잠금 전략을 들고 있는다 (MUL-143).
 *
 * <p>설정값에서 시작하되 돌아가는 중에 바꿀 수 있다. 셋을 비교하려면 <b>같은 조건</b>에서
 * 돌려야 하는데, 전략마다 앱을 다시 띄우면 데이터도 상태도 매번 달라진다.
 * 재시도 간격 정책(MUL-140)을 비교할 때와 같은 이유다.
 *
 * <p>기본값은 {@code PESSIMISTIC} 이다. 측정이 끝나고 무엇을 고를지 정할 때까지
 * 운영 동작은 지금 그대로 둔다.
 */
@Slf4j
@Component
public class LedgerWriteStrategySwitch {

    private final AtomicReference<LedgerWriteStrategy> current;

    public LedgerWriteStrategySwitch(
            @Value("${ondo.settlement.ledger.strategy:PESSIMISTIC}") LedgerWriteStrategy initial) {
        this.current = new AtomicReference<>(initial);
    }

    public LedgerWriteStrategy current() {
        return current.get();
    }

    public void set(LedgerWriteStrategy strategy) {
        LedgerWriteStrategy old = current.getAndSet(strategy);
        log.info("미수 원장 쓰기 전략을 바꿨다. {} → {}", old, strategy);
    }
}
