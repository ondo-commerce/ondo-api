package com.ondo.wholesale.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 입고 멱등 기록 (MUL-72) — 같은 {@code Idempotency-Key} 재요청(replay)에 첫 응답과
 * 똑같은 본문을 내리기 위한 저장소.
 *
 * <p>응답의 avgCostAfter 가 시점값이라 재계산으로는 같은 본문을 만들 수 없다 —
 * 그래서 첫 201 의 data 페이로드 JSON 을 통째로 저장한다. {@code requestHash}는
 * 같은 키에 다른 body 를 보내는 실수(409 IDEMPOTENCY_KEY_REUSED)를 가려내는 지문이다.
 */
@Entity
@Table(name = "inbound_idempotency", schema = "wholesale")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InboundIdempotency {

    @EmbeddedId
    private Key key;

    /** 정렬(고정 필드 순서) 직렬화 SHA-256 hex. */
    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "inbound_id", nullable = false, updatable = false)
    private Long inboundId;

    /** 첫 201 의 data 페이로드 JSON — replay 는 이걸 그대로 내린다. */
    @Column(name = "response_body", nullable = false, updatable = false)
    private String responseBody;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public InboundIdempotency(Long wholesalerId, String idempotencyKey, String requestHash,
                              Long inboundId, String responseBody) {
        this.key = new Key(wholesalerId, idempotencyKey);
        this.requestHash = requestHash;
        this.inboundId = inboundId;
        this.responseBody = responseBody;
    }

    /** 복합 PK (wholesaler_id, idempotency_key) — 키는 도매처 안에서만 유일하면 된다. */
    @Embeddable
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {

        @Column(name = "wholesaler_id", nullable = false)
        private Long wholesalerId;

        @Column(name = "idempotency_key", nullable = false, length = 64)
        private String idempotencyKey;
    }
}
