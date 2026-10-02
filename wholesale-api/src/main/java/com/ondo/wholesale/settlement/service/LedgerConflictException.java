package com.ondo.wholesale.settlement.service;

/**
 * 낙관적 락에서 다른 요청이 먼저 잔액을 바꿨다 (MUL-143).
 *
 * <p>사고가 아니라 <b>예상된 결과</b>다. 부르는 쪽이 트랜잭션을 되돌리고 다시 하면 된다.
 * 되돌려야 하는 이유 — 원장 행은 이미 썼는데 잔액 갱신만 실패했으므로, 그대로 두면
 * 원장과 요약이 어긋난다.
 */
public class LedgerConflictException extends RuntimeException {

    public LedgerConflictException(long partnerId) {
        super("미수 잔액이 그 사이 바뀌었다. partnerId=" + partnerId);
    }
}
