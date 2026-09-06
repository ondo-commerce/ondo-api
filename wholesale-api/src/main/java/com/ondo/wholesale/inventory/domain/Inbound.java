package com.ondo.wholesale.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 입고 헤더 — 라인(로트 {@link InboundItem}) N건을 거느리는 애그리거트 뿌리 (MUL-72).
 *
 * <p>라인 저장은 cascade 가 아니라 서비스가 라인 순서대로 한 건씩 한다 — 응답의
 * qtyAfter·avgCostAfter 가 "이 라인 반영 직후" 값이라 로트 생성과 재고 반영이
 * 라인 단위로 맞물려야 해서다.
 */
@Entity
@Table(name = "inbound", schema = "wholesale")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inbound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wholesaler_id", nullable = false, updatable = false)
    private Long wholesalerId;

    /** 입고 시각 — 계약상 생략 가능이라 서버 현재시각 보정은 서비스가 한다. */
    @Column(name = "received_at", nullable = false, updatable = false)
    private OffsetDateTime receivedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "inbound")
    private List<InboundItem> items = new ArrayList<>();

    @Builder
    private Inbound(Long wholesalerId, OffsetDateTime receivedAt) {
        this.wholesalerId = wholesalerId;
        this.receivedAt = receivedAt;
    }

    /** 로트 한 건을 헤더에 붙여 만든다 — 저장은 호출부가 라인 순서대로 한다. */
    public InboundItem addLot(Long variantId, int qty, BigDecimal unitCost) {
        InboundItem lot = new InboundItem(this, variantId, qty, unitCost);
        items.add(lot);
        return lot;
    }

    public List<InboundItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
