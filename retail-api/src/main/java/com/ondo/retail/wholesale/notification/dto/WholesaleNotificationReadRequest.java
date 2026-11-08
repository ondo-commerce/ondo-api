package com.ondo.retail.wholesale.notification.dto;

import java.util.List;

/**
 * 도매 읽음 요청 (MUL-155). {@code ids} 또는 {@code all + expectedUnread} 중 하나만 찬다.
 *
 * @param retailerId 세션에서 꺼낸 값
 */
public record WholesaleNotificationReadRequest(
        Long retailerId,
        List<Long> ids,
        Boolean all,
        Integer expectedUnread) {

    public static WholesaleNotificationReadRequest ids(Long retailerId, List<Long> ids) {
        return new WholesaleNotificationReadRequest(retailerId, ids, null, null);
    }

    public static WholesaleNotificationReadRequest all(Long retailerId, int expectedUnread) {
        return new WholesaleNotificationReadRequest(retailerId, null, true, expectedUnread);
    }
}
