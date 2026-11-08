package com.ondo.wholesale.retailnotification;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * 소매 알림 행을 만든다 (MUL-155). 설계: docs/design/notification.md
 *
 * <p><b>부르는 쪽 트랜잭션 안에서만 돈다</b>({@link Propagation#MANDATORY}). 사건(입고일 변경)과
 * 알림이 한 커밋이어야 "날짜는 바뀌었는데 알림이 없다" · "알림은 왔는데 날짜는 그대로다" 가 안 생긴다.
 * 같은 도매 DB 라 가능한 일이고, 그래서 서버 간 전달·재시도가 없다.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RetailNotificationWriter {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JdbcClient jdbc;

    /**
     * 입고 예정일이 바뀌었을 때. 그 SKU 에 OPEN 이고 남은 수량이 있는 미송을 가진 소매처마다 1건.
     *
     * <p>날짜가 같으면(사유만 바뀜) 0건이다. 미정 ↔ 날짜는 바뀐 것이다.
     *
     * <p>남은 수량은 <b>이 트랜잭션이 읽은 값</b>이다. 배분이 먼저 커밋돼 남은 게 0 이 된 소매처는
     * 받지 않는다. 소매 화면이 그 미송을 볼 수 있어야 의미가 있으니 소매 주문서가 있는 것만
     * 센다({@code retail_order_id IS NOT NULL}, 소매 미송 목록과 같은 조건).
     *
     * @return 만든 알림 수
     */
    public int backorderDateChanged(long variantId, LocalDate before, LocalDate after) {
        if (Objects.equals(before, after)) {
            return 0;
        }

        List<Recipient> recipients = jdbc.sql("""
                        select pt.retailer_id, sum(oi.qty - oi.allocated_qty) as remaining_qty
                        from wholesale.backorder b
                        join wholesale.order_item oi on oi.id = b.order_item_id
                        join wholesale.orders o      on o.id = oi.order_id
                        join wholesale.partner pt    on pt.id = o.partner_id
                        where oi.variant_id = :variantId
                          and b.status = 'OPEN'
                          and o.retail_order_id is not null
                          and oi.qty - oi.allocated_qty > 0
                        group by pt.retailer_id
                        order by pt.retailer_id
                        """)
                .param("variantId", variantId)
                .query((rs, rowNum) -> new Recipient(rs.getLong("retailer_id"), rs.getInt("remaining_qty")))
                .list();
        if (recipients.isEmpty()) {
            return 0;
        }

        Sku sku = jdbc.sql("""
                        select w.id as wholesaler_id, w.biz_name,
                               coalesce((select l.title from wholesale.listing l
                                         where l.product_id = p.id and l.deleted_at is null
                                         order by l.id desc limit 1), p.name) as title,
                               c.name as color_name, v.size
                        from wholesale.variant v
                        join wholesale.product p       on p.id = v.product_id
                        join wholesale.wholesaler w    on w.id = p.wholesaler_id
                        join wholesale.color_option co on co.id = v.color_option_id
                        join common.color c            on c.id = co.color_id
                        where v.id = :variantId
                        """)
                .param("variantId", variantId)
                .query((rs, rowNum) -> new Sku(rs.getLong("wholesaler_id"), rs.getString("biz_name"),
                        rs.getString("title"), rs.getString("color_name"), rs.getString("size")))
                .single();

        LocalDate today = LocalDate.now(KST);
        String title = BackorderDateChangeText.title(sku.wholesalerName());
        for (Recipient recipient : recipients) {
            jdbc.sql("""
                            insert into wholesale.retail_notification
                                (retailer_id, type, title, body, target_kind, target_wholesaler_id)
                            values (:retailerId, :type, :title, :body, 'BACKORDERS', :wholesalerId)
                            """)
                    .param("retailerId", recipient.retailerId())
                    .param("type", RetailNotificationType.BACKORDER_DATE_CHANGED.name())
                    .param("title", title)
                    .param("body", BackorderDateChangeText.body(sku.title(), sku.colorName(), sku.size(),
                            recipient.remainingQty(), before, after, today))
                    .param("wholesalerId", sku.wholesalerId())
                    .update();
        }
        return recipients.size();
    }

    private record Recipient(long retailerId, int remainingQty) {}

    private record Sku(long wholesalerId, String wholesalerName, String title, String colorName, String size) {}
}
