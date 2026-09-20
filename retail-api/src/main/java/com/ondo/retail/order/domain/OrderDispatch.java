package com.ondo.retail.order.domain;

import com.ondo.retail.order.dto.WholesaleOrderCommand;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * 도매처 한 곳에 넣을 주문의 접수 대기함 줄 (MUL-139).
 *
 * <p><b>실패했을 때 만드는 게 아니라 주문서와 같이 만든다.</b> 도매를 부르는 도중에
 * 태스크가 내려가면 "실패를 감지해 기록" 할 주체가 없어 주문 의사가 통째로 사라진다.
 * 주문서와 한 트랜잭션에 쓰면 둘 다 되거나 둘 다 안 된다.
 *
 * <p>줄 하나가 (주문서 · 도매처) 하나다. 도매의 원자성 단위가 도매처별 주문이고
 * 부분 성공이 정상이라, 재시도 단위도 주문 전체가 아니라 여기서 갈린다.
 *
 * <p>{@code payload} 를 굳혀 두는 이유 — 다시 보낼 때 명령을 새로 조립하면 그 사이
 * 바뀐 값이 섞인다. 장바구니 줄은 접수되면 지워지고 판매가는 도매가 고칠 수 있다.
 * 사용자가 승인한 그 내용으로 보내야 한다.
 */
@Entity
@Table(name = "order_dispatch", schema = "retail")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderDispatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_group_id", nullable = false, updatable = false)
    private Long orderGroupId;

    @Column(name = "wholesaler_id", nullable = false, updatable = false)
    private Long wholesalerId;

    /** 도매에 보낼 명령. 부를 때의 값으로 굳힌다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private WholesaleOrderCommand payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DispatchStatus status;

    @Column(nullable = false)
    private int attempts;

    /** 이 시각 전에는 워커가 집지 않는다. 간격 정책이 이 값을 민다. */
    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt;

    /**
     * 이 시각을 넘기면 접수하지 않는다.
     *
     * <p>메시지는 늦게 처리돼도 가치가 남지만 주문은 아니다. 실패를 본 소매처가 다른
     * 도매에서 이미 샀을 수 있고, 그 뒤 자동 접수되면 같은 물건을 두 번 사게 된다.
     */
    @Column(name = "expires_at", nullable = false, updatable = false)
    private OffsetDateTime expiresAt;

    /** 왜 못 갔는지. 소매처가 물으면 답할 수 있어야 한다. */
    @Column(name = "last_error")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Builder
    private OrderDispatch(Long orderGroupId, Long wholesalerId, WholesaleOrderCommand payload,
                          OffsetDateTime nextAttemptAt, OffsetDateTime expiresAt) {
        this.orderGroupId = orderGroupId;
        this.wholesalerId = wholesalerId;
        this.payload = payload;
        this.status = DispatchStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = nextAttemptAt;
        this.expiresAt = expiresAt;
    }

    /** 도매가 받았다. 409(이미 있음)도 여기로 온다 — 주문은 도매 장부에 있다. */
    public void markSent() {
        this.status = DispatchStatus.SENT;
        this.lastError = null;
    }

    /** 도매가 거절했다. 다시 해도 같은 답이 오므로 여기서 끝낸다. */
    public void markRejected(String reason) {
        this.status = DispatchStatus.REJECTED;
        this.lastError = reason;
    }

    /**
     * 이번엔 못 보냈다. 다음 시각까지 미룬다.
     *
     * <p>시도 횟수는 여기서만 오른다 — 집어서 실제로 불러봤다는 뜻이다.
     */
    public void retryAt(OffsetDateTime next, String error) {
        this.attempts++;
        this.nextAttemptAt = next;
        this.lastError = error;
    }

    /**
     * 워커가 집어 가는 동안 다른 워커가 못 건드리게 잠깐 뒤로 민다 (MUL-140).
     *
     * <p><b>시도 횟수를 올리지 않는다.</b> 아직 불러보지 않았다 — 집기만 한 것이다.
     * 여기서 올리면 워커가 호출 전에 죽을 때마다 횟수가 깎여 기회를 잃는다.
     *
     * <p>이 값이 재시도 간격을 늘리지는 않는다. 호출이 끝나면 결과에 따라 진짜 다음
     * 시각으로 다시 쓴다. 워커가 호출 도중에 죽은 경우에만 이만큼 기다렸다 다시 잡힌다.
     */
    public void holdUntil(OffsetDateTime until) {
        this.nextAttemptAt = until;
    }

    /** 기한을 넘겼거나 시도 횟수를 다 썼다. */
    public void markExpired(String error) {
        this.status = DispatchStatus.EXPIRED;
        this.lastError = error;
    }

    /** 소매처가 기다리지 않기로 했다. */
    public void markCancelled() {
        this.status = DispatchStatus.CANCELLED;
    }

    public boolean isExpiredAt(OffsetDateTime now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isPending() {
        return status == DispatchStatus.PENDING;
    }
}
