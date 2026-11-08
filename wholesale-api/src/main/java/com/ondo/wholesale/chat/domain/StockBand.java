package com.ondo.wholesale.chat.domain;

/** 소매에게 보이는 재고 구간 (MUL-158). NONE 0 / UNDER_10 1~9 / UNDER_50 10~49 / FIFTY_PLUS 50 이상(경계 잠정). */
public enum StockBand {
    NONE,
    UNDER_10,
    UNDER_50,
    FIFTY_PLUS
}
