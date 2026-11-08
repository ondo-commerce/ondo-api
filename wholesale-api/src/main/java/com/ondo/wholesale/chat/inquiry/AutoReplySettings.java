package com.ondo.wholesale.chat.inquiry;

import com.ondo.wholesale.chat.domain.ChatIntent;

/**
 * 자동 답 설정 (MUL-157, 계약 {@code AutoReplySettings}). 설정 저장·화면은 MUL-159 다 — 그 전까지 판정은
 * {@link #DEFAULTS}(전부 켜짐)를 읽는다({@link AutoReplySettingsSource}).
 *
 * @param enabled 전체 켜짐. 꺼져도 판정은 의도를 읽어 OFF 넘김 줄에 싣는다(문의 지표용)
 */
public record AutoReplySettings(boolean enabled, boolean stock, boolean backorder, boolean receivable) {

    public static final AutoReplySettings DEFAULTS = new AutoReplySettings(true, true, true, true);

    /** 의도별 켜짐. OTHER 는 자동 답이 없어 늘 꺼짐이다. */
    public boolean intentOn(ChatIntent intent) {
        return switch (intent) {
            case STOCK -> stock;
            case BACKORDER -> backorder;
            case RECEIVABLE -> receivable;
            case OTHER -> false;
        };
    }

    AutoReplySettings withEnabled(boolean value) {
        return new AutoReplySettings(value, stock, backorder, receivable);
    }
}
