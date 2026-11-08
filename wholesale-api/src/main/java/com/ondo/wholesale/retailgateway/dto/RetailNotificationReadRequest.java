package com.ondo.wholesale.retailgateway.dto;

import java.util.List;

/**
 * 소매 알림 읽음 (MUL-155). {@code ids} 하나 또는 {@code all + expectedUnread} 둘 중 하나.
 *
 * <p>프론트 본문의 세밀한 검증(중복 id, 101개 이상, 모르는 칸)은 소매가 이미 했다. 여기는
 * 소매가 잘못 만들어 보낸 걸 막는 최종 방어만 한다.
 *
 * @param retailerId 소매가 자기 세션에서 꺼낸 값
 */
public record RetailNotificationReadRequest(
        Long retailerId,
        List<Long> ids,
        Boolean all,
        Integer expectedUnread
) {}
