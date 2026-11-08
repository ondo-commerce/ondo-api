package com.ondo.wholesale.chat.golden;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 골든셋 파일 자체가 온전한지 (MUL-156). 스프링·DB 없이 돈다.
 *
 * <p>판정 결과가 기대와 같은지는 {@link InquiryGoldenScenarioTest} 가 본다(MUL-157 전까지 꺼져 있다).
 * 여기는 복사가 잘렸거나 다른 버전을 가져온 사고만 잡는다 — 그러면 판정 테스트가 켜져도
 * 시나리오 일부만 돌고 초록이 된다.
 */
class InquiryGoldenFileTest {

    private final JsonNode golden = InquiryGolden.golden();
    private final JsonNode score = InquiryGolden.score();

    @Test
    void 골든셋_v7은_시나리오_132개_단계_167개다() {
        List<JsonNode> scenarios = list(golden.get("scenarios"));
        int steps = scenarios.stream().mapToInt(s -> s.get("steps").size()).sum();

        assertThat(golden.get("version").asInt()).isEqualTo(7);
        assertThat(scenarios).hasSize(132);
        assertThat(steps).isEqualTo(167);
    }

    @Test
    void 개수가_점수_파일과_같다() {
        List<JsonNode> scenarios = list(golden.get("scenarios"));
        long retailSteps = scenarios.stream()
                .flatMap(s -> list(s.get("steps")).stream())
                .filter(step -> !step.has("sender") || "RETAILER".equals(step.get("sender").asString()))
                .count();

        assertThat(score.get("version").asInt()).isEqualTo(golden.get("version").asInt());
        assertThat(score.get("scenarios").asInt()).isEqualTo(scenarios.size());
        assertThat(score.get("steps").asInt())
                .isEqualTo(scenarios.stream().mapToInt(s -> s.get("steps").size()).sum());
        assertThat(score.get("retailSteps").asInt()).isEqualTo((int) retailSteps);
    }

    @Test
    void 시나리오_id는_겹치지_않고_필수는_P1부터_P5다() {
        List<JsonNode> scenarios = list(golden.get("scenarios"));
        Set<String> ids = new HashSet<>();
        scenarios.forEach(s -> ids.add(s.get("id").asString()));

        List<String> required = scenarios.stream()
                .filter(s -> s.path("required").asBoolean(false))
                .map(s -> s.get("id").asString())
                .toList();

        assertThat(ids).hasSize(scenarios.size());
        assertThat(required).containsExactly("P1", "P2", "P3", "P4", "P5");
    }

    @Test
    void 모든_단계는_본문과_세_투영_중_하나의_기대를_가진다() {
        for (JsonNode scenario : golden.get("scenarios")) {
            for (JsonNode step : scenario.get("steps")) {
                JsonNode expect = step.get("expect");
                boolean known = expect.has("lines") || expect.has("openHandoff")
                        || expect.has("kind") || expect.has("kinds");

                assertThat(step.get("text").isString())
                        .as("%s 본문", scenario.get("id").asString()).isTrue();
                assertThat(known)
                        .as("%s 기대 모양 %s", scenario.get("id").asString(), expect).isTrue();
            }
        }
    }

    @Test
    void 정규화_표는_6행이다() {
        JsonNode rows = golden.get("normalization");

        assertThat(rows.size()).isEqualTo(6);
        for (JsonNode row : rows) {
            assertThat(row.get("cases").size()).as(row.get("rule").asString()).isPositive();
        }
    }

    private static List<JsonNode> list(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).toList();
    }
}
