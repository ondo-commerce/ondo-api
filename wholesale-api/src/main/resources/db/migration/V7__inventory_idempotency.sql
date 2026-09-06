-- ═══════════════════════════════════════════════════════════════
--  재고 (MUL-72) — 입고 멱등 테이블 · 원장 ref 널 허용
-- ═══════════════════════════════════════════════════════════════

-- 입고는 중복 등록을 되돌릴 수단이 없는 요청이라 Idempotency-Key 가 필수다.
-- 같은 키 재요청(replay)은 첫 응답과 똑같은 본문을 내려야 하는데, 응답의
-- avgCostAfter 가 시점값이라 재계산으로는 같은 본문을 못 만든다 — 그래서
-- 응답 본문 자체를 저장한다. request_hash 는 같은 키에 다른 body 를 보내는
-- 실수(409 IDEMPOTENCY_KEY_REUSED)를 가려내는 지문이다.
CREATE TABLE wholesale.inbound_idempotency (
    wholesaler_id    bigint      NOT NULL REFERENCES wholesale.wholesaler (id),
    idempotency_key  varchar(64) NOT NULL,                    -- 폼이 만든 UUID/ULID
    request_hash     varchar(64) NOT NULL,                    -- 정렬 직렬화 SHA-256 hex
    inbound_id       bigint      NOT NULL REFERENCES wholesale.inbound (id),
    response_body    text        NOT NULL,                    -- 첫 201 의 data 페이로드 JSON
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT inbound_idempotency_pk PRIMARY KEY (wholesaler_id, idempotency_key)
);

-- 조정(ADJUST)은 출처가 없다는 계약(ref null)과 V1 의 NOT NULL 이 충돌한다.
-- 입고 IN·출고 OUT 은 여전히 로트를 가리킨다 — 널 허용은 조정만을 위한 것이다.
ALTER TABLE wholesale.stock_movement ALTER COLUMN ref_type DROP NOT NULL;
ALTER TABLE wholesale.stock_movement ALTER COLUMN ref_id   DROP NOT NULL;
