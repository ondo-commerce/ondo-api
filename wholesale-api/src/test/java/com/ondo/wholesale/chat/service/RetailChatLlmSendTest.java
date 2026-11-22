package com.ondo.wholesale.chat.service;

import com.ondo.wholesale.chat.domain.ChatIntent;
import com.ondo.wholesale.chat.dto.ChatInquiry;
import com.ondo.wholesale.chat.dto.SendChatMessageRequest;
import com.ondo.wholesale.chat.inquiry.AutoReplySettings;
import com.ondo.wholesale.chat.inquiry.AutoReplySettingsSource;
import com.ondo.wholesale.chat.inquiry.InquiryReading;
import com.ondo.wholesale.chat.inquiry.InquiryReading.Fields;
import com.ondo.wholesale.chat.inquiry.llm.InquiryLlmGate;
import com.ondo.wholesale.chat.inquiry.llm.InquiryReader;
import com.ondo.wholesale.chat.inquiry.llm.InquiryReader.Kind;
import com.ondo.wholesale.chat.inquiry.llm.InquiryReader.Result;
import com.ondo.wholesale.chat.repository.ChatRoomRepository;
import com.ondo.wholesale.chat.repository.ChatRoomRepository.RoomRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 소매 보내기 입구의 순서와 갈래만 본다. 가짜 InquiryReader(람다)와 Mockito 서비스를 쓰고 DB 와 스프링은 없다.
 * 판정 규칙은 LlmInquiryTest, 읽기 검증은 InquiryLlmGateTest, 실제 DB 와 HTTP 는 ChatAutoReplyLlmIntegrationTest 몫이다.
 *
 * <p>잡고 싶은 사고: LLM 을 잠금 앞에서 기다리면 뒤에 보낸 글(칩 등)이 먼저 저장된다. 시간은 흔들리지 않게
 * 래치와 잠금 대기열({@code hasQueuedThreads})로 맞추고, 멈춤은 assertTimeoutPreemptively 로 막는다.
 */
class RetailChatLlmSendTest {

    private static final String KEY = "00000000-0000-4000-8000-000000000001";
    private static final long RETAILER = 7L;
    private static final long ROOM = 1L;
    // 64칸 중 ROOM(1)과 다른 칸
    private static final long OTHER_ROOM = 2L;
    private static final String FREE = "레드 M 구할 수 있을까요?";
    private static final Duration STUCK_LIMIT = Duration.ofSeconds(10);
    private static final InquiryReading READING = new InquiryReading(ChatIntent.STOCK, "M", "", false, false);

    private final List<InquiryLlmGate> gates = new ArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final RetailChatService service = mock(RetailChatService.class);
    private final ChatRoomRepository rooms = mock(ChatRoomRepository.class);
    private final List<String> reads = Collections.synchronizedList(new ArrayList<>());
    private final List<String> sent = Collections.synchronizedList(new ArrayList<>());
    private AutoReplySettings settings = AutoReplySettings.DEFAULTS;

    @AfterEach
    void cleanUp() {
        pool.shutdownNow();
        gates.forEach(InquiryLlmGate::close);
    }

    private static Result ok() {
        return new Result(Kind.OK, new Fields("STOCK", false, "M", "M", "레드", "", "", false), 1, 1, null);
    }

    private RetailChatLlmSend send(boolean enabled, Duration timeout, InquiryReader reader) {
        InquiryLlmGate gate = new InquiryLlmGate(reader, enabled, "k", "m", timeout, 4, false);
        gates.add(gate);
        RoomRow room = mock(RoomRow.class);
        when(room.wholesalerId()).thenReturn(30L);
        when(rooms.findForRetailer(anyLong(), anyLong())).thenReturn(Optional.of(room));
        // service 에 닿은 순서와 그때 넘어온 reading 을 남긴다
        when(service.send(anyLong(), anyLong(), any(), any(), any())).thenAnswer(call -> {
            SendChatMessageRequest request = call.getArgument(3);
            sent.add(request.body() + "|" + (call.getArgument(4) == null ? "null" : "reading"));
            return new RetailChatService.SendOutcome(null, false);
        });
        return new RetailChatLlmSend(service, gate, rooms, id -> settings);
    }

    private RetailChatLlmSend send(InquiryReader reader) {
        return send(true, Duration.ofSeconds(5), reader);
    }

    private InquiryReader counting() {
        return body -> {
            reads.add(body);
            return ok();
        };
    }

    private static SendChatMessageRequest free(String body) {
        return new SendChatMessageRequest(body, null);
    }

    private static SendChatMessageRequest chip(String body) {
        return new SendChatMessageRequest(body, new ChatInquiry(ChatIntent.STOCK, 1L, null, null, "재고", null));
    }

    private void call(RetailChatLlmSend target, long room, SendChatMessageRequest request) {
        target.send(RETAILER, room, KEY, request);
    }

    private void awaitQueued(ReentrantLock lock) throws InterruptedException {
        long end = System.nanoTime() + STUCK_LIMIT.toNanos();
        while (!lock.hasQueuedThreads() && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertThat(lock.hasQueuedThreads()).as("두 번째 요청이 줄에서 기다린다").isTrue();
    }

    @Test
    void 꺼져_있으면_읽기도_방_조회도_없이_바로_서비스를_부른다() {
        for (boolean enabled : new boolean[]{false, true}) {
            // 둘째 바퀴는 키가 빈 값인 경우다
            InquiryLlmGate gate = new InquiryLlmGate(body -> {
                reads.add(body);
                return ok();
            }, enabled, enabled ? " " : "k", "m", Duration.ofSeconds(5), 4, false);
            gates.add(gate);
            RetailChatLlmSend target = new RetailChatLlmSend(service, gate, rooms, id -> settings);

            target.send(RETAILER, ROOM, KEY, free(FREE));
        }
        // enabled=false, 키 공백 둘 다 꺼짐이다
        assertThat(reads).isEmpty();
        verifyNoInteractions(rooms);
        verify(service, org.mockito.Mockito.times(2)).send(RETAILER, ROOM, KEY, free(FREE), null);
    }

    @Test
    void 켜져_있고_거름을_통과하면_읽기가_서비스보다_먼저_불리고_그_읽기가_넘어간다() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        RetailChatLlmSend target = send(body -> {
            order.add("read");
            return ok();
        });
        // when(...) 로 다시 걸면 앞선 답변이 null 인자로 한 번 돈다
        doAnswer(call -> {
            order.add(call.getArgument(4) == null ? "send-null" : "send-reading");
            return new RetailChatService.SendOutcome(null, false);
        }).when(service).send(anyLong(), anyLong(), any(), any(), any());

        call(target, ROOM, free("  " + FREE + "  "));

        assertThat(order).containsExactly("read", "send-reading");
        // 거름은 서비스가 저장할 글자(앞뒤 공백 뺀 것)로 읽는다
        verify(service).send(RETAILER, ROOM, KEY, free("  " + FREE + "  "),
                READING);
    }

    @Test
    void 같은_방에서는_느린_자유_문장_뒤의_칩이_먼저_서비스에_닿지_않는다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RetailChatLlmSend target = send(body -> {
            entered.countDown();
            await(release);
            return ok();
        });
        // 65 도 같은 칸이다. 방이 달라도 칸이 같으면 같은 줄이라는 것까지 함께 본다
        Future<?> slow = pool.submit(() -> call(target, ROOM, free(FREE)));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        Future<?> chip = pool.submit(() -> call(target, ROOM + 64, chip("칩 문의")));

        awaitQueued(target.lockFor(ROOM));
        assertThat(target.lockFor(ROOM + 64)).isSameAs(target.lockFor(ROOM));
        assertThat(sent).as("느린 글이 끝나기 전에는 칩이 서비스에 닿지 않는다").isEmpty();

        release.countDown();
        slow.get(10, TimeUnit.SECONDS);
        chip.get(10, TimeUnit.SECONDS);
        assertThat(sent).containsExactly(FREE + "|reading", "칩 문의|null");
    }

    @Test
    void 다른_칸의_방은_막히지_않는다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RetailChatLlmSend target = send(body -> {
            entered.countDown();
            await(release);
            return ok();
        });
        assertThat(target.lockFor(OTHER_ROOM)).isNotSameAs(target.lockFor(ROOM));
        Future<?> slow = pool.submit(() -> call(target, ROOM, free(FREE)));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        assertTimeoutPreemptively(STUCK_LIMIT, () -> call(target, OTHER_ROOM, chip("다른 방 칩")));

        assertThat(sent).containsExactly("다른 방 칩|null");
        release.countDown();
        slow.get(10, TimeUnit.SECONDS);
    }

    @Test
    void 칩_문의는_읽기를_부르지_않고_null로_보낸다() {
        call(send(counting()), ROOM, chip("칩 문의"));

        assertThat(reads).isEmpty();
        assertThat(sent).containsExactly("칩 문의|null");
        verifyNoInteractions(rooms);
    }

    @Test
    void 사장이_자동_답을_껐거나_세_항목이_다_꺼져_있으면_읽기를_부르지_않는다() {
        RetailChatLlmSend target = send(counting());
        settings = new AutoReplySettings(false, true, true, true);
        call(target, ROOM, free(FREE));
        settings = new AutoReplySettings(true, false, false, false);
        call(target, ROOM, free(FREE));

        assertThat(reads).isEmpty();
        assertThat(sent).containsExactly(FREE + "|null", FREE + "|null");
    }

    @Test
    void 항목_하나라도_켜져_있으면_읽는다() {
        RetailChatLlmSend target = send(counting());
        settings = new AutoReplySettings(true, true, false, false);

        call(target, ROOM, free(FREE));

        assertThat(reads).hasSize(1);
        assertThat(sent).containsExactly(FREE + "|reading");
    }

    @Test
    void 남의_방이면_읽기를_부르지_않고_null로_보낸다() {
        RetailChatLlmSend target = send(counting());
        when(rooms.findForRetailer(anyLong(), anyLong())).thenReturn(Optional.empty());

        call(target, ROOM, free(FREE));

        assertThat(reads).isEmpty();
        assertThat(sent).containsExactly(FREE + "|null");
    }

    @Test
    void worthAsking이_거절하는_글은_읽기를_부르지_않는다() {
        RetailChatLlmSend target = send(counting());
        // 사람 요청, 입금 알림, 긴 숫자열, 빈 본문
        for (String body : List.of("사장님 바꿔주세요", "방금 입금했어요", "010-1234-5678 연락주세요", "   ")) {
            call(target, ROOM, free(body));
        }

        assertThat(reads).isEmpty();
        assertThat(sent).hasSize(4).allMatch(line -> line.endsWith("|null"));
        verifyNoInteractions(rooms);
    }

    @Test
    void 거름에서_RuntimeException이_나도_null로_서비스까지_간다() {
        RetailChatLlmSend target = send(counting());
        when(rooms.findForRetailer(anyLong(), anyLong())).thenThrow(new IllegalStateException("db down"));

        call(target, ROOM, free(FREE));

        // 키가 올바라야 거름이 방 조회까지 간다. 불렸는지 확인해야 키 거름에 걸려 헛통과한 것이 아니다
        verify(rooms).findForRetailer(ROOM, RETAILER);
        assertThat(reads).isEmpty();
        assertThat(sent).containsExactly(FREE + "|null");
        assertThat(target.lockFor(ROOM).isLocked()).isFalse();
    }

    @Test
    void 서비스가_던져도_잠금은_풀리고_같은_방_다음_글이_진행한다() throws Exception {
        RetailChatLlmSend target = send(counting());
        doThrow(new IllegalStateException("boom")).doReturn(new RetailChatService.SendOutcome(null, false))
                .when(service).send(anyLong(), anyLong(), any(), any(), any());

        assertThatThrownBy(() -> call(target, ROOM, free(FREE))).isInstanceOf(IllegalStateException.class);
        assertThat(target.lockFor(ROOM).isLocked()).isFalse();

        // 다른 스레드가 같은 칸을 바로 잡을 수 있어야 한다
        Future<?> next = pool.submit(() -> call(target, ROOM, free(FREE)));
        next.get(10, TimeUnit.SECONDS);
        verify(service, org.mockito.Mockito.times(2)).send(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void 줄_대기_상한을_넘기면_순서_보장_없이_읽기_없이_null로_보내고_상한_근처에서_끝난다() throws Exception {
        RetailChatLlmSend target = send(counting());
        ReentrantLock lock = target.lockFor(ROOM);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        // 다른 스레드가 칸을 쥔다(같은 스레드면 재진입이라 기다리지 않는다)
        Future<?> holder = pool.submit(() -> {
            lock.lock();
            try {
                held.countDown();
                await(letGo);
            } finally {
                lock.unlock();
            }
        });
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        Future<?> waiter = pool.submit(() -> call(target, ROOM, free(FREE)));
        assertTimeoutPreemptively(STUCK_LIMIT, () -> waiter.get());
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        // 줄 대기 상한(QUEUE_LIMIT)은 LLM 예산과 따로 고정이다. 상한 이상 기다리고 5초 한도 안에 끝난다
        assertThat(millis).isGreaterThanOrEqualTo(RetailChatLlmSend.QUEUE_LIMIT.toMillis()).isLessThan(5_000);
        assertThat(reads).isEmpty();
        assertThat(sent).containsExactly(FREE + "|null");
        verifyNoInteractions(rooms);
        letGo.countDown();
        holder.get(10, TimeUnit.SECONDS);
    }

    @Test
    void 앞사람이_LLM_예산을_거의_다_쓰면_순서는_지키고_뒷사람은_읽기_없이_null로_보낸다() throws Exception {
        // 예산 1000ms. 뒷사람이 줄에 선 것을 확인한 뒤, 앞사람 도착 후 800ms 가 지났을 때 앞사람을 푼다.
        // 그때 뒷사람은 잠금을 잡아도 남은 예산이 200ms 이하라 300ms 미만이다. 앞사람은 200ms 여유로 검증을 마친다
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RetailChatLlmSend target = send(true, Duration.ofMillis(1000), body -> {
            reads.add(body);
            entered.countDown();
            await(release);
            // 본문에 사이즈가 없는 문장이라 사이즈 없는 읽기여야 검증을 통과한다
            return new Result(Kind.OK, new Fields("STOCK", false, "NONE", "", "", "", "", false), 1, 1, null);
        });
        long started = System.nanoTime();
        Future<?> front = pool.submit(() -> call(target, ROOM, free("앞 문장")));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        Future<?> back = pool.submit(() -> call(target, ROOM, free("뒤 문장")));
        awaitQueued(target.lockFor(ROOM));
        while (System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(800)) {
            Thread.sleep(5);
        }
        release.countDown();

        assertTimeoutPreemptively(STUCK_LIMIT, () -> {
            front.get();
            back.get();
        });

        assertThat(sent).containsExactly("앞 문장|reading", "뒤 문장|null");
        assertThat(reads).containsExactly("앞 문장");
        // 방 조회는 앞사람 것 한 번뿐이다
        verify(rooms, org.mockito.Mockito.times(1)).findForRetailer(anyLong(), anyLong());
    }

    @Test
    void 키가_없거나_형식이_틀리면_읽기도_방_조회도_없이_원래_키와_null로_서비스를_부른다() {
        RetailChatLlmSend target = send(counting());
        SendChatMessageRequest request = free(FREE);
        for (String key : new String[]{null, "", "   ", "not-a-uuid", "1-1-1-1-1"}) {
            target.send(RETAILER, ROOM, key, request);
            verify(service).send(RETAILER, ROOM, key, request, null);
        }

        assertThat(reads).isEmpty();
        verifyNoInteractions(rooms);
        assertThat(target.lockFor(ROOM).isLocked()).isFalse();
    }

    @Test
    void 클래스와_메서드에_Transactional이_없다() {
        assertThat(hasTransactional(RetailChatLlmSend.class.getAnnotations())).isFalse();
        for (Method method : RetailChatLlmSend.class.getDeclaredMethods()) {
            assertThat(hasTransactional(method.getAnnotations())).as(method.getName()).isFalse();
        }
    }

    private static boolean hasTransactional(java.lang.annotation.Annotation[] annotations) {
        // 스프링과 jakarta 둘 다 이름으로 거른다
        return Arrays.stream(annotations).anyMatch(a -> a.annotationType().getSimpleName().equals("Transactional"));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
