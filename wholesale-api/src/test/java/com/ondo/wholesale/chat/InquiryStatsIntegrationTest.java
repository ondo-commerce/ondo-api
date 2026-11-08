package com.ondo.wholesale.chat;

import com.ondo.wholesale.chat.dto.InquiryCounts;
import com.ondo.wholesale.chat.dto.InquiryStats;
import com.ondo.wholesale.chat.service.InquiryStatsService;
import com.ondo.wholesale.common.error.ApiException;
import com.ondo.wholesale.common.error.ErrorCode;
import com.ondo.wholesale.support.ChatFixture;
import com.ondo.wholesale.support.MasterDataFixture;
import com.ondo.wholesale.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static com.ondo.wholesale.security.support.TestSecuritySupport.approvedAs;
import static com.ondo.wholesale.security.support.TestSecuritySupport.pending;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 문의 지표 inquiryStats (MUL-159) — 웹 목 fixture F1~F17({@code mocks/chat/inquiryStats.fixture.ts} @ cdb20a1)을
 * DB 에 그대로 심고, 목 테스트({@code inquiryStats.test.ts})가 <b>손으로 센 숫자</b>와 비교한다. 함수 출력끼리 비교하지 않는다.
 *
 * <p>잡고 싶은 사고: ① 영업일 경계를 자정(KST 또는 UTC)으로 잡는다 — 11:59 와 12:00, 08:59(UTC 자정 직전)와 09:00 을
 * 양쪽에서 본다 ② 결과 줄 시각으로 날을 정한다 ③ asOf 뒤 결과 줄을 센다 ④ 남의 도매처 방을 센다 ⑤ 답+확인을 자동 답
 * 분자에 넣는다. 결과 줄(intent·source_sequence·handoff_reason)이 판정에서 어떻게 채워지는지는 소매 게이트웨이
 * 테스트와 골든셋이 본다 — 여기는 칸이 채워진 줄을 직접 심는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InquiryStatsIntegrationTest extends PostgresTestSupport {

    private static final String 지표 = "/api/wholesale/chat/inquiry-stats";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired InquiryStatsService service;

    private long 무드온;
    private long 라온;

    /** fixture 방 1~3 (목의 F1_F16). F17 방(4)은 asOf 시험에서만 넣는다 — 목도 그 방을 따로 뺀다. */
    @BeforeEach
    void fixture를_심는다() {
        무드온 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-stats@ondo.test", "9500000181");
        라온 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-stats-2@ondo.test", "9500000182");

        long 방1 = ChatFixture.방을_넣는다(jdbc, 무드온, 1L, "봄봄상회", kst("2026-10-01 10:00"));
        소매(방1, "2026-10-28 11:59", "플라워 셔츠 몇 장 남았어요?");                 // 1  F16 — 10/27 영업일
        답(방1, "2026-10-28 11:59", "STOCK", 1);                                     // 2
        소매(방1, "2026-10-28 13:00", "코튼 셔츠 화이트 M 있어요?");                  // 3  F1
        답(방1, "2026-10-28 13:00", "STOCK", 3);                                     // 4
        소매(방1, "2026-10-28 13:05", "블랙 L도 있어요?");                            // 5  F2 답+확인
        답(방1, "2026-10-28 13:05", "STOCK", 5);                                     // 6
        넘김(방1, "2026-10-28 13:05", "STOCK_ZERO", "STOCK", 5);                     // 7
        소매(방1, "2026-10-28 13:10", "조금 깎아 주세요");                            // 8  F3
        넘김(방1, "2026-10-28 13:10", "NEGOTIATION", null, 8);                       // 9
        소매(방1, "2026-10-28 13:20", "만 원만 더 깎아 주세요");                       // 10 F4 결과 없음
        소매(방1, "2026-10-28 13:25", "제 미수 얼마예요?");                           // 11 F5
        답(방1, "2026-10-28 13:25", "RECEIVABLE", 11);                               // 12
        소매(방1, "2026-10-28 13:30", "재고랑 미송 둘 다 알려 주세요");                 // 13 F6
        넘김(방1, "2026-10-28 13:30", "MULTI_INTENT", null, 13);                     // 14
        소매(방1, "2026-10-28 14:00", "이 셔츠 남았어요?");                           // 15 F7 되묻기
        되묻기(방1, "2026-10-28 14:00", 15);                                          // 16
        소매(방1, "2026-10-28 14:01", "레드 / M");                                   // 17 F8 칩 답
        답(방1, "2026-10-28 14:01", "STOCK", 17);                                    // 18
        소매(방1, "2026-10-28 15:00", "이거 있어요?");                                // 19 F9
        되묻기(방1, "2026-10-28 15:00", 19);                                          // 20
        소매(방1, "2026-10-28 15:10", "셔츠 있어요?");                                // 21 F10
        되묻기(방1, "2026-10-28 15:10", 21);                                          // 22
        소매(방1, "2026-10-28 15:12", "아니 미송 언제 와요?");                         // 23 F11
        답(방1, "2026-10-28 15:12", "BACKORDER", 23);                                // 24
        ChatFixture.도매_글을_넣는다(jdbc, 방1, "네 확인해 볼게요", kst("2026-10-28 16:00")); // 25 F15 도매 TEXT
        소매(방1, "2026-10-29 11:59", "플라워 셔츠 블루 S 있어요?");                   // 26 F12 — 10/28 영업일
        답(방1, "2026-10-29 11:59", "STOCK", 26);                                    // 27

        // F14 남의 도매처 방. 도매처 필터를 빼면 retailMessages, autoReplied, STOCK 이 1씩 는다
        long 방2 = ChatFixture.방을_넣는다(jdbc, 라온, 1L, "봄봄상회", kst("2026-10-01 10:00"));
        소매(방2, "2026-10-28 16:00", "블랙 L 재고 있어요?");
        답(방2, "2026-10-28 16:00", "STOCK", 1);

        // F13 10/29 영업일 첫 순간(경계 포함)
        long 방3 = ChatFixture.방을_넣는다(jdbc, 무드온, 2L, "라라상회", kst("2026-10-01 10:00"));
        소매(방3, "2026-10-29 12:00", "니트 조끼 몇 장 있어요?");
        답(방3, "2026-10-29 12:00", "STOCK", 1);
    }

    @Nested
    class 손으로_센_숫자 {

        @Test
        @DisplayName("10/28 하루: 12, 자동 답 5, 답+확인 1, 넘김 2, 되묻기 3, 결과 없음 1, STOCK 4/3, BACKORDER 1/1, "
                + "RECEIVABLE 1/1, OTHER 0/0, UNCLASSIFIED 2/0")
        void 하루_집계가_목_fixture와_같다() {
            InquiryStats stats = service.stats(무드온, "2026-10-28", "2026-10-28", at("2026-10-29 11:59:30"));

            assertThat(stats.total()).isEqualTo(counts(12, 5, 1, 2, 3, 1,
                    pair(4, 3), pair(1, 1), pair(1, 1), pair(0, 0), pair(2, 0)));
            assertThat(stats.days()).hasSize(1);
            assertThat(stats.days().getFirst().counts()).isEqualTo(stats.total());
            assertThat(stats.from()).isEqualTo(LocalDate.parse("2026-10-28"));
            assertThat(stats.to()).isEqualTo(LocalDate.parse("2026-10-28"));
            assertThat(stats.startAt().toInstant()).isEqualTo(at("2026-10-28 12:00:00"));
            assertThat(stats.endAt().toInstant()).isEqualTo(at("2026-10-29 12:00:00"));
            assertThat(stats.asOf().toInstant()).isEqualTo(at("2026-10-29 11:59:30"));
            합_규칙을_지킨다(stats);
        }

        @Test
        void 사흘_집계는_14건_자동_답_7이고_days는_오름차순_1_12_1이다() {
            InquiryStats stats = service.stats(무드온, "2026-10-27", "2026-10-29", at("2026-10-29 13:00:00"));

            assertThat(stats.total().retailMessages()).isEqualTo(14);
            assertThat(stats.total().autoReplied()).isEqualTo(7);
            assertThat(stats.days()).extracting(InquiryStats.InquiryDay::date).containsExactly(
                    LocalDate.parse("2026-10-27"), LocalDate.parse("2026-10-28"), LocalDate.parse("2026-10-29"));
            assertThat(stats.days()).extracting(d -> d.counts().retailMessages()).containsExactly(1, 12, 1);
            합_규칙을_지킨다(stats);
        }

        @Test
        void 메시지가_없는_날도_days에_0으로_있다() {
            InquiryStats stats = service.stats(무드온, "2026-10-25", "2026-10-26", at("2026-10-29 13:00:00"));

            assertThat(stats.days()).extracting(d -> d.counts().retailMessages()).containsExactly(0, 0);
            assertThat(stats.total()).isEqualTo(counts(0, 0, 0, 0, 0, 0,
                    pair(0, 0), pair(0, 0), pair(0, 0), pair(0, 0), pair(0, 0)));
        }
    }

    @Nested
    class 영업일_경계 {

        @Test
        @DisplayName("11:59 는 전날 영업일, 12:00 은 그날 영업일이다 — 10/29 하루만 부르면 F13(12:00)만, F12(11:59)는 없다")
        void 낮_12시가_경계다() {
            InquiryStats d29 = service.stats(무드온, "2026-10-29", "2026-10-29", at("2026-10-29 13:00:00"));
            InquiryStats d28 = service.stats(무드온, "2026-10-28", "2026-10-28", at("2026-10-29 13:00:00"));

            assertThat(d29.total().retailMessages()).isEqualTo(1);   // F13 12:00
            assertThat(d28.total().retailMessages()).isEqualTo(12);  // F12 11:59 포함
        }

        @Test
        @DisplayName("UTC 자정(KST 09:00) 양쪽과 KST 자정 양쪽이 같은 영업일이고, 11:59:59.999999 와 12:00 이 갈린다")
        void 자정은_경계가_아니다() {
            long 해담 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-stats-3@ondo.test", "9500000183");
            long 방 = ChatFixture.방을_넣는다(jdbc, 해담, 9L, "해담상회", kst("2026-10-01 10:00"));
            소매(방, "2026-10-26 23:59", "KST 자정 전");                                   // 10/26
            소매(방, "2026-10-27 00:00", "KST 자정");                                      // 10/26
            ChatFixture.소매_글을_넣는다(jdbc, 방, "UTC 자정 전", OffsetDateTime.parse("2026-10-26T23:59:59Z"));  // 08:59:59 KST
            ChatFixture.소매_글을_넣는다(jdbc, 방, "UTC 자정", OffsetDateTime.parse("2026-10-27T00:00:00Z"));     // 09:00 KST
            ChatFixture.소매_글을_넣는다(jdbc, 방, "정오 직전", OffsetDateTime.parse("2026-10-27T11:59:59.999999+09:00"));
            ChatFixture.소매_글을_넣는다(jdbc, 방, "정오", OffsetDateTime.parse("2026-10-27T12:00:00+09:00"));

            InquiryStats stats = service.stats(해담, "2026-10-26", "2026-10-27", at("2026-10-29 13:00:00"));

            assertThat(stats.days()).extracting(d -> d.counts().retailMessages()).containsExactly(5, 1);
            assertThat(stats.total().noResult()).isEqualTo(6);
        }

        @Test
        @DisplayName("날은 출처 메시지 시각으로 정한다 — 11:59 소매 글에 12:01 에 붙은 답은 전날 영업일의 자동 답이다")
        void 결과_줄_시각이_아니라_출처_시각으로_날을_정한다() {
            long 해담 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-stats-4@ondo.test", "9500000184");
            long 방 = ChatFixture.방을_넣는다(jdbc, 해담, 9L, "해담상회", kst("2026-10-01 10:00"));
            소매(방, "2026-10-27 11:59", "재고 있어요?");
            답(방, "2026-10-27 12:01", "STOCK", 1);

            InquiryStats stats = service.stats(해담, "2026-10-26", "2026-10-27", at("2026-10-29 13:00:00"));

            assertThat(stats.days()).extracting(d -> d.counts().autoReplied()).containsExactly(1, 0);
        }
    }

    @Nested
    class asOf_경계 {

        @BeforeEach
        void F17_방을_넣는다() {
            // 결과 줄이 2분 늦게 붙는다. asOf 16:31 이면 결과 없음, 16:33 이면 자동 답
            long 방4 = ChatFixture.방을_넣는다(jdbc, 무드온, 3L, "다온상회", kst("2026-10-01 10:00"));
            소매(방4, "2026-10-28 16:30", "린넨 팬츠 있어요?");
            답(방4, "2026-10-28 16:32", "STOCK", 1);
        }

        @Test
        void asOf_16시31분이면_F17은_결과_없음이다() {
            InquiryStats stats = service.stats(무드온, "2026-10-28", "2026-10-28", at("2026-10-28 16:31:00"));

            // asOf 뒤에 보낸 F12(10/29 11:59)는 아직 없다 → F1~F11 + F17
            assertThat(stats.total()).isEqualTo(counts(12, 4, 1, 2, 3, 2,
                    pair(3, 2), pair(1, 1), pair(1, 1), pair(0, 0), pair(2, 0)));
            합_규칙을_지킨다(stats);
        }

        @Test
        void asOf_16시33분이면_F17이_자동_답이다() {
            InquiryStats stats = service.stats(무드온, "2026-10-28", "2026-10-28", at("2026-10-28 16:33:00"));

            assertThat(stats.total()).isEqualTo(counts(12, 5, 1, 2, 3, 1,
                    pair(4, 3), pair(1, 1), pair(1, 1), pair(0, 0), pair(2, 0)));
        }

        @Test
        void asOf가_결과_줄과_같은_시각이면_센다() {
            InquiryStats stats = service.stats(무드온, "2026-10-28", "2026-10-28", at("2026-10-28 16:32:00"));

            assertThat(stats.total().autoReplied()).isEqualTo(5);
            assertThat(stats.total().noResult()).isEqualTo(1);
        }

        @Test
        @DisplayName("응답 asOf 로 다시 집계하면 같은 숫자다 — asOf 를 DB 정밀도(µs)로 자르고 그 값으로 센다")
        void 응답_asOf로_다시_세면_같다() {
            Instant nanos = at("2026-10-28 16:32:00").plusNanos(1_500);
            InquiryStats first = service.stats(무드온, "2026-10-28", "2026-10-28", nanos);
            InquiryStats again = service.stats(무드온, "2026-10-28", "2026-10-28", first.asOf().toInstant());

            assertThat(first.asOf().toInstant()).isEqualTo(at("2026-10-28 16:32:00").plusNanos(1_000));
            assertThat(again).isEqualTo(first);
        }
    }

    @Nested
    class 규칙_하나씩 {

        @Test
        void 되묻기와_넘김이_같은_출처면_넘김이고_의도는_넘김_줄_것이다() {
            long 해담 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-stats-5@ondo.test", "9500000185");
            long 방 = ChatFixture.방을_넣는다(jdbc, 해담, 9L, "해담상회", kst("2026-10-01 10:00"));
            소매(방, "2026-10-28 14:00", "이거요");
            되묻기(방, "2026-10-28 14:00", 1);
            넘김(방, "2026-10-28 14:00", "NEGOTIATION", null, 1);

            InquiryCounts c = service.stats(해담, "2026-10-28", "2026-10-28", at("2026-10-28 15:00:00")).total();

            assertThat(List.of(c.handedOff(), c.clarified(), c.byIntent().unclassified().judged())).containsExactly(1, 0, 1);
        }

        @Test
        void 다른_메시지를_가리키는_줄은_이_메시지_결과가_아니다() {
            long 해담 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-stats-6@ondo.test", "9500000186");
            long 방 = ChatFixture.방을_넣는다(jdbc, 해담, 9L, "해담상회", kst("2026-10-01 10:00"));
            소매(방, "2026-10-28 14:00", "하나");
            소매(방, "2026-10-28 14:00", "둘");
            답(방, "2026-10-28 14:00", "STOCK", 2);

            InquiryCounts c = service.stats(해담, "2026-10-28", "2026-10-28", at("2026-10-28 15:00:00")).total();

            assertThat(List.of(c.retailMessages(), c.autoReplied(), c.noResult())).containsExactly(2, 1, 1);
        }

        @Test
        void OFF_넘김은_넘김에_세고_의도는_넘김_줄의_의도다() {
            long 해담 = MasterDataFixture.도매처를_넣는다(jdbc, "chat-stats-7@ondo.test", "9500000187");
            long 방 = ChatFixture.방을_넣는다(jdbc, 해담, 9L, "해담상회", kst("2026-10-01 10:00"));
            소매(방, "2026-10-28 14:00", "미송 언제 와요?");
            넘김(방, "2026-10-28 14:00", "OFF", "BACKORDER", 1);

            InquiryCounts c = service.stats(해담, "2026-10-28", "2026-10-28", at("2026-10-28 15:00:00")).total();

            assertThat(c.handedOff()).isEqualTo(1);
            assertThat(c.byIntent().backorder()).isEqualTo(pair(1, 0));
        }
    }

    @Nested
    class 기간_검사 {

        @Test
        @DisplayName("from·to 가 없으면 오늘 영업일 하루다 — 11:59 에 부르면 어제, 12:00 에 부르면 오늘")
        void 기본값은_오늘_영업일이다() {
            InquiryStats before = service.stats(무드온, null, null, at("2026-10-29 11:59:59"));
            InquiryStats after = service.stats(무드온, null, null, at("2026-10-29 12:00:00"));

            assertThat(before.from()).isEqualTo(LocalDate.parse("2026-10-28"));
            assertThat(before.to()).isEqualTo(LocalDate.parse("2026-10-28"));
            assertThat(after.from()).isEqualTo(LocalDate.parse("2026-10-29"));
            assertThat(after.days()).hasSize(1);
        }

        @Test
        void to가_오늘_영업일_뒤면_400_field_to다_낮_12시_전후로_갈린다() {
            필드_오류(() -> service.stats(무드온, "2026-10-29", null, at("2026-10-29 11:59:59")), "to");
            assertThat(service.stats(무드온, "2026-10-29", null, at("2026-10-29 12:00:00")).to())
                    .isEqualTo(LocalDate.parse("2026-10-29"));
        }

        @Test
        void from이_to보다_늦으면_400_field_from이다() {
            필드_오류(() -> service.stats(무드온, "2026-10-28", "2026-10-27", at("2026-10-29 13:00:00")), "from");
            // to 만 주면 from 은 오늘 영업일 — 그보다 이른 to 는 from > to
            필드_오류(() -> service.stats(무드온, null, "2026-10-27", at("2026-10-29 13:00:00")), "from");
        }

        @Test
        void 날짜_형식이_아니거나_없는_날이면_400이다() {
            필드_오류(() -> service.stats(무드온, "2026/10/28", null, at("2026-10-29 13:00:00")), "from");
            필드_오류(() -> service.stats(무드온, "2026-10-01", "2026-02-30", at("2026-10-29 13:00:00")), "to");
            필드_오류(() -> service.stats(무드온, "", null, at("2026-10-29 13:00:00")), "from");
        }

        @Test
        void 최대_62일이다() {
            assertThat(service.stats(무드온, "2026-08-29", "2026-10-29", at("2026-10-29 13:00:00")).days()).hasSize(62);
            필드_오류(() -> service.stats(무드온, "2026-08-28", "2026-10-29", at("2026-10-29 13:00:00")), "to");
        }
    }

    @Nested
    class HTTP {

        @Test
        void data_봉투와_계약_칸_이름으로_나가고_시각은_UTC다() throws Exception {
            mvc.perform(get(지표).param("from", "2026-10-28").param("to", "2026-10-28").with(approvedAs(무드온)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.from").value("2026-10-28"))
                    .andExpect(jsonPath("$.data.to").value("2026-10-28"))
                    .andExpect(jsonPath("$.data.startAt").value("2026-10-28T03:00:00Z"))
                    .andExpect(jsonPath("$.data.endAt").value("2026-10-29T03:00:00Z"))
                    .andExpect(jsonPath("$.data.asOf").exists())
                    .andExpect(jsonPath("$.data.total.retailMessages").value(12))
                    .andExpect(jsonPath("$.data.total.autoReplied").value(5))
                    .andExpect(jsonPath("$.data.total.autoRepliedWithHandoff").value(1))
                    .andExpect(jsonPath("$.data.total.handedOff").value(2))
                    .andExpect(jsonPath("$.data.total.clarified").value(3))
                    .andExpect(jsonPath("$.data.total.noResult").value(1))
                    .andExpect(jsonPath("$.data.total.byIntent.STOCK.judged").value(4))
                    .andExpect(jsonPath("$.data.total.byIntent.STOCK.autoReplied").value(3))
                    .andExpect(jsonPath("$.data.total.byIntent.OTHER.judged").value(0))
                    .andExpect(jsonPath("$.data.total.byIntent.UNCLASSIFIED.judged").value(2))
                    .andExpect(jsonPath("$.data.days.length()").value(1))
                    .andExpect(jsonPath("$.data.days[0].date").value("2026-10-28"))
                    .andExpect(jsonPath("$.data.days[0].counts.retailMessages").value(12));
        }

        @Test
        void 미래_to와_형식_오류는_400_VALIDATION_FAILED다() throws Exception {
            mvc.perform(get(지표).param("from", "2026-10-28").param("to", "2099-01-01").with(approvedAs(무드온)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("to"));
            mvc.perform(get(지표).param("from", "20261028").with(approvedAs(무드온)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("from"));
        }

        @Test
        void 승인_전_계정은_403_NOT_APPROVED다() throws Exception {
            mvc.perform(get(지표).with(pending()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("NOT_APPROVED"));
        }
    }

    /* --- 도우미 ---------------------------------------------------------------- */

    private static OffsetDateTime kst(String local) {
        String withSeconds = local.length() == 16 ? local + ":00" : local;
        return OffsetDateTime.parse(withSeconds.replace(' ', 'T') + "+09:00");
    }

    private static Instant at(String local) {
        return kst(local).toInstant();
    }

    private void 소매(long roomId, String local, String body) {
        ChatFixture.소매_글을_넣는다(jdbc, roomId, body, kst(local));
    }

    private void 답(long roomId, String local, String intent, long replyTo) {
        ChatFixture.자동_답을_넣는다(jdbc, roomId, "자동 답", intent, replyTo, null, kst(local));
    }

    private void 되묻기(long roomId, String local, long replyTo) {
        ChatFixture.되묻기를_넣는다(jdbc, roomId, "어떤 색상, 사이즈요?", "STOCK", replyTo, kst(local));
    }

    private void 넘김(long roomId, String local, String reason, String intent, long source) {
        ChatFixture.넘김_줄을_넣는다(jdbc, roomId, "자동 답변이 답하지 못했어요. 직접 답해 주세요", reason, intent, source,
                kst(local));
    }

    private static InquiryCounts.IntentCounts pair(int judged, int autoReplied) {
        return new InquiryCounts.IntentCounts(judged, autoReplied);
    }

    private static InquiryCounts counts(int retail, int auto, int autoWithHandoff, int handedOff, int clarified,
                                        int noResult, InquiryCounts.IntentCounts stock,
                                        InquiryCounts.IntentCounts backorder, InquiryCounts.IntentCounts receivable,
                                        InquiryCounts.IntentCounts other, InquiryCounts.IntentCounts unclassified) {
        return new InquiryCounts(retail, auto, autoWithHandoff, handedOff, clarified, noResult,
                new InquiryCounts.ByIntent(stock, backorder, receivable, other, unclassified));
    }

    /** 모든 응답이 지키는 합 규칙(목 expectSums). */
    private static void 합_규칙을_지킨다(InquiryStats stats) {
        List<InquiryCounts> all = new java.util.ArrayList<>(List.of(stats.total()));
        stats.days().forEach(d -> all.add(d.counts()));
        for (InquiryCounts c : all) {
            assertThat(c.autoReplied() + c.autoRepliedWithHandoff() + c.handedOff() + c.clarified() + c.noResult())
                    .isEqualTo(c.retailMessages());
            InquiryCounts.ByIntent b = c.byIntent();
            List<InquiryCounts.IntentCounts> intents = List.of(b.stock(), b.backorder(), b.receivable(), b.other(),
                    b.unclassified());
            assertThat(intents.stream().mapToInt(InquiryCounts.IntentCounts::judged).sum())
                    .isEqualTo(c.autoReplied() + c.autoRepliedWithHandoff() + c.handedOff());
            assertThat(intents.stream().mapToInt(InquiryCounts.IntentCounts::autoReplied).sum())
                    .isEqualTo(c.autoReplied());
        }
        assertThat(stats.days().stream().mapToInt(d -> d.counts().retailMessages()).sum())
                .isEqualTo(stats.total().retailMessages());
    }

    private static void 필드_오류(Runnable call, String field) {
        ApiException e = catchThrowableOfType(ApiException.class, call::run);
        assertThat(e).as("400 이어야 한다").isNotNull();
        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(e.errors()).extracting(com.ondo.wholesale.common.error.ErrorResponse.FieldError::field)
                .containsExactly(field);
    }
}
