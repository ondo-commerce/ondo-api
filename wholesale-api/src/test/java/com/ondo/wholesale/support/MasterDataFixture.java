package com.ondo.wholesale.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 상품 계열 테스트가 공유하는 마스터·도매처 픽스처.
 *
 * <p>V5 시드는 팀이 값을 바꿀 수 있는 데이터라 테스트는 시드 행을 쓰지 않고
 * 자기 행을 큰 id 대역(9xxx)에 심는다 — 대역 관리도 여기 한 곳에서 한다.
 */
public final class MasterDataFixture {

    private MasterDataFixture() {
    }

    /** 도매처 한 명을 넣고 id 를 돌려준다. NOT NULL 컬럼만 채운다. */
    public static long 도매처를_넣는다(JdbcTemplate jdbc, String email, String bizRegNo) {
        return jdbc.queryForObject("""
                insert into wholesale.wholesaler (email, password_hash, biz_reg_no, biz_name, biz_owner_name)
                values (?, 'x', ?, '테스트도매', '김테스트') returning id
                """, Long.class, email, bizRegNo);
    }

    /** 정산 계좌 한 건 (MUL-130). 주계좌는 도매처당 하나 — 둘째 주계좌는 bank_account_primary_uk 에 걸린다. */
    public static long 계좌를_넣는다(JdbcTemplate jdbc, long wholesalerId, String bankName, String accountNo,
                               String accountHolder, boolean primary) {
        return jdbc.queryForObject("""
                insert into wholesale.bank_account (wholesaler_id, bank_name, account_no, account_holder, is_primary)
                values (?, ?, ?, ?, ?) returning id
                """, Long.class, wholesalerId, bankName, accountNo, accountHolder, primary);
    }

    /** 여성>의류>상의 3단 체인을 baseId, baseId+1, baseId+2 로 넣고 리프 id 를 돌려준다. */
    public static long 카테고리_리프를_넣는다(JdbcTemplate jdbc, long baseId) {
        jdbc.update("insert into common.category (id, parent_id, name, depth) values (?, null, '여성', 1)", baseId);
        jdbc.update("insert into common.category (id, parent_id, name, depth) values (?, ?, '의류', 2)", baseId + 1, baseId);
        jdbc.update("insert into common.category (id, parent_id, name, depth) values (?, ?, '상의', 3)", baseId + 2, baseId + 1);
        return baseId + 2;
    }

    /** 색상 그룹(무채색)과 색(블랙)을 넣고 색 id 를 돌려준다. */
    public static long 색상을_넣는다(JdbcTemplate jdbc, long groupId, long colorId) {
        jdbc.update("insert into common.color_group (id, name, sort_order) values (?, '무채색', 0)", groupId);
        jdbc.update("insert into common.color (id, group_id, name, hex, sort_order) values (?, ?, '블랙', '#191F28', 0)",
                colorId, groupId);
        return colorId;
    }
}
