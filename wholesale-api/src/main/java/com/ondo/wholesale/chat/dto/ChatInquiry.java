package com.ondo.wholesale.chat.dto;

import com.ondo.wholesale.chat.domain.ChatIntent;

/**
 * 칩으로 고른 문의 문맥 (MUL-158, 계약 {@code ChatInquiry}). 자유 문장이면 메시지에 null 이다.
 * {@code notListed} 는 계약상 필수가 아니다 — 요청에서 빠지면 false 로 본다.
 */
public record ChatInquiry(
        ChatIntent intent,
        Long listingId,
        Long variantId,
        Long backorderId,
        String label,
        Boolean notListed) {
}
