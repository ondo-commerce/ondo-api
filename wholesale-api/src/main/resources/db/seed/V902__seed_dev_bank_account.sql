-- ═══════════════════════════════════════════════════════════════
--  개발 환경 시드 — 도매처 주계좌 (MUL-130)
--
--  V900 이 만든 도매처 3곳(101~103)은 계좌가 없다. 그래서 소매 정산 요약의 bank 가
--  null 이고, 소매 주문서에서 계좌이체를 못 고른다. 화면을 붙이려면 계좌가 있어야 한다.
--
--  계좌는 V15 부터 wholesale.bank_account 다. 도매처마다 주계좌 하나씩 넣는다.
--
--  ⚠️ V900·V901 을 고치지 않고 새 파일을 낸다. 둘 다 배포 DB 에 이미 적용됐다.
--  ⚠️ db/seed 다 — dev 프로필만 읽는다(application-dev.yml). 운영엔 안 들어간다.
--  ⚠️ id 는 박지 않는다. bigserial 카운터를 그대로 쓰게 둬야 화면에서 계좌를 추가할 때
--     PK 가 부딪히지 않는다(V900 이 시퀀스를 손으로 올려야 했던 이유).
--
--  이미 계좌가 있는 도매처는 건너뛴다 — V15 가 옛 칸에서 옮겨 온 주계좌가 있을 수 있고,
--  그 위에 주계좌를 또 넣으면 bank_account_primary_uk 에 걸린다.
-- ═══════════════════════════════════════════════════════════════

INSERT INTO wholesale.bank_account (wholesaler_id, bank_name, account_no, account_holder, memo, is_primary)
SELECT v.wholesaler_id, v.bank_name, v.account_no, v.account_holder, '시드 · 주거래', true
FROM (VALUES
          (101, '국민은행', '101-0101-0001', '김무드'),
          (102, '신한은행', '110-102-000002', '이라온'),
          (103, '우리은행', '1002-103-000003', '박코튼')
     ) AS v (wholesaler_id, bank_name, account_no, account_holder)
WHERE NOT EXISTS (SELECT 1 FROM wholesale.bank_account b WHERE b.wholesaler_id = v.wholesaler_id);

-- 셋 다 주계좌가 없으면 V900 과 어긋난 것이다. 조용히 넘어가면 소매 정산 bank 가
-- 다시 null 로 돌아온다.
DO $$
DECLARE
    primaries int;
BEGIN
    SELECT count(*) INTO primaries
    FROM wholesale.bank_account
    WHERE wholesaler_id IN (101, 102, 103)
      AND is_primary;

    IF primaries <> 3 THEN
        RAISE EXCEPTION 'MUL-130: 시드 도매처 101~103 의 주계좌가 3개가 아니다 (% 개)', primaries;
    END IF;
END $$;
