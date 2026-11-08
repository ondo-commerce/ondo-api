package com.ondo.wholesale.chat.golden;

import com.ondo.wholesale.chat.domain.ChatIntent;
import com.ondo.wholesale.chat.domain.ChatMessageKind;
import com.ondo.wholesale.chat.domain.ChatSender;
import com.ondo.wholesale.chat.dto.ChatAutoReply;
import com.ondo.wholesale.chat.dto.ChatAutoReply.ChatChoice;
import com.ondo.wholesale.chat.dto.ChatAutoReply.ChatReceivableFacts;
import com.ondo.wholesale.chat.dto.ChatInquiry;
import com.ondo.wholesale.chat.inquiry.AutoReplySettings;
import com.ondo.wholesale.chat.inquiry.InquiryDecider;
import com.ondo.wholesale.chat.inquiry.InquiryDecision;
import com.ondo.wholesale.chat.inquiry.InquiryInput;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Backorder;
import com.ondo.wholesale.chat.inquiry.InquiryInput.HistoryItem;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Listing;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Packing;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Receivable;
import com.ondo.wholesale.chat.inquiry.InquiryInput.RoomContext;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Variant;
import com.ondo.wholesale.chat.inquiry.InquiryLines;
import com.ondo.wholesale.chat.repository.ChatMessageRepository.NewMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

/**
 * 골든셋 → 운영 판정기 어댑터 (MUL-157). 웹 {@code golden.testkit.ts} 의 {@code runScenario} · {@code projectDecision} ·
 * {@code projectBackorderLines} · {@code projectV4Lines} 를 옮겼다.
 *
 * <p>골든 표({@code stock} · {@code backorders} · {@code receivables} · 시나리오 {@code room.packings})로 가짜 원본을 만들고
 * {@link InquiryDecider#decide} → {@link InquiryLines#generated} → {@link InquiryLines#toAppend} 를 차례로 부른다 —
 * 운영 보내기({@code RetailChatService})가 부르는 순서와 같다. 투영만 테스트 몫이다.
 */
final class GoldenInquiryJudge implements InquiryJudge {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final Pattern REL_DATE = Pattern.compile("^D([+-]\\d+)$");

    @Override
    public List<JsonNode> play(JsonNode golden, JsonNode scenario) {
        Tables t = new Tables(golden);
        JsonNode room = scenario.get("room");
        long wholesalerId = room.get("wholesalerId").asLong();
        long retailerId = room.path("retailerId").asLong(1);
        int[] receivableFails = {room.path("receivableFailTimes").asInt(0)};
        int[] packingFails = {room.path("packingFailTimes").asInt(0)};
        List<Packing> packingRows = packings(room.path("packings"));
        Supplier<List<Receivable>> receivables = () -> {
            if (receivableFails[0] > 0) {
                receivableFails[0]--;
                throw new IllegalStateException("골든셋: 정산 조회 실패");
            }
            return t.receivables;
        };
        Supplier<List<Packing>> packings = () -> {
            if (packingFails[0] > 0) {
                packingFails[0]--;
                throw new IllegalStateException("골든셋: 포장 조회 실패");
            }
            return packingRows;
        };
        OffsetDateTime rowAsOf = t.receivables.stream()
                .filter(r -> r.wholesalerId() == wholesalerId && r.retailerId() == retailerId)
                .map(Receivable::asOf).findFirst().orElse(null);

        Instant now = t.t0.plus(Duration.ofDays(room.path("afterDays").asInt(0)));
        RoomContext context = room.get("context").isNull() ? null : new RoomContext(
                room.get("context").get("listingId").asLong(),
                now.minus(Duration.ofMinutes(room.get("context").get("ageMinutes").asLong())));
        AutoReplySettings settings = settings(room);
        boolean v4 = scenario.path("since").asInt(0) >= 4 && !"decision".equals(scenario.path("projection").asString(""));

        List<HistoryItem> history = new ArrayList<>();
        long sequence = 0;
        List<JsonNode> actual = new ArrayList<>();
        for (JsonNode step : scenario.get("steps")) {
            now = now.plus(Duration.ofMinutes(step.path("afterMinutes").asLong(0)));
            if (step.has("enter")) {
                JsonNode listing = step.get("enter").get("listingId");
                context = listing.isNull() ? null : new RoomContext(listing.asLong(), now);
            }
            sequence++;
            long mine = sequence;
            if ("WHOLESALER".equals(step.path("sender").asString(""))) {
                history.add(new HistoryItem(mine, ChatSender.WHOLESALER, ChatMessageKind.TEXT, now, null, null));
                ObjectNode o = F.objectNode();
                o.put("openHandoff", InquiryLines.openHandoffOf(history) != null);
                actual.add(o);
                continue;
            }
            InquiryInput input = new InquiryInput(step.get("text").asString(), inquiry(step.get("inquiry")), now,
                    LocalDate.ofInstant(now, SEOUL), wholesalerId, retailerId, settings, context, List.copyOf(history),
                    t::listing, () -> t.backorders, receivables, packings);
            InquiryDecision decision = InquiryDecider.decide(input);
            InquiryLines.OpenHandoff open = InquiryLines.openHandoffOf(history);
            history.add(new HistoryItem(mine, ChatSender.RETAILER, ChatMessageKind.TEXT, now, null, null));
            List<NewMessage> lines = InquiryLines.generated(decision, mine, now);
            List<NewMessage> kept = InquiryLines.toAppend(lines, open);
            for (NewMessage line : kept) {
                sequence++;
                history.add(historyOf(sequence, line, now));
            }
            actual.add(v4 ? projectV4Lines(kept, mine, now, rowAsOf, t) : projectStep(decision, lines, now, t));
        }
        return actual;
    }

    @Override
    public JsonNode judgeNormalizationCase(JsonNode golden, JsonNode normalizationCase) {
        Tables t = new Tables(golden);
        Instant now = t.t0;
        Instant minuteAgo = now.minus(Duration.ofMinutes(1));
        RoomContext context = new RoomContext(2001, now.minus(Duration.ofMinutes(2)));
        List<HistoryItem> history = new ArrayList<>();
        if (normalizationCase.path("afterClarify").asBoolean(false)) {
            // 직전 유효 되묻기를 깐다: 1분 전 소매 질문 + 같은 시각 되묻기
            InquiryDecision ask = InquiryDecider.decide(normalizationInput(t, "이 셔츠 몇 장 남았어요?", minuteAgo,
                    context, List.of()));
            history.add(new HistoryItem(1, ChatSender.RETAILER, ChatMessageKind.TEXT, minuteAgo, null, null));
            for (NewMessage line : InquiryLines.generated(ask, 1, minuteAgo)) {
                history.add(historyOf(2, line, minuteAgo));
            }
        }
        InquiryDecision d = InquiryDecider.decide(normalizationInput(t, normalizationCase.get("text").asString(), now,
                context, history));
        return projectDecision(d);
    }

    private static InquiryInput normalizationInput(Tables t, String body, Instant now, RoomContext context,
                                                   List<HistoryItem> history) {
        return new InquiryInput(body, null, now, LocalDate.ofInstant(t.t0, SEOUL), 101, 1, AutoReplySettings.DEFAULTS,
                context, history, t::listing, () -> t.backorders, () -> t.receivables, List::of);
    }

    /* --- 골든 표 → 원본 -------------------------------------------------------- */

    private static final class Tables {
        final Instant t0;
        final LocalDate d0;
        final Map<Long, Listing> listings = new LinkedHashMap<>();
        final List<Backorder> backorders = new ArrayList<>();
        final List<Receivable> receivables = new ArrayList<>();

        Tables(JsonNode golden) {
            t0 = OffsetDateTime.parse(golden.get("rules").get("t0").asString()).toInstant();
            d0 = LocalDate.ofInstant(t0, SEOUL);
            Map<Long, List<Variant>> variants = new LinkedHashMap<>();
            Map<Long, JsonNode> heads = new LinkedHashMap<>();
            for (JsonNode r : golden.get("stock")) {
                long id = r.get("listingId").asLong();
                heads.putIfAbsent(id, r);
                variants.computeIfAbsent(id, k -> new ArrayList<>()).add(new Variant(r.get("variantId").asLong(),
                        r.get("color").asString(), r.get("size").asString(), r.get("availableQty").asInt(),
                        r.get("orderLimit").asInt(), r.get("variantPublished").asBoolean()));
            }
            heads.forEach((id, r) -> listings.put(id, new Listing(id, r.get("wholesalerId").asLong(),
                    r.get("listingTitle").asString(), r.get("listingPublished").asBoolean(),
                    List.copyOf(variants.get(id)))));
            for (JsonNode r : golden.get("backorders")) {
                backorders.add(new Backorder(r.get("backorderId").asLong(), r.get("orderId").asLong(),
                        r.get("wholesalerId").asLong(), r.get("retailerId").asLong(), r.get("variantId").asLong(),
                        r.get("title").asString(), r.get("color").asString(), r.get("size").asString(),
                        r.get("remainingQty").asInt(), OffsetDateTime.parse(r.get("orderedAt").asString()).toInstant(),
                        resolve(r.get("expectedInboundDate"), d0), textOrNull(r.get("expectedInboundReason"))));
            }
            for (JsonNode r : golden.get("receivables")) {
                JsonNode o = r.get("overdue");
                receivables.add(new Receivable(r.get("wholesalerId").asLong(), r.get("retailerId").asLong(),
                        r.get("balance").asLong(), r.get("unpaid").asLong(), r.get("prepaid").asLong(),
                        o.get("amount").asLong(), o.get("count").asLong(), o.get("maxDays").asLong(),
                        r.get("exact").asBoolean(), OffsetDateTime.parse(r.get("asOf").asString())));
            }
        }

        Listing listing(long id) {
            return listings.get(id);
        }

        String relDate(LocalDate date) {
            if (date == null) {
                return null;
            }
            long n = ChronoUnit.DAYS.between(d0, date);
            return "D" + (n >= 0 ? "+" : "") + n;
        }
    }

    private static LocalDate resolve(JsonNode rel, LocalDate base) {
        if (rel == null || rel.isNull()) {
            return null;
        }
        Matcher m = REL_DATE.matcher(rel.asString());
        if (!m.matches()) {
            throw new IllegalStateException("골든셋 날짜 " + rel);
        }
        return base.plusDays(Long.parseLong(m.group(1)));
    }

    private static List<Packing> packings(JsonNode rows) {
        if (rows.isMissingNode() || rows.isNull()) {
            return List.of();
        }
        return StreamSupport.stream(rows.spliterator(), false).map(r -> new Packing(
                r.get("packingId").asLong(), r.get("orderId").asLong(),
                r.get("wholesalerId").isNull() ? null : r.get("wholesalerId").asLong(),
                r.get("retailerId").asLong(), r.get("orderCancelled").asBoolean(), r.get("packingStatus").asString(),
                r.get("outboundShipped").isNull() ? null : r.get("outboundShipped").asBoolean(),
                r.get("qty").asInt())).toList();
    }

    private static AutoReplySettings settings(JsonNode room) {
        JsonNode intents = room.path("intents");
        boolean enabled = room.get("autoReply").asBoolean();
        if (intents.isMissingNode()) {
            return new AutoReplySettings(enabled, true, true, true);
        }
        return new AutoReplySettings(enabled, intents.get("STOCK").asBoolean(), intents.get("BACKORDER").asBoolean(),
                intents.get("RECEIVABLE").asBoolean());
    }

    private static ChatInquiry inquiry(JsonNode i) {
        if (i == null || i.isNull()) {
            return null;
        }
        return new ChatInquiry(ChatIntent.valueOf(i.get("intent").asString()), longOrNull(i.get("listingId")),
                longOrNull(i.get("variantId")), longOrNull(i.get("backorderId")), null,
                i.has("notListed") ? i.get("notListed").asBoolean() : null);
    }

    private static HistoryItem historyOf(long sequence, NewMessage line, Instant sentAt) {
        ChatAutoReply autoReply = line.autoReply() == null ? null
                : ChatAutoReply.of(line.intent(), line.sourceSequence(), line.autoReply());
        return new HistoryItem(sequence, line.sender(), line.kind(), sentAt, autoReply, line.handoffReason());
    }

    /* --- 투영 (golden.testkit.ts) ---------------------------------------------- */

    /** 판정 → 골든 투영(재고). 넘김은 reason 까지. */
    private static JsonNode projectDecision(InquiryDecision d) {
        ObjectNode o = F.objectNode();
        switch (d) {
            case InquiryDecision.Handoff h -> {
                o.put("kind", "HANDOFF");
                o.put("reason", h.reason().name());
            }
            case InquiryDecision.StockClarify c -> {
                o.put("kind", "CLARIFY");
                o.put("intent", "STOCK");
                ArrayNode choices = o.putArray("choices");
                c.choices().forEach(v -> choices.add(num(v.variantId())));
            }
            case InquiryDecision.StockAnswer a -> {
                if (a.band() == com.ondo.wholesale.chat.domain.StockBand.NONE) {
                    o.putArray("kinds").add("AUTO_REPLY").add("HANDOFF");
                } else {
                    o.put("kind", "AUTO_REPLY");
                }
                o.put("intent", "STOCK");
                o.set("target", num(a.variant().variantId()));
                o.put("band", a.band().name());
                o.put("orderable", a.orderable());
                o.set("orderLimit", a.orderLimit() == null ? F.nullNode() : num(a.orderLimit()));
                o.put("qtyNote", a.qtyNote());
            }
            default -> throw new IllegalStateException("재고가 아닌 판정은 줄로 투영한다: " + d);
        }
        return o;
    }

    private static JsonNode projectStep(InquiryDecision d, List<NewMessage> lines, Instant now, Tables t) {
        if (d instanceof InquiryDecision.Handoff || d.intent() != ChatIntent.BACKORDER) {
            return projectDecision(d);
        }
        return projectBackorderLines(lines, now, t);
    }

    /** 미송 줄 → 투영. 입고 상태는 <b>본문 문장</b>으로 읽는다 — 날짜 칸만 보면 문장이 틀려도 통과한다. */
    private static JsonNode projectBackorderLines(List<NewMessage> lines, Instant now, Tables t) {
        ObjectNode o = F.objectNode();
        if (lines.isEmpty()) {
            o.putNull("kind");
            return o;
        }
        NewMessage first = lines.getFirst();
        if (first.kind() == ChatMessageKind.HANDOFF && lines.size() == 1) {
            o.put("kind", "HANDOFF");
            return o;
        }
        ChatAutoReply.Detail a = first.autoReply();
        if (first.kind() == ChatMessageKind.CLARIFY) {
            o.put("kind", "CLARIFY");
            o.put("intent", first.intent().name());
            ArrayNode choices = o.putArray("choices");
            ArrayNode labels = o.putArray("labels");
            for (ChatChoice c : a.choices()) {
                ObjectNode choice = choices.addObject();
                choice.put("kind", c.kind() == null ? "VARIANT" : c.kind().name());
                choice.set("backorderId", c.backorderId() == null ? F.nullNode() : num(c.backorderId()));
                labels.add(c.label());
            }
            return o;
        }
        if (lines.size() > 1) {
            ArrayNode kinds = o.putArray("kinds");
            lines.forEach(l -> kinds.add(l.kind().name()));
        } else {
            o.put("kind", first.kind().name());
        }
        String body = first.body();
        String state = body.contains("지났어요") ? "PAST"
                : body.contains("정해지지 않았어요") ? "NONE"
                : body.contains(" 오늘 도매 입고") ? "TODAY" : "FUTURE";
        ChatAutoReply.ChatBackorderFacts facts = a.facts().backorder();
        o.put("intent", first.intent().name());
        o.set("target", a.subject().backorderId() == null ? F.nullNode() : num(a.subject().backorderId()));
        if (facts != null) {
            o.set("remainingQty", num(facts.qty()));
        }
        ObjectNode eta = o.putObject("eta");
        String rel = t.relDate(facts == null ? null : facts.expectedInboundDate());
        if (rel == null) {
            eta.putNull("date");
        } else {
            eta.put("date", rel);
        }
        eta.put("state", state);
        String reason = facts == null ? null : facts.expectedInboundReason();
        if (reason == null) {
            o.putNull("reason");
        } else {
            o.put("reason", reason);
        }
        o.put("checkedAt", a.checkedAt().toInstant().equals(now) ? "now" : a.checkedAt().toString());
        return o;
    }

    /** v4 투영: 방에 <b>들어간</b> 줄 전부. self = 그 단계 소매 메시지, asOf = 그 방 원본 행의 조회 시각. */
    private static JsonNode projectV4Lines(List<NewMessage> kept, long self, Instant now, OffsetDateTime asOf,
                                           Tables t) {
        ObjectNode o = F.objectNode();
        ArrayNode out = o.putArray("lines");
        for (NewMessage l : kept) {
            ObjectNode line = out.addObject();
            line.put("kind", l.kind().name());
            if (l.kind() == ChatMessageKind.HANDOFF) {
                line.put("reason", l.handoffReason().name());
                if (l.intent() == null) {
                    line.putNull("intent");
                } else {
                    line.put("intent", l.intent().name());
                }
                if (l.sourceSequence() == self) {
                    line.put("sourceSequence", "self");
                } else {
                    line.set("sourceSequence", num(l.sourceSequence()));
                }
                continue;
            }
            ChatAutoReply.Detail a = l.autoReply();
            line.put("intent", l.intent().name());
            if (l.kind() == ChatMessageKind.CLARIFY) {
                ArrayNode choices = line.putArray("choices");
                a.choices().forEach(c -> choices.add(num(c.variantId())));
                continue;
            }
            ChatReceivableFacts r = a.facts().receivable();
            if (l.intent() == ChatIntent.RECEIVABLE && r != null) {
                line.set("outstanding", num(r.outstandingAmount()));
                line.set("prepaid", num(r.prepaidAmount()));
                ObjectNode overdue = line.putObject("overdue");
                overdue.set("count", num(r.overdue().count()));
                overdue.set("amount", num(r.overdue().amount()));
                overdue.set("maxDaysOverdue", num(r.overdue().maxDaysOverdue()));
                line.put("body", l.body());
                Instant checked = a.checkedAt().toInstant();
                line.put("checkedAt", asOf != null && checked.equals(asOf.toInstant()) ? "asOf"
                        : checked.equals(now) ? "now" : a.checkedAt().toString());
                continue;
            }
            Long target = a.subject().backorderId() != null ? a.subject().backorderId() : a.subject().variantId();
            line.set("target", target == null ? F.nullNode() : num(target));
            line.put("body", l.body());
            boolean packing = l.intent() == ChatIntent.BACKORDER && a.subject().backorderId() == null
                    && a.facts().stock() == null && a.facts().backorder() == null && a.facts().receivable() == null;
            if (packing) {
                line.putNull("facts");
            }
        }
        return o;
    }

    /** 골든 파일을 읽은 노드와 equals 로 비교한다 — int 범위면 IntNode 여야 같다. */
    private static JsonNode num(long n) {
        return n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE ? F.numberNode((int) n) : F.numberNode(n);
    }

    private static Long longOrNull(JsonNode n) {
        return n == null || n.isNull() ? null : n.asLong();
    }

    private static String textOrNull(JsonNode n) {
        return n == null || n.isNull() ? null : n.asString();
    }
}
