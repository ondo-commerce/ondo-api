package com.ondo.wholesale.settlement;

import com.ondo.wholesale.security.support.TestSecuritySupport;
import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 정산 계좌 API 4종 통합 검증 (MUL-130) — 계약 스텁(MUL-83)의 규칙 그대로인지.
 *
 * <p>여기서 보는 것: 목록 순서, 첫 계좌 = 주계좌, 주계좌 교체, 중복, 부분 수정, 주계좌 해제 금지,
 * 삭제와 승격, 소유 스코핑, 에러의 HTTP 매핑. 동시 요청에서 주계좌 0·1 개가 지켜지는지는
 * BankAccountConcurrencyIntegrationTest 가, 스키마·이관은 BankAccountMigrationTest 가 본다.
 * 소매로 나가는 계좌(주계좌)는 RetailGateway*ApiTest 가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BankAccountIntegrationTest extends PostgresTestSupport {

    private static final String URL = "/api/wholesale/bank-accounts";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private long 무드온;
    private long 라온;

    @BeforeEach
    void 도매처를_심는다() {
        무드온 = MasterDataFixture.도매처를_넣는다(jdbc, "bank-moodon@ondo.test", "9500001301");
        라온 = MasterDataFixture.도매처를_넣는다(jdbc, "bank-raon@ondo.test", "9500001302");
    }

    // ── 목록 ────────────────────────────────────────────────────

    @Test
    void 계좌가_없으면_빈_목록이다() throws Exception {
        mvc.perform(get(URL).with(TestSecuritySupport.approvedAs(무드온)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta").doesNotExist());
    }

    @Test
    void 목록은_주계좌_먼저_그다음_등록순이고_남의_계좌는_안_보인다() throws Exception {
        long 첫째 = 등록_id("국민은행", "111", null);
        long 둘째 = 등록_id("신한은행", "222", null);
        long 셋째 = 등록_id("기업은행", "333", true);
        MasterDataFixture.계좌를_넣는다(jdbc, 라온, "우리은행", "999", "이라온", true);

        mvc.perform(get(URL).with(TestSecuritySupport.approvedAs(무드온)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].id").value(셋째))
                .andExpect(jsonPath("$.data[0].isPrimary").value(true))
                .andExpect(jsonPath("$.data[1].id").value(첫째))
                .andExpect(jsonPath("$.data[1].isPrimary").value(false))
                .andExpect(jsonPath("$.data[2].id").value(둘째));
    }

    // ── 등록 ────────────────────────────────────────────────────

    @Test
    void 첫_계좌는_isPrimary_false여도_주계좌가_되고_201로_계좌를_내린다() throws Exception {
        등록("신한은행", "110-482-948102", false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.bankName").value("신한은행"))
                .andExpect(jsonPath("$.data.accountNo").value("110-482-948102"))
                .andExpect(jsonPath("$.data.accountHolder").value("서울유통"))
                .andExpect(jsonPath("$.data.memo").value("주거래 계좌"))
                .andExpect(jsonPath("$.data.isPrimary").value(true))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty());
    }

    @Test
    void isPrimary_없이_등록한_둘째_계좌는_일반_계좌다() throws Exception {
        등록_id("국민은행", "111", null);

        등록("신한은행", "222", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.isPrimary").value(false));
        assertThat(주계좌_수(무드온)).isEqualTo(1);
    }

    @Test
    void isPrimary_true로_등록하면_기존_주계좌가_내려간다() throws Exception {
        long 기존 = 등록_id("국민은행", "111", null);

        등록("신한은행", "222", true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.isPrimary").value(true));

        assertThat(주계좌인가(기존)).isFalse();
        assertThat(주계좌_수(무드온)).isEqualTo(1);
    }

    @Test
    void 같은_은행_같은_계좌번호는_400_DUPLICATE_BANK_ACCOUNT이고_기존_계좌_id를_준다() throws Exception {
        long 기존 = 등록_id("국민은행", "111", null);

        등록("국민은행", "111", true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BANK_ACCOUNT"))
                .andExpect(jsonPath("$.errors[0].field").value("accountNo"))
                .andExpect(jsonPath("$.errors[0].data.bankAccountId").value(기존));
        assertThat(주계좌인가(기존)).isTrue();
    }

    @Test
    void 은행이_다르거나_도매처가_다르면_같은_계좌번호도_등록된다() throws Exception {
        등록_id("국민은행", "111", null);
        등록("신한은행", "111", null).andExpect(status().isCreated());
        MasterDataFixture.계좌를_넣는다(jdbc, 라온, "국민은행", "111", "이라온", true);
    }

    @Test
    void 필수값이_비면_400_VALIDATION_FAILED이다() throws Exception {
        mvc.perform(post(URL).with(TestSecuritySupport.approvedAs(무드온))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "bankName": "국민은행", "accountNo": " " }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(계좌_수(무드온)).isZero();
    }

    // ── 수정 ────────────────────────────────────────────────────

    @Test
    void 수정은_보낸_필드만_바꾼다() throws Exception {
        long 계좌 = 등록_id("국민은행", "111", null);

        수정(계좌, """
                { "accountHolder": "김무드" }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(계좌))
                .andExpect(jsonPath("$.data.bankName").value("국민은행"))
                .andExpect(jsonPath("$.data.accountNo").value("111"))
                .andExpect(jsonPath("$.data.accountHolder").value("김무드"))
                .andExpect(jsonPath("$.data.memo").value("주거래 계좌"))
                .andExpect(jsonPath("$.data.isPrimary").value(true));
    }

    @Test
    void memo는_null로_지운다() throws Exception {
        long 계좌 = 등록_id("국민은행", "111", null);

        수정(계좌, """
                { "memo": null }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memo").value(nullValue()));
    }

    @Test
    void memo_말고_다른_필드의_null은_400_VALIDATION_FAILED이다() throws Exception {
        long 계좌 = 등록_id("국민은행", "111", null);

        수정(계좌, """
                { "bankName": null }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("bankName"));
        수정(계좌, """
                { "isPrimary": null }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 주계좌를_isPrimary_false로_해제하면_400_PRIMARY_ACCOUNT_CANNOT_BE_UNSET이다() throws Exception {
        long 주계좌 = 등록_id("국민은행", "111", null);

        수정(주계좌, """
                { "isPrimary": false, "memo": "바뀌면 안 된다" }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRIMARY_ACCOUNT_CANNOT_BE_UNSET"));
        assertThat(주계좌인가(주계좌)).isTrue();
    }

    @Test
    void 일반_계좌에_isPrimary_false는_아무것도_안_바꾼다() throws Exception {
        등록_id("국민은행", "111", null);
        long 일반 = 등록_id("신한은행", "222", null);

        수정(일반, """
                { "isPrimary": false }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isPrimary").value(false));
    }

    @Test
    void 일반_계좌를_주계좌로_올리면_기존_주계좌가_내려간다() throws Exception {
        long 기존 = 등록_id("국민은행", "111", null);
        long 일반 = 등록_id("신한은행", "222", null);

        수정(일반, """
                { "isPrimary": true }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isPrimary").value(true));

        assertThat(주계좌인가(기존)).isFalse();
        assertThat(주계좌_수(무드온)).isEqualTo(1);
    }

    @Test
    void 수정으로_다른_계좌와_같아지면_400_DUPLICATE_BANK_ACCOUNT이다() throws Exception {
        long 국민 = 등록_id("국민은행", "111", null);
        long 신한 = 등록_id("신한은행", "111", null);

        수정(신한, """
                { "bankName": "국민은행" }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BANK_ACCOUNT"))
                .andExpect(jsonPath("$.errors[0].data.bankAccountId").value(국민));
    }

    @Test
    void 자기_값_그대로_보내는_것은_중복이_아니다() throws Exception {
        long 계좌 = 등록_id("국민은행", "111", null);

        수정(계좌, """
                { "bankName": "국민은행", "accountNo": "111" }
                """)
                .andExpect(status().isOk());
    }

    @Test
    void 남의_계좌_수정은_404_RESOURCE_NOT_FOUND이다() throws Exception {
        long 라온계좌 = MasterDataFixture.계좌를_넣는다(jdbc, 라온, "우리은행", "999", "이라온", true);

        수정(라온계좌, """
                { "memo": "남의 것" }
                """)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // ── 삭제 ────────────────────────────────────────────────────

    @Test
    void 삭제는_204_본문없음이고_행이_완전히_지워진다() throws Exception {
        등록_id("국민은행", "111", null);
        long 일반 = 등록_id("신한은행", "222", null);

        삭제(일반)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        assertThat(jdbc.queryForObject("select count(*) from wholesale.bank_account where id = ?",
                Integer.class, 일반)).isZero();
    }

    @Test
    void 주계좌를_지우면_남은_것_중_가장_먼저_등록한_계좌가_주계좌가_된다() throws Exception {
        long 첫째 = 등록_id("국민은행", "111", null);
        long 둘째 = 등록_id("신한은행", "222", null);
        long 셋째 = 등록_id("기업은행", "333", null);
        수정(셋째, "{ \"isPrimary\": true }").andExpect(status().isOk());
        삭제(첫째).andExpect(status().isNoContent());   // 일반 계좌 삭제는 주계좌를 안 건드린다
        assertThat(주계좌인가(셋째)).isTrue();

        삭제(셋째).andExpect(status().isNoContent());

        assertThat(주계좌인가(둘째)).isTrue();
        assertThat(주계좌_수(무드온)).isEqualTo(1);
    }

    @Test
    void 마지막_계좌를_지우면_계좌_0건이_된다() throws Exception {
        long 계좌 = 등록_id("국민은행", "111", null);

        삭제(계좌).andExpect(status().isNoContent());

        assertThat(계좌_수(무드온)).isZero();
    }

    @Test
    void 이미_지운_계좌나_남의_계좌_삭제는_404_RESOURCE_NOT_FOUND이다() throws Exception {
        long 계좌 = 등록_id("국민은행", "111", null);
        삭제(계좌).andExpect(status().isNoContent());
        long 라온계좌 = MasterDataFixture.계좌를_넣는다(jdbc, 라온, "우리은행", "999", "이라온", true);

        삭제(계좌)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        삭제(라온계좌).andExpect(status().isNotFound());
        assertThat(주계좌인가(라온계좌)).isTrue();
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private ResultActions 등록(String bankName, String accountNo, Boolean isPrimary) throws Exception {
        String primary = isPrimary == null ? "" : ", \"isPrimary\": " + isPrimary;
        return mvc.perform(post(URL).with(TestSecuritySupport.approvedAs(무드온))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "bankName": "%s", "accountNo": "%s", "accountHolder": "서울유통",
                          "memo": "주거래 계좌"%s }
                        """.formatted(bankName, accountNo, primary)));
    }

    private long 등록_id(String bankName, String accountNo, Boolean isPrimary) throws Exception {
        String body = 등록(bankName, accountNo, isPrimary)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private ResultActions 수정(long id, String json) throws Exception {
        return mvc.perform(patch(URL + "/" + id).with(TestSecuritySupport.approvedAs(무드온))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private ResultActions 삭제(long id) throws Exception {
        return mvc.perform(delete(URL + "/" + id).with(TestSecuritySupport.approvedAs(무드온)));
    }

    private boolean 주계좌인가(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select is_primary from wholesale.bank_account where id = ?", Boolean.class, id));
    }

    private int 주계좌_수(long wholesalerId) {
        return jdbc.queryForObject("select count(*) from wholesale.bank_account where wholesaler_id = ? and is_primary",
                Integer.class, wholesalerId);
    }

    private int 계좌_수(long wholesalerId) {
        return jdbc.queryForObject("select count(*) from wholesale.bank_account where wholesaler_id = ?",
                Integer.class, wholesalerId);
    }
}
