package com.ondo.wholesale.chat.repository;

import com.ondo.wholesale.chat.domain.ChatMessageKind;
import com.ondo.wholesale.chat.domain.ChatRoomFilter;
import com.ondo.wholesale.chat.domain.ChatSender;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 채팅방 읽기·쓰기 (MUL-158). 엔티티 없이 SQL 로 바로 다룬다 — 목록 행이 곧 집계 쿼리라서다(미송 조회와 같은 판단).
 *
 * <p>도매 쪽 조회는 늘 {@code wholesaler_id} 로 거른다. 남의 방은 "없는 방"과 구별되지 않는다(404, 존재를 숨긴다).
 */
@Repository
@RequiredArgsConstructor
public class ChatRoomRepository {

    /** 영문 대문자만 소문자로 접는다 — 계약이 "영문 대소문자"만 구분하지 않는다고 적었다(lower 는 그리스·전각까지 접는다). */
    private static final String ASCII_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String ASCII_LOWER = "abcdefghijklmnopqrstuvwxyz";

    private static final String ROOM_COLUMNS = """
            r.id, r.wholesaler_id, r.retailer_id, r.retailer_name, r.last_sequence, r.last_activity_at,
            r.last_message_id, r.open_handoff_sequence,
            r.wholesaler_last_read_sequence, r.retailer_last_read_sequence
            """;

    private final JdbcClient jdbc;

    /** 방 한 행. {@code retailerLastReadSequence} 는 소매가 한 번도 안 읽었으면 null. */
    public record RoomRow(
            long id,
            long wholesalerId,
            long retailerId,
            String retailerName,
            long lastSequence,
            OffsetDateTime lastActivityAt,
            Long lastMessageId,
            Long openHandoffSequence,
            long wholesalerLastReadSequence,
            Long retailerLastReadSequence) {
    }

    /** 목록·머리 한 줄 — 방 + 마지막 메시지 + 도매 기준 미확인 수. 마지막 메시지가 없으면 lm* 가 null. */
    public record WholesaleSummaryRow(
            RoomRow room,
            Long lastMessageSequence,
            ChatSender lastMessageSender,
            ChatMessageKind lastMessageKind,
            String lastMessageBody,
            OffsetDateTime lastMessageSentAt,
            int unreadCount) {
    }

    /** 목록 조건. q 는 앞뒤 공백을 지우고 영문을 접은 값(없으면 null), cursor 는 키셋 지점(없으면 null). */
    public record WholesaleRoomQuery(
            long wholesalerId,
            ChatRoomFilter filter,
            String foldedQuery,
            OffsetDateTime cursorActivityAt,
            Long cursorRoomId,
            int limit) {
    }

    /** 내 방 한 행. 남의 방·없는 방은 빈 값. */
    public Optional<RoomRow> findForWholesaler(long roomId, long wholesalerId) {
        return jdbc.sql("select " + ROOM_COLUMNS + " from wholesale.chat_room r"
                        + " where r.id = :roomId and r.wholesaler_id = :wholesalerId")
                .param("roomId", roomId)
                .param("wholesalerId", wholesalerId)
                .query(ChatRoomRepository::room)
                .optional();
    }

    /**
     * 내 방 한 행을 FOR UPDATE 로 잡는다. 보내기·읽음이 방 단위로 한 줄로 선다 — sequence 채번과
     * 같은 키 동시 요청(뒤 요청이 앞 요청의 커밋을 기다렸다가 replay 한다)이 이 잠금에 기댄다.
     */
    public Optional<RoomRow> lockForWholesaler(long roomId, long wholesalerId) {
        return jdbc.sql("select " + ROOM_COLUMNS + " from wholesale.chat_room r"
                        + " where r.id = :roomId and r.wholesaler_id = :wholesalerId for update")
                .param("roomId", roomId)
                .param("wholesalerId", wholesalerId)
                .query(ChatRoomRepository::room)
                .optional();
    }

    /** 메시지 한 건을 붙인 뒤의 방 머리 갱신. 잠금을 잡은 트랜잭션 안에서만 부른다. */
    public void recordAppended(long roomId, long sequence, long messageId, OffsetDateTime sentAt,
                               Long openHandoffSequence) {
        jdbc.sql("""
                        update wholesale.chat_room
                           set last_sequence = :sequence, last_message_id = :messageId,
                               last_activity_at = :sentAt, open_handoff_sequence = :openHandoff,
                               updated_at = now()
                         where id = :roomId
                        """)
                .param("sequence", sequence)
                .param("messageId", messageId)
                .param("sentAt", sentAt)
                .param("openHandoff", openHandoffSequence)
                .param("roomId", roomId)
                .update();
    }

    /** 도매 읽음 위치를 max(기존, 요청)으로 올리고 저장된 값을 돌려준다 — 늦게 온 작은 값이 되돌리지 않는다. */
    public long advanceWholesalerRead(long roomId, long sequence) {
        return jdbc.sql("""
                        update wholesale.chat_room
                           set wholesaler_last_read_sequence = greatest(wholesaler_last_read_sequence, :sequence),
                               updated_at = now()
                         where id = :roomId
                        returning wholesaler_last_read_sequence
                        """)
                .param("sequence", sequence)
                .param("roomId", roomId)
                .query(Long.class)
                .single();
    }

    /** 내 방 머리 한 줄. 남의 방·없는 방은 빈 값. */
    public Optional<WholesaleSummaryRow> findWholesaleSummary(long roomId, long wholesalerId) {
        return jdbc.sql(WHOLESALE_SUMMARY_SELECT + " where r.wholesaler_id = :wholesalerId and r.id = :roomId")
                .param("wholesalerId", wholesalerId)
                .param("roomId", roomId)
                .query(ChatRoomRepository::summary)
                .optional();
    }

    /**
     * 내 방 목록 한 장 — 정렬 (last_activity_at desc, id desc) 키셋. 순서가 계약이다: 내 방 → 조건 → 정렬 → 커서 자르기.
     * 인덱스 {@code chat_room_wholesaler_activity_idx} 가 이 정렬 그대로다.
     */
    public List<WholesaleSummaryRow> findWholesaleSummaries(WholesaleRoomQuery query) {
        StringBuilder sql = new StringBuilder(WHOLESALE_SUMMARY_SELECT).append(" where r.wholesaler_id = :wholesalerId");
        if (query.foldedQuery() != null) {
            sql.append(" and strpos(translate(r.retailer_name, '").append(ASCII_UPPER).append("', '")
                    .append(ASCII_LOWER).append("'), :q) > 0");
        }
        if (query.filter() == ChatRoomFilter.UNREAD) {
            sql.append(" and exists (select 1 from wholesale.chat_message u where u.room_id = r.id")
                    .append(" and u.sender = 'RETAILER' and u.sequence > r.wholesaler_last_read_sequence)");
        } else if (query.filter() == ChatRoomFilter.NEEDS_REPLY) {
            sql.append(" and (r.open_handoff_sequence is not null or (m.sender = 'RETAILER' and m.kind = 'TEXT'))");
        }
        if (query.cursorRoomId() != null) {
            sql.append(" and (r.last_activity_at, r.id) < (:cursorAt, :cursorId)");
        }
        sql.append(" order by r.last_activity_at desc, r.id desc limit :limit");

        var spec = jdbc.sql(sql.toString())
                .param("wholesalerId", query.wholesalerId())
                .param("limit", query.limit());
        if (query.foldedQuery() != null) {
            spec = spec.param("q", query.foldedQuery());
        }
        if (query.cursorRoomId() != null) {
            spec = spec.param("cursorAt", query.cursorActivityAt()).param("cursorId", query.cursorRoomId());
        }
        return spec.query(ChatRoomRepository::summary).list();
    }

    /** 도매 기준 미확인 = RETAILER 가 보낸 것 중 도매 읽음 위치 초과분. SYSTEM 은 세지 않는다. */
    private static final String WHOLESALE_SUMMARY_SELECT = "select " + ROOM_COLUMNS + """
            , m.sequence as lm_sequence, m.sender as lm_sender, m.kind as lm_kind,
              m.body as lm_body, m.sent_at as lm_sent_at,
              (select count(*) from wholesale.chat_message u
                where u.room_id = r.id and u.sender = 'RETAILER'
                  and u.sequence > r.wholesaler_last_read_sequence) as unread_count
            from wholesale.chat_room r
            left join wholesale.chat_message m on m.id = r.last_message_id
            """;

    private static RoomRow room(ResultSet rs, int rowNum) throws SQLException {
        return new RoomRow(
                rs.getLong("id"),
                rs.getLong("wholesaler_id"),
                rs.getLong("retailer_id"),
                rs.getString("retailer_name"),
                rs.getLong("last_sequence"),
                rs.getObject("last_activity_at", OffsetDateTime.class),
                rs.getObject("last_message_id", Long.class),
                rs.getObject("open_handoff_sequence", Long.class),
                rs.getLong("wholesaler_last_read_sequence"),
                rs.getObject("retailer_last_read_sequence", Long.class));
    }

    private static WholesaleSummaryRow summary(ResultSet rs, int rowNum) throws SQLException {
        String sender = rs.getString("lm_sender");
        String kind = rs.getString("lm_kind");
        return new WholesaleSummaryRow(
                room(rs, rowNum),
                rs.getObject("lm_sequence", Long.class),
                sender == null ? null : ChatSender.valueOf(sender),
                kind == null ? null : ChatMessageKind.valueOf(kind),
                rs.getString("lm_body"),
                rs.getObject("lm_sent_at", OffsetDateTime.class),
                rs.getInt("unread_count"));
    }
}
