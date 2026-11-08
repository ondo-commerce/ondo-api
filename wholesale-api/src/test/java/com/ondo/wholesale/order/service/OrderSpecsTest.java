package com.ondo.wholesale.order.service;

import com.ondo.wholesale.order.OrderFilterKey;
import com.ondo.wholesale.order.SettlementStatus;
import com.ondo.wholesale.order.domain.Order;
import com.ondo.wholesale.order.repository.OrderRepository;
import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.OrderFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주문 목록 검색 조건(Specification) 통합 검증 (MUL-47, 주문 번호 검색 MUL-150).
 */
@SpringBootTest
@Transactional
class OrderSpecsTest extends PostgresTestSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired OrderRepository orderRepository;
    @Autowired OrderSummaryReader reader;
    @Autowired JdbcTemplate jdbc;

    private long wholesalerId;
    private long partnerId;
    private long 니트;

    @BeforeEach
    void 재료를_심는다() {
        wholesalerId = MasterDataFixture.도매처를_넣는다(jdbc, "specs@ondo.test", "9500000004");
        long leafId = MasterDataFixture.카테고리_리프를_넣는다(jdbc, 9100);
        long colorId = MasterDataFixture.색상을_넣는다(jdbc, 9200, 9201);
        니트 = OrderFixture.상품_변형을_넣는다(jdbc, wholesalerId, leafId, colorId, "니트", 1);
        partnerId = OrderFixture.거래처를_넣는다(jdbc, wholesalerId, 701L, "행복상회");
    }

    @Test
    void 자기_도매처의_주문만_나온다() {
        long 내주문 = 주문(1, "NEW", OffsetDateTime.now());
        long 남 = MasterDataFixture.도매처를_넣는다(jdbc, "other-specs@ondo.test", "9500000005");
        long 남거래처 = OrderFixture.거래처를_넣는다(jdbc, 남, 702L, "남의상회");
        OrderFixture.주문을_넣는다(jdbc, 남, 남거래처, 1, "NEW", OffsetDateTime.now());

        List<Long> ids = ids(OrderSpecs.ownedBy(wholesalerId));

        assertThat(ids).containsExactly(내주문);
    }

    @Test
    void 검색어는_거래처명과_상품명_양쪽에_걸린다() {
        long orderId = 주문(1, "NEW", OffsetDateTime.now());
        OrderFixture.라인을_넣는다(jdbc, orderId, 니트, 1, 1000, 0, 0);

        assertThat(ids(spec(OrderSpecs.searchLike("행복")))).containsExactly(orderId);
        assertThat(ids(spec(OrderSpecs.searchLike("니트")))).containsExactly(orderId);
        assertThat(ids(spec(OrderSpecs.searchLike("없는말")))).isEmpty();
    }

    @Test
    void 숫자뿐인_검색어는_주문_번호_일치도_건다() {
        long 찾는주문 = 주문(1501, "NEW", OffsetDateTime.now());
        long 다른주문 = 주문(1502, "NEW", OffsetDateTime.now());

        assertThat(ids(spec(OrderSpecs.searchLike("1501")))).containsExactly(찾는주문);
        assertThat(ids(spec(OrderSpecs.searchLike(" 1502 ")))).containsExactly(다른주문);
        assertThat(ids(spec(OrderSpecs.searchLike("150")))).isEmpty();
    }

    @Test
    void 숫자_검색어는_주문_번호와_이름_부분_일치를_함께_돌려준다() {
        long 번호일치 = 주문(77, "NEW", OffsetDateTime.now());
        long 이름일치 = OrderFixture.주문을_넣는다(jdbc, wholesalerId,
                OrderFixture.거래처를_넣는다(jdbc, wholesalerId, 703L, "77상회"), 1, "NEW", OffsetDateTime.now());

        assertThat(ids(spec(OrderSpecs.searchLike("77")))).containsExactlyInAnyOrder(번호일치, 이름일치);
    }

    @Test
    void int_범위를_넘는_숫자_검색어는_이름_검색으로만_다룬다() {
        주문(1, "NEW", OffsetDateTime.now());

        assertThat(ids(spec(OrderSpecs.searchLike("99999999999")))).isEmpty();
    }

    @Test
    void 파생_상태_필터가_확정과_부분출고와_전량출고를_가른다() {
        long 확정 = 주문(1, "CONFIRMED", OffsetDateTime.now());
        OrderFixture.라인을_넣는다(jdbc, 확정, 니트, 3, 1000, 3, 0);
        long 부분 = 주문(2, "CONFIRMED", OffsetDateTime.now());
        OrderFixture.라인을_넣는다(jdbc, 부분, 니트, 3, 1000, 3, 1);
        long 전량 = 주문(3, "CONFIRMED", OffsetDateTime.now());
        OrderFixture.라인을_넣는다(jdbc, 전량, 니트, 3, 1000, 3, 3);

        assertThat(ids(spec(OrderSpecs.derivedStatus(OrderFilterKey.CONFIRMED)))).containsExactly(확정);
        assertThat(ids(spec(OrderSpecs.derivedStatus(OrderFilterKey.PARTIALLY_SHIPPED)))).containsExactly(부분);
        assertThat(ids(spec(OrderSpecs.derivedStatus(OrderFilterKey.SHIPPED)))).containsExactly(전량);
    }

    @Test
    void retailerId를_걸면_그_거래처의_확정_주문만_나온다() {
        long 신규 = 주문(1, "NEW", OffsetDateTime.now());
        long 확정 = 주문(2, "CONFIRMED", OffsetDateTime.now());
        long 다른거래처 = OrderFixture.거래처를_넣는다(jdbc, wholesalerId, 702L, "기쁨상회");
        OrderFixture.주문을_넣는다(jdbc, wholesalerId, 다른거래처, 3, "CONFIRMED", OffsetDateTime.now());

        List<Long> ids = ids(spec(OrderSpecs.retailerScoped(701L)));

        assertThat(ids).containsExactly(확정);
        assertThat(ids).doesNotContain(신규);
    }

    @Test
    void 기간은_주문일에_KST_경계로_걸린다() {
        long 첫날 = 주문(1, "NEW", LocalDate.of(2026, 9, 1).atStartOfDay(KST).toOffsetDateTime());
        long 둘째날 = 주문(2, "NEW",
                LocalDate.of(2026, 9, 2).atTime(23, 59).atZone(KST).toOffsetDateTime());
        long 셋째날 = 주문(3, "NEW", LocalDate.of(2026, 9, 3).atStartOfDay(KST).toOffsetDateTime());

        List<Long> ids = ids(spec(
                OrderSpecs.orderedBetween(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2))));

        assertThat(ids).containsExactlyInAnyOrder(첫날, 둘째날);
        assertThat(ids).doesNotContain(셋째날);
    }

    @Test
    void 기간은_from이나_to만_줘도_걸린다() {
        long 첫날 = 주문(1, "NEW", LocalDate.of(2026, 9, 1).atStartOfDay(KST).toOffsetDateTime());
        long 셋째날 = 주문(2, "NEW", LocalDate.of(2026, 9, 3).atStartOfDay(KST).toOffsetDateTime());

        assertThat(ids(spec(OrderSpecs.orderedBetween(LocalDate.of(2026, 9, 2), null))))
                .containsExactly(셋째날);
        assertThat(ids(spec(OrderSpecs.orderedBetween(null, LocalDate.of(2026, 9, 2)))))
                .containsExactly(첫날);
    }

    @Test
    void 정산_상태_필터는_원장으로_고른_id와_idIn으로_조합한다() {
        long 미입금 = 주문(1, "CONFIRMED", OffsetDateTime.now());
        long 완납 = 주문(2, "CONFIRMED", OffsetDateTime.now());
        OrderFixture.원장_출고를_넣는다(jdbc, partnerId, 완납, 5000);
        OrderFixture.원장_입금을_넣는다(jdbc, partnerId, 완납, 5000);

        List<Long> settled = reader.orderIdsBySettlement(wholesalerId, SettlementStatus.SETTLED);

        assertThat(ids(spec(OrderSpecs.idIn(settled)))).containsExactly(완납);
        assertThat(ids(spec(OrderSpecs.idIn(settled)))).doesNotContain(미입금);
    }

    private long 주문(int orderNumber, String status, OffsetDateTime orderedAt) {
        return OrderFixture.주문을_넣는다(jdbc, wholesalerId, partnerId, orderNumber, status, orderedAt);
    }

    private Specification<Order> spec(Specification<Order> extra) {
        return Specification.allOf(List.of(OrderSpecs.ownedBy(wholesalerId), extra));
    }

    private List<Long> ids(Specification<Order> spec) {
        return orderRepository.findAll(spec).stream().map(Order::getId).toList();
    }
}
