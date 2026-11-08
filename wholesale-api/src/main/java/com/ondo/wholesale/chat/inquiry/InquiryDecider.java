package com.ondo.wholesale.chat.inquiry;

import com.ondo.wholesale.chat.domain.ChatChoiceKind;
import com.ondo.wholesale.chat.domain.ChatHandoffReason;
import com.ondo.wholesale.chat.domain.ChatIntent;
import com.ondo.wholesale.chat.domain.ChatMessageKind;
import com.ondo.wholesale.chat.domain.ChatSender;
import com.ondo.wholesale.chat.domain.StockBand;
import com.ondo.wholesale.chat.dto.ChatAutoReply;
import com.ondo.wholesale.chat.dto.ChatAutoReply.ChatChoice;
import com.ondo.wholesale.chat.dto.ChatAutoReply.ChatReceivableFacts;
import com.ondo.wholesale.chat.dto.ChatInquiry;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.BackorderAnswer;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.BackorderClarify;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.EtaState;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.Handoff;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.PackingAnswer;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.ReceivableAnswer;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.StockAnswer;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.StockClarify;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Backorder;
import com.ondo.wholesale.chat.inquiry.InquiryInput.HistoryItem;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Listing;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Packing;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Receivable;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Variant;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.ondo.wholesale.chat.inquiry.InquiryText.compact;
import static com.ondo.wholesale.chat.inquiry.InquiryText.containsAny;

/**
 * 재고 · 미송 · 미수 문의 판정 (MUL-157). 웹 목 {@code packages/api/src/mocks/chat/inquiry.ts} 의 {@code decideInquiry}
 * 를 그대로 옮겼다(ondo-web dev @ cdb20a1). 골든셋 132 시나리오 · 167 단계 + 정규화 17줄이 심판이다
 * ({@code InquiryGoldenScenarioTest}).
 *
 * <p>순수 함수다 — 시계도 저장소도 모른다. 원본 조회는 {@link InquiryInput} 의 함수를 부른다.
 *
 * <p>지키는 것 하나: <b>틀린 숫자를 말하느니 사람에게 넘긴다.</b> 애매하면 전부 HANDOFF 다.
 *
 * <p>판정 순서가 규칙이다(위에서부터 처음 걸린 것): 0 꺼짐 → 1 사람 요청 → 1-1 협상어 → 1-2 입금 알림 → 의도 둘 →
 * 1-3 "얼마"+기간 → 2 재고 되묻기 계승 → 4·5 의도 → 미송·미수 가지 → 6 상품 문맥 → 7 대상 → 8·9 검증과 답.
 */
public final class InquiryDecider {

    private InquiryDecider() {
    }

    /** 상품 문맥과 되묻기가 살아 있는 시간. 골든셋 {@code rules.contextMinutes} 와 같아야 한다. */
    static final Duration CONTEXT_TTL = Duration.ofMinutes(30);

    /** 구간 경계: 0 이하 NONE / 1~9 / 10~49 / 50 이상. 골든셋 {@code rules.bands} 와 같아야 한다. */
    static StockBand stockBand(int availableQty) {
        if (availableQty <= 0) {
            return StockBand.NONE;
        }
        if (availableQty < 10) {
            return StockBand.UNDER_10;
        }
        return availableQty < 50 ? StockBand.UNDER_50 : StockBand.FIFTY_PLUS;
    }

    /** 한도 0 = 없음 = null. 음수도 없음으로 본다. */
    static Integer orderLimitOf(int raw) {
        return raw > 0 ? raw : null;
    }

    /** 되묻기 보기 순서: 색상(처음 등록된 순서) → 사이즈. 게시된 옵션만. */
    static List<Variant> choiceOrder(Listing listing) {
        List<Variant> published = listing.variants().stream().filter(Variant::published).toList();
        List<String> colors = new ArrayList<>(new LinkedHashSet<>(published.stream().map(Variant::color).toList()));
        List<Variant> sorted = new ArrayList<>(published);
        sorted.sort(Comparator.<Variant>comparingInt(v -> colors.indexOf(v.color()))
                .thenComparingInt(v -> InquiryText.SIZE_ORDER.indexOf(v.size())));
        return sorted;
    }

    /* --- 입구 ---------------------------------------------------------------- */

    /**
     * 판정 0~9. 꺼짐(0)이면 이유는 OFF, intent 는 켜졌다면 나갔을 판정의 intent 다 — 꺼진 시간의 문의를 전부
     * 「미분류」로 세지 않게. 그 재판정이 원본 조회로 죽으면 intent null 로 둔다.
     */
    public static InquiryDecision decide(InquiryInput input) {
        if (!input.settings().enabled()) {
            try {
                InquiryDecision would = decideEnabled(input.withSettings(input.settings().withEnabled(true)));
                return handoff(ChatHandoffReason.OFF, would.intent());
            } catch (RuntimeException e) {
                return handoff(ChatHandoffReason.OFF, null);
            }
        }
        return decideEnabled(input);
    }

    private static InquiryDecision decideEnabled(InquiryInput input) {
        AutoReplySettings settings = input.settings();
        ChatInquiry inquiry = input.inquiry();
        Instant now = input.now();
        String text = InquiryText.normalize(input.body());
        String flat = compact(text);

        // 1. 사람 요청 → 1-1. 협상어 → 1-2. 입금 알림(미수 낱말이 같이 있어도 금액을 말하지 않는다)
        if (InquiryText.isHumanRequest(text, flat)) {
            return handoff(ChatHandoffReason.HUMAN_REQUEST, null);
        }
        if (InquiryText.isNegotiation(text, flat)) {
            return handoff(ChatHandoffReason.NEGOTIATION, null);
        }
        if (containsAny(flat, InquiryText.PAYMENT_REPORT_WORDS)) {
            return handoff(ChatHandoffReason.PAYMENT_REPORT, ChatIntent.RECEIVABLE);
        }

        OpenClarify open = openClarify(input.history(), now);
        // 재고 계승(2, 3, 7)은 재고 되묻기만 본다. 미송 되묻기는 미송 가지가 따로 본다
        OpenClarify prev = open != null && open.intent() == ChatIntent.STOCK ? open : null;
        Long freshContext = input.context() != null
                && !Duration.between(input.context().at(), now).minus(CONTEXT_TTL).isPositive()
                ? input.context().listingId() : null;
        Long wordListingId = firstNonNull(inquiry == null ? null : inquiry.listingId(),
                prev == null ? null : prev.listingId(), freshContext);
        Set<ChatIntent> intents = keywordIntents(text,
                wordListingId == null ? null : input.catalog().find(wordListingId));
        if (intents.size() >= 2) {
            return handoff(ChatHandoffReason.MULTI_INTENT, null);
        }
        // 칩 의도와 본문 의도가 다르면 의도 둘로 본다. 칩만 믿으면 미송 질문에 재고 숫자가 나간다
        if (inquiry != null && intents.size() == 1 && !said(intents, inquiry.intent())) {
            return handoff(ChatHandoffReason.MULTI_INTENT, null);
        }
        // 1-3. "이번 달 얼마 남았어요?" — 재고 낱말뿐이지만 "얼마" + 기간 말은 돈을 묻는 문장일 수 있다
        if (intents.size() == 1 && intents.contains(ChatIntent.STOCK) && flat.contains("얼마")
                && InquiryText.hasPeriodWord(input.body())) {
            return handoff(ChatHandoffReason.PERIOD, ChatIntent.RECEIVABLE);
        }

        // 2. 재고 되묻기 계승: 같은 의도, 같은 상품일 때만. 칩이면 variantId 가 보기 안, 아니면 본문이 보기 하나
        if (prev != null && prev.listingId() != null) {
            Listing listing = ownListing(input, prev.listingId());
            boolean sameIntent = (intents.isEmpty() || said(intents, prev.intent()))
                    && (inquiry == null || inquiry.intent() == prev.intent());
            boolean sameListing = inquiry == null || inquiry.listingId() == null
                    || inquiry.listingId().equals(prev.listingId());
            if (listing != null && sameIntent && sameListing) {
                List<Variant> choices = listing.variants().stream()
                        .filter(v -> prev.variantIds().contains(v.variantId()))
                        .toList();
                // 칩의 variantId 가 보기 밖이면 본문으로 다시 고르지 않는다 — 소매가 고른 건 그 옵션이다
                Variant picked = inquiry != null && inquiry.variantId() != null
                        ? choices.stream().filter(v -> v.variantId() == inquiry.variantId()).findFirst().orElse(null)
                        : matchVariant(text, choices);
                if (picked != null) {
                    if (!settings.intentOn(prev.intent())) {
                        return handoff(ChatHandoffReason.OFF, prev.intent());
                    }
                    return answer(listing, picked, text);
                }
            }
            // 3. 계승 안 됨 → 끊고 4부터 새로. 끝이 같은 상품 옵션 불명이면 또 묻지 않는다(아래 7)
        }

        // 미송 되묻기 계승: 이번 의도가 없거나 미송일 때만. 재고 질문이면 끊고 새로 판정한다
        OpenClarify backorderPrev = open != null && open.intent() == ChatIntent.BACKORDER
                && (intents.isEmpty() || said(intents, ChatIntent.BACKORDER))
                && (inquiry == null || inquiry.intent() == ChatIntent.BACKORDER)
                ? open : null;

        // 4, 5. 의도
        ChatIntent keyword = intents.isEmpty() ? null : intents.iterator().next();
        ChatIntent intent = backorderPrev != null ? ChatIntent.BACKORDER
                : inquiry != null ? inquiry.intent() : keyword;
        if (intent == null) {
            return handoff(ChatHandoffReason.NO_INTENT, null);
        }
        if (!settings.intentOn(intent)) {
            return handoff(intent == ChatIntent.OTHER ? ChatHandoffReason.NOT_SUPPORTED : ChatHandoffReason.OFF,
                    intent);
        }
        if (intent == ChatIntent.BACKORDER) {
            return decideBackorder(input, text, backorderPrev);
        }
        // 미수는 되묻기가 없고 다른 의도의 열린 되묻기를 계승하지 않는다
        if (intent == ChatIntent.RECEIVABLE) {
            return decideReceivable(input);
        }
        if (intent != ChatIntent.STOCK) {
            return handoff(ChatHandoffReason.NOT_SUPPORTED, intent);
        }

        // 6. 상품 문맥: inquiry.listingId > 방 문맥(30분 안)
        Long listingId = inquiry != null && inquiry.listingId() != null ? inquiry.listingId() : freshContext;
        if (listingId == null) {
            return handoff(ChatHandoffReason.NO_CONTEXT, ChatIntent.STOCK);
        }
        // 8(앞부분). 남의 도매처, 없는 상품은 되묻기 전에 넘긴다 — 남의 옵션을 칩으로 보여 주지 않는다
        Listing listing = ownListing(input, listingId);
        if (listing == null) {
            return handoff(ChatHandoffReason.INVALID_TARGET, ChatIntent.STOCK);
        }

        // 7. 대상: inquiry.variantId > 본문 옵션어 > 게시 옵션 1개 > 0개면 넘김 > 되묻기
        if (inquiry != null && inquiry.variantId() != null) {
            long wanted = inquiry.variantId();
            return answer(listing,
                    listing.variants().stream().filter(v -> v.variantId() == wanted).findFirst().orElse(null), text);
        }
        List<Variant> published = choiceOrder(listing);
        Variant byText = matchVariant(text, published);
        if (byText != null) {
            return answer(listing, byText, text);
        }
        if (published.size() == 1) {
            return answer(listing, published.getFirst(), text);
        }
        if (published.isEmpty()) {
            return handoff(ChatHandoffReason.NO_OPTIONS, ChatIntent.STOCK);
        }
        if (prev != null && Objects.equals(prev.listingId(), listing.listingId())) {
            return handoff(ChatHandoffReason.CLARIFY_AGAIN, ChatIntent.STOCK);
        }
        return new StockClarify(listing, published);
    }

    /* --- 미송 ---------------------------------------------------------------- */

    /**
     * 미송 가지. 위에서부터: a 「다른 주문이에요」 → NOT_LISTED / b 칩(backorderId) → 30분 안 가장 최근 미송 되묻기의
     * 보기 안이고 지금도 후보면 답, 아니면 INVALID_TARGET / c 배송어 → NOT_SUPPORTED / d 열린 미송 되묻기 계승 →
     * 확정이면 답, 아니면 CLARIFY_AGAIN / e 후보 0 → 포장 단계 / f 본문 좁히기.
     */
    private static InquiryDecision decideBackorder(InquiryInput input, String text, OpenClarify prev) {
        ChatInquiry inquiry = input.inquiry();
        if (inquiry != null && Boolean.TRUE.equals(inquiry.notListed())) {
            return handoff(ChatHandoffReason.NOT_LISTED, ChatIntent.BACKORDER);
        }
        List<Backorder> all = BackorderCandidates.candidatesOf(
                input.backorders().get(), input.wholesalerId(), input.retailerId());

        if (inquiry != null && inquiry.backorderId() != null) {
            long chip = inquiry.backorderId();
            List<Long> shown = latestBackorderClarify(input.history(), input.now());
            Backorder target = shown != null && shown.contains(chip)
                    ? all.stream().filter(b -> b.backorderId() == chip).findFirst().orElse(null)
                    : null;
            return target == null
                    ? handoff(ChatHandoffReason.INVALID_TARGET, ChatIntent.BACKORDER)
                    : backorderAnswer(target, input);
        }

        if (containsAny(compact(text), InquiryText.DELIVERY_WORDS)) {
            return handoff(ChatHandoffReason.NOT_SUPPORTED, ChatIntent.BACKORDER);
        }

        if (prev != null) {
            List<Backorder> pool = all.stream().filter(b -> prev.backorderIds().contains(b.backorderId())).toList();
            Backorder confirmed = BackorderCandidates.narrow(text, pool).confirmed();
            return confirmed == null
                    ? handoff(ChatHandoffReason.CLARIFY_AGAIN, ChatIntent.BACKORDER)
                    : backorderAnswer(confirmed, input);
        }

        if (all.isEmpty()) {
            return decidePacking(input, text);
        }
        BackorderCandidates.Narrowed n = BackorderCandidates.narrow(text, all);
        // blocked: 하나로 좁혀졌지만 모르는 말(후보에 없는 상품명일 수 있다)이 있다 — 그 칩으로 되물으면 안 된다
        if (n.said() && (n.left().isEmpty() || n.blocked())) {
            return handoff(ChatHandoffReason.NO_MATCH, ChatIntent.BACKORDER);
        }
        if (n.confirmed() != null) {
            return backorderAnswer(n.confirmed(), input);
        }
        List<Backorder> left = n.left();
        return new BackorderClarify(left.subList(0, Math.min(left.size(), BackorderCandidates.CHOICE_MAX)),
                left.size() > BackorderCandidates.CHOICE_MAX, input.today());
    }

    /**
     * 미송 후보 0일 때. 본문이 대상을 말함 → NO_BACKORDER(원본을 부르지 않는다) / 포장 원본이 던짐 → NO_BACKORDER /
     * 유효한 출고 준비 중 주문 0 → NO_BACKORDER / 1건 이상 → 포장 답(+ NO_BACKORDER 넘김 줄).
     */
    private static InquiryDecision decidePacking(InquiryInput input, String text) {
        if (BackorderCandidates.mentionsTarget(text)) {
            return handoff(ChatHandoffReason.NO_BACKORDER, ChatIntent.BACKORDER);
        }
        List<Packing> rows;
        try {
            rows = input.packings().get();
        } catch (RuntimeException e) {
            return handoff(ChatHandoffReason.NO_BACKORDER, ChatIntent.BACKORDER);
        }
        List<Long> ids = PackingOrders.orderIdsOf(rows, input.wholesalerId(), input.retailerId());
        return ids.isEmpty()
                ? handoff(ChatHandoffReason.NO_BACKORDER, ChatIntent.BACKORDER)
                : new PackingAnswer(ids);
    }

    private static InquiryDecision backorderAnswer(Backorder b, InquiryInput input) {
        return new BackorderAnswer(b, EtaState.of(b.expectedInboundDate(), input.today()), input.today());
    }

    /* --- 미수 ---------------------------------------------------------------- */

    /**
     * 미수 가지. 되묻기는 없다. a 기간 말 → PERIOD / b 원본이 던짐 → LOOKUP_FAILED / c 이 쌍의 줄 없음 → NO_RELATION /
     * d 이상값 → AMOUNT_UNSURE / e 정해 둔 문장이 아님 → AMOUNT_UNSURE / f 답(checkedAt = 원본 asOf).
     */
    private static InquiryDecision decideReceivable(InquiryInput input) {
        if (InquiryText.hasPeriodWord(input.body())) {
            return handoff(ChatHandoffReason.PERIOD, ChatIntent.RECEIVABLE);
        }
        List<Receivable> rows;
        try {
            rows = input.receivables().get();
        } catch (RuntimeException e) {
            return handoff(ChatHandoffReason.LOOKUP_FAILED, ChatIntent.RECEIVABLE);
        }
        Optional<Receivable> row = ReceivableFacts.of(rows, input.wholesalerId(), input.retailerId());
        if (row.isEmpty()) {
            return handoff(ChatHandoffReason.NO_RELATION, ChatIntent.RECEIVABLE);
        }
        Optional<ChatReceivableFacts> facts = ReceivableFacts.convert(row.get());
        if (facts.isEmpty() || !InquiryText.isReceivableAmountQuestion(input.body())) {
            return handoff(ChatHandoffReason.AMOUNT_UNSURE, ChatIntent.RECEIVABLE);
        }
        return new ReceivableAnswer(facts.get(), row.get().asOf());
    }

    /* --- 재고 대상 ------------------------------------------------------------ */

    /** 판정 8, 9. 대상이 정해진 뒤의 검증과 답. */
    private static InquiryDecision answer(Listing listing, Variant variant, String normalized) {
        if (variant == null || !variant.published()) {
            return handoff(ChatHandoffReason.INVALID_TARGET, ChatIntent.STOCK);
        }
        return new StockAnswer(listing, variant, stockBand(variant.availableQty()),
                listing.published() && variant.published(), orderLimitOf(variant.orderLimit()),
                InquiryText.QTY_IN_TEXT.matcher(normalized).find());
    }

    /** 이 방 도매처의 상품인지까지 본다. 아니면 null(→ INVALID_TARGET). */
    private static Listing ownListing(InquiryInput input, long listingId) {
        Listing listing = input.catalog().find(listingId);
        return listing != null && listing.wholesalerId() == input.wholesalerId() ? listing : null;
    }

    /**
     * 본문의 색상 <b>과</b> 사이즈가 가리키는 옵션이 하나면 그것. 한쪽만으로 좁히지 않는다 — "빨강 L" 이 "L" 로만 읽혀
     * 소매가 말하지 않은 색의 재고를 답하게 된다. 예외: 보기 전체에서 값이 하나뿐인 칸은 말하지 않아도 읽은 것으로 친다.
     */
    private static Variant matchVariant(String normalized, List<Variant> variants) {
        String flat = compact(normalized);
        List<String> allColors = new ArrayList<>(new LinkedHashSet<>(variants.stream().map(Variant::color).toList()));
        List<String> allSizes = new ArrayList<>(new LinkedHashSet<>(variants.stream().map(Variant::size).toList()));
        List<String> saidColors = allColors.stream().filter(flat::contains).toList();
        List<String> saidSizes = BackorderCandidates.sizesIn(normalized).stream().filter(allSizes::contains).toList();
        if (saidColors.isEmpty() && saidSizes.isEmpty()) {
            return null;
        }
        List<String> colors = !saidColors.isEmpty() ? saidColors : allColors.size() == 1 ? allColors : List.of();
        List<String> sizes = !saidSizes.isEmpty() ? saidSizes : allSizes.size() == 1 ? allSizes : List.of();
        List<Variant> hits = variants.stream()
                .filter(v -> colors.contains(v.color()) && sizes.contains(v.size()))
                .toList();
        return hits.size() == 1 ? hits.getFirst() : null;
    }

    private static boolean hasOptionWord(String normalized, Listing listing) {
        if (listing == null) {
            return false;
        }
        String flat = compact(normalized);
        List<String> sizes = BackorderCandidates.sizesIn(normalized);
        return listing.variants().stream().anyMatch(v -> flat.contains(v.color()) || sizes.contains(v.size()));
    }

    /** 공백 지운 본문의 의도 키워드. 재고의 「있어요」 무리는 옵션어가 같이 있을 때만. */
    private static Set<ChatIntent> keywordIntents(String normalized, Listing listing) {
        String flat = compact(normalized);
        Set<ChatIntent> found = EnumSet.noneOf(ChatIntent.class);
        if (containsAny(flat, InquiryText.STOCK_WORDS)
                || (containsAny(flat, InquiryText.STOCK_WORDS_WITH_OPTION) && hasOptionWord(normalized, listing))) {
            found.add(ChatIntent.STOCK);
        }
        if (containsAny(flat, InquiryText.BACKORDER_WORDS)) {
            found.add(ChatIntent.BACKORDER);
        }
        if (containsAny(flat, InquiryText.RECEIVABLE_WORDS)) {
            found.add(ChatIntent.RECEIVABLE);
        }
        return found;
    }

    private static boolean said(Set<ChatIntent> intents, ChatIntent intent) {
        return intent != ChatIntent.OTHER && intents.contains(intent);
    }

    private static Handoff handoff(ChatHandoffReason reason, ChatIntent intent) {
        return new Handoff(reason, intent);
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /* --- 열린 되묻기 ----------------------------------------------------------- */

    /**
     * 직전 유효 되묻기.
     *
     * @param listingId    재고 되묻기의 상품. 미송 되묻기는 null
     * @param backorderIds 미송 되묻기 보기의 미송 id(NOT_LISTED 제외). 재고 되묻기는 빈 목록 — 두 목록을 섞지 않는다
     */
    record OpenClarify(ChatIntent intent, Long listingId, List<Long> variantIds, List<Long> backorderIds) {
    }

    private static boolean expired(HistoryItem m, Instant now) {
        return Duration.between(m.sentAt(), now).compareTo(CONTEXT_TTL) > 0;
    }

    private static OpenClarify clarifyOf(HistoryItem m) {
        ChatAutoReply a = m.autoReply();
        if (a == null) {
            return null;
        }
        List<ChatChoice> choices = a.choices() == null ? List.of() : a.choices();
        if (a.intent() == ChatIntent.BACKORDER) {
            return new OpenClarify(ChatIntent.BACKORDER, null, List.of(), choices.stream()
                    .filter(c -> c.kind() == ChatChoiceKind.BACKORDER && c.backorderId() != null)
                    .map(ChatChoice::backorderId)
                    .toList());
        }
        Long listingId = a.subject() == null ? null : a.subject().listingId();
        if (listingId == null) {
            return null;
        }
        return new OpenClarify(a.intent(), listingId, choices.stream().map(ChatChoice::variantId).toList(), List.of());
    }

    /** 30분 안, 그 뒤 소매 메시지도 도매 사람 답(TEXT)도 없는 되묻기. */
    static OpenClarify openClarify(List<HistoryItem> history, Instant now) {
        for (int i = history.size() - 1; i >= 0; i--) {
            HistoryItem m = history.get(i);
            if (m.kind() == ChatMessageKind.CLARIFY) {
                return expired(m, now) ? null : clarifyOf(m);
            }
            if (m.sender() == ChatSender.RETAILER) {
                return null;
            }
            if (m.sender() == ChatSender.WHOLESALER && m.kind() == ChatMessageKind.TEXT) {
                return null;
            }
        }
        return null;
    }

    /**
     * 칩 검증용: 30분 안에 연 <b>가장 최근</b> 미송 되묻기의 보기. 그 뒤에 소매가 다른 글을 썼어도 칩은 그 보기에서
     * 고른 것이라 본다(열린 되묻기만 보는 계승과 다르다).
     */
    private static List<Long> latestBackorderClarify(List<HistoryItem> history, Instant now) {
        for (int i = history.size() - 1; i >= 0; i--) {
            HistoryItem m = history.get(i);
            if (m.kind() == ChatMessageKind.CLARIFY && m.autoReply() != null
                    && m.autoReply().intent() == ChatIntent.BACKORDER) {
                if (expired(m, now)) {
                    return null;
                }
                OpenClarify c = clarifyOf(m);
                return c == null ? null : c.backorderIds();
            }
        }
        return null;
    }
}
