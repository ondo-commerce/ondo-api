-- ═══════════════════════════════════════════════════════════════
--  채팅방 상품 문맥 (MUL-157)
-- ═══════════════════════════════════════════════════════════════
--
-- 소매 createChatRoom 의 listingId 와 그 시각. 자동 답 판정이 "이 상품" 을 읽는 값이다
-- (계약: "방의 상품 문맥을 이 값과 지금 시각으로 바꾼다(null 이면 지운다, 이미 있는 방도). 30분 지나면 문맥 없음").
-- 방을 가르지 않는다 — 다른 상품에서 같은 도매처에 물어도 같은 방이고 문맥만 바뀐다.
--
-- 게시글 FK 를 걸지 않는다. 소매가 보낸 값을 그대로 남기고, 남의 도매처 게시글·지워진 게시글인지는
-- 판정이 매번 본다(INVALID_TARGET 넘김). 저장 시점에 거르면 그 사이 바뀐 상태를 놓친다.
-- V19 는 자동 답 설정(MUL-159) 예약이다. out-of-order: true 라 번호 순서와 머지 순서가 달라도 된다.

ALTER TABLE wholesale.chat_room
    ADD COLUMN context_listing_id bigint,
    ADD COLUMN context_at         timestamptz,
    ADD CONSTRAINT chat_room_context_ck
        CHECK ((context_listing_id IS NULL) = (context_at IS NULL));
