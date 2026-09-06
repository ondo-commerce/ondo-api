package com.ondo.wholesale.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * 재고 변동 원장 한 줄. APPEND-ONLY — 수정·삭제 경로를 두지 않고 정정은
 * 반대부호 ADJUST 행을 새로 쌓는다. 재고(MUL-72)의 입고·조정과 출고(MUL-49)의
 * 출고 확정이 같이 쓰는 부품이라 먼저 매핑해 뒀다.
 *
 * <p>variant 는 상품 애그리거트 밖이라 Long 으로만 든다. ref_type/ref_id 는 변동을
 * 일으킨 원본(INBOUND_ITEM · ORDER …)을 가리키는 다형 참조라 FK 가 없다(V1 주석).
 * qty_after 는 파생값이지만 감사 목적으로 함께 저장한다.
 */
@Entity
@Table(name = "stock_movement", schema = "wholesale")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private Long variantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private StockMovementType type;

    /** 부호 포함 증감. */
    @Column(name = "qty_change", nullable = false, updatable = false)
    private int qtyChange;

    @Column(name = "qty_after", nullable = false, updatable = false)
    private int qtyAfter;

    /** 조정(ADJUST)은 출처가 없어 null 이다 (V7) — 입고 IN·출고 OUT 은 로트를 가리킨다. */
    @Column(name = "ref_type", length = 30, updatable = false)
    private String refType;

    @Column(name = "ref_id", updatable = false)
    private Long refId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private StockMovement(Long variantId, StockMovementType type, int qtyChange, int qtyAfter,
                          String refType, Long refId) {
        this.variantId = variantId;
        this.type = type;
        this.qtyChange = qtyChange;
        this.qtyAfter = qtyAfter;
        this.refType = refType;
        this.refId = refId;
    }
}
