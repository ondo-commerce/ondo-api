package com.ondo.wholesale.settlement;

import com.ondo.wholesale.settlement.dto.BankAccountCreateRequest;
import com.ondo.wholesale.settlement.dto.BankAccountUpdateRequest;
import com.ondo.wholesale.settlement.service.BankAccountService;
import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시 요청에서도 주계좌가 0 또는 1개인지 (MUL-130 완료 조건 "어떤 순간에도").
 *
 * <p>잡고 싶은 사고: 화면 연타·두 탭에서 동시에 첫 계좌를 넣으면 둘 다 "계좌가 없다" 를 보고
 * 둘 다 주계좌로 넣으려 한다. 부분 유니크(bank_account_primary_uk)는 둘째를 막지만 500 으로 깨진다.
 * 도매처 행 잠금이 요청을 한 줄로 세워 전부 정상 응답이 되는지 본다 — 그래서 "주계좌 1개" 만이 아니라
 * "실패 0건" 도 단언한다.
 *
 * <p>규칙 자체는 BankAccountIntegrationTest 가 본다. 여기는 스레드마다 진짜 트랜잭션이 커밋돼야 해서
 * {@code @Transactional} 을 안 붙이고 직접 지운다.
 */
@SpringBootTest
class BankAccountConcurrencyIntegrationTest extends PostgresTestSupport {

    private static final int THREADS = 8;

    @Autowired BankAccountService service;
    @Autowired JdbcTemplate jdbc;

    private long 무드온;

    @BeforeEach
    void 도매처를_심는다() {
        무드온 = MasterDataFixture.도매처를_넣는다(jdbc, "bank-concurrency@ondo.test", "9500001309");
    }

    @AfterEach
    void 지운다() {
        jdbc.update("delete from wholesale.bank_account where wholesaler_id = ?", 무드온);
        jdbc.update("delete from wholesale.wholesaler where id = ?", 무드온);
    }

    @Test
    void 첫_계좌를_동시에_여러_개_넣어도_주계좌는_하나고_전부_성공한다() throws Exception {
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String accountNo = "100-" + i;
            tasks.add(() -> service.create(무드온, 등록요청(accountNo, false)));
        }

        assertThat(동시에_돌린다(tasks)).isZero();

        assertThat(계좌_수()).isEqualTo(THREADS);
        assertThat(주계좌_수()).isEqualTo(1);
    }

    @Test
    void 주계좌_지정_등록과_승격과_주계좌_삭제가_섞여도_주계좌는_하나다() throws Exception {
        long 처음 = service.create(무드온, 등록요청("000-0", false)).id();
        List<Long> 일반 = new ArrayList<>();
        for (int i = 1; i <= THREADS; i++) {
            일반.add(service.create(무드온, 등록요청("000-" + i, false)).id());
        }

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String accountNo = "200-" + i;
            tasks.add(() -> service.create(무드온, 등록요청(accountNo, true)));
            long target = 일반.get(i);
            tasks.add(() -> {
                service.update(무드온, target, 주계좌로());
                return null;
            });
        }
        tasks.add(() -> {
            service.delete(무드온, 처음);
            return null;
        });

        assertThat(동시에_돌린다(tasks)).isZero();

        assertThat(계좌_수()).isEqualTo(THREADS * 2);
        assertThat(주계좌_수()).isEqualTo(1);
    }

    /** 출발선에 세웠다가 한꺼번에 놓는다. 실패한 작업 수를 돌려준다. */
    private static int 동시에_돌린다(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            int failed = 0;
            for (Future<Object> f : futures) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    failed++;
                }
            }
            return failed;
        } finally {
            pool.shutdownNow();
        }
    }

    private static BankAccountCreateRequest 등록요청(String accountNo, boolean primary) {
        return new BankAccountCreateRequest("국민은행", accountNo, "김무드", null, primary);
    }

    private static BankAccountUpdateRequest 주계좌로() {
        BankAccountUpdateRequest request = new BankAccountUpdateRequest();
        request.setIsPrimary(Optional.of(true));
        return request;
    }

    private int 계좌_수() {
        return jdbc.queryForObject("select count(*) from wholesale.bank_account where wholesaler_id = ?",
                Integer.class, 무드온);
    }

    private int 주계좌_수() {
        return jdbc.queryForObject(
                "select count(*) from wholesale.bank_account where wholesaler_id = ? and is_primary",
                Integer.class, 무드온);
    }
}
