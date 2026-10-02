package com.ondo.retail.order;

import com.ondo.retail.order.domain.OrderDispatch;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface OrderDispatchRepository extends JpaRepository<OrderDispatch, Long> {

    List<OrderDispatch> findByOrderGroupId(Long orderGroupId);

    Optional<OrderDispatch> findByOrderGroupIdAndWholesalerId(Long orderGroupId, Long wholesalerId);

    /**
     * 지금 보낼 차례가 된 줄을 집는다.
     *
     * <p><b>{@code SKIP LOCKED} 가 핵심이다.</b> 소매 태스크가 여럿이면 워커도 여럿이고,
     * 그냥 두면 같은 줄을 동시에 집어 같은 주문을 두 번 보낸다. 도매의 멱등 제약이
     * 막아주긴 하지만 쓸데없는 호출이고, 애초에 나눠 가지는 게 맞다.
     *
     * <p>보통의 잠금은 남이 놓을 때까지 <b>기다린다</b> — 그러면 태스크 둘이 줄 서서
     * 한 줄씩 처리하게 돼 워커를 늘린 의미가 없다. {@code SKIP LOCKED} 는 잠긴 줄을
     * 건너뛰고 다음 걸 가져가므로 서로 다른 구간을 동시에 처리한다.
     *
     * <p>한 바퀴에 {@code limit} 만큼만 집는다. 도매가 막 살아난 순간 밀린 것을 통째로
     * 쏟아부으면 다시 쓰러뜨린다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))  // -2 = SKIP LOCKED
    @Query("""
            SELECT d FROM OrderDispatch d
            WHERE d.status = com.ondo.retail.order.domain.DispatchStatus.PENDING
              AND d.nextAttemptAt <= :now
            ORDER BY d.nextAttemptAt
            """)
    List<OrderDispatch> findDue(@Param("now") OffsetDateTime now, Limit limit);
}
