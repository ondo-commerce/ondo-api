package com.ondo.wholesale.retailgateway;

import com.ondo.wholesale.order.PaymentMethod;
import com.ondo.wholesale.order.ReceiveBy;
import com.ondo.wholesale.order.domain.OrderStatus;
import com.ondo.wholesale.retailgateway.dto.RetailOrderViewResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 소매가 자기 주문을 되읽는 SQL (MUL-98).
 *
 * <p>접수({@link RetailGatewayOrderQuery})와 나눠 뒀다. 그쪽은 쓰기 직전의 대조라
 * "이 옵션을 팔 수 있나" 만 보고, 여기는 이미 들어간 주문을 화면 모양으로 꺼낸다.
 *
 * <p>소매처로 한 번 더 거른다. {@code retailOrderId} 는 소매가 보낸 값이라 그것만
 * 믿으면 남의 주문서 번호를 넣어 남의 주문을 읽을 수 있다.
 */
@Repository
@RequiredArgsConstructor
public class RetailGatewayOrderViewQuery {

    private final JdbcClient jdbc;

    /** 주문 뼈대. 라인은 {@link #items(List)} 로 따로 가져와 서비스가 붙인다. */
    public record OrderRow(Long orderId, Long retailOrderId, Integer orderNumber,
                           OffsetDateTime orderedAt, OrderStatus status,
                           PaymentMethod paymentTerm, ReceiveBy receiveMethod,
                           String agentName, String agentPhone,
                           RetailOrderViewResponse.Wholesaler wholesaler) {}

    /** 라인 한 줄. {@code orderId} 로 위 뼈대에 붙는다. */
    public record ItemRow(Long orderId, RetailOrderViewResponse.Item item,
                          int allocatedQty, int shippedQty) {}

    /**
     * 그 소매처의 주문서들에 딸린 도매처 주문 전부.
     *
     * <p>한 번에 여러 주문서를 받는 건 내역 화면 때문이다. 한 장에 주문서가 스무 개면
     * 하나씩 부를 때 왕복이 스무 번이 된다.
     */
    public List<OrderRow> orders(long retailerId, List<Long> retailOrderIds) {
        if (retailOrderIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT o.id                  AS order_id,
                               o.retail_order_id     AS retail_order_id,
                               o.order_number        AS order_number,
                               o.ordered_at          AS ordered_at,
                               o.status              AS status,
                               o.payment_term        AS payment_term,
                               o.receive_method      AS receive_method,
                               o.agent_name          AS agent_name,
                               o.agent_phone         AS agent_phone,
                               w.id                  AS wholesaler_id,
                               w.biz_name            AS wholesaler_name,
                               w.store_building      AS store_building,
                               w.store_unit          AS store_unit,
                               ba.bank_name          AS bank_name,
                               ba.account_no         AS bank_account_no,
                               ba.account_holder     AS bank_account_holder
                        FROM wholesale.orders o
                        JOIN wholesale.partner pt   ON pt.id = o.partner_id
                        JOIN wholesale.wholesaler w ON w.id = o.wholesaler_id
                        LEFT JOIN wholesale.bank_account ba ON ba.wholesaler_id = w.id AND ba.is_primary
                        WHERE o.retail_order_id IN (:retailOrderIds)
                          AND pt.retailer_id = :retailerId
                        ORDER BY o.retail_order_id DESC, o.id
                        """)
                .param("retailOrderIds", retailOrderIds)
                .param("retailerId", retailerId)
                .query((rs, rowNum) -> new OrderRow(
                        rs.getLong("order_id"),
                        rs.getLong("retail_order_id"),
                        rs.getInt("order_number"),
                        rs.getObject("ordered_at", OffsetDateTime.class),
                        OrderStatus.valueOf(rs.getString("status")),
                        PaymentMethod.valueOf(rs.getString("payment_term")),
                        ReceiveBy.valueOf(rs.getString("receive_method")),
                        rs.getString("agent_name"),
                        rs.getString("agent_phone"),
                        new RetailOrderViewResponse.Wholesaler(
                                rs.getLong("wholesaler_id"),
                                rs.getString("wholesaler_name"),
                                rs.getString("store_building"),
                                rs.getString("store_unit"),
                                rs.getString("bank_name"),
                                rs.getString("bank_account_no"),
                                rs.getString("bank_account_holder"))))
                .list();
    }

    /**
     * 라인들.
     *
     * <p><b>「아직 못 받은 수량」을 {@code qty − shipped_qty} 로 센다.</b> 미송 표를 세지 않는
     * 이유는 소매 화면이 묻는 게 "내가 아직 못 받은 게 몇 장이냐" 여서다. 미송 행은
     * 배분이 모자랐을 때만 생기는데, 확정 전이라 배분 자체를 안 한 주문도 소매 입장에선
     * 아직 못 받은 것이다. 계약의 예시 "주문 5 · 받음 3 · 미송 2" 도 이 뺄셈이다.
     *
     * <p>게시글은 {@code LEFT JOIN} 이다. 도매가 상품을 지워도 지난 주문은 남아야 한다 —
     * 그때는 상품 상세로 넘어갈 수 없어 {@code listingId} 가 null 이다. 미송(MUL-97)과 같다.
     */
    public List<ItemRow> items(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT oi.id                      AS order_item_id,
                               oi.order_id                AS order_id,
                               oi.variant_id              AS variant_id,
                               oi.qty                     AS qty,
                               oi.unit_price              AS unit_price,
                               oi.allocated_qty           AS allocated_qty,
                               oi.shipped_qty             AS shipped_qty,
                               l.id                       AS listing_id,
                               COALESCE(l.title, p.name)  AS title,
                               c.name                     AS color_name,
                               v.size                     AS size,
                               v.expected_inbound_date    AS expected_inbound_date
                        FROM wholesale.order_item oi
                        JOIN wholesale.variant v       ON v.id = oi.variant_id
                        JOIN wholesale.product p       ON p.id = v.product_id
                        JOIN wholesale.color_option co ON co.id = v.color_option_id
                        JOIN common.color c            ON c.id = co.color_id
                        LEFT JOIN wholesale.listing l  ON l.product_id = p.id AND l.deleted_at IS NULL
                        WHERE oi.order_id IN (:orderIds)
                        ORDER BY oi.id
                        """)
                .param("orderIds", orderIds)
                .query((rs, rowNum) -> {
                    int qty = rs.getInt("qty");
                    int shipped = rs.getInt("shipped_qty");
                    return new ItemRow(
                            rs.getLong("order_id"),
                            new RetailOrderViewResponse.Item(
                                    rs.getLong("order_item_id"),
                                    rs.getLong("variant_id"),
                                    (Long) rs.getObject("listing_id"),
                                    rs.getString("title"),
                                    rs.getString("color_name"),
                                    rs.getString("size"),
                                    qty,
                                    rs.getInt("unit_price"),
                                    shipped,
                                    qty - shipped,
                                    rs.getObject("expected_inbound_date", LocalDate.class)),
                            rs.getInt("allocated_qty"),
                            shipped);
                })
                .list();
    }
}
