package com.ondo.wholesale.migration;

import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V19 검증 (MUL-159) — 채팅 자동 답 설정 테이블과 문의 지표 인덱스, 그리고 <b>V20 뒤에 붙는 V19</b>.
 *
 * <p>잡고 싶은 사고: ① V20(MUL-157)이 먼저 적용된 dev·운영 DB 에서 V19 가 "순서가 늦은 파일"로 거절돼 앱이 안 뜬다
 * ② 도매처 하나에 설정 행이 둘 ③ 칸이 NULL 이라 "통째로 바꾼다" 계약이 깨진다.
 * 빈 DB 에서는 V19 → V20 순서로 돌아 ①이 안 보인다. 그래서 같은 컨테이너에 빈 데이터베이스를 따로 만들어
 * V19 를 뺀 파일로 먼저 끝까지 돌리고, 그다음 전체 파일로 다시 돌린다(BankAccountMigrationTest 와 같은 방식).
 * API 동작은 ChatAutoReplySettingsIntegrationTest 가 본다.
 */
class ChatAutoReplySettingSchemaTest extends PostgresTestSupport {

    @Test
    void V20이_먼저_적용된_DB에도_V19가_뒤늦게_적용된다() throws IOException {
        JdbcTemplate jdbc = 빈_DB를_만든다();
        Path withoutV19 = Files.createTempDirectory("mul159-migration");
        for (Resource file : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql")) {
            String name = file.getFilename();
            if (name != null && !name.startsWith("V19__")) {
                try (InputStream in = file.getInputStream()) {
                    Files.copy(in, withoutV19.resolve(name));
                }
            }
        }
        flyway(jdbc, "filesystem:" + withoutV19.toAbsolutePath()).migrate();
        assertThat(버전들(jdbc)).contains("20").doesNotContain("19");

        flyway(jdbc, "classpath:db/migration").migrate();

        List<Map<String, Object>> history = jdbc.queryForList(
                "select version, success from wholesale.flyway_schema_history where version in ('19', '20')"
                        + " order by installed_rank");
        assertThat(history).extracting(r -> r.get("version")).containsExactly("20", "19");
        assertThat(history).allSatisfy(r -> assertThat(r.get("success")).isEqualTo(true));
        assertThat(jdbc.queryForObject("select to_regclass('wholesale.chat_auto_reply_setting') is not null",
                Boolean.class)).isTrue();
    }

    @Test
    void 도매처당_한_행이고_칸은_전부_필수다() {
        JdbcTemplate jdbc = 빈_DB를_만든다();
        flyway(jdbc, "classpath:db/migration").migrate();
        long 무드온 = MasterDataFixture.도매처를_넣는다(jdbc, "v19@ondo.test", "9500000199");

        jdbc.update("insert into wholesale.chat_auto_reply_setting"
                + " (wholesaler_id, enabled, stock_enabled, backorder_enabled, receivable_enabled)"
                + " values (?, false, true, true, true)", 무드온);

        assertThatThrownBy(() -> jdbc.update("insert into wholesale.chat_auto_reply_setting"
                + " (wholesaler_id, enabled, stock_enabled, backorder_enabled, receivable_enabled)"
                + " values (?, true, true, true, true)", 무드온))
                .hasMessageContaining("chat_auto_reply_setting_pkey");
        assertThatThrownBy(() -> jdbc.update("update wholesale.chat_auto_reply_setting set stock_enabled = null"
                + " where wholesaler_id = ?", 무드온))
                .hasMessageContaining("stock_enabled");
        assertThatThrownBy(() -> jdbc.update("insert into wholesale.chat_auto_reply_setting"
                + " (wholesaler_id, enabled, stock_enabled, backorder_enabled, receivable_enabled)"
                + " values (987654321, true, true, true, true)"))
                .hasMessageContaining("wholesaler");
    }

    @Test
    void 문의_지표용_출처_인덱스가_있다() {
        JdbcTemplate jdbc = 빈_DB를_만든다();
        flyway(jdbc, "classpath:db/migration").migrate();

        String def = jdbc.queryForObject(
                "select indexdef from pg_indexes where schemaname = 'wholesale' and indexname = 'chat_message_source_idx'",
                String.class);
        assertThat(def).contains("(room_id, source_sequence)").contains("WHERE (source_sequence IS NOT NULL)");
    }

    private static List<String> 버전들(JdbcTemplate jdbc) {
        return jdbc.queryForList("select version from wholesale.flyway_schema_history where version is not null",
                String.class);
    }

    private static JdbcTemplate 빈_DB를_만든다() {
        String name = "mul159_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        new JdbcTemplate(dataSource(POSTGRES.getJdbcUrl())).execute("CREATE DATABASE " + name);
        String url = POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        return new JdbcTemplate(dataSource(url));
    }

    private static DriverManagerDataSource dataSource(String url) {
        return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** 앱(application.yml)과 같은 설정 — 기본 스키마 wholesale, out-of-order 허용. */
    private static Flyway flyway(JdbcTemplate jdbc, String location) {
        return Flyway.configure()
                .dataSource(jdbc.getDataSource())
                .defaultSchema("wholesale")
                .outOfOrder(true)
                .locations(location)
                .load();
    }
}
