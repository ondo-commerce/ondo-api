package com.ondo.wholesale.inventory.service;

import com.ondo.wholesale.common.error.ApiException;
import com.ondo.wholesale.common.web.SortParser;
import com.ondo.wholesale.inventory.domain.StockMovementType;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 재고 변동 이력 쿼리 파라미터 — 형식 검증과 정렬 파싱을 HTTP 계층에서 끝낸 값 (MUL-72).
 * 주문 목록({@code OrderListQuery}) 관행 복제.
 *
 * <p>{@code type}은 계약이 콤마 다중값 문자열이라 여기서 {@link StockMovementType}
 * 리스트로 파싱하고 미정의 값을 400 으로 거른다. 빈 리스트 = 필터 없음.
 */
public record StockMovementListQuery(List<StockMovementType> types, LocalDate from, LocalDate to,
                                     int page, int size, Sort sort) {

    /** 정렬 화이트리스트 — 계약에 없는 키는 400. */
    private static final Map<String, String> SORT_KEYS = Map.of("createdAt", "createdAt");

    private static final int MAX_PAGE_SIZE = 100;

    public static StockMovementListQuery of(String type, LocalDate from, LocalDate to,
                                            int page, int size, String sort) {
        if (size > MAX_PAGE_SIZE) {
            throw ApiException.validationFailed("size", "size 는 최대 " + MAX_PAGE_SIZE + " 이다.");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.validationFailed("from", "from 이 to 보다 뒤일 수 없다.");
        }
        return new StockMovementListQuery(parseTypes(type), from, to, page, size,
                SortParser.parse(sort, "createdAt,desc", SORT_KEYS));
    }

    private static List<StockMovementType> parseTypes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<StockMovementType> types = new ArrayList<>();
        for (String token : raw.split(",", -1)) {
            try {
                types.add(StockMovementType.valueOf(token.trim()));
            } catch (IllegalArgumentException e) {
                throw ApiException.validationFailed("type", "정의되지 않은 type: " + token.trim());
            }
        }
        return List.copyOf(types);
    }
}
