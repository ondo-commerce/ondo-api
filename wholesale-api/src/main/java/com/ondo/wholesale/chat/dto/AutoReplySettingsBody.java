package com.ondo.wholesale.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ondo.wholesale.chat.inquiry.AutoReplySettings;

/**
 * 자동 답 설정 응답 (MUL-159, 계약 {@code AutoReplySettings}). GET 과 PUT 이 같은 모양을 돌려준다.
 * 판정이 읽는 {@link AutoReplySettings} 와 칸은 같고 JSON 모양({@code intents.STOCK} …)만 다르다.
 *
 * @param enabled false 면 모든 소매 문의를 사람에게 넘긴다(자동 답 0건)
 * @param intents 의도별 켜짐. enabled 가 true 일 때만 뜻이 있다. OTHER 는 늘 사람 넘김이라 칸이 없다
 */
public record AutoReplySettingsBody(boolean enabled, Intents intents) {

    public record Intents(@JsonProperty("STOCK") boolean stock,
                          @JsonProperty("BACKORDER") boolean backorder,
                          @JsonProperty("RECEIVABLE") boolean receivable) {
    }

    public static AutoReplySettingsBody of(AutoReplySettings s) {
        return new AutoReplySettingsBody(s.enabled(), new Intents(s.stock(), s.backorder(), s.receivable()));
    }
}
