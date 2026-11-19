package com.ondo.wholesale.chat.inquiry;

import com.ondo.wholesale.chat.domain.ChatGeneratedBy;
import com.ondo.wholesale.chat.domain.ChatHandoffReason;
import com.ondo.wholesale.chat.domain.ChatIntent;
import com.ondo.wholesale.chat.domain.ChatMessageKind;
import com.ondo.wholesale.chat.domain.ChatSender;
import com.ondo.wholesale.chat.domain.StockBand;
import com.ondo.wholesale.chat.dto.ChatAutoReply;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.BackorderClarify;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.Handoff;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.StockAnswer;
import com.ondo.wholesale.chat.inquiry.InquiryDecision.StockClarify;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Backorder;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Catalog;
import com.ondo.wholesale.chat.inquiry.InquiryInput.HistoryItem;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Listing;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Packing;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Receivable;
import com.ondo.wholesale.chat.inquiry.InquiryInput.RoomContext;
import com.ondo.wholesale.chat.inquiry.InquiryInput.Variant;
import com.ondo.wholesale.chat.repository.ChatMessageRepository.NewMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.ondo.wholesale.chat.domain.ChatIntent.BACKORDER;
import static com.ondo.wholesale.chat.domain.ChatIntent.OTHER;
import static com.ondo.wholesale.chat.domain.ChatIntent.RECEIVABLE;
import static com.ondo.wholesale.chat.domain.ChatIntent.STOCK;
import static org.assertj.core.api.Assertions.assertThat;

/** 손으로 만든 방에서 규칙과 LLM 읽기 사이의 선택, 조회 횟수, 줄 표기를 본다. 저장과 API 응답은 통합 테스트 몫이다. */
class LlmInquiryTest {

    private static final Instant NOW = Instant.parse("2026-11-18T04:00:00Z");
    private static final RoomContext CONTEXT = new RoomContext(20, NOW);
    private static final Variant RED_M = new Variant(31, "레드", "M", 10, 0, true);
    private static final Variant RED_L = new Variant(32, "레드", "L", 0, 5, true);
    private static final Listing LISTING = new Listing(20, 1, "빈티지 셔츠", true, List.of(RED_M, RED_L));
    private static final Backorder OPEN = new Backorder(41, 51, 1, 2, 31, "빈티지 셔츠", "레드", "M", 3,
            NOW, LocalDate.of(2026, 12, 1), null);
    private static final Receivable BALANCE = new Receivable(1, 2, 2500, 2500, 0, 0, 0, 0, true,
            NOW.atOffset(ZoneOffset.UTC));

    @Test
    void 읽기가_없으면_기존_판정을_그대로_한_번만_한다() {
        AtomicInteger reads = new AtomicInteger();
        InquiryInput input = input("지금 미수 얼마예요?", CONTEXT, List.of(), id -> LISTING,
                AutoReplySettings.DEFAULTS, () -> List.of(OPEN), () -> {
                    reads.incrementAndGet();
                    return List.of(BALANCE);
                }, List::of);
        var result = LlmInquiry.decide(input, null);
        assertThat(result.decision()).isEqualTo(InquiryDecider.decide(input(input.body())));
        assertThat(result.generatedBy()).isEqualTo(ChatGeneratedBy.RULE);
        assertThat(reads).hasValue(1);
    }

    @Test
    void 규칙이_답했는데_LLM_읽기가_다르면_다른_답을_내지_않고_사장님께_넘긴다() {
        InquiryInput stock = input("레드 M 몇 장 남았어요?");
        for (ChatIntent intent : ChatIntent.values()) {
            var result = LlmInquiry.decide(stock, reading(intent, "L", ""));
            assertThat(result.decision()).isEqualTo(intent == STOCK
                    ? new Handoff(ChatHandoffReason.NO_MATCH, STOCK)
                    : new Handoff(ChatHandoffReason.MULTI_INTENT, null));
        }
        InquiryInput balance = input("미수 얼마예요?");
        for (ChatIntent intent : ChatIntent.values()) {
            var result = LlmInquiry.decide(balance, reading(intent, "L", ""));
            assertThat(result.decision()).isEqualTo(intent == RECEIVABLE ? InquiryDecider.decide(balance)
                    : new Handoff(ChatHandoffReason.MULTI_INTENT, null));
        }
    }

    @ParameterizedTest
    @CsvSource({"사장님이랑 통화할게요,HUMAN_REQUEST", "이거 깎아 주세요,NEGOTIATION",
            "송금했어요,PAYMENT_REPORT", "재고랑 미수 얼마예요,MULTI_INTENT", "이번 달 미수 얼마예요,PERIOD"})
    void 안전상_넘긴_사유를_바꾸지_않는다(String body, ChatHandoffReason reason) {
        InquiryInput input = input(body);
        var rule = InquiryDecider.decide(input);
        assertThat(rule).isInstanceOfSatisfying(Handoff.class, h -> assertThat(h.reason()).isEqualTo(reason));
        assertThat(LlmInquiry.decide(input, reading(STOCK, "M", "")).decision()).isEqualTo(rule);
    }

    @Test
    void 놓친_재고_문의와_한글_사이즈_되묻기는_서버_구간으로_답한다() {
        for (String body : List.of("레드 M 구할 수 있을까요?", "레드 엠 사이즈 있어요?")) {
            var result = LlmInquiry.decide(input(body), reading(STOCK, "M", ""));
            assertThat(result.decision()).isInstanceOfSatisfying(StockAnswer.class, a -> {
                assertThat(a.variant()).isEqualTo(RED_M);
                assertThat(a.band()).isEqualTo(StockBand.UNDER_50);
            });
            assertThat(result.generatedBy()).isEqualTo(ChatGeneratedBy.LLM);
            assertThat(result.adoption()).isEqualTo(LlmInquiry.Adoption.ADOPTED);
        }
    }

    @Test
    void 가격이나_다른_상품_문의는_문맥_상품의_재고로_답하지_않는다() {
        for (String body : List.of("이 셔츠 얼마예요?", "이 셔츠 가격 좀요", "이 셔츠 단가 궁금해요")) {
            InquiryInput input = input(body);
            assertThat(LlmInquiry.decide(input, reading(STOCK, "M", "셔츠")).decision())
                    .isEqualTo(InquiryDecider.decide(input));
        }
        InquiryInput input = input("코튼 팬츠 레드 M 구할 수 있을까요?");
        assertThat(LlmInquiry.decide(input, reading(STOCK, "M", "코튼 팬츠")).decision())
                .isEqualTo(new Handoff(ChatHandoffReason.NO_INTENT, null));
    }

    @Test
    void 문맥과_소유자와_항목_설정은_기존_판정기가_확인한다() {
        var read = reading(STOCK, "M", "");
        var body = "레드 M 구해요";
        assertThat(LlmInquiry.decide(input(body, null, LISTING, AutoReplySettings.DEFAULTS), read).decision())
                .isEqualTo(new Handoff(ChatHandoffReason.NO_CONTEXT, STOCK));
        var foreign = new Listing(20, 99, LISTING.title(), true, LISTING.variants());
        assertThat(LlmInquiry.decide(input(body, CONTEXT, foreign, AutoReplySettings.DEFAULTS), read).decision())
                .isEqualTo(new Handoff(ChatHandoffReason.INVALID_TARGET, STOCK));
        assertThat(LlmInquiry.decide(input(body, CONTEXT, LISTING, new AutoReplySettings(true, false, true, true)),
                read).decision()).isEqualTo(new Handoff(ChatHandoffReason.OFF, STOCK));
    }

    @Test
    void 되묻기는_같은_유형의_답이나_보기_안의_사이즈만_채택한다() {
        InquiryInput first = input("재고 있어요?");
        var clarify = InquiryDecider.decide(first);
        assertThat(clarify).isInstanceOf(StockClarify.class);
        assertThat(LlmInquiry.decide(first, reading(BACKORDER, "NONE", "")).decision()).isEqualTo(clarify);
        var line = InquiryLines.generated(clarify, 1, NOW).getFirst();
        var history = List.of(new HistoryItem(2, ChatSender.WHOLESALER, ChatMessageKind.CLARIFY, NOW,
                ChatAutoReply.of(STOCK, 1L, line.autoReply()), null));
        InquiryInput followup = input("엠이요", null, history, id -> LISTING, AutoReplySettings.DEFAULTS,
                List::of, List::of, List::of);
        assertThat(LlmInquiry.decide(followup, reading(OTHER, "M", "")).decision())
                .isInstanceOfSatisfying(StockAnswer.class, a -> assertThat(a.variant()).isEqualTo(RED_M));
        assertThat(LlmInquiry.decide(followup, reading(OTHER, "XL", "")).decision())
                .isEqualTo(InquiryDecider.decide(followup));
    }

    @Test
    void 미송은_어느_주문인지_되묻고_배송이나_기간별_미수는_넘긴다() {
        var result = LlmInquiry.decide(input("주문한 거 언제쯤 들어와요?"), reading(BACKORDER, "NONE", ""));
        assertThat(result.decision()).isInstanceOf(BackorderClarify.class);
        assertThat(result.generatedBy()).isEqualTo(ChatGeneratedBy.LLM);
        assertThat(LlmInquiry.decide(input("택배는요?"), reading(BACKORDER, "NONE", "")).decision())
                .isEqualTo(new Handoff(ChatHandoffReason.NOT_SUPPORTED, BACKORDER));
        assertThat(LlmInquiry.decide(input("이번 달 결제할 금액 얼마예요?"), reading(RECEIVABLE, "NONE", "")).decision())
                .isEqualTo(new Handoff(ChatHandoffReason.PERIOD, RECEIVABLE));
        assertThat(LlmInquiry.decide(input("아직 결제할 금액이 얼마예요?"), reading(RECEIVABLE, "NONE", "")).decision())
                .isEqualTo(new Handoff(ChatHandoffReason.AMOUNT_UNSURE, RECEIVABLE));
    }

    @Test
    void 읽기를_붙인_판정에서_조회가_던지면_앞의_규칙_결과를_쓴다() {
        InquiryInput input = input("어떻게 되나요", null, List.of(), id -> null, AutoReplySettings.DEFAULTS,
                () -> { throw new IllegalStateException(); }, List::of, List::of);
        var result = LlmInquiry.decide(input, reading(BACKORDER, "NONE", ""));
        assertThat(result.decision()).isEqualTo(new Handoff(ChatHandoffReason.NO_INTENT, null));
        assertThat(result.adoption()).isEqualTo(LlmInquiry.Adoption.RULE_KEPT);
    }

    @Test
    void 두_판정이_같은_원본과_같은_조회_예외를_보고_조회는_한_번만_한다() {
        AtomicInteger backorders = new AtomicInteger();
        AtomicInteger packings = new AtomicInteger();
        AtomicInteger receivables = new AtomicInteger();
        Supplier<List<Backorder>> empty = () -> {
            backorders.incrementAndGet();
            return List.of();
        };
        Supplier<List<Packing>> brokenPacking = () -> {
            packings.incrementAndGet();
            throw new IllegalStateException();
        };
        Supplier<List<Receivable>> brokenBalance = () -> {
            receivables.incrementAndGet();
            throw new IllegalStateException();
        };
        var packing = input("미송", null, List.of(), id -> null, AutoReplySettings.DEFAULTS, empty, brokenBalance,
                brokenPacking);
        var balance = input("미수", null, List.of(), id -> null, AutoReplySettings.DEFAULTS, empty, brokenBalance,
                brokenPacking);
        assertThat(LlmInquiry.decide(packing, reading(BACKORDER, "NONE", "")).decision())
                .isEqualTo(new Handoff(ChatHandoffReason.NO_BACKORDER, BACKORDER));
        assertThat(LlmInquiry.decide(balance, reading(RECEIVABLE, "NONE", "")).decision())
                .isEqualTo(new Handoff(ChatHandoffReason.LOOKUP_FAILED, RECEIVABLE));
        assertThat(List.of(backorders.get(), packings.get(), receivables.get())).containsExactly(1, 1, 1);
    }

    @Test
    void 답과_되묻기의_생성_표시만_바꾸고_넘김_줄은_그대로_쓴다() {
        for (String body : List.of("레드 L 재고", "재고")) {
            var result = LlmInquiry.decide(input(body), reading(STOCK, "NONE", ""));
            assertThat(result.adoption()).isEqualTo(LlmInquiry.Adoption.SAME);
            assertThat(result.generatedBy()).isEqualTo(ChatGeneratedBy.LLM);
            var original = InquiryLines.generated(result.decision(), 1, NOW);
            assertThat(LlmInquiry.label(original, ChatGeneratedBy.RULE)).isSameAs(original);
            var labeled = LlmInquiry.label(original, result.generatedBy());
            assertThat(labeled).hasSameSizeAs(original);
            for (int i = 0; i < original.size(); i++) {
                NewMessage line = labeled.get(i);
                if (line.kind() == ChatMessageKind.HANDOFF) {
                    assertThat(line).isSameAs(original.get(i));
                } else {
                    assertThat(line.autoReply().generatedBy()).isEqualTo(ChatGeneratedBy.LLM);
                    assertThat(line).usingRecursiveComparison().ignoringFields("autoReply.generatedBy")
                            .isEqualTo(original.get(i));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"사장님이랑 통화할게요", "할인 부탁", "송금했어요", "전화 010-1234-5678",
            "계좌 １２３ ４５.６７", "연락 1234567"})
    void 안전_낱말과_긴_숫자는_보내지_않는다(String body) {
        assertThat(LlmInquiry.worthAsking(body, false)).isFalse();
    }

    @Test
    void 칩과_빈_글과_천_자를_넘는_글은_보내지_않는다() {
        assertThat(LlmInquiry.worthAsking("문의", true)).isFalse();
        assertThat(LlmInquiry.worthAsking(null, false)).isFalse();
        assertThat(LlmInquiry.worthAsking(" ", false)).isFalse();
        assertThat(LlmInquiry.worthAsking("가".repeat(1001), false)).isFalse();
        assertThat(LlmInquiry.worthAsking("😀".repeat(1000), false)).isTrue();
        assertThat(LlmInquiry.worthAsking("123456 이 셔츠 레드 엠 구해요", false)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"재고,STOCK", "미송,BACKORDER", "미수,RECEIVABLE"})
    void 서버가_붙이는_낱말을_규칙이_그_유형으로_읽는다(String word, ChatIntent intent) {
        assertThat(InquiryDecider.decide(input(word)).intent()).isEqualTo(intent);
    }

    /** 리뷰 결함 A: 열린 재고 되묻기가 없으면 OTHER 읽기의 사이즈를 붙여 재고 규칙을 새로 켜지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"엠 사이즈 기장 어떻게 되나요?", "엠으로 바꿔 주실 수 있나요?",
            "이 셔츠 엠 사이즈 값이 어떻게 되나요?", "레드 엠 기장 어떻게 되나요?"})
    void 열린_되묻기가_없으면_기타_읽기의_사이즈를_붙이지_않는다(String body) {
        InquiryInput input = input(body);
        var result = LlmInquiry.decide(input, reading(OTHER, "M", ""));
        assertThat(result.decision()).isEqualTo(InquiryDecider.decide(input));
        assertThat(result.generatedBy()).isEqualTo(ChatGeneratedBy.RULE);
    }

    @Test
    void 사이즈만_붙이는_갈래는_열린_재고_되묻기가_있을_때_채택한다() {
        var result = LlmInquiry.decide(openStockClarify("엠이요", LISTING), reading(OTHER, "M", ""));
        assertThat(result.decision()).isInstanceOfSatisfying(StockAnswer.class,
                a -> assertThat(a.variant()).isEqualTo(RED_M));
        assertThat(result.generatedBy()).isEqualTo(ChatGeneratedBy.LLM);
        var stock = LlmInquiry.decide(openStockClarify("엠이요", LISTING), reading(STOCK, "M", ""));
        assertThat(stock.decision()).isInstanceOf(StockAnswer.class);
    }

    /** 리뷰 결함 B: 상품 이름은 한 낱말만 겹쳐서는 같은 상품이 아니다. */
    @Test
    void 다른_상품_이름은_셔츠_한_낱말만_겹쳐도_문맥_상품으로_답하지_않는다() {
        InquiryInput input = input("린넨 셔츠 레드 M 구할 수 있을까요?");
        var result = LlmInquiry.decide(input, reading(STOCK, "M", "린넨 셔츠"));
        assertThat(result.decision()).isEqualTo(new Handoff(ChatHandoffReason.NO_INTENT, null));
        assertThat(result.generatedBy()).isEqualTo(ChatGeneratedBy.RULE);
    }

    @Test
    void 문맥_상품의_제목과_같은_이름만_채택한다() {
        String body = "빈티지 셔츠 레드 M 구할 수 있을까요?";
        var result = LlmInquiry.decide(input(body), validated(body, "STOCK", "M", "M", "빈티지 셔츠"));
        assertThat(result.decision()).isInstanceOf(StockAnswer.class);
        assertThat(result.generatedBy()).isEqualTo(ChatGeneratedBy.LLM);
        // 제목의 앞부분만 적은 이름은 버린다
        assertRuleResult(input(body), validated(body, "STOCK", "M", "M", "빈티지"));
    }

    /** 리뷰 결함 E: 열린 되묻기의 상품과 지금 문맥 상품이 다르면(대조를 지나는 입력) 규칙 결과를 쓴다. */
    @Test
    void 열린_되묻기의_상품과_방_문맥_상품이_다르면_규칙_결과를_쓴다() {
        var blueM = new Variant(41, "블루", "M", 10, 0, true);
        var listingA = new Listing(20, 1, "빈티지 셔츠", true, List.of(RED_M, blueM));
        var listingB = new Listing(21, 1, "스트라이프 셔츠", true,
                List.of(new Variant(51, "레드", "M", 10, 0, true)));
        InquiryInput first = input("재고 있어요?", CONTEXT, listingA, AutoReplySettings.DEFAULTS);
        var line = InquiryLines.generated(InquiryDecider.decide(first), 1, NOW).getFirst();
        var history = List.of(new HistoryItem(2, ChatSender.WHOLESALER, ChatMessageKind.CLARIFY, NOW,
                ChatAutoReply.of(STOCK, 1L, line.autoReply()), null));
        InquiryInput followup = input("엠이요", new RoomContext(21, NOW), history,
                id -> id == 21 ? listingB : listingA, AutoReplySettings.DEFAULTS, List::of, List::of, List::of);
        var result = LlmInquiry.decide(followup, reading(STOCK, "M", ""));
        assertThat(result.decision()).isEqualTo(InquiryDecider.decide(followup));
        assertThat(result.generatedBy()).isEqualTo(ChatGeneratedBy.RULE);
    }

    /** 아래 새 테스트는 InquiryReading을 직접 만들지 않고 validate를 거친 읽기로 decide까지 간다(실제 경로). */
    private static InquiryReading validated(String body, String intent, String size, String evidence,
                                            String productName) {
        return InquiryReading.validate(body,
                new InquiryReading.Fields(intent, false, size, evidence, "", productName, "", false));
    }

    private static void assertRuleResult(InquiryInput input, InquiryReading reading) {
        var result = LlmInquiry.decide(input, reading);
        assertThat(result.decision()).isEqualTo(InquiryDecider.decide(input));
        assertThat(result.generatedBy()).isEqualTo(ChatGeneratedBy.RULE);
    }

    /** 규칙 R3 / 지적 1: 열린 재고 되묻기 중이어도 긴 문장에는 사이즈만 붙이지 않는다. */
    @Test
    void 열린_되묻기_중에도_사이즈_말고_다른_질문이_섞이면_규칙_결과를_쓴다() {
        String body = "엠 사이즈 기장 어떻게 되나요?";
        assertRuleResult(openStockClarify(body, LISTING), validated(body, "OTHER", "M", "엠", ""));
    }

    @ParameterizedTest
    @CsvSource({"엠이요,M,엠,31", "라지로 주세요,L,라지,32", "엠 사이즈요,M,엠,31", "엠이요?,M,엠,31", "엠이요~,M,엠,31",
            "엠 사이즈요.,M,엠,31"})
    void 열린_되묻기에_짧게_한글_사이즈만_답하면_validate를_거친_읽기가_채택된다(String body, String size, String evidence,
                                                         long variantId) {
        var reading = validated(body, "OTHER", size, evidence, "");
        assertThat(reading).isNotNull();
        var result = LlmInquiry.decide(openStockClarify(body, LISTING), reading);
        assertThat(result.decision()).isInstanceOfSatisfying(StockAnswer.class,
                a -> assertThat(a.variant().variantId()).isEqualTo(variantId));
        assertThat(result.generatedBy()).isEqualTo(ChatGeneratedBy.LLM);
        assertThat(result.adoption()).isEqualTo(LlmInquiry.Adoption.ADOPTED);
    }

    /** 규칙도 영문 M을 읽어 같은 답을 내므로 LLM 보완이 아니라 SAME이다. */
    @Test
    void 영문_M이요는_규칙이_이미_같은_답을_낸다() {
        var reading = validated("M이요", "OTHER", "M", "M", "");
        assertThat(reading).isNotNull();
        var result = LlmInquiry.decide(openStockClarify("M이요", LISTING), reading);
        assertThat(result.decision()).isInstanceOfSatisfying(StockAnswer.class,
                a -> assertThat(a.variant()).isEqualTo(RED_M));
        assertThat(result.adoption()).isEqualTo(LlmInquiry.Adoption.SAME);
    }

    /** 지적 1: 읽은 사이즈와 답 옵션의 사이즈가 다르면 채택하지 않는다. */
    @Test
    void 읽은_사이즈와_다른_옵션으로_답이_나오면_규칙_결과를_쓴다() {
        var onlyL = new Listing(20, 1, "빈티지 셔츠", true, List.of(RED_L));
        String body = "레드 엠 구할 수 있을까요?";
        assertRuleResult(input(body, CONTEXT, onlyL, AutoReplySettings.DEFAULTS),
                validated(body, "STOCK", "M", "엠", ""));
    }

    /** 지적 1 같은 원리: 열린 되묻기에서 읽은 사이즈와 다른 옵션으로 답이 나오면 채택하지 않는다. */
    @Test
    void 열린_되묻기_중_읽은_사이즈와_다른_옵션_답은_채택하지_않는다() {
        String body = "엠이요";
        var result = LlmInquiry.decide(openStockClarify(body, LISTING), validated(body, "OTHER", "L", "엠", ""));
        // 근거 엠은 L을 가리키지 않아 validate가 버린다. 버린 읽기는 규칙 결과가 된다
        assertThat(result.generatedBy()).isEqualTo(ChatGeneratedBy.RULE);
    }

    /** 지적 2: 문장부호가 낀 질문은 짧은 답이 아니다. */
    @Test
    void 열린_되묻기_중_문장부호가_낀_질문에는_사이즈를_붙이지_않는다() {
        String body = "엠 = 사이즈?";
        assertRuleResult(openStockClarify(body, LISTING), validated(body, "OTHER", "M", "엠", ""));
    }

    @Test
    void 상품명이_비어_있으면_기존대로_채택한다() {
        String body = "레드 엠 사이즈 있어요?";
        var result = LlmInquiry.decide(input(body), validated(body, "STOCK", "M", "엠", ""));
        assertThat(result.decision()).isInstanceOfSatisfying(StockAnswer.class,
                a -> assertThat(a.variant()).isEqualTo(RED_M));
        assertThat(result.generatedBy()).isEqualTo(ChatGeneratedBy.LLM);
    }

    @Test
    void 상품명이_문맥_제목과_같고_본문에_그대로_있으면_채택한다() {
        String body = "빈티지 셔츠 레드 M 구할 수 있을까요?";
        var result = LlmInquiry.decide(input(body), validated(body, "STOCK", "M", "M", "빈티지 셔츠"));
        assertThat(result.decision()).isInstanceOf(StockAnswer.class);
        assertThat(result.generatedBy()).isEqualTo(ChatGeneratedBy.LLM);
    }

    /** 규칙 R1 / 지적 2: 제목 일부만 겹치는 상품명은 읽기를 버린다. */
    @Test
    void 상품명이_문맥_제목의_일부만_겹치면_읽기를_버린다() {
        String body = "린넨 셔츠 레드 M 구할 수 있을까요?";
        assertRuleResult(input(body), validated(body, "STOCK", "M", "M", "셔츠"));
        var linenLike = new Listing(20, 1, "린넨풍 셔츠", true, List.of(RED_M, RED_L));
        assertRuleResult(input(body, CONTEXT, linenLike, AutoReplySettings.DEFAULTS),
                validated(body, "STOCK", "M", "M", "린넨 셔츠"));
    }

    /** 규칙 R1 / 지적 3: 상품명 글자가 본문에 그대로(문장부호 포함) 이어져 있지 않으면 버린다. */
    @Test
    void 상품명_낱말이_본문에_흩어져_있으면_읽기를_버린다() {
        String body = "빈티지. 셔츠 말고 린넨 상의 레드 M 구할 수 있을까요?";
        assertRuleResult(input(body), validated(body, "STOCK", "M", "M", "빈티지 셔츠"));
    }

    /** 규칙 R2 / 지적 4: "프리-미엄"의 프리는 사이즈 근거가 아니다. */
    @Test
    void 프리미엄의_프리를_사이즈로_읽은_읽기는_버린다() {
        var free = new Variant(61, "레드", "FREE", 10, 0, true);
        var listing = new Listing(20, 1, "빈티지 셔츠", true, List.of(RED_M, free));
        String body = "프리-미엄 레드 구할 수 있을까요?";
        assertRuleResult(input(body, CONTEXT, listing, AutoReplySettings.DEFAULTS),
                validated(body, "STOCK", "FREE", "프리", ""));
    }

    /** 시드 2001 모양: 색이 둘(레드 S/M/L, 네이비 S/M). 색 하나짜리 상품은 "하나뿐인 칸은 읽은 것으로 친다" 예외로 통과해 버그를 못 잡는다. */
    private static final Variant TWO_RED_S = new Variant(71, "레드", "S", 10, 0, true);
    private static final Variant TWO_RED_M = new Variant(72, "레드", "M", 10, 0, true);
    private static final Variant TWO_RED_L = new Variant(73, "레드", "L", -15, 0, true);
    private static final Variant TWO_NAVY_S = new Variant(74, "네이비", "S", 10, 0, true);
    private static final Variant TWO_NAVY_M = new Variant(75, "네이비", "M", 10, 0, true);
    private static final Listing TWO_COLORS = new Listing(20, 1, "빈티지 셔츠", true,
            List.of(TWO_RED_S, TWO_RED_M, TWO_RED_L, TWO_NAVY_S, TWO_NAVY_M));

    @Test
    void 색이_둘인_상품에서_열린_되묻기에_사이즈가_한_옵션뿐이면_그_옵션으로_답한다() {
        String body = "라지로 주세요";
        var result = LlmInquiry.decide(openStockClarify(body, TWO_COLORS), validated(body, "OTHER", "L", "라지", ""));
        assertThat(result.decision()).isInstanceOfSatisfying(StockAnswer.class,
                a -> assertThat(a.variant()).isEqualTo(TWO_RED_L));
        assertThat(result.generatedBy()).isEqualTo(ChatGeneratedBy.LLM);
        assertThat(result.adoption()).isEqualTo(LlmInquiry.Adoption.ADOPTED);
    }

    @ParameterizedTest
    @CsvSource({"엠이요,M,엠", "S요,S,S"})
    void 색이_둘인_상품에서_사이즈가_두_옵션이면_규칙_결과를_쓴다(String body, String size, String evidence) {
        InquiryInput input = openStockClarify(body, TWO_COLORS);
        assertRuleResult(input, validated(body, "OTHER", size, evidence, ""));
        assertThat(InquiryDecider.decide(input)).isEqualTo(new Handoff(ChatHandoffReason.NO_INTENT, null));
    }

    @Test
    void 색이_둘인_상품에서도_짧은_답이_아니면_사이즈를_붙이지_않는다() {
        String body = "엘 사이즈 기장 어떻게 되나요?";
        assertRuleResult(openStockClarify(body, TWO_COLORS), validated(body, "OTHER", "L", "엘", ""));
    }

    @Test
    void 색이_둘인_상품에서_그_사이즈_옵션이_비공개면_재고_답이_아니다() {
        var hidden = new Variant(73, "레드", "L", -15, 0, false);
        var listing = new Listing(20, 1, "빈티지 셔츠", true,
                List.of(TWO_RED_S, TWO_RED_M, hidden, TWO_NAVY_S, TWO_NAVY_M));
        String body = "라지로 주세요";
        var result = LlmInquiry.decide(openStockClarify(body, listing), validated(body, "OTHER", "L", "라지", ""));
        assertThat(result.decision()).isNotInstanceOf(StockAnswer.class);
    }

    /** 현재 동작을 고정한다. 규칙이 읽지 못하는 한글 사이즈를 STOCK 읽기가 어떻게 보완하는지. */
    @Test
    void 현재_동작_색이_둘인_상품에서_STOCK_읽기_라지_남았어요() {
        String body = "라지 남았어요?";
        InquiryInput input = input(body, CONTEXT, TWO_COLORS, AutoReplySettings.DEFAULTS);
        var result = LlmInquiry.decide(input, validated(body, "STOCK", "L", "라지", ""));
        assertThat(result.decision()).isInstanceOf(StockClarify.class).isEqualTo(result.rule());
        assertThat(result.generatedBy()).isEqualTo(ChatGeneratedBy.LLM);
        assertThat(result.adoption()).isEqualTo(LlmInquiry.Adoption.SAME);
    }

    /** 보기에 있던 옵션이 지금 카탈로그에서 안 보이면 "L이 하나뿐"이라 단정하지 않는다. */
    @Test
    void 보기의_옵션이_카탈로그에서_빠져_있으면_칩_없이_규칙_결과를_쓴다() {
        var navyL = new Variant(76, "네이비", "L", 10, 0, true);
        var full = new Listing(20, 1, "빈티지 셔츠", true,
                List.of(TWO_RED_S, TWO_RED_M, TWO_RED_L, TWO_NAVY_S, TWO_NAVY_M, navyL));
        var withoutNavyL = new Listing(20, 1, "빈티지 셔츠", true,
                List.of(TWO_RED_S, TWO_RED_M, TWO_RED_L, TWO_NAVY_S, TWO_NAVY_M));
        String body = "라지로 주세요";
        var opened = openStockClarify(body, full);
        var reading = validated(body, "OTHER", "L", "라지", "");
        // 대조: 네이비 L을 빼지 않으면 L이 둘이라 규칙 결과
        assertRuleResult(opened, reading);
        var narrowed = new InquiryInput(opened.body(), opened.inquiry(), opened.now(), opened.today(),
                opened.wholesalerId(), opened.retailerId(), opened.settings(), opened.context(), opened.history(),
                id -> withoutNavyL, opened.backorders(), opened.receivables(), opened.packings());
        assertRuleResult(narrowed, reading);
        assertThat(InquiryDecider.decide(narrowed)).isEqualTo(new Handoff(ChatHandoffReason.NO_INTENT, null));
    }

    @Test
    void 규칙_답과_읽은_유형이_다르면_종류_의견_다름으로_넘긴다() {
        String body = "레드 L 단가가 어떻게 되나요";
        var result = LlmInquiry.decide(input(body), validated(body, "OTHER", "L", "L", ""));
        assertThat(result.decision()).isEqualTo(new Handoff(ChatHandoffReason.MULTI_INTENT, null));
        assertThat(result.adoption()).isEqualTo(LlmInquiry.Adoption.VETOED_INTENT);
    }

    @Test
    void 규칙_답과_읽은_상품이_다르면_상품_의견_다름으로_넘긴다() {
        String body = "린넨 셔츠 레드 M 있어요?";
        var result = LlmInquiry.decide(input(body), validated(body, "STOCK", "M", "M", "린넨 셔츠"));
        assertThat(result.decision()).isEqualTo(new Handoff(ChatHandoffReason.NO_MATCH, STOCK));
        assertThat(result.adoption()).isEqualTo(LlmInquiry.Adoption.VETOED_PRODUCT);
    }

    @Test
    void 읽은_상품_낱말이_모두_제목에_있거나_읽기가_없거나_되묻기_답이면_규칙_답을_유지한다() {
        String body = "이 셔츠 레드 M 몇 장 남았어요?";
        InquiryInput input = input(body);
        var same = LlmInquiry.decide(input, validated(body, "STOCK", "M", "M", "셔츠"));
        assertThat(same.decision()).isEqualTo(InquiryDecider.decide(input)).isInstanceOf(StockAnswer.class);
        assertThat(same.adoption()).isNotIn(LlmInquiry.Adoption.VETOED_INTENT, LlmInquiry.Adoption.VETOED_PRODUCT,
                LlmInquiry.Adoption.VETOED_SIZE);
        var none = LlmInquiry.decide(input, null);
        assertThat(none.decision()).isEqualTo(InquiryDecider.decide(input)).isInstanceOf(StockAnswer.class);

        String reply = "레드 M이요";
        var open = LlmInquiry.decide(openStockClarify(reply, LISTING), validated(reply, "OTHER", "M", "M", ""));
        assertThat(open.decision()).isInstanceOfSatisfying(StockAnswer.class,
                a -> assertThat(a.variant()).isEqualTo(RED_M));
    }

    /** 열린 되묻기의 종류 검사 예외는 "보기 선택 응답"(색 하나, 사이즈, 조사, 허용 낱말, 끝의 ?!~.)에만 적용한다. */
    @ParameterizedTest
    @CsvSource({"레드 M이요,M,M", "네이비 S요,S,S"})
    void 열린_되묻기에_색과_사이즈로_보기를_고르면_답을_유지한다(String body, String size, String evidence) {
        var result = LlmInquiry.decide(openStockClarify(body, TWO_COLORS), validated(body, "OTHER", size, evidence, ""));
        assertThat(result.decision()).isInstanceOf(StockAnswer.class);
        assertThat(result.adoption()).isNotEqualTo(LlmInquiry.Adoption.VETOED_INTENT);
    }

    @ParameterizedTest
    @CsvSource({"레드 L 단가가 어떻게 되나요,L,L", "레드 M 기장 어떻게 되나요?,M,M", "레드 M 가격은요?,M,M",
            "네이비 L 레드요,L,L"})
    void 열린_되묻기_중에도_보기_선택이_아닌_질문은_종류_의견_다름으로_넘긴다(String body, String size, String evidence) {
        var result = LlmInquiry.decide(openStockClarify(body, TWO_COLORS), validated(body, "OTHER", size, evidence, ""));
        assertThat(result.decision()).isEqualTo(new Handoff(ChatHandoffReason.MULTI_INTENT, null));
        assertThat(result.adoption()).isEqualTo(LlmInquiry.Adoption.VETOED_INTENT);
    }

    /** 현재 동작을 고정한다. 색과 한글 사이즈가 붙은 "레드 엠이요"는 보기 선택 응답으로 못 알아본다. 놓침이지만 안전한 쪽이다. */
    @Test
    void 현재_동작_레드_엠이요는_규칙_결과로_넘긴다() {
        String body = "레드 엠이요";
        InquiryInput input = openStockClarify(body, TWO_COLORS);
        var result = LlmInquiry.decide(input, validated(body, "OTHER", "M", "엠", ""));
        assertThat(result.decision()).isEqualTo(InquiryDecider.decide(input))
                .isEqualTo(new Handoff(ChatHandoffReason.NO_INTENT, null));
        assertThat(result.generatedBy()).isEqualTo(ChatGeneratedBy.RULE);
        assertThat(result.adoption()).isEqualTo(LlmInquiry.Adoption.RULE_KEPT);
    }

    private static InquiryInput openStockClarify(String body, Listing listing) {
        var first = input("재고 있어요?", CONTEXT, listing, AutoReplySettings.DEFAULTS);
        var line = InquiryLines.generated(InquiryDecider.decide(first), 1, NOW).getFirst();
        var history = List.of(new HistoryItem(2, ChatSender.WHOLESALER, ChatMessageKind.CLARIFY, NOW,
                ChatAutoReply.of(STOCK, 1L, line.autoReply()), null));
        return input(body, CONTEXT, history, id -> listing, AutoReplySettings.DEFAULTS, List::of, List::of,
                List::of);
    }

    private static InquiryReading reading(ChatIntent intent, String size, String product) {
        return new InquiryReading(intent, size, product, false, false);
    }

    private static InquiryInput input(String body) {
        return input(body, CONTEXT, LISTING, AutoReplySettings.DEFAULTS);
    }

    private static InquiryInput input(String body, RoomContext context, Listing listing, AutoReplySettings settings) {
        return input(body, context, List.of(), id -> listing, settings, () -> List.of(OPEN), () -> List.of(BALANCE),
                List::of);
    }

    private static InquiryInput input(String body, RoomContext context, List<HistoryItem> history, Catalog catalog,
                                      AutoReplySettings settings, Supplier<List<Backorder>> backorders,
                                      Supplier<List<Receivable>> receivables, Supplier<List<Packing>> packings) {
        return new InquiryInput(body, null, NOW, LocalDate.of(2026, 11, 18), 1, 2, settings, context, history, catalog,
                backorders, receivables, packings);
    }
}
