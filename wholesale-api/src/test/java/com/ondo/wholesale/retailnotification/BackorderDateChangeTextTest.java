package com.ondo.wholesale.retailnotification;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 미송 입고일 변경 알림 문구 (MUL-155).
 *
 * <p>웹 목 {@code dateChangeNotice.ts} 와 글자가 같은지 본다 — 계약 예시가 그 목에서 왔다.
 * 누구에게 몇 건이 가는지는 ExpectedInboundNotificationIntegrationTest 가 본다.
 */
class BackorderDateChangeTextTest {

    private static final LocalDate 오늘 = LocalDate.of(2026, 11, 3);

    @Test
    void 계약_예시와_같은_문구를_만든다() {
        assertThat(BackorderDateChangeText.title("무드온")).isEqualTo("무드온 미송 입고일 변경");
        assertThat(BackorderDateChangeText.body("린넨 셋업 자켓", "베이지", "L", 2,
                LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 8), 오늘))
                .isEqualTo("린넨 셋업 자켓 베이지/L 남은 2장, 11/5(목) → 11/8(일)");
    }

    @Test
    void 날짜가_없으면_입고일_미정이다() {
        assertThat(BackorderDateChangeText.body("와이드 데님 팬츠", "소라", "M", 1,
                LocalDate.of(2026, 11, 5), null, 오늘))
                .isEqualTo("와이드 데님 팬츠 소라/M 남은 1장, 11/5(목) → 입고일 미정");
        assertThat(BackorderDateChangeText.etaLabel(null, 오늘)).isEqualTo("입고일 미정");
    }

    @Test
    void 오늘과_해가_다르면_연도를_붙인다() {
        assertThat(BackorderDateChangeText.etaLabel(LocalDate.of(2027, 1, 5), 오늘)).isEqualTo("2027/1/5(화)");
        assertThat(BackorderDateChangeText.etaLabel(LocalDate.of(2026, 1, 5), 오늘)).isEqualTo("1/5(월)");
    }
}
