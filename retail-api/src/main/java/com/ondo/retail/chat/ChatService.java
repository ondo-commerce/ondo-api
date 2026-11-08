package com.ondo.retail.chat;

import com.ondo.retail.chat.dto.ChatMessageResponses.ChatInquiry;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatMessagePage;
import com.ondo.retail.chat.dto.ChatMessageResponses.ChatReadResult;
import com.ondo.retail.chat.dto.ChatRoomResponses.ChatRoomDetail;
import com.ondo.retail.chat.dto.ChatRoomResponses.ChatRoomPage;
import com.ondo.retail.common.error.BusinessException;
import com.ondo.retail.common.error.ErrorCode;
import com.ondo.retail.common.error.ErrorResponse.FieldError;
import com.ondo.retail.retailer.RetailerRepository;
import com.ondo.retail.retailer.domain.Retailer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 소매 채팅 6 op (MUL-157). 설계: docs/design/chat.md a 안 — 방·메시지·판정은 도매가 맡고 여기는 얇은 프록시다.
 *
 * <p>여기는 <b>프론트 계약의 검증과 커서</b>만 맡는다. 검증을 여기서 하는 건 에러 모양(errors[{field}])이 소매 계약이라서다.
 * 방이 남의 것인지, 같은 키에 다른 본문인지, 글자 수·읽음 위치가 방의 범위 안인지는 도매가 본다(원본이 거기 있다) —
 * 어댑터가 그 거절을 소매 코드로 옮긴다.
 *
 * <p>{@code @Transactional} 이 없다. 소매 DB 는 상호 한 줄만 읽고, 도매 호출을 트랜잭션 안에 두면 커넥션을 붙든 채 기다린다.
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    static final int ROOM_DEFAULT_SIZE = 30;
    static final int MESSAGE_DEFAULT_SIZE = 50;
    static final int MAX_SIZE = 100;
    private static final Pattern UUID_SHAPE =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Set<String> INTENTS = Set.of("STOCK", "BACKORDER", "RECEIVABLE", "OTHER");

    private final ChatClient client;
    private final RetailerRepository retailerRepository;

    /** 방 목록 한 장. size + 1 개를 물어 하나가 남으면 다음 장이 있다. */
    public ChatRoomPage rooms(Long retailerId, String rawSize, String rawCursor) {
        int size = parseSize(rawSize, ROOM_DEFAULT_SIZE);
        ChatRoomCursor before = null;
        if (rawCursor != null && !rawCursor.isEmpty()) {
            before = ChatRoomCursor.decode(rawCursor)
                    .orElseThrow(() -> invalid("cursor", "INVALID_CURSOR", "cursor 가 올바르지 않아요"));
        }
        List<ChatRoomDetail> rows = client.rooms(retailerId, before, size + 1);
        boolean hasNext = rows.size() > size;
        List<ChatRoomDetail> page = hasNext ? rows.subList(0, size) : rows;
        String next = null;
        if (hasNext) {
            ChatRoomDetail last = page.getLast();
            next = ChatRoomCursor.of(last.lastActivityAt(), last.roomId()).encode();
        }
        return new ChatRoomPage(page.stream().map(ChatRoomDetail::summary).toList(), new ChatRoomPage.Meta(next));
    }

    /**
     * 방 열기. 본문은 {@code {wholesalerId, listingId}} 둘 다 필수 칸이다(listingId 는 null 일 수 있다 — 도매처 홈에서 연 방).
     * 소매처 상호는 소매 DB 에서 읽어 도매에 실어 보낸다 — 거래 전 소매처도 방을 열 수 있어 도매가 이름을 모를 수 있다.
     */
    public ChatClient.Opened open(Long retailerId, Map<String, Object> body) {
        if (!(body.get("wholesalerId") instanceof Number id) || !isWhole(id)) {
            throw invalid("wholesalerId", "NOT_NULL", "도매처를 골라 주세요");
        }
        if (!body.containsKey("listingId")) {
            throw invalid("listingId", "NOT_NULL", "listingId 가 필요해요");
        }
        Object listing = body.get("listingId");
        if (listing != null && (!(listing instanceof Number n) || !isWhole(n))) {
            throw invalid("listingId", "INVALID", "listingId 는 정수여야 해요");
        }
        String shopName = retailerRepository.findById(retailerId).map(Retailer::getShopName)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        return client.open(retailerId, shopName, id.longValue(), listing == null ? null : ((Number) listing).longValue());
    }

    public ChatRoomDetail room(Long retailerId, Long roomId) {
        return client.room(retailerId, roomId);
    }

    /** 메시지 조회. after 와 before 는 같이 못 쓴다. after ≥ 0, before ≥ 1, size 1~100(기본 50). */
    public ChatMessagePage messages(Long retailerId, Long roomId, String rawAfter, String rawBefore, String rawSize) {
        Long after = parseLong(rawAfter, "afterSequence");
        Long before = parseLong(rawBefore, "beforeSequence");
        if (after != null && before != null) {
            throw invalid("afterSequence", "MUTUALLY_EXCLUSIVE", "afterSequence 와 beforeSequence 는 같이 쓸 수 없어요");
        }
        if (after != null && after < 0) {
            throw invalid("afterSequence", "OUT_OF_RANGE", "0 이상이어야 해요");
        }
        if (before != null && before < 1) {
            throw invalid("beforeSequence", "OUT_OF_RANGE", "1 이상이어야 해요");
        }
        int size = parseSize(rawSize, MESSAGE_DEFAULT_SIZE);
        return client.messages(retailerId, roomId, after, before, size);
    }

    /**
     * 보내기. 키 형식과 본문 모양만 여기서 본다. 빈 본문·1,000자 초과는 도매가 본다 — 같은 키에 다른 본문이면 그 본문이
     * 규칙에 맞든 아니든 409 가 계약이라 멱등 확인(방이 있는 도매)이 글자 수보다 먼저다.
     */
    public ChatClient.Sent send(Long retailerId, Long roomId, String idempotencyKey, Map<String, Object> body) {
        if (idempotencyKey == null || !UUID_SHAPE.matcher(idempotencyKey).matches()) {
            throw invalid("Idempotency-Key", "INVALID_FORMAT", "Idempotency-Key 헤더(uuid)가 필요해요");
        }
        if (body == null || !(body.get("body") instanceof String text)) {
            throw invalid("body", "NOT_BLANK", "메시지를 입력해 주세요");
        }
        return client.send(retailerId, roomId, idempotencyKey, text, inquiry(body.get("inquiry")));
    }

    public ChatReadResult markRead(Long retailerId, Long roomId, Map<String, Object> body) {
        if (body == null || !(body.get("lastReadSequence") instanceof Number n) || !isWhole(n) || n.longValue() < 0) {
            throw invalid("lastReadSequence", "OUT_OF_RANGE", "방에 없는 sequence 예요");
        }
        return client.markRead(retailerId, roomId, n.longValue());
    }

    // ── 검증 ────────────────────────────────────────────────

    /** 칩 문맥. intent 는 네 값 중 하나, 나머지 칸은 정수 또는 null. */
    private static ChatInquiry inquiry(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map) || !(map.get("intent") instanceof String intent)
                || !INTENTS.contains(intent)) {
            throw invalid("inquiry.intent", "INVALID", "문의 종류가 올바르지 않아요");
        }
        Object label = map.get("label");
        Object notListed = map.get("notListed");
        if ((label != null && !(label instanceof String)) || (notListed != null && !(notListed instanceof Boolean))) {
            throw invalid("inquiry", "INVALID", "문의 문맥이 올바르지 않아요");
        }
        return new ChatInquiry(intent, id(map.get("listingId"), "inquiry.listingId"),
                id(map.get("variantId"), "inquiry.variantId"), id(map.get("backorderId"), "inquiry.backorderId"),
                (String) label, (Boolean) notListed);
    }

    private static Long id(Object raw, String field) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number n) || !isWhole(n)) {
            throw invalid(field, "INVALID", field + " 는 정수여야 해요");
        }
        return n.longValue();
    }

    private static boolean isWhole(Number n) {
        return n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte
                || (n instanceof java.math.BigInteger b && b.bitLength() < 64);
    }

    private static int parseSize(String raw, int fallback) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        if (!raw.matches("\\d{1,3}")) {
            throw invalid("size", "OUT_OF_RANGE", "size 는 1~" + MAX_SIZE + " 이어야 해요");
        }
        int size = Integer.parseInt(raw);
        if (size < 1 || size > MAX_SIZE) {
            throw invalid("size", "OUT_OF_RANGE", "size 는 1~" + MAX_SIZE + " 이어야 해요");
        }
        return size;
    }

    private static Long parseLong(String raw, String field) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (!raw.matches("-?\\d{1,18}")) {
            throw invalid(field, "INVALID", field + " 는 정수여야 해요");
        }
        return Long.parseLong(raw);
    }

    static BusinessException invalid(String field, String code, String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, List.of(new FieldError(field, code, message)));
    }
}
