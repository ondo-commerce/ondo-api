package com.ondo.wholesale.chat.inquiry;

/**
 * 도매처의 자동 답 설정을 읽는 이음매 (MUL-157). 운영 구현은 V19 테이블을 읽는
 * {@code AutoReplySettingsRepository}(MUL-159)다. 판정과 보내기는 이 인터페이스만 안다.
 */
public interface AutoReplySettingsSource {

    AutoReplySettings forWholesaler(long wholesalerId);
}
