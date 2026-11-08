package com.ondo.wholesale.chat.inquiry;

/**
 * 도매처의 자동 답 설정을 읽는 이음매 (MUL-157). 설정 테이블과 GET/PUT 은 MUL-159 가 만든다 —
 * 그때 {@link DefaultAutoReplySettingsSource} 를 DB 구현으로 바꾼다. 판정과 보내기는 이 인터페이스만 안다.
 */
public interface AutoReplySettingsSource {

    AutoReplySettings forWholesaler(long wholesalerId);
}
