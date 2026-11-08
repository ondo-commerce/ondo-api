package com.ondo.wholesale.common.error;

import com.ondo.wholesale.security.support.TestSecuritySupport;
import com.ondo.wholesale.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스프링이 스스로 던지는 요청 오류가 500 으로 새지 않는지 (MUL-80).
 *
 * <p>우리가 던진 예외·Bean Validation·알 수 없는 예외의 매핑은 GlobalExceptionHandlerTest 가,
 * 401/403 게이트는 AuthGateWebMvcTest 가 이미 본다. 여기는 <b>진짜 디스패처와 보안 체인</b>을
 * 거쳐서 나오는 것 — 없는 주소, 틀린 메서드, 깨진 JSON, 빠진 파라미터, 못 받는 Content-Type —
 * 이 각자 맞는 상태 코드와 공통 포맷으로 나가는지 본다. standalone MockMvc 로는
 * {@code NoResourceFoundException} 이 안 나서 통합으로 쓴다.
 *
 * <p>잡고 싶은 사고 — catch-all 이 이것들을 500 INTERNAL_ERROR 로 바꿔서, 클라이언트 실수가
 * 서버 장애처럼 보이고 프론트가 "잠시 후 다시 시도" 를 띄우는 것.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SpringErrorStatusIntegrationTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mvc;

    @Test
    void 없는_주소는_404_RESOURCE_NOT_FOUND다() throws Exception {
        // 승인 세션이 없으면 보안 체인이 먼저 401 로 끊는다 — 404 를 보려면 게이트를 통과해야 한다
        공통포맷(mvc.perform(get("/api/wholesale/nope").with(TestSecuritySupport.approved())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void 로그인을_GET으로_부르면_405_METHOD_NOT_ALLOWED다() throws Exception {
        공통포맷(mvc.perform(get("/api/wholesale/auth/login")))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void 깨진_JSON은_400_VALIDATION_FAILED다() throws Exception {
        공통포맷(mvc.perform(post("/api/wholesale/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 필수_쿼리_파라미터가_없으면_400_VALIDATION_FAILED다() throws Exception {
        // 로컬·테스트는 게이트웨이 시크릿이 비어 있어 헤더 없이 통과한다
        공통포맷(mvc.perform(get("/api/retail-gateway/settlements")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("retailerId"));
    }

    @Test
    void 못_받는_Content_Type은_415_UNSUPPORTED_MEDIA_TYPE이다() throws Exception {
        공통포맷(mvc.perform(post("/api/wholesale/auth/login")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("email=a@b.c")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    private ResultActions 공통포맷(ResultActions result) throws Exception {
        return result
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
