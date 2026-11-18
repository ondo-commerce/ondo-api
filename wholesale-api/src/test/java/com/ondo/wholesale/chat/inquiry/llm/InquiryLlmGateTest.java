package com.ondo.wholesale.chat.inquiry.llm;

import com.ondo.wholesale.chat.domain.ChatIntent;
import com.ondo.wholesale.chat.inquiry.InquiryReading;
import com.ondo.wholesale.chat.inquiry.InquiryReading.Fields;
import com.ondo.wholesale.chat.inquiry.llm.InquiryReader.Kind;
import com.ondo.wholesale.chat.inquiry.llm.InquiryReader.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가짜 InquiryReader(람다)로 게이트의 갈래만 본다. SDK와 네트워크는 AnthropicInquiryReaderTest 몫이고,
 * 읽기 값 검증 규칙 자체는 InquiryReadingTest 몫이다. 시간은 넉넉한 상한만 두고 기다림은 래치로 만든다.
 */
class InquiryLlmGateTest {

    private static final String BODY = "레드 M 구할 수 있을까요?";
    private static final Duration LONG = Duration.ofSeconds(10);

    private final List<InquiryLlmGate> gates = new ArrayList<>();

    @AfterEach
    void closeGates() {
        gates.forEach(InquiryLlmGate::close);
    }

    private InquiryLlmGate gate(InquiryReader reader) {
        return gate(reader, true, "key", 4, false);
    }

    private InquiryLlmGate gate(InquiryReader reader, boolean enabled, String apiKey, int max, boolean receivable) {
        InquiryLlmGate gate = new InquiryLlmGate(reader, enabled, apiKey, "model-x", Duration.ofMillis(1500), max,
                receivable);
        gates.add(gate);
        return gate;
    }

    private static Fields stock() {
        return new Fields("STOCK", false, "M", "M", "레드", "", "", false);
    }

    private static Result ok(Fields fields) {
        return new Result(Kind.OK, fields, 10, 5, null);
    }

    private static Result of(Kind kind) {
        return new Result(kind, null, 0, 0, kind == Kind.ERROR ? "SomeException" : null);
    }

    @Test
    void 스위치가_꺼져_있으면_LLM을_부르지_않는다() {
        AtomicInteger calls = new AtomicInteger();
        InquiryLlmGate gate = gate(b -> {
            calls.incrementAndGet();
            return ok(stock());
        }, false, "key", 4, false);

        assertThat(gate.isOn()).isFalse();
        assertThat(gate.read(1, BODY, LONG)).isNull();
        assertThat(calls).hasValue(0);
    }

    @Test
    void 키가_비어_있거나_공백이면_켜져_있어도_부르지_않는다() {
        AtomicInteger calls = new AtomicInteger();
        InquiryReader reader = b -> {
            calls.incrementAndGet();
            return ok(stock());
        };
        for (String key : new String[] {"", "   ", null}) {
            InquiryLlmGate gate = gate(reader, true, key, 4, false);
            assertThat(gate.isOn()).isFalse();
            assertThat(gate.read(1, BODY, LONG)).isNull();
        }
        assertThat(calls).hasValue(0);
    }

    @Test
    void 정상_읽기는_검증을_거쳐_돌려주고_본문을_그대로_넘긴다() {
        List<String> seen = new ArrayList<>();
        InquiryLlmGate gate = gate(b -> {
            seen.add(b);
            return ok(stock());
        });

        InquiryReading reading = gate.read(1, BODY, LONG);

        assertThat(gate.isOn()).isTrue();
        assertThat(gate.timeout()).isEqualTo(Duration.ofMillis(1500));
        assertThat(seen).containsExactly(BODY);
        assertThat(reading).isNotNull();
        assertThat(reading.intent()).isEqualTo(ChatIntent.STOCK);
        assertThat(reading.size()).isEqualTo("M");
    }

    @Test
    void 남은_시간_안에_안_오면_기다리지_않고_null이고_그_뒤_호출은_계속_된다() {
        CountDownLatch never = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        InquiryLlmGate gate = gate(b -> {
            if (calls.incrementAndGet() == 1) {
                try {
                    never.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return ok(stock());
        });

        long started = System.nanoTime();
        assertThat(gate.read(1, BODY, Duration.ofMillis(400))).isNull();
        // 상한만 본다. 래치가 안 풀리므로 이 시간 안에 돌아왔다면 기다림이 끊긴 것이다
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));

        assertThat(gate.read(1, BODY, LONG)).isNotNull();
        assertThat(calls).hasValue(2);
        never.countDown();
    }

    @Test
    void 남은_시간이_300ms_미만이면_reader를_부르지_않는다() {
        AtomicInteger calls = new AtomicInteger();
        InquiryLlmGate gate = gate(b -> {
            calls.incrementAndGet();
            return ok(stock());
        });

        assertThat(gate.read(1, BODY, InquiryLlmGate.MIN_REMAINING.minusMillis(1))).isNull();
        assertThat(gate.read(1, BODY, Duration.ZERO)).isNull();
        assertThat(gate.read(1, BODY, Duration.ofMillis(-5))).isNull();
        assertThat(calls).hasValue(0);

        assertThat(gate.read(1, BODY, InquiryLlmGate.MIN_REMAINING.plusSeconds(5))).isNotNull();
        assertThat(calls).hasValue(1);
    }

    @Test
    void 동시에_네_건이_차_있으면_호출자가_타임아웃된_뒤에도_포화가_유지되고_풀리면_다시_된다() throws Exception {
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        // 취소의 인터럽트를 무시한다. 실제 SDK 소켓 읽기가 끊기지 않고 끝까지 가는 상황이다
        InquiryLlmGate gate = gate(b -> {
            calls.incrementAndGet();
            started.countDown();
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException ignored) {
                    // 계속 기다린다
                }
            }
            return ok(stock());
        });
        // 공용 풀 병렬도에 기대지 않게 호출자 스레드를 직접 정한다
        ExecutorService callers = Executors.newFixedThreadPool(5);
        try {
            List<Future<InquiryReading>> slow = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                slow.add(callers.submit(() -> gate.read(1, BODY, Duration.ofMillis(400))));
            }
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            // 호출자들은 400ms 뒤 타임아웃으로 null을 받는다. 그래도 워커 네 개는 아직 잡혀 있다
            for (Future<InquiryReading> f : slow) {
                assertThat(f.get(10, TimeUnit.SECONDS)).isNull();
            }

            long begin = System.nanoTime();
            assertThat(callers.submit(() -> gate.read(2, BODY, LONG)).get(10, TimeUnit.SECONDS)).isNull();
            // 풀릴 때까지 기다렸다면 래치 때문에 10초를 넘는다
            assertThat(Duration.ofNanos(System.nanoTime() - begin)).isLessThan(Duration.ofSeconds(5));
            assertThat(calls).hasValue(4);

            release.countDown();
            // 워커가 풀로 돌아오는 시간을 허용한다
            InquiryReading recovered = null;
            long limit = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (recovered == null && System.nanoTime() < limit) {
                recovered = callers.submit(() -> gate.read(3, BODY, LONG)).get(10, TimeUnit.SECONDS);
                if (recovered == null) {
                    Thread.sleep(20);
                }
            }
            assertThat(recovered).isNotNull();
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void reader가_던져도_밖으로_나가지_않고_다음_호출은_된다() {
        AtomicInteger calls = new AtomicInteger();
        InquiryLlmGate gate = gate(b -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("본문이 섞인 메시지 " + b);
            }
            return ok(stock());
        });

        assertThat(gate.read(1, BODY, LONG)).isNull();
        assertThat(gate.read(1, BODY, LONG)).isNotNull();
    }

    @Test
    void reader가_null을_돌려줘도_던지지_않고_null이다() {
        InquiryLlmGate gate = gate(b -> null);

        assertThat(gate.read(1, BODY, LONG)).isNull();
    }

    @Test
    void OK가_아닌_결과_종류는_모두_null이다() {
        for (Kind kind : new Kind[] {Kind.REFUSAL, Kind.MAX_TOKENS, Kind.INVALID, Kind.ERROR, Kind.TIMEOUT}) {
            InquiryLlmGate gate = gate(b -> of(kind));
            assertThat(gate.read(1, BODY, LONG)).as(kind.name()).isNull();
        }
    }

    @Test
    void OK인데_칸이_없거나_검증에_걸리면_null이다() {
        assertThat(gate(b -> ok(null)).read(1, BODY, LONG)).isNull();
        // 사이즈 근거 글자가 본문에 없다
        assertThat(gate(b -> ok(new Fields("STOCK", false, "L", "L", "", "", "", false))).read(1, BODY, LONG))
                .isNull();
        // 유형이 OTHER인데 사이즈가 없다
        assertThat(gate(b -> ok(new Fields("OTHER", false, "NONE", "", "", "", "", false))).read(1, BODY, LONG))
                .isNull();
        // 허용 값 밖 유형
        assertThat(gate(b -> ok(new Fields("BOGUS", false, "M", "M", "", "", "", false))).read(1, BODY, LONG))
                .isNull();
    }

    @Test
    void 불확실하다고_답하면_null이다() {
        InquiryLlmGate gate = gate(b -> ok(new Fields("STOCK", true, "M", "M", "", "", "", false)));

        assertThat(gate.read(1, BODY, LONG)).isNull();
    }

    @Test
    void 미수_풀기_설정이_꺼져_있으면_전체_미수_질문_표시는_false로_바뀐다() {
        Fields asksTotal = new Fields("RECEIVABLE", false, "NONE", "", "", "", "", true);

        InquiryReading off = gate(b -> ok(asksTotal), true, "key", 4, false).read(1, "지금 드릴 돈 얼마예요", LONG);
        InquiryReading on = gate(b -> ok(asksTotal), true, "key", 4, true).read(1, "지금 드릴 돈 얼마예요", LONG);

        assertThat(off).isNotNull();
        assertThat(off.intent()).isEqualTo(ChatIntent.RECEIVABLE);
        assertThat(off.asksOwnOutstandingTotal()).isFalse();
        assertThat(on).isNotNull();
        assertThat(on.asksOwnOutstandingTotal()).isTrue();
    }

    @Test
    void 미수_풀기가_꺼져_있어도_다른_칸은_그대로다() {
        Fields fields = new Fields("STOCK", false, "M", "M", "", "코튼 팬츠", "지난주", true);

        InquiryReading reading = gate(b -> ok(fields)).read(1, "코튼 팬츠 레드 M 구할 수 있을까요?", LONG);

        assertThat(reading).isNotNull();
        assertThat(reading.intent()).isEqualTo(ChatIntent.STOCK);
        assertThat(reading.size()).isEqualTo("M");
        assertThat(reading.productName()).isEqualTo("코튼 팬츠");
        assertThat(reading.hasOrderHint()).isTrue();
        assertThat(reading.asksOwnOutstandingTotal()).isFalse();
    }

    @Test
    void close_뒤에는_부르지_않고_null이며_던지지도_않는다() {
        AtomicInteger calls = new AtomicInteger();
        InquiryLlmGate gate = gate(b -> {
            calls.incrementAndGet();
            return ok(stock());
        });
        assertThat(gate.read(1, BODY, LONG)).isNotNull();

        gate.close();
        gate.close();

        assertThat(gate.read(1, BODY, LONG)).isNull();
        assertThat(calls).hasValue(1);
    }

    @Test
    void 한_번도_안_쓰고_close해도_던지지_않는다() {
        InquiryLlmGate gate = gate(b -> ok(stock()));

        gate.close();
    }

    @Test
    void 첫_사용_전에_close하면_read는_null이고_reader를_부르지_않는다() {
        AtomicInteger calls = new AtomicInteger();
        InquiryLlmGate gate = gate(b -> {
            calls.incrementAndGet();
            return ok(stock());
        });

        gate.close();

        assertThat(gate.read(1, BODY, LONG)).isNull();
        assertThat(calls).hasValue(0);
    }

    @Test
    void reader에는_진입_시각에서_남은_시간을_더한_기한이_넘어간다() {
        long[] seenDeadline = new long[1];
        InquiryReader reader = new InquiryReader() {
            @Override
            public Result read(String body) {
                throw new AssertionError("기한 없는 read는 불리면 안 된다");
            }

            @Override
            public Result read(String body, long deadlineNanos) {
                seenDeadline[0] = deadlineNanos;
                return ok(stock());
            }
        };
        InquiryLlmGate gate = gate(reader);

        long before = System.nanoTime();
        assertThat(gate.read(1, BODY, Duration.ofSeconds(10))).isNotNull();
        long after = System.nanoTime();

        assertThat(seenDeadline[0] - before).isGreaterThanOrEqualTo(Duration.ofSeconds(10).toNanos());
        assertThat(seenDeadline[0] - after).isLessThanOrEqualTo(Duration.ofSeconds(10).toNanos());
    }
}
