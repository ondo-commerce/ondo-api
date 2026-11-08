package com.ondo.wholesale.migration;

import com.ondo.wholesale.support.PostgresTestSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 정산 계좌 이관 (MUL-130) — V15 가 wholesaler 계좌 칸을 bank_account 주계좌로 옮기고 칸을 지우는지,
 * dev 시드 V902 가 시드 도매처 101~103 에 주계좌를 넣는지.
 *
 * <p>계좌 API 규칙은 BankAccountIntegrationTest 가, 동시성은 BankAccountConcurrencyIntegrationTest 가 본다.
 *
 * <p>다른 마이그레이션 테스트와 달리 스프링 컨텍스트를 안 쓴다. 이관은 "V14 까지 적용된 DB 에
 * 계좌 값이 있는 상태" 에서 V15 가 돌아야 보이는데, 컨텍스트의 DB 는 이미 끝까지 적용돼 있다.
 * 그래서 같은 컨테이너에 빈 데이터베이스를 따로 만들어 Flyway 를 직접 V14 → V15 로 돌린다.
 */
class BankAccountMigrationTest extends PostgresTestSupport {

    @Test
    void 기존_도매처_계좌_칸이_주계좌로_옮겨지고_칸은_지워진다() {
        JdbcTemplate jdbc = 빈_DB를_만든다();
        flyway(jdbc, "14", "classpath:db/migration").migrate();
        Flyway flyway = flyway(jdbc, "latest", "classpath:db/migration");

        long 무드온 = 도매처(jdbc, "moodon@ondo.test", "1000000001", "국민은행", "123-45-678", "김무드");
        도매처(jdbc, "raon@ondo.test", "1000000002", null, null, null);   // 미등록
        도매처(jdbc, "blank@ondo.test", "1000000003", " ", "", " ");      // 빈칸뿐 — 미등록과 같다

        flyway.migrate();

        List<Map<String, Object>> accounts = jdbc.queryForList(
                "select wholesaler_id, bank_name, account_no, account_holder, memo, is_primary"
                        + " from wholesale.bank_account order by id");
        // 미등록 도매처는 계좌 0건 — 소매 화면이 계좌이체를 막는 기존 동작 그대로
        assertThat(accounts).hasSize(1);
        assertThat(accounts.getFirst())
                .containsEntry("wholesaler_id", 무드온)
                .containsEntry("bank_name", "국민은행")
                .containsEntry("account_no", "123-45-678")
                .containsEntry("account_holder", "김무드")
                .containsEntry("memo", null)
                .containsEntry("is_primary", true);

        Integer leftover = jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'wholesale' and table_name = 'wholesaler'
                  and column_name in ('bank_name', 'bank_account_no', 'bank_account_holder')
                """, Integer.class);
        assertThat(leftover).isZero();
    }

    @Test
    void 계좌_칸이_일부만_찬_도매처가_있으면_이관이_멈춘다() {
        JdbcTemplate jdbc = 빈_DB를_만든다();
        flyway(jdbc, "14", "classpath:db/migration").migrate();
        Flyway flyway = flyway(jdbc, "latest", "classpath:db/migration");
        도매처(jdbc, "half@ondo.test", "1000000004", "국민은행", null, "김반쪽");

        // 칸을 지우는 순간 값이 사라진다 — 조용히 버리지 않고 멈춘다
        assertThatThrownBy(flyway::migrate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("MUL-130");
    }

    @Test
    void 주계좌는_도매처당_하나뿐이고_같은_은행_계좌번호는_한번뿐이다() {
        JdbcTemplate jdbc = 빈_DB를_만든다();
        flyway(jdbc, "latest", "classpath:db/migration").migrate();
        long 무드온 = 도매처(jdbc, "moodon@ondo.test", "1000000005", null, null, null);

        계좌(jdbc, 무드온, "국민은행", "111", true);
        계좌(jdbc, 무드온, "국민은행", "222", false);
        계좌(jdbc, 무드온, "신한은행", "111", false);

        assertThatThrownBy(() -> 계좌(jdbc, 무드온, "기업은행", "333", true))
                .hasMessageContaining("bank_account_primary_uk");
        assertThatThrownBy(() -> 계좌(jdbc, 무드온, "국민은행", "111", false))
                .hasMessageContaining("bank_account_no_uk");
    }

    @Test
    void dev_시드는_도매처_101에서_103에_주계좌를_하나씩_넣는다() {
        JdbcTemplate jdbc = 빈_DB를_만든다();
        flyway(jdbc, "latest", "classpath:db/migration", "classpath:db/seed").migrate();

        List<Map<String, Object>> primaries = jdbc.queryForList(
                "select wholesaler_id, count(*) as n from wholesale.bank_account"
                        + " where is_primary group by wholesaler_id order by wholesaler_id");
        assertThat(primaries).extracting(r -> ((Number) r.get("wholesaler_id")).longValue())
                .containsExactly(101L, 102L, 103L);
        assertThat(primaries).allSatisfy(r -> assertThat(((Number) r.get("n")).intValue()).isEqualTo(1));
    }

    private static JdbcTemplate 빈_DB를_만든다() {
        String name = "mul130_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        new JdbcTemplate(dataSource(POSTGRES.getJdbcUrl())).execute("CREATE DATABASE " + name);
        String url = POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        return new JdbcTemplate(dataSource(url));
    }

    private static DriverManagerDataSource dataSource(String url) {
        return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** 앱(application.yml)과 같은 설정 — 기본 스키마 wholesale, out-of-order 허용. */
    private static Flyway flyway(JdbcTemplate jdbc, String target, String... locations) {
        return Flyway.configure()
                .dataSource(jdbc.getDataSource())
                .target(target)
                .defaultSchema("wholesale")
                .outOfOrder(true)
                .locations(locations)
                .load();
    }

    private static long 도매처(JdbcTemplate jdbc, String email, String bizRegNo,
                            String bankName, String accountNo, String holder) {
        boolean hasBankColumns = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from information_schema.columns
                               where table_schema = 'wholesale' and table_name = 'wholesaler'
                                 and column_name = 'bank_name')
                """, Boolean.class));
        if (!hasBankColumns) {
            return jdbc.queryForObject("""
                    insert into wholesale.wholesaler (email, password_hash, biz_reg_no, biz_name, biz_owner_name)
                    values (?, 'x', ?, '테스트도매', '김테스트') returning id
                    """, Long.class, email, bizRegNo);
        }
        return jdbc.queryForObject("""
                insert into wholesale.wholesaler (email, password_hash, biz_reg_no, biz_name, biz_owner_name,
                                                  bank_name, bank_account_no, bank_account_holder)
                values (?, 'x', ?, '테스트도매', '김테스트', ?, ?, ?) returning id
                """, Long.class, email, bizRegNo, bankName, accountNo, holder);
    }

    private static void 계좌(JdbcTemplate jdbc, long wholesalerId, String bankName, String accountNo, boolean primary) {
        jdbc.update("insert into wholesale.bank_account (wholesaler_id, bank_name, account_no, account_holder, is_primary)"
                + " values (?, ?, ?, '김테스트', ?)", wholesalerId, bankName, accountNo, primary);
    }
}
