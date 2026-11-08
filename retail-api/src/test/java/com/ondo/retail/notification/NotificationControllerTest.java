package com.ondo.retail.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ondo.retail.chat.ChatClient;
import com.ondo.retail.common.error.BusinessException;
import com.ondo.retail.common.error.ErrorCode;
import com.ondo.retail.notification.dto.NotificationResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 소매 알림 3 op 의 HTTP 계약 (MUL-155).
 *
 * <p>여기서 보는 것: 프론트 400(errors[{field}]) · 커서로 다음 장 잇기 · 409 옮기기 · 소매처 id 를
 * 세션에서 꺼내는지 · 응답 모양(target 갈래 칸, body null). 도매 호출 자체는
 * WholesaleNotificationAdapterTest 가, 알림을 누가 받는지는 도매 테스트가 본다.
 *
 * <p>{@code @WithMockUser(username = "1")} — 시드의 봄봄상회(승인됨). 도매는 가짜로 세운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(username = "1")
class NotificationControllerTest {

    private static final long 봄봄 = 1L;
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 7, 1, 0, 0, 123_456_000, ZoneOffset.UTC);

    @Autowired MockMvc mvc;

    @MockitoBean NotificationClient client;
    @MockitoBean ChatClient chatClient;

    @Test
    @DisplayName("size 를 안 주면 20, 21개를 물어 하나가 남으면 nextCursor 가 붙는다")
    void 기본_size_와_다음_커서() throws Exception {
        when(client.find(eq(봄봄), eq(null), eq(21))).thenReturn(알림들(21));

        String next = NotificationCursor.of(T0.minusSeconds(19), 20).encode();
        mvc.perform(get("/api/retail/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(20))
                .andExpect(jsonPath("$.meta.nextCursor").value(next))
                .andExpect(jsonPath("$.data[0].target.kind").value("BACKORDERS"))
                .andExpect(jsonPath("$.data[0].target.wholesalerId").value(101))
                .andExpect(jsonPath("$.data[0].target.orderId").doesNotExist())
                // body 가 없어도 칸은 있다
                .andExpect(jsonPath("$.data[0].body").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].readAt").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("nextCursor 를 그대로 주면 그 지점 뒤를 묻는다. 마지막 장이면 nextCursor 는 null")
    void 커서로_다음_장() throws Exception {
        NotificationCursor cursor = NotificationCursor.of(T0, 2031L);
        when(client.find(eq(봄봄), eq(cursor), eq(3))).thenReturn(알림들(2));

        mvc.perform(get("/api/retail/notifications").param("cursor", cursor.encode()).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.meta.nextCursor").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void size가_범위_밖이면_400_VALIDATION_FAILED_field_size다() throws Exception {
        for (String size : List.of("0", "101", "abc", "-1")) {
            mvc.perform(get("/api/retail/notifications").param("size", size))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("size"));
        }
        verify(client, never()).find(anyLong(), any(), anyInt());
    }

    @Test
    void 깨진_cursor는_400_VALIDATION_FAILED_field_cursor다() throws Exception {
        mvc.perform(get("/api/retail/notifications").param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("cursor"));
    }

    @Test
    @DisplayName("배지 — chats 는 도매 채팅 배지(내 방 unreadCount 합)를 그대로 싣는다 (MUL-157)")
    void 배지() throws Exception {
        when(client.unread(봄봄)).thenReturn(new NotificationClient.Unread(2, 2031L));
        when(chatClient.unreadChats(봄봄)).thenReturn(3);

        mvc.perform(get("/api/retail/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications").value(2))
                .andExpect(jsonPath("$.data.chats").value(3))
                .andExpect(jsonPath("$.data.latestNotificationId").value(2031));
    }

    @Test
    void ids_읽음은_남은_안읽음_수를_돌려준다() throws Exception {
        when(client.markRead(봄봄, List.of(2001L, 2002L))).thenReturn(1);

        mvc.perform(읽음("{\"ids\":[2001,2002]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications").value(1));
    }

    @Test
    void 모두_읽음의_수가_다르면_409_UNREAD_CHANGED다() throws Exception {
        when(client.markAllRead(봄봄, 3)).thenThrow(new BusinessException(ErrorCode.UNREAD_CHANGED));

        mvc.perform(읽음("{\"all\":true,\"expectedUnread\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNREAD_CHANGED"));
    }

    @Test
    @DisplayName("본문 규칙 위반은 400 VALIDATION_FAILED 이고 걸린 칸을 알려준다")
    void 본문_규칙_위반은_400이다() throws Exception {
        String ids101 = "[" + String.join(",", java.util.stream.IntStream.rangeClosed(1, 101)
                .mapToObj(String::valueOf).toList()) + "]";
        List<String[]> cases = List.of(
                new String[]{"{\"ids\":[1],\"all\":true,\"expectedUnread\":1}", "all"},
                new String[]{"{}", "ids"},
                new String[]{"{\"ids\":[]}", "ids"},
                new String[]{"{\"ids\":" + ids101 + "}", "ids"},
                new String[]{"{\"ids\":[1,1]}", "ids"},
                new String[]{"{\"ids\":[1.5]}", "ids"},
                new String[]{"{\"all\":true}", "expectedUnread"},
                new String[]{"{\"all\":true,\"expectedUnread\":0}", "expectedUnread"},
                new String[]{"{\"all\":false,\"expectedUnread\":1}", "all"},
                new String[]{"{\"ids\":[1],\"extra\":1}", "extra"});
        for (String[] c : cases) {
            mvc.perform(읽음(c[0]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        verify(client, never()).markRead(anyLong(), any());
        verify(client, never()).markAllRead(anyLong(), anyInt());
    }

    @Test
    @DisplayName("소매처 id 는 세션에서만 온다 — 쿼리에 남의 id 를 실어도 무시한다")
    void 세션의_소매처로_묻는다() throws Exception {
        when(client.find(eq(봄봄), eq(null), eq(21))).thenReturn(List.of());

        mvc.perform(get("/api/retail/notifications").param("retailerId", "999"))
                .andExpect(status().isOk());

        verify(client).find(eq(봄봄), eq(null), eq(21));
        verify(client, never()).find(eq(999L), any(), anyInt());
    }

    // ── 픽스처 ──────────────────────────────────────────────

    /** 1초 간격 최신순. id 는 1부터. */
    private static List<NotificationResponse> 알림들(int count) {
        List<NotificationResponse> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new NotificationResponse(i + 1, "BACKORDER_DATE_CHANGED", "무드온 미송 입고일 변경", null,
                    new NotificationResponse.Target("BACKORDERS", null, 101L), T0.minusSeconds(i), null));
        }
        return rows;
    }

    private static MockHttpServletRequestBuilder 읽음(String body) {
        return post("/api/retail/notifications/read").contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
