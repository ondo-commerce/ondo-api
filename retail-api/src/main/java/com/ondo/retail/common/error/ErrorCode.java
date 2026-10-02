package com.ondo.retail.common.error;

import org.springframework.http.HttpStatus;

/**
 * 에러 코드와 화면 문구를 한곳에서 관리한다.
 *
 * <p>여기 적은 message 가 화면에 그대로 나간다. 그래서 "실패" 라고 쓰지 않는다 —
 * 사용자가 뭘 잘못했을 때 쓰는 말인데 대부분 그런 상황이 아니다.
 * 항상 다음에 뭘 하면 되는지가 읽히게 쓴다.
 */
public enum ErrorCode {

    // 공통
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력한 내용을 다시 확인해주세요"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요해요"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근할 수 없어요"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없어요"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "잘못된 요청이에요"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "요청 형식이 올바르지 않아요"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "잠시 후 다시 시도해주세요"),

    // 인증 · 가입
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호를 확인해주세요"),
    ACCOUNT_NOT_APPROVED(HttpStatus.FORBIDDEN, "승인 후 이용할 수 있어요"),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일이에요"),
    FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "파일은 10MB까지 올릴 수 있어요"),
    UNSUPPORTED_FILE_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "jpg, png, pdf 파일만 올릴 수 있어요"),

    // 장바구니 · 주문
    ORDER_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "한 번에 담을 수 있는 수량을 넘었어요"),
    UNORDERABLE_ITEM_INCLUDED(HttpStatus.BAD_REQUEST, "주문할 수 없는 상품이 있어요"),
    LISTING_CLOSED(HttpStatus.CONFLICT, "판매가 끝난 상품이에요"),

    // 같은 Idempotency-Key 로 이미 접수된 주문이 있을 때 (MUL-98).
    //
    // 연타를 막는 열쇠인데, 첫 요청이 이미 끝났으면 장바구니가 비어 있어 두 번째
    // 요청은 "없는 줄" 로 걸린다. 그걸 400 으로 내보내면 화면이 "입력을 확인해주세요"
    // 를 띄우는데, 사용자는 잘못한 게 없고 주문은 이미 됐다.
    //
    // 계약은 "처음 결과 그대로" 지만 실패분을 안 남겨서 아직 못 한다(숙제 7번).
    // 그때까지는 주문 id 를 실어 이 코드로 알린다 — 화면이 주문 상세로 보내면 된다.
    ORDER_ALREADY_PLACED(HttpStatus.CONFLICT, "이미 접수된 주문이에요"),

    // 주문 접수가 도매처 전부에서 거절됐을 때 (MUL-98).
    // 계약이 "전부 안 되면 통합 주문을 안 만든다 — 그때는 502" 다.
    // 일부만 실패한 건 여기 안 온다. 그건 에러가 아니라 결과라 201 로 나간다.
    UPSTREAM_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "지금 주문을 넣을 수 없어요. 장바구니는 그대로예요"),

    /**
     * 대기 취소를 눌렀는데 이미 끝난 건이다 (MUL-141).
     *
     * <p>조용히 성공으로 두면 취소된 줄 알고 다른 도매에서 또 산다. 이미 접수됐을 수도
     * 있으니 분명히 알려야 한다.
     */
    DISPATCH_NOT_PENDING(HttpStatus.CONFLICT, "이미 처리된 주문이에요. 주문 내역을 확인해주세요");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }

    /**
     * 상태 코드만 알 때 쓸 코드를 고른다 (MUL-106).
     *
     * <p>{@link JsonErrorReportValve} 가 쓴다. 톰캣이 스프링에 닿기 전에 거절한 요청은
     * 우리가 던진 예외가 없어서 <b>상태 코드밖에 모른다.</b> 그때 우리 규약 모양으로
     * 대답하려면 코드 하나를 골라야 한다.
     *
     * <p>세밀하게 나누지 않는다. 여기 오는 건 주소가 깨졌거나 톰캣이 스스로 낸
     * 응답뿐이라, 화면이 분기할 만한 상황이 아니다. "요청이 잘못됐다" 와
     * "우리 쪽 문제다" 만 가른다.
     */
    public static ErrorCode of(int httpStatus) {
        if (httpStatus == HttpStatus.NOT_FOUND.value()) {
            return RESOURCE_NOT_FOUND;
        }
        if (httpStatus == HttpStatus.UNAUTHORIZED.value()) {
            return UNAUTHORIZED;
        }
        if (httpStatus == HttpStatus.FORBIDDEN.value()) {
            return FORBIDDEN;
        }
        return httpStatus < 500 ? VALIDATION_FAILED : INTERNAL_ERROR;
    }
}
