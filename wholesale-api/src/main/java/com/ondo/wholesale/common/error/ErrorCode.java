package com.ondo.wholesale.common.error;

import org.springframework.http.HttpStatus;

/**
 * 공통 에러 코드. {@code code} 문자열은 enum 이름을 그대로 쓴다.
 *
 * <p>티켓(MUL-66) 명시 4종 + 방어용 2종({@code ACCESS_DENIED}, {@code INTERNAL_ERROR})
 * + 가입(MUL-68) 5종.
 *
 * <p>가입 5종은 전부 400 이다. 소매(MUL-78)는 이메일 중복을 409 로 냈지만,
 * 도매 티켓이 400 을 명시했으므로 티켓을 따른다.
 */
public enum ErrorCode {

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    NOT_APPROVED(HttpStatus.FORBIDDEN, "승인 대기 중인 계정입니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다."),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),

    // ── 가입 (MUL-68) ──
    // 형식이 아니라 정책을 어겼을 때 쓴다. 형식 위반은 위의 VALIDATION_FAILED 로 뭉뚱그린다.
    // 클라이언트가 화면에서 다르게 처리해야 하는 것들이라 코드를 따로 뒀다.
    EMAIL_DUPLICATED(HttpStatus.BAD_REQUEST, "이미 가입된 이메일입니다."),
    BIZ_REG_NO_DUPLICATED(HttpStatus.BAD_REQUEST, "이미 가입된 사업자등록번호입니다."),
    PASSWORD_POLICY_VIOLATED(HttpStatus.BAD_REQUEST,
            "비밀번호는 8~20자이며 영문·숫자·특수문자를 각각 1자 이상 포함해야 합니다."),
    REQUIRED_CONSENT_MISSING(HttpStatus.BAD_REQUEST, "필수 동의 항목이 누락되었습니다."),
    REQUIRED_DOCUMENT_MISSING(HttpStatus.BAD_REQUEST, "필수 증빙 서류가 누락되었습니다."),

    // ── 상품 (MUL-91) ──
    // 화면이 항목별로 다르게 안내해야 하는 정책 위반들. 형식 위반은 VALIDATION_FAILED.
    CATEGORY_NOT_FOUND(HttpStatus.BAD_REQUEST, "존재하지 않는 카테고리입니다."),
    CATEGORY_NOT_LEAF(HttpStatus.BAD_REQUEST, "상품에는 소분류(리프) 카테고리만 지정할 수 있습니다."),
    COLOR_DUPLICATED(HttpStatus.BAD_REQUEST, "같은 색상을 두 번 넣을 수 없습니다."),
    SIZE_DUPLICATED(HttpStatus.BAD_REQUEST, "한 색상에 같은 사이즈를 두 번 넣을 수 없습니다."),
    OPTION_REQUIRED(HttpStatus.BAD_REQUEST, "색상 옵션과 사이즈는 1개 이상이어야 합니다."),
    PRICE_REQUIRED(HttpStatus.BAD_REQUEST, "게시하려면 모든 옵션의 판매가가 필요합니다."),
    INVARIANT_VIOLATED(HttpStatus.BAD_REQUEST, "요청이 상품 구성과 맞지 않습니다."),
    // 재고·주문 쪽에 물려 있는 variant 는 못 지운다 — 화면이 항목별로 다른 안내를 띄운다
    VARIANT_HAS_STOCK(HttpStatus.CONFLICT, "재고가 남아 있는 옵션은 뺄 수 없습니다."),
    VARIANT_ALLOCATED(HttpStatus.CONFLICT, "포장 배분에 잡혀 있는 옵션은 뺄 수 없습니다."),
    VARIANT_HAS_BACKORDER(HttpStatus.CONFLICT, "미송이 걸려 있는 옵션은 뺄 수 없습니다."),
    VARIANT_IN_PENDING_ORDER(HttpStatus.CONFLICT, "처리 중인 주문에 들어 있는 옵션은 뺄 수 없습니다."),
    TRANSITION_NOT_ALLOWED(HttpStatus.CONFLICT, "지금 상태에서는 할 수 없는 전환입니다."),

    // ── 로그인 (MUL-69) ──
    // 이메일이 없는 것과 비밀번호가 틀린 것을 구분하지 않는다. 나눠서 알려주면 밖에서
    // 이메일만 넣어보며 가입 여부를 확인할 수 있다(계정 열거). 그래서 실패는 이 하나뿐이다.
    // UNAUTHENTICATED 도 401 이지만 그건 "세션이 없다"는 뜻이라 문구가 다르다 —
    // 로그인 폼 밑에 "인증이 필요합니다."를 띄울 수는 없다.
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),

    // ── 주문 (MUL-47) ──
    // 400 은 요청 자체가 잘못된 것 — 주문·배분 상태와 무관하게 언제 보내도 실패한다.
    // 409 는 지금 상태와 충돌하는 것 — 같은 요청도 상태가 바뀌면 성공할 수 있다.
    // ALLOCATION_EXCEEDS_ORDER 는 확정 스텁(400)과 포장 준비 스텁(409)이 다르게 적었었는데,
    // 배분 합이 주문 수량을 넘는 것은 서버 상태와 무관한 요청 오류라 400 으로 통일했다.
    ORDER_ITEM_MISSING(HttpStatus.BAD_REQUEST, "확정에는 주문의 모든 라인이 필요합니다."),
    ORDER_ITEM_NOT_IN_ORDER(HttpStatus.BAD_REQUEST, "이 주문에 없는 라인입니다."),
    DUPLICATE_ORDER_ITEM(HttpStatus.BAD_REQUEST, "같은 라인을 두 번 담을 수 없습니다."),
    ALLOCATION_EXCEEDS_ORDER(HttpStatus.BAD_REQUEST, "배분 수량이 주문 수량을 넘을 수 없습니다."),
    ALLOCATION_EXCEEDS_REMAINING(HttpStatus.CONFLICT, "배분 수량이 남은 수량을 넘을 수 없습니다."),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "가용 재고가 부족합니다."),
    DOCUMENT_FINALIZED(HttpStatus.CONFLICT, "이미 확정된 문서는 변경할 수 없습니다."),

    // 재고·출고·미송·회원은 병렬로 구현하기로 해서 섹션을 미리 갈라 뒀다 — 각 작업은
    // 자기 섹션 안만 고친다(다른 섹션을 만지면 머지가 꼬인다). 아래 코드들은 MUL-83
    // 계약 스텁의 @Operation 자바독에서 그대로 옮겨 왔고, 문구는 실구현이 다듬어도 된다.

    // ── 재고 (MUL-72) ──
    DUPLICATE_LOT(HttpStatus.BAD_REQUEST, "같은 입고에 같은 로트를 두 번 넣을 수 없습니다."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "이미 사용한 Idempotency-Key 에 다른 요청을 보낼 수 없습니다."),
    STATE_CONFLICT(HttpStatus.CONFLICT, "지금 상태와 충돌하는 요청입니다."),
    STOCK_BELOW_ZERO(HttpStatus.CONFLICT, "재고는 0 미만이 될 수 없습니다."),
    STOCK_BELOW_ALLOCATED(HttpStatus.CONFLICT, "배분에 잡힌 수량 아래로는 재고를 줄일 수 없습니다."),

    // ── 출고 (MUL-49) ──
    DUPLICATE_PACKING_ITEM(HttpStatus.BAD_REQUEST, "같은 포장을 두 번 담을 수 없습니다."),
    RETAILER_MIXED(HttpStatus.BAD_REQUEST, "한 출고에는 한 소매처의 포장만 담을 수 있습니다."),
    RECEIVE_BY_MIXED(HttpStatus.BAD_REQUEST, "수령 방식이 다른 포장은 한 출고에 담을 수 없습니다."),
    PACKING_NOT_READY(HttpStatus.CONFLICT, "출고에 담을 수 있는 상태의 포장이 아닙니다."),
    OUTBOUND_EMPTY(HttpStatus.CONFLICT, "빈 출고는 확정할 수 없습니다."),

    // ── 미송 (MUL-48) ──
    DUPLICATE_BACKORDER(HttpStatus.BAD_REQUEST, "같은 미송을 두 번 담을 수 없습니다."),
    BACKORDER_NOT_OPEN(HttpStatus.CONFLICT, "열려 있는 미송이 아닙니다."),

    // ── 정산 (MUL-124) ──
    // HTTP 상태는 입금 계약 스텁(PaymentController @Operation)이 적은 대로다.
    PAID_AT_IN_FUTURE(HttpStatus.BAD_REQUEST, "입금 일시는 지금보다 뒤일 수 없습니다."),
    DUPLICATE_ORDER(HttpStatus.BAD_REQUEST, "같은 주문에 두 번 배분할 수 없습니다."),
    ORDER_RETAILER_MISMATCH(HttpStatus.BAD_REQUEST, "이 소매처의 주문이 아닙니다."),
    ORDER_NOT_CONFIRMED(HttpStatus.CONFLICT, "확정된 주문에만 배분할 수 있습니다."),
    // 입금 등록은 이번 입금액 + 남은 선수금까지 붙일 수 있다 (MUL-125)
    ALLOCATION_EXCEEDS_PAYMENT(HttpStatus.CONFLICT, "배분 합계가 입금액과 남은 선수금을 넘을 수 없습니다."),
    ALLOCATION_EXCEEDS_PREPAID(HttpStatus.CONFLICT, "배분 합계가 남은 선수금을 넘을 수 없습니다."),
    ALLOCATION_EXCEEDS_OUTSTANDING(HttpStatus.CONFLICT, "주문의 남은 미수보다 많이 배분할 수 없습니다."),

    // ── 정산 계좌 (MUL-130) ──
    // HTTP 상태는 계좌 계약 스텁(BankAccountController @Operation)이 적은 대로 둘 다 400 이다.
    // 중복은 errors[0].data 에 기존 계좌 id 를 실어 화면이 그 계좌로 안내하게 한다.
    DUPLICATE_BANK_ACCOUNT(HttpStatus.BAD_REQUEST, "이미 등록된 계좌입니다."),
    PRIMARY_ACCOUNT_CANNOT_BE_UNSET(HttpStatus.BAD_REQUEST,
            "주계좌는 직접 해제할 수 없습니다. 다른 계좌를 주계좌로 지정해 주세요."),

    // ── 회원 (MUL-70·71) ──
    // 심사 현황·재신청은 계약 스텁이 없어 선등록할 코드가 없다 — 구현이 이 자리에 채운다.

    // ── 소매 접수 (MUL-98) ──
    // 소매 백엔드가 POST /api/retail-gateway/orders 로 주문을 넣을 때 나온다.
    // 400 은 요청이 애초에 틀린 것 — 남의 상품을 보냈다. 언제 보내도 실패한다.
    // 409 는 소매가 본 화면과 지금 도매 상태가 어긋난 것 — 담아둔 사이 값이 바뀌었다.
    // 이 구분이 중요한 건 소매 화면의 안내가 갈리기 때문이다. 400 은 다시 눌러도
    // 소용없고, 409 는 "장바구니를 새로 고쳐주세요" 로 이어진다.
    VARIANT_WHOLESALER_MISMATCH(HttpStatus.BAD_REQUEST, "이 도매처의 상품이 아닙니다."),
    LISTING_NOT_ON_SALE(HttpStatus.CONFLICT, "판매 중인 상품이 아닙니다."),
    PRICE_NOT_SET(HttpStatus.CONFLICT, "판매가가 등록되지 않은 옵션입니다."),
    PRICE_CHANGED(HttpStatus.CONFLICT, "주문하려는 사이에 판매가가 바뀌었습니다."),
    ORDER_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "1회 주문 가능 수량을 넘었습니다."),
    // 멱등성 (D-052). UNIQUE(retail_order_id, wholesaler_id) 가 막는다 —
    // 소매가 연타하거나 응답을 못 받고 재시도해도 주문이 둘 생기지 않는다.
    ORDER_ALREADY_CREATED(HttpStatus.CONFLICT, "이미 접수된 주문입니다."),
    ;

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
