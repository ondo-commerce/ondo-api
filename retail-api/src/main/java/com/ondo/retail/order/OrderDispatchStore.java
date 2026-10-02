package com.ondo.retail.order;

import com.ondo.retail.order.domain.OrderDispatch;
import com.ondo.retail.order.dto.WholesaleOrderCommand;
import com.ondo.retail.order.dto.WholesaleOrderReceipt;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대기함의 DB 쓰기만 모은다 (MUL-140).
 *
 * <p><b>워커와 나눠 둔 이유가 트랜잭션이다.</b> 두 가지가 겹쳐 있다.
 *
 * <p>첫째, 같은 클래스 안에서 부르면 {@code @Transactional} 이 안 걸린다. 스프링은
 * 프록시로 가로채는데 자기 호출은 프록시를 안 지난다.
 *
 * <p>둘째, <b>도매 호출을 트랜잭션 안에 두면 안 된다.</b> {@code SKIP LOCKED} 로 집은
 * 잠금은 트랜잭션이 끝나야 풀리는데, 그 안에서 HTTP 를 부르면 응답을 기다리는 내내
 * DB 커넥션을 붙들고 있게 된다. 도매가 느릴 때 소매 커넥션 풀이 마른다 —
 * 주문 접수가 {@code @Transactional} 을 일부러 안 쓰는 것과 같은 이유다.
 *
 * <p>그래서 <b>집기 · 부르기 · 적기</b>를 끊는다. 집을 때 다음 시각을 잠깐 뒤로 밀어
 * 다른 워커가 같은 줄을 못 건드리게 해 두고, 호출이 끝나면 결과에 따라 진짜 값으로
 * 다시 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderDispatchStore {

    private final OrderDispatchRepository repository;
    private final OrderDispatchProperties properties;
    private final DispatchCartReturn cartReturn;
    private final RetryPolicySwitch policy;

    /** 워커가 들고 갈 한 건. 엔티티를 트랜잭션 밖으로 내보내지 않으려고 값만 옮긴다. */
    public record Claimed(Long id, Long orderGroupId, Long wholesalerId,
                          int attempts, WholesaleOrderCommand payload) {}

    /**
     * 보낼 차례가 된 줄을 집는다. 짧은 트랜잭션이다.
     *
     * <p>기한을 넘긴 줄은 집지 않고 여기서 {@code EXPIRED} 로 끝낸다. 늦은 접수는
     * 도움이 아니라 사고다 — 소매처가 이미 다른 곳에서 샀을 수 있다.
     */
    @Transactional
    public List<Claimed> claim() {
        OffsetDateTime now = OffsetDateTime.now();
        List<OrderDispatch> rows =
                repository.findDue(now, Limit.of(properties.batchSize()));

        List<Claimed> claimed = new ArrayList<>(rows.size());
        for (OrderDispatch row : rows) {
            if (row.isExpiredAt(now)) {
                giveUp(row, "기한을 넘겨 접수하지 않았다");
                continue;
            }
            if (row.getAttempts() >= properties.retry().maxAttempts()) {
                giveUp(row, "재시도 횟수를 다 썼다. 마지막 오류: " + row.getLastError());
                continue;
            }
            // 부르는 동안 다른 워커가 못 건드리게 잠깐 뒤로 민다. 호출이 끝나면
            // 결과에 따라 진짜 값으로 다시 쓴다
            row.holdUntil(now.plus(properties.claimTimeout()));
            claimed.add(new Claimed(row.getId(), row.getOrderGroupId(), row.getWholesalerId(),
                    row.getAttempts(), row.getPayload()));
        }
        return claimed;
    }

    /**
     * 호출 결과를 적는다. 건마다 짧은 트랜잭션이다.
     *
     * <p>한 건이 실패해도 나머지 결과는 남아야 하므로 배치로 묶지 않는다.
     *
     * @return 다시 시도할 예정이면 true
     */
    @Transactional
    public boolean record(Long dispatchId, WholesaleOrderReceipt receipt) {
        OrderDispatch row = repository.findById(dispatchId).orElse(null);
        if (row == null || !row.isPending()) {
            return false;
        }
        OffsetDateTime now = OffsetDateTime.now();

        if (receipt.accepted()) {
            // 접수된 것만 장바구니에서 영영 빠진다
            row.markSent();
            return false;
        }
        if (!receipt.retryable()) {
            // 재고 없음 · 판매 종료. 다시 해도 같은 답이니 사장님께 돌려드린다
            row.markRejected(receipt.reason());
            cartReturn.restore(row.getPayload(), receipt.reason());
            return false;
        }

        int attempts = row.getAttempts() + 1;
        OffsetDateTime next = now.plus(policy.current().nextDelay(attempts, properties.retry()));

        // 다음 시도가 기한 밖이면 지금 끝낸다. 굳이 한 번 더 집었다가 버릴 이유가 없다
        if (attempts >= properties.retry().maxAttempts() || !next.isBefore(row.getExpiresAt())) {
            giveUp(row, receipt.reason());
            return false;
        }
        row.retryAt(next, receipt.reason());
        return true;
    }

    /**
     * 사장님이 기다리지 않기로 했다 (MUL-141).
     *
     * <p>기다리라고 해놓고 빠져나갈 길이 없으면 안 된다. 동대문은 주문 시점이 중요해서
     * 다른 도매에서 사기로 할 수 있다.
     *
     * @return 정말 취소했으면 true. 이미 접수됐거나 끝난 건이면 false
     */
    @Transactional
    public boolean cancel(Long orderGroupId, Long wholesalerId, Long retailerId) {
        OrderDispatch row = repository
                .findByOrderGroupIdAndWholesalerId(orderGroupId, wholesalerId).orElse(null);

        // 남의 주문을 취소하면 안 된다. 굳혀 둔 명령에 소매처가 들어 있다
        if (row == null || !row.isPending() || !row.getPayload().retailerId().equals(retailerId)) {
            return false;
        }
        row.markCancelled();
        cartReturn.restore(row.getPayload(), "사장님이 대기를 취소했다");
        return true;
    }

    /**
     * 이 주문서의 대기 줄을 전부 접는다 (MUL-141).
     *
     * <p>도매처가 <b>전부</b> 못 받은 경우에 쓴다. 그때는 계약상 "주문서를 안 만든 것"
     * 으로 보고 502 를 내는데, 주문서가 {@code FAILED} 라 내역에도 안 보인다.
     * 그 상태로 서버가 나중에 몰래 넣으면 <b>사용자가 볼 수 없는 주문</b>이 생긴다.
     * 서버는 사용자가 볼 수 있는 주문만 대신 넣는다.
     *
     * <p>장바구니는 건드리지 않는다 — 이 경우엔 애초에 빼지 않았다.
     */
    @Transactional
    public void abandonAll(Long orderGroupId) {
        repository.findByOrderGroupId(orderGroupId).stream()
                .filter(OrderDispatch::isPending)
                .forEach(OrderDispatch::markCancelled);
    }

    /** 서버가 손을 뗀다. 맡았던 물건은 장바구니로 돌려준다. */
    private void giveUp(OrderDispatch row, String reason) {
        row.markExpired(reason);
        cartReturn.restore(row.getPayload(), reason);
        log.info("대기 주문을 포기했다. dispatchId={} 시도={} 사유={}",
                row.getId(), row.getAttempts(), reason);
    }
}
