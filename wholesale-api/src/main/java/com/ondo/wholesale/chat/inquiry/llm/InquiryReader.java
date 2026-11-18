package com.ondo.wholesale.chat.inquiry.llm;

import com.ondo.wholesale.chat.inquiry.InquiryReading;

/** 문의 한 문장을 LLM에 읽힌다. 테스트는 이 자리를 가짜로 바꾼다. 검증과 기다리기는 {@link InquiryLlmGate}가 한다. */
public interface InquiryReader {

    /** 던지지 않는다. 실패도 결과 종류로 돌려준다. */
    Result read(String body);

    /**
     * deadlineNanos는 {@link System#nanoTime()} 기준 기한이다. 취소로는 끊기지 않는 대기(첫 클라이언트 생성) 뒤에
     * 기한이 지났으면 보내지 않고 TIMEOUT을 돌려주도록 실제 호출 구현이 덮어쓴다. 가짜는 기한을 몰라도 된다.
     */
    default Result read(String body, long deadlineNanos) {
        return read(body);
    }

    enum Kind { OK, REFUSAL, MAX_TOKENS, INVALID, ERROR, TIMEOUT }

    /**
     * fields는 OK일 때만 있고 여덟 칸이 다 들어 있다. 토큰 수는 응답이 없으면 0이다.
     * error는 ERROR일 때 예외 클래스 이름과 고정된 오류 종류뿐이다. 예외 메시지에는 요청 본문이 섞일 수 있다.
     */
    record Result(Kind kind, InquiryReading.Fields fields, long inputTokens, long outputTokens, String error) {
    }
}
