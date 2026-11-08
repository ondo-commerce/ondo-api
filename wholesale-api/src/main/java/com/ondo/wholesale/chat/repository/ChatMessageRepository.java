package com.ondo.wholesale.chat.repository;

import com.ondo.wholesale.chat.domain.ChatHandoffReason;
import com.ondo.wholesale.chat.domain.ChatIntent;
import com.ondo.wholesale.chat.domain.ChatMessageKind;
import com.ondo.wholesale.chat.domain.ChatSender;
import com.ondo.wholesale.chat.dto.ChatAutoReply;
import com.ondo.wholesale.chat.dto.ChatHandoff;
import com.ondo.wholesale.chat.dto.ChatInquiry;
import com.ondo.wholesale.chat.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 채팅 메시지 읽기·쓰기 (MUL-158). 응답은 도매 모양({@link ChatMessage}, handoff 칸 포함)으로 조립한다.
 *
 * <p>조회 셋 다 {@code (room_id, sequence)} 유니크 인덱스 한 번으로 끝난다 — 열린 방 polling(3초)이 이 질의다.
 */
@Repository
@RequiredArgsConstructor
public class ChatMessageRepository {

    private static final String COLUMNS = """
            select m.id, m.room_id, m.sequence, m.client_message_id, m.sender, m.kind, m.body, m.sent_at,
                   m.inquiry_intent, m.inquiry_listing_id, m.inquiry_variant_id, m.inquiry_backorder_id,
                   m.inquiry_label, m.inquiry_not_listed,
                   m.intent, m.source_sequence, m.handoff_reason, m.auto_reply::text as auto_reply
            from wholesale.chat_message m
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    /** 새 줄. 만들지 않는 칸은 null. {@code autoReply} 는 AUTO_REPLY·CLARIFY 에만. */
    public record NewMessage(
            ChatSender sender,
            ChatMessageKind kind,
            String body,
            UUID clientMessageId,
            ChatInquiry inquiry,
            ChatIntent intent,
            Long sourceSequence,
            ChatHandoffReason handoffReason,
            ChatAutoReply.Detail autoReply) {

        /** 사람이 쓴 TEXT. */
        public static NewMessage text(ChatSender sender, String body, UUID clientMessageId, ChatInquiry inquiry) {
            return new NewMessage(sender, ChatMessageKind.TEXT, body, clientMessageId, inquiry,
                    null, null, null, null);
        }
    }

    /**
     * {@code sequence} 를 붙여 저장하고 id 를 돌려준다. sequence 채번은 방 잠금을 잡은 쪽이 한다.
     *
     * <p>sent_at 은 {@code now()}(트랜잭션 시작 시각)가 아니라 {@code clock_timestamp()} 다. 잠금을 기다린 요청이
     * 먼저 시작한 시각을 들고 오면 방의 lastActivityAt 이 뒤로 가서 목록 순서가 흔들린다.
     */
    public long insert(long roomId, long sequence, NewMessage message) {
        ChatInquiry inquiry = message.inquiry();
        return jdbc.sql("""
                        insert into wholesale.chat_message
                            (room_id, sequence, sender, kind, body, client_message_id,
                             inquiry_intent, inquiry_listing_id, inquiry_variant_id, inquiry_backorder_id,
                             inquiry_label, inquiry_not_listed,
                             intent, source_sequence, handoff_reason, auto_reply, sent_at)
                        values (:roomId, :sequence, :sender, :kind, :body, :clientMessageId,
                                :inquiryIntent, :inquiryListingId, :inquiryVariantId, :inquiryBackorderId,
                                :inquiryLabel, :inquiryNotListed,
                                :intent, :sourceSequence, :handoffReason, cast(:autoReply as jsonb),
                                clock_timestamp())
                        returning id
                        """)
                .param("roomId", roomId)
                .param("sequence", sequence)
                .param("sender", message.sender().name())
                .param("kind", message.kind().name())
                .param("body", message.body())
                .param("clientMessageId", message.clientMessageId())
                .param("inquiryIntent", inquiry == null ? null : inquiry.intent().name())
                .param("inquiryListingId", inquiry == null ? null : inquiry.listingId())
                .param("inquiryVariantId", inquiry == null ? null : inquiry.variantId())
                .param("inquiryBackorderId", inquiry == null ? null : inquiry.backorderId())
                .param("inquiryLabel", inquiry == null ? null : inquiry.label())
                .param("inquiryNotListed", inquiry != null && Boolean.TRUE.equals(inquiry.notListed()))
                .param("intent", message.intent() == null ? null : message.intent().name())
                .param("sourceSequence", message.sourceSequence())
                .param("handoffReason", message.handoffReason() == null ? null : message.handoffReason().name())
                .param("autoReply", message.autoReply() == null ? null
                        : objectMapper.writeValueAsString(message.autoReply()))
                .query(Long.class)
                .single();
    }

    public ChatMessage findById(long id) {
        return jdbc.sql(COLUMNS + " where m.id = :id")
                .param("id", id)
                .query(this::message)
                .single();
    }

    /** afterSequence 보다 큰 것을 오름차순으로 limit 개. */
    public List<ChatMessage> findAfter(long roomId, long afterSequence, int limit) {
        return jdbc.sql(COLUMNS + " where m.room_id = :roomId and m.sequence > :after order by m.sequence limit :limit")
                .param("roomId", roomId)
                .param("after", afterSequence)
                .param("limit", limit)
                .query(this::message)
                .list();
    }

    /** beforeSequence 바로 앞의 limit 개(가까운 것부터 골라)를 오름차순으로. before 가 null 이면 최신 limit 개. */
    public List<ChatMessage> findBefore(long roomId, Long beforeSequence, int limit) {
        String where = beforeSequence == null
                ? " where m.room_id = :roomId"
                : " where m.room_id = :roomId and m.sequence < :before";
        var spec = jdbc.sql(COLUMNS + where + " order by m.sequence desc limit :limit")
                .param("roomId", roomId)
                .param("limit", limit);
        if (beforeSequence != null) {
            spec = spec.param("before", beforeSequence);
        }
        List<ChatMessage> newestFirst = new ArrayList<>(spec.query(this::message).list());
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    /** 방 안 sequence 하나. 없으면 null. */
    public ChatMessage findBySequence(long roomId, long sequence) {
        return jdbc.sql(COLUMNS + " where m.room_id = :roomId and m.sequence = :sequence")
                .param("roomId", roomId)
                .param("sequence", sequence)
                .query(this::message)
                .optional()
                .orElse(null);
    }

    /**
     * 판정이 읽는 지난 줄 (MUL-157) — {@code since} 이후에 보낸 것 중 최신 limit 개를 오름차순으로.
     * 판정의 되묻기·칩 검증은 30분 안의 줄만 본다(그보다 오래된 되묻기는 만료다). 그래서 방 전체를 읽지 않는다.
     */
    public List<ChatMessage> findRecentSince(long roomId, OffsetDateTime since, int limit) {
        List<ChatMessage> newestFirst = new ArrayList<>(jdbc.sql(COLUMNS
                        + " where m.room_id = :roomId and m.sent_at >= :since order by m.sequence desc limit :limit")
                .param("roomId", roomId)
                .param("since", since)
                .param("limit", limit)
                .query(this::message)
                .list());
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    /** 그 sender 가 보낸 것 중 sequence 초과분. 미확인 수 계산 — 인덱스 (room_id, sender, sequence). */
    public int countFromAfter(long roomId, ChatSender sender, long afterSequence) {
        return jdbc.sql("""
                        select count(*) from wholesale.chat_message
                         where room_id = :roomId and sender = :sender and sequence > :after
                        """)
                .param("roomId", roomId)
                .param("sender", sender.name())
                .param("after", afterSequence)
                .query(Integer.class)
                .single();
    }

    private ChatMessage message(ResultSet rs, int rowNum) throws SQLException {
        ChatMessageKind kind = ChatMessageKind.valueOf(rs.getString("kind"));
        String intentRaw = rs.getString("intent");
        ChatIntent intent = intentRaw == null ? null : ChatIntent.valueOf(intentRaw);
        Long sourceSequence = rs.getObject("source_sequence", Long.class);

        String inquiryIntent = rs.getString("inquiry_intent");
        ChatInquiry inquiry = inquiryIntent == null ? null : new ChatInquiry(
                ChatIntent.valueOf(inquiryIntent),
                rs.getObject("inquiry_listing_id", Long.class),
                rs.getObject("inquiry_variant_id", Long.class),
                rs.getObject("inquiry_backorder_id", Long.class),
                rs.getString("inquiry_label"),
                rs.getBoolean("inquiry_not_listed"));

        String autoReplyJson = rs.getString("auto_reply");
        ChatAutoReply autoReply = autoReplyJson == null ? null : ChatAutoReply.of(intent, sourceSequence,
                objectMapper.readValue(autoReplyJson, ChatAutoReply.Detail.class));

        String reason = rs.getString("handoff_reason");
        ChatHandoff handoff = kind == ChatMessageKind.HANDOFF
                ? new ChatHandoff(intent, ChatHandoffReason.valueOf(reason), sourceSequence)
                : null;

        return new ChatMessage(
                rs.getLong("id"),
                rs.getLong("room_id"),
                rs.getLong("sequence"),
                rs.getObject("client_message_id", UUID.class),
                ChatSender.valueOf(rs.getString("sender")),
                kind,
                rs.getString("body"),
                utc(rs.getObject("sent_at", OffsetDateTime.class)),
                inquiry,
                autoReply,
                handoff);
    }

    static OffsetDateTime utc(OffsetDateTime time) {
        return time == null ? null : time.withOffsetSameInstant(ZoneOffset.UTC);
    }
}
