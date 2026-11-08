package com.ondo.wholesale.notification;

import com.ondo.wholesale.notification.dto.NotificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 알림 읽기·쓰기 (MUL-154). 모든 쿼리가 {@code wholesaler_id} 로 범위를 잡는다 —
 * 읽음 범위가 도매처(사업체)라서다. 남의 id 를 넣어도 이 조건에서 걸러진다.
 */
@Repository
@RequiredArgsConstructor
class NotificationRepository {

    private final JdbcClient jdbc;

    long insert(long wholesalerId, String type, String title, String body, String targetKind, long targetId) {
        return jdbc.sql("""
                        INSERT INTO wholesale.notification
                            (wholesaler_id, type, title, body, target_kind, target_id)
                        VALUES (:wholesalerId, :type, :title, :body, :targetKind, :targetId)
                        RETURNING id
                        """)
                .param("wholesalerId", wholesalerId)
                .param("type", type)
                .param("title", title)
                .param("body", body)
                .param("targetKind", targetKind)
                .param("targetId", targetId)
                .query(Long.class)
                .single();
    }

    /**
     * 한 장 + 1 행. 커서가 있으면 그보다 뒤(더 오래된) 행만 — {@code (created_at, id) < (cursor)}.
     *
     * <p>keyset 이라 첫 장을 받은 뒤 새 알림이 생겨도 다음 장이 밀리지 않는다.
     * 새 알림은 첫 장 앞에 붙는다.
     */
    List<NotificationResponse> page(long wholesalerId, NotificationCursor after, int limit) {
        String keyset = after == null ? "" : "AND (created_at, id) < (:cursorAt, :cursorId)";
        JdbcClient.StatementSpec spec = jdbc.sql("""
                        SELECT id, type, title, body, target_kind, target_id, created_at, read_at
                        FROM wholesale.notification
                        WHERE wholesaler_id = :wholesalerId
                        %s
                        ORDER BY created_at DESC, id DESC
                        LIMIT :limit
                        """.formatted(keyset))
                .param("wholesalerId", wholesalerId)
                .param("limit", limit);
        if (after != null) {
            spec = spec.param("cursorAt", after.createdAt()).param("cursorId", after.id());
        }
        return spec.query((rs, rowNum) -> new NotificationResponse(
                        rs.getLong("id"),
                        rs.getString("type"),
                        rs.getString("title"),
                        rs.getString("body"),
                        new NotificationResponse.Target(rs.getString("target_kind"), rs.getLong("target_id")),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("read_at", OffsetDateTime.class)))
                .list();
    }

    int countUnread(long wholesalerId) {
        return jdbc.sql("""
                        SELECT count(*) FROM wholesale.notification
                        WHERE wholesaler_id = :wholesalerId AND read_at IS NULL
                        """)
                .param("wholesalerId", wholesalerId)
                .query(Integer.class)
                .single();
    }

    Optional<Long> latestId(long wholesalerId) {
        return jdbc.sql("""
                        SELECT id FROM wholesale.notification
                        WHERE wholesaler_id = :wholesalerId
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        """)
                .param("wholesalerId", wholesalerId)
                .query(Long.class)
                .optional();
    }

    /** 고른 것만. 이미 읽은 행은 건드리지 않는다 — readAt 은 처음 읽은 시각 그대로. */
    int markRead(long wholesalerId, Collection<Long> ids) {
        return jdbc.sql("""
                        UPDATE wholesale.notification SET read_at = now()
                        WHERE wholesaler_id = :wholesalerId AND id IN (:ids) AND read_at IS NULL
                        """)
                .param("wholesalerId", wholesalerId)
                .param("ids", ids)
                .update();
    }

    /** 안 읽음 전부. 바뀐 행 수를 돌려준다 — 서비스가 expectedUnread 와 대조한다. */
    int markAllRead(long wholesalerId) {
        return jdbc.sql("""
                        UPDATE wholesale.notification SET read_at = now()
                        WHERE wholesaler_id = :wholesalerId AND read_at IS NULL
                        """)
                .param("wholesalerId", wholesalerId)
                .update();
    }
}
