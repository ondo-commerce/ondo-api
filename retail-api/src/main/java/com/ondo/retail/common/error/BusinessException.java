package com.ondo.retail.common.error;

import java.util.List;

/** 우리가 의도적으로 던지는 예외. GlobalExceptionHandler 가 ErrorResponse 로 바꾼다. */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient List<ErrorResponse.FieldError> errors;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    /**
     * 어느 칸이 걸렸는지 같이 알려야 할 때 (MUL-155). {@code @Valid} 로 못 거르는 검증 —
     * 알림 커서·본문 갈래 — 이 {@code errors[{field}]} 를 채운다. 문구는 코드 기본값이다.
     */
    public BusinessException(ErrorCode errorCode, List<ErrorResponse.FieldError> errors) {
        super(errorCode.message());
        this.errorCode = errorCode;
        this.errors = List.copyOf(errors);
    }

    /**
     * 상황에 따라 문구가 달라져야 할 때 쓴다. 예를 들어 수량 상한을 넘었다면
     * 상한이 몇인지 알려주는 게 낫다 — "최대 5장까지 담을 수 있어요".
     *
     * <p>이 문구가 화면에 그대로 나가므로 "실패" 같은 말을 쓰지 않는다.
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.errors = List.of();
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 필드 단위 에러. 없으면 빈 배열이다. */
    public List<ErrorResponse.FieldError> errors() {
        return errors;
    }
}
