package com.ondo.retail.common.error;

import com.ondo.retail.wholesale.WholesaleApiException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * 예외를 팀 규약 모양의 실패 응답으로 바꾼다.
 *
 * <p>컨트롤러마다 try-catch 를 두지 않으려고 한곳에 모았다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 우리가 의도적으로 던진 것. 예상된 흐름이라 스택트레이스를 남기지 않는다. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode code = e.errorCode();
        log.warn("[{}] {}", code.name(), e.getMessage());
        return ResponseEntity.status(code.status())
                .body(new ErrorResponse(code.name(), e.getMessage(), traceId(), e.errors()));
    }

    /** @Valid 가 걸러낸 것. 어느 필드가 왜 걸렸는지 errors[] 에 담는다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<ErrorResponse.FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ErrorResponse.FieldError(
                        f.getField(),                 // items[2].quantity 처럼 JS 경로로 나온다
                        fieldCode(f.getCode()),       // 걸린 제약 이름. Email → EMAIL
                        f.getDefaultMessage()))
                .toList();

        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code, traceId(), errors));
    }

    /**
     * 요청이 잘못 온 것들. 전부 사용자 입력 문제라 400 이다.
     *
     * <p>이걸 안 잡으면 아래 catch-all 이 500 으로 내보낸다. 서버 잘못이 아닌데
     * "잠시 후 다시 시도해주세요" 라고 하면 거짓말이 된다 — 다시 해도 똑같다.
     *
     * <ul>
     *   <li>필수 파라미터 누락 — {@code ?cartItemIds=} 를 안 보냈다</li>
     *   <li>타입 불일치 — {@code ?page=abc}</li>
     *   <li>JSON 파싱 실패 — 본문이 깨졌거나 enum 값이 틀렸다</li>
     *   <li>@PathVariable · @RequestParam 에 붙은 검증 실패</li>
     * </ul>
     */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            HandlerMethodValidationException.class,
            MissingServletRequestPartException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        log.warn("[{}] {}", code.name(), e.getMessage());
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code, traceId()));
    }

    /**
     * 없는 주소. 스프링이 정상적으로 던지는 것이라 서버 잘못이 아니다.
     * 아래 catch-all 보다 먼저 잡아야 500 으로 새지 않는다.
     */

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException e) {
        ErrorCode code = ErrorCode.RESOURCE_NOT_FOUND;
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code, traceId()));
    }

    /**
     * 못 받는 Content-Type. 멀티파트로 받는 가입 API 에 JSON 을 보내면 여기로 온다.
     * 클라이언트가 형식을 틀린 것이라 415 다 — 서버 잘못이 아니다.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException e) {
        ErrorCode code = ErrorCode.UNSUPPORTED_MEDIA_TYPE;
        log.warn("[{}] {}", code.name(), e.getMessage());
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code, traceId()));
    }

    /** 있는 주소인데 메서드가 틀렸다. POST /login 을 GET 으로 부른 경우다. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException e) {
        ErrorCode code = ErrorCode.METHOD_NOT_ALLOWED;
        log.warn("[{}] {}", code.name(), e.getMessage());
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code, traceId()));
    }

    /**
     * 파일이 너무 크다. <b>톰캣이 컨트롤러보다 먼저 끊는다</b> — application.yml 의
     * {@code max-file-size} 를 넘는 순간이라 AuthService 의 크기 검사까지 못 간다.
     * 여기서 안 잡으면 같은 상황이 500 으로 나가서 "파일이 크다" 를 못 알려준다.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadSize(MaxUploadSizeExceededException e) {
        ErrorCode code = ErrorCode.FILE_TOO_LARGE;
        log.warn("[{}] {}", code.name(), e.getMessage());
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code, traceId()));
    }

    /**
     * 도매를 부르다 실패했다 (MUL-88). 도매가 안 떠 있거나 · 느리거나 · 5xx 를 준 경우다.
     *
     * <p>소매 잘못이 아니라서 사용자에겐 "잠시 후 다시" 로 나가지만, <b>원인은 반드시
     * 남긴다.</b> 아래 catch-all 에 맡기면 "처리하지 못한 예외" 로 찍혀서 소매 버그와
     * 도매 장애를 로그에서 구분할 수 없다.
     *
     * <p>상태 코드를 500 으로 둔 건 아직 정한 게 없어서다. 도매 장애를 프론트가
     * 따로 그려야 하면 전용 코드(503)를 그때 판다 — 숙제.
     */
    @ExceptionHandler(WholesaleApiException.class)
    public ResponseEntity<ErrorResponse> handleWholesaleApi(WholesaleApiException e) {
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        log.error("도매 호출 실패", e);
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code, traceId()));
    }

    /** 예상 못 한 것. 원인을 로그에 남기고 사용자에겐 자세히 알려주지 않는다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        log.error("처리하지 못한 예외", e);
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code, traceId()));
    }

    /**
     * 스프링이 주는 제약 이름을 우리 코드 표기로 바꾼다. {@code NotBlank → NOT_BLANK}
     *
     * <p>팀 규약이 에러 코드를 「도메인_사유」 대문자 문자열로 정해서 필드 단위도 맞춘다.
     */
    private static String fieldCode(String constraintName) {
        if (constraintName == null) {
            return ErrorCode.VALIDATION_FAILED.name();
        }
        return constraintName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase();
    }

    /**
     * 로그와 응답을 이어주는 값. 사용자가 traceId 를 알려주면 그 요청의 로그를 찾을 수 있다.
     * MDC 를 채우는 필터는 아직 없어서 지금은 비어 있다.
     */
    private String traceId() {
        return MDC.get("traceId");
    }
}
