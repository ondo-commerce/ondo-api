package com.ondo.wholesale.chat.inquiry;

import com.ondo.wholesale.chat.domain.ChatHandoffReason;
import com.ondo.wholesale.chat.domain.ChatMessageKind;
import com.ondo.wholesale.chat.domain.ChatSender;
import com.ondo.wholesale.chat.dto.ChatAutoReply;
import com.ondo.wholesale.chat.dto.ChatInquiry;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Supplier;

/**
 * 판정 한 번의 입력 (MUL-157). 판정({@link InquiryDecider})은 순수 함수라 시계도 저장소도 모른다 —
 * 부르는 쪽이 지금 시각, 방의 지난 메시지, 원본 조회 함수를 넘긴다. 운영은 {@code InquirySourceReader} 가 DB 에서,
 * 골든셋은 골든 표에서 같은 모양을 만든다.
 *
 * <p>원본(미송·미수·포장)은 <b>거르지 않은 것</b>을 받아도 된다. 이 방 도매처·소매처 것을 고르는 건 판정이 한다 —
 * 화면이 보낸 값을 믿지 않으려면 거르는 곳이 판정 안에 있어야 한다.
 *
 * @param body        소매가 보낸 본문(앞뒤 공백을 지운 값)
 * @param inquiry     칩 문맥. 자유 문장이면 null
 * @param now         판정 시각. 문맥 30분·되묻기 30분을 이 값으로 잰다
 * @param today       서울 날짜. 입고일이 오늘인지·지났는지를 이 값으로 잰다
 * @param context     방의 상품 문맥(createChatRoom 의 listingId 와 그 시각). 없으면 null
 * @param history     이번 메시지보다 <b>앞</b> 메시지들(sequence 오름차순). 이번 메시지는 넣지 않는다
 * @param backorders  미송 원본. 미송 가지에서만 부른다
 * @param receivables 미수 원본. 미수 가지에서만 부른다. 던지면 조회 실패(LOOKUP_FAILED) 넘김
 * @param packings    포장 원본. 미송 후보가 0이고 본문이 대상을 말하지 않았을 때만 부른다. 던지면 NO_BACKORDER 넘김
 */
public record InquiryInput(
        String body,
        ChatInquiry inquiry,
        Instant now,
        LocalDate today,
        long wholesalerId,
        long retailerId,
        AutoReplySettings settings,
        RoomContext context,
        List<HistoryItem> history,
        Catalog catalog,
        Supplier<List<Backorder>> backorders,
        Supplier<List<Receivable>> receivables,
        Supplier<List<Packing>> packings) {

    InquiryInput withSettings(AutoReplySettings next) {
        return new InquiryInput(body, inquiry, now, today, wholesalerId, retailerId, next, context, history,
                catalog, backorders, receivables, packings);
    }

    /** 방의 상품 문맥. 30분 지나면 없는 것으로 본다. */
    public record RoomContext(long listingId, Instant at) {
    }

    /** 게시글 하나. 없으면 {@link Catalog#find} 가 null. */
    @FunctionalInterface
    public interface Catalog {
        Listing find(long listingId);
    }

    /**
     * @param published 게시 중(ON_SALE)인지. 시즌 종료면 false — 답은 하되 주문 가능이 false 다
     * @param variants  등록 순서(variant 순번). 되묻기 보기 순서가 여기서 나온다
     */
    public record Listing(long listingId, long wholesalerId, String title, boolean published, List<Variant> variants) {
    }

    /**
     * 옵션 하나.
     *
     * @param availableQty 판매가능 수량. <b>음수 그대로</b> 받는다(0으로 접는 건 판정이). 소매로 나가지 않는다
     * @param orderLimit   1회 주문 한도 원값. 0은 한도 없음
     * @param published    게시글에 올린 옵션인지({@code listing_variant} 행이 있는지)
     */
    public record Variant(long variantId, String color, String size, int availableQty, int orderLimit,
                          boolean published) {
    }

    /**
     * 열린 미송 한 줄. <b>없는 칸이 규칙이다</b> — 그 SKU 판매가능, SKU 미송 합계, 원래 미송량, 소매처 이름이 없어서
     * 소매에게 나가는 글에 섞일 길이 없다.
     *
     * @param orderId      칩·답 글자의 주문 번호. 소매가 아는 번호여야 한다
     * @param remainingQty 남은 미배분 수량. 0이면 해소
     * @param orderedAt    주문 시각. 후보 순서와 칩 글자의 주문일
     */
    public record Backorder(long backorderId, long orderId, long wholesalerId, long retailerId, long variantId,
                            String title, String color, String size, int remainingQty, Instant orderedAt,
                            LocalDate expectedInboundDate, String expectedInboundReason) {
    }

    /**
     * (도매처, 소매처) 쌍 하나의 정산 요약. 금액은 원 단위.
     *
     * @param balance 갚을 돈 − 선수금. 플러스 = 소매가 갚을 돈
     * @param unpaid  주문별 (출고액 − 배정액) 합
     * @param prepaid 입금 − 배정 합(남은 선수금)
     * @param exact   원본이 금액을 보장하는가. false 면 넘김
     * @param asOf    원본을 읽은 시각. 답의 checkedAt 이 이 값이다
     */
    public record Receivable(long wholesalerId, long retailerId, long balance, long unpaid, long prepaid,
                             long overdueAmount, long overdueCount, long overdueMaxDays, boolean exact,
                             OffsetDateTime asOf) {
    }

    /**
     * 포장 한 줄. 출고 예정일·상품명·옵션 칸이 없다 — 본문에는 주문 번호만 나간다.
     *
     * @param wholesalerId    실제 소유. 모르면 null(판정이 뺀다)
     * @param packingStatus   READY | PACKED
     * @param outboundShipped 담긴 봉투의 출고 여부. 봉투 없으면 null
     */
    public record Packing(long packingId, long orderId, Long wholesalerId, long retailerId, boolean orderCancelled,
                          String packingStatus, Boolean outboundShipped, int qty) {
    }

    /**
     * 지난 메시지 한 줄 중 판정이 읽는 칸.
     *
     * @param autoReply     AUTO_REPLY·CLARIFY 의 근거. 그 밖 null
     * @param handoffReason HANDOFF 줄의 이유. 그 밖 null
     */
    public record HistoryItem(long sequence, ChatSender sender, ChatMessageKind kind, Instant sentAt,
                              ChatAutoReply autoReply, ChatHandoffReason handoffReason) {
    }
}
