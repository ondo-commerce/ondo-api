package com.ondo.wholesale.retailnotification;

/**
 * 소매 알림 종류 (MUL-155). 계약 {@code NotificationType} 과 같은 이름이다.
 *
 * <p>지금 만드는 건 {@link #BACKORDER_DATE_CHANGED} 하나다. 나머지는 계약에 있어 미리 적어 두었고
 * 각 사건(주문 확정·배분·출고)을 붙이는 티켓이 만든다. DB 칸에 CHECK 를 안 건 값이 늘 때마다
 * 마이그레이션이 생기지 않게 하려는 것이다.
 */
public enum RetailNotificationType {
    ORDER_CONFIRMED,
    BACKORDER_DATE_CHANGED,
    BACKORDER_ALLOCATED,
    SHIPPED
}
