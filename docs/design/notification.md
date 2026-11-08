# 소매 알림 · 입고 예정일 변경 연동 설계 (MUL-155)

MUL-152 「알림 API」의 소매 쪽 설계 메모다. 소매 3 op(`notifications` · `markNotificationsRead` · `notificationUnreadCount`)와
도매 `PUT /api/wholesale/variants/{id}/expected-inbound` 가 소매처에게 알림을 남기는 길을 정한다.

> **아래 「제안」은 사람 확인이 필요하다.** 확정되면 「제안」을 「결정」으로 바꾸고 날짜를 적는다.

## 기준 자료

웹 레포 `ondo-commerce/ondo-web` `dev` 에서 읽었다.

| 무엇 | 경로 |
|---|---|
| 계약 (초안 `x-ondo-draft: true`) | `packages/api/openapi/retail.json`, `openapi/drafts/retail.json` |
| 문구 규칙 (목 = BE 규칙) | `packages/api/src/mocks/retail/dateChangeNotice.ts` |
| 목록·읽음 동작 | `packages/api/src/mocks/retail/notification.ts` |

## a. 알림은 어느 DB 가 갖나

| 안 | 내용 | 득 | 실 |
|---|---|---|---|
| **A. 도매 DB** | `wholesale.retail_notification`. 사건과 같은 트랜잭션에서 행을 만든다. 소매 3 op 는 `/api/retail-gateway/notifications*` 프록시 | 사건과 알림이 한 커밋 — 분산 쓰기·재시도가 없다. 받는 사람(그 SKU 에 OPEN 미송이 있는 소매처)과 남은 수량을 같은 트랜잭션에서 읽는다. 채팅 설계(MUL-156 `docs/design/chat.md` 안 A)와 같은 모양이라 배지 하나로 알림·채팅을 함께 받을 수 있다 | 소매 알림 화면이 도매 가용성에 묶인다. 소매 polling(30초 배지)이 한 홉 더 탄다 |
| B. 소매 DB | 소매 `notification` 테이블(V6 예약). 도매가 소매를 HTTP 로 불러 넣는다 | 소매가 로컬로 읽는다 | **도매 → 소매 호출이 새로 생긴다**(지금은 소매 → 도매 한 방향뿐). 날짜 변경이 실패하면 안 되니 도매에 outbox + 재시도 워커가 필요하고, 중복 전달 멱등 키가 필요하다. 문구의 남은 수량이 "보낸 순간" 값이라 배분과 순서가 어긋날 수 있다 |

**제안: A.** 근거 셋.

1. 웹 목이 이미 이렇게 적었다 — "BE는 `expected-inbound` PUT 커밋 안에서 이 규칙대로 알림 행을 만든다", 남은 수량은
   "그 트랜잭션이 읽은 이 소매처의 그 SKU 남은 합", "배분이 먼저 커밋돼 남은 수량이 0이 된 소매처는 0건(커밋 순서대로)".
   한 트랜잭션은 도매 DB 안에서만 가능하다.
2. 「서버 간 전달 실패가 날짜 변경을 실패시키면 안 된다」는 요구를 **전달 자체를 없애서** 지킨다. 쓰기 시점에 서버를 건너지 않는다.
3. 나머지 알림(주문 확정 · 미송 배분 · 출고)도 사건이 전부 도매에서 일어난다. B 면 사건마다 outbox 가 늘어난다.

**티켓과 다른 점:** 티켓은 소매 V6 테이블을 적었다. A 를 택하면 소매에는 알림 테이블이 없고 **소매 V6 는 반납**한다.
도매 마이그레이션은 **V18** 이다(V15 입금 계좌 · V16 도매 알림 MUL-154 · V17 채팅이 예약). `chat.md` 의 표가 V18 을
자동 답 설정(MUL-159) 제안으로 적어 두었으니 그쪽이 V19 로 옮기거나, 머지 전에 번호를 다시 맞춘다. 도매는 `out-of-order: true` 라 순서는 상관없다.

도매 화면용 알림(MUL-154, V16)과는 **테이블을 나눈다.** 받는 사람(도매처 ↔ 소매처)과 읽음 범위가 달라서, 한 테이블이면
조회마다 "누구 몫" 조건을 빠뜨릴 자리가 생긴다.

## b. 입고 예정일 변경 → 알림

`ExpectedInboundService.register` 한 트랜잭션 안에서:

1. variant 행을 `FOR UPDATE` 로 잠그고 바뀌기 전 날짜를 읽는다(같은 SKU 의 동시 PUT 둘이 같은 "전" 값을 보지 않게).
2. 날짜·사유를 대체한다(기존 동작 그대로, 응답 모양 그대로).
3. `RetailNotificationWriter.backorderDateChanged` — **날짜가 바뀔 때만**(미정 ↔ 날짜 포함, 사유만 바뀌면 0건).
   그 SKU 에 `status = 'OPEN'` 이고 남은 수량(`qty − allocated_qty`)이 0 보다 큰 미송을 가진 소매처마다 1건.
   소매 주문서가 있는 주문(`retail_order_id IS NOT NULL`)만 센다 — 소매 미송 화면과 같은 조건이다.

문구는 웹 목과 글자가 같다(`BackorderDateChangeText`).

- title `"{도매처명} 미송 입고일 변경"`
- body `"{상품명} {색}/{사이즈} 남은 {이 소매처 남은 합}장, {전} → {후}"` — 날짜 `M/D(요일)`, 해가 다르면 `YYYY/M/D(요일)`, 없으면 `입고일 미정`
- target `{kind: BACKORDERS, wholesalerId}`

문구를 만드는 함수의 인자에 다른 소매처 이름·수량, SKU 합계, 판매가능 수량이 **아예 없다.** 소매처마다 따로 합을 낸다.

## c. 실패 · 재시도 규칙

| 상황 | 동작 |
|---|---|
| 알림 INSERT 가 실패(도매 DB 장애) | 날짜 변경과 **같이 롤백**된다. 같은 DB 라 둘 중 하나만 남는 일이 없다. 사용자가 PUT 을 다시 누른다 |
| 소매 서버가 죽어 있음 | 도매 PUT 은 영향 없다. 쓰기 시점에 소매를 안 부른다 |
| 소매 → 도매 게이트웨이 실패(목록·배지·읽음) | 소매 500 `INTERNAL_ERROR`(계약에 502 가 없다). **서버는 재시도하지 않는다** — 배지는 화면이 30초마다 다시 부르고, 목록·읽음은 사용자가 다시 누른다. 읽음은 멱등이라 다시 보내도 안전하다 |
| 「모두 읽음」 수가 어긋남 | 도매가 세기·바꾸기를 한 문장(`FOR UPDATE` CTE)에서 하고 수가 다르면 0행 → 409 `UNREAD_CHANGED`. 소매가 같은 코드로 옮긴다 |

재시도 outbox 가 필요 없는 건 A 의 결과다. B 로 뒤집히면 이 표를 다시 쓴다(MUL-137 주문 접수 outbox 를 본뜬다).

## d. 소매 3 op

| op | 소매 | 도매 게이트웨이 |
|---|---|---|
| 목록 | `GET /api/retail/notifications?cursor&size` | `GET /api/retail-gateway/notifications?retailerId&limit&beforeMicros&beforeId` |
| 읽음 | `POST /api/retail/notifications/read` | `POST /api/retail-gateway/notifications/read` `{retailerId, ids \| all+expectedUnread}` |
| 배지 | `GET /api/retail/notifications/unread-count` | `GET /api/retail-gateway/notifications/unread-count?retailerId` |

- 소매처 id 는 소매 **세션에서만** 꺼내 싣는다. 읽음 범위 = 소매 사업체(로그인한 소매처).
- 프론트 검증(size 1..100 기본 20, 커서, 본문 `oneOf`)과 에러 칸(`errors[{field}]`)은 **소매가** 한다 — 에러 모양이 소매 계약이다.
- 커서는 소매가 만든다. 불투명 base64url(`"<epoch µs>|<id>"`), keyset `(created_at, id) <`. 소매가 `size + 1` 을 물어 다음 장을 안다.
  시각을 마이크로초 숫자로 넘기는 건 ① timestamptz 정밀도와 맞추고 ② ISO 문자열의 `+` 가 쿼리에서 공백으로 바뀌는 사고를 피하려는 것이다.
- 배지의 `chats` 는 채팅 게이트웨이 `GET /api/retail-gateway/chat/unread-count` 가 준다(MUL-157). 소매가 알림 배지와 함께 부른다.

## 남은 일

- 주문 확정 · 미송 배분 · 출고 알림은 각 사건을 붙이는 티켓에서 `RetailNotificationWriter` 에 메서드를 더한다.
- 알림 보존 기간(오래된 행 정리)은 정하지 않았다.
