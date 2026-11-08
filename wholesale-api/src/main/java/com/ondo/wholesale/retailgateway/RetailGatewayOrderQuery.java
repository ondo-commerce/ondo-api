package com.ondo.wholesale.retailgateway;

import com.ondo.wholesale.retailgateway.dto.RetailWholesalerResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 접수가 대조할 값을 한 번에 읽는다 (MUL-98).
 *
 * <p>{@code VariantInfoReader}(채빈, MUL-47)를 안 쓰는 건 그게 주는 게 다르기 때문이다 —
 * 그쪽은 화면에 찍을 품번·색·사이즈와 가용재고를 준다. 접수가 봐야 하는 건
 * <b>누구 상품인지 · 팔고 있는지 · 얼마인지 · 한 번에 몇 장까지인지</b>다.
 *
 * <p>한 방에 읽는 이유는 주문 라인이 여럿이어서다. 라인마다 부르면 장바구니가 클수록
 * 왕복이 늘고, 그 사이에 도매가 가격을 바꾸면 라인끼리 다른 시점을 보게 된다.
 */
@Repository
@RequiredArgsConstructor
public class RetailGatewayOrderQuery {

    private final JdbcClient jdbc;

    /**
     * 접수 대조용 스냅샷.
     *
     * @param wholesalerId 이 옵션의 주인. 요청의 wholesalerId 와 다르면 남의 상품이다
     * @param onSale       <b>게시글</b>이 팔리는 상태인지. 상품·게시글·옵션 어느 것도 안 지워졌고
     *                     게시글이 {@code ON_SALE} 인지까지다 — 이 옵션이 게시에 올라갔는지는 안 본다
     * @param salePrice    게시된 판매가. <b>이 옵션이 게시에 없으면 null</b>이라 가격을 못 정한다
     * @param orderLimit   1회 주문 최대 수량. 0 이면 무제한 (D-057)
     */
    public record VariantSnapshot(Long variantId, Long wholesalerId, boolean onSale,
                                  Integer salePrice, Integer orderLimit) {}

    /** @return 찾은 것만 담긴다. 없는 variantId 는 키가 없다 */
    public Map<Long, VariantSnapshot> snapshots(List<Long> variantIds) {
        if (variantIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                        SELECT v.id            AS variant_id,
                               p.wholesaler_id AS wholesaler_id,
                               -- 게시글 수준의 판단만 한다. 옵션이 게시에 올라갔는지는
                               -- sale_price 가 null 인지로 갈린다 — 둘을 묶으면 "이 상품은
                               -- 안 팔아요" 와 "이 사이즈는 값이 없어요" 가 같은 에러가 된다
                               (l.id IS NOT NULL
                                    AND l.status = 'ON_SALE'
                                    AND l.deleted_at IS NULL
                                    AND p.deleted_at IS NULL
                                    AND v.deleted_at IS NULL) AS on_sale,
                               lv.sale_price   AS sale_price,
                               lv.order_limit  AS order_limit
                        FROM wholesale.variant v
                        JOIN wholesale.product p            ON p.id = v.product_id
                        LEFT JOIN wholesale.listing l       ON l.product_id = p.id
                        LEFT JOIN wholesale.listing_variant lv
                               ON lv.listing_id = l.id AND lv.variant_id = v.id
                        WHERE v.id IN (:ids)
                        """)
                .param("ids", variantIds)
                .query((rs, rowNum) -> new VariantSnapshot(
                        rs.getLong("variant_id"),
                        rs.getLong("wholesaler_id"),
                        rs.getBoolean("on_sale"),
                        (Integer) rs.getObject("sale_price"),
                        (Integer) rs.getObject("order_limit")))
                .list().stream()
                .collect(Collectors.toMap(VariantSnapshot::variantId, s -> s));
    }

    // ── 멱등성 ──────────────────────────────────────────────────

    /**
     * 이미 접수된 주문인지 (D-052).
     *
     * <p>UNIQUE(retail_order_id, wholesaler_id)가 최종 방어지만 여기서 먼저 본다.
     * 제약에만 기대면 소매가 재시도할 때마다 채번이 한 칸씩 올라가서 주문번호에
     * 구멍이 생긴다. 도매처는 그 번호로 장부를 맞춘다.
     */
    public Optional<Long> findExistingOrderId(Long retailOrderId, Long wholesalerId) {
        return jdbc.sql("""
                        SELECT id FROM wholesale.orders
                        WHERE retail_order_id = :retailOrderId AND wholesaler_id = :wholesalerId
                        """)
                .param("retailOrderId", retailOrderId)
                .param("wholesalerId", wholesalerId)
                .query(Long.class)
                .optional();
    }

    // ── 거래처 ──────────────────────────────────────────────────

    /**
     * 거래처를 찾고, 첫 거래면 만든다.
     *
     * <p>{@code ON CONFLICT DO NOTHING} 인 이유 — 소매처가 두 도매처에 동시에 주문하면
     * 같은 거래처 행을 두 요청이 함께 만들려 든다. 먼저 넣은 쪽이 이기고 나중은 조용히
     * 넘어간 뒤 아래 SELECT 로 같은 행을 집는다.
     *
     * <p><b>이미 있으면 상호·연락처를 안 덮어쓴다.</b> 도매가 보던 이름이 주문할 때마다
     * 바뀌면 장부가 흔들린다. 소매처가 상호를 바꿨을 때 어떻게 할지는 따로 정한다.
     */
    public long resolvePartnerId(Long wholesalerId, Long retailerId,
                                 String retailerName, String retailerPhone) {
        jdbc.sql("""
                        INSERT INTO wholesale.partner
                            (wholesaler_id, retailer_id, retailer_name, retailer_phone)
                        VALUES (:wholesalerId, :retailerId, :retailerName, :retailerPhone)
                        ON CONFLICT (wholesaler_id, retailer_id) DO NOTHING
                        """)
                .param("wholesalerId", wholesalerId)
                .param("retailerId", retailerId)
                .param("retailerName", retailerName)
                .param("retailerPhone", retailerPhone)
                .update();

        return jdbc.sql("""
                        SELECT id FROM wholesale.partner
                        WHERE wholesaler_id = :wholesalerId AND retailer_id = :retailerId
                        """)
                .param("wholesalerId", wholesalerId)
                .param("retailerId", retailerId)
                .query(Long.class)
                .single();
    }

    // ── 채번 ────────────────────────────────────────────────────

    /**
     * 도매처별 주문 연번을 하나 뽑는다 (D-064 · 숙제 9번).
     *
     * <p>읽고 더해서 쓰지 않고 <b>한 문장으로 올린다.</b> 나눠 하면 두 주문이 같은 값을
     * 읽어 같은 번호를 쓰고, {@code orders_number_uk} 에 걸려 한쪽이 애먼 실패를 한다.
     * {@code UPDATE ... RETURNING} 은 그 행에 락을 잡은 채 올린 값을 돌려주므로
     * 동시에 들어와도 번호가 안 겹친다.
     */
    public int nextOrderNumber(Long wholesalerId) {
        return jdbc.sql("""
                        UPDATE wholesale.wholesaler
                        SET last_order_seq = last_order_seq + 1
                        WHERE id = :wholesalerId
                        RETURNING last_order_seq
                        """)
                .param("wholesalerId", wholesalerId)
                .query(Integer.class)
                .single();
    }

    // ── 도매처 ──────────────────────────────────────────────────

    /**
     * 주문서에 쓸 도매처 정보를 한 번에 (MUL-98).
     *
     * <p>계좌를 같이 준다. 소매 주문서는 도매처마다 따로 입금하는 화면이라 계좌가 없으면
     * 그릴 수가 없다. 계좌가 비어 있는 도매처는 아직 등록을 안 한 것이고, 그때는
     * 소매 화면이 계좌이체를 못 고르게 막는다.
     *
     * <p>계좌는 주계좌다 (MUL-130). 주계좌는 도매처당 최대 1개(bank_account_primary_uk)라
     * LEFT JOIN 이 행을 불리지 않는다. 계좌가 0건이면 셋 다 null — 위의 "미등록" 그대로다.
     *
     * <p>지워진 도매처도 돌려준다. 지난 주문의 주문서를 다시 열 수 있어야 해서다.
     */
    public List<RetailWholesalerResponse> wholesalers(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT w.id, w.biz_name, w.store_building, w.store_unit,
                               ba.bank_name, ba.account_no AS bank_account_no, ba.account_holder AS bank_account_holder
                        FROM wholesale.wholesaler w
                        LEFT JOIN wholesale.bank_account ba ON ba.wholesaler_id = w.id AND ba.is_primary
                        WHERE w.id IN (:ids)
                        """)
                .param("ids", ids)
                .query((rs, rowNum) -> new RetailWholesalerResponse(
                        rs.getLong("id"),
                        rs.getString("biz_name"),
                        rs.getString("store_building"),
                        rs.getString("store_unit"),
                        rs.getString("bank_name"),
                        rs.getString("bank_account_no"),
                        rs.getString("bank_account_holder")))
                .list();
    }
}
