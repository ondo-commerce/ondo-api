package com.ondo.wholesale.chat.service;

import com.ondo.wholesale.chat.domain.ChatMessageKind;
import com.ondo.wholesale.chat.domain.ChatRoomFilter;
import com.ondo.wholesale.chat.domain.ChatSender;
import com.ondo.wholesale.chat.dto.ChatMessage;
import com.ondo.wholesale.chat.dto.ChatMessagePage;
import com.ondo.wholesale.chat.dto.ChatMessagePage.ChatMessagePageMeta;
import com.ondo.wholesale.chat.dto.ChatRoomDetail;
import com.ondo.wholesale.chat.dto.ChatRoomPage;
import com.ondo.wholesale.chat.dto.ChatRoomPage.CursorPageMeta;
import com.ondo.wholesale.chat.dto.ChatRoomSummary;
import com.ondo.wholesale.chat.dto.ChatRoomSummary.ChatCounterpart;
import com.ondo.wholesale.chat.dto.ChatRoomSummary.ChatMessagePreview;
import com.ondo.wholesale.chat.repository.ChatMessageRepository;
import com.ondo.wholesale.chat.repository.ChatRoomRepository;
import com.ondo.wholesale.chat.repository.ChatRoomRepository.RoomRow;
import com.ondo.wholesale.chat.repository.ChatRoomRepository.WholesaleRoomQuery;
import com.ondo.wholesale.chat.repository.ChatRoomRepository.WholesaleSummaryRow;
import com.ondo.wholesale.common.error.ApiException;
import com.ondo.wholesale.common.error.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 도매 채팅 조회 3종 (MUL-158) — 방 목록(15초 polling) · 방 머리 · 메시지(열린 방 3초 polling).
 *
 * <p>계산 규칙은 계약 그대로다.
 * <ul>
 *   <li>unreadCount = RETAILER 가 보낸 것 중 myLastReadSequence 초과분. SYSTEM(HANDOFF)은 세지 않는다</li>
 *   <li>openHandoff = HANDOFF 가 있고 그 뒤 WHOLESALER TEXT 가 없다 (방의 open_handoff_sequence)</li>
 *   <li>needsReply = openHandoff 이거나 마지막 메시지가 RETAILER TEXT</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatQueryService {

    static final int SIZE_MAX = 100;
    static final int QUERY_MAX = 50;
    private static final int PREVIEW_MAX = 80;
    /** 계약: "줄바꿈은 공백으로". 줄바꿈 둘레 공백까지 한 칸으로 접는다(목과 같은 규칙). */
    private static final Pattern LINE_BREAK = Pattern.compile("\\s*\\n\\s*", Pattern.UNICODE_CHARACTER_CLASS);

    private final ChatRoomRepository roomRepository;
    private final ChatMessageRepository messageRepository;

    /** 방 목록. 순서가 계약이다: 내 방 → 조건 → 정렬 → 커서 자르기. size+1 개를 읽어 다음 장이 있는지 본다. */
    public ChatRoomPage rooms(long wholesalerId, String cursor, int size, String filter, String q) {
        validateSize(size);
        ChatRoomCursor position = cursor == null ? null : ChatRoomCursor.decode(cursor)
                .orElseThrow(() -> ApiException.validationFailed("cursor", "cursor 가 올바르지 않습니다."));
        ChatRoomFilter roomFilter = parseFilter(filter);
        String folded = foldQuery(q);

        List<WholesaleSummaryRow> rows = roomRepository.findWholesaleSummaries(new WholesaleRoomQuery(
                wholesalerId, roomFilter, folded,
                position == null ? null : position.lastActivityAt(),
                position == null ? null : position.roomId(),
                size + 1));
        boolean hasNext = rows.size() > size;
        List<WholesaleSummaryRow> page = hasNext ? rows.subList(0, size) : rows;
        String nextCursor = null;
        if (hasNext) {
            RoomRow last = page.getLast().room();
            nextCursor = new ChatRoomCursor(last.lastActivityAt(), last.id()).encode();
        }
        return new ChatRoomPage(page.stream().map(ChatQueryService::summaryOf).toList(),
                new CursorPageMeta(nextCursor));
    }

    /** 방 머리. 남의 방·없는 방은 404(존재를 숨긴다). */
    public ChatRoomDetail room(long wholesalerId, long roomId) {
        WholesaleSummaryRow row = roomRepository.findWholesaleSummary(roomId, wholesalerId)
                .orElseThrow(ChatQueryService::roomNotFound);
        return ChatRoomDetail.of(summaryOf(row), row.room().retailerLastReadSequence());
    }

    /**
     * 메시지 조회. data 는 늘 sequence 오름차순.
     * after → 그 다음부터 size 개(hasMore = 더 새 것), before → 바로 앞 size 개(hasMore = 더 오래된 것), 둘 다 없음 → 최신 size 개.
     */
    public ChatMessagePage messages(long wholesalerId, long roomId, Long afterSequence, Long beforeSequence, int size) {
        RoomRow room = roomRepository.findForWholesaler(roomId, wholesalerId)
                .orElseThrow(ChatQueryService::roomNotFound);
        validateSize(size);
        if (afterSequence != null && beforeSequence != null) {
            throw ApiException.validationFailed("afterSequence", "afterSequence 와 beforeSequence 는 같이 쓸 수 없습니다.");
        }
        if (afterSequence != null && afterSequence < 0) {
            throw ApiException.validationFailed("afterSequence", "0 이상이어야 합니다.");
        }
        if (beforeSequence != null && beforeSequence < 1) {
            throw ApiException.validationFailed("beforeSequence", "1 이상이어야 합니다.");
        }

        List<ChatMessage> rows = afterSequence != null
                ? messageRepository.findAfter(roomId, afterSequence, size + 1)
                : messageRepository.findBefore(roomId, beforeSequence, size + 1);
        boolean hasMore = rows.size() > size;
        List<ChatMessage> data;
        if (!hasMore) {
            data = rows;
        } else if (afterSequence != null) {
            data = rows.subList(0, size);            // 오름차순 앞 size 개, 넘친 한 개는 더 새 것
        } else {
            data = rows.subList(1, rows.size());     // 가장 오래된 한 개가 넘친 것
        }
        return new ChatMessagePage(List.copyOf(data), new ChatMessagePageMeta(
                hasMore, room.lastSequence(), room.retailerLastReadSequence()));
    }

    static ChatRoomSummary summaryOf(WholesaleSummaryRow row) {
        RoomRow room = row.room();
        ChatMessagePreview last = row.lastMessageSequence() == null ? null : new ChatMessagePreview(
                row.lastMessageSequence(), row.lastMessageSender(), row.lastMessageKind(),
                preview(row.lastMessageBody()), utc(row.lastMessageSentAt()));
        boolean openHandoff = room.openHandoffSequence() != null;
        boolean lastIsRetailerText = last != null
                && last.sender() == ChatSender.RETAILER && last.kind() == ChatMessageKind.TEXT;
        return new ChatRoomSummary(
                room.id(),
                new ChatCounterpart(room.retailerId(), room.retailerName()),
                last,
                utc(room.lastActivityAt()),
                row.unreadCount(),
                room.wholesalerLastReadSequence(),
                openHandoff || lastIsRetailerText,
                openHandoff);
    }

    /** 본문 앞 80 코드 포인트. 줄바꿈은 공백으로. */
    static String preview(String body) {
        String flat = LINE_BREAK.matcher(body).replaceAll(" ");
        int end = flat.offsetByCodePoints(0, Math.min(PREVIEW_MAX, flat.codePointCount(0, flat.length())));
        return flat.substring(0, end);
    }

    static ApiException roomNotFound() {
        return new ResourceNotFoundException("대화를 찾을 수 없습니다.");
    }

    private static void validateSize(int size) {
        if (size < 1 || size > SIZE_MAX) {
            throw ApiException.validationFailed("size", "size 는 1~" + SIZE_MAX + " 이어야 합니다.");
        }
    }

    /** 빈 값은 없는 것으로 본다. 모르는 값은 400. */
    private static ChatRoomFilter parseFilter(String filter) {
        if (filter == null || filter.isEmpty()) {
            return null;
        }
        for (ChatRoomFilter f : ChatRoomFilter.values()) {
            if (f.name().equals(filter)) {
                return f;
            }
        }
        throw ApiException.validationFailed("filter", "filter 는 UNREAD | NEEDS_REPLY 이다.");
    }

    /** 앞뒤 공백을 지운 뒤 비면 조건 없음(null). 50 코드 포인트를 넘으면 400. 영문 대문자만 소문자로 접는다. */
    private static String foldQuery(String q) {
        if (q == null) {
            return null;
        }
        String trimmed = q.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.codePointCount(0, trimmed.length()) > QUERY_MAX) {
            throw ApiException.validationFailed("q", "검색어는 " + QUERY_MAX + "자까지입니다.");
        }
        StringBuilder folded = new StringBuilder(trimmed.length());
        for (char c : trimmed.toCharArray()) {
            folded.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return folded.toString();
    }

    private static OffsetDateTime utc(OffsetDateTime time) {
        return time == null ? null : time.withOffsetSameInstant(ZoneOffset.UTC);
    }
}
