# 웹 인수인계(11/7) 서버 답

- 출처 — ondo-web 인수인계 문서 (dev `1daab0a`, 2026-11-07) 3-3 "서버에 정해 달라고 한 것"
- 작성 — 2026-11-08 · MUL-151
- 근거는 전부 dev 의 서버 코드다. 경로의 `W/` 는 `wholesale-api/src/main/java/com/ondo/wholesale/`, `R/` 는 `retail-api/src/main/java/com/ondo/retail/` 다.
- 웹에 그대로 넘길 메모다. 계약을 바꾸는 항목은 없다 — 다른 건 웹이 서버에 맞춘다.

## 요약

| # | 결정 | 웹 기대 | 서버 실제 | 웹이 할 일 |
|---|---|---|---|---|
| 1 | D59 미송 배분·포장 | 줄 잔량 초과 = 409 `ALLOCATION_EXCEEDS_ORDER` | 잔량 초과는 409 `ALLOCATION_EXCEEDS_REMAINING`. `ALLOCATION_EXCEEDS_ORDER` 는 400 | 코드 매핑 고치기 |
| 2 | D61/D63 주문 탭 포장 | 배열 순서로 미송 연결 (mock) | 라인의 OPEN 미송 중 가장 오래된 것(FIFO) 하나에 연결 | 없음 (라인당 OPEN 1건 전제) |
| 3 | #212 가용재고 | — | `availableQty = stockQty − reservedQty`. 미송 대기는 안 뺀다 | 없음 |
| 4 | D34 재고 0 주문 | 재고 0 이면 주문 막음 | 막지 않는다. 확정·포장 때 409 `INSUFFICIENT_STOCK` | **불일치 — 막는 것 풀기 검토** |
| 5 | #196 주문 검색 `q` | 주문번호·포장 회차도 검색 | 거래처명·상품명 + 숫자면 주문번호 일치 (MUL-150). 회차는 미정 | 회차 검색은 보류 |
| 6 | D52 선수금 배분 | 검증 순서·코드 | 아래 6절 순서 그대로 | 코드 매핑 확인 |
| 7 | 채팅 재고 답 | 골든셋 v7 | 수량 구간·주문 가능 여부만. 정확한 재고는 안 준다 | 없음 |
| 8 | D46·D49 | HUMAN_REQUEST · 403/404 | `HUMAN_REQUEST` 유지(21값), 403·404 는 영구 거절 — 채팅 설계 제안 | 확인 |
| 9 | 기타 | — | 9절 참고 | — |
| 10 | 오류 응답 `errors[]` | `{code, field, message}` | 도매는 `{field, reason}` | **불일치 — 정해야 함** |
| 11 | 알림·채팅 초안 op | 서버 없음 → 목 | 알림 6 op · 도매 채팅 5 op 구현됨. 소매 채팅 · 자동 답은 남음 | 11절 참고 |

## 1. D59 미송 배분과 포장

**미송 배분이 포장을 만든다.** `POST /api/wholesale/backorders/allocations` 한 번 = batch 1행, 주문마다 포장 1장 (`W/backorder/BackorderAllocationWriter.java:45-66`). 잔량(`qty − allocatedQty`)이 0 이 된 미송만 RESOLVED, 부분 배분은 OPEN 으로 남는다 (`:58-62`).

**포장을 지우면 미송이 되살아난다.** `DELETE` 포장 → `W/order/service/AllocationWriter.java:119-149`
- 연결된 미송이 있으면 OPEN 으로 되돌린다.
- 연결이 없고 그 라인에 OPEN 미송이 있으면 그대로 둔다 (잔량은 파생값이라 자동으로 는다).
- 둘 다 없으면 새 미송을 만든다.
- PACKED 포장은 못 지운다 → 409 `DOCUMENT_FINALIZED` (`W/order/service/PackingCommandService.java:64-65`). 이미 지운 포장은 404.

**에러 코드 — 웹 문서와 다르다.**

| 상황 | 코드 | 근거 |
|---|---|---|
| 원래 수량 초과 (미송 배분: 미송 원래 수량 / 주문 탭: 라인 주문 수량) | 400 `ALLOCATION_EXCEEDS_ORDER` | `W/common/error/ErrorCode.java:64` |
| 남은 수량 초과 | 409 `ALLOCATION_EXCEEDS_REMAINING` | `ErrorCode.java:65` |

웹은 "줄 잔량 초과 409 `ALLOCATION_EXCEEDS_ORDER`" 로 적었다. 잔량 초과는 `ALLOCATION_EXCEEDS_REMAINING` 이다. `ALLOCATION_EXCEEDS_ORDER` 는 서버 상태와 무관한 요청 오류라 400 으로 통일했다 (`ErrorCode.java:59-60` 주석).

**`BACKORDER_NOT_OPEN` 409** (`ErrorCode.java:89`, `W/backorder/BackorderAllocationValidator.java:81-85`) — 미송 상태가 OPEN 이 아니면 난다. 실제로는 "이미 RESOLVED" 뿐이다. "취소된 주문의 미송" 은 생기지 않는다 — 주문 취소는 NEW 에서만 되고 (`W/order/service/OrderCommandService.java:51-58`), 미송은 확정 때 생긴다.

**포장이 있는 주문 취소 → 409 `TRANSITION_NOT_ALLOWED`** — 맞다. 다만 포장 유무를 따로 보지 않고 "NEW 가 아님" 으로 걸린다 (`OrderCommandService.java:53-54`). 포장은 확정 뒤에만 생기므로 결과는 같다.

**미송 배분 검증 순서** (`BackorderAllocationValidator.java`). 앞 단계가 걸리면 뒤는 안 본다. 한 단계 안에서는 걸린 항목을 전부 `errors` 에 담는다.

1. 형식 — `items` 비었음, `backorderId` 없음, `allocateQty < 1` → 400 `INVARIANT_VIOLATED` (`:35-49`)
2. 같은 미송 두 번 → 400 `DUPLICATE_BACKORDER` (`:51-63`)
3. 없는·남의 미송 → 404 `RESOURCE_NOT_FOUND` (`:76-80`)
4. OPEN 아님 → 409 `BACKORDER_NOT_OPEN` (`:81-85`)
5. 원래 미송량 초과 → 400 `ALLOCATION_EXCEEDS_ORDER` (`:86-90`)
6. 남은 미송량 초과 → 409 `ALLOCATION_EXCEEDS_REMAINING` (`:91-95`)
7. 같은 SKU 합이 가용재고 초과 → 409 `INSUFFICIENT_STOCK` (`:97-108`)

형식 오류 코드가 `VALIDATION_FAILED` 가 아니라 `INVARIANT_VIOLATED` 인 점에 주의.

## 2. D61/D63 주문 탭 포장과 미송 연결

`POST` 주문 탭 포장 → `W/order/service/AllocationWriter.java:87-110`

- 라인마다 OPEN 미송 중 가장 오래된 것 하나에 연결한다 (`W/order/repository/BackorderRepository.java:12` `findFirstByOrderItemIdAndStatusOrderByCreatedAtAsc`).
- 라인의 `allocatedQty` 를 올리고 variant 를 예약한다.
- `backorder.qty` 는 바뀌지 않는다. 남은 수량은 언제나 `orderItem.qty − allocatedQty` 다.
- 라인 잔량이 0 이 되면 그 미송을 RESOLVED.
- 검증 (`W/order/service/AllocationValidator.java:50-91`): 라인 주문 수량 초과 400 `ALLOCATION_EXCEEDS_ORDER`, 남은 수량 초과 409 `ALLOCATION_EXCEEDS_REMAINING`.

설계는 **라인당 OPEN 미송 1건**을 전제한다. DB 에 유니크 제약은 없다 — 부분 인덱스만 있다 (`wholesale-api/src/main/resources/db/migration/V1__init.sql:336-337`). 웹 mock 은 배열 순서로 고르는데, 1건 전제라 결과는 같다.

## 3. #212 가용재고

`availableQty = stockQty − reservedQty`. 미송 대기량은 빼지 않는다 (`W/product/service/ProductDetailAssembler.java:89-94`, `W/product/dto/response/VariantResponse.java:8`). 응답의 `allocatedQty` 필드 값이 `reservedQty` 다.

**채팅 재고 답은 다르게 센다 (MUL-157, 2026-11-08 결정).** 자동 답의 재고 구간은 `stockQty − reservedQty − 그 SKU 의 열린 미송 남은 수` 로 계산한다 (`W/chat/inquiry/InquirySourceReader.java:45-53`). 웹 목 규칙을 따랐다 — 입고되면 미송이 먼저 풀리니 그만큼은 새 주문에 못 준다. 그래서 같은 상품이 화면의 `availableQty` 보다 채팅에서 낮은 구간으로 보일 수 있다. 채팅 쪽이 보수적이라 "있다고 했는데 없다" 는 줄어든다. 두 숫자를 맞출지는 #212 답과 함께 정한다.

## 4. D34 재고 0 주문 — 불일치

서버는 재고가 0 이어도 주문을 받는다 (D-062). 소매 접수 `POST /api/retail-gateway/orders` 는 재고를 보지 않는다 (`W/retailgateway/RetailGatewayOrderService.java:27`, 라인 검증 `:126-158` 에 재고 검사 없음). 재고는 도매가 확정·포장할 때 보고, 모자라면 409 `INSUFFICIENT_STOCK` 이다.

웹은 지금 재고 0 주문을 막는다. **서버 정책과 다르다.** 막을지 풀지는 사람이 정한다.

## 5. #196 주문 검색 `q`

- 지금 — 거래처 상호 · 라인 상품명 부분 일치 (대소문자 무시) (`W/order/service/OrderSpecs.java:34-53`, 칩 개수 `W/order/service/OrderSummaryReader.java:140-151`).
- 주문번호 검색 — `q` 가 숫자로만 되어 있고 int 범위면 `orderNumber` 일치도 OR 로 찾는다 (MUL-150). 이름 검색도 같이 돌아서 `q=77` 은 77번 주문과 "77상회" 를 함께 낸다. int 를 넘는 숫자는 글자로 본다.
- 포장 회차 — 응답에 그런 필드가 없다. 넣으려면 계약 변경이라 보류.

## 6. D52 선수금 배분 `POST /api/wholesale/allocations`

검증 순서 (`W/settlement/service/AllocationCommandService.java:42-87`, `AllocationPlanner.java`). 앞에서 걸리면 끝이다.

| # | 검사 | 코드 | field | 근거 |
|---|---|---|---|---|
| 1 | `Idempotency-Key` 없음·공백·64자 초과 | 400 `VALIDATION_FAILED` | `Idempotency-Key` | `AllocationCommandService.java:78-79` |
| 2 | `retailerId` 없음 | 400 `VALIDATION_FAILED` | `retailerId` | `:81-82` |
| 3 | `allocations` 비었음 | 400 `VALIDATION_FAILED` | `allocations` | `:84-85` |
| 4 | 줄마다 `orderId` 없음 | 400 `VALIDATION_FAILED` | `allocations.orderId` | `AllocationPlanner.java:59-60` |
| 4 | 줄마다 `amount` 없음·0 이하 | 400 `VALIDATION_FAILED` | `allocations.amount` | `:62-63` |
| 4 | 같은 주문 두 번 | 400 `DUPLICATE_ORDER` | — | `:65-66` |
| 5 | 같은 키 재요청 — 같은 본문이면 저장된 응답을 200, 다른 본문이면 409 `IDEMPOTENCY_KEY_REUSED` | | | `AllocationCommandService.java:50-57` |
| 6 | 거래처 없음 | 404 `RESOURCE_NOT_FOUND` | — | `AllocationPlanner.java:84-97` |
| 7 | 주문 없음 → 404 / 남의 거래처 주문 → 400 `ORDER_RETAILER_MISMATCH` / 확정 아님 → 409 `ORDER_NOT_CONFIRMED` / 남은 미수 초과 → 409 `ALLOCATION_EXCEEDS_OUTSTANDING` | | | `:150-165` |
| 8 | 합계가 남은 선수금 초과 | 409 `ALLOCATION_EXCEEDS_PREPAID` | — | `AllocationCommandService.java:62-65` |

4 는 줄을 앞에서부터 보며 `orderId` → `amount` → 중복 순으로 검사한다. 첫 성공은 201, 재요청 성공은 200.

## 7. 채팅 자동응답의 재고 답

웹 골든셋 v7 을 따른다 (2026-11-08 결정, MUL-138 원칙 갱신).

- 수량 **구간**(예: `10~49장`)과 주문 가능 여부만 답한다.
- 정확한 재고 숫자는 답하지 않는다.
- 채팅 자체는 MUL-153 에서 다룬다.

## 8. D46 · D49 (채팅 설계 `docs/design/chat.md` 의 제안)

- D46 `HUMAN_REQUEST` — 도매 초안 enum 21값에 이미 있고 골든셋 U01~U09 가 요구한다. 그대로 둔다. 소매 메시지에는 넘김 이유를 내보내지 않는다.
- D49 소매 보내기 — 403 `ACCOUNT_NOT_APPROVED` · 404 `RESOURCE_NOT_FOUND`(남의 방 포함) · 409 `IDEMPOTENCY_KEY_REUSED` · 400 `VALIDATION_FAILED` 는 영구 거절. 401 · 500 은 일시적이라 같은 키로 다시 보내도 안전하다. 도매 연결 실패용 502 를 따로 둘지는 미정.

## 9. 그 밖에 웹이 알아 둘 서버 사실

- **소매 정산의 은행 정보가 null — 고침 (MUL-130).** 계좌가 `bank_account` 테이블(여러 개 + 주계좌 1개)로 옮겨졌고, dev 시드 `V902` 가 도매처 101~103 에 주계좌를 넣는다. 소매 주문서 · 주문 조회 · 정산 요약은 주계좌를 내보낸다. `bank-accounts` API 4개도 스텁이 아니라 실동작이다.
  - 확인 필요: 중복 계좌 오류(`DUPLICATE_BANK_ACCOUNT`)의 기존 계좌 id 는 `errors[0].data.bankAccountId` 에 담긴다. 스펙은 "data 에 기존 계좌 id" 라고만 적었다.
- **`GET /api/wholesale/me` 없음** — 보안 설정에 경로만 있고 (`W/config/SecurityConfig.java:69`) 컨트롤러가 없다. 웹 스펙에도 없다. 이제 404 가 난다.
- **도매가 없는 URL·깨진 JSON 에 500 — 고침 (MUL-80).** 없는 경로 404 `RESOURCE_NOT_FOUND`, 허용 안 된 메서드 405 `METHOD_NOT_ALLOWED`, 지원 안 하는 Content-Type 415 `UNSUPPORTED_MEDIA_TYPE`, 깨진 JSON · 필수 파라미터 누락 400 `VALIDATION_FAILED`.
- **소매 주문 취소는 아직 mock** — `POST /api/retail/orders/{orderId}/cancel` 이 `MockOrderData` 를 돌려준다 (`R/order/OrderController.java:126-130`). 실제로 취소되지 않는다.

## 10. 오류 응답 `errors[]` 모양 — 불일치

웹 스펙의 필드 오류는 `{code, field, message}` 다. 도매 서버는 모든 API 에서 `{field, reason}` 을 낸다 (`W/common/error/ErrorResponse.java`). `field` 는 같고 `code` · `message` 가 없다. 소매 서버는 `{field, code, message}` 다 (`R/common/error/ErrorResponse.java`).

도매 전체 API 에 걸린 계약이라 서버가 혼자 바꾸지 않았다. 웹이 `errors[].reason` 을 읽게 할지, 도매가 `code` · `message` 를 추가할지(추가만이라 기존 필드는 안 깨짐) 정해야 한다.

## 11. 알림 · 채팅 초안 op 구현 현황 (2026-11-08)

| op | 상태 | 티켓 |
|---|---|---|
| 도매 알림 3 (`notifications` · `markNotificationsRead` · `notificationUnreadCount`) | 구현. 새 주문 알림만 생성 | MUL-154 |
| 소매 알림 3 | 구현. 입고 예정일 변경 알림. 도매가 저장하고 소매는 gateway 로 위임 | MUL-155 |
| 도매 채팅 5 (`chatRooms` · `chatRoom` · `chatMessages` · `sendChatMessage` · `markChatRead`) | 구현. 자동 답은 아직 없어 `generatedMessages` 는 빈 배열 | MUL-158 |
| 소매 채팅 6 + 자동 답 판정 | 남음. 골든셋 v7 167단계 통과가 완료 조건 | MUL-157 |
| 자동 답 설정 2 · 문의 지표 1 | 남음 | MUL-159 |
| 이미지 업로드 1 | 남음 (저장소 미정) | — |

알아 둘 것:
- 알림 unread-count 의 채팅 수(도매 `chatsNeedingReply`, 소매 `chats`)는 지금 항상 0 이다. MUL-157 · MUL-159 에서 채운다.
- 시각은 UTC(`...Z`)로 내보낸다. 웹 목은 `+09:00` 이지만 스펙은 오프셋만 요구한다.
- 알림 · 채팅 모두 실시간은 polling 이다 (스펙대로). 커서는 불투명 문자열이니 그대로 돌려보내면 된다.
