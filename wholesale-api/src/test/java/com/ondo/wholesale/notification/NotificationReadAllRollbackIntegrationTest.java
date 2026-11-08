package com.ondo.wholesale.notification;

import com.ondo.wholesale.security.support.TestSecuritySupport;
import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.NotificationFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 모두 읽음 409 UNREAD_CHANGED 의 롤백 검증 (MUL-154).
 *
 * <p>목록·읽음의 나머지는 {@code NotificationIntegrationTest} 가 본다.
 *
 * <p><b>이 클래스는 {@code @Transactional} 이 아니다.</b> 서비스는 안 읽음을 먼저 다 바꾼 뒤 수가
 * 다르면 예외로 되돌린다. 테스트 트랜잭션으로 감싸면 서비스가 그 안에 합류해 롤백 표시만 남기고
 * 바꾼 행은 테스트 끝까지 그대로 보인다 — "아무것도 안 바꿨다" 를 볼 수 없다. 그래서 실제로
 * 커밋·롤백되게 돌리고 직접 치운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationReadAllRollbackIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private long 무드온;

    @BeforeEach
    void 도매처를_심는다() {
        무드온 = MasterDataFixture.도매처를_넣는다(jdbc, "notification-rollback@ondo.test", "9500000156");
    }

    @AfterEach
    void 남긴_행을_지운다() {
        jdbc.update("delete from wholesale.notification where wholesaler_id = ?", 무드온);
        jdbc.update("delete from wholesale.wholesaler where id = ?", 무드온);
    }

    @Test
    void 모두_읽음의_수가_다르면_409_UNREAD_CHANGED이고_아무것도_안_바꾼다() throws Exception {
        OffsetDateTime 기준 = OffsetDateTime.parse("2026-11-01T10:00:00+09:00");
        NotificationFixture.안읽은_알림을_넣는다(jdbc, 무드온, 기준);
        NotificationFixture.안읽은_알림을_넣는다(jdbc, 무드온, 기준.minusHours(1));

        // 화면은 1개를 봤는데 그 사이 하나가 더 왔다
        mvc.perform(post("/api/wholesale/notifications/read").with(TestSecuritySupport.approvedAs(무드온))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"all\": true, \"expectedUnread\": 1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNREAD_CHANGED"));

        assertThat(jdbc.queryForObject(
                "select count(*) from wholesale.notification where wholesaler_id = ? and read_at is null",
                Integer.class, 무드온)).isEqualTo(2);
    }
}
