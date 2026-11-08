# 채팅 · 문의 자동 답 설계 (MUL-156)

MUL-153 「채팅 방·메시지와 규칙 자동 답」의 설계 메모다. 이 티켓에는 운영 엔드포인트가 없다 — 골든셋 테스트 틀과 이 문서만 있다.

> **아래 「제안」은 전부 사람 확인이 필요하다.** 확정되면 「제안」을 「결정」으로 바꾸고 날짜를 적는다.

## 기준 자료

웹 레포 `ondo-commerce/ondo-web` `dev` @ `cdb20a127b3343b6052af426215dfe1ee219ebeb` 에서 읽었다.

| 무엇 | 경로 |
|---|---|
| 계약 (초안 `x-ondo-draft: true`) | `packages/api/openapi/{retail,wholesale}.json`, `openapi/drafts/` |
| 판정 규칙 (목 = BE 규칙) | `packages/api/src/mocks/chat/inquiry.ts` (+ `backorderCandidates.ts`, `receivable.ts`, `packingOrders.ts`) |
| 골든셋 v7 | `packages/api/openapi/golden/inquiry-golden.json` → 이 레포 `wholesale-api/src/test/resources/golden/` |

초안 op — 소매 6: `chatRooms` `createChatRoom` `chatRoom` `chatMessages` `sendChatMessage` `markChatRead`.
도매 7: `chatRooms` `chatRoom` `chatMessages` `sendChatMessage` `markChatRead` `autoReplySettings`(GET/PUT) `inquiryStats`.

## a. 방·메시지는 어느 DB 가 갖나

계약이 이미 정한 것: **방 id 는 하나다** (`createChatRoom` — "도매 쪽과 같은 roomId"). 방은 (도매처, 소매처) 쌍에 1개.
그러니 원본은 한 DB 에만 있어야 한다. 둘 다 가지면 동기화가 생긴다.

| 안 | 내용 | 득 | 실 |
|---|---|---|---|
| **A. 도매 DB** | 방·메시지·멱등 키를 도매가 갖는다. 판정도 도매에서. 소매 6 op 는 `/api/retail-gateway/chat/*` 를 부르는 얇은 프록시 | 판정 원본(재고·미송·미수·포장)이 전부 같은 DB — 저장과 판정이 한 트랜잭션. 사장 답이 같은 테이블에 들어가 소매 polling 에 그냥 보인다. 설정·통계(도매 op)도 로컬. 정확 수량이 서버 밖으로 안 나간다 | 소매 채팅이 도매 가용성에 묶인다. 소매 polling 이 전부 한 홉 더 탄다 |
| B. 소매 DB | 소매가 갖고 도매 5 op 가 소매를 부른다 | 보내는 쪽이 로컬 | **도매 → 소매 호출이 새로 생긴다**(지금은 한 방향뿐). 판정 원본 4개를 전부 원격으로 읽는다. 도매 facts 의 정확 수량(`ChatWholesaleStockFacts.availableQty`)을 소매 DB 에 저장하게 된다 |
| C. 양쪽 복제 | 각자 사본 + 동기화 | 각자 로컬 읽기 | 한 방 id 계약과 순서(sequence)를 두 DB 가 맞춰야 한다. 버린다 |

**제안: A.** 근거 셋 — ① 판정 입력이 전부 도매 데이터다 ② 사장 답이 소매에게 보여야 하는데 A 는 같은 테이블이라 별도 전달이 없다 ③ 정확 수량을 소매로 넘기지 않는다는 원칙(D31②)을 네트워크 단에서 지킨다.

polling 부하(A 기준, 소매 → 도매 게이트웨이):

| 화면 | 주기 | 동시 1,000 명이 다 켜 두면 |
|---|---|---|
| 열린 방 `chatMessages?afterSequence` | 3초 | ≈ 333 rps |
| 방 목록 `chatRooms` | 15초 | ≈ 67 rps |
| 헤더 배지 | 30초 | ≈ 33 rps |

- 열린 방은 `(room_id, sequence)` 인덱스 한 번이면 끝나는 질의라 감당한다. 숫자는 추정이다 — MUL-157 에서 `load/` 에 측정을 남긴다.
- 배지는 방 목록을 다 받지 않게 `GET /api/retail-gateway/chat/unread-count?retailerId=` 하나를 따로 두는 걸 **제안**한다(게이트웨이 내부 경로라 프론트 계약이 아니다).
- 소매처 표시 이름: 거래 전 소매처가 방을 열 수도 있다. `createChatRoom` 게이트웨이 호출에 소매가 `retailerName` 을 실어 보내고 도매가 방에 스냅샷으로 남긴다(**제안**).

## b. 판정 데이터는 어디서 오나

A 안이면 판정이 도매 안에서 돈다 — **새 게이트웨이 조회는 필요 없다.** 아래 표는 「B 안이었다면」의 빈칸이기도 하다.

| 원본 | 판정이 쓰는 칸 (골든 표) | 도매 내부 | 지금 게이트웨이 | 빠진 것 |
|---|---|---|---|---|
| 재고 | variantId, color, size, availableQty, orderLimit, 게시 여부 | `stock_qty - reserved_qty` (`VariantInfoReader`, `BackorderQueryService`) | `/listings/{id}` · `/variants` — 옵션·한도·`orderable` 만, **수량 없음** | 구간(band). 소매로는 **`band` + `orderable` + `orderLimit` 만** (`ChatRetailStockFacts`). 정확 수량 칸은 응답에 아예 싣지 않는다 |
| 미송 | backorderId, orderId, wholesalerId, retailerId, variantId, qty·remainingQty, 입고 예정일·사유, status | 미송 테이블 | `/backorders?retailerId` — OPEN 만, 페이지 ≤100 | 도매처 필터, `variantId`, `remainingQty`·`status`, `orderId` |
| 미수 | balance, unpaid, prepaid, overdue{amount,count,maxDays}, exact, asOf | `ReceivableQueryService`, `PrepaidQueryService`, `OverdueCalculator` | `/settlements?retailerId` — balance, overdue | `unpaid`, `prepaid`, `exact`, `asOf`. 판정은 이상값 7종(`receivableAnomalies`)을 보고 넘긴다 — 이 칸 없이는 판정 불가 |
| 포장 | READY + 봉투 없음 / PACKED + 봉투 출고 전, 주문으로 묶음 | `Packing`, `PackingStatus{READY,PACKED}` | 없음 (`/orders` 는 상태 키만) | 전부 |

판정 원본은 "거르지 않은 전체"를 받고 이 방 도매처·소매처 고르기는 판정이 한다(골든 `about`). 도매 내부 구현에서도 이 경계를 지키면 골든 어댑터가 그대로 붙는다.

## c. D46 — `ChatHandoffReason` 에 `HUMAN_REQUEST`

- 도매 초안 enum 은 **이미 21개이고 `HUMAN_REQUEST` 가 들어 있다**: OFF, NEGOTIATION, MULTI_INTENT, NO_INTENT, NOT_SUPPORTED, NO_CONTEXT, NO_OPTIONS, INVALID_TARGET, CLARIFY_AGAIN, NOT_LISTED, NO_BACKORDER, NO_MATCH, STOCK_ZERO, ETA_UNKNOWN, ETA_PAST, PAYMENT_REPORT, PERIOD, NO_RELATION, AMOUNT_UNSURE, LOOKUP_FAILED, HUMAN_REQUEST.
- `inquiry.ts` 는 판정 1단계(꺼짐 다음, 협상어 앞)에서 사람 요청을 본다. intent 는 null. "사장님, 미수 얼마예요?" 처럼 호칭만으로는 안 넘긴다.
- 골든 U01~U09 가 이 이유를 기대한다 — 빼면 골든을 통과할 수 없다.
- 소매 `ChatMessage` 에는 넘김 이유 칸이 없다. 도매 화면·통계에만 보인다.

**제안: 넣는다(21개 그대로).** enum 설명이 "값이 늘 수 있다. 모르는 값은 이유 줄 없이 그린다"라 추가는 안전하다. DB 칸은 `varchar` 로 두고 CHECK 를 걸지 않는다 — 값이 늘 때마다 마이그레이션이 생기지 않게. Java enum 이 최종 방어다.

## d. D49 — 소매 `sendChatMessage` 의 거절

초안 스펙이 정한 코드는 이것뿐이다: 400 `VALIDATION_FAILED` · 401 `UNAUTHORIZED` · 403 `ACCOUNT_NOT_APPROVED` · 404 `RESOURCE_NOT_FOUND` · 409 `IDEMPOTENCY_KEY_REUSED` · 500 `INTERNAL_ERROR`.

| 응답 | 언제 | 같은 키로 다시 보내면 | 분류 |
|---|---|---|---|
| 403 `ACCOUNT_NOT_APPROVED` | 승인 전 계정 | 승인 전까지 늘 같다 | **영구** |
| 404 `RESOURCE_NOT_FOUND` | 없는 방 · 남의 방(존재를 숨긴다) | 늘 같다 | **영구** |
| 409 `IDEMPOTENCY_KEY_REUSED` | 같은 키 + 다른 본문 | 늘 같다 | 영구 (그 키에 한해) |
| 400 `VALIDATION_FAILED` | 빈 본문 · 1,000자 초과 · 키 형식 | 늘 같다 | 영구 (그 본문에 한해) |
| 401 | 세션 끊김 | 다시 로그인하면 바뀐다 | 일시 |
| 500 · 네트워크 | 서버 문제 | 같은 키 재전송이 안전하다(200 replay) | 일시 |

**제안**
- 403·404 는 영구 거절로 둔다. 화면은 다시 보내기를 멈추고 말풍선을 실패로 고정한다.
- 403 코드를 지금 더 만들지 않는다. 차단·정지 같은 사유가 생기면 그때 `ACCOUNT_NOT_APPROVED` 와 **다른 코드**로 추가하고 영구로 분류한다(사람에게 먼저 묻는다 — 계약 변경).
- A 안에서 도매 게이트웨이에 닿지 못하면 소매는 500 `INTERNAL_ERROR` 로 낸다. 스펙에 502 가 없어서다. 주문의 `UPSTREAM_UNAVAILABLE`(502)을 채팅에도 열지는 사람이 정한다.
- 게이트웨이 404 는 소매 404 로 그대로 옮긴다. 소매가 방 존재 여부를 따로 판단하지 않는다.

## e. Flyway 번호

| 서버 | 번호 | 용도 | 상태 |
|---|---|---|---|
| 도매 | V15 | 입금 계좌 (MUL-130) | 머지됨 |
| 도매 | V16 | 도매 알림 (MUL-154) | 머지됨 |
| 도매 | **V17** | `chat_room` · `chat_message`(intent, handoff_reason 칸 포함) · 멱등 키 (MUL-158) | 머지됨 |
| 도매 | V18 | 소매 알림 `retail_notification` (MUL-155) | 머지됨 |
| 도매 | V19 | 자동 답 설정 (MUL-159) | **예약** |
| 도매 | V20 | `chat_room.context_listing_id` · `context_at` — 방 상품 문맥 (MUL-157) | 이 브랜치 |
| 소매 | V6 · V7 | 알림 · 채팅 | **반납** — A 안(도매 소유)으로 정해져 소매엔 두 테이블이 없다 |

도매는 `spring.flyway.out-of-order: true` 라 번호 순서와 머지 순서가 달라도 적용된다. 번호만 겹치지 않으면 된다. (2026-11-08 갱신)

## f. 후속 티켓 나누기 (A 안 기준, 제안)

| 티켓 | 범위 |
|---|---|
| MUL-158 도매 채팅 5 op | V17 스키마, 방·메시지·읽음·사장 보내기(자동 답 없음 = `generation: NONE`). 원본 테이블을 먼저 깐다 |
| MUL-157 소매 채팅 6 op + 판정 | 도매: 판정기 + `InquiryJudge` 어댑터, `InquiryGoldenScenarioTest` 켜기(167/167 + 정규화 17줄), `/api/retail-gateway/chat/*`. 소매: 6 op 프록시(`wholesale/chat` 클라이언트), 정확 수량 없는 소매 facts |
| MUL-159 자동 답 설정 + 문의 통계 | V19 설정 테이블, `autoReplySettings` GET/PUT, `inquiryStats`(메시지의 intent·reason 집계). 그 전까지 판정은 기본값(전부 켜짐)을 읽는다 |

순서: MUL-158 → MUL-157 → MUL-159. MUL-157 의 이름은 "소매"지만 판정 코드는 도매 프로젝트에 들어간다 — 티켓 설명을 고칠지 사람이 정한다.

## 골든셋 테스트 (이 티켓)

- 위치: `wholesale-api` — 판정이 도매에서 돈다는 a 의 제안을 따른다. A 가 뒤집히면 파일째 `retail-api` 로 옮긴다(스프링·DB 없는 순수 JUnit 이라 그대로 옮겨진다).
- `InquiryGoldenFileTest` — 늘 돈다. v7 · 132 시나리오 · 167 단계 · 점수 파일과 개수 일치 · 정규화 6행.
- `InquiryGoldenScenarioTest` — `@Disabled`(MUL-157). 시나리오마다 컨테이너, 단계마다 테스트(167 + 정규화 17 = 184개).
- `InquiryJudge` — 테스트 쪽 이음매. 골든 JSON 을 받아 `expect` 와 같은 투영을 돌려준다. 운영 판정기 모양은 MUL-157 이 정한다.

## MUL-157 구현 메모 (2026-11-08)

- **판정기** `wholesale-api` `com.ondo.wholesale.chat.inquiry` — `InquiryDecider.decide`(순수) → `InquiryLines.generated` →
  `InquiryLines.toAppend`. 웹 `inquiry.ts` @ cdb20a1 을 옮겼다. 골든 167/167 + 정규화 17/17 (`GoldenInquiryJudge` 어댑터).
- **보내기** `RetailChatService.send` — 방 잠금 → 멱등 → 소매 TEXT → `ChatAutoReplyGenerator` 가 원본(`InquirySourceReader`)을
  읽어 판정하고 줄을 `ChatMessageAppender` 로 붙인다. 원본 조회는 JDBC 세이브포인트 안이라 SQL 실패도 넘김 줄(LOOKUP_FAILED 등)로 남는다.
  판정이 던지면 NO_INTENT 넘김. 멱등 기록에는 도매 모양 결과를 두고 내보낼 때마다 소매 모양으로 바꾼다.
- **게이트웨이** `/api/retail-gateway/chat/` — `rooms`(GET keyset µs·id / POST 열기 `{retailerId, retailerName, wholesalerId, listingId}`),
  `rooms/{id}`, `rooms/{id}/messages`(GET/POST, `Idempotency-Key`), `rooms/{id}/read`, `unread-count`. 응답은 소매 모양 —
  재고 facts 는 `band · orderLimit · orderable` 뿐이고 메시지에 `handoff` 칸이 없다. `orderable` 은 내보낼 때 다시 계산한다.
- **원본 값** 판매가능 = `stock_qty − reserved_qty − 그 SKU 열린 미송 남은 수`(웹 목 규칙). 칩·답 글자의 주문 번호는
  `orders.retail_order_id`(소매 주문서 id) — 소매 주문서가 없는 주문의 미송·포장은 원본에서 뺀다. 미수 `exact=false` 는 조정·입금 무효 줄,
  나눠 출고, 취소 주문 출고 줄, 출고보다 큰 배정이 있을 때.
- **방 열기** 승인된 도매처만(그 밖 404). 상호는 소매 DB `shop_name` 스냅샷.
- **설정** `AutoReplySettingsSource` — 지금은 `DefaultAutoReplySettingsSource`(전부 켜짐). MUL-159 가 V19 테이블 구현으로 바꾼다.
- **소매 배지** `notificationUnreadCount.chats` = 게이트웨이 `unread-count`(내 방 unreadCount 합).
