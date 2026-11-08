package com.ondo.wholesale.chat.domain;

/**
 * 사람 넘김 이유 (MUL-158) — 도매 응답에만 싣는다. 고르는 건 판정(MUL-157)이다.
 *
 * <p>계약 enum 21개 그대로다(docs/design/chat.md c — D46 의 HUMAN_REQUEST 포함). 값이 늘 수 있어
 * DB 칸({@code chat_message.handoff_reason})은 CHECK 없는 varchar 다. 이 enum 이 최종 방어다.
 */
public enum ChatHandoffReason {
    OFF,
    NEGOTIATION,
    MULTI_INTENT,
    NO_INTENT,
    NOT_SUPPORTED,
    NO_CONTEXT,
    NO_OPTIONS,
    INVALID_TARGET,
    CLARIFY_AGAIN,
    NOT_LISTED,
    NO_BACKORDER,
    NO_MATCH,
    STOCK_ZERO,
    ETA_UNKNOWN,
    ETA_PAST,
    PAYMENT_REPORT,
    PERIOD,
    NO_RELATION,
    AMOUNT_UNSURE,
    LOOKUP_FAILED,
    HUMAN_REQUEST
}
