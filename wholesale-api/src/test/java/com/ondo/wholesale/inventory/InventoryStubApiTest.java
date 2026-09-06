package com.ondo.wholesale.inventory;

import com.ondo.wholesale.common.error.ErrorResponseWriter;
import com.ondo.wholesale.common.response.ApiResponseBodyAdvice;
import com.ondo.wholesale.common.trace.TraceIdFilter;
import com.ondo.wholesale.config.SecurityConfig;
import com.ondo.wholesale.inventory.controller.InventoryController;
import com.ondo.wholesale.inventory.service.InboundCommandService;
import com.ondo.wholesale.inventory.service.StockAdjustmentService;
import com.ondo.wholesale.security.ApprovedAuthorizationManager;
import com.ondo.wholesale.security.RestAccessDeniedHandler;
import com.ondo.wholesale.security.RestAuthenticationEntryPoint;
import com.ondo.wholesale.security.support.TestSecuritySupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 재고 계약 스텁 대표 응답 검증 (MUL-83). 봉투·계약 핵심 필드·상태 코드만 본다. */
@WebMvcTest(InventoryController.class)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        ApprovedAuthorizationManager.class, ErrorResponseWriter.class, ApiResponseBodyAdvice.class,
        TraceIdFilter.class})
class InventoryStubApiTest {

    @Autowired
    private MockMvc mvc;

    // 입고·조정은 실구현으로 교체됐다 — InboundCreateIntegrationTest·StockAdjustmentIntegrationTest 가 본다
    @MockitoBean
    private InboundCommandService inboundCommandService;

    @MockitoBean
    private StockAdjustmentService stockAdjustmentService;

    @Test
    void 재고이력은_data배열과_페이징meta를_함께_내린다() throws Exception {
        mvc.perform(get("/api/wholesale/variants/90231/stock-movements").with(TestSecuritySupport.approved()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].type").value("IN"))
                .andExpect(jsonPath("$.data[0].refType").value("INBOUND_ITEM"))
                .andExpect(jsonPath("$.meta.totalElements").value(41));
    }
}
