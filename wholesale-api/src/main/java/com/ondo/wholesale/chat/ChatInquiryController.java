package com.ondo.wholesale.chat;

import com.ondo.wholesale.chat.dto.AutoReplySettingsBody;
import com.ondo.wholesale.chat.dto.InquiryStats;
import com.ondo.wholesale.chat.service.ChatAutoReplySettingsService;
import com.ondo.wholesale.chat.service.InquiryStatsService;
import com.ondo.wholesale.security.WholesalePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * 자동 답 설정 2 op + 문의 지표 1 op (MUL-159) — 원본 계약: ondo-web {@code packages/api/openapi/wholesale.json}
 * 「10 채팅」 초안({@code x-ondo-draft}). 소매 쪽 op 는 없다(소매는 결과만 본다).
 */
@Tag(name = "10 채팅")
@RestController
@RequestMapping("/api/wholesale/chat")
@RequiredArgsConstructor
public class ChatInquiryController {

    private final ChatAutoReplySettingsService settingsService;
    private final InquiryStatsService statsService;

    @Operation(operationId = "autoReplySettings", summary = "채팅 자동 답 설정 보기", description = """
            이 도매처의 채팅 자동 답 설정. 저장한 적이 없으면 전부 true 다.

            에러: 401 / 403""")
    @GetMapping("/auto-reply-settings")
    public AutoReplySettingsBody autoReplySettings(@AuthenticationPrincipal WholesalePrincipal principal) {
        return settingsService.get(principal.wholesalerId());
    }

    @Operation(operationId = "updateAutoReplySettings", summary = "채팅 자동 답 설정 바꾸기", description = """
            통째로 바꾼다(부분 수정 없음, 칸이 빠지면 400). 판정 순서 0: enabled 가 false 면 소매 문의는 전부 사람
            넘김(HANDOFF, 이유 OFF), true 여도 그 의도가 false 면 그 의도만 넘김. 바꾼 뒤 들어오는 메시지부터 적용된다.

            에러: 400 `VALIDATION_FAILED`(errors[].field = enabled | intents | intents.STOCK …, 불린 아님 포함) / 401 / 403""")
    @PutMapping("/auto-reply-settings")
    public AutoReplySettingsBody updateAutoReplySettings(@AuthenticationPrincipal WholesalePrincipal principal,
                                                         @RequestBody Map<String, Object> body) {
        return settingsService.put(principal.wholesalerId(), body);
    }

    @Operation(operationId = "inquiryStats", summary = "문의 지표 (영업일 집계)", description = """
            내 도매처 방의 소매 TEXT 메시지(칩 답 포함)를 결과 하나로 센다. 우선순위 자동 답 > 넘김 > 되묻기 > 결과 없음.
            영업일 D = [D 12:00, D+1 12:00) Asia/Seoul, 출처 메시지 sentAt 으로 날을 정한다. asOf 뒤에 쓰인 결과 줄은 안 센다.

            에러: 400 `VALIDATION_FAILED`(날짜 형식, from > to, to 가 오늘 영업일 뒤, 62일 넘음. errors[{field: from|to}])
            / 401 / 403""")
    @GetMapping("/inquiry-stats")
    public InquiryStats inquiryStats(@AuthenticationPrincipal WholesalePrincipal principal,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to) {
        return statsService.stats(principal.wholesalerId(), from, to, Instant.now());
    }
}
