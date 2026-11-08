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
            r.wholesaler_last_read_sequence, r.retailer_last_read_sequence,
            r.context_listing_id, r.context_at
            """;

    private final JdbcClient jdbc;

    /**
     * 방 한 행. {@code retailerLastReadSequence} 는 소매가 한 번도 안 읽었으면 null.
     * {@code contextListingId}·{@code contextAt} 은 소매 createChatRoom 이 남긴 상품 문맥(MUL-157, 없으면 둘 다 null).
     */
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
            Long retailerLastReadSequence,
            Long contextListingId,
            OffsetDateTime contextAt) {

        /** 한 트랜잭션에서 줄을 이어 붙일 때 다음 append 에 넘길 방(MUL-157) — 채번·열린 넘김만 바뀐다. */
        public RoomRow appended(long sequence, Long openHandoff) {
            return new RoomRow(id, wholesalerId, retailerId, retailerName, sequence, lastActivityAt, lastMessageId,
                    openHandoff, wholesalerLastReadSequence, retailerLastReadSequence, contextListingId, contextAt);
        }
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

    /* --- 소매 쪽 (MUL-157) — 늘 retailer_id 로 거른다 ------------------------------------ */

    /** 소매 목록·머리 한 줄 — 방 + 도매처 상호 + 마지막 메시지 + 소매 기준 미확인 수. */
    public record RetailSummaryRow(
            RoomRow room,
            String wholesalerName,
            Long lastMessageSequence,
            ChatSender lastMessageSender,
            ChatMessageKind lastMessageKind,
            String lastMessageBody,
            OffsetDateTime lastMessageSentAt,
            int unreadCount) {
    }

    /** 그 소매처의 방 한 행. 남의 방·없는 방은 빈 값. */
    public Optional<RoomRow> findForRetailer(long roomId, long retailerId) {
        return jdbc.sql("select " + ROOM_COLUMNS + " from wholesale.chat_room r"
                        + " where r.id = :roomId and r.retailer_id = :retailerId")
                .param("roomId", roomId)
                .param("retailerId", retailerId)
                .query(ChatRoomRepository::room)
                .optional();
    }

    /** 그 소매처의 방 한 행을 FOR UPDATE 로 잡는다 — 도매 보내기와 같은 행 잠금이라 양쪽 글이 한 줄로 선다. */
    public Optional<RoomRow> lockForRetailer(long roomId, long retailerId) {
        return jdbc.sql("select " + ROOM_COLUMNS + " from wholesale.chat_room r"
                        + " where r.id = :roomId and r.retailer_id = :retailerId for update")
                .param("roomId", roomId)
                .param("retailerId", retailerId)
                .query(ChatRoomRepository::room)
                .optional();
    }

    /**
     * (도매처, 소매처) 쌍의 방을 만들거나 있는 방을 잡는다. 쌍 유니크(chat_room_pair_uk)에 기댄다 — 동시에 둘이 열어도
     * 방은 하나다. 잡은 방은 FOR UPDATE 상태라 문맥 갱신이 이어서 안전하다.
     *
     * @return 방과 이번에 새로 만들었는지
     */
    public Opened openForRetailer(long wholesalerId, long retailerId, String retailerName) {
        Long created = jdbc.sql("""
                        insert into wholesale.chat_room (wholesaler_id, retailer_id, retailer_name)
                        values (:wholesalerId, :retailerId, :retailerName)
                        on conflict (wholesaler_id, retailer_id) do nothing
                        returning id
                        """)
                .param("wholesalerId", wholesalerId)
                .param("retailerId", retailerId)
                .param("retailerName", retailerName)
                .query(Long.class)
                .optional()
                .orElse(null);
        RoomRow room = jdbc.sql("select " + ROOM_COLUMNS + " from wholesale.chat_room r"
                        + " where r.wholesaler_id = :wholesalerId and r.retailer_id = :retailerId for update")
                .param("wholesalerId", wholesalerId)
                .param("retailerId", retailerId)
                .query(ChatRoomRepository::room)
                .single();
        return new Opened(room, created != null);
    }

    public record Opened(RoomRow room, boolean created) {
    }

    /** 방의 상품 문맥을 바꾼다. listingId 가 null 이면 지운다. */
    public void setContext(long roomId, Long listingId, OffsetDateTime at) {
        jdbc.sql("""
                        update wholesale.chat_room
                           set context_listing_id = :listingId, context_at = :at, updated_at = now()
                         where id = :roomId
                        """)
                .param("listingId", listingId)
                .param("at", listingId == null ? null : at)
                .param("roomId", roomId)
                .update();
    }

    /** 소매 읽음 위치를 max(기존, 요청)으로 올린다. 한 번도 안 읽었으면(NULL) 요청 값이 된다. */
    public long advanceRetailerRead(long roomId, long sequence) {
        return jdbc.sql("""
                        update wholesale.chat_room
                           set retailer_last_read_sequence = greatest(coalesce(retailer_last_read_sequence, 0), :sequence),
                               updated_at = now()
                         where id = :roomId
                        returning retailer_last_read_sequence
                        """)
                .param("sequence", sequence)
                .param("roomId", roomId)
                .query(Long.class)
                .single();
    }

    /** 그 소매처의 방 머리 한 줄. 남의 방·없는 방은 빈 값. */
    public Optional<RetailSummaryRow> findRetailSummary(long roomId, long retailerId) {
        return jdbc.sql(RETAIL_SUMMARY_SELECT + " where r.retailer_id = :retailerId and r.id = :roomId")
                .param("retailerId", retailerId)
                .param("roomId", roomId)
                .query(ChatRoomRepository::retailSummary)
                .optional();
    }

    /** 그 소매처의 방 목록 한 장 — (last_activity_at desc, id desc) 키셋. 인덱스 chat_room_retailer_activity_idx. */
    public List<RetailSummaryRow> findRetailSummaries(long retailerId, OffsetDateTime cursorAt, Long cursorId,
                                                      int limit) {
        String sql = RETAIL_SUMMARY_SELECT + " where r.retailer_id = :retailerId"
                + (cursorId == null ? "" : " and (r.last_activity_at, r.id) < (:cursorAt, :cursorId)")
                + " order by r.last_activity_at desc, r.id desc limit :limit";
        var spec = jdbc.sql(sql).param("retailerId", retailerId).param("limit", limit);
        if (cursorId != null) {
            spec = spec.param("cursorAt", cursorAt).param("cursorId", cursorId);
        }
        return spec.query(ChatRoomRepository::retailSummary).list();
    }

    /** 소매 헤더 배지 = 그 소매처 방들의 unreadCount 합. 방 목록과 같은 정의(WHOLESALER 만, SYSTEM 제외). */
    public int retailUnreadTotal(long retailerId) {
        return jdbc.sql("""
                        select count(*) from wholesale.chat_room r
                        join wholesale.chat_message u on u.room_id = r.id
                        where r.retailer_id = :retailerId and u.sender = 'WHOLESALER'
                          and u.sequence > coalesce(r.retailer_last_read_sequence, 0)
                        """)
                .param("retailerId", retailerId)
                .query(Integer.class)
                .single();
    }

    /** 소매 기준 미확인 = WHOLESALER 가 보낸 것(사람 답·자동 답·되묻기) 중 소매 읽음 위치(없으면 0) 초과분. */
    private static final String RETAIL_SUMMARY_SELECT = "select " + ROOM_COLUMNS + """
            , w.biz_name as wholesaler_name,
              m.sequence as lm_sequence, m.sender as lm_sender, m.kind as lm_kind,
              m.body as lm_body, m.sent_at as lm_sent_at,
              (select count(*) from wholesale.chat_message u
                where u.room_id = r.id and u.sender = 'WHOLESALER'
                  and u.sequence > coalesce(r.retailer_last_read_sequence, 0)) as unread_count
            from wholesale.chat_room r
            join wholesale.wholesaler w on w.id = r.wholesaler_id
            left join wholesale.chat_message m on m.id = r.last_message_id
            """;

    private static RetailSummaryRow retailSummary(ResultSet rs, int rowNum) throws SQLException {
        String sender = rs.getString("lm_sender");
        String kind = rs.getString("lm_kind");
        return new RetailSummaryRow(
                room(rs, rowNum),
                rs.getString("wholesaler_name"),
                rs.getObject("lm_sequence", Long.class),
                sender == null ? null : ChatSender.valueOf(sender),
                kind == null ? null : ChatMessageKind.valueOf(kind),
                rs.getString("lm_body"),
                rs.getObject("lm_sent_at", OffsetDateTime.class),
                rs.getInt("unread_count"));
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
                rs.getObject("retailer_last_read_sequence", Long.class),
                rs.getObject("context_listing_id", Long.class),
                rs.getObject("context_at", OffsetDateTime.class));
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
