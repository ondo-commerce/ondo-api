package com.ondo.wholesale.inventory.service;

import com.ondo.wholesale.common.error.ApiException;
import com.ondo.wholesale.common.error.ErrorCode;
import com.ondo.wholesale.inventory.domain.StockMovementType;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 재고 변동 이력 쿼리 파라미터의 형식 규칙 단위 검증 (MUL-72) — 주문 목록 관행 복제.
 * KST 경계·페이징의 실제 동작은 StockMovementListIntegrationTest 가 본다.
 */
class StockMovementListQueryTest {

    @Test
    void 지원하지_않는_정렬은_400() {
        assertThat(of(null, 0, 20, "createdAt,desc").sort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        검증_실패한다(() -> of(null, 0, 20, "qtyChange,desc"), "sort");
        검증_실패한다(() -> of(null, 0, 20, "createdAt,sideways"), "sort");
    }

    @Test
    void from이_to보다_뒤면_400() {
        LocalDate day = LocalDate.of(2026, 8, 19);
        assertThat(StockMovementListQuery.of(null, day, day, 0, 20, null).from()).isEqualTo(day);
        검증_실패한다(() -> StockMovementListQuery.of(null, day.plusDays(1), day, 0, 20, null), "from");
    }

    @Test
    void 미정의_type은_400() {
        assertThat(of("IN,ADJUST", 0, 20, null).types())
                .containsExactly(StockMovementType.IN, StockMovementType.ADJUST);
        검증_실패한다(() -> of("RETURN", 0, 20, null), "type");
        검증_실패한다(() -> of("IN,", 0, 20, null), "type");
    }

    @Test
    void size_100_초과는_400() {
        assertThat(of(null, 0, 100, null).size()).isEqualTo(100);
        검증_실패한다(() -> of(null, 0, 101, null), "size");
    }

    private StockMovementListQuery of(String type, int page, int size, String sort) {
        return StockMovementListQuery.of(type, null, null, page, size, sort);
    }

    private void 검증_실패한다(ThrowingCallable call, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(ex.errors()).anySatisfy(err -> assertThat(err.field()).isEqualTo(field));
        });
    }
}
