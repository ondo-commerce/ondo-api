package com.ondo.wholesale.chat.service;

import com.ondo.wholesale.chat.dto.SendChatMessageRequest;
import com.ondo.wholesale.chat.inquiry.AutoReplySettings;
import com.ondo.wholesale.chat.inquiry.AutoReplySettingsSource;
import com.ondo.wholesale.chat.inquiry.InquiryReading;
import com.ondo.wholesale.chat.inquiry.LlmInquiry;
import com.ondo.wholesale.chat.inquiry.llm.InquiryLlmGate;
import com.ondo.wholesale.chat.repository.ChatRoomRepository;
import com.ondo.wholesale.chat.repository.ChatRoomRepository.RoomRow;
import com.ondo.wholesale.common.error.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * 소매 보내기의 입구. LLM 분류를 트랜잭션과 방 행 잠금 앞에서 한 번 부르고, 그 읽기를 들고 {@link RetailChatService}로 간다.
 *
 * <p>{@code @Transactional}을 달지 않는다. 달면 LLM을 기다리는 동안 DB 커넥션을 쥔다. 같은 클래스 안 호출은 프록시를
 * 지나지 않아서 서비스와 빈을 나눴다.
 *
 * <p>LLM을 잠금 앞에서 기다리면 뒤에 보낸 글(칩, 거름에 걸린 글)이 먼저 저장될 수 있다. 그래서 같은 방 글은 공정 잠금에
 * 도착 순서대로 줄을 서고, 서비스가 돌아온 뒤에 줄을 푼다. JVM 잠금 다음에 DB 방 잠금을 잡는 순서가 늘 같아 서로 물리지 않는다.
 * 줄 대기 상한({@link #QUEUE_LIMIT})과 LLM 예산({@link InquiryLlmGate#timeout()}, 도착부터 잰다)은 따로다. 줄에서 예산을
 * 다 써도 순서는 지키고 LLM만 건너뛴다. 상한을 넘겨야 줄을 포기한다.
 *
 * <p>한계: 줄은 이 JVM 안에서만 지킨다. 태스크가 둘 이상이면 태스크 사이 순서는 못 지키니 태스크를 늘리기 전에 다시 정한다.
 * 같은 키 다시 보내기도 LLM을 한 번 더 부를 수 있다(저장은 멱등 기록 덕에 한 번이다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetailChatLlmSend {

    /** 방마다 잠금을 만들면 지울 때를 정해야 한다. 고정 칸이라 새지 않고, 다른 방이 같은 칸이면 그만큼 기다릴 뿐이다. */
    private static final int SLOTS = 64;
    /** 소매가 도매를 5초 기다린다. 줄 대기 뒤 자기 DB 처리(어림 500ms, 미측정)와 왕복까지 그 안에 들도록 3.5초로 둔다. */
    static final Duration QUEUE_LIMIT = Duration.ofMillis(3500);

    private final RetailChatService service;
    private final InquiryLlmGate gate;
    private final ChatRoomRepository roomRepository;
    private final AutoReplySettingsSource settingsSource;
    // 공정 잠금이어야 기다린 순서대로 지나간다. 시간 제한 tryLock도 공정성을 지킨다
    private final ReentrantLock[] locks = Stream.generate(() -> new ReentrantLock(true)).limit(SLOTS)
            .toArray(ReentrantLock[]::new);

    public RetailChatService.SendOutcome send(long retailerId, long roomId, String idempotencyKey,
                                              SendChatMessageRequest request) {
        if (!gate.isOn()) {
            return service.send(retailerId, roomId, idempotencyKey, request, null);
        }
        long arrived = System.nanoTime();
        ReentrantLock lock = lockFor(roomId);
        boolean locked = false;
        try {
            locked = lock.tryLock(QUEUE_LIMIT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!locked) {
            log.warn("문의 분류 줄 대기 상한 초과 — 순서 보장 없이 규칙으로 보낸다. room={}", roomId);
            return service.send(retailerId, roomId, idempotencyKey, request, null);
        }
        try {
            return service.send(retailerId, roomId, idempotencyKey, request,
                    read(retailerId, roomId, idempotencyKey, request, arrived));
        } finally {
            lock.unlock();
        }
    }

    /** 같은 방은 늘 같은 칸이다. */
    ReentrantLock lockFor(long roomId) {
        return locks[Math.floorMod(roomId, SLOTS)];
    }

    /**
     * 하나라도 걸리면 null이다. 여기서 난 예외는 삼킨다. 원래 오류(400, 404, 409)는 서비스가 지금과 같은 순서로 낸다.
     * 본문은 서비스가 저장할 글자(앞뒤 공백 뺀 것)로 본다. 그래야 글자 수 거름이 서비스 검사와 같다.
     */
    private InquiryReading read(long retailerId, long roomId, String idempotencyKey, SendChatMessageRequest request,
                                long arrived) {
        try {
            if (remaining(arrived).compareTo(InquiryLlmGate.MIN_REMAINING) < 0) {
                log.info("문의 분류 room={} result=busy — 줄에서 LLM 예산을 다 썼다", roomId);
                return null;
            }
            // 서비스가 400으로 거절할 키는 LLM에 보내지 않는다. 같은 검사를 서비스가 맨 먼저 다시 한다
            ChatCommandService.parseKey(idempotencyKey);
            String body = request.body() == null ? null : request.body().strip();
            if (!LlmInquiry.worthAsking(body, request.inquiry() != null)) {
                return null;
            }
            RoomRow room = roomRepository.findForRetailer(roomId, retailerId).orElse(null);
            if (room == null) {
                return null;
            }
            // 사장이 자동 응답을 끈 도매처의 글은 밖으로 보내지 않는다
            AutoReplySettings settings = settingsSource.forWholesaler(room.wholesalerId());
            if (!settings.enabled() || !(settings.stock() || settings.backorder() || settings.receivable())) {
                return null;
            }
            return gate.read(roomId, body, remaining(arrived));
        } catch (ApiException e) {
            return null;
        } catch (RuntimeException e) {
            log.warn("문의 분류 거름 실패 — 규칙으로 보낸다. room={} error={}", roomId, e.getClass().getSimpleName());
            return null;
        }
    }

    private Duration remaining(long arrived) {
        return gate.timeout().minusNanos(System.nanoTime() - arrived);
    }
}
