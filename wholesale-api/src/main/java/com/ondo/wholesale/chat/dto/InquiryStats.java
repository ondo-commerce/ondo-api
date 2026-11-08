package com.ondo.wholesale.chat.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 문의 지표 응답 (MUL-159, 계약 {@code InquiryStats}). 보고서는 숫자와 함께 startAt, endAt, asOf 를 옮겨 적는다.
 *
 * @param startAt from 12:00 Asia/Seoul(포함)
 * @param endAt   to 다음날 12:00 Asia/Seoul(제외)
 * @param asOf    집계 시각. 이 뒤에 쓰인 결과 줄은 안 센다
 * @param days    from 부터 to 까지 모든 영업일, 0인 날 포함, 오름차순. counts 합 = total
 */
public record InquiryStats(
        LocalDate from,
        LocalDate to,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        OffsetDateTime asOf,
        InquiryCounts total,
        List<InquiryDay> days) {

    /** 영업일 하루. 구간은 [date 12:00, date+1 12:00) Asia/Seoul. */
    public record InquiryDay(LocalDate date, InquiryCounts counts) {
    }
}
