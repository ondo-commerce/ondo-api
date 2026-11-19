package com.ondo.wholesale.chat.inquiry.llm;

import com.anthropic.models.messages.MessageCreateParams;
import com.ondo.wholesale.chat.inquiry.InquiryReading;
import com.ondo.wholesale.chat.inquiry.InquiryReading.Fields;
import com.ondo.wholesale.chat.inquiry.llm.InquiryReader.Kind;
import com.ondo.wholesale.chat.inquiry.llm.InquiryReader.Result;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 요청 모양, 응답 JSON 읽기, 실패를 결과 종류로 바꾸는 것을 본다. 서버는 전부 127.0.0.1 로컬이고 외부 호출은 없다.
 * 값 검증(허용 값, 사이즈 근거)은 InquiryReadingTest, 기다리기와 동시 한도는 InquiryLlmGateTest 몫이다.
 */
class AnthropicInquiryReaderTest {

    private static final String BODY = "레드 M 구할 수 있을까요?";
    private static final String MODEL = "claude-haiku-5-5";
    private static final JsonMapper JSON = JsonMapper.shared();

    private final List<AutoCloseable> toClose = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (AutoCloseable c : toClose) {
            c.close();
        }
    }

    /** 요청 한 건을 기록하고 정해진 상태와 본문으로 답하는 로컬 서버. */
    private record Seen(String method, String path, String headers, String body) {
    }

    private final class Stub {
        final HttpServer server;
        final List<Seen> seen = new CopyOnWriteArrayList<>();

        Stub(int status, String responseBody) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        String.valueOf(new java.util.TreeMap<>(exchange.getRequestHeaders())), body));
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("content-type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            toClose.add(() -> server.stop(0));
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }
    }

    private static String message(String stopReason, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "msg_test");
        m.put("type", "message");
        m.put("role", "assistant");
        m.put("model", MODEL);
        m.put("content", List.of(Map.of("type", "text", "text", text)));
        m.put("stop_reason", stopReason);
        m.put("stop_sequence", null);
        m.put("usage", Map.of("input_tokens", 120, "output_tokens", 34));
        return JSON.writeValueAsString(m);
    }

    private static final String VALID_JSON = """
            {"intent":"STOCK","uncertain":false,"size":"M","sizeEvidence":"M","color":"레드","productName":"",\
            "orderHint":"","asksOwnOutstandingTotal":false}""";

    private AnthropicInquiryReader reader(String url, Duration timeout) {
        return new AnthropicInquiryReader("test-key", MODEL, timeout, url);
    }

    // ---- request() ----

    @Test
    void 요청_객체에는_모델과_시스템_프롬프트와_본문_한_줄뿐이다() {
        MessageCreateParams params = AnthropicInquiryReader.request("model-xyz", BODY);

        assertThat(params.model().asString()).isEqualTo("model-xyz");
        assertThat(params.system()).isPresent();
        assertThat(params.system().get().asString()).isEqualTo(AnthropicInquiryReader.SYSTEM_PROMPT);
        assertThat(params.messages()).hasSize(1);
        assertThat(params.messages().get(0).content().asString()).isEqualTo(BODY);
        assertThat(params.metadata()).isEmpty();
        assertThat(params.tools()).isEmpty();
        assertThat(params.thinking()).isEmpty();
    }

    @Test
    void 실제로_나가는_JSON에는_허용한_키만_있고_방_id나_사용자_정보가_없다() throws Exception {
        Stub stub = new Stub(200, message("end_turn", VALID_JSON));
        String secretLooking = "retailerId=77 roomId=4242 사업자번호 123-45-67890";

        reader(stub.url(), Duration.ofSeconds(10)).read(BODY);
        assertThat(stub.seen).hasSize(1);
        JsonNode sent = JSON.readTree(stub.seen.get(0).body());

        assertThat(new TreeSet<>(sent.propertyNames())).containsExactlyInAnyOrder("model", "max_tokens", "system",
                "output_config", "messages");
        assertThat(sent.get("model").stringValue()).isEqualTo(MODEL);
        assertThat(sent.get("messages")).hasSize(1);
        assertThat(sent.get("messages").get(0).get("role").stringValue()).isEqualTo("user");
        assertThat(sent.get("messages").get(0).get("content").stringValue()).isEqualTo(BODY);
        // 시스템 프롬프트는 상수 그대로고, 요청마다 달라지는 값이 없다
        String system = sent.get("system").isString() ? sent.get("system").stringValue()
                : sent.get("system").get(0).get("text").stringValue();
        assertThat(system).isEqualTo(AnthropicInquiryReader.SYSTEM_PROMPT);
        assertThat(AnthropicInquiryReader.SYSTEM_PROMPT).doesNotContain(secretLooking);
        // 다른 본문으로 보내도 본문 칸만 바뀌고 나머지는 같다
        reader(stub.url(), Duration.ofSeconds(10)).read("다른 글");
        JsonNode second = JSON.readTree(stub.seen.get(1).body());
        assertThat(second.get("messages").get(0).get("content").stringValue()).isEqualTo("다른 글");
        assertThat(second.get("system")).isEqualTo(sent.get("system"));
        assertThat(second.get("output_config")).isEqualTo(sent.get("output_config"));
    }

    @Test
    void 요청은_POST_v1_messages이고_키는_헤더로_간다() throws Exception {
        Stub stub = new Stub(200, message("end_turn", VALID_JSON));

        reader(stub.url(), Duration.ofSeconds(10)).read(BODY);

        Seen seen = stub.seen.get(0);
        assertThat(seen.method()).isEqualTo("POST");
        assertThat(seen.path()).isEqualTo("/v1/messages");
        assertThat(seen.headers()).contains("test-key");
        assertThat(seen.body()).doesNotContain("test-key");
    }

    // ---- fields() ----

    @Test
    void 정상_JSON은_여덟_칸으로_읽는다() {
        Fields f = AnthropicInquiryReader.fields("""
                {"intent":"BACKORDER","uncertain":true,"size":"2XL","sizeEvidence":"투엑스라지","color":"블랙",\
                "productName":"셔츠","orderHint":"지난주","asksOwnOutstandingTotal":true}""");

        assertThat(f).isEqualTo(new Fields("BACKORDER", true, "2XL", "투엑스라지", "블랙", "셔츠", "지난주", true));
    }

    @Test
    void 칸_하나가_빠지면_null이다() {
        for (String name : List.of("intent", "uncertain", "size", "sizeEvidence", "color", "productName",
                "orderHint", "asksOwnOutstandingTotal")) {
            Map<String, Object> m = new LinkedHashMap<>(Map.of("intent", "STOCK", "uncertain", false, "size", "M",
                    "sizeEvidence", "M", "color", "", "productName", "", "orderHint", "",
                    "asksOwnOutstandingTotal", false));
            m.remove(name);
            assertThat(AnthropicInquiryReader.fields(JSON.writeValueAsString(m))).as(name).isNull();
        }
    }

    @Test
    void 타입이_다르면_null이다() {
        assertThat(AnthropicInquiryReader.fields("""
                {"intent":"STOCK","uncertain":"false","size":"M","sizeEvidence":"M","color":"","productName":"",\
                "orderHint":"","asksOwnOutstandingTotal":false}""")).as("uncertain 문자열").isNull();
        assertThat(AnthropicInquiryReader.fields("""
                {"intent":1,"uncertain":false,"size":"M","sizeEvidence":"M","color":"","productName":"",\
                "orderHint":"","asksOwnOutstandingTotal":false}""")).as("intent 숫자").isNull();
        assertThat(AnthropicInquiryReader.fields("""
                {"intent":"STOCK","uncertain":false,"size":"M","sizeEvidence":"M","color":null,"productName":"",\
                "orderHint":"","asksOwnOutstandingTotal":false}""")).as("color null").isNull();
        assertThat(AnthropicInquiryReader.fields("""
                {"intent":"STOCK","uncertain":false,"size":"M","sizeEvidence":"M","color":"","productName":"",\
                "orderHint":"","asksOwnOutstandingTotal":0}""")).as("asks 숫자").isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "{\"intent\":\"STOCK\",\"uncertain\":fa", "not json", "[]", "null", "\"str\"",
            "{}"})
    void 잘린_JSON과_빈_문자열과_객체가_아닌_값은_null이다(String json) {
        assertThat(AnthropicInquiryReader.fields(json)).isNull();
    }

    @Test
    void 허용_값_밖의_문자열은_여기서는_읽히고_값_검사는_InquiryReading이_한다() {
        Fields f = AnthropicInquiryReader.fields("""
                {"intent":"BOGUS","uncertain":false,"size":"XXL","sizeEvidence":"M","color":"","productName":"",\
                "orderHint":"","asksOwnOutstandingTotal":false}""");

        assertThat(f).isNotNull();
        assertThat(InquiryReading.validate(BODY, f)).isNull();
    }

    @Test
    void 수량이나_금액_같은_모르는_칸이_있어도_읽지_않고_정상으로_본다() {
        Fields f = AnthropicInquiryReader.fields("""
                {"intent":"STOCK","uncertain":false,"size":"M","sizeEvidence":"M","color":"","productName":"",\
                "orderHint":"","asksOwnOutstandingTotal":false,"quantity":5,"amount":120000,"reply":"재고 5개"}""");

        assertThat(f).isEqualTo(new Fields("STOCK", false, "M", "M", "", "", "", false));
    }

    // ---- 네트워크를 부르지 않는 경우 ----

    @Test
    void 객체를_만들기만_하면_서버에_요청이_없고_던지지_않는다() throws Exception {
        Stub stub = new Stub(200, message("end_turn", VALID_JSON));

        new AnthropicInquiryReader("fake-key", MODEL, Duration.ofMillis(500), stub.url());
        new AnthropicInquiryReader("fake-key", MODEL, Duration.ofMillis(500));
        new AnthropicInquiryReader("", MODEL, Duration.ofMillis(500));

        assertThat(stub.seen).isEmpty();
    }

    @Test
    void 키가_비어_있어도_read는_던지지_않고_결과_종류로_돌려준다() throws Exception {
        Stub stub = new Stub(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                + "\"message\":\"invalid\"}}");

        Result result = new AnthropicInquiryReader("", MODEL, Duration.ofSeconds(5), stub.url()).read(BODY);

        assertThat(result).isNotNull();
        assertThat(result.kind()).isNotEqualTo(Kind.OK);
        assertThat(result.fields()).isNull();
    }

    // ---- 응답 종류 ----

    @Test
    void 정상_응답은_OK와_여덟_칸과_토큰_수다() throws Exception {
        Stub stub = new Stub(200, message("end_turn", VALID_JSON));

        Result result = reader(stub.url(), Duration.ofSeconds(10)).read(BODY);

        assertThat(result.kind()).isEqualTo(Kind.OK);
        assertThat(result.fields()).isEqualTo(new Fields("STOCK", false, "M", "M", "레드", "", "", false));
        assertThat(result.inputTokens()).isEqualTo(120);
        assertThat(result.outputTokens()).isEqualTo(34);
        assertThat(result.error()).isNull();
    }

    @Test
    void stop_reason이_refusal이면_본문을_읽지_않고_REFUSAL이다() throws Exception {
        Stub stub = new Stub(200, message("refusal", VALID_JSON));

        Result result = reader(stub.url(), Duration.ofSeconds(10)).read(BODY);

        assertThat(result.kind()).isEqualTo(Kind.REFUSAL);
        assertThat(result.fields()).isNull();
        assertThat(result.inputTokens()).isEqualTo(120);
    }

    @Test
    void stop_reason이_max_tokens면_잘린_JSON이_멀쩡해_보여도_MAX_TOKENS다() throws Exception {
        Stub stub = new Stub(200, message("max_tokens", VALID_JSON));

        Result result = reader(stub.url(), Duration.ofSeconds(10)).read(BODY);

        assertThat(result.kind()).isEqualTo(Kind.MAX_TOKENS);
        assertThat(result.fields()).isNull();
    }

    @Test
    void 본문이_JSON이_아니거나_칸이_빠지면_INVALID다() throws Exception {
        for (String text : List.of("죄송합니다. 분류할 수 없습니다", "{\"intent\":\"STOCK\"}", "")) {
            Stub stub = new Stub(200, message("end_turn", text));

            Result result = reader(stub.url(), Duration.ofSeconds(10)).read(BODY);

            assertThat(result.kind()).as(text).isEqualTo(Kind.INVALID);
            assertThat(result.fields()).isNull();
        }
    }

    @Test
    void end_turn이_아닌_stop_reason은_INVALID다() throws Exception {
        Stub stub = new Stub(200, message("stop_sequence", VALID_JSON));

        assertThat(reader(stub.url(), Duration.ofSeconds(10)).read(BODY).kind()).isEqualTo(Kind.INVALID);
    }

    // ---- 실패 ----

    @Test
    void 인증_401이면_ERROR이고_오류_문자열에_본문이_섞이지_않는다() throws Exception {
        String leak = "LEAK-서버가_되돌려준_글";
        Stub stub = new Stub(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                + "\"message\":\"" + leak + " " + BODY + " test-key\"}}");

        Result result = reader(stub.url(), Duration.ofSeconds(10)).read(BODY);

        assertThat(result.kind()).isEqualTo(Kind.ERROR);
        assertThat(result.fields()).isNull();
        assertThat(result.error()).isNotBlank().doesNotContain(leak).doesNotContain("구할").doesNotContain("test-key");
        assertThat(result.error()).contains("authentication_error");
    }

    @Test
    void 서버가_500을_줘도_다시_보내지_않고_한_번만_요청한다() throws Exception {
        Stub stub = new Stub(500, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"boom\"}}");

        Result result = reader(stub.url(), Duration.ofSeconds(10)).read(BODY);

        assertThat(result.kind()).isEqualTo(Kind.ERROR);
        assertThat(stub.seen).hasSize(1);
    }

    @Test
    void 응답하지_않는_서버에는_설정한_시간_근처에서_ERROR로_끝나고_연결은_한_번뿐이다() throws Exception {
        ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        AtomicInteger connections = new AtomicInteger();
        List<Socket> held = new CopyOnWriteArrayList<>();
        Thread acceptor = Thread.ofPlatform().daemon().start(() -> {
            try {
                while (true) {
                    held.add(silent.accept());
                    connections.incrementAndGet();
                }
            } catch (IOException closed) {
                // 테스트가 끝나 소켓을 닫았다
            }
        });
        toClose.add(() -> {
            silent.close();
            for (Socket s : held) {
                s.close();
            }
            acceptor.join(2000);
        });
        String url = "http://127.0.0.1:" + silent.getLocalPort();
        // JVM 첫 SDK 호출의 클래스 로딩이 측정에 섞이지 않게, 바로 답하는 다른 로컬 서버로 데운다.
        // 응답 없는 소켓을 쓰지 않으므로 연결 성립이나 짧은 타임아웃에 기대지 않는다
        Stub warm = new Stub(500, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"x\"}}");
        assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> new AnthropicInquiryReader("warm", MODEL, Duration.ofSeconds(20), warm.url()).read("warm"));
        AnthropicInquiryReader reader = reader(url, Duration.ofMillis(500));

        // 설정이 수 초로 늘어나는 회귀는 이 상한에서 실패한다. 독립 스레드에서 돌려 멈춰도 테스트가 끝난다
        long[] elapsedNanos = new long[1];
        Result result = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            long started = System.nanoTime();
            Result r = reader.read(BODY);
            elapsedNanos[0] = System.nanoTime() - started;
            return r;
        });

        assertThat(result.kind()).isEqualTo(Kind.ERROR);
        assertThat(result.error()).isNotBlank().doesNotContain("구할");
        assertThat(Duration.ofNanos(elapsedNanos[0])).isGreaterThanOrEqualTo(Duration.ofMillis(200))
                .isLessThan(Duration.ofSeconds(2));
        // 본 연결이 accept 되는 것을 기다린 뒤, 재시도 연결이 더 생기지 않았는지 짧게 지켜본다
        awaitTrue(() -> connections.get() >= 1);
        Thread.sleep(300);
        assertThat(connections.get()).isEqualTo(1);
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long limit = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < limit) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test
    void 모르는_오류_type에_민감한_글자가_있어도_오류_문자열에_없다() throws Exception {
        String type = "LEAK-" + BODY + "-test-key";
        Stub stub = new Stub(401, "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + type
                + "\"}}");

        Result result = reader(stub.url(), Duration.ofSeconds(10)).read(BODY);

        assertThat(result.kind()).isEqualTo(Kind.ERROR);
        assertThat(result.error()).isNotBlank().doesNotContain("LEAK").doesNotContain("test-key")
                .doesNotContain("구할").doesNotContain("/");
    }

    @Test
    void 중복_키는_fields에서_null이다() {
        assertThat(AnthropicInquiryReader.fields("""
                {"intent":"STOCK","uncertain":true,"uncertain":false,"size":"M","sizeEvidence":"M","color":"",\
                "productName":"","orderHint":"","asksOwnOutstandingTotal":false}""")).as("uncertain 중복").isNull();
        assertThat(AnthropicInquiryReader.fields("""
                {"intent":"STOCK","uncertain":false,"size":"L","size":"M","sizeEvidence":"M","color":"",\
                "productName":"","orderHint":"","asksOwnOutstandingTotal":false}""")).as("size 중복").isNull();
    }

    @Test
    void 중복_키가_든_응답은_HTTP_경로로_INVALID다() throws Exception {
        String dup = """
                {"intent":"STOCK","uncertain":true,"uncertain":false,"size":"M","sizeEvidence":"M","color":"",\
                "productName":"","orderHint":"","asksOwnOutstandingTotal":false}""";
        Stub stub = new Stub(200, message("end_turn", dup));

        Result result = reader(stub.url(), Duration.ofSeconds(10)).read(BODY);

        assertThat(result.kind()).isEqualTo(Kind.INVALID);
        assertThat(result.fields()).isNull();
    }

    @Test
    void 기한이_이미_지났으면_보내지_않고_TIMEOUT이다() throws Exception {
        Stub stub = new Stub(200, message("end_turn", VALID_JSON));

        Result result = reader(stub.url(), Duration.ofSeconds(10)).read(BODY, System.nanoTime() - 1);

        assertThat(result.kind()).isEqualTo(Kind.TIMEOUT);
        assertThat(result.fields()).isNull();
        assertThat(stub.seen).isEmpty();
    }

    @Test
    void 기한이_남아_있으면_기한_있는_read도_OK다() throws Exception {
        Stub stub = new Stub(200, message("end_turn", VALID_JSON));

        Result result = reader(stub.url(), Duration.ofSeconds(10))
                .read(BODY, System.nanoTime() + Duration.ofSeconds(30).toNanos());

        assertThat(result.kind()).isEqualTo(Kind.OK);
        assertThat(stub.seen).hasSize(1);
    }

    @Test
    void close_뒤_read는_ERROR이고_요청이_없다() throws Exception {
        Stub stub = new Stub(200, message("end_turn", VALID_JSON));
        AnthropicInquiryReader reader = reader(stub.url(), Duration.ofSeconds(10));
        assertThat(reader.read(BODY).kind()).isEqualTo(Kind.OK);

        reader.close();

        assertThat(reader.read(BODY).kind()).isEqualTo(Kind.ERROR);
        assertThat(stub.seen).hasSize(1);
    }

    @Test
    void 첫_read_전에_close해도_이후_read는_ERROR이고_요청이_없다() throws Exception {
        Stub stub = new Stub(200, message("end_turn", VALID_JSON));
        AnthropicInquiryReader reader = reader(stub.url(), Duration.ofSeconds(10));

        reader.close();
        reader.close();

        assertThat(reader.read(BODY).kind()).isEqualTo(Kind.ERROR);
        assertThat(stub.seen).isEmpty();
    }
}
