package com.ondo.wholesale.chat.dto;

/**
 * 읽음 결과 (MUL-158, 계약 {@code ChatReadResult}). 서버가 max(기존, 요청)으로 저장하고 미확인 수를 같은 트랜잭션에서 센다.
 * lastReadSequence 는 저장된 값이라 요청보다 클 수 있다.
 */
public record ChatReadResult(Long roomId, Long lastReadSequence, Integer unreadCount) {
}
