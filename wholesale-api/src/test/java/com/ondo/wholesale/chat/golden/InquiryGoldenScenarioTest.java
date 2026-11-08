package com.ondo.wholesale.chat.golden;

import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문의 자동 답 판정이 골든셋 132 시나리오 · 167 단계와 정확히 같은지 (MUL-156 틀, MUL-157 판정기).
 *
 * <p>웹 목({@code inquiry.ts})과 서버가 <b>같은 파일</b>로 같은 판정을 내는지 보는 게 목적이다.
 * 웹 점수 파일 기준은 전 단계 정확 일치(exact 167/167), 잘못된 자동 답 0 이다. 필수 P1~P5 만이 아니라
 * 전부 맞아야 한다. 파일이 온전한지는 {@link InquiryGoldenFileTest} 가 본다.
 *
 * <p>시나리오 하나가 컨테이너, 단계 하나가 테스트다. 실패 메시지에 시나리오 id · 단계 번호 · 본문이 찍힌다.
 */
class InquiryGoldenScenarioTest {

    /** 운영 판정기({@code InquiryDecider} → {@code InquiryLines})를 감싼 어댑터 (MUL-157). */
    private static final InquiryJudge JUDGE = new GoldenInquiryJudge();

    private final JsonNode golden = InquiryGolden.golden();

    @TestFactory
    Stream<DynamicNode> 시나리오마다_모든_단계가_기대_줄과_같다() {
        return stream(golden.get("scenarios")).map(scenario -> {
            String id = scenario.get("id").asString();
            List<JsonNode> steps = stream(scenario.get("steps")).toList();
            // 한 시나리오는 한 방에서 이어진다. 단계마다 따로 돌리지 않고 한 번 재생한 결과를 나눠 본다
            List<JsonNode> actual = new ArrayList<>();

            Stream<DynamicTest> tests = IntStream.range(0, steps.size()).mapToObj(i -> {
                JsonNode step = steps.get(i);
                String name = "%s #%d %s".formatted(id, i + 1, step.get("text").asString());
                return DynamicTest.dynamicTest(name, () -> {
                    if (actual.isEmpty()) {
                        List<JsonNode> played = JUDGE.play(golden, scenario);
                        assertThat(played).as("%s 단계 수", id).hasSize(steps.size());
                        actual.addAll(played);
                    }
                    assertThat(actual.get(i)).as(name).isEqualTo(step.get("expect"));
                });
            });
            return DynamicContainer.dynamicContainer(id + " " + scenario.get("title").asString(), tests);
        });
    }

    @TestFactory
    Stream<DynamicNode> 정규화_표의_모든_줄이_같은_판정이다() {
        return stream(golden.get("normalization")).map(row -> DynamicContainer.dynamicContainer(
                row.get("rule").asString(),
                stream(row.get("cases")).map(c -> DynamicTest.dynamicTest(c.get("text").asString(), () ->
                        assertThat(JUDGE.judgeNormalizationCase(golden, c))
                                .as(c.get("text").asString())
                                .isEqualTo(c.get("expect"))))));
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false);
    }
}
