package com.ondo.wholesale.chat.service;

import com.ondo.wholesale.chat.dto.AutoReplySettingsBody;
import com.ondo.wholesale.chat.inquiry.AutoReplySettings;
import com.ondo.wholesale.chat.repository.AutoReplySettingsRepository;
import com.ondo.wholesale.common.error.ApiException;
import com.ondo.wholesale.common.error.ErrorCode;
import com.ondo.wholesale.common.error.ErrorResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 자동 답 설정 보기·바꾸기 (MUL-159, 초안 op {@code autoReplySettings} · {@code updateAutoReplySettings}).
 *
 * <p>PUT 은 통째로 바꾼다 — 칸이 하나라도 빠지거나 불린이 아니면 400 이다(부분 수정 없음). 본문을 {@code Map} 으로 받아
 * 여기서 본다: 레코드 + Bean Validation 으로는 {@code "true"}(문자열)·{@code 1} 이 불린으로 바뀌어 들어올 수 있다.
 * 어긋난 칸은 전부 모아 errors 에 싣는다(field = enabled | intents | intents.STOCK …, 웹 목과 같은 순서).
 */
@Service
@RequiredArgsConstructor
public class ChatAutoReplySettingsService {

    private static final List<String> INTENT_KEYS = List.of("STOCK", "BACKORDER", "RECEIVABLE");
    private static final String NOT_BOOLEAN = "켜짐, 꺼짐 값(불린)이 필요합니다.";

    private final AutoReplySettingsRepository repository;

    @Transactional(readOnly = true)
    public AutoReplySettingsBody get(long wholesalerId) {
        return AutoReplySettingsBody.of(repository.forWholesaler(wholesalerId));
    }

    @Transactional
    public AutoReplySettingsBody put(long wholesalerId, Map<String, Object> body) {
        AutoReplySettings settings = parse(body);
        repository.save(wholesalerId, settings);
        return AutoReplySettingsBody.of(settings);
    }

    static AutoReplySettings parse(Map<String, Object> body) {
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        Object enabled = body == null ? null : body.get("enabled");
        if (!(enabled instanceof Boolean)) {
            errors.add(new ErrorResponse.FieldError("enabled", NOT_BOOLEAN));
        }
        Object intents = body == null ? null : body.get("intents");
        if (!(intents instanceof Map<?, ?> map)) {
            errors.add(new ErrorResponse.FieldError("intents", "의도별 설정(STOCK, BACKORDER, RECEIVABLE)이 필요합니다."));
        } else {
            for (String key : INTENT_KEYS) {
                if (!(map.get(key) instanceof Boolean)) {
                    errors.add(new ErrorResponse.FieldError("intents." + key, NOT_BOOLEAN));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }
        Map<?, ?> map = (Map<?, ?>) intents;
        return new AutoReplySettings((Boolean) enabled, (Boolean) map.get("STOCK"), (Boolean) map.get("BACKORDER"),
                (Boolean) map.get("RECEIVABLE"));
    }
}
