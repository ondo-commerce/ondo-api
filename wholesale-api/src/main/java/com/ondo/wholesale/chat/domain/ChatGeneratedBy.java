package com.ondo.wholesale.chat.domain;

/** 자동 답을 만든 방식 (MUL-158). RULE 규칙+템플릿 / LLM 분류 뒤 템플릿. 숫자는 둘 다 DB 값. */
public enum ChatGeneratedBy {
    RULE,
    LLM
}
