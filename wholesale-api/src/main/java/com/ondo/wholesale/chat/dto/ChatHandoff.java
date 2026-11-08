package com.ondo.wholesale.chat.dto;

import com.ondo.wholesale.chat.domain.ChatHandoffReason;
import com.ondo.wholesale.chat.domain.ChatIntent;

/**
 * 넘김 줄(kind HANDOFF)이 왜 생겼는지 (MUL-158, 계약 {@code ChatHandoff}). 도매 화면이 이유 줄로 그린다.
 * {@code intent} 는 못 읽었으면(협상어, 의도 둘, 의도 없음) null.
 */
public record ChatHandoff(ChatIntent intent, ChatHandoffReason reason, Long sourceSequence) {
}
