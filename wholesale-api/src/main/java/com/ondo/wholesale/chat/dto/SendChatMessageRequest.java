package com.ondo.wholesale.chat.dto;

/**
 * 보내기 본문 (MUL-158, 계약 {@code SendChatMessageRequest}). body 는 앞뒤 공백을 지운 뒤 1~1000 코드 포인트.
 * inquiry 는 칩 문맥, 자유 문장이면 null.
 */
public record SendChatMessageRequest(String body, ChatInquiry inquiry) {
}
