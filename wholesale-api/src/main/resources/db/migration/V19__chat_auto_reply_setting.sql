-- ═══════════════════════════════════════════════════════════════
--  채팅 자동 답 설정 · 문의 지표 인덱스 (MUL-159)
-- ═══════════════════════════════════════════════════════════════
--
-- 도매처(사업체) 단위 설정 한 행. 같은 사업체의 탭·기기·직원이 같은 설정을 본다.
-- 행이 없으면 전부 켜짐이다(계약: "저장한 적이 없으면 전부 true") — 기본값을 행으로 미리 깔지 않는다.
-- PUT 은 통째로 바꾼다(부분 수정 없음)라 칸은 전부 NOT NULL 이다.
-- 소매 보내기 트랜잭션이 판정 직전에 이 행을 읽는다. 바꾼 뒤 들어오는 메시지부터 걸린다.
--
-- OTHER 는 늘 사람 넘김이라 칸이 없다(계약 AutoReplyIntentSettings).
--
-- V20(방 상품 문맥, MUL-157)이 먼저 머지됐다. out-of-order: true 라 V19 가 뒤에 와도 적용된다.

CREATE TABLE wholesale.chat_auto_reply_setting (
    wholesaler_id       bigint      PRIMARY KEY REFERENCES wholesale.wholesaler (id),
    enabled             boolean     NOT NULL,   -- false 면 소매 문의 전부 사람 넘김(OFF)
    stock_enabled       boolean     NOT NULL,   -- intents.STOCK
    backorder_enabled   boolean     NOT NULL,   -- intents.BACKORDER
    receivable_enabled  boolean     NOT NULL,   -- intents.RECEIVABLE
    updated_at          timestamptz NOT NULL DEFAULT now()
);

-- 문의 지표: 소매 메시지(출처) 하나에 붙은 결과 줄(AUTO_REPLY·CLARIFY·HANDOFF)을 (방, 출처 sequence) 로 찾는다.
-- 사람 글(TEXT)은 source_sequence 가 NULL 이라 부분 인덱스로 뺀다.
CREATE INDEX chat_message_source_idx
    ON wholesale.chat_message (room_id, source_sequence)
    WHERE source_sequence IS NOT NULL;

COMMENT ON TABLE wholesale.chat_auto_reply_setting IS
    '채팅 자동 답 설정. 도매처 단위, 행이 없으면 전부 켜짐 (MUL-159)';
