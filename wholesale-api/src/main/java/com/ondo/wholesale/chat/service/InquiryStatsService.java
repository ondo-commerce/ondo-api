package com.ondo.wholesale.chat.service;

import com.ondo.wholesale.chat.domain.ChatIntent;
import com.ondo.wholesale.chat.dto.InquiryCounts;
import com.ondo.wholesale.chat.dto.InquiryStats;
import com.ondo.wholesale.chat.repository.InquiryStatsRepository;
import com.ondo.wholesale.chat.repository.InquiryStatsRepository.SourceRow;
import com.ondo.wholesale.common.error.ApiException;
import com.ondo.wholesale.dashboard.BusinessDay;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 문의 지표 (MUL-159, 초안 op {@code inquiryStats}). 규칙 원본은 웹 목 {@code mocks/chat/inquiryStats.ts} @ cdb20a1.
 *
 * <ul>
 *   <li>세는 단위: 내 도매처 방의 소매 TEXT 한 건 = 결과 하나. 우선순위 자동 답 &gt; 넘김 &gt; 되묻기 &gt; 결과 없음.
 *       AUTO_REPLY 와 HANDOFF 가 둘 다면 autoRepliedWithHandoff(재고 0, 미송 입고일 미정)</li>
 *   <li>의도: 자동 답(답+확인 포함)이면 AUTO_REPLY 줄 intent, 넘김만이면 HANDOFF 줄 intent, null 은 UNCLASSIFIED.
 *       되묻기·결과 없음은 판정 끝남이 아니라 byIntent 에 안 들어간다</li>
 *   <li>기간: 영업일 D = [D 12:00, D+1 12:00) Asia/Seoul({@link BusinessDay}, 대시보드와 같은 경계). 날은 출처
 *       sentAt 으로 정한다(결과 줄 시각이 아니다)</li>
 *   <li>asOf(집계 시각) 뒤에 쓰인 결과 줄은 안 센다(이하는 센다). DB 정밀도(µs)로 자른다 — 응답 asOf 와 집계에 쓴
 *       값이 같아야 다시 불렀을 때 같은 숫자가 나온다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class InquiryStatsService {

    static final int MAX_DAYS = 62;

    private final InquiryStatsRepository repository;

    /**
     * @param fromRaw 첫 영업일(YYYY-MM-DD). 없으면 오늘 영업일
     * @param toRaw   마지막 영업일(포함). 없으면 from
     * @param now     집계 시각. "오늘 영업일"도 이 값으로 정한다
     */
    @Transactional(readOnly = true)
    public InquiryStats stats(long wholesalerId, String fromRaw, String toRaw, Instant now) {
        Instant asOf = now.truncatedTo(ChronoUnit.MICROS);
        LocalDate today = BusinessDay.dateOf(asOf);
        // 검사 순서(목과 같다): 형식 → from > to → 오늘 영업일 뒤 → 62일
        LocalDate fromParsed = parse("from", fromRaw);
        LocalDate toParsed = parse("to", toRaw);
        LocalDate from = fromParsed == null ? today : fromParsed;
        LocalDate to = toParsed == null ? from : toParsed;
        if (from.isAfter(to)) {
            throw ApiException.validationFailed("from", "from 이 to 보다 늦습니다.");
        }
        if (to.isAfter(today)) {
            throw ApiException.validationFailed("to", "오늘 영업일 뒤는 조회할 수 없습니다.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw ApiException.validationFailed("to", "최대 " + MAX_DAYS + "일까지 조회할 수 있습니다.");
        }

        OffsetDateTime startAt = utc(BusinessDay.startOf(from));
        OffsetDateTime endAt = utc(BusinessDay.startOf(to.plusDays(1)));
        OffsetDateTime asOfAt = OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC);

        Map<LocalDate, Tally> byDate = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            byDate.put(d, new Tally());
        }
        Tally total = new Tally();
        for (SourceRow row : repository.sources(wholesalerId, startAt, endAt, asOfAt)) {
            Outcome outcome = Outcome.of(row);
            total.add(outcome);
            Tally day = byDate.get(BusinessDay.dateOf(row.sentAt().toInstant()));
            if (day != null) {
                day.add(outcome);
            }
        }

        List<InquiryStats.InquiryDay> days = new ArrayList<>(byDate.size());
        byDate.forEach((date, tally) -> days.add(new InquiryStats.InquiryDay(date, tally.counts())));
        return new InquiryStats(from, to, startAt, endAt, asOfAt, total.counts(), List.copyOf(days));
    }

    private static LocalDate parse(String field, String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw);   // ISO_LOCAL_DATE 는 STRICT — 2026-02-30 도 거른다
        } catch (DateTimeParseException e) {
            throw ApiException.validationFailed(field, "날짜는 YYYY-MM-DD 입니다.");
        }
    }

    private static OffsetDateTime utc(OffsetDateTime t) {
        return t.withOffsetSameInstant(ZoneOffset.UTC);
    }

    enum Kind { AUTO_REPLIED, AUTO_REPLIED_WITH_HANDOFF, HANDED_OFF, CLARIFIED, NO_RESULT }

    /** 출처 하나의 결과와 의도 칸. intentKey 가 null 이면 byIntent 에 안 센다(되묻기·결과 없음). */
    record Outcome(Kind kind, String intentKey) {

        static final String UNCLASSIFIED = "UNCLASSIFIED";

        static Outcome of(SourceRow row) {
            if (row.hasAutoReply()) {
                return new Outcome(row.hasHandoff() ? Kind.AUTO_REPLIED_WITH_HANDOFF : Kind.AUTO_REPLIED,
                        key(row.autoIntent()));
            }
            if (row.hasHandoff()) {
                return new Outcome(Kind.HANDED_OFF, key(row.handoffIntent()));
            }
            return new Outcome(row.hasClarify() ? Kind.CLARIFIED : Kind.NO_RESULT, null);
        }

        private static String key(ChatIntent intent) {
            return intent == null ? UNCLASSIFIED : intent.name();
        }
    }

    /** 한 구간의 셈. */
    private static final class Tally {

        private final EnumMap<Kind, Integer> kinds = new EnumMap<>(Kind.class);
        private final Map<String, int[]> intents = new LinkedHashMap<>();

        void add(Outcome o) {
            kinds.merge(o.kind(), 1, Integer::sum);
            if (o.intentKey() == null) {
                return;
            }
            int[] pair = intents.computeIfAbsent(o.intentKey(), k -> new int[2]);
            pair[0]++;
            if (o.kind() == Kind.AUTO_REPLIED) {
                pair[1]++;
            }
        }

        InquiryCounts counts() {
            int retail = kinds.values().stream().mapToInt(Integer::intValue).sum();
            return new InquiryCounts(retail, n(Kind.AUTO_REPLIED), n(Kind.AUTO_REPLIED_WITH_HANDOFF),
                    n(Kind.HANDED_OFF), n(Kind.CLARIFIED), n(Kind.NO_RESULT),
                    new InquiryCounts.ByIntent(intent(ChatIntent.STOCK.name()), intent(ChatIntent.BACKORDER.name()),
                            intent(ChatIntent.RECEIVABLE.name()), intent(ChatIntent.OTHER.name()),
                            intent(Outcome.UNCLASSIFIED)));
        }

        private int n(Kind kind) {
            return kinds.getOrDefault(kind, 0);
        }

        private InquiryCounts.IntentCounts intent(String key) {
            int[] pair = intents.getOrDefault(key, new int[2]);
            return new InquiryCounts.IntentCounts(pair[0], pair[1]);
        }
    }
}
