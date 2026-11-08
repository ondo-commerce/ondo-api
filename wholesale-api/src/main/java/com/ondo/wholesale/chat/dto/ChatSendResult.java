package com.ondo.wholesale.chat.dto;

import com.ondo.wholesale.chat.domain.ChatGeneration;

import java.util.List;

/**
 * 보내기 결과 (MUL-158, 계약 {@code ChatSendResult}). 모양은 자동 답이 붙는 회차(MUL-157)에도 바뀌지 않는다.
 * 사장이 보내는 글엔 자동 답이 없어 지금은 늘 {@code generatedMessages=[]}, {@code generation=NONE}.
 */
public record ChatSendResult(ChatMessage message, List<ChatMessage> generatedMessages, ChatGeneration generation) {
}
