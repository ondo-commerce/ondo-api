package com.ondo.wholesale.migration;

import com.ondo.wholesale.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V7 검증 (MUL-72) — 입고 멱등 테이블과 원장 ref 널 허용.
 *
 * <p>입고 재요청의 replay 는 응답 본문을 그대로 돌려줘야 한다 — avgCost 가 시점값이라
 * 재계산으로는 같은 본문을 만들 수 없어 본문 자체를 저장한다. 원장 ref 는 "조정은
 * 출처가 없다"는 계약과 V1 의 NOT NULL 이 충돌해 널 허용으로 푼다.
 */
@SpringBootTest
@Transactional
class InventoryIdempotencySchemaTest extends PostgresTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void V7은_멱등_테이블을_만들고_원장_ref를_널허용으로_바꾼다() {
        // 멱등 테이블 — PK (wholesaler_id, idempotency_key)
        List<String> pkColumns = jdbc.queryForList("""
                select kcu.column_name
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu
                  on kcu.constraint_name = tc.constraint_name
                 and kcu.table_schema = tc.table_schema
                where tc.table_schema = 'wholesale'
                  and tc.table_name = 'inbound_idempotency'
                  and tc.constraint_type = 'PRIMARY KEY'
                order by kcu.ordinal_position
                """, String.class);
        assertThat(pkColumns).containsExactly("wholesaler_id", "idempotency_key");

        List<Map<String, Object>> columns = jdbc.queryForList("""
                select column_name, is_nullable from information_schema.columns
                where table_schema = 'wholesale' and table_name = 'inbound_idempotency'
                """);
        assertThat(columns).extracting(c -> c.get("column_name"))
                .contains("request_hash", "inbound_id", "response_body", "created_at");

        // 원장 ref — 조정은 출처가 없으므로 널 허용이어야 한다
        List<Map<String, Object>> refColumns = jdbc.queryForList("""
                select column_name, is_nullable from information_schema.columns
                where table_schema = 'wholesale' and table_name = 'stock_movement'
                  and column_name in ('ref_type', 'ref_id')
                """);
        assertThat(refColumns).hasSize(2)
                .allSatisfy(c -> assertThat(c.get("is_nullable")).isEqualTo("YES"));
    }
}
