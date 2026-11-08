-- ═══════════════════════════════════════════════════════════════
--  채팅 (MUL-158) — 방 · 메시지 · 보내기 멱등
-- ═══════════════════════════════════════════════════════════════
--
-- 근거: docs/design/chat.md a 안 — 방·메시지·멱등 키의 원본은 도매 DB 하나다.
-- 소매 채팅(MUL-157)은 /api/retail-gateway/chat/* 를 거쳐 같은 테이블을 읽고 쓴다.
-- 그래서 이 스키마는 처음부터 양쪽(도매처, 소매처)을 다 담는다 — 읽음 위치, 보낸 쪽,
-- 멱등 키 범위가 전부 "누가"를 칸으로 갖는다.
--
-- 값이 늘 수 있는 enum(kind, handoff_reason, intent)은 varchar 에 CHECK 를 걸지 않는다.
-- 계약이 "값이 늘 수 있다"고 적었고, 늘 때마다 마이그레이션이 생기지 않게 한다.
-- Java enum 이 최종 방어다. sender 는 세 값으로 닫혀 있어 CHECK 를 건다.

-- 방은 (도매처, 소매처) 사업체 쌍에 1개. 양쪽이 같은 id 를 본다.
-- 거래(partner) 전에도 방이 생길 수 있어 partner 를 참조하지 않는다.
CREATE TABLE wholesale.chat_room (
    id                             bigserial   PRIMARY KEY,
    wholesaler_id                  bigint      NOT NULL REFERENCES wholesale.wholesaler (id),
    retailer_id                    bigint      NOT NULL,           -- 논리 참조(경계). FK 없음
    retailer_name                  varchar(50) NOT NULL,           -- 소매 상호 스냅샷 (partner.retailer_name 과 같은 길이)
    -- 방 안 순번 채번. 보내기가 이 행을 FOR UPDATE 로 잡고 +1 한다 — 방 단위로 직렬화돼
    -- 커밋 순서대로 sequence 가 붙는다. 값 자체가 maxSequence(메시지 없으면 0)다.
    last_sequence                  bigint      NOT NULL DEFAULT 0,
    -- 목록 정렬 키. 마지막 메시지 시각, 메시지가 없으면 방 생성 시각
    last_activity_at               timestamptz NOT NULL DEFAULT now(),
    last_message_id                bigint,                         -- 목록 미리보기. FK 는 메시지 테이블 뒤에 건다
    -- 열린 사람 넘김: 마지막 WHOLESALER TEXT 뒤에 온 가장 최근 HANDOFF 줄의 sequence.
    -- HANDOFF 가 오면 그 sequence, WHOLESALER TEXT 가 오면 NULL. AUTO_REPLY 로는 닫히지 않는다.
    -- 목록이 15초마다 방마다 계산하지 않게 쓰기 때 갱신한다. 이유는 이 sequence 의 줄에서 읽는다.
    open_handoff_sequence          bigint,
    -- 읽음 위치는 사업체 단위. 도매는 "안 읽었으면 0", 소매는 "한 번도 안 읽었으면 NULL"
    -- (도매 응답의 counterpartLastReadSequence 가 null 을 구분한다)
    wholesaler_last_read_sequence  bigint      NOT NULL DEFAULT 0,
    retailer_last_read_sequence    bigint,
    created_at                     timestamptz NOT NULL DEFAULT now(),
    updated_at                     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chat_room_pair_uk UNIQUE (wholesaler_id, retailer_id),
    CONSTRAINT chat_room_last_sequence_ck CHECK (last_sequence >= 0),
    CONSTRAINT chat_room_wholesaler_read_ck
        CHECK (wholesaler_last_read_sequence BETWEEN 0 AND last_sequence),
    CONSTRAINT chat_room_retailer_read_ck
        CHECK (retailer_last_read_sequence IS NULL
               OR retailer_last_read_sequence BETWEEN 0 AND last_sequence)
);

-- 방 목록 키셋 (lastActivityAt desc, roomId desc). 도매 목록은 wholesaler_id, 소매 목록은 retailer_id 로 자른다
CREATE INDEX chat_room_wholesaler_activity_idx
    ON wholesale.chat_room (wholesaler_id, last_activity_at DESC, id DESC);
CREATE INDEX chat_room_retailer_activity_idx
    ON wholesale.chat_room (retailer_id, last_activity_at DESC, id DESC);

-- 메시지 1건. 수정·삭제 없음(append-only). 사람 글(TEXT)과 서버가 만든 줄(AUTO_REPLY, CLARIFY, HANDOFF)이 같은 줄에 선다.
CREATE TABLE wholesale.chat_message (
    id                    bigserial   PRIMARY KEY,                  -- 전역 식별자. 순서에 쓰지 않는다
    room_id               bigint      NOT NULL REFERENCES wholesale.chat_room (id),
    sequence              bigint      NOT NULL,                     -- 방 안 순번. 1부터
    sender                varchar(20) NOT NULL,                     -- WHOLESALER | RETAILER | SYSTEM
    kind                  varchar(20) NOT NULL,                     -- TEXT | AUTO_REPLY | CLARIFY | HANDOFF (늘 수 있다)
    body                  text        NOT NULL,                     -- 앞뒤 공백을 지운 값
    client_message_id     uuid,                                     -- 사람이 보낸 TEXT 의 Idempotency-Key. 서버가 만든 줄은 NULL
    -- 소매가 칩으로 고른 문의 문맥(ChatInquiry). inquiry_intent 가 NULL 이면 문맥 없음
    inquiry_intent        varchar(20),
    inquiry_listing_id    bigint,
    inquiry_variant_id    bigint,
    inquiry_backorder_id  bigint,
    inquiry_label         text,
    inquiry_not_listed    boolean     NOT NULL DEFAULT false,
    -- 서버가 만든 줄의 근거. 문의 지표(MUL-159)가 칸으로 집계하도록 JSON 밖에 둔다.
    --   intent           AUTO_REPLY·CLARIFY 는 autoReply.intent, HANDOFF 는 handoff.intent(못 읽었으면 NULL)
    --   source_sequence  AUTO_REPLY·CLARIFY 는 autoReply.replyToSequence, HANDOFF 는 handoff.sourceSequence
    --   handoff_reason   HANDOFF 에만. ChatHandoffReason(지금 21개). CHECK 없음 — 값이 늘 수 있다
    intent                varchar(20),
    source_sequence       bigint,
    handoff_reason        varchar(40),
    -- 자동 답(AUTO_REPLY, CLARIFY)의 나머지 근거: {generatedBy, checkedAt, subject, facts, choices}.
    -- facts 는 조회 시점 스냅샷이라 다시 계산하지 않고 그대로 둔다. 도매용 정확 수량이 들어 있다 —
    -- 소매 응답으로 옮길 때는 소매 facts 모양으로 골라 싣는다(D31②)
    auto_reply            jsonb,
    sent_at               timestamptz NOT NULL DEFAULT now(),       -- 서버가 받은 시각
    CONSTRAINT chat_message_sequence_uk UNIQUE (room_id, sequence),  -- polling(afterSequence)·이전 보기 인덱스 겸용
    CONSTRAINT chat_message_sequence_ck CHECK (sequence >= 1),
    CONSTRAINT chat_message_sender_ck CHECK (sender IN ('WHOLESALER','RETAILER','SYSTEM')),
    CONSTRAINT chat_message_body_ck CHECK (char_length(body) BETWEEN 1 AND 1000),
    -- 넘김 줄이면 이유가 늘 있고, 넘김 줄이 아니면 이유가 없다
    CONSTRAINT chat_message_handoff_ck CHECK ((kind = 'HANDOFF') = (handoff_reason IS NOT NULL)),
    CONSTRAINT chat_message_client_id_ck CHECK (client_message_id IS NULL OR sender <> 'SYSTEM'),
    CONSTRAINT chat_message_inquiry_ck CHECK (inquiry_intent IS NOT NULL OR (
        inquiry_listing_id IS NULL AND inquiry_variant_id IS NULL AND inquiry_backorder_id IS NULL
        AND inquiry_label IS NULL AND inquiry_not_listed = false))
);

-- 미확인 수 = 상대(sender)가 보낸 것 중 내 읽음 위치 초과분. 방·보낸 쪽·순번으로 바로 센다
CREATE INDEX chat_message_room_sender_sequence_idx
    ON wholesale.chat_message (room_id, sender, sequence);

-- 같은 말풍선(Idempotency-Key)이 한 방·한 보낸 쪽에서 두 번 저장되지 않는다. 멱등 테이블의 마지막 방어선
CREATE UNIQUE INDEX chat_message_client_id_uk
    ON wholesale.chat_message (room_id, sender, client_message_id)
    WHERE client_message_id IS NOT NULL;

ALTER TABLE wholesale.chat_room
    ADD CONSTRAINT chat_room_last_message_fk
        FOREIGN KEY (last_message_id) REFERENCES wholesale.chat_message (id);

-- 보내기 멱등. 키 범위는 (방, 보낸 사업체) — 도매와 소매가 우연히 같은 uuid 를 써도 겹치지 않는다.
-- 같은 키 + 같은 본문은 첫 응답 본문을 그대로 내린다(자동 답을 다시 만들지 않는다). 그래서 응답 본문을 저장한다.
-- request_hash 는 같은 키에 다른 본문(409 IDEMPOTENCY_KEY_REUSED)을 가려내는 지문이다.
-- 보관 24시간(잠정, 계약). 지우는 작업은 아직 없다 — created_at 으로 지운다.
CREATE TABLE wholesale.chat_send_idempotency (
    room_id          bigint      NOT NULL REFERENCES wholesale.chat_room (id),
    sender           varchar(20) NOT NULL,
    idempotency_key  uuid        NOT NULL,
    request_hash     varchar(64) NOT NULL,                       -- [body, inquiry] JSON 의 SHA-256 hex
    message_id       bigint      NOT NULL REFERENCES wholesale.chat_message (id),
    response_body    text        NOT NULL,                       -- 첫 201 의 data 페이로드 JSON (ChatSendResult)
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chat_send_idempotency_pk PRIMARY KEY (room_id, sender, idempotency_key),
    CONSTRAINT chat_send_idempotency_sender_ck CHECK (sender IN ('WHOLESALER','RETAILER'))
);
