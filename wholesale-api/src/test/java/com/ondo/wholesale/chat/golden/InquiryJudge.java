package com.ondo.wholesale.chat.golden;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 골든셋을 판정기에 물리는 이음매 (MUL-156). 구현은 MUL-157 이 만든다.
 *
 * <p>운영 판정기의 모양(입력 레코드, 결정 타입)은 아직 정하지 않았다. 여기서 그걸 미리 박으면
 * MUL-157 이 테스트에 맞춰 도메인을 짜게 된다. 그래서 이 인터페이스는 <b>골든셋 JSON 을 받아
 * 골든셋 JSON 투영을 돌려주는 어댑터</b>만 정한다. 구현은 골든 표({@code stock}, {@code backorders},
 * {@code receivables}, 시나리오의 {@code room.packings})로 가짜 원본을 만들고, 진짜 판정기를 부른 뒤,
 * 결과를 {@code expect} 와 같은 모양으로 투영한다.
 *
 * <p>투영 규칙은 골든 파일의 {@code about}·{@code aboutV3}~{@code aboutV7} 과 웹 레포
 * {@code packages/api/src/mocks/chat/golden.testkit.ts} ({@code runScenario}, {@code projectDecision},
 * {@code projectV4Lines}) 가 기준이다. 요약하면:
 * <ul>
 *   <li>{@code since} 4 이상이고 {@code projection} 이 {@code "decision"} 이 아니면 방에 들어간 줄 투영 {@code {lines: [...]}}
 *   <li>그 밖은 판정 투영 ({@code kind}/{@code kinds}, {@code intent}, {@code target} …)
 *   <li>{@code sender: WHOLESALER} 단계(사장이 직접 쓴 글)는 {@code {openHandoff}}
 * </ul>
 *
 * <p>숫자는 {@code IntNode} 로 만든다 — 골든 파일을 읽은 노드와 {@code equals} 로 비교하기 때문이다.
 */
public interface InquiryJudge {

    /**
     * 시나리오 하나를 첫 단계부터 끝까지 한 방에서 재생한다. 단계 사이 방 상태(되묻기, 열린 넘김,
     * 재진입 {@code enter}, 흐른 시간 {@code afterMinutes})는 구현이 들고 간다.
     *
     * @param golden   골든 파일 전체 (rules · stock · backorders · receivables 표)
     * @param scenario {@code scenarios[]} 의 한 원소
     * @return 단계마다 하나, {@code steps[i].expect} 와 같은 모양의 실제 투영. 길이 = 단계 수
     */
    List<JsonNode> play(JsonNode golden, JsonNode scenario);

    /**
     * 정규화 표({@code normalization[].cases[]})의 한 줄. 방 101 · 상품 2001 문맥 · 봄봄상회(1) ·
     * {@code rules.t0} 시각에서 판정한다. {@code afterClarify: true} 면 1분 전 되묻기를 깔고 판정한다
     * (웹 {@code inquiry.test.ts} 「S7-4 정규화 표 6행」과 같다).
     *
     * @return {@code case.expect} 와 같은 모양의 판정 투영
     */
    JsonNode judgeNormalizationCase(JsonNode golden, JsonNode normalizationCase);
}
