package com.ondo.wholesale.inventory.service;

import com.ondo.wholesale.common.error.ApiException;
import com.ondo.wholesale.common.error.ErrorCode;
import com.ondo.wholesale.common.error.ResourceNotFoundException;
import com.ondo.wholesale.inventory.domain.Inbound;
import com.ondo.wholesale.inventory.domain.InboundIdempotency;
import com.ondo.wholesale.inventory.domain.InboundItem;
import com.ondo.wholesale.inventory.dto.InboundCreateRequest;
import com.ondo.wholesale.inventory.dto.InboundCreatedResponse;
import com.ondo.wholesale.inventory.dto.InboundItemRequest;
import com.ondo.wholesale.inventory.dto.InboundItemResponse;
import com.ondo.wholesale.inventory.repository.InboundIdempotencyRepository;
import com.ondo.wholesale.inventory.repository.InboundItemRepository;
import com.ondo.wholesale.inventory.repository.InboundRepository;
import com.ondo.wholesale.product.domain.Variant;
import com.ondo.wholesale.product.repository.VariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 입고 등록 (MUL-72). 한 트랜잭션 = create 한 번.
 *
 * <p>입고는 중복 등록을 되돌릴 수단이 없어 {@code Idempotency-Key}가 필수다. 같은 키 +
 * 같은 본문은 저장해 둔 첫 응답을 그대로 내리고(200 replay — avgCostAfter 가 시점값이라
 * 재계산으로는 같은 본문을 못 만든다), 같은 키 + 다른 본문은 409 로 거절한다.
 *
 * <p>variant 락은 {@link VariantRepository#lockAllByIdIn}(id 오름차순) 재사용 —
 * 배분(MUL-47)·출고(MUL-49)와 락 순서가 같아 교착이 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class InboundCommandService {

    private final InboundRepository inboundRepository;
    private final InboundItemRepository inboundItemRepository;
    private final InboundIdempotencyRepository idempotencyRepository;
    private final VariantRepository variantRepository;
    private final StockLedger stockLedger;
    private final ObjectMapper objectMapper;

    /** {@code replayed}가 참이면 같은 키 재요청 — 컨트롤러가 201 대신 200 을 내린다. */
    public record InboundResult(InboundCreatedResponse response, boolean replayed) {}

    public InboundResult create(Long wholesalerId, String idempotencyKey, InboundCreateRequest request) {
        validate(request);
        String requestHash = requestHash(request);

        Optional<InboundIdempotency> existing = idempotencyRepository.findById(
                new InboundIdempotency.Key(wholesalerId, idempotencyKey));
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(requestHash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return new InboundResult(readStoredResponse(existing.get()), true);
        }

        Map<Long, Variant> variants = lockOwnedVariants(wholesalerId, request.items());
        Inbound inbound = inboundRepository.save(Inbound.builder()
                .wholesalerId(wholesalerId)
                .receivedAt(request.receivedAt() != null ? request.receivedAt() : OffsetDateTime.now())
                .build());

        // 라인 순서대로 로트 생성·재고 반영 — 응답의 qtyAfter·avgCostAfter 가 순차 누적값이 된다
        List<InboundItemResponse> items = new ArrayList<>();
        for (InboundItemRequest line : request.items()) {
            Variant variant = variants.get(line.variantId());
            InboundItem lot = inboundItemRepository.save(
                    inbound.addLot(line.variantId(), line.qty(), line.unitCost()));
            stockLedger.recordInbound(variant, lot);
            items.add(new InboundItemResponse(
                    lot.getId(), variant.getId(),
                    variant.getProduct().getProductNumber(), variant.getVariantSeq(),
                    line.qty(), money(line.unitCost()), lot.getRemainingQty(),
                    variant.getStockQty(), money(variant.getAvgCost())));
        }

        InboundCreatedResponse response =
                new InboundCreatedResponse(inbound.getId(), inbound.getReceivedAt(), items);
        idempotencyRepository.save(new InboundIdempotency(
                wholesalerId, idempotencyKey, requestHash, inbound.getId(),
                objectMapper.writeValueAsString(response)));
        return new InboundResult(response, false);
    }

    private void validate(InboundCreateRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new ApiException(ErrorCode.INVARIANT_VIOLATED, "입고 라인은 1개 이상이어야 합니다.");
        }
        Set<String> lots = new HashSet<>();
        for (InboundItemRequest item : request.items()) {
            if (item.variantId() == null || item.qty() == null || item.qty() < 1
                    || item.unitCost() == null || item.unitCost().signum() < 0) {
                throw new ApiException(ErrorCode.INVARIANT_VIOLATED,
                        "각 라인은 variantId 와 qty ≥ 1, unitCost ≥ 0 이 필요합니다.");
            }
            // 8500 과 8500.00 은 같은 단가 — 표기 차이가 로트를 가르지 않게 정규화해 비교한다
            if (!lots.add(item.variantId() + ":" + normalized(item.unitCost()))) {
                throw new ApiException(ErrorCode.DUPLICATE_LOT);
            }
        }
    }

    /** 소유·삭제 검증 후 id 오름차순 비관 락 — 남의 것 404, 삭제된 것 409 STATE_CONFLICT. */
    private Map<Long, Variant> lockOwnedVariants(Long wholesalerId, List<InboundItemRequest> items) {
        List<Long> ids = items.stream().map(InboundItemRequest::variantId)
                .distinct().sorted(Comparator.naturalOrder()).toList();
        Map<Long, Variant> variants = variantRepository.lockAllByIdIn(ids).stream()
                .collect(Collectors.toMap(Variant::getId, Function.identity()));
        for (Long id : ids) {
            Variant variant = variants.get(id);
            if (variant == null || !variant.getProduct().getWholesalerId().equals(wholesalerId)) {
                throw new ResourceNotFoundException("옵션이 없거나 접근할 수 없습니다.");
            }
            if (!variant.isAlive()) {
                throw new ApiException(ErrorCode.STATE_CONFLICT, "삭제된 옵션에는 입고할 수 없습니다.");
            }
        }
        return variants;
    }

    /** 고정 필드 순서 직렬화의 SHA-256 — "같은 본문"의 지문. 라인 순서는 의미가 있어 보존한다. */
    private String requestHash(InboundCreateRequest request) {
        StringBuilder canonical = new StringBuilder("receivedAt=");
        if (request.receivedAt() != null) {
            canonical.append(request.receivedAt().toInstant());
        }
        for (InboundItemRequest item : request.items()) {
            canonical.append('|').append(item.variantId())
                    .append(':').append(item.qty())
                    .append(':').append(normalized(item.unitCost()));
        }
        return sha256Hex(canonical.toString());
    }

    private InboundCreatedResponse readStoredResponse(InboundIdempotency idempotency) {
        return objectMapper.readValue(idempotency.getResponseBody(), InboundCreatedResponse.class);
    }

    private static String normalized(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** 응답 금액은 계약대로 소수 2자리 — 저장(scale 6)과 별개다. */
    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 은 JVM 표준 알고리즘이다", e);
        }
    }
}
