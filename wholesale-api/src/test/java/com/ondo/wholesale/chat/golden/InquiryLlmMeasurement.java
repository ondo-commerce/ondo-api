package com.ondo.wholesale.chat.golden;

import com.ondo.wholesale.chat.domain.ChatHandoffReason;
import com.ondo.wholesale.chat.domain.ChatIntent;
import com.ondo.wholesale.chat.inquiry.InquiryDecider;
import com.ondo.wholesale.chat.inquiry.InquiryDecision;
import com.ondo.wholesale.chat.inquiry.InquiryInput;
import com.ondo.wholesale.chat.inquiry.InquiryReading;
import com.ondo.wholesale.chat.inquiry.LlmInquiry;
import com.ondo.wholesale.chat.inquiry.llm.AnthropicInquiryReader;
import com.ondo.wholesale.chat.inquiry.llm.InquiryReader;
import com.ondo.wholesale.chat.inquiry.llm.InquiryReader.Kind;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 실제 Anthropic API를 부르는 옵트인 측정. 과금된다.
 *
 * <p>{@code ONDO_LLM_MEASURE}(smoke, latency, golden)와 {@code ONDO_LLM_API_KEY}가 둘 다 있을 때만 돈다. 기존 {@code -Dmeasure=true}와
 * 다른 스위치라 그 명령으로는 과금이 일어나지 않는다. 환경변수는 그레이들 입력이 아니라 같은 명령을 다시 돌리면 최신이라고 건너뛰므로
 * {@code --rerun}을 붙인다. 결과는 {@code build/llm-measure/}에 쓴다. 측정값으로 실패시키지 않고, 첫 호출이 오류이거나
 * golden의 예정 호출 수가 상한(600)을 넘을 때만 실패한다. 예정 호출 수는 golden 결과 파일 첫 줄에도 적는다.
 *
 * <pre>
 *   ONDO_LLM_MEASURE=latency ONDO_LLM_API_KEY="..." ./gradlew test --tests '*InquiryLlmMeasurement*' --rerun
 *   ONDO_LLM_MEASURE=golden ONDO_LLM_API_KEY="..." ./gradlew test --tests '*InquiryLlmMeasurement.golden' --rerun -i
 * </pre>
 */
class InquiryLlmMeasurement {

    private static final String MODE = System.getenv("ONDO_LLM_MEASURE");
    private static final String MODEL = System.getenv().getOrDefault("ONDO_LLM_MODEL", "claude-haiku-5-5");
    /** 운영 타임아웃(1500ms)으로 자르면 그 뒤 분포가 안 보여서 측정은 넉넉히 기다리고 초과 수만 따로 센다. */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final long PROD_TIMEOUT_MS = 1500;
    private static final int SMOKE_CAP = 1;
    private static final int SENTENCES = 30;
    /** 데우기 1회 + 문장 30회. */
    private static final int LATENCY_CAP = SENTENCES + 1;
    private static final String SMOKE_BODY = "레드 엠 사이즈 있어요?";
    /** Haiku 5.5 달러 / 100만 토큰. 다른 모델로 돌리면 이 어림은 맞지 않는다. */
    private static final double INPUT_USD = 0.10;
    private static final double OUTPUT_USD = 0.50;
    private static final int GOLDEN_CAP = 600;
    private static final String PROBES = "/llm/probes.json";
    private static final Set<String> CONTEXTS = Set.of("default", "open_stock_clarify");
    private static final Set<String> EXPECTS = Set.of("HANDOFF", "NO_STOCK_ANSWER", "ANSWER_OK");
    private static final Set<ChatHandoffReason> OTHER_REASONS = Set.of(ChatHandoffReason.HUMAN_REQUEST,
            ChatHandoffReason.NEGOTIATION, ChatHandoffReason.MULTI_INTENT);

    private int callsLeft;

    @Test
    void smoke() throws IOException {
        String key = keyFor("smoke");
        callsLeft = SMOKE_CAP;
        String stamp = now();
        try (AnthropicInquiryReader reader = new AnthropicInquiryReader(key, MODEL, TIMEOUT)) {
            Call call = call(reader, "smoke", SMOKE_BODY);
            InquiryReader.Result r = call.result();
            String report = """
                    # LLM smoke %s

                    - 모델: %s, 측정 타임아웃: %dms
                    - 결과: %s
                    - 걸린 시간: %dms (첫 호출이라 클라이언트 생성 포함)
                    - 토큰: 입력 %d, 출력 %d
                    - stop reason(결과 종류로 거꾸로 적음): %s
                    - 오류: %s
                    - 읽은 칸: %s
                    """.formatted(stamp, MODEL, TIMEOUT.toMillis(), r.kind(), call.ms(), r.inputTokens(),
                    r.outputTokens(), stopReason(r.kind()), dash(r.error()), r.fields() == null ? "-" : r.fields());
            write("smoke-" + stamp, report);
            failIfError(call);
        }
    }

    @Test
    void latency() throws IOException {
        String key = keyFor("latency");
        List<Map.Entry<String, String>> picks = picks();
        callsLeft = LATENCY_CAP;
        String stamp = now();
        try (AnthropicInquiryReader reader = new AnthropicInquiryReader(key, MODEL, TIMEOUT)) {
            Call warm = call(reader, "데우기", SMOKE_BODY);
            List<Call> runs = new ArrayList<>();
            if (warm.result().kind() != Kind.ERROR) {
                for (Map.Entry<String, String> pick : picks) {
                    runs.add(call(reader, pick.getKey(), pick.getValue()));
                }
            }
            write("latency-" + stamp, latencyReport(stamp, warm, runs));
            failIfError(warm);
        }
    }

    /**
     * 정답셋 시나리오(대상 A)와 probes.json(대상 B)을 운영 보내기와 같은 순서로 판정한다: 거름 → 읽기 → 검증 → 미수 풀기 끔 →
     * {@link LlmInquiry#decide}. 품질을 재려고 운영 타임아웃으로 자르지 않는다. 1500ms 초과는 따로 센다.
     */
    @Test
    void golden() throws IOException {
        String key = keyFor("golden");
        JsonNode golden = InquiryGolden.golden();
        List<JsonNode> probes = probes();
        int a = callsA(golden);
        int b = callsB(probes);
        int planned = 1 + a + b;
        String plan = "예정 호출 %d회 = 데우기 1 + 대상 A %d + 대상 B %d (상한 %d)".formatted(planned, a, b, GOLDEN_CAP);
        System.out.println(plan);
        assertThat(planned).as("호출 상한").isLessThanOrEqualTo(GOLDEN_CAP);
        // 예정 수가 정확하니 그만큼만 허락한다. 더 부르면 셈이 틀린 것이라 멈춘다
        callsLeft = planned;
        String stamp = now();
        try (AnthropicInquiryReader reader = new AnthropicInquiryReader(key, MODEL, TIMEOUT)) {
            write("golden-" + stamp, goldenRun(reader, golden, probes, plan, a, stamp));
        }
    }

    private String goldenRun(InquiryReader reader, JsonNode golden, List<JsonNode> probes, String plan, int a,
                             String stamp) {
        Call warm = call(reader, "데우기", SMOKE_BODY);
        failIfError(warm);

        List<StepResult> steps = new ArrayList<>();
        List<String> broken = new ArrayList<>();
        int[] whole = new int[3]; // 단계, 규칙 경로 정답, LLM 경로 정답
        for (JsonNode scenario : golden.get("scenarios")) {
            String id = scenario.get("id").asString();
            List<JsonNode> rule = new GoldenInquiryJudge().play(golden, scenario);
            List<Turn> turns = new ArrayList<>();
            List<JsonNode> llm;
            try {
                llm = new GoldenInquiryJudge(input -> llmPath(reader, input, turns)).play(golden, scenario);
            } catch (IllegalStateException e) {
                if (callsLeft < 0) {
                    throw e;
                }
                // 판정 투영은 재고와 미송 판정만 받는다. LLM 경로가 다른 유형을 내면 그 단계에서 재생이 멈춘다
                broken.add(id + ": " + e.getMessage());
                llm = List.of();
            }
            JsonNode raw = scenario.get("steps");
            int t = 0;
            for (int i = 0; i < raw.size(); i++) {
                JsonNode step = raw.get(i);
                boolean ruleRight = rule.get(i).equals(step.get("expect"));
                boolean llmRight = i < llm.size() && llm.get(i).equals(step.get("expect"));
                whole[0]++;
                whole[1] += ruleRight ? 1 : 0;
                whole[2] += llmRight ? 1 : 0;
                if ("WHOLESALER".equals(step.path("sender").asString("")) || t >= turns.size()) {
                    continue;
                }
                Turn turn = turns.get(t++);
                if (turn.call() != null) {
                    steps.add(new StepResult(id + "#" + (i + 1), step.get("text").asString(), turn, ruleRight,
                            llmRight));
                }
            }
        }

        List<ProbeResult> probeResults = new ArrayList<>();
        for (JsonNode p : probes) {
            String body = p.get("body").asString();
            boolean open = "open_stock_clarify".equals(p.get("context").asString());
            int runs = LlmInquiry.worthAsking(body.strip(), false) ? p.get("repeat").asInt() : 1;
            List<Turn> turns = new ArrayList<>();
            for (int r = 0; r < runs; r++) {
                turns.add(probe(reader, golden, body, open));
            }
            probeResults.add(new ProbeResult(p, turns));
        }
        return goldenReport(stamp, plan, a, warm, steps, broken, whole, probes, probeResults);
    }

    /** 둘 다 있어야 돈다. 키는 리더에 넘기기만 하고 어디에도 찍지 않는다. */
    private static String keyFor(String mode) {
        String key = System.getenv("ONDO_LLM_API_KEY");
        assumeTrue(mode.equals(MODE) && key != null && !key.isBlank(), "ONDO_LLM_MEASURE=" + mode + "와 키가 없어 건너뛴다");
        return key;
    }

    private Call call(InquiryReader reader, String id, String body) {
        if (callsLeft-- <= 0) {
            throw new IllegalStateException("호출 상한을 넘었다. 과금을 막으려고 멈춘다");
        }
        long started = System.nanoTime();
        InquiryReader.Result result = reader.read(body);
        return new Call(id, result, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    /** 키 오류처럼 다음 호출도 다 실패할 상황만 실패로 본다. 오류 글자는 예외 클래스와 고정 종류뿐이라 키가 섞이지 않는다. */
    private static void failIfError(Call first) {
        if (first.result().kind() == Kind.ERROR) {
            fail("첫 호출이 오류라 측정할 수 없다: " + first.result().error());
        }
    }

    /**
     * 운영과 같은 {@link LlmInquiry#worthAsking}을 통과한 정답셋 자유 문장을 파일 순서로 모아 같은 글은 하나만 남기고,
     * 앞쪽 재고 문장에 몰리지 않게 전체에서 같은 간격으로 30개를 고른다. 파일이 같으면 매번 같은 30개다.
     */
    static List<Map.Entry<String, String>> picks() {
        Map<String, String> byText = new LinkedHashMap<>();
        for (JsonNode scenario : InquiryGolden.golden().get("scenarios")) {
            int no = 0;
            for (JsonNode step : scenario.get("steps")) {
                no++;
                String text = step.path("text").asString("");
                boolean free = !"WHOLESALER".equals(step.path("sender").asString("")) && !step.hasNonNull("inquiry");
                if (free && LlmInquiry.worthAsking(text, false)) {
                    byText.putIfAbsent(text, scenario.get("id").asString() + "#" + no);
                }
            }
        }
        List<Map.Entry<String, String>> all = byText.entrySet().stream().map(e -> Map.entry(e.getValue(), e.getKey())).toList();
        assertThat(all).as("worthAsking을 통과한 자유 문장 수").hasSizeGreaterThanOrEqualTo(SENTENCES);
        List<Map.Entry<String, String>> picked = new ArrayList<>();
        for (int i = 0; i < SENTENCES; i++) {
            picked.add(all.get(i * all.size() / SENTENCES));
        }
        return picked;
    }

    private static String latencyReport(String stamp, Call warm, List<Call> runs) {
        StringBuilder md = new StringBuilder("# LLM 지연 측정 %s%n%n".formatted(stamp));
        md.append("- 모델: %s, 측정 타임아웃: %dms, 운영 타임아웃: %dms%n".formatted(MODEL, TIMEOUT.toMillis(), PROD_TIMEOUT_MS));
        md.append("- 노트북에서 이어서 보낸 값이다. 공개 서버의 쉬었다 보내는 호출과 다를 수 있다%n".formatted());
        md.append("- 데우기 1회(통계 밖): %dms, %s%s%n".formatted(warm.ms(), warm.result().kind(), errorOf(warm)));
        if (!runs.isEmpty()) {
            List<Long> ms = runs.stream().map(Call::ms).sorted().toList();
            Map<Kind, Integer> kinds = new EnumMap<>(Kind.class);
            runs.forEach(c -> kinds.merge(c.result().kind(), 1, Integer::sum));
            md.append("- 본 측정 %d회: p50 %dms, p95 %dms, 최대 %dms%n".formatted(ms.size(), percentile(ms, 50),
                    percentile(ms, 95), ms.getLast()));
            md.append("- %dms 초과: %d회%n".formatted(PROD_TIMEOUT_MS, ms.stream().filter(v -> v > PROD_TIMEOUT_MS).count()));
            md.append("- 결과 종류: %s%n".formatted(kinds));
        }
        List<Call> all = new ArrayList<>(runs);
        all.addFirst(warm);
        long in = all.stream().mapToLong(c -> c.result().inputTokens()).sum();
        long out = all.stream().mapToLong(c -> c.result().outputTokens()).sum();
        md.append("- 토큰 합(데우기 포함 %d회): 입력 %d, 출력 %d%n".formatted(all.size(), in, out));
        md.append("- 비용 어림(Haiku 단가): $%.4f%n%n".formatted((in * INPUT_USD + out * OUTPUT_USD) / 1_000_000));
        // 문장 원문은 남기지 않는다. 정답셋 id로 찾아본다
        md.append("| 정답셋 id | ms | 결과 | 입력 | 출력 | 오류 |%n|---|---|---|---|---|---|%n".formatted());
        for (Call c : all) {
            md.append("| %s | %d | %s | %d | %d | %s |%n".formatted(c.id(), c.ms(), c.result().kind(),
                    c.result().inputTokens(), c.result().outputTokens(), dash(c.result().error())));
        }
        return md.toString();
    }

    /** 가장 가까운 순위 방식. 30개의 p95는 29번째 값이다. */
    static long percentile(List<Long> sorted, int p) {
        return sorted.get((int) Math.ceil(p / 100.0 * sorted.size()) - 1);
    }

    /** Result는 stop reason을 싣지 않아서 리더가 종류를 정한 규칙을 거꾸로 읽는다. */
    private static String stopReason(Kind kind) {
        return switch (kind) {
            case OK -> "end_turn";
            case REFUSAL -> "refusal";
            case MAX_TOKENS -> "max_tokens";
            case INVALID -> "end_turn인데 JSON 칸이 모자람, 또는 end_turn이 아닌 값";
            case ERROR, TIMEOUT -> "응답 없음";
        };
    }

    private static void write(String name, String report) throws IOException {
        Path out = Path.of("build", "llm-measure", name + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        System.out.println(report + "\n결과 파일: " + out.toAbsolutePath());
    }

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    private static String errorOf(Call call) {
        return call.result().error() == null ? "" : " " + call.result().error();
    }

    private static String dash(String value) {
        return value == null ? "-" : value;
    }

    /* --- golden ------------------------------------------------------------------ */

    /** {@link com.ondo.wholesale.chat.service.RetailChatLlmSend}의 거름 중 DB 없는 것과 자동 응답 설정. 방 확인은 정답셋에서 늘 통과한다. */
    static boolean asked(InquiryInput input) {
        var s = input.settings();
        return LlmInquiry.worthAsking(input.body().strip(), input.inquiry() != null)
                && s.enabled() && (s.stock() || s.backorder() || s.receivable());
    }

    /** 호출 없이 규칙으로 한 번 재생해 센다. 거름은 본문, 칩, 방 설정만 봐서 LLM 경로로 재생해도 같은 단계가 걸린다. */
    static int callsA(JsonNode golden) {
        int[] n = {0};
        InquiryJudge judge = new GoldenInquiryJudge(input -> {
            n[0] += asked(input) ? 1 : 0;
            return InquiryDecider.decide(input);
        });
        golden.get("scenarios").forEach(scenario -> judge.play(golden, scenario));
        return n[0];
    }

    /** 거름에 걸리는 문장은 운영처럼 부르지 않고 규칙으로 한 번만 판정한다. */
    static int callsB(List<JsonNode> probes) {
        return probes.stream().filter(p -> LlmInquiry.worthAsking(p.get("body").asString().strip(), false))
                .mapToInt(p -> p.get("repeat").asInt()).sum();
    }

    /** 정답셋 밖 추가 문장. 파일이 없으면 빈 목록이라 대상 B를 건너뛴다. 모르는 값은 호출 전에 멈춘다. */
    static List<JsonNode> probes() throws IOException {
        try (InputStream in = InquiryLlmMeasurement.class.getResourceAsStream(PROBES)) {
            if (in == null) {
                return List.of();
            }
            List<JsonNode> probes = new ArrayList<>();
            for (JsonNode p : JsonMapper.builder().build().readTree(in)) {
                String id = p.path("id").asString("");
                if (id.isEmpty() || p.path("body").asString("").isBlank()
                        || !CONTEXTS.contains(p.path("context").asString(""))
                        || !EXPECTS.contains(p.path("expect").asString("")) || p.path("repeat").asInt(0) < 1) {
                    throw new IllegalStateException("probes.json 형식이 다르다: " + (id.isEmpty() ? p : id));
                }
                probes.add(p);
            }
            return probes;
        }
    }

    /** 운영과 같은 순서. 거름에 걸리면 읽기 없이 규칙이다. */
    private InquiryDecision llmPath(InquiryReader reader, InquiryInput input, List<Turn> turns) {
        Call call = asked(input) ? call(reader, "golden", input.body().strip()) : null;
        InquiryReading reading = call == null ? null : readingOf(input.body().strip(), call.result());
        LlmInquiry.Decided decided = LlmInquiry.decide(input, reading);
        turns.add(new Turn(call, reading, decided));
        return decided.decision();
    }

    /** {@link com.ondo.wholesale.chat.inquiry.llm.InquiryLlmGate}와 같다. 미수 금액 풀기는 운영 기본값(끔)대로 지운다. */
    private static InquiryReading readingOf(String body, InquiryReader.Result result) {
        InquiryReading r = result.kind() == Kind.OK ? InquiryReading.validate(body, result.fields()) : null;
        return r == null ? null : new InquiryReading(r.intent(), r.size(), r.productName(), r.hasOrderHint(), false);
    }

    /**
     * 고정 문맥 둘은 정규화 표의 문맥을 빌린다: 방 101, 문맥 상품 2001 빈티지 플라워 셔츠(레드 S/M/L, 네이비 S/M), 2분 전 진입,
     * 봄봄상회(1), 정답셋 t0, 미송과 미수 표 그대로. open_stock_clarify는 그 어댑터가 1분 전 "이 셔츠 몇 장 남았어요?"와
     * 그 재고 되묻기를 이력에 먼저 깐다. 앞 질문은 규칙으로 판정한다.
     */
    private Turn probe(InquiryReader reader, JsonNode golden, String body, boolean openClarify) {
        List<Turn> turns = new ArrayList<>();
        int[] seen = {0};
        Function<InquiryInput, InquiryDecision> decider = input -> openClarify && seen[0]++ == 0
                ? InquiryDecider.decide(input) : llmPath(reader, input, turns);
        ObjectNode c = JsonNodeFactory.instance.objectNode().put("text", body).put("afterClarify", openClarify);
        try {
            new GoldenInquiryJudge(decider).judgeNormalizationCase(golden, c);
        } catch (IllegalStateException e) {
            // 정규화 투영은 재고 판정만 받는다. 판정은 이미 받았으니 투영 실패만 버린다
            if (turns.isEmpty()) {
                throw e;
            }
        }
        return turns.getFirst();
    }

    private static String goldenReport(String stamp, String plan, int targetA, Call warm, List<StepResult> steps,
                                       List<String> broken, int[] whole, List<JsonNode> probes,
                                       List<ProbeResult> probeResults) {
        StringBuilder md = new StringBuilder(plan).append("%n%n# LLM 정답셋 측정 %s%n%n".formatted(stamp));
        md.append("- 모델: %s, 날짜: %s, 측정 타임아웃: %dms. 미수 금액 풀기: 끔(운영 기본값)%n".formatted(MODEL, stamp,
                TIMEOUT.toMillis()));
        md.append("- 원문은 사람 검수용이다. 이 파일은 build 아래라 커밋되지 않는다%n%n## 대상 A: 정답셋 시나리오%n%n".formatted());
        md.append("- 분모: 정답셋 184건 = 시나리오 %d단계 + 정규화 17줄. 정규화 17줄은 부르지 않았다. ".formatted(whole[0]));
        md.append("LLM 대상은 소매 자유 문장 중 worthAsking을 통과하고 자동 응답이 켜진 방의 단계 %d개(고유 문장 %d개). ".formatted(
                targetA, steps.stream().map(StepResult::text).distinct().count()));
        md.append("같은 문장도 단계마다 부른다%n".formatted());
        if (steps.size() != targetA || !broken.isEmpty()) {
            md.append("- 재생이 멈춰 못 잰 단계: %d. 멈춘 시나리오: %s%n".formatted(targetA - steps.size(), broken));
        }
        Map<String, Integer> directions = new TreeMap<>();
        int intentLabeled = 0;
        int intentSame = 0;
        int sizeSaid = 0;
        int sizeSame = 0;
        for (StepResult s : steps) {
            LlmInquiry.Decided d = s.turn().decided();
            if (!d.rule().equals(d.decision())) {
                directions.merge(direction(d.rule(), d.decision()), 1, Integer::sum);
            }
            InquiryReading.Fields f = s.turn().call().result().fields();
            ChatIntent label = label(d.rule());
            if (f != null && label != null) {
                intentLabeled++;
                intentSame += label.name().equals(f.intent()) ? 1 : 0;
            }
            if (f != null && d.rule() instanceof InquiryDecision.StockAnswer answer
                    && !InquiryReading.NO_SIZE.equals(f.size())) {
                sizeSaid++;
                sizeSame += answer.variant().size().equals(f.size()) ? 1 : 0;
            }
        }
        int changed = directions.values().stream().mapToInt(Integer::intValue).sum();
        md.append("- 규칙과 최종이 같은 수: %d / %d%n".formatted(steps.size() - changed, steps.size()));
        md.append("- 달라진 문항 %d. 방향: %s%n".formatted(changed, directions));
        md.append("- LLM 해석으로 최종 결과가 바뀐 건수(넘김→답 + 넘김→되묻기 + 되묻기→답): %d%n".formatted(
                directions.getOrDefault("넘김→답", 0) + directions.getOrDefault("넘김→되묻기", 0)
                        + directions.getOrDefault("되묻기→답", 0)));
        md.append("- 정답 일치(대상 A 단계): 규칙 경로 %d / %d, LLM 경로 %d / %d%n".formatted(
                steps.stream().filter(StepResult::ruleRight).count(), steps.size(),
                steps.stream().filter(StepResult::llmRight).count(), steps.size()));
        md.append("- 정답 일치(시나리오 전체): 규칙 경로 %d / %d, LLM 경로 %d / %d. LLM 경로는 앞 단계가 바뀌면 뒤 단계 이력도 바뀐다%n"
                .formatted(whole[1], whole[0], whole[2], whole[0]));
        md.append(("- LLM 자체 유형 일치: %d / %d. 라벨은 같은 입력의 규칙 판정 유형이고, 유형 없는 넘김은 사람 요청, 협상, 유형 둘만 "
                + "OTHER, 나머지(NO_INTENT 등)는 라벨이 없어 뺐다. LLM 값은 검증 전 원값이다%n").formatted(intentSame, intentLabeled));
        md.append("- LLM이 낸 사이즈(원값, NONE 아님)가 규칙 재고 답 옵션과 같은 수: %d / %d%n".formatted(sizeSame, sizeSaid));
        md.append("- 폴백: %s%n".formatted(outcomes(steps.stream().map(StepResult::turn).toList())));
        md.append("- 사람이 검수한 잘못된 자동 답 수: (검수 뒤 적는다)%n%n".formatted());
        md.append("### 검수할 단계 (규칙과 최종이 다르거나 LLM 경로가 정답과 다르다)%n%n".formatted());
        md.append("| 정답셋 id | 원문 | 방향 | 규칙 | 최종 | LLM 원값 | 정답 일치 규칙/LLM |%n|---|---|---|---|---|---|---|%n".formatted());
        for (StepResult s : steps) {
            LlmInquiry.Decided d = s.turn().decided();
            boolean same = d.rule().equals(d.decision());
            if (!same || !s.llmRight()) {
                md.append("| %s | %s | %s | %s | %s | %s | %s/%s |%n".formatted(s.id(), cell(s.text()),
                        same ? "같음" : direction(d.rule(), d.decision()), describe(d.rule()), describe(d.decision()),
                        cell(fieldsOf(s.turn())), s.ruleRight() ? "O" : "X", s.llmRight() ? "O" : "X"));
            }
        }

        md.append("%n## 대상 B: probes.json%n%n".formatted());
        if (probes.isEmpty()) {
            md.append("- probes.json이 없어 건너뛰었다%n".formatted());
        } else {
            List<Turn> called = probeResults.stream().flatMap(r -> r.runs().stream()).toList();
            md.append("- 문항 %d, 판정 %d회. 거름에 걸린 문항 %d개는 운영처럼 부르지 않고 규칙으로 한 번 판정했다%n".formatted(
                    probes.size(), called.size(), probeResults.stream().filter(r -> r.runs().getFirst().call() == null)
                            .count()));
            md.append("- 실패: HANDOFF는 넘김이 아니면, NO_STOCK_ANSWER는 답(재고, 미송, 포장, 미수)이 나오면 실패. "
                    + "반복 중 한 번이라도 어긋나면 실패. ANSWER_OK는 기록만%n".formatted());
            md.append("- 폴백: %s%n- 규칙만으로도 실패: 같은 입력의 규칙 판정이 이미 기대와 어긋난다. LLM 탓이 아니다%n%n".formatted(
                    outcomes(called)));
            md.append("| 그룹 | 문항 | 실패 | 실패 id | 규칙만으로도 실패 id |%n|---|---|---|---|---|%n".formatted());
            Map<String, List<ProbeResult>> groups = new TreeMap<>();
            probeResults.forEach(r -> groups.computeIfAbsent(r.probe().path("group").asString(""),
                    k -> new ArrayList<>()).add(r));
            groups.forEach((group, rs) -> {
                List<String> failed = rs.stream().filter(ProbeResult::failed).map(r -> r.probe().get("id").asString())
                        .toList();
                List<String> ruleFailed = rs.stream().filter(ProbeResult::ruleFailed)
                        .map(r -> r.probe().get("id").asString()).toList();
                md.append("| %s | %d | %d | %s | %s |%n".formatted(group, rs.size(), failed.size(),
                        String.join(", ", failed), String.join(", ", ruleFailed)));
            });
            md.append("%n### 실패 문항과 ANSWER_OK 기록%n%n".formatted());
            md.append("| id | 기대 | 문맥 | 원문 | 규칙 | 회차별 최종 | 회차별 LLM 원값 |%n|---|---|---|---|---|---|---|%n".formatted());
            for (ProbeResult r : probeResults) {
                JsonNode p = r.probe();
                if (r.failed() || "ANSWER_OK".equals(p.get("expect").asString())) {
                    md.append("| %s | %s%s | %s | %s | %s | %s | %s |%n".formatted(p.get("id").asString(),
                            p.get("expect").asString(), r.failed() ? " 실패" : "", p.get("context").asString(),
                            cell(p.get("body").asString()), describe(r.runs().getFirst().decided().rule()),
                            r.runs().stream().map(t -> describe(t.decided().decision())).toList(),
                            cell(r.runs().stream().map(InquiryLlmMeasurement::fieldsOf).toList().toString())));
                }
            }
        }

        List<Call> calls = new ArrayList<>(steps.stream().map(s -> s.turn().call()).toList());
        probeResults.forEach(r -> r.runs().stream().map(Turn::call).filter(c -> c != null).forEach(calls::add));
        md.append("%n## 호출%n%n- 실제 호출 %d회(데우기 포함). 데우기 %dms%n".formatted(calls.size() + 1, warm.ms()));
        if (!calls.isEmpty()) {
            List<Long> ms = calls.stream().map(Call::ms).sorted().toList();
            md.append("- 데우기 뺀 %d회: p50 %dms, p95 %dms, 최대 %dms. %dms 초과 %d회(운영이면 그 건은 규칙으로 갔다)%n".formatted(
                    ms.size(), percentile(ms, 50), percentile(ms, 95), ms.getLast(), PROD_TIMEOUT_MS,
                    ms.stream().filter(v -> v > PROD_TIMEOUT_MS).count()));
        }
        calls.add(warm);
        long in = calls.stream().mapToLong(c -> c.result().inputTokens()).sum();
        long out = calls.stream().mapToLong(c -> c.result().outputTokens()).sum();
        md.append("- 토큰 합: 입력 %d, 출력 %d. 비용 어림(Haiku 단가): $%.4f%n".formatted(in, out,
                (in * INPUT_USD + out * OUTPUT_USD) / 1_000_000));
        return md.toString();
    }

    /** 결과 종류별 수. OK인데 검증을 못 넘으면 불확실과 칸 오류로 나눈다(게이트 로그와 같은 갈래). */
    private static Map<String, Integer> outcomes(List<Turn> turns) {
        Map<String, Integer> counts = new TreeMap<>();
        turns.stream().filter(t -> t.call() != null).forEach(t -> counts.merge(outcome(t), 1, Integer::sum));
        return counts;
    }

    private static String outcome(Turn turn) {
        InquiryReader.Result r = turn.call().result();
        if (r.kind() != Kind.OK || turn.reading() != null) {
            return r.kind().name();
        }
        return r.fields() != null && Boolean.TRUE.equals(r.fields().uncertain()) ? "UNCERTAIN" : "INVALID_FIELDS";
    }

    /** 읽은 칸만 적는다. 응답 원문은 적지 않는다. */
    private static String fieldsOf(Turn turn) {
        if (turn.call() == null) {
            return "안 부름";
        }
        InquiryReading.Fields f = turn.call().result().fields();
        return f == null ? outcome(turn) : "%s %s %s 근거=%s 색=%s 상품=%s 주문=%s 미수전체=%s".formatted(outcome(turn),
                f.intent(), f.size(), f.sizeEvidence(), f.color(), f.productName(), f.orderHint(),
                f.asksOwnOutstandingTotal());
    }

    /** 8-3의 라벨. 유형이 있는 판정은 그 유형, 유형 없는 넘김은 사람 요청, 협상, 유형 둘만 OTHER다. */
    private static ChatIntent label(InquiryDecision rule) {
        if (rule.intent() != null) {
            return rule.intent();
        }
        return rule instanceof InquiryDecision.Handoff h && OTHER_REASONS.contains(h.reason()) ? ChatIntent.OTHER : null;
    }

    private static String category(InquiryDecision d) {
        return switch (d) {
            case InquiryDecision.Handoff h -> "넘김";
            case InquiryDecision.StockClarify c -> "되묻기";
            case InquiryDecision.BackorderClarify c -> "되묻기";
            default -> "답";
        };
    }

    private static String direction(InquiryDecision rule, InquiryDecision fin) {
        String arrow = category(rule) + "→" + category(fin);
        if (!category(rule).equals(category(fin))) {
            return arrow;
        }
        if (rule instanceof InquiryDecision.Handoff r && fin instanceof InquiryDecision.Handoff f) {
            return arrow + (r.reason() == f.reason() ? " 유형만 다름" : " 사유만 다름");
        }
        return arrow + " 대상 다름";
    }

    private static String describe(InquiryDecision d) {
        return switch (d) {
            case InquiryDecision.Handoff h -> "넘김 " + h.reason() + (h.intent() == null ? "" : " " + h.intent());
            case InquiryDecision.StockClarify c -> "되묻기 재고";
            case InquiryDecision.BackorderClarify c -> "되묻기 미송";
            case InquiryDecision.StockAnswer s -> "답 재고 %s %s %s".formatted(s.variant().color(), s.variant().size(),
                    s.band());
            case InquiryDecision.BackorderAnswer b -> "답 미송 " + b.backorder().backorderId();
            case InquiryDecision.PackingAnswer p -> "답 포장 " + p.orderIds();
            case InquiryDecision.ReceivableAnswer r -> "답 미수";
        };
    }

    /** 표 칸을 깨는 글자만 바꾼다. 지시처럼 보이는 문장에 | 가 섞일 수 있다. */
    private static String cell(String text) {
        return text.replace("|", "\\|").replaceAll("\\s+", " ");
    }

    /** HANDOFF는 넘김만 통과, NO_STOCK_ANSWER는 답이면 실패(넘김, 되묻기 통과), ANSWER_OK는 실패로 세지 않는다. */
    static boolean fails(String expect, InquiryDecision d) {
        return switch (expect) {
            case "HANDOFF" -> !(d instanceof InquiryDecision.Handoff);
            case "NO_STOCK_ANSWER" -> "답".equals(category(d));
            default -> false;
        };
    }

    private record Call(String id, InquiryReader.Result result, long ms) {
    }

    /** call은 거름에 걸려 안 불렀으면 null이다. */
    private record Turn(Call call, InquiryReading reading, LlmInquiry.Decided decided) {
    }

    private record StepResult(String id, String text, Turn turn, boolean ruleRight, boolean llmRight) {
    }

    private record ProbeResult(JsonNode probe, List<Turn> runs) {
        boolean failed() {
            return runs.stream().anyMatch(t -> fails(probe.get("expect").asString(), t.decided().decision()));
        }

        /** 규칙 판정은 같은 입력이면 회차마다 같다. */
        boolean ruleFailed() {
            return fails(probe.get("expect").asString(), runs.getFirst().decided().rule());
        }
    }
}
