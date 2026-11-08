package com.ondo.wholesale.retailgateway;

import com.ondo.wholesale.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 소매 알림 게이트웨이 (MUL-155) — 목록 keyset · 배지 · 읽음.
 *
 * <p>알림이 언제 생기는지는 ExpectedInboundNotificationIntegrationTest 가 본다. 여기는 행을 SQL 로
 * 직접 심고 소매가 읽어 가는 길만 본다. 커서 문자열·프론트 400 은 소매 몫이라 소매 테스트가 본다.
 *
 * <p>절반은 <b>남의 것이 안 보이는지</b>다. 알림은 소매처별로 잘린 데이터다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RetailGatewayNotificationApiTest extends PostgresTestSupport {

    private static final long 우리 = 8201L;
    private static final long 남 = 8202L;
    private static final String 목록 = "/api/retail-gateway/notifications";

    /** 기준 시각. 마이크로초까지 있어야 keyset 정밀도를 볼 수 있다. */
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 7, 1, 0, 0, 123_456_000, ZoneOffset.UTC);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private long 첫째;
    private long 동시_큰id;
    private long 동시_작은id;
    private long 넷째;

    @BeforeEach
    void 알림을_심는다() {
        // 최신순: 첫째(T0) > 동시 둘(T0-1h, id 로 갈림) > 넷째(T0-2h)
        동시_작은id = 알림(우리, T0.minusHours(1), null);
        넷째 = 알림(우리, T0.minusHours(2), T0);
        첫째 = 알림(우리, T0, null);
        동시_큰id = 알림(우리, T0.minusHours(1), T0);
        알림(남, T0.plusHours(1), null);
    }

    @Test
    @DisplayName("내 것만 createdAt desc, id desc 로 나온다")
    void 내_것만_정해진_순서로() throws Exception {
        mvc.perform(get(목록).param("retailerId", String.valueOf(우리)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].id").value(첫째))
                .andExpect(jsonPath("$.data[1].id").value(동시_큰id))
                .andExpect(jsonPath("$.data[2].id").value(동시_작은id))
                .andExpect(jsonPath("$.data[3].id").value(넷째))
                .andExpect(jsonPath("$.data[0].type").value("BACKORDER_DATE_CHANGED"))
                .andExpect(jsonPath("$.data[0].target.kind").value("BACKORDERS"))
                .andExpect(jsonPath("$.data[0].target.wholesalerId").value(101))
                // 빈 갈래 칸은 아예 없다 (additionalProperties: false)
                .andExpect(jsonPath("$.data[0].target.orderId").doesNotExist())
                .andExpect(jsonPath("$.data[0].readAt").isEmpty());
    }

    @Test
    @DisplayName("keyset 은 마이크로초까지 맞고, 같은 시각은 id 로 갈린다")
    void keyset_다음_장() throws Exception {
        // 직전 장 마지막 = 동시_큰id. 그 뒤는 동시_작은id, 넷째
        mvc.perform(get(목록).param("retailerId", String.valueOf(우리))
                        .param("beforeMicros", String.valueOf(micros(T0.minusHours(1))))
                        .param("beforeId", String.valueOf(동시_큰id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(동시_작은id))
                .andExpect(jsonPath("$.data[1].id").value(넷째));
    }

    @Test
    void limit_범위_밖은_400_VALIDATION_FAILED이다() throws Exception {
        mvc.perform(get(목록).param("retailerId", "1").param("limit", "102"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
    }

    @Test
    @DisplayName("배지 — 안 읽은 수와 목록 첫 행 id (id 최대값이 아니다)")
    void 배지() throws Exception {
        mvc.perform(get(목록 + "/unread-count").param("retailerId", String.valueOf(우리)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications").value(2))
                .andExpect(jsonPath("$.data.latestNotificationId").value(첫째));

        mvc.perform(get(목록 + "/unread-count").param("retailerId", "8299"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications").value(0))
                .andExpect(jsonPath("$.data.latestNotificationId").isEmpty());
    }

    @Test
    @DisplayName("ids 읽음은 멱등이고 남의 id 는 조용히 무시한다")
    void ids_읽음() throws Exception {
        long 남의것 = jdbc.queryForObject(
                "select id from wholesale.retail_notification where retailer_id = ?", Long.class, 남);

        mvc.perform(읽음("{\"retailerId\":%d,\"ids\":[%d,%d,%d,999999]}".formatted(우리, 첫째, 넷째, 남의것)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications").value(1));

        // 이미 읽은 넷째의 readAt 은 처음 값(T0) 그대로다
        assertThat(readAt(넷째)).isEqualTo(T0.toInstant());
        assertThat(readAt(첫째)).isNotNull();
        assertThat(readAt(남의것)).isNull();
    }

    @Test
    void 모두_읽음은_수가_맞으면_전부_읽는다() throws Exception {
        mvc.perform(읽음("{\"retailerId\":%d,\"all\":true,\"expectedUnread\":2}".formatted(우리)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications").value(0));

        assertThat(readAt(동시_작은id)).isNotNull();
    }

    @Test
    void 모두_읽음의_수가_다르면_409_UNREAD_CHANGED이고_아무것도_안_바뀐다() throws Exception {
        mvc.perform(읽음("{\"retailerId\":%d,\"all\":true,\"expectedUnread\":1}".formatted(우리)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNREAD_CHANGED"));

        assertThat(readAt(첫째)).isNull();
        assertThat(readAt(동시_작은id)).isNull();
    }

    @Test
    void ids_와_all_이_같이_오면_400_VALIDATION_FAILED이다() throws Exception {
        mvc.perform(읽음("{\"retailerId\":%d,\"ids\":[1],\"all\":true,\"expectedUnread\":1}".formatted(우리)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ── 픽스처 ──────────────────────────────────────────────

    private long 알림(long retailerId, OffsetDateTime createdAt, OffsetDateTime readAt) {
        return jdbc.queryForObject("""
                insert into wholesale.retail_notification
                    (retailer_id, type, title, body, target_kind, target_wholesaler_id, created_at, read_at)
                values (?, 'BACKORDER_DATE_CHANGED', '무드온 미송 입고일 변경', '린넨 셋업 자켓 베이지/L 남은 2장',
                        'BACKORDERS', 101, ?, ?)
                returning id
                """, Long.class, retailerId, createdAt, readAt);
    }

    private Instant readAt(long id) {
        OffsetDateTime value = jdbc.queryForObject(
                "select read_at from wholesale.retail_notification where id = ?", OffsetDateTime.class, id);
        return value == null ? null : value.toInstant();
    }

    private static long micros(OffsetDateTime at) {
        Instant i = at.toInstant();
        return i.getEpochSecond() * 1_000_000L + i.getNano() / 1_000L;
    }

    private static MockHttpServletRequestBuilder 읽음(String body) {
        return post(목록 + "/read").contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
