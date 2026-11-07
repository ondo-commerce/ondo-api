package com.ondo.wholesale.settlement.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 계좌 등록 요청. 은행 목록은 프론트 상수(서버 조회 API 없음), 계좌번호 형식은 검증하지 않는다.
 * 첫 계좌는 {@code isPrimary} 값과 무관하게 서버가 주계좌로 만든다.
 *
 * <p>길이 상한은 bank_account 칸 크기(V15)다 — 넘으면 500 대신 400 으로 돌려준다.
 */
public record BankAccountCreateRequest(
        @NotBlank @Size(max = 30) String bankName,
        @NotBlank @Size(max = 30) String accountNo,
        @NotBlank @Size(max = 50) String accountHolder,
        @Size(max = 100) String memo,
        @JsonProperty("isPrimary") Boolean isPrimary
) {}
