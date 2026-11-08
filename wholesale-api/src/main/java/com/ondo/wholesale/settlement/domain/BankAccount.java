package com.ondo.wholesale.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * 정산 계좌 한 건 (MUL-130). 도매처당 여러 개, 주계좌는 최대 1개다 — 소매 화면의 입금 계좌가 주계좌다.
 *
 * <p>주계좌 0·1 개 불변식은 엔티티 혼자 지킬 수 없다(다른 행을 봐야 한다).
 * {@code BankAccountService}가 도매처 행을 잠근 채 내리고 올리며, DB 의 bank_account_primary_uk 가 최종 방어선이다.
 */
@Entity
@Table(name = "bank_account", schema = "wholesale")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BankAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wholesaler_id", nullable = false, updatable = false)
    private Long wholesalerId;

    @Column(name = "bank_name", nullable = false, length = 30)
    private String bankName;

    /** 입력 그대로(- 포함). 서버가 정규화하지 않는다 — 계약. */
    @Column(name = "account_no", nullable = false, length = 30)
    private String accountNo;

    @Column(name = "account_holder", nullable = false, length = 50)
    private String accountHolder;

    @Column(length = 100)
    private String memo;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public BankAccount(Long wholesalerId, String bankName, String accountNo, String accountHolder,
                       String memo, boolean primary) {
        this.wholesalerId = wholesalerId;
        this.bankName = bankName;
        this.accountNo = accountNo;
        this.accountHolder = accountHolder;
        this.memo = memo;
        this.primary = primary;
    }

    public void changeBankName(String bankName) {
        this.bankName = bankName;
    }

    public void changeAccountNo(String accountNo) {
        this.accountNo = accountNo;
    }

    public void changeAccountHolder(String accountHolder) {
        this.accountHolder = accountHolder;
    }

    public void changeMemo(String memo) {
        this.memo = memo;
    }

    public void promote() {
        this.primary = true;
    }

    public void demote() {
        this.primary = false;
    }
}
