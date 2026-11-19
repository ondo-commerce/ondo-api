package com.ondo.wholesale.chat.golden;

import com.ondo.wholesale.chat.domain.ChatHandoffReason;
import com.ondo.wholesale.chat.domain.ChatIntent;
import com.ondo.wholesale.chat.inquiry.InquiryDecider;
import com.ondo.wholesale.chat.inquiry.InquiryDecision;
import com.ondo.wholesale.chat.inquiry.InquiryInput;
import com.ondo.wholesale.chat.inquiry.InquiryReading;
import com.ondo.wholesale.chat.inquiry.LlmInquiry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 골든 입력을 가짜 분류값으로 재생해 확정된 답과 안전 넘김을 지키는지 본다. 정답 자체는 InquiryGoldenScenarioTest가 본다. */
class InquiryGoldenLlmPathTest {

    private static final List<InquiryReading> READINGS = readings();
    private final JsonNode golden = InquiryGolden.golden();
    private final InquiryJudge judge = new GoldenInquiryJudge(this::checkReadings);

    @Test
    void 연속된_문의의_이력을_규칙대로_쌓으며_모든_단계를_확인한다() {
        int steps = 0;
        for (JsonNode scenario : golden.get("scenarios")) {
            steps += judge.play(golden, scenario).size();
        }
        assertThat(steps).isEqualTo(167); // 도매 사장 글 한 건은 분류하지 않고 이력만 쌓는다.
    }

    @Test
    void 정규화_17건과_그_앞의_되묻기에도_같은_규칙을_지킨다() {
        int cases = 0;
        for (JsonNode row : golden.get("normalization")) {
            for (JsonNode item : row.get("cases")) {
                judge.judgeNormalizationCase(golden, item);
                cases++;
            }
        }
        assertThat(cases).isEqualTo(17);
    }

    private InquiryDecision checkReadings(InquiryInput input) {
        InquiryInput turn = LlmInquiry.withCachedSources(input);
        InquiryDecision rule = InquiryDecider.decide(turn);
        boolean protectedDecision = switch (rule) {
            case InquiryDecision.Handoff h -> h.reason() != ChatHandoffReason.NO_INTENT
                    && h.reason() != ChatHandoffReason.CLARIFY_AGAIN;
            case InquiryDecision.StockClarify c -> false;
            case InquiryDecision.BackorderClarify c -> false;
            default -> false;
        };
        for (InquiryReading reading : READINGS) {
            var result = LlmInquiry.decide(turn, reading);
            assertThat(result.rule()).as("같은 턴의 규칙 판정").isEqualTo(rule);
            if (protectedDecision || input.inquiry() != null) {
                assertThat(result.decision()).as("골든 문장 %s, 가짜 읽기 %s", input.body(), reading).isEqualTo(rule);
            } else if (isAnswer(rule)) {
                // 규칙이 답했으면 최종은 규칙 그대로이거나 넘김(종류 다름 MULTI_INTENT, 상품·사이즈 다름 NO_MATCH)이다
                assertThat(List.of(rule, new InquiryDecision.Handoff(ChatHandoffReason.MULTI_INTENT, null),
                        new InquiryDecision.Handoff(ChatHandoffReason.NO_MATCH, rule.intent())))
                        .as("골든 문장 %s, 가짜 읽기 %s", input.body(), reading).contains(result.decision());
                if (reading.intent() == rule.intent() && reading.size().equals(InquiryReading.NO_SIZE)
                        && reading.productName().isEmpty()) {
                    assertThat(result.decision()).as("읽기가 일치하면 규칙 그대로").isEqualTo(rule);
                }
            }
        }
        return rule;
    }

    private static boolean isAnswer(InquiryDecision rule) {
        return !(rule instanceof InquiryDecision.Handoff || rule instanceof InquiryDecision.StockClarify
                || rule instanceof InquiryDecision.BackorderClarify);
    }

    private static List<InquiryReading> readings() {
        var readings = new ArrayList<InquiryReading>();
        for (ChatIntent intent : ChatIntent.values()) {
            for (String size : List.of("XS", "S", "M", "L", "XL", "2XL", "FREE", InquiryReading.NO_SIZE)) {
                for (String product : List.of("", "다른 상품")) {
                    for (boolean total : List.of(false, true)) {
                        readings.add(new InquiryReading(intent, size, product, false, total));
                    }
                }
            }
        }
        return List.copyOf(readings);
    }
}
