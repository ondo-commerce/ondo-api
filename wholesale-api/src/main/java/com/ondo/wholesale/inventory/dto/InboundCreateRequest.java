package com.ondo.wholesale.inventory.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 입고 등록 요청 (api-lite/03_재고/POST_inbounds.md). {@code Idempotency-Key} 헤더 필수.
 *
 * <p>같은 {@code variantId}를 여러 줄에 넣을 수 있다 — 단가가 다르면 다른 로트다.
 * {@code (variantId, unitCost)}가 완전히 같은 중복만 거절(400 DUPLICATE_LOT).
 *
 * <p>{@code receivedAt}은 보낸 오프셋 그대로 보존한다 — 기본 역직렬화가 UTC 로 바꿔
 * 응답이 요청과 다른 표기로 나가는 것을 막는다.
 */
public record InboundCreateRequest(
        @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        OffsetDateTime receivedAt,
        List<InboundItemRequest> items) {}
