package com.ondo.wholesale.chat.domain;

/** 문의 의도 (MUL-158). STOCK 재고 / BACKORDER 미송 / RECEIVABLE 미수 / OTHER 그 밖. */
public enum ChatIntent {
    STOCK,
    BACKORDER,
    RECEIVABLE,
    OTHER
}
