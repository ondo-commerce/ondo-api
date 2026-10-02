-- ═══════════════════════════════════════════════════════════════
--  접수 대기함 (MUL-139)
--
--  도매가 멈춰 있으면 주문서는 저장되지만 도매 접수는 실패한다. 지금은 사용자가
--  다시 눌러야 한다. 빠진 것은 "누가 다시 보내는가" 다 — 재시도 자체는 이미 안전하다.
--  도매에 UNIQUE (retail_order_id, wholesaler_id) 가 있어 같은 열쇠로 두 번 가면
--  409 ORDER_ALREADY_CREATED 로 막힌다.
-- ═══════════════════════════════════════════════════════════════


-- ── 대기함 ──────────────────────────────────────────────────────
--
-- ⚠️ 실패했을 때 쓰는 표가 아니다. **주문서를 쓸 때 같이 쓴다.**
--
-- "실패를 감지해서 기록" 하려면 감지할 주체가 살아 있어야 한다. 도매를 부르는
-- 도중에 태스크가 내려가면 기록할 사람이 없고 주문 의사가 통째로 사라진다.
-- 주문서와 한 트랜잭션에 쓰면 둘 다 되거나 둘 다 안 된다.
--
-- 도매 호출은 그 뒤에 지금처럼 동기로 한다. 대기함은 그게 실패했을 때의 보험이다.
CREATE TABLE retail.order_dispatch (
    id              bigserial   PRIMARY KEY,
    order_group_id  bigint      NOT NULL REFERENCES retail.order_group (id),
    wholesaler_id   bigint      NOT NULL,          -- 논리 참조(경계). 도매 DB 의 값이다

    -- 재시도에 필요한 모든 것. 도매를 다시 부르려면 주문 내용이 그대로 있어야 하는데,
    -- 장바구니는 접수되면 지워지고 상품 값은 도매가 바꿀 수 있다. 부를 때 만든
    -- 명령을 그대로 굳혀 둔다 — 나중에 다시 조립하면 그 사이 바뀐 값이 섞인다
    payload         jsonb       NOT NULL,

    status          varchar(20) NOT NULL,
    attempts        int         NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,          -- 이 시각 전에는 집지 않는다

    -- 기한. 메시지는 늦게 처리돼도 가치가 남지만 주문은 아니다.
    -- 실패를 본 소매처는 다른 도매에서 이미 샀을 수 있고, 그 뒤 자동 접수되면
    -- 같은 물건을 두 번 사게 된다. 영업일 경계(KST 12시)를 넘기면 어제 주문이
    -- 오늘 주문으로 집계돼 도매 장부와 소매 화면의 날짜가 어긋난다
    expires_at      timestamptz NOT NULL,

    last_error      text,                          -- 왜 못 갔는지. 소매처가 물으면 답해야 한다
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    -- 도매 쪽 orders_idempotent_uk 와 같은 모양이다. 양쪽이 같은 열쇠를 쓴다
    CONSTRAINT order_dispatch_uk UNIQUE (order_group_id, wholesaler_id),

    CONSTRAINT order_dispatch_status_ck CHECK (
        status IN ('PENDING', 'SENT', 'REJECTED', 'EXPIRED', 'CANCELLED'))
);

COMMENT ON TABLE retail.order_dispatch IS
    '도매 접수 대기함. 주문서와 한 트랜잭션에 쓰고 워커가 재시도한다 (MUL-139)';

COMMENT ON COLUMN retail.order_dispatch.status IS
    'PENDING = 보낼 차례를 기다림 · SENT = 접수됨 · REJECTED = 도매가 거절(재시도 안 함) '
    '· EXPIRED = 기한/횟수 초과 · CANCELLED = 소매처가 대기를 취소';

COMMENT ON COLUMN retail.order_dispatch.payload IS
    '도매에 보낼 명령. 부를 때의 값으로 굳힌다 — 나중에 다시 조립하면 그 사이 바뀐 가격이 섞인다';


-- 워커가 "지금 보낼 것" 만 찾는다. 보낼 게 없는 평상시가 대부분이라
-- PENDING 만 담는 부분 인덱스로 훑는 양을 줄인다
CREATE INDEX order_dispatch_due_idx
    ON retail.order_dispatch (next_attempt_at)
    WHERE status = 'PENDING';

-- 주문 상세가 "이 주문서의 대기 건" 을 읽는다
CREATE INDEX order_dispatch_group_idx
    ON retail.order_dispatch (order_group_id);
