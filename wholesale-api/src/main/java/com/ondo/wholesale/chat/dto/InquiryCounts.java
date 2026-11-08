package com.ondo.wholesale.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 한 구간의 소매 메시지 결과 (MUL-159, 계약 {@code InquiryCounts}).
 * 다섯 칸(autoReplied, autoRepliedWithHandoff, handedOff, clarified, noResult) 합 = retailMessages.
 *
 * @param autoReplied            AUTO_REPLY 만 붙은 메시지
 * @param autoRepliedWithHandoff 같은 출처에 AUTO_REPLY 와 HANDOFF 가 둘 다(답+확인)
 * @param handedOff              HANDOFF 만(CLARIFY 가 같이 있어도 넘김이다)
 * @param clarified              CLARIFY 만
 * @param noResult               결과 줄 0
 * @param byIntent               판정 끝남(autoReplied + autoRepliedWithHandoff + handedOff)을 의도별로
 */
public record InquiryCounts(
        int retailMessages,
        int autoReplied,
        int autoRepliedWithHandoff,
        int handedOff,
        int clarified,
        int noResult,
        ByIntent byIntent) {

    /** 의도별. UNCLASSIFIED 는 결과 줄 intent 가 null(협상어, 의도 둘, 의도 없음). */
    public record ByIntent(
            @JsonProperty("STOCK") IntentCounts stock,
            @JsonProperty("BACKORDER") IntentCounts backorder,
            @JsonProperty("RECEIVABLE") IntentCounts receivable,
            @JsonProperty("OTHER") IntentCounts other,
            @JsonProperty("UNCLASSIFIED") IntentCounts unclassified) {
    }

    /**
     * @param judged      자동 답 + 답+확인 + 넘김
     * @param autoReplied autoReplied 칸과 같은 기준(답+확인 제외)
     */
    public record IntentCounts(int judged, int autoReplied) {
    }
}
