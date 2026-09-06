package com.ondo.wholesale.inventory.domain;

/** 재고 변동 종류. 원장은 append-only — 정정은 반대부호 ADJUST 로 한다. */
public enum StockMovementType {
    IN, OUT, ADJUST
}
