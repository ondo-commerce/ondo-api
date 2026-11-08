package com.ondo.wholesale.chat.domain;

/**
 * 메시지 종류 (MUL-158). TEXT 사람이 쓴 글 / AUTO_REPLY 자동 답 / CLARIFY 자동 되묻기 / HANDOFF 사람 넘김 줄.
 * 계약상 값이 늘 수 있어 DB 칸에는 CHECK 가 없다 — 이 enum 이 최종 방어다.
 */
public enum ChatMessageKind {
    TEXT,
    AUTO_REPLY,
    HANDOFF,
    CLARIFY
}
