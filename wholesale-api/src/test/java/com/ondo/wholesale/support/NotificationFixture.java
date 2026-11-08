package com.ondo.wholesale.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;

/**
 * 알림 픽스처 (MUL-154). 알림을 만드는 API(소매 접수)를 거치지 않고 행을 직접 심는다.
 */
public final class NotificationFixture {

    private NotificationFixture() {
    }

    /** 새 주문 알림 한 건을 넣고 id 를 돌려준다. readAt 이 null 이면 안 읽음. */
    public static long 알림을_넣는다(JdbcTemplate jdbc, long wholesalerId, OffsetDateTime createdAt,
                                OffsetDateTime readAt) {
        return jdbc.queryForObject("""
                insert into wholesale.notification
                    (wholesaler_id, type, title, body, target_kind, target_id, created_at, read_at)
                values (?, 'NEW_ORDER', '봄봄상회 새 주문', '빈티지 셔츠 블랙 / M, 1장', 'ORDER', 1, ?, ?)
                returning id
                """, Long.class, wholesalerId, createdAt, readAt);
    }

    /** 안 읽은 알림. */
    public static long 안읽은_알림을_넣는다(JdbcTemplate jdbc, long wholesalerId, OffsetDateTime createdAt) {
        return 알림을_넣는다(jdbc, wholesalerId, createdAt, null);
    }
}
