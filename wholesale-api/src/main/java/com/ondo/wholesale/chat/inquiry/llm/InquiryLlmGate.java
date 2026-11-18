package com.ondo.wholesale.chat.inquiry.llm;

import com.ondo.wholesale.chat.inquiry.InquiryReading;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * LLM을 부를지와 얼마나 기다릴지를 정한다. 무슨 일이 있어도 던지지 않고, 못 읽으면 null을 돌려 규칙 판정으로 가게 한다.
 *
 * <p>호출은 전용 스레드에서 돌고 요청 스레드는 남은 시간만큼만 기다린다. 스레드는 SDK 호출이 실제로 끝나야 비고 대기열이 없어서,
 * 늦게 끝나는 호출이 동시 한도만큼 쌓이면 그 뒤 호출은 기다리지 않고 바쁨으로 건너뛴다.
 * 스프링이 컨텍스트를 닫을 때 {@link #close()}를 불러 스레드를 내린다. 닫은 뒤에는 실행기를 다시 만들지 않는다.
 */
@Slf4j
@Component
public class InquiryLlmGate implements AutoCloseable {

    /** 이보다 적게 남았으면 부르지 않는다. 어림값이고 5단계 지연 측정 뒤 다시 정한다. */
    public static final Duration MIN_REMAINING = Duration.ofMillis(300);

    private final InquiryReader reader;
    private final boolean on;
    private final String model;
    private final Duration timeout;
    private final int maxConcurrent;
    private final boolean receivableAmount;
    private ExecutorService executor;
    private boolean closed;

    public InquiryLlmGate(InquiryReader reader,
                          @Value("${ondo.chat.inquiry.llm.enabled:false}") boolean enabled,
                          @Value("${ondo.chat.inquiry.llm.api-key:}") String apiKey,
                          @Value("${ondo.chat.inquiry.llm.model:claude-haiku-5-5}") String model,
                          @Value("${ondo.chat.inquiry.llm.timeout:1500ms}") Duration timeout,
                          @Value("${ondo.chat.inquiry.llm.max-concurrent:4}") int maxConcurrent,
                          @Value("${ondo.chat.inquiry.llm.receivable-amount:false}") boolean receivableAmount) {
        this.reader = reader;
        this.on = enabled && apiKey != null && !apiKey.isBlank();
        this.model = model;
        this.timeout = timeout;
        this.maxConcurrent = maxConcurrent;
        this.receivableAmount = receivableAmount;
    }

    /** 꺼져 있으면 보내기 입구는 줄 세우기와 추가 읽기 없이 지금 경로로 간다. */
    public boolean isOn() {
        return on;
    }

    /** 도착부터 트랜잭션 시작까지 쓸 수 있는 전체 시간. 줄 서기, 짧은 읽기, LLM 대기를 합친 한도다. */
    public Duration timeout() {
        return timeout;
    }

    /** 검증을 통과한 읽기. 꺼짐, 바쁨, 지연, 실패, 불확실이면 null이다. */
    public InquiryReading read(long roomId, String body, Duration remaining) {
        if (!on) {
            return null;
        }
        long started = System.nanoTime();
        Future<InquiryReader.Result> call = null;
        try {
            if (remaining.compareTo(MIN_REMAINING) < 0) {
                log(roomId, "busy", started, null, null, null);
                return null;
            }
            // 기한은 진입 때 한 번 정한다. 제출과 검증에 쓴 시간도 남은 시간에서 빠진다
            long deadline = started + remaining.toNanos();
            try {
                call = executor().submit(() -> reader.read(body, deadline));
            } catch (RejectedExecutionException e) {
                log(roomId, "busy", started, null, null, null);
                return null;
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new TimeoutException();
            }
            InquiryReader.Result result = call.get(left, TimeUnit.NANOSECONDS);
            if (result.kind() != InquiryReader.Kind.OK) {
                log(roomId, result.kind().name().toLowerCase(Locale.ROOT), started, result, null, result.error());
                return null;
            }
            InquiryReading reading = InquiryReading.validate(body, result.fields());
            if (reading == null) {
                boolean uncertain = result.fields() != null && Boolean.TRUE.equals(result.fields().uncertain());
                log(roomId, uncertain ? "uncertain" : "invalid", started, result, null, null);
                return null;
            }
            if (System.nanoTime() - deadline > 0) {
                throw new TimeoutException();
            }
            log(roomId, "ok", started, result, reading, null);
            // 판정 함수는 설정을 모른다. 미수 금액 풀기가 꺼져 있으면 그 갈래가 열리지 않게 여기서 지운다
            return receivableAmount ? reading : new InquiryReading(reading.intent(), reading.size(),
                    reading.productName(), reading.hasOrderHint(), false);
        } catch (TimeoutException e) {
            if (call != null) {
                call.cancel(true);
            }
            log(roomId, "timeout", started, null, null, null);
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            log(roomId, "error", started, null, null, e.getClass().getSimpleName());
        } catch (ExecutionException | RuntimeException e) {
            // 가짜나 SDK 밖에서 던진 예외다. 메시지에 본문이 섞일 수 있어 클래스 이름만 남긴다
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            log(roomId, "error", started, null, null, cause.getClass().getSimpleName());
        }
        return null;
    }

    /** 본문, 추출한 글자, 응답 원문은 남기지 않는다. */
    private void log(long roomId, String kind, long started, InquiryReader.Result result, InquiryReading reading,
                     String error) {
        log.info("문의 분류 room={} result={} ms={} model={} in={} out={} intent={} error={}", roomId, kind,
                millis(started), model, result == null ? 0 : result.inputTokens(),
                result == null ? 0 : result.outputTokens(), reading == null ? null : reading.intent(), error);
    }

    private static long millis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private synchronized ExecutorService executor() {
        if (closed) {
            throw new RejectedExecutionException("closed");
        }
        if (executor == null) {
            executor = new ThreadPoolExecutor(maxConcurrent, maxConcurrent, 0L, TimeUnit.MILLISECONDS,
                    new SynchronousQueue<>(), Thread.ofPlatform().name("inquiry-llm-", 0).daemon().factory());
        }
        return executor;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
