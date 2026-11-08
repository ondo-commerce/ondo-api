package com.ondo.wholesale.notification;

import com.ondo.wholesale.order.domain.Order;
import com.ondo.wholesale.order.domain.OrderItem;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 주문에서 알림을 만든다 (MUL-154).
 *
 * <p><b>부르는 쪽 트랜잭션에 탄다</b>({@link Propagation#MANDATORY}). 주문이 롤백되면 알림도 없어야 하고,
 * 알림이 실패하면 주문도 안 들어가야 한다 — 따로 커밋하면 "알림은 왔는데 주문이 없다" 가 생긴다.
 *
 * <p>문구는 웹 목(mocks/wholesale/notification.ts)과 같은 꼴이다.
 * <ul>
 *   <li>title — {@code "<소매 상호> 새 주문"}</li>
 *   <li>body — {@code "<품명> <색> / <사이즈>[ 외 N건], <총 수량>장"} (첫 라인 기준)</li>
 * </ul>
 * 상호는 거래처 행의 것을 쓴다. 주문 탭이 그 이름을 보여 주니 알림과 글자가 맞는다.
 */
@Component
@RequiredArgsConstructor
public class OrderNotifier {

    static final String NEW_ORDER = "NEW_ORDER";
    static final String TARGET_ORDER = "ORDER";

    private final NotificationRepository repository;
    private final JdbcClient jdbc;

    /** 소매 주문 접수로 도매 주문 하나가 생겼다. 그 도매처에 새 주문 알림 1건. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long newOrder(Order order) {
        String retailerName = jdbc.sql("SELECT retailer_name FROM wholesale.partner WHERE id = :id")
                .param("id", order.getPartnerId())
                .query(String.class)
                .single();
        return repository.insert(order.getWholesalerId(), NEW_ORDER,
                retailerName + " 새 주문", body(order.getItems()), TARGET_ORDER, order.getId());
    }

    private String body(List<OrderItem> items) {
        if (items.isEmpty()) {
            return null;
        }
        OrderItem first = items.getFirst();
        String line = jdbc.sql("""
                        SELECT p.name || ' ' || c.name || ' / ' || v.size
                        FROM wholesale.variant v
                        JOIN wholesale.product p      ON p.id = v.product_id
                        JOIN wholesale.color_option co ON co.id = v.color_option_id
                        JOIN common.color c           ON c.id = co.color_id
                        WHERE v.id = :variantId
                        """)
                .param("variantId", first.getVariantId())
                .query(String.class)
                .single();
        int rest = items.size() - 1;
        int qty = items.stream().mapToInt(OrderItem::getQty).sum();
        return line + (rest > 0 ? " 외 " + rest + "건" : "") + ", " + qty + "장";
    }
}
