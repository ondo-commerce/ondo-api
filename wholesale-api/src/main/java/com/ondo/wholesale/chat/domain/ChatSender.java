package com.ondo.wholesale.chat.domain;

/** 보낸 쪽 (MUL-158). AUTO_REPLY·CLARIFY 는 WHOLESALER(도매 이름으로 나간 자동 답), HANDOFF 는 SYSTEM. */
public enum ChatSender {
    WHOLESALER,
    RETAILER,
    SYSTEM
}
