package com.ondo.wholesale.chat.service;

import com.ondo.wholesale.chat.domain.ChatHandoffReason;
import com.ondo.wholesale.chat.dto.ChatMessage;
import com.ondo.wholesale.chat.inquiry.AutoReplySettingsSource;
import com.ondo.wholesale.chat.inquiry.InquiryDecider;
import com.ondo.wholesale.chat.inquiry.InquiryDecision;
import com.ondo.wholesale.chat.inquiry.InquiryInput;
import com.ondo.wholesale.chat.inquiry.InquiryInput.HistoryItem;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Listing;
import com.ondo.wholesale.chat.inquiry.InquiryInput.RoomContext;
import com.ondo.wholesale.chat.inquiry.InquiryLines;
import com.ondo.wholesale.chat.inquiry.InquiryLines.OpenHandoff;
import com.ondo.wholesale.chat.inquiry.InquirySourceReader;
import com.ondo.wholesale.chat.repository.ChatMessageRepository;
import com.ondo.wholesale.chat.repository.ChatMessageRepository.NewMessage;
import com.ondo.wholesale.chat.repository.ChatRoomRepository.RoomRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 방금 붙인 소매 글에 자동 답 줄을 붙인다 (MUL-157). 판정은 {@link InquiryDecider}(순수), 줄은 {@link InquiryLines},
 * 저장은 {@link ChatMessageAppender} 다. 여기는 원본을 모아 판정에 넘기고 나온 줄을 같은 방 잠금 아래에서 붙인다.
 *
 * <p>요청 안에서 동기로 만든다({@code generation: DONE}) — 원본(재고·미송·미수·포장)이 전부 같은 DB 라 한 트랜잭션이다.
 *
 * <p>판정이 던지면(버그·원본 조회 장애) 보내기를 실패시키지 않고 NO_INTENT 넘김 줄로 바꾼다(웹 목과 같다) —
 * 소매는 답 대신 "전달했어요" 를 본다. 소매 글 저장은 이미 끝났다. 미수·포장 원본이 던지면 판정 안에서
 * LOOKUP_FAILED·NO_BACKORDER 넘김이 된다.
 *
 * <p>원본 조회와 판정은 <b>JDBC 세이브포인트</b> 안에서 돈다. Postgres 는 SQL 하나가 실패하면 트랜잭션 전체가
 * 막혀서, 잡아서 넘김으로 바꿔도 뒤의 줄 붙이기가 같이 죽는다. 세이브포인트로 되감으면 소매 글과 넘김 줄이 남는다.
 * 트랜잭션 관리자(JPA)의 NESTED 는 꺼져 있어(전역 설정) 트랜잭션에 묶인 커넥션에 직접 건다 — 원본 조회는 JdbcClient
 * 읽기뿐이라 영속성 컨텍스트와 어긋날 게 없다.
 */
@Slf4j
@Component
public class ChatAutoReplyGenerator {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 판정이 읽는 지난 줄의 창. 되묻기 수명(30분)보다 조금 넓게 읽고, 정확한 만료는 판정이 잰다. */
    private static final Duration HISTORY_WINDOW = Duration.ofMinutes(31);
    /** 창 안에서도 이만큼만 읽는다. 열린 되묻기는 가장 최근 소매 글 뒤에만 있어 이 정도면 넉넉하다. */
    private static final int HISTORY_LIMIT = 200;

    private final ChatMessageRepository messageRepository;
    private final ChatMessageAppender appender;
    private final InquirySourceReader sources;
    private final AutoReplySettingsSource settingsSource;
    private final DataSource dataSource;

    public ChatAutoReplyGenerator(ChatMessageRepository messageRepository, ChatMessageAppender appender,
                                  InquirySourceReader sources, AutoReplySettingsSource settingsSource,
                                  DataSource dataSource) {
        this.messageRepository = messageRepository;
        this.appender = appender;
        this.sources = sources;
        this.settingsSource = settingsSource;
        this.dataSource = dataSource;
    }

    /**
     * @param roomBefore      소매 글을 붙이기 <b>전</b> 방(열린 넘김을 이 값으로 본다)
     * @param roomAfter       소매 글을 붙인 뒤 방(다음 줄 채번이 여기서 이어진다)
     * @param retailerMessage 방금 붙인 소매 TEXT
     * @return 붙인 줄(sequence 오름차순). 열린 넘김과 이유가 같아 붙일 게 없으면 빈 목록
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ChatMessage> reply(RoomRow roomBefore, RoomRow roomAfter, ChatMessage retailerMessage) {
        Instant now = retailerMessage.sentAt().toInstant();
        long replyTo = retailerMessage.sequence();

        InquiryDecision decision;
        try {
            decision = guarded(() -> InquiryDecider.decide(input(roomAfter, retailerMessage, now)));
        } catch (RuntimeException e) {
            log.warn("자동 답 판정 실패 — 넘김으로 바꾼다. room={} sequence={}", roomAfter.id(), replyTo, e);
            decision = new InquiryDecision.Handoff(ChatHandoffReason.NO_INTENT, null);
        }
        List<NewMessage> lines = InquiryLines.toAppend(InquiryLines.generated(decision, replyTo, now),
                openHandoff(roomBefore));

        List<ChatMessage> appended = new ArrayList<>(lines.size());
        RoomRow room = roomAfter;
        for (NewMessage line : lines) {
            ChatMessageAppender.Appended a = appender.append(room, line);
            appended.add(a.message());
            room = a.roomAfter(room);
        }
        return appended;
    }

    private InquiryInput input(RoomRow room, ChatMessage message, Instant now) {
        long wholesalerId = room.wholesalerId();
        long retailerId = room.retailerId();
        List<HistoryItem> history = messageRepository
                .findRecentSince(room.id(), message.sentAt().minus(HISTORY_WINDOW), HISTORY_LIMIT).stream()
                .filter(m -> m.sequence() < message.sequence())
                .map(m -> new HistoryItem(m.sequence(), m.sender(), m.kind(), m.sentAt().toInstant(), m.autoReply(),
                        m.handoff() == null ? null : m.handoff().reason()))
                .toList();
        RoomContext context = room.contextListingId() == null ? null
                : new RoomContext(room.contextListingId(), room.contextAt().toInstant());
        // 한 판정이 같은 게시글을 두세 번 찾는다(의도 낱말·대상). 한 번만 읽는다
        Map<Long, Optional<Listing>> listings = new HashMap<>();
        return new InquiryInput(message.body(), message.inquiry(), now, LocalDate.ofInstant(now, SEOUL),
                wholesalerId, retailerId, settingsSource.forWholesaler(wholesalerId), context, history,
                id -> listings.computeIfAbsent(id, k -> Optional.ofNullable(guarded(() -> sources.listing(k))))
                        .orElse(null),
                () -> guarded(() -> sources.backorders(wholesalerId, retailerId)),
                () -> guarded(() -> sources.receivables(wholesalerId, retailerId, now)),
                () -> guarded(() -> sources.packings(wholesalerId, retailerId)));
    }

    /** 세이브포인트 안에서 부른다. 던지면 세이브포인트로 되감고 그대로 던진다 — 넘김으로 바꾸는 건 판정·호출부다. */
    private <T> T guarded(Supplier<T> work) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        Savepoint savepoint;
        try {
            savepoint = connection.setSavepoint();
        } catch (SQLException e) {
            throw new IllegalStateException("세이브포인트를 만들지 못했다", e);
        }
        try {
            T result = work.get();
            connection.releaseSavepoint(savepoint);
            return result;
        } catch (RuntimeException e) {
            try {
                connection.rollback(savepoint);
            } catch (SQLException rollback) {
                e.addSuppressed(rollback);
            }
            throw e;
        } catch (SQLException e) {
            throw new IllegalStateException("세이브포인트를 풀지 못했다", e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    /** 방 머리의 열린 넘김 sequence 로 그 줄의 이유를 읽는다. 줄을 못 찾으면 이유 모름(어떤 이유와도 다르다). */
    private OpenHandoff openHandoff(RoomRow room) {
        if (room.openHandoffSequence() == null) {
            return null;
        }
        ChatMessage line = messageRepository.findBySequence(room.id(), room.openHandoffSequence());
        return new OpenHandoff(line == null || line.handoff() == null ? null : line.handoff().reason());
    }
}
