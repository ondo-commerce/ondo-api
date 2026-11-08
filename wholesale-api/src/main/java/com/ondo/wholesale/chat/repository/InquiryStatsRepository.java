package com.ondo.wholesale.chat.repository;

import com.ondo.wholesale.chat.domain.ChatIntent;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 문의 지표 원본 (MUL-159) — 소매 TEXT(출처) 한 건마다 그 메시지를 가리키는 결과 줄을 모은다.
 *
 * <p>결과 줄은 V17 칸 {@code source_sequence} 로 잇는다: AUTO_REPLY·CLARIFY 는 autoReply.replyToSequence,
 * HANDOFF 는 handoff.sourceSequence 가 같은 칸에 들어 있다. 되묻기 묶음을 따라가지 않는다 — 칩 답은 칩 답 메시지가
 * 자기 결과로 따로 세진다(계약). 분류(우선순위)는 서비스가 한다.
 */
@Repository
@RequiredArgsConstructor
public class InquiryStatsRepository {

    private final JdbcClient jdbc;

    /**
     * 출처 하나와 결과 줄 요약.
     *
     * @param autoIntent    첫 AUTO_REPLY 줄의 intent(없으면 null)
     * @param handoffIntent 첫 HANDOFF 줄의 intent(못 읽었으면 null — {@code hasHandoff} 로 줄 유무를 본다)
     */
    public record SourceRow(OffsetDateTime sentAt, boolean hasAutoReply, boolean hasHandoff, boolean hasClarify,
                            ChatIntent autoIntent, ChatIntent handoffIntent) {
    }

    /**
     * [startAt, endAt) 에 보낸 내 도매처 방의 소매 TEXT. asOf 뒤에 쓰인 결과 줄과 출처는 없는 것으로 본다(이하 포함).
     * 남의 도매처 방, 도매 TEXT, 자동 줄, SYSTEM 줄은 출처가 아니다.
     */
    public List<SourceRow> sources(long wholesalerId, OffsetDateTime startAt, OffsetDateTime endAt,
                                   OffsetDateTime asOf) {
        return jdbc.sql("""
                        select s.sent_at,
                               coalesce(bool_or(l.kind = 'AUTO_REPLY'), false) as has_auto,
                               coalesce(bool_or(l.kind = 'HANDOFF'), false)    as has_handoff,
                               coalesce(bool_or(l.kind = 'CLARIFY'), false)    as has_clarify,
                               (array_agg(l.intent order by l.sequence) filter (where l.kind = 'AUTO_REPLY'))[1]
                                   as auto_intent,
                               (array_agg(l.intent order by l.sequence) filter (where l.kind = 'HANDOFF'))[1]
                                   as handoff_intent
                          from wholesale.chat_room r
                          join wholesale.chat_message s on s.room_id = r.id
                          left join wholesale.chat_message l
                                 on l.room_id = s.room_id and l.source_sequence = s.sequence
                                and l.kind in ('AUTO_REPLY', 'CLARIFY', 'HANDOFF') and l.sent_at <= :asOf
                         where r.wholesaler_id = :wholesalerId
                           and s.sender = 'RETAILER' and s.kind = 'TEXT'
                           and s.sent_at >= :startAt and s.sent_at < :endAt and s.sent_at <= :asOf
                         group by s.id, s.sent_at
                        """)
                .param("wholesalerId", wholesalerId)
                .param("startAt", startAt)
                .param("endAt", endAt)
                .param("asOf", asOf)
                .query(InquiryStatsRepository::row)
                .list();
    }

    private static SourceRow row(ResultSet rs, int rowNum) throws SQLException {
        return new SourceRow(
                rs.getObject("sent_at", OffsetDateTime.class),
                rs.getBoolean("has_auto"),
                rs.getBoolean("has_handoff"),
                rs.getBoolean("has_clarify"),
                intent(rs.getString("auto_intent")),
                intent(rs.getString("handoff_intent")));
    }

    private static ChatIntent intent(String raw) {
        return raw == null ? null : ChatIntent.valueOf(raw);
    }
}
