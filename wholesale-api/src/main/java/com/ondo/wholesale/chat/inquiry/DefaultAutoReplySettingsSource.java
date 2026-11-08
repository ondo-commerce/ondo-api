package com.ondo.wholesale.chat.inquiry;

import org.springframework.stereotype.Component;

/**
 * 설정 저장소가 생기기 전의 기본값 — 모든 도매처가 전부 켜짐 (MUL-157, docs/design/chat.md f).
 * MUL-159 가 V19 설정 테이블을 읽는 구현으로 이 클래스를 바꾼다.
 */
@Component
public class DefaultAutoReplySettingsSource implements AutoReplySettingsSource {

    @Override
    public AutoReplySettings forWholesaler(long wholesalerId) {
        return AutoReplySettings.DEFAULTS;
    }
}
