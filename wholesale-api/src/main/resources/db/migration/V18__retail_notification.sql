-- 소매처에게 가는 알림 (MUL-155). 설계: docs/design/notification.md
--
-- 도매 DB 에 두는 이유 — 알림을 만드는 사건(입고 예정일 변경, 앞으로 주문 확정·배분·출고)이
-- 전부 도매 트랜잭션 안에서 일어난다. 같은 DB 라 사건과 알림이 한 커밋으로 묶이고
-- 서버 간 전달·재시도가 없다. 소매 3 op 는 /api/retail-gateway/notifications* 를 부르는 프록시다.
--
-- 도매 화면용 알림(MUL-154, V16)과는 다른 테이블이다. 받는 사람이 다르고(도매처 ↔ 소매처)
-- 읽음 범위도 다르다. 한 테이블에 섞으면 조회마다 "누구 몫" 조건을 빠뜨릴 자리가 생긴다.
--
-- V18 인 건 V15(입금 계좌)·V16(도매 알림)·V17(채팅)이 다른 티켓 예약이라서다. out-of-order 가 켜져 있다.

CREATE TABLE wholesale.retail_notification (
    id                    bigserial    PRIMARY KEY,
    retailer_id           bigint       NOT NULL,              -- 받는 소매 사업체. 논리 참조(경계). FK 없음
    type                  varchar(40)  NOT NULL,              -- CHECK 없음 — 값이 늘 수 있다(계약). Java enum 이 최종 방어
    title                 varchar(200) NOT NULL,              -- 서버가 만든 문구. 남의 소매처 정보를 싣지 않는다
    body                  varchar(500),
    target_kind           varchar(20)  NOT NULL,
    target_order_id       bigint,                             -- ORDER → 소매 주문 id
    target_wholesaler_id  bigint,                             -- BACKORDERS → 도매처 id
    created_at            timestamptz  NOT NULL DEFAULT now(),
    read_at               timestamptz,                        -- 처음 읽은 시각. 다시 읽어도 안 바뀐다
    CONSTRAINT retail_notification_target_ck CHECK (
        (target_kind = 'ORDER'      AND target_order_id IS NOT NULL AND target_wholesaler_id IS NULL) OR
        (target_kind = 'BACKORDERS' AND target_wholesaler_id IS NOT NULL AND target_order_id IS NULL))
);

-- 목록 keyset (created_at desc, id desc) 과 최신 1건(latestNotificationId)
CREATE INDEX retail_notification_page_idx
    ON wholesale.retail_notification (retailer_id, created_at DESC, id DESC);

-- 안 읽은 수 (30초 polling) · 모두 읽음
CREATE INDEX retail_notification_unread_idx
    ON wholesale.retail_notification (retailer_id) WHERE read_at IS NULL;
