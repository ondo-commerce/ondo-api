-- ═══════════════════════════════════════════════════════════════
--  도매 알림 (MUL-154)
--
--  알림은 도매처(사업체) 단위로 쌓고 읽는다. 같은 사업체의 탭·기기·직원이
--  같은 읽음 상태를 본다 — 그래서 읽음을 사람별 테이블로 빼지 않고 이 행에 둔다.
--
--  문구(title, body)는 만들 때 굳힌다. 나중에 상품명이 바뀌어도 그때 받은
--  알림의 글자는 그대로다.
--
--  target 은 지금 ORDER 하나뿐이라 FK 를 걸 수도 있지만 kind 가 늘 자리라 논리 참조로 둔다.
--
--  V15 는 계좌(#74), V17 은 채팅 몫으로 비워 둔다 (out-of-order: true).
-- ═══════════════════════════════════════════════════════════════

CREATE TABLE wholesale.notification (
    id             bigserial    PRIMARY KEY,                   -- 식별용. 순서 의미 없음
    wholesaler_id  bigint       NOT NULL REFERENCES wholesale.wholesaler (id),
    type           varchar(30)  NOT NULL,                      -- NEW_ORDER · ORDER_CANCELLED · (늘 수 있다)
    title          varchar(200) NOT NULL,                      -- 서버가 만든 한 줄
    body           varchar(500),                               -- 없으면 NULL
    target_kind    varchar(20)  NOT NULL,                      -- ORDER
    target_id      bigint       NOT NULL,                      -- 도매 주문 id (논리 참조)
    created_at     timestamptz  NOT NULL DEFAULT now(),
    read_at        timestamptz                                 -- 처음 읽은 시각. NULL = 안 읽음
);

-- 목록 keyset (created_at desc, id desc) 을 그대로 따라 읽는다
CREATE INDEX notification_list_idx
    ON wholesale.notification (wholesaler_id, created_at DESC, id DESC);

-- 30초마다 부르는 안 읽음 수 · 모두 읽음
CREATE INDEX notification_unread_idx
    ON wholesale.notification (wholesaler_id)
    WHERE read_at IS NULL;

COMMENT ON TABLE wholesale.notification IS
    '도매 알림. 읽음 범위는 도매처(사업체) 단위 (MUL-154)';
