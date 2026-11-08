package com.ondo.wholesale.chat.golden;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * 골든셋 파일을 클래스패스에서 읽는다 (MUL-156). 출처·버전은 {@code src/test/resources/golden/README.md}.
 */
final class InquiryGolden {

    static final String GOLDEN = "/golden/inquiry-golden.json";
    static final String SCORE = "/golden/inquiry-golden.score.json";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private InquiryGolden() {
    }

    static JsonNode golden() {
        return read(GOLDEN);
    }

    static JsonNode score() {
        return read(SCORE);
    }

    private static JsonNode read(String path) {
        try (InputStream in = InquiryGolden.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("클래스패스에 없다: " + path);
            }
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
