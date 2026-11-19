package com.ondo.wholesale.chat.inquiry;

import static org.assertj.core.api.Assertions.assertThat;

import com.ondo.wholesale.chat.domain.StockBand;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// 골든셋에 재고 49 경계가 없어 기준값을 49 로 바꿔도 아무 테스트가 실패하지 않았다. 경계를 직접 고정한다.
class InquiryDeciderStockBandTest {

    @ParameterizedTest
    @CsvSource({
        "-13, NONE",
        "0, NONE",
        "1, UNDER_10",
        "9, UNDER_10",
        "10, UNDER_50",
        "49, UNDER_50",
        "50, FIFTY_PLUS",
        "100000, FIFTY_PLUS"
    })
    void 재고_구간_경계(int qty, StockBand expected) {
        assertThat(InquiryDecider.stockBand(qty)).isEqualTo(expected);
    }
}
