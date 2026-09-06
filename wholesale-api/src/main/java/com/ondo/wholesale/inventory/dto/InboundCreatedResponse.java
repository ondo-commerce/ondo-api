package com.ondo.wholesale.inventory.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 입고 등록 201 응답 — 헤더 1건 + 라인(로트) N건.
 * 같은 키 재요청은 200 + 동일 본문(멱등) — 재고가 두 번 오르지 않는다.
 *
 * <p>{@code receivedAt}의 오프셋 보존은 replay 를 위해서다 — 저장해 둔 응답 JSON 을
 * 다시 읽어 내릴 때 표기가 흔들리면 "동일 본문" 계약이 깨진다.
 */
public record InboundCreatedResponse(
        Long id,
        @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        OffsetDateTime receivedAt,
        List<InboundItemResponse> items) {}
