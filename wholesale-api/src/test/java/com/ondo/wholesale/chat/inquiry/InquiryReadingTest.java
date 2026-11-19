package com.ondo.wholesale.chat.inquiry;

import com.ondo.wholesale.chat.domain.ChatIntent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 분류값과 본문의 사이즈 근거를 검사한다. SDK의 JSON 파싱과 최종 판정은 각 경로의 테스트에서 본다. */
class InquiryReadingTest {

    @ParameterizedTest
    @CsvSource({"XS,엑스스몰", "S,스몰", "S,에스", "M,미디움", "M,미디엄", "M,엠", "L,라지", "L,엘",
            "XL,엑스라지", "XL,엑스엘", "2XL,투엑스라지", "2XL,투엑스엘", "FREE,프리", "FREE,프리사이즈",
            "XS,xs", "S,s", "M,ｍ", "L,l", "XL,xl", "2XL,２ｘｌ", "FREE,free"})
    void 본문에서_찾은_별칭이_가리키는_사이즈만_받는다(String size, String evidence) {
        var reading = InquiryReading.validate("레드 " + evidence + " 구해요",
                fields("STOCK", false, size, evidence));
        assertThat(reading).isNotNull();
        assertThat(reading.intent()).isEqualTo(ChatIntent.STOCK);
        assertThat(reading.size()).isEqualTo(size);
    }

    @Test
    void 되묻기에_답한_사이즈는_기타로_분류해도_받는다() {
        assertThat(InquiryReading.validate("엑스 라지요", fields("OTHER", false, "XL", "엑스라지")))
                .isNotNull();
    }

    @Test
    void 불확실하거나_유형과_사이즈가_허용값_밖이면_버린다() {
        assertThat(InquiryReading.validate("안녕", fields("OTHER", false, "NONE", ""))).isNull();
        assertThat(InquiryReading.validate("문의", fields("STOCK", true, "NONE", ""))).isNull();
        assertThat(InquiryReading.validate("문의", fields("stock", false, "NONE", ""))).isNull();
        assertThat(InquiryReading.validate("문의", fields("STOCK", false, "3XL", "3XL"))).isNull();
    }

    @ParameterizedTest
    @CsvSource({"레드 엠,L,엠", "레드 라지,L,엘", "레드 큰것,L,큰것", "레드 XL,L,L",
            "레드 엑스라지,L,라지", "레드 L 엠,M,엠", "레드 SMALL,S,S"})
    void 근거가_다른_사이즈이거나_다른_낱말의_일부면_버린다(String body, String size, String evidence) {
        assertThat(InquiryReading.validate(body, fields("STOCK", false, size, evidence))).isNull();
    }

    @Test
    void 필수_칸이_빠지거나_추출한_글자가_길거나_제어문자가_있으면_버린다() {
        assertThat(InquiryReading.validate("질문", fields("STOCK", null, "NONE", ""))).isNull();
        assertThat(InquiryReading.validate("질문", new InquiryReading.Fields(
                "STOCK", false, "NONE", "", null, "", "", false))).isNull();
        assertThat(InquiryReading.validate("질문", new InquiryReading.Fields(
                "STOCK", false, "NONE", "", "", "셔".repeat(41), "", false))).isNull();
        assertThat(InquiryReading.validate("질문", new InquiryReading.Fields(
                "STOCK", false, "NONE", "", "", "", "지난\n주", false))).isNull();
        assertThat(InquiryReading.validate("질문", new InquiryReading.Fields(
                "STOCK", false, "NONE", "", "", "", "", null))).isNull();
    }

    /** 리뷰 결함 C: 추출한 상품명이 본문에 없으면 읽기를 버려야 한다. 기존 LlmInquiryTest의 "셔츠" 읽기와 충돌한다. */
    @Test
    void 상품명이_본문에_없으면_읽기를_버린다() {
        assertThat(InquiryReading.validate("레드 M 구할 수 있을까요?", withProduct("셔츠"))).isNull();
    }

    @Test
    void 상품명이_본문에_있거나_비어_있으면_받는다() {
        assertThat(InquiryReading.validate("셔츠 레드 M 구할 수 있을까요?", withProduct("셔츠"))).isNotNull();
        assertThat(InquiryReading.validate("레드 M 구할 수 있을까요?", withProduct(""))).isNotNull();
    }

    /** 리뷰 결함 D: 한글 별칭은 낱말 안의 부분 문자열이면 근거가 아니다. */
    @ParameterizedTest
    @CsvSource({"레드 엑스-라지 구할 수 있을까요?,L,라지", "사라지기 전에 레드 구할 수 있을까요?,L,라지",
            "프리미엄 레드 구할 수 있을까요?,FREE,프리", "프리오더 레드 구할 수 있을까요?,FREE,프리"})
    void 한글_별칭이_다른_낱말의_일부면_근거로_치지_않는다(String body, String size, String evidence) {
        assertThat(InquiryReading.validate(body, fields("STOCK", false, size, evidence))).isNull();
    }

    @ParameterizedTest
    @CsvSource({"레드 라지 있어요?,L,라지", "프리 사이즈 있어요?,FREE,프리"})
    void 낱말로_쓰인_한글_별칭은_근거로_친다(String body, String size, String evidence) {
        assertThat(InquiryReading.validate(body, fields("STOCK", false, size, evidence))).isNotNull();
    }

    /** 규칙 R2: 한글 사이즈 근거는 공백과 문장부호를 지우지 않은 본문에서 독립 토큰이어야 한다. 하이픈이 붙으면 거부. */
    @ParameterizedTest
    @CsvSource({"레드 엑스-라지 있어요?,XL,엑스라지", "레드 엑스-라지 있어요?,L,라지", "프리-오더 레드 있어요?,FREE,프리",
            "프리-미엄 레드 있어요?,FREE,프리", "레드 엠-사이즈 있어요?,M,엠", "레드 라지-엘 있어요?,L,라지"})
    void 한글_사이즈_근거에_하이픈이나_글자가_붙으면_버린다(String body, String size, String evidence) {
        assertThat(InquiryReading.validate(body, fields("STOCK", false, size, evidence))).isNull();
    }

    @ParameterizedTest
    @CsvSource({"엠이요,M,엠", "라지로 주세요,L,라지", "M이요,M,M", "엠 사이즈요,M,엠", "레드 엠 사이즈 있어요?,M,엠"})
    void 독립_토큰과_정한_조사가_붙은_근거는_받는다(String body, String size, String evidence) {
        assertThat(InquiryReading.validate(body, fields("OTHER", false, size, evidence))).isNotNull();
    }

    private static InquiryReading.Fields withProduct(String productName) {
        return new InquiryReading.Fields("STOCK", false, "M", "M", "", productName, "", false);
    }

    private static InquiryReading.Fields fields(String intent, Boolean uncertain, String size, String evidence) {
        return new InquiryReading.Fields(intent, uncertain, size, evidence, "", "", "", false);
    }
}
