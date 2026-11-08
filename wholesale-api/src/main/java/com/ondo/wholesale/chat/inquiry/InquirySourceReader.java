package com.ondo.wholesale.chat.inquiry;

import com.ondo.wholesale.chat.inquiry.InquiryInput.Backorder;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Listing;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Packing;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Receivable;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Variant;
import com.ondo.wholesale.settlement.service.OverdueCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 판정 원본을 도매 DB 에서 읽는다 (MUL-157, docs/design/chat.md b). 판정은 이 방 쌍을 다시 거르지만, 여기서도
 * 쌍으로 잘라 읽는다 — 남의 행을 메모리에 올릴 이유가 없다.
 *
 * <p>정확 수량(판매가능 수, 미수 금액)은 이 원본과 도매 facts 에만 있다. 소매로 나가는 건 게이트웨이 투영을 지난 값뿐이다.
 *
 * <p>주문 번호는 소매 주문서 id({@code orders.retail_order_id})를 쓴다. 칩·답 글자의 "#번호" 는 소매가 읽는 글이라
 * 도매 내부 id 를 내보내지 않는다. 그래서 소매 주문서가 없는 주문(도매 직접 주문)의 미송·포장은 원본에서 뺀다 —
 * 소매 미송 화면과 같은 조건이다.
 */
@Component
@RequiredArgsConstructor
public class InquirySourceReader {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JdbcClient jdbc;

    /**
     * 게시글 하나. 지워졌거나 없으면 null(→ INVALID_TARGET). 시즌 종료는 published=false 로 돌려준다(답은 하되 주문 불가).
     *
     * <p>판매가능 = 재고 − 주문처리중(reserved) − 그 SKU 의 열린 미송 남은 수(웹 목 규칙). 미송이 먼저 풀리므로
     * 새로 주문할 몫에서 뺀다. 음수 그대로 넘긴다 — 0으로 접는 건 판정이 한다.
     */
    public Listing listing(long listingId) {
        List<Row> rows = jdbc.sql("""
                        select l.id as listing_id, p.wholesaler_id, l.title,
                               (l.status = 'ON_SALE' and p.deleted_at is null) as listing_published,
                               v.id as variant_id, c.name as color, v.size,
                               v.stock_qty - v.reserved_qty - coalesce((
                                   select sum(oi.qty - oi.allocated_qty) from wholesale.order_item oi
                                    where oi.variant_id = v.id
                                      and exists (select 1 from wholesale.backorder b
                                                   where b.order_item_id = oi.id and b.status = 'OPEN')), 0)
                                   as available_qty,
                               coalesce(lv.order_limit, 0) as order_limit,
                               (lv.variant_id is not null) as variant_published
                        from wholesale.listing l
                        join wholesale.product p        on p.id = l.product_id
                        join wholesale.variant v        on v.product_id = p.id and v.deleted_at is null
                        join wholesale.color_option co  on co.id = v.color_option_id
                        join common.color c             on c.id = co.color_id
                        left join wholesale.listing_variant lv on lv.listing_id = l.id and lv.variant_id = v.id
                        where l.id = :listingId and l.deleted_at is null
                        order by v.variant_seq, v.id
                        """)
                .param("listingId", listingId)
                .query((rs, i) -> new Row(rs.getLong("listing_id"), rs.getLong("wholesaler_id"),
                        rs.getString("title"), rs.getBoolean("listing_published"),
                        new Variant(rs.getLong("variant_id"), rs.getString("color"), rs.getString("size"),
                                rs.getInt("available_qty"), rs.getInt("order_limit"),
                                rs.getBoolean("variant_published"))))
                .list();
        if (rows.isEmpty()) {
            return null;
        }
        Row head = rows.getFirst();
        return new Listing(head.listingId(), head.wholesalerId(), head.title(), head.published(),
                rows.stream().map(Row::variant).toList());
    }

    private record Row(long listingId, long wholesalerId, String title, boolean published, Variant variant) {
    }

    /** 이 쌍의 열린 미송(남은 수량 = 라인 qty − allocated_qty). */
    public List<Backorder> backorders(long wholesalerId, long retailerId) {
        return jdbc.sql("""
                        select b.id as backorder_id, o.retail_order_id, o.wholesaler_id, pt.retailer_id,
                               oi.variant_id, coalesce(l.title, p.name) as title, c.name as color, v.size,
                               oi.qty - oi.allocated_qty as remaining_qty, o.ordered_at,
                               v.expected_inbound_date, v.expected_inbound_reason
                        from wholesale.backorder b
                        join wholesale.order_item oi    on oi.id = b.order_item_id
                        join wholesale.orders o         on o.id = oi.order_id
                        join wholesale.partner pt       on pt.id = o.partner_id
                        join wholesale.variant v        on v.id = oi.variant_id
                        join wholesale.color_option co  on co.id = v.color_option_id
                        join common.color c             on c.id = co.color_id
                        join wholesale.product p        on p.id = v.product_id
                        left join wholesale.listing l   on l.product_id = p.id and l.deleted_at is null
                        where b.status = 'OPEN' and o.wholesaler_id = :wholesalerId and pt.retailer_id = :retailerId
                          and o.retail_order_id is not null
                        order by o.ordered_at, b.id
                        """)
                .param("wholesalerId", wholesalerId)
                .param("retailerId", retailerId)
                .query((rs, i) -> new Backorder(rs.getLong("backorder_id"), rs.getLong("retail_order_id"),
                        rs.getLong("wholesaler_id"), rs.getLong("retailer_id"), rs.getLong("variant_id"),
                        rs.getString("title"), rs.getString("color"), rs.getString("size"),
                        rs.getInt("remaining_qty"), rs.getObject("ordered_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("expected_inbound_date", LocalDate.class),
                        rs.getString("expected_inbound_reason")))
                .list();
    }

    /**
     * 이 쌍의 정산 요약 한 줄. 거래 관계(partner)가 없으면 빈 목록(→ NO_RELATION).
     *
     * <p>exact=false(→ 금액 없이 넘김)인 경우: 조정·입금 무효 줄이 있다 / 한 주문이 나눠 출고됐다 / 취소 주문에 출고 줄이 있다 /
     * 출고보다 배정이 큰 주문이 있다(출고 전 배정). 원장끼리 맞는지 판정이 다시 본다(unpaid − prepaid = balance).
     */
    public List<Receivable> receivables(long wholesalerId, long retailerId, Instant now) {
        Optional<long[]> partner = jdbc.sql("""
                        select pt.id, pt.receivable_balance,
                               coalesce((select sum(p.amount) from wholesale.payment p
                                          where p.partner_id = pt.id and p.voided_at is null), 0) as paid,
                               coalesce((select sum(a.amount) from wholesale.payment_allocation a
                                          join wholesale.payment p on p.id = a.payment_id
                                          where p.partner_id = pt.id and p.voided_at is null
                                            and a.cancelled_at is null), 0) as allocated,
                               (select count(*) from wholesale.receivable_ledger l
                                 where l.partner_id = pt.id and l.entry_type in ('ADJUST', 'PAYMENT_VOID')) as irregular
                        from wholesale.partner pt
                        where pt.wholesaler_id = :wholesalerId and pt.retailer_id = :retailerId
                        """)
                .param("wholesalerId", wholesalerId)
                .param("retailerId", retailerId)
                .query((rs, i) -> new long[]{rs.getLong("id"), rs.getLong("receivable_balance"), rs.getLong("paid"),
                        rs.getLong("allocated"), rs.getLong("irregular")})
                .optional();
        if (partner.isEmpty()) {
            return List.of();
        }
        long partnerId = partner.get()[0];
        long balance = partner.get()[1];
        long prepaid = partner.get()[2] - partner.get()[3];
        boolean exact = partner.get()[4] == 0;

        Map<Long, Long> paidByOrder = new HashMap<>();
        jdbc.sql("""
                        select a.order_id, sum(a.amount) as paid
                        from wholesale.payment_allocation a
                        join wholesale.payment p on p.id = a.payment_id
                        where p.partner_id = :partnerId and p.voided_at is null and a.cancelled_at is null
                        group by a.order_id
                        """)
                .param("partnerId", partnerId)
                .query(rs -> {
                    paidByOrder.put(rs.getLong("order_id"), rs.getLong("paid"));
                });

        List<OverdueCalculator.Shipment> shipments = new ArrayList<>();
        Map<Long, Long> shippedByOrder = new HashMap<>();
        Map<Long, Integer> outboundRows = new HashMap<>();
        boolean[] cancelledShipment = {false};
        jdbc.sql("""
                        select l.id, l.order_id, l.delta, l.occurred_at, o.status
                        from wholesale.receivable_ledger l
                        join wholesale.orders o on o.id = l.order_id
                        where l.partner_id = :partnerId and l.entry_type = 'OUTBOUND'
                        """)
                .param("partnerId", partnerId)
                .query(rs -> {
                    long orderId = rs.getLong("order_id");
                    long delta = rs.getLong("delta");
                    shipments.add(new OverdueCalculator.Shipment(orderId,
                            rs.getObject("occurred_at", OffsetDateTime.class).atZoneSameInstant(KST).toLocalDate(),
                            delta, rs.getLong("id")));
                    shippedByOrder.merge(orderId, delta, Long::sum);
                    outboundRows.merge(orderId, 1, Integer::sum);
                    if ("CANCELLED".equals(rs.getString("status"))) {
                        cancelledShipment[0] = true;
                    }
                });

        long unpaid = 0;
        for (Map.Entry<Long, Long> e : shippedByOrder.entrySet()) {
            unpaid += Math.max(0, e.getValue() - paidByOrder.getOrDefault(e.getKey(), 0L));
        }
        boolean allocatedBeyondShipment = paidByOrder.entrySet().stream()
                .anyMatch(e -> e.getValue() > shippedByOrder.getOrDefault(e.getKey(), 0L));
        boolean splitShipment = outboundRows.values().stream().anyMatch(n -> n > 1);
        exact = exact && !cancelledShipment[0] && !allocatedBeyondShipment && !splitShipment;

        OverdueCalculator.Overdue overdue =
                OverdueCalculator.of(shipments, paidByOrder, LocalDate.ofInstant(now, KST));
        return List.of(new Receivable(wholesalerId, retailerId, balance, unpaid, prepaid, overdue.amount(),
                overdue.count(), overdue.maxDays(), exact, OffsetDateTime.ofInstant(now, ZoneOffset.UTC)));
    }

    /** 이 쌍의 출고 전 포장(봉투 없음 또는 봉투 출고 전). 줄 수량은 지워지지 않은 포장 품목 합. */
    public List<Packing> packings(long wholesalerId, long retailerId) {
        return jdbc.sql("""
                        select pk.id as packing_id, o.retail_order_id, o.wholesaler_id, pt.retailer_id,
                               (o.status = 'CANCELLED') as order_cancelled, pk.status as packing_status,
                               case when pk.outbound_id is null then null else ob.shipped_at is not null end
                                   as outbound_shipped,
                               coalesce((select sum(pi.qty) from wholesale.packing_item pi
                                          where pi.packing_id = pk.id and pi.deleted_at is null), 0) as qty
                        from wholesale.packing pk
                        join wholesale.orders o      on o.id = pk.order_id
                        join wholesale.partner pt    on pt.id = o.partner_id
                        left join wholesale.outbound ob on ob.id = pk.outbound_id
                        where o.wholesaler_id = :wholesalerId and pt.retailer_id = :retailerId
                          and o.retail_order_id is not null
                          and (pk.outbound_id is null or ob.shipped_at is null)
                        """)
                .param("wholesalerId", wholesalerId)
                .param("retailerId", retailerId)
                .query((rs, i) -> new Packing(rs.getLong("packing_id"), rs.getLong("retail_order_id"),
                        rs.getLong("wholesaler_id"), rs.getLong("retailer_id"), rs.getBoolean("order_cancelled"),
                        rs.getString("packing_status"), rs.getObject("outbound_shipped", Boolean.class),
                        rs.getInt("qty")))
                .list();
    }
}
