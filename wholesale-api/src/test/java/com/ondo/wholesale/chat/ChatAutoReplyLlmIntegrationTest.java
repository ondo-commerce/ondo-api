package com.ondo.wholesale.chat;

import com.ondo.wholesale.chat.inquiry.InquiryReading;
import com.ondo.wholesale.chat.inquiry.InquiryReading.Fields;
import com.ondo.wholesale.chat.inquiry.llm.InquiryReader;
import com.ondo.wholesale.chat.inquiry.llm.InquiryReader.Kind;
import com.ondo.wholesale.chat.inquiry.llm.InquiryReader.Result;
import com.ondo.wholesale.support.ChatFixture;
import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LLM 을 켠 상태의 소매 보내기 통합 검증 — 소매 게이트웨이 → RetailChatLlmSend → 서비스 → 자동 답 (MUL-157).
 *
 * <p>InquiryReader 는 가짜(@MockitoBean)라 네트워크로 나가지 않는다. 판정 규칙은 LlmInquiryTest 와 골든셋이,
 * 줄 서기 순서는 RetailChatLlmSendTest 가 본다. 여기는 켠 상태가 실제 DB 원본과 HTTP 응답까지 이어지는지,
 * 가짜가 던지거나 늦어도 보내기가 201 이고 규칙 결과가 남는지 본다. 꺼진 기본 상태는 ChatAutoReplyLlmOffIntegrationTest 몫이다.
 *
 * <p>켠 상태 테스트는 이 클래스 하나에 모아 컨텍스트를 하나만 더 만든다. 클래스는 @Transactional 이라 테스트마다 롤백하지만,
 * "LLM 을 기다리는 동안 방 행이 잠겨 있지 않다"만은 다른 커넥션에서 봐야 해서 트랜잭션 없이 돌고 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "ondo.chat.inquiry.llm.enabled=true",
        "ondo.chat.inquiry.llm.api-key=test-key",
        "ondo.chat.inquiry.llm.timeout=2000ms"})
class ChatAutoReplyLlmIntegrationTest extends PostgresTestSupport {

    private static final String 방들 = "/api/retail-gateway/chat/rooms";
    private static final long 봄봄 = 8601L;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    // 규칙은 못 읽고(NO_INTENT) LLM 이 재고로 읽으면 답이 나오는 문장
    private static final String 자유문장 = "블랙 M 구할 수 있을까요?";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @MockitoBean InquiryReader reader;

    private long 무드온;
    private long 셔츠;
    private long 블랙M;

    @BeforeEach
    void 도매처와_게시글을_심는다() {
        // 트랜잭션 없는 테스트(방 잠금)는 자기 픽스처를 직접 넣는다. 여기서 넣으면 커밋돼 남는다
        if (!TestTransaction.isActive()) {
            return;
        }
        무드온 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-llm@ondo.test", "9500000170");
        jdbc.update("update wholesale.wholesaler set approval_status = 'APPROVED', biz_name = '무드온' where id = ?",
                무드온);
        long 리프 = MasterDataFixture.카테고리_리프를_넣는다(jdbc, 9580);
        long 블랙 = MasterDataFixture.색상을_넣는다(jdbc, 9580, 9580);
        long product = jdbc.queryForObject("""
                insert into wholesale.product (wholesaler_id, product_number, name, category_id)
                values (?, 1, '린넨 셔츠', ?) returning id
                """, Long.class, 무드온, 리프);
        long colorOption = jdbc.queryForObject(
                "insert into wholesale.color_option (product_id, color_id) values (?, ?) returning id",
                Long.class, product, 블랙);
        블랙M = jdbc.queryForObject("""
                insert into wholesale.variant (color_option_id, product_id, size, variant_seq, stock_qty, reserved_qty)
                values (?, ?, 'M', 1, 45, 5) returning id
                """, Long.class, colorOption, product);   // 판매가능 40 → 10~49장
        셔츠 = jdbc.queryForObject("insert into wholesale.listing (product_id, title) values (?, '린넨 셔츠') returning id",
                Long.class, product);
        jdbc.update("insert into wholesale.listing_variant (listing_id, variant_id, sale_price, order_limit)"
                + " values (?, ?, 10000, 500)", 셔츠, 블랙M);
    }

    @Test
    void LLM이_재고로_읽으면_구간만_있고_수량_칸이_없고_generatedBy가_LLM이다() throws Exception {
        long roomId = 방을_연다();
        when(reader.read(anyString(), anyLong())).thenReturn(재고읽기());

        String raw = 보낸다(roomId, 자유문장)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.generatedMessages.length()").value(1))
                .andExpect(jsonPath("$.data.generatedMessages[0].kind").value("AUTO_REPLY"))
                .andExpect(jsonPath("$.data.generatedMessages[0].body")
                        .value("블랙 M은 10~49장 있어요. 지금 주문할 수 있어요(한 번에 최대 500장)"))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.generatedBy").value("LLM"))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.facts.stock.band").value("UNDER_50"))
                .andReturn().getResponse().getContentAsString();

        verify(reader).read(eq(자유문장), anyLong());
        assertThat(raw).doesNotContain("availableQty");
        assertThat(jdbc.queryForObject("select auto_reply ->> 'generatedBy' from wholesale.chat_message"
                + " where room_id = ? and sequence = 2", String.class, roomId)).isEqualTo("LLM");
        소매_글과_멱등_기록이_한_건씩_남았다(roomId);
    }

    @Test
    void 규칙이_답했는데_검증을_통과한_읽기가_다른_유형이면_사장님께_넘긴다() throws Exception {
        long roomId = 방을_연다();
        // 본문에 사이즈가 없고 유형만 미송으로 읽은 값이다. 검증은 통과하지만 규칙(재고)과 의견이 달라 넘긴다
        Fields conflict = new Fields("BACKORDER", false, "NONE", "", "", "", "", false);
        assertThat(InquiryReading.validate("블랙 M 몇 장 남았어요?", conflict)).as("검증을 통과하는 읽기").isNotNull();
        when(reader.read(anyString(), anyLong())).thenReturn(new Result(Kind.OK, conflict, 10, 5, null));

        보낸다(roomId, "블랙 M 몇 장 남았어요?")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.generatedMessages[0].kind").value("HANDOFF"));

        verify(reader).read(eq("블랙 M 몇 장 남았어요?"), anyLong());
        assertThat(jdbc.queryForObject("select handoff_reason from wholesale.chat_message"
                + " where room_id = ? and kind = 'HANDOFF'", String.class, roomId)).isEqualTo("MULTI_INTENT");
    }

    @Test
    void 규칙과_같은_결론의_유효한_읽기는_답은_그대로이고_generatedBy는_LLM이다() throws Exception {
        long roomId = 방을_연다();
        // 계획 4-1 추천 1번: 읽기가 있었고 최종 결과가 LLM 해석의 결과와 같으면 LLM(규칙만으로도 같은 답이 나오는 문장 포함)
        Fields same = new Fields("STOCK", false, "M", "M", "블랙", "", "", false);
        assertThat(InquiryReading.validate("블랙 M 몇 장 남았어요?", same)).as("검증을 통과하는 읽기").isNotNull();
        when(reader.read(anyString(), anyLong())).thenReturn(new Result(Kind.OK, same, 10, 5, null));

        보낸다(roomId, "블랙 M 몇 장 남았어요?")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.generatedMessages[0].body")
                        .value("블랙 M은 10~49장 있어요. 지금 주문할 수 있어요(한 번에 최대 500장)"))
                .andExpect(jsonPath("$.data.generatedMessages[0].autoReply.generatedBy").value("LLM"));
    }

    @Test
    void LLM이_던져도_201이고_소매_글과_규칙_결과_줄이_남는다() throws Exception {
        long roomId = 방을_연다();
        when(reader.read(anyString(), anyLong())).thenThrow(new IllegalStateException("boom"));

        규칙_결과만_붙는다(roomId);
    }

    @Test
    void LLM이_ERROR를_내도_201이고_소매_글과_규칙_결과_줄이_남는다() throws Exception {
        long roomId = 방을_연다();
        when(reader.read(anyString(), anyLong())).thenReturn(new Result(Kind.ERROR, null, 0, 0, "SomeException"));

        규칙_결과만_붙는다(roomId);
    }

    @Test
    void LLM이_타임아웃보다_오래_붙잡아도_기다리지_않고_201이고_규칙_결과가_남는다() throws Exception {
        long roomId = 방을_연다();
        CountDownLatch never = new CountDownLatch(1);
        when(reader.read(anyString(), anyLong())).thenAnswer(call -> {
            // 게이트가 취소하면 interrupt 로 풀린다
            never.await(30, TimeUnit.SECONDS);
            return 재고읽기();
        });

        long started = System.nanoTime();
        규칙_결과만_붙는다(roomId);

        // 타임아웃(2초) 근처에서 끝나야 한다. 소매 서버 한도 5초보다 한참 안쪽
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(4_500);
    }

    @Test
    void 사장이_자동_답을_끈_도매처면_LLM을_부르지_않고_OFF_넘김이다() throws Exception {
        long roomId = 방을_연다();
        jdbc.update("""
                insert into wholesale.chat_auto_reply_setting
                    (wholesaler_id, enabled, stock_enabled, backorder_enabled, receivable_enabled)
                values (?, false, true, true, true)
                """, 무드온);

        보낸다(roomId, 자유문장).andExpect(status().isCreated());

        verify(reader, never()).read(anyString(), anyLong());
        assertThat(jdbc.queryForObject("select handoff_reason from wholesale.chat_message"
                + " where room_id = ? and kind = 'HANDOFF'", String.class, roomId)).isEqualTo("OFF");
    }

    @Test
    void 칩으로_온_문의는_LLM을_부르지_않는다() throws Exception {
        long roomId = 방을_연다();

        mvc.perform(post(방들 + "/" + roomId + "/messages").param("retailerId", String.valueOf(봄봄))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + 자유문장 + "\",\"inquiry\":{\"intent\":\"STOCK\",\"listingId\":" + 셔츠
                                + ",\"variantId\":null,\"backorderId\":null,\"label\":\"재고\",\"notListed\":null}}"))
                .andExpect(status().isCreated());

        verify(reader, never()).read(anyString(), anyLong());
    }

    /**
     * LLM 을 기다리는 동안 방 행 FOR UPDATE 를 쥐지 않는다. 쥐고 있으면 사장이 같은 방에 답하는 동안 소매 보내기가,
     * 반대로 소매 보내기가 느린 LLM 을 기다리는 동안 사장 답이 막힌다. 가짜 읽기가 다른 커넥션에서 nowait 로 잡아 보고,
     * 그 검사가 끝난 뒤에야 답한다(검사 도중 LLM 대기가 끝나 헛통과하지 않게). 대조군은 PostgreSQL 잠금 충돌(55P03)이다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void LLM을_기다리는_동안_방_행이_잠겨_있지_않다() throws Throwable {
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            커밋된_방으로("chat-llm-lock@ondo.test", "9500000171", roomId -> {
                // 대조군: 방 행을 쥔 트랜잭션이 있으면 다른 커넥션의 nowait 는 잠금 충돌(55P03)로 실패한다
                new TransactionTemplate(txManager).executeWithoutResult(status -> {
                    jdbc.queryForObject("select id from wholesale.chat_room where id = ? for update", Long.class, roomId);
                    assertThat(잠금_결과(other, roomId)).isEqualTo("55P03");
                });
                assertThat(잠금_결과(other, roomId)).as("쥐는 쪽이 없으면 잡힌다").isEqualTo("acquired");

                AtomicReference<String> during = new AtomicReference<>("not-run");
                CountDownLatch checked = new CountDownLatch(1);
                when(reader.read(anyString(), anyLong())).thenAnswer(call -> {
                    try {
                        during.set(잠금_결과(other, roomId));
                    } finally {
                        checked.countDown();
                    }
                    return 재고읽기();
                });

                보낸다(roomId, 자유문장).andExpect(status().isCreated());

                assertThat(checked.await(10, TimeUnit.SECONDS)).as("가짜 읽기 안의 검사가 끝났다").isTrue();
                assertThat(during.get()).as("LLM 을 기다리는 동안 방 행 잠금이 잡힌다").isEqualTo("acquired");
            });
        } finally {
            other.shutdownNow();
        }
    }

    /**
     * 같은 방 두 글의 저장 순서가 도착 순서와 같다. 첫 글은 가짜가 붙잡고, 그사이 도착해 줄에 선 것을 확인한 뒤 풀어 준다. 둘째 글(사람 요청 문장이라 LLM 에 안 간다)은 줄에서 기다렸다가 뒤에 저장된다. 줄이 없으면 둘째 글이 sequence 1 이 된다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 같은_방에서_느린_LLM_글_뒤에_온_글은_도착_순서대로_저장된다() throws Throwable {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            커밋된_방으로("chat-llm-order@ondo.test", "9500000173", roomId -> {
                CountDownLatch entered = new CountDownLatch(1);
                CountDownLatch hold = new CountDownLatch(1);
                when(reader.read(anyString(), anyLong())).thenAnswer(call -> {
                    entered.countDown();
                    hold.await(30, TimeUnit.SECONDS);   // 게이트가 타임아웃 뒤 취소(interrupt)하면 풀린다
                    return 재고읽기();
                });

                Future<?> first = pool.submit(() -> {
                    보낸다(roomId, 자유문장).andExpect(status().isCreated());
                    return null;
                });
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                AtomicReference<Thread> secondThread = new AtomicReference<>();
                Future<?> second = pool.submit(() -> {
                    secondThread.set(Thread.currentThread());
                    보낸다(roomId, "사장님 바꿔주세요").andExpect(status().isCreated());
                    return null;
                });
                // 둘째 요청이 RetailChatLlmSend.send 안 잠금 대기(TIMED_WAITING)에 선 것을 보고서야 첫 읽기를 푼다.
                // 줄이 없으면 둘째는 곧장 서비스로 가서 이 대기가 끝나지 않고 테스트가 실패한다
                assertThat(줄에_섰다(secondThread)).as("둘째 요청이 줄에서 기다린다").isTrue();
                hold.countDown();
                first.get(15, TimeUnit.SECONDS);
                second.get(15, TimeUnit.SECONDS);

                List<String> order = jdbc.queryForList("select body from wholesale.chat_message"
                        + " where room_id = ? and sender = 'RETAILER' order by sequence", String.class, roomId);
                assertThat(order).containsExactly(자유문장, "사장님 바꿔주세요");
            });
        } finally {
            pool.shutdownNow();
        }
    }

    /* --- 도우미 ---------------------------------------------------------------- */

    /** 커밋되는 도매처와 방을 만들어 본문을 돌리고, 본문이 끝나면(실패나 생성 중간 실패 포함) 각 트랜잭션으로 지운다. */
    private void 커밋된_방으로(String email, String bizRegNo, ThrowingConsumer<Long> body) throws Throwable {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            long roomId = tx.execute(status -> {
                long wholesaler = MasterDataFixture.도매처를_넣는다(jdbc, email, bizRegNo);
                return ChatFixture.방을_넣는다(jdbc, wholesaler, 봄봄, "잠금상회");
            });
            body.accept(roomId);
        } finally {
            // 넣은 반대 순서: 멱등 기록, 줄(방 머리의 마지막 줄 참조를 먼저 끊고), 방, 도매처. 이메일로 찾아 중간 실패에도 돈다
            tx.executeWithoutResult(status -> {
                for (Long wholesaler : jdbc.queryForList("select id from wholesale.wholesaler where email = ?",
                        Long.class, email)) {
                    for (Long room : jdbc.queryForList("select id from wholesale.chat_room where wholesaler_id = ?",
                            Long.class, wholesaler)) {
                        jdbc.update("delete from wholesale.chat_send_idempotency where room_id = ?", room);
                        jdbc.update("update wholesale.chat_room set last_message_id = null where id = ?", room);
                        jdbc.update("delete from wholesale.chat_message where room_id = ?", room);
                        jdbc.update("delete from wholesale.chat_room where id = ?", room);
                    }
                    jdbc.update("delete from wholesale.wholesaler where id = ?", wholesaler);
                }
            });
        }
    }

    /** 그 스레드가 RetailChatLlmSend.send 안에서 시간 제한 대기(잠금 줄)에 서 있는지 스택으로 본다. 리플렉션이 필요 없다. */
    private static boolean 줄에_섰다(AtomicReference<Thread> holder) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            Thread thread = holder.get();
            if (thread != null && thread.getState() == Thread.State.TIMED_WAITING
                    && java.util.Arrays.stream(thread.getStackTrace()).anyMatch(f ->
                    f.getClassName().endsWith(".RetailChatLlmSend") && f.getMethodName().equals("send"))) {
                return true;
            }
            Thread.sleep(5);
        }
        return false;
    }

    /** 다른 스레드(다른 커넥션)에서 방 행에 nowait 잠금을 건다. 잡히면 "acquired", 아니면 PostgreSQL SQLState 다. */
    private String 잠금_결과(ExecutorService other, long roomId) {
        try {
            return other.submit(() -> {
                try {
                    new TransactionTemplate(txManager).executeWithoutResult(status ->
                            jdbc.queryForObject("select id from wholesale.chat_room where id = ? for update nowait",
                                    Long.class, roomId));
                    return "acquired";
                } catch (RuntimeException e) {
                    Throwable cause = e;
                    while (cause != null && !(cause instanceof SQLException)) {
                        cause = cause.getCause();
                    }
                    return cause == null ? e.getClass().getName() : ((SQLException) cause).getSQLState();
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void 규칙_결과만_붙는다(long roomId) throws Exception {
        // 규칙은 이 문장을 못 읽는다 → NO_INTENT 넘김 한 줄. 가짜가 실패해도 이 결과가 그대로다
        보낸다(roomId, 자유문장)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.message.sender").value("RETAILER"))
                .andExpect(jsonPath("$.data.generatedMessages.length()").value(1))
                .andExpect(jsonPath("$.data.generatedMessages[0].kind").value("HANDOFF"));

        verify(reader).read(eq(자유문장), anyLong());
        assertThat(jdbc.queryForObject("select handoff_reason from wholesale.chat_message"
                + " where room_id = ? and sequence = 2", String.class, roomId)).isEqualTo("NO_INTENT");
        소매_글과_멱등_기록이_한_건씩_남았다(roomId);
    }

    private void 소매_글과_멱등_기록이_한_건씩_남았다(long roomId) {
        assertThat(jdbc.queryForObject("select count(*) from wholesale.chat_message"
                + " where room_id = ? and sender = 'RETAILER' and kind = 'TEXT'", Integer.class, roomId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from wholesale.chat_send_idempotency where room_id = ?",
                Integer.class, roomId)).isEqualTo(1);
    }

    private static Result 재고읽기() {
        return ok("STOCK", "M", "M");
    }

    private static Result ok(String intent, String size, String evidence) {
        return new Result(Kind.OK, new Fields(intent, false, size, evidence, "블랙", "", "", false), 10, 5, null);
    }

    private ResultActions 열기() throws Exception {
        return mvc.perform(post(방들).contentType(MediaType.APPLICATION_JSON).content(
                "{\"retailerId\":%d,\"retailerName\":\"봄봄상회\",\"wholesalerId\":%d,\"listingId\":%d}"
                        .formatted(봄봄, 무드온, 셔츠)));
    }

    private long 방을_연다() throws Exception {
        String raw = 열기().andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(raw).get("data").get("room").get("roomId").asLong();
    }

    private ResultActions 보낸다(long roomId, String body) throws Exception {
        return mvc.perform(post(방들 + "/" + roomId + "/messages").param("retailerId", String.valueOf(봄봄))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"%s\",\"inquiry\":null}".formatted(body)));
    }
}
