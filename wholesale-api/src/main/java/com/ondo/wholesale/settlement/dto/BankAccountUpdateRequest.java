package com.ondo.wholesale.settlement.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Optional;

/**
 * 계좌 수정 요청 — 보낸 필드만 바뀐다(공통 §6). {@code memo}만 null 로 지울 수 있고
 * 나머지 필드의 null 은 400. 주계좌를 {@code isPrimary: false}로 직접 해제할 수 없다
 * (400 PRIMARY_ACCOUNT_CANNOT_BE_UNSET) — 다른 계좌를 승격하면 부수 효과로 내려간다.
 *
 * <p>생략과 명시적 null 을 구분해야 해서 record 가 아니라 세터 바인딩 클래스다
 * ({@code ProductUpdateRequest}와 같은 방식): 생략된 필드는 세터가 안 불려 {@code null}로 남고,
 * 명시적 null 은 Jackson 이 {@code Optional.empty()}로 넣는다. JSON 모양은 스텁(MUL-83) 그대로다.
 *
 * <p>값 검사(빈 문자열 · 길이 · null 금지)는 {@code BankAccountService}가 한다.
 */
public class BankAccountUpdateRequest {

    private Optional<String> bankName;
    private Optional<String> accountNo;
    private Optional<String> accountHolder;
    private Optional<String> memo;
    private Optional<Boolean> isPrimary;

    public BankAccountUpdateRequest() {
    }

    /** 생략 = {@code null}, 명시적 null = {@code Optional.empty()}. */
    public Optional<String> bankName() {
        return bankName;
    }

    public Optional<String> accountNo() {
        return accountNo;
    }

    public Optional<String> accountHolder() {
        return accountHolder;
    }

    public Optional<String> memo() {
        return memo;
    }

    public Optional<Boolean> isPrimary() {
        return isPrimary;
    }

    public void setBankName(Optional<String> bankName) {
        this.bankName = bankName;
    }

    public void setAccountNo(Optional<String> accountNo) {
        this.accountNo = accountNo;
    }

    public void setAccountHolder(Optional<String> accountHolder) {
        this.accountHolder = accountHolder;
    }

    public void setMemo(Optional<String> memo) {
        this.memo = memo;
    }

    @JsonProperty("isPrimary")
    public void setIsPrimary(Optional<Boolean> isPrimary) {
        this.isPrimary = isPrimary;
    }
}
