package com.ondo.wholesale.notification;

import com.jayway.jsonpath.JsonPath;
import com.ondo.wholesale.security.ApprovalStatus;
import com.ondo.wholesale.security.WholesalePrincipal;
import com.ondo.wholesale.security.support.TestSecuritySupport;
import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.NotificationFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 도매 알림 3 op 통합 검증 (MUL-154) — 목록 keyset 페이징, 안 읽음 수, 읽음 처리, 400 매핑.
 *
 * <p>새 주문 알림이 소매 접수와 한 트랜잭션에 생기는지는 {@code RetailGatewayOrderApiTest} 가,
 * 테이블·인덱스는 {@code NotificationSchemaTest} 가, 모두 읽음 409 의 롤백은 테스트 트랜잭션 밖에서 봐야 해
 * {@code NotificationReadAllRollbackIntegrationTest} 가 본다. 여기의 알림은 픽스처로 직접 심는다.
 *
 * <p>잡고 싶은 사고 둘. ① 첫 장을 받은 뒤 새 알림이 오면 다음 장이 밀려 중복·누락이 생기는 것
 * (오프셋 페이징의 고질), ② 읽음이 사람별로 갈려 같은 가게의 다른 탭·직원 화면에 배지가 남는 것.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NotificationIntegrationTest extends PostgresTestSupport {

    private static final OffsetDateTime 기준 = OffsetDateTime.parse("2026-11-01T10:00:00+09:00");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private long 무드온;
    private long 라온;

    @BeforeEach
    void 도매처를_심는다() {
        무드온 = MasterDataFixture.도매처를_넣는다(jdbc, "notification-a@ondo.test", "9500000154");
        라온 = MasterDataFixture.도매처를_넣는다(jdbc, "notification-b@ondo.test", "9500000155");
    }

    // ── 목록 ────────────────────────────────────────────────────

    @Test
    void 목록은_createdAt_내림차순이고_같으면_id_내림차순이다() throws Exception {
        long 오래된 = NotificationFixture.안읽은_알림을_넣는다(jdbc, 무드온, 기준.minusHours(2));
        long 같은시각_앞 = NotificationFixture.안읽은_알림을_넣는다(jdbc, 무드온, 기준);
        long 같은시각_뒤 = NotificationFixture.안읽은_알림을_넣는다(jdbc, 무드온, 기준);
        // id 가 가장 크지만 가장 오래됐다 — id 만으로 정렬하면 틀린다
        long 늦게_넣은_옛것 = NotificationFixture.안읽은_알림을_넣는다(jdbc, 무드온, 기준.minusHours(5));

        mvc.perform(get("/api/wholesale/notifications").with(TestSecuritySupport.approvedAs(무드온)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].id").value(같은시각_뒤))
                .andExpect(jsonPath("$.data[1].id").value(같은시각_앞))
                .andExpect(jsonPath("$.data[2].id").value(오래된))
                .andExpect(jsonPath("$.data[3].id").value(늦게_넣은_옛것))
                .andExpect(jsonPath("$.data[0].type").value("NEW_ORDER"))
                .andExpect(jsonPath("$.data[0].title").value("봄봄상회 새 주문"))
                .andExpect(jsonPath("$.data[0].body").value("빈티지 셔츠 블랙 / M, 1장"))
                .andExpect(jsonPath("$.data[0].target.kind").value("ORDER"))
                .andExpect(jsonPath("$.data[0].target.id").value(1))
                .andExpect(jsonPath("$.data[0].createdAt").isNotEmpty())
                // 안 읽음은 칸을 빼지 않고 null 로 낸다(required)
                .andExpect(jsonPath("$.data[0].readAt").value(nullValue()))
                .andExpect(jsonPath("$.meta.nextCursor").value(nullValue()));
    }

    @Test
    void 다른_도매처의_알림은_목록에_없다() throws Exception {
        NotificationFixture.안읽은_알림을_넣는다(jdbc, 라온, 기준);

        mvc.perform(get("/api/wholesale/notifications").with(TestSecuritySupport.approvedAs(무드온)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.nextCursor").value(nullValue()));
    }

    @Test
    void 첫_장을_받은_뒤_새_알림이_와도_다음_장에_중복과_누락이_없다() throws Exception {
        List<Long> 원래 = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            원래.add(NotificationFixture.안읽은_알림을_넣는다(jdbc, 무드온, 기준.minusMinutes(i)));
        }

        String 첫장 = 목록(null, 20).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Long> 첫장_id = ids(첫장);
        String 커서 = JsonPath.read(첫장, "$.meta.nextCursor");
        assertThat(첫장_id).hasSize(20);
        assertThat(커서).isNotNull();

        // 사이에 새 알림 셋 — 하나는 마지막 행과 같은 시각이지만 id 가 더 커서 순서상 커서 앞이다
        OffsetDateTime 첫장_끝_시각 = 기준.minusMinutes(19);
        NotificationFixture.안읽은_알림을_넣는다(jdbc, 무드온, 기준.plusMinutes(1));
        NotificationFixture.안읽은_알림을_넣는다(jdbc, 무드온, 기준.plusMinutes(2));
        NotificationFixture.안읽은_알림을_넣는다(jdbc, 무드온, 첫장_끝_시각);

        String 둘째장 = 목록(커서, 20).andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.nextCursor").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        List<Long> 둘째장_id = ids(둘째장);

        List<Long> 합친 = new ArrayList<>(첫장_id);
        합친.addAll(둘째장_id);
        // 원래 25개가 순서대로 한 번씩 — 새 알림은 첫 장 앞에 붙을 뿐 다음 장에 끼지 않는다
        assertThat(합친).containsExactlyElementsOf(원래);
    }

    @Test
    void 같은_시각이_장_경계에_걸려도_id로_갈린다() throws Exception {
        long[] 같은시각 = LongStream.range(0, 5)
                .map(i -> NotificationFixture.안읽은_알림을_넣는다(jdbc, 무드온, 기준))
                .toArray();

        List<Long> 전부 = 끝까지_읽는다(2);

        assertThat(전부).containsExactly(같은시각[4], 같은시각[3], 같은시각[2], 같은시각[1], 같은시각[0]);
    }

    @Test
    void 같은_밀리초_안의_마이크로초_차이도_잃지_않는다() throws Exception {
        // 커서를 밀리초로 자르면 둘째 행이 커서와 "같은 시각" 으로 계산돼 사라진다
        long 늦은 = NotificationFixture.안읽은_알림을_넣는다(jdbc, 무드온, 기준.plusNanos(700_000));
        long 이른 = NotificationFixture.안읽은_알림을_넣는다(jdbc, 무드온, 기준.plusNanos(300_000));

        assertThat(끝까지_읽는다(1)).containsExactly(늦은, 이른);
    }

    @Test
    void size를_생략하면_20개씩이다() throws Exception {
        for (int i = 0; i < 21; i++) {
            NotificationFixture.안읽은_알림을_넣는다(jdbc, 무드온, 기준.minusMinutes(i));
        }

        목록(null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(20))
                .andExpect(jsonPath("$.meta.nextCursor").isNotEmpty());
    }

    @Test
    void size가_0이면_VALIDATION_FAILED_400이다() throws Exception {
        목록(null, 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    void size가_101이면_VALIDATION_FAILED_400이다() throws Exception {
        목록(null, 101)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    void size가_숫자가_아니면_VALIDATION_FAILED_400이다() throws Exception {
        mvc.perform(get("/api/wholesale/notifications").param("size", "abc")
                        .with(TestSecuritySupport.approvedAs(무드온)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    void 잘못된_cursor는_VALIDATION_FAILED_400이다() throws Exception {
        String 모양이_틀린 = Base64.getUrlEncoder().encodeToString("abc|1".getBytes(StandardCharsets.UTF_8));
        for (String cursor : List.of("!!!", 모양이_틀린, "MTIz")) {
            목록(cursor, 20)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("cursor"));
        }
    }

    // ── 안 읽음 수 ──────────────────────────────────────────────

    @Test
    void 안읽음_수는_내_안읽은_알림만_센다() throws Exception {
        NotificationFixture.안읽은_알림을_넣는다(jdbc, 무드온, 기준.minusHours(1));
        long 최신 = NotificationFixture.안읽은_알림을_넣는다(jdbc, 무드온, 기준);
        NotificationFixture.알림을_넣는다(jdbc, 무드온, 기준.minusHours(2), 기준.minusHours(1));
        // id 는 더 크지만 오래된 것 — latestNotificationId 는 id 최대값이 아니라 목록 첫 행이다
        NotificationFixture.알림을_넣는다(jdbc, 무드온, 기준.minusDays(3), 기준.minusDays(2));
        NotificationFixture.안읽은_알림을_넣는다(jdbc, 라온, 기준);

        mvc.perform(get("/api/wholesale/notifications/unread-count").with(TestSecuritySupport.approvedAs(무드온)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications").value(2))
                .andExpect(jsonPath("$.data.chatsNeedingReply").value(0))
                .andExpect(jsonPath("$.data.latestNotificationId").value(최신));
    }

    @Test
    void 알림이_없으면_latestNotificationId는_null이다() throws Exception {
        mvc.perform(get("/api/wholesale/notifications/unread-count").with(TestSecuritySupport.approvedAs(무드온)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications").value(0))
                .andExpect(jsonPath("$.data.latestNotificationId").value(nullValue()));
    }

    // ── 읽음 ────────────────────────────────────────────────────

    @Test
    void 한_직원이_읽으면_같은_가게의_다른_직원도_읽은_것으로_본다() throws Exception {
        long 알림 = NotificationFixture.안읽은_알림을_넣는다(jdbc, 무드온, 기준);
        NotificationFixture.안읽은_알림을_넣는다(jdbc, 무드온, 기준.minusHours(1));

        읽음(직원(무드온, "owner@moodon.test"), "{\"ids\": [%d]}".formatted(알림))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications").value(1));

        mvc.perform(get("/api/wholesale/notifications/unread-count").with(직원(무드온, "staff@moodon.test")))
                .andExpect(jsonPath("$.data.notifications").value(1));
        mvc.perform(get("/api/wholesale/notifications").with(직원(무드온, "staff@moodon.test")))
                .andExpect(jsonPath("$.data[0].id").value(알림))
                .andExpect(jsonPath("$.data[0].readAt").isNotEmpty());
    }

    @Test
    void ids_읽음은_멱등이고_처음_읽은_시각을_지킨다() throws Exception {
        OffsetDateTime 처음_읽은 = 기준.plusMinutes(5);
        long 읽은 = NotificationFixture.알림을_넣는다(jdbc, 무드온, 기준, 처음_읽은);

        읽음(TestSecuritySupport.approvedAs(무드온), "{\"ids\": [%d, %d]}".formatted(읽은, 읽은))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications").value(0));

        OffsetDateTime readAt = jdbc.queryForObject(
                "select read_at from wholesale.notification where id = ?", OffsetDateTime.class, 읽은);
        assertThat(readAt.toInstant()).isEqualTo(처음_읽은.toInstant());
    }

    @Test
    void 없는_id와_남의_id는_조용히_무시한다() throws Exception {
        long 남의것 = NotificationFixture.안읽은_알림을_넣는다(jdbc, 라온, 기준);
        NotificationFixture.안읽은_알림을_넣는다(jdbc, 무드온, 기준);

        읽음(TestSecuritySupport.approvedAs(무드온), "{\"ids\": [%d, 999999999]}".formatted(남의것))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications").value(1));

        assertThat(jdbc.queryForObject("select read_at is null from wholesale.notification where id = ?",
                Boolean.class, 남의것)).isTrue();
    }

    @Test
    void 모두_읽음은_수가_맞으면_전부_읽고_남의_것은_안_건드린다() throws Exception {
        NotificationFixture.안읽은_알림을_넣는다(jdbc, 무드온, 기준);
        NotificationFixture.안읽은_알림을_넣는다(jdbc, 무드온, 기준.minusHours(1));
        long 남의것 = NotificationFixture.안읽은_알림을_넣는다(jdbc, 라온, 기준);

        읽음(TestSecuritySupport.approvedAs(무드온), "{\"all\": true, \"expectedUnread\": 2}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications").value(0));

        assertThat(jdbc.queryForObject("select read_at is null from wholesale.notification where id = ?",
                Boolean.class, 남의것)).isTrue();
    }

    @Test
    void 읽음_본문이_규칙에_맞지_않으면_VALIDATION_FAILED_400이다() throws Exception {
        String 백한개 = LongStream.rangeClosed(1, 101).mapToObj(String::valueOf).collect(Collectors.joining(","));
        List<String[]> 경우 = List.of(
                new String[]{"{\"ids\": [1], \"all\": true}", "all"},
                new String[]{"{}", "ids"},
                new String[]{"{\"ids\": []}", "ids"},
                new String[]{"{\"ids\": [" + 백한개 + "]}", "ids"},
                new String[]{"{\"ids\": [1.5]}", "ids"},
                new String[]{"{\"ids\": [1], \"foo\": 1}", "foo"},
                new String[]{"{\"all\": true}", "expectedUnread"},
                new String[]{"{\"all\": true, \"expectedUnread\": 0}", "expectedUnread"},
                new String[]{"{\"all\": false, \"expectedUnread\": 1}", "all"});
        for (String[] c : 경우) {
            읽음(TestSecuritySupport.approvedAs(무드온), c[0])
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
    }

    // ── 인증 ────────────────────────────────────────────────────

    @Test
    void 세션이_없으면_401_UNAUTHENTICATED이다() throws Exception {
        mvc.perform(get("/api/wholesale/notifications/unread-count"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 승인_전이면_403_NOT_APPROVED이다() throws Exception {
        mvc.perform(get("/api/wholesale/notifications").with(TestSecuritySupport.pending()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_APPROVED"));
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private ResultActions 목록(String cursor, Integer size) throws Exception {
        var req = get("/api/wholesale/notifications").with(TestSecuritySupport.approvedAs(무드온));
        if (cursor != null) {
            req = req.param("cursor", cursor);
        }
        if (size != null) {
            req = req.param("size", String.valueOf(size));
        }
        return mvc.perform(req);
    }

    private List<Long> 끝까지_읽는다(int size) throws Exception {
        List<Long> 전부 = new ArrayList<>();
        String cursor = null;
        do {
            String body = 목록(cursor, size).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            전부.addAll(ids(body));
            cursor = JsonPath.read(body, "$.meta.nextCursor");
        } while (cursor != null);
        return 전부;
    }

    private ResultActions 읽음(RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(post("/api/wholesale/notifications/read").with(who)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static List<Long> ids(String body) {
        List<Number> raw = JsonPath.read(body, "$.data[*].id");
        return raw.stream().map(Number::longValue).toList();
    }

    /** 같은 도매처의 다른 사람 — 이메일만 다르고 도매처 id 는 같다. */
    private static RequestPostProcessor 직원(long wholesalerId, String email) {
        WholesalePrincipal principal = new WholesalePrincipal(wholesalerId, email, ApprovalStatus.APPROVED);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_WHOLESALER"))));
    }
}
