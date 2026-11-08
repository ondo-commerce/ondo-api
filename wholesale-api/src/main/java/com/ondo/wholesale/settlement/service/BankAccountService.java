package com.ondo.wholesale.settlement.service;

import com.ondo.wholesale.common.error.ApiException;
import com.ondo.wholesale.common.error.ErrorCode;
import com.ondo.wholesale.common.error.ErrorResponse;
import com.ondo.wholesale.common.error.ResourceNotFoundException;
import com.ondo.wholesale.settlement.domain.BankAccount;
import com.ondo.wholesale.settlement.dto.BankAccountCreateRequest;
import com.ondo.wholesale.settlement.dto.BankAccountResponse;
import com.ondo.wholesale.settlement.dto.BankAccountUpdateRequest;
import com.ondo.wholesale.settlement.repository.BankAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 정산 계좌 (MUL-130) — 여러 계좌 + 주계좌 1개. 규칙은 계약 스텁(MUL-83) 그대로다.
 *
 * <p><b>주계좌 0 또는 1개.</b> 쓰기(등록·수정·삭제)는 전부 맨 먼저 도매처 행을 잠근다
 * ({@link BankAccountRepository#lockWholesaler}). 한 도매처의 계좌 쓰기가 한 줄로 서므로
 * "계좌가 없으니 첫 계좌는 주계좌" · "기존 주계좌를 내리고 새 걸 올린다" · "주계좌를 지웠으니
 * 가장 오래된 걸 올린다" · 중복 검사가 모두 다른 요청의 끼어듦 없이 판단된다.
 * DB 의 부분 유니크(bank_account_primary_uk)는 잠금을 빠뜨린 경로가 생겨도 주계좌 둘이
 * 커밋되지 않게 하는 마지막 그물이다. 그물만 있으면 동시 요청이 500 으로 깨진다 —
 * 잠금이 그걸 정상 응답으로 바꾼다.
 *
 * <p>부분 유니크 인덱스는 DEFERRABLE 이 안 돼 문장마다 검사한다. 그래서 주계좌를 바꿀 땐
 * 기존 주계좌를 내리는 UPDATE 를 먼저 flush 하고 나서 새 주계좌를 쓴다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class BankAccountService {

    private static final int BANK_NAME_MAX = 30;
    private static final int ACCOUNT_NO_MAX = 30;
    private static final int ACCOUNT_HOLDER_MAX = 50;
    private static final int MEMO_MAX = 100;

    private final BankAccountRepository repository;

    @Transactional(readOnly = true)
    public List<BankAccountResponse> list(Long wholesalerId) {
        return repository.findAllByWholesalerIdOrderByPrimaryDescIdAsc(wholesalerId).stream()
                .map(BankAccountService::toResponse)
                .toList();
    }

    public BankAccountResponse create(Long wholesalerId, BankAccountCreateRequest request) {
        repository.lockWholesaler(wholesalerId);
        rejectDuplicate(wholesalerId, request.bankName(), request.accountNo(), null);

        boolean first = !repository.existsByWholesalerId(wholesalerId);
        boolean primary = first || Boolean.TRUE.equals(request.isPrimary());
        if (primary && !first) {
            demoteCurrentPrimary(wholesalerId);
        }
        BankAccount saved = repository.save(new BankAccount(wholesalerId, request.bankName(),
                request.accountNo(), request.accountHolder(), request.memo(), primary));
        return toResponse(saved);
    }

    public BankAccountResponse update(Long wholesalerId, Long bankAccountId, BankAccountUpdateRequest request) {
        validate(request);
        repository.lockWholesaler(wholesalerId);
        BankAccount account = owned(wholesalerId, bankAccountId);

        Optional<Boolean> isPrimary = request.isPrimary();
        if (isPrimary != null && !isPrimary.get() && account.isPrimary()) {
            throw new ApiException(ErrorCode.PRIMARY_ACCOUNT_CANNOT_BE_UNSET);
        }

        String bankName = request.bankName() != null ? request.bankName().get() : account.getBankName();
        String accountNo = request.accountNo() != null ? request.accountNo().get() : account.getAccountNo();
        rejectDuplicate(wholesalerId, bankName, accountNo, account.getId());

        account.changeBankName(bankName);
        account.changeAccountNo(accountNo);
        if (request.accountHolder() != null) {
            account.changeAccountHolder(request.accountHolder().get());
        }
        if (request.memo() != null) {
            account.changeMemo(request.memo().orElse(null));
        }
        if (isPrimary != null && isPrimary.get() && !account.isPrimary()) {
            demoteCurrentPrimary(wholesalerId);
            account.promote();
        }
        repository.flush();
        return toResponse(account);
    }

    public void delete(Long wholesalerId, Long bankAccountId) {
        repository.lockWholesaler(wholesalerId);
        BankAccount account = owned(wholesalerId, bankAccountId);
        repository.delete(account);
        repository.flush();
        if (account.isPrimary()) {
            repository.findFirstByWholesalerIdOrderByIdAsc(wholesalerId).ifPresent(BankAccount::promote);
            repository.flush();
        }
    }

    private BankAccount owned(Long wholesalerId, Long bankAccountId) {
        return repository.findByIdAndWholesalerId(bankAccountId, wholesalerId)
                .orElseThrow(() -> new ResourceNotFoundException("계좌가 없거나 접근할 수 없습니다."));
    }

    /** 내리는 UPDATE 를 바로 내보낸다 — 새 주계좌를 쓰기 전에 부분 유니크가 비어 있어야 한다. */
    private void demoteCurrentPrimary(Long wholesalerId) {
        repository.findByWholesalerIdAndPrimaryTrue(wholesalerId).ifPresent(current -> {
            current.demote();
            repository.flush();
        });
    }

    private void rejectDuplicate(Long wholesalerId, String bankName, String accountNo, Long selfId) {
        repository.findByWholesalerIdAndBankNameAndAccountNo(wholesalerId, bankName, accountNo)
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> {
                    throw new ApiException(ErrorCode.DUPLICATE_BANK_ACCOUNT,
                            ErrorCode.DUPLICATE_BANK_ACCOUNT.defaultMessage(),
                            List.of(new ErrorResponse.FieldError("accountNo", "같은 은행에 이미 등록된 계좌번호입니다.",
                                    Map.of("bankAccountId", existing.getId()))));
                });
    }

    /**
     * PATCH 값 검사. 생략은 통과, 명시적 null 은 memo 만 허용, 문자열은 비면 안 되고 칸 크기 이내.
     * Bean Validation 이 Optional 의 "생략 vs null" 을 구분하지 못해 여기서 직접 본다.
     */
    private static void validate(BankAccountUpdateRequest request) {
        requireText("bankName", request.bankName(), BANK_NAME_MAX);
        requireText("accountNo", request.accountNo(), ACCOUNT_NO_MAX);
        requireText("accountHolder", request.accountHolder(), ACCOUNT_HOLDER_MAX);
        if (request.memo() != null && request.memo().isPresent() && request.memo().get().length() > MEMO_MAX) {
            throw ApiException.validationFailed("memo", MEMO_MAX + "자 이내여야 합니다.");
        }
        if (request.isPrimary() != null && request.isPrimary().isEmpty()) {
            throw ApiException.validationFailed("isPrimary", "null 은 보낼 수 없습니다 — 바꾸지 않으려면 생략합니다.");
        }
    }

    private static void requireText(String field, Optional<String> value, int max) {
        if (value == null) {
            return;
        }
        if (value.isEmpty()) {
            throw ApiException.validationFailed(field, "null 은 보낼 수 없습니다 — 바꾸지 않으려면 생략합니다.");
        }
        if (value.get().isBlank()) {
            throw ApiException.validationFailed(field, "비울 수 없습니다.");
        }
        if (value.get().length() > max) {
            throw ApiException.validationFailed(field, max + "자 이내여야 합니다.");
        }
    }

    private static BankAccountResponse toResponse(BankAccount account) {
        return new BankAccountResponse(account.getId(), account.getBankName(), account.getAccountNo(),
                account.getAccountHolder(), account.getMemo(), account.isPrimary(), account.getCreatedAt());
    }
}
