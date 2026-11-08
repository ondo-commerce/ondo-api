package com.ondo.wholesale.migration;

import com.ondo.wholesale.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V16 검증 (MUL-154) — 알림 테이블과 keyset·안 읽음 인덱스.
 *
 * <p>빈 DB 에서 전부 붙는지는 {@code MigrationSmokeTest} 가 본다. 여기는 V16 이 만든 모양이다.
 * 목록이 30초마다 불리므로 인덱스가 정렬 순서와 어긋나면 사업체가 커질수록 느려진다.
 */
@SpringBootTest
@Transactional
class NotificationSchemaTest extends PostgresTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void V16은_알림_테이블과_인덱스를_만든다() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                select column_name, is_nullable from information_schema.columns
                where table_schema = 'wholesale' and table_name = 'notification'
                """);
        assertThat(columns).extracting(c -> c.get("column_name"))
                .containsExactlyInAnyOrder("id", "wholesaler_id", "type", "title", "body",
                        "target_kind", "target_id", "created_at", "read_at");
        assertThat(columns)
                .filteredOn(c -> List.of("body", "read_at").contains(c.get("column_name")))
                .allSatisfy(c -> assertThat(c.get("is_nullable")).isEqualTo("YES"));

        List<String> indexes = jdbc.queryForList("""
                select indexdef from pg_indexes
                where schemaname = 'wholesale' and tablename = 'notification'
                """, String.class);
        assertThat(indexes).anySatisfy(d ->
                assertThat(d).contains("(wholesaler_id, created_at DESC, id DESC)"));
        assertThat(indexes).anySatisfy(d ->
                assertThat(d).contains("WHERE (read_at IS NULL)"));
    }

    @Test
    void 없는_도매처의_알림은_못_넣는다() {
        assertThatThrownBy(() -> jdbc.update("""
                        insert into wholesale.notification (wholesaler_id, type, title, target_kind, target_id)
                        values (999999999, 'NEW_ORDER', 't', 'ORDER', 1)
                        """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
