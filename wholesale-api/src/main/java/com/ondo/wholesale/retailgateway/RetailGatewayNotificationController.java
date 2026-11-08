package com.ondo.wholesale.retailgateway;

import com.ondo.wholesale.common.error.ApiException;
import com.ondo.wholesale.common.response.ApiResponse;
import com.ondo.wholesale.retailgateway.dto.RetailNotificationReadRequest;
import com.ondo.wholesale.retailgateway.dto.RetailNotificationReadResponse;
import com.ondo.wholesale.retailgateway.dto.RetailNotificationResponse;
import com.ondo.wholesale.retailgateway.dto.RetailNotificationUnreadResponse;
import com.ondo.wholesale.retailnotification.RetailNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 소매 알림 (MUL-155) — 소매 백엔드가 부른다. 설계: docs/design/notification.md
 *
 * <p>소매처에게 가는 알림은 도매 DB 에 있다. 알림을 만드는 사건이 전부 도매 트랜잭션이라서다.
 * 소매의 {@code /api/retail/notifications*} 3 op 는 여기를 부르는 프록시다.
 *
 * <p>프론트 계약(커서 문자열, 에러 필드)은 소매 것이라 소매가 검증하고 커서를 만든다. 여기는
 * keyset 의 두 값을 그대로 받는다. 시각을 epoch 마이크로초로 받는 건 ISO 문자열의 {@code +} 가
 * 쿼리에서 공백으로 바뀌는 사고를 피하려는 것이다 — Postgres timestamptz 정밀도도 마이크로초다.
 */
@Tag(name = "08 소매접점")
@RestController
@RequestMapping("/api/retail-gateway/notifications")
@RequiredArgsConstructor
public class RetailGatewayNotificationController {

    /** 소매가 다음 장 여부를 알려고 size(≤100) + 1 로 부른다. */
    private static final int MAX_LIMIT = 101;
    private static final int MAX_IDS = 100;

    private final RetailNotificationService service;

    @Operation(summary = "소매 알림 목록 (소매 백엔드 → 도매)", description = """
            그 소매처 것만, `createdAt desc, id desc`. keyset — `beforeMicros`(epoch µs)·`beforeId` 는
            직전 장 마지막 행 값이고 둘이 같이 온다. 첫 장은 둘 다 생략.

            에러: 400 `VALIDATION_FAILED` (`limit` 범위 밖, keyset 한쪽만 옴)""")
    @GetMapping
    public ApiResponse<List<RetailNotificationResponse>> notifications(
            @RequestParam Long retailerId,
            @RequestParam(defaultValue = "21") int limit,
            @RequestParam(required = false) Long beforeMicros,
            @RequestParam(required = false) Long beforeId) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.validationFailed("limit", "limit 는 1 이상 " + MAX_LIMIT + " 이하다.");
        }
        if ((beforeMicros == null) != (beforeId == null)) {
            throw ApiException.validationFailed("beforeId", "beforeMicros 와 beforeId 는 같이 온다.");
        }
        OffsetDateTime before = beforeMicros == null ? null : fromMicros(beforeMicros);
        return ApiResponse.of(service.page(retailerId, before, beforeId, limit));
    }

    @Operation(summary = "소매 알림 배지 (소매 백엔드 → 도매)",
               description = "안 읽은 수와 가장 최근 알림 id(없으면 null).")
    @GetMapping("/unread-count")
    public RetailNotificationUnreadResponse unreadCount(@RequestParam Long retailerId) {
        return service.unread(retailerId);
    }

    @Operation(summary = "소매 알림 읽음 (소매 백엔드 → 도매)", description = """
            `{retailerId, ids}` 고른 것만(멱등, 없는·남의 id 무시) 또는
            `{retailerId, all: true, expectedUnread}` 안 읽음 전부. 바뀐 수가 다르면 롤백하고 409.

            에러: 400 `VALIDATION_FAILED` / 409 `UNREAD_CHANGED`""")
    @PostMapping("/read")
    public RetailNotificationReadResponse read(@RequestBody RetailNotificationReadRequest request) {
        if (request.retailerId() == null) {
            throw ApiException.validationFailed("retailerId", "retailerId 가 필요하다.");
        }
        boolean byIds = request.ids() != null;
        boolean byAll = Boolean.TRUE.equals(request.all());
        if (byIds == byAll) {
            throw ApiException.validationFailed("ids", "ids 와 all 중 하나만 온다.");
        }
        if (byIds) {
            if (request.ids().isEmpty() || request.ids().size() > MAX_IDS) {
                throw ApiException.validationFailed("ids", "ids 는 1~" + MAX_IDS + "개다.");
            }
            return new RetailNotificationReadResponse(service.markRead(request.retailerId(), request.ids()));
        }
        if (request.expectedUnread() == null || request.expectedUnread() < 1) {
            throw ApiException.validationFailed("expectedUnread", "expectedUnread 는 1 이상이다.");
        }
        return new RetailNotificationReadResponse(
                service.markAllRead(request.retailerId(), request.expectedUnread()));
    }

    private static OffsetDateTime fromMicros(long micros) {
        Instant instant = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                Math.floorMod(micros, 1_000_000L) * 1_000L);
        return instant.atOffset(ZoneOffset.UTC);
    }
}
