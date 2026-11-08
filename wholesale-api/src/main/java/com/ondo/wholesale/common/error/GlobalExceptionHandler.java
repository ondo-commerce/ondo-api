package com.ondo.wholesale.common.error;

import com.ondo.wholesale.common.trace.TraceIdFilter;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * 전역 예외 핸들러. 예외를 공통 {@link ErrorResponse}로 매핑한다(요구 4·5).
 *
 * <p>401/403은 필터 체인(디스패처 진입 전)에서 발생하므로 여기서 못 잡는다.
 * 인증/인가 실패 응답은 SecurityConfig의 EntryPoint/AccessDeniedHandler가 직접 처리한다(커밋 3·4).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return build(ex.errorCode(), ex.getMessage(), ex.errors());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<ErrorResponse.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraint(ConstraintViolationException ex) {
        List<ErrorResponse.FieldError> errors = ex.getConstraintViolations().stream()
                .map(v -> new ErrorResponse.FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
    }

    /** 필수 요청 헤더 누락(예: Idempotency-Key) — 클라이언트 요청 문제라 400 이다. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        List<ErrorResponse.FieldError> errors = List.of(
                new ErrorResponse.FieldError(ex.getHeaderName(), "필수 헤더가 없습니다."));
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
    }

    /** 쿼리 파라미터 타입 불일치(예: 날짜 형식 오류) — 클라이언트 입력 문제라 400 이다. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        List<ErrorResponse.FieldError> errors = List.of(
                new ErrorResponse.FieldError(ex.getName(), "값의 형식이 올바르지 않습니다."));
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
    }

    /** 필수 쿼리 파라미터 누락 — 클라이언트 요청 문제라 400 이다 (MUL-80). */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex) {
        List<ErrorResponse.FieldError> errors = List.of(
                new ErrorResponse.FieldError(ex.getParameterName(), "필수 파라미터가 없습니다."));
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
    }

    /**
     * 본문을 못 읽었다 — JSON 이 깨졌거나 enum 값이 틀렸다 (MUL-80). 다시 보내도 똑같으니 400 이다.
     * 어느 필드인지는 파서 메시지에만 있어 errors 는 비운다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), List.of());
    }

    /**
     * 없는 주소 (MUL-80). 스프링이 정상적으로 던지는 것이라 서버 잘못이 아니다.
     * 아래 catch-all 보다 먼저 잡아야 500 으로 새지 않는다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return build(ErrorCode.RESOURCE_NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND.defaultMessage(), List.of());
    }

    /** 있는 주소인데 메서드가 틀렸다 — POST /login 을 GET 으로 부른 경우 (MUL-80). */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return build(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.defaultMessage(), List.of());
    }

    /** 못 받는 Content-Type — JSON API 에 text/plain 을 보낸 경우 (MUL-80). */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return build(ErrorCode.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE.defaultMessage(),
                List.of());
    }

    /** 위에서 못 잡은 것 — 진짜 서버 잘못이다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception ex) {
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), List.of());
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode code, String message,
                                                List<ErrorResponse.FieldError> errors) {
        ErrorResponse body = ErrorResponse.of(code, message, errors, MDC.get(TraceIdFilter.TRACE_ID));
        return ResponseEntity.status(code.status()).body(body);
    }

    /** {@code request.name} 같은 경로에서 마지막 노드(필드명)만 뽑는다. */
    private String lastNode(String propertyPath) {
        int i = propertyPath.lastIndexOf('.');
        return i >= 0 ? propertyPath.substring(i + 1) : propertyPath;
    }
}
