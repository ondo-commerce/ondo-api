package com.ondo.wholesale.order.service;

import com.ondo.wholesale.common.time.KstDays;
import com.ondo.wholesale.order.OrderFilterKey;
import com.ondo.wholesale.order.domain.Order;
import com.ondo.wholesale.order.domain.OrderItem;
import com.ondo.wholesale.order.domain.OrderStatus;
import com.ondo.wholesale.order.domain.Partner;
import com.ondo.wholesale.product.domain.Variant;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 주문 목록 검색 조건. 조합은 {@link OrderQueryService}가 한다 (MUL-47). */
final class OrderSpecs {

    private OrderSpecs() {
    }

    static Specification<Order> ownedBy(Long wholesalerId) {
        return (root, query, cb) -> cb.equal(root.get("wholesalerId"), wholesalerId);
    }

    static Specification<Order> idIn(List<Long> ids) {
        return (root, query, cb) -> ids.isEmpty() ? cb.disjunction() : root.get("id").in(ids);
    }

    /**
     * 검색어는 거래처 상호와 라인 상품명 양쪽에 부분 일치(대소문자 무시)로 건다.
     * 검색어가 숫자뿐이면 주문 번호 일치도 OR 로 더한다 (MUL-150).
     */
    static Specification<Order> searchLike(String q) {
        String pattern = "%" + q.toLowerCase() + "%";
        Integer orderNumber = asOrderNumber(q);
        return (root, query, cb) -> {
            Subquery<Long> partner = query.subquery(Long.class);
            Root<Partner> p = partner.from(Partner.class);
            partner.select(cb.literal(1L)).where(
                    cb.equal(p.get("id"), root.get("partnerId")),
                    cb.like(cb.lower(p.get("retailerName")), pattern));

            Subquery<Long> item = query.subquery(Long.class);
            Root<OrderItem> oi = item.from(OrderItem.class);
            Root<Variant> v = item.from(Variant.class);
            item.select(cb.literal(1L)).where(
                    cb.equal(oi.get("order"), root),
                    cb.equal(v.get("id"), oi.get("variantId")),
                    cb.like(cb.lower(v.get("product").get("name")), pattern));

            if (orderNumber == null) {
                return cb.or(cb.exists(partner), cb.exists(item));
            }
            return cb.or(cb.exists(partner), cb.exists(item),
                    cb.equal(root.get("orderNumber"), orderNumber));
        };
    }

    /** 숫자뿐이고 int 범위 안이면 주문 번호로 본다. 넘치면 숫자가 아닌 검색어와 같게 다룬다. */
    private static Integer asOrderNumber(String q) {
        String trimmed = q.trim();
        if (trimmed.isEmpty() || !trimmed.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    /** 정산 탭 전용 — 그 거래처의 확정 주문만 내려간다는 계약을 조건으로 옮긴 것. */
    static Specification<Order> retailerScoped(Long retailerId) {
        return (root, query, cb) -> {
            Subquery<Long> partner = query.subquery(Long.class);
            Root<Partner> p = partner.from(Partner.class);
            partner.select(cb.literal(1L)).where(
                    cb.equal(p.get("id"), root.get("partnerId")),
                    cb.equal(p.get("retailerId"), retailerId));
            return cb.and(cb.exists(partner),
                    cb.equal(root.get("status"), OrderStatus.CONFIRMED));
        };
    }

    /**
     * 표시 상태 칩의 필터. NEW·CANCELLED 는 저장값 비교고, 확정 계열 3값은
     * 출고 합계 서브쿼리로 가른다 — 파생 규칙은 {@link OrderStatusRule}와 같은 어휘다.
     */
    static Specification<Order> derivedStatus(OrderFilterKey filter) {
        return (root, query, cb) -> switch (filter) {
            case ALL -> cb.conjunction();
            case NEW -> cb.equal(root.get("status"), OrderStatus.NEW);
            case CANCELLED -> cb.equal(root.get("status"), OrderStatus.CANCELLED);
            case CONFIRMED -> cb.and(
                    cb.equal(root.get("status"), OrderStatus.CONFIRMED),
                    cb.equal(shippedSum(root, query, cb), 0));
            case PARTIALLY_SHIPPED -> cb.and(
                    cb.equal(root.get("status"), OrderStatus.CONFIRMED),
                    cb.greaterThan(shippedSum(root, query, cb), 0),
                    cb.lessThan(shippedSum(root, query, cb), totalSum(root, query, cb)));
            case SHIPPED -> cb.and(
                    cb.equal(root.get("status"), OrderStatus.CONFIRMED),
                    cb.greaterThan(shippedSum(root, query, cb), 0),
                    cb.equal(shippedSum(root, query, cb), totalSum(root, query, cb)));
        };
    }

    /** 주문일 기간 — KST 로 [from 00:00, to 다음날 00:00). */
    static Specification<Order> orderedBetween(LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            var path = root.<OffsetDateTime>get("orderedAt");
            if (from != null && to != null) {
                return cb.and(
                        cb.greaterThanOrEqualTo(path, KstDays.start(from)),
                        cb.lessThan(path, KstDays.startOfNext(to)));
            }
            if (from != null) {
                return cb.greaterThanOrEqualTo(path, KstDays.start(from));
            }
            return cb.lessThan(path, KstDays.startOfNext(to));
        };
    }

    private static Subquery<Integer> shippedSum(Root<Order> root, CriteriaQuery<?> query,
                                                CriteriaBuilder cb) {
        Subquery<Integer> sub = query.subquery(Integer.class);
        Root<OrderItem> oi = sub.from(OrderItem.class);
        sub.select(cb.coalesce(cb.sum(oi.get("shippedQty")), 0))
                .where(cb.equal(oi.get("order"), root));
        return sub;
    }

    private static Subquery<Integer> totalSum(Root<Order> root, CriteriaQuery<?> query,
                                              CriteriaBuilder cb) {
        Subquery<Integer> sub = query.subquery(Integer.class);
        Root<OrderItem> oi = sub.from(OrderItem.class);
        sub.select(cb.coalesce(cb.sum(oi.get("qty")), 0))
                .where(cb.equal(oi.get("order"), root));
        return sub;
    }
}
