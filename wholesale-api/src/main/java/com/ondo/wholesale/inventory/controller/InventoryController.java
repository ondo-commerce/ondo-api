package com.ondo.wholesale.inventory.controller;

import com.ondo.wholesale.common.response.ApiResponse;
import com.ondo.wholesale.inventory.dto.InboundCreateRequest;
import com.ondo.wholesale.inventory.dto.InboundCreatedResponse;
import com.ondo.wholesale.inventory.dto.StockAdjustmentRequest;
import com.ondo.wholesale.inventory.dto.StockMovementResponse;
import com.ondo.wholesale.inventory.service.InboundCommandService;
import com.ondo.wholesale.inventory.service.StockAdjustmentService;
import com.ondo.wholesale.security.WholesalePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 재고 API (MUL-72) — 원본 계약: api-lite/03_재고. 조정·이력은 아직 계약 스텁이다.
 */
@Tag(name = "03 재고")
@RestController
@RequestMapping("/api/wholesale")
@RequiredArgsConstructor
public class InventoryController {

    private final InboundCommandService inboundCommandService;
    private final StockAdjustmentService stockAdjustmentService;

    @Operation(summary = "입고 등록 (Idempotency-Key 필수)", description = """
            입고 헤더 1건 + 라인(로트) N건을 등록하고 재고를 올린다. 단가가 다르면 다른 로트 —
            같은 `variantId`를 여러 줄에 넣을 수 있다. 같은 키 재요청은 200 + 동일 본문(멱등),
            같은 키에 다른 body 는 409 `IDEMPOTENCY_KEY_REUSED`.

            에러: 400 `DUPLICATE_LOT` · `INVARIANT_VIOLATED` / 404 `RESOURCE_NOT_FOUND` /
            409 `IDEMPOTENCY_KEY_REUSED` · `STATE_CONFLICT`""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "첫 등록", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "같은 키 재요청(replay) — 첫 응답과 동일 본문",
                    useReturnTypeSchema = true)})
    @PostMapping("/inbounds")
    public ResponseEntity<InboundCreatedResponse> createInbound(
            @AuthenticationPrincipal WholesalePrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InboundCreateRequest request) {
        InboundCommandService.InboundResult result =
                inboundCommandService.create(principal.wholesalerId(), idempotencyKey, request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result.response());
    }

    @Operation(summary = "재고 조정 (실사 반영)", description = """
            부호 포함 증감(`qtyChange`)을 받는다 — 절대값은 남의 변동을 덮어쓴다. `0`은 거절.
            변동 이력에 ADJUST 한 줄이 남고, 응답이 그 이력 항목이다.

            에러: 400 `INVARIANT_VIOLATED` / 404 `RESOURCE_NOT_FOUND` /
            409 `STOCK_BELOW_ZERO` · `STOCK_BELOW_ALLOCATED`(errors[].data 에 취소 후보 포장 목록)""")
    @PostMapping("/variants/{variantId}/stock-adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    public StockMovementResponse adjustStock(@AuthenticationPrincipal WholesalePrincipal principal,
                                             @PathVariable Long variantId,
                                             @RequestBody StockAdjustmentRequest request) {
        return stockAdjustmentService.adjust(principal.wholesalerId(), variantId, request);
    }

    @Operation(summary = "재고 변동 이력", description = """
            SKU 하나의 재고 변동 원장, 시간 역순. 원장은 append-only — 수정·삭제 경로가 없고
            정정은 반대부호 조정으로 한다.

            에러: 400 `VALIDATION_FAILED` / 404 `RESOURCE_NOT_FOUND`""")
    @GetMapping("/variants/{variantId}/stock-movements")
    public ApiResponse<List<StockMovementResponse>> stockMovements(
            @PathVariable Long variantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String type,
            @RequestParam(required = false, defaultValue = "0") Integer page,
            @RequestParam(required = false, defaultValue = "20") Integer size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.paged(
                InventoryStubExamples.movements(),
                new ApiResponse.PageMeta(0, 20, 41, 3));
    }
}
