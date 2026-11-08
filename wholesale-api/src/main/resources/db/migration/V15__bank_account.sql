-- ═══════════════════════════════════════════════════════════════
--  정산 계좌 — 여러 개 + 주계좌 1개 (MUL-130)
--
--  지금까지 계좌는 wholesaler 행에 칸 3개(bank_name · bank_account_no ·
--  bank_account_holder)로 하나뿐이었다. 화면(ondo-web #208)과 계약 스텁(MUL-83)은
--  여러 계좌 + 주계좌 1개다. 계좌를 자기 테이블로 빼고, 칸의 값은 주계좌로 옮긴 뒤
--  칸을 지운다. 소매에 나가는 입금 계좌는 이제 주계좌다.
--
--  주계좌 0 또는 1개
--    bank_account_primary_uk(부분 유니크)가 마지막 방어선이다 — 어떤 코드 경로로도
--    한 도매처에 주계좌 둘이 커밋되지 않는다. 그런데 이것만으로는 동시 요청이 500 으로
--    깨진다(둘 다 "계좌가 없다" 를 보고 둘 다 주계좌로 넣으면 하나는 유니크 위반).
--    그래서 앱은 계좌를 쓸 때마다 wholesaler 행을 잠가 한 도매처의 계좌 쓰기를 한 줄로
--    세운다(BankAccountService). 인덱스는 잠금을 빠뜨린 경로를 잡는 그물이다.
--
--  주계좌를 내리고 다른 걸 올리는 건 한 트랜잭션 안에서 "내리기 → 올리기" 순서로
--  문장을 낸다. 부분 유니크 인덱스는 DEFERRABLE 이 안 돼 문장마다 검사한다.
--
--  같은 도매처에 같은 은행 + 계좌번호는 1개. 계좌번호는 입력 그대로 비교한다
--  (서버 정규화 없음 — 계약). 이 유니크가 wholesaler_id 로 시작해서 도매처별 조회
--  인덱스를 겸한다.
-- ═══════════════════════════════════════════════════════════════

CREATE TABLE wholesale.bank_account (
    id              bigserial    PRIMARY KEY,
    wholesaler_id   bigint       NOT NULL REFERENCES wholesale.wholesaler (id),
    bank_name       varchar(30)  NOT NULL,                -- 은행 목록은 프론트 상수. 서버는 문자열 그대로
    account_no      varchar(30)  NOT NULL,                -- 입력 그대로(- 포함). 형식 검증 없음
    account_holder  varchar(50)  NOT NULL,
    memo            varchar(100),
    is_primary      boolean      NOT NULL DEFAULT false,  -- 소매 화면 입금 계좌
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT bank_account_no_uk UNIQUE (wholesaler_id, bank_name, account_no)
);

CREATE UNIQUE INDEX bank_account_primary_uk
    ON wholesale.bank_account (wholesaler_id)
    WHERE is_primary;

COMMENT ON TABLE wholesale.bank_account IS
    '정산 계좌. 도매처당 여러 개, 주계좌는 최대 1개(bank_account_primary_uk). 삭제는 완전 삭제 (MUL-130)';

-- ── 기존 칸 → 주계좌 ────────────────────────────────────────────
--
-- 셋이 다 있어야 계좌다. 일부만 찬 행은 옮길 수 없다 — 새 테이블은 셋 다 NOT NULL 이다.
-- 그런 행을 조용히 버리면 칸을 지우는 순간 값이 사라지므로 마이그레이션을 멈춘다.
-- (이 칸을 쓰는 API 가 없어서 일부만 찬 행은 손으로 넣은 것뿐이다. dev 시드 V900·V901 은
-- 이 칸을 안 쓴다.)
DO $$
DECLARE
    partial text;
BEGIN
    SELECT string_agg(id::text, ', ' ORDER BY id) INTO partial
    FROM wholesale.wholesaler
    WHERE num_nonnulls(nullif(btrim(bank_name), ''),
                       nullif(btrim(bank_account_no), ''),
                       nullif(btrim(bank_account_holder), '')) IN (1, 2);

    IF partial IS NOT NULL THEN
        RAISE EXCEPTION 'MUL-130: 계좌 칸이 일부만 찬 도매처가 있어 옮길 수 없다 (wholesaler id: %)', partial;
    END IF;
END $$;

-- 지워진 도매처도 옮긴다 — 소매가 지난 주문서를 다시 열 때 계좌가 나와야 한다.
INSERT INTO wholesale.bank_account (wholesaler_id, bank_name, account_no, account_holder, is_primary)
SELECT id, bank_name, bank_account_no, bank_account_holder, true
FROM wholesale.wholesaler
WHERE nullif(btrim(bank_name), '') IS NOT NULL
  AND nullif(btrim(bank_account_no), '') IS NOT NULL
  AND nullif(btrim(bank_account_holder), '') IS NOT NULL
ORDER BY id;

ALTER TABLE wholesale.wholesaler
    DROP COLUMN bank_name,
    DROP COLUMN bank_account_no,
    DROP COLUMN bank_account_holder;
