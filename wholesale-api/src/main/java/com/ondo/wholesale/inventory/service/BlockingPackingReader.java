package com.ondo.wholesale.inventory.service;

import com.ondo.wholesale.inventory.dto.StockBelowAllocatedData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 예약 하한 409 의 취소 후보 포장 읽기 부품 (MUL-72).
 *
 * <p>포장·주문은 주문 도메인 소유라 엔티티를 끌어오지 않고 jdbc 로만 읽는다 —
 * 재고 레인은 그쪽 코드를 수정하지 않는다는 경계 그대로.
 */
@Component
public class BlockingPackingReader {

    private final JdbcTemplate jdbc;

    public BlockingPackingReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 이 variant 의 예약을 잡고 있는 살아있는 포장 항목 전부 — 오래된 항목부터. */
    public List<StockBelowAllocatedData.BlockingPacking> read(Long variantId) {
        return jdbc.query("""
                select pk.id as packing_id, pi.id as packing_item_id, o.order_number,
                       pi.qty, pk.status, pk.outbound_id
                from wholesale.packing_item pi
                join wholesale.packing pk    on pk.id = pi.packing_id
                join wholesale.order_item oi on oi.id = pi.order_item_id
                join wholesale.orders o      on o.id = pk.order_id
                where oi.variant_id = ? and pi.deleted_at is null
                order by pi.id
                """, (rs, i) -> {
            String status = rs.getString("status");
            Long outboundId = rs.getObject("outbound_id", Long.class);
            return new StockBelowAllocatedData.BlockingPacking(
                    rs.getLong("packing_id"), rs.getLong("packing_item_id"),
                    rs.getInt("order_number"), rs.getInt("qty"), status, outboundId,
                    "READY".equals(status) && outboundId == null);
        }, variantId);
    }
}
