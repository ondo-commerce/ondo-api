# 문의 자동 답 골든셋 (MUL-156)

웹 레포에서 **그대로 복사한** 판정 기준 데이터다. 손으로 고치지 않는다.

| 파일 | 원본 경로 (ondo-commerce/ondo-web) |
|---|---|
| `inquiry-golden.json` | `packages/api/openapi/golden/inquiry-golden.json` |
| `inquiry-golden.score.json` | `packages/api/openapi/golden/inquiry-golden.score.json` |

- 복사한 커밋: `dev` @ `cdb20a127b3343b6052af426215dfe1ee219ebeb` (2026-11-08)
- 버전: **v7** — 시나리오 132 · 단계 167 (소매 166 + 도매 사장 1) · 정규화 표 6행
- 기준 판정 로직: 같은 커밋의 `packages/api/src/mocks/chat/inquiry.ts`, 투영·재생 규칙은 `golden.testkit.ts`

## 갱신

웹 쪽 골든셋이 바뀌면 두 파일을 함께 다시 복사하고 위 SHA·버전을 고친다.
`InquiryGoldenFileTest` 의 개수 단언도 새 버전에 맞춘다 — 개수가 바뀌었는데 테스트가 그대로 통과하면 복사를 잘못한 것이다.

설계 배경은 `docs/design/chat.md` (`docs/index.yml` 의 `domain.design.chat`).
