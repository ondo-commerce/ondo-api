package com.ondo.wholesale.settlement.repository;

import com.ondo.wholesale.settlement.domain.BankAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {

    /** 목록 순서 — 주계좌 먼저 → 등록순(id). 계약 고정, sort 미지원. */
    List<BankAccount> findAllByWholesalerIdOrderByPrimaryDescIdAsc(Long wholesalerId);

    /** 소유 스코핑 — 남의 계좌는 없는 계좌와 같이 404 로 흘린다. */
    Optional<BankAccount> findByIdAndWholesalerId(Long id, Long wholesalerId);

    Optional<BankAccount> findByWholesalerIdAndBankNameAndAccountNo(Long wholesalerId, String bankName, String accountNo);

    Optional<BankAccount> findByWholesalerIdAndPrimaryTrue(Long wholesalerId);

    /** 주계좌를 지운 뒤 승격할 계좌 — 남은 것 중 가장 먼저 등록한 것. */
    Optional<BankAccount> findFirstByWholesalerIdOrderByIdAsc(Long wholesalerId);

    boolean existsByWholesalerId(Long wholesalerId);

    /**
     * 한 도매처의 계좌 쓰기를 한 줄로 세운다 — 도매처 행을 잠근다.
     *
     * <p>계좌 행이 아니라 도매처 행을 잡는 이유: "계좌가 없으면 첫 계좌는 주계좌" 처럼
     * 아직 없는 행을 보고 정하는 규칙이 있어서 계좌 행 잠금으로는 못 막는다.
     *
     * <p>{@code FOR NO KEY UPDATE} 인 이유: 상품·주문 INSERT 가 FK 검사로 도매처 행에
     * {@code KEY SHARE} 를 건다. {@code FOR UPDATE} 면 그것까지 막지만 이건 안 막는다.
     */
    @Query(value = "SELECT id FROM wholesale.wholesaler WHERE id = :wholesalerId FOR NO KEY UPDATE",
            nativeQuery = true)
    Optional<Long> lockWholesaler(@Param("wholesalerId") Long wholesalerId);
}
