package com.ondo.wholesale.backorder;

import com.ondo.wholesale.backorder.dto.ExpectedInboundRequest;
import com.ondo.wholesale.backorder.dto.ExpectedInboundResponse;
import com.ondo.wholesale.common.error.ApiException;
import com.ondo.wholesale.common.error.ResourceNotFoundException;
import com.ondo.wholesale.retailnotification.RetailNotificationWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 예상 입고일 등록 (MUL-48). SKU 에 붙는 값이라 미송과 독립 — 값 2개를 통째로 대체한다.
 *
 * <p>variant 엔티티는 product 패키지 소유라 만지지 않고 jdbc UPDATE 로 컬럼만 바꾼다 —
 * retailgateway 가 SQL 로만 읽는 것과 같은 경계다. 과거 날짜는 막지 않는다(계약 명시,
 * 이미 들어왔는데 기록만 늦는 경우가 실제로 있다). 이력은 남지 않는다 — 최신값 하나.
 *
 * <p>날짜가 바뀌면 그 SKU 를 기다리는 소매처에게 알림을 남긴다 (MUL-155). 같은 도매 DB ·
 * 같은 트랜잭션이라 서버 간 전달이 없고, 소매가 죽어 있어도 이 PUT 은 영향을 안 받는다.
 * 설계: docs/design/notification.md
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ExpectedInboundService {

    private static final int MAX_REASON_LENGTH = 200;

    private final JdbcClient jdbc;
    private final RetailNotificationWriter retailNotifications;

    public ExpectedInboundResponse register(Long wholesalerId, Long variantId,
                                            ExpectedInboundRequest request) {
        if (request.expectedInboundReason() != null
                && request.expectedInboundReason().length() > MAX_REASON_LENGTH) {
            throw ApiException.validationFailed("expectedInboundReason",
                    "사유는 최대 " + MAX_REASON_LENGTH + "자다.");
        }
        // null 날짜 = 해제 — 날짜 없이 사유만 남는 상태는 계약에 없어 사유도 함께 지운다
        LocalDate date = request.expectedInboundDate();
        String reason = (date == null) ? null : request.expectedInboundReason();

        // 바뀌기 전 날짜. 행을 잠가 같은 SKU 의 동시 PUT 둘이 같은 "전" 값을 보지 않게 한다 —
        // 안 잠그면 둘 다 "미정 → X" 로 알림을 낸다
        Optional<LocalDate> before = jdbc.sql("""
                        select v.expected_inbound_date
                        from wholesale.variant v
                        join wholesale.product p on p.id = v.product_id
                        where v.id = :variantId and p.wholesaler_id = :wholesalerId
                          and v.deleted_at is null
                        for update of v
                        """)
                .param("variantId", variantId)
                .param("wholesalerId", wholesalerId)
                .query((rs, rowNum) -> Optional.ofNullable(rs.getObject("expected_inbound_date", LocalDate.class)))
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("SKU 가 없거나 접근할 수 없습니다."));

        int updated = jdbc.sql("""
                        update wholesale.variant v
                        set expected_inbound_date   = cast(:date as date),
                            expected_inbound_reason = cast(:reason as varchar),
                            updated_at = now()
                        from wholesale.product p
                        where v.id = :variantId and p.id = v.product_id
                          and p.wholesaler_id = :wholesalerId and v.deleted_at is null
                        """)
                .param("date", date)
                .param("reason", reason)
                .param("variantId", variantId)
                .param("wholesalerId", wholesalerId)
                .update();
        if (updated == 0) {
            throw new ResourceNotFoundException("SKU 가 없거나 접근할 수 없습니다.");
        }

        retailNotifications.backorderDateChanged(variantId, before.orElse(null), date);

        return jdbc.sql("""
                        select p.product_number, v.variant_seq,
                               v.expected_inbound_date, v.expected_inbound_reason
                        from wholesale.variant v
                        join wholesale.product p on p.id = v.product_id
                        where v.id = :variantId
                        """)
                .param("variantId", variantId)
                .query((rs, rowNum) -> new ExpectedInboundResponse(
                        variantId, rs.getInt("product_number"), rs.getInt("variant_seq"),
                        rs.getObject("expected_inbound_date", LocalDate.class),
                        rs.getString("expected_inbound_reason")))
                .single();
    }
}
