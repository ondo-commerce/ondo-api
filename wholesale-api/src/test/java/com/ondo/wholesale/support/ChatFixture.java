package com.ondo.wholesale.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;

/**
 * 채팅 픽스처 (MUL-158) — 방과 줄을 SQL 로 직접 심는다.
 *
 * <p>소매 보내기·자동 답 API(MUL-157)를 거치지 않고 소매 글·자동 답·넘김 줄을 직접 심을 때 쓴다.
 * 줄을 넣을 때 운영 {@code ChatMessageAppender} 와 같은 규칙으로 방 머리를 고친다 — sequence 채번,
 * 마지막 메시지·활동 시각, 열린 넘김(HANDOFF 면 열고 WHOLESALER TEXT 면 닫는다).
 */
public final class ChatFixture {

    private ChatFixture() {
    }

    /** 메시지 없는 방을 넣고 id 를 돌려준다. lastActivityAt = 생성 시각. */
    public static long 방을_넣는다(JdbcTemplate jdbc, long wholesalerId, long retailerId, String retailerName,
                              OffsetDateTime createdAt) {
        return jdbc.queryForObject("""
                insert into wholesale.chat_room (wholesaler_id, retailer_id, retailer_name, last_activity_at, created_at)
                values (?, ?, ?, ?, ?) returning id
                """, Long.class, wholesalerId, retailerId, retailerName, createdAt, createdAt);
    }

    public static long 방을_넣는다(JdbcTemplate jdbc, long wholesalerId, long retailerId, String retailerName) {
        return 방을_넣는다(jdbc, wholesalerId, retailerId, retailerName, OffsetDateTime.now().minusDays(1));
    }

    /** 소매처가 쓴 TEXT. sequence 를 돌려준다. */
    public static long 소매_글을_넣는다(JdbcTemplate jdbc, long roomId, String body, OffsetDateTime sentAt) {
        return 줄을_넣는다(jdbc, roomId, "RETAILER", "TEXT", body, sentAt, true, null, null, null, null);
    }

    public static long 소매_글을_넣는다(JdbcTemplate jdbc, long roomId, String body) {
        return 소매_글을_넣는다(jdbc, roomId, body, OffsetDateTime.now());
    }

    /** 사장이 쓴 TEXT(보내기 API 를 거치지 않고). */
    public static long 도매_글을_넣는다(JdbcTemplate jdbc, long roomId, String body) {
        return 줄을_넣는다(jdbc, roomId, "WHOLESALER", "TEXT", body, OffsetDateTime.now(), true, null, null, null, null);
    }

    /** 자동 답(AUTO_REPLY) 한 줄. autoReplyJson 은 {generatedBy, checkedAt, subject, facts, choices}. */
    public static long 자동_답을_넣는다(JdbcTemplate jdbc, long roomId, String body, String intent,
                                  long replyToSequence, String autoReplyJson) {
        return 줄을_넣는다(jdbc, roomId, "WHOLESALER", "AUTO_REPLY", body, OffsetDateTime.now(), false,
                intent, replyToSequence, null, autoReplyJson);
    }

    /** 사람 넘김 줄(SYSTEM HANDOFF). intent 는 못 읽었으면 null. */
    public static long 넘김_줄을_넣는다(JdbcTemplate jdbc, long roomId, String body, String reason, String intent,
                                  long sourceSequence) {
        return 줄을_넣는다(jdbc, roomId, "SYSTEM", "HANDOFF", body, OffsetDateTime.now(), false,
                intent, sourceSequence, reason, null);
    }

    /** 소매 읽음 위치를 정한다. */
    public static void 소매가_읽는다(JdbcTemplate jdbc, long roomId, long sequence) {
        jdbc.update("update wholesale.chat_room set retailer_last_read_sequence = ? where id = ?", sequence, roomId);
    }

    /** 도매 읽음 위치를 정한다. */
    public static void 도매가_읽는다(JdbcTemplate jdbc, long roomId, long sequence) {
        jdbc.update("update wholesale.chat_room set wholesaler_last_read_sequence = ? where id = ?", sequence, roomId);
    }

    private static long 줄을_넣는다(JdbcTemplate jdbc, long roomId, String sender, String kind, String body,
                               OffsetDateTime sentAt, boolean human, String intent, Long sourceSequence,
                               String handoffReason, String autoReplyJson) {
        long sequence = jdbc.queryForObject(
                "select last_sequence + 1 from wholesale.chat_room where id = ? for update", Long.class, roomId);
        long messageId = jdbc.queryForObject("""
                insert into wholesale.chat_message
                    (room_id, sequence, sender, kind, body, client_message_id, intent, source_sequence,
                     handoff_reason, auto_reply, sent_at)
                values (?, ?, ?, ?, ?, case when ? then gen_random_uuid() end, ?, ?, ?, cast(? as jsonb), ?)
                returning id
                """, Long.class, roomId, sequence, sender, kind, body, human, intent, sourceSequence,
                handoffReason, autoReplyJson, sentAt);
        jdbc.update("""
                update wholesale.chat_room
                   set last_sequence = ?, last_message_id = ?, last_activity_at = ?,
                       open_handoff_sequence = case
                           when ? = 'HANDOFF' then ?
                           when ? = 'WHOLESALER' and ? = 'TEXT' then null
                           else open_handoff_sequence end
                 where id = ?
                """, sequence, messageId, sentAt, kind, sequence, sender, kind, roomId);
        return sequence;
    }
}
