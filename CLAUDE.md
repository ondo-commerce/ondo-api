# ondo-api

동대문 도매↔소매 B2B. **서버 둘 · DB 둘**인 모노레포다.
사람과 Claude Code 가 이 레포에서 일할 때 처음 읽는 파일이다. 자세한 건 링크로 따라간다.

> Humans steer. Agents execute. — 방향과 최종 판단은 사람이, 실행은 에이전트가 한다.

## 지도

| 폴더 | 내용 | 하위 규칙 |
|---|---|---|
| `wholesale-api/` | 도매 API (포트 8081) | [wholesale-api/CLAUDE.md](wholesale-api/CLAUDE.md) |
| `retail-api/` | 소매 API (포트 8080) | [retail-api/CLAUDE.md](retail-api/CLAUDE.md) |
| `db/` | 로컬 PostgreSQL 두 대 compose | — |
| `infra/` | AWS 인프라 Terraform | — |
| `monitoring/` · `load/` | 로컬 모니터링 · 부하 테스트 기록 | — |
| `docs/` | 레포 안 문서. **목차는 [docs/index.yml](docs/index.yml)** | — |

**한 프로젝트만 손볼 때는 그 폴더에서 세션을 시작해라.** 루트에서 시작하면 도매·소매 규칙이 섞인다.

## 맥락 세 층

| 층 | 무엇 | 어디 |
|---|---|---|
| 프로젝트 | 스택·규칙·구조 | 이 파일 + 하위 CLAUDE.md |
| 도메인 | 유스케이스·결정·테스트 규칙 | `docs/` (index.yml 의 `layer: domain`) |
| 태스크 | 지금 할 일 | 지라 티켓(MUL) · 스펙 |

문서를 찾을 땐 경로를 추측하지 말고 `docs/index.yml` 에서 ID 로 찾는다.

## 스택

- Java 21 · Spring Boot 4.1.1 · Gradle(Groovy)
- PostgreSQL 16 · Flyway · Spring Data JPA · Spring Security · Spring Session(JDBC)
- Lombok · Bean Validation
- 두 프로젝트는 **독립 Gradle 프로젝트**다 (각자 `settings.gradle`, `gradlew`). 루트 통합 빌드 없음.

## 명령어

```bash
docker compose -f db/compose.yml up -d      # DB 두 대 (도매 5432 / 소매 5433, 계정 ondo/ondo)
cd wholesale-api && ./gradlew bootRun        # 8081
cd retail-api    && ./gradlew bootRun        # 8080
./gradlew test                               # 각 프로젝트 폴더에서. 규칙은 docs/testing.md
```

앱이 뜰 때 **Flyway 가 자기 스키마를 넣는다.** 빈 DB 로 시작해도 된다. 지우려면 `down -v`.

## 반드시 지킬 것

- **DB 두 대는 서로 다른 물리 DB 다.** 한 트랜잭션·조인으로 못 묶는다. 서버 간 연동은 API 호출로.
- **기존 API 의 요청·응답 모양(계약)을 바꾸지 않는다.** 프론트가 그 계약으로 개발 중이다. 바꿔야 하면 사람에게 먼저 묻는다.
- **이미 적용된 Flyway 파일은 고치지 않는다.** 새 `V<n>__...sql` 을 추가한다. 엔티티를 바꾸면 마이그레이션도 같이.
- **비밀은 커밋하지 않는다.** 환경변수로 주입. (compose 의 `ondo/ondo` 는 로컬 전용)

## 브랜치 · 커밋 · PR

- **`main` 직접 푸시 금지.** `dev` 에서 피처 브랜치를 따고 `dev` 로 PR.
- 브랜치 — `<타입>/MUL-<번호>-<짧은설명>` · 예: `feat/MUL-123-order-confirm-api`
- 커밋 — `<타입>: MUL-<번호> <설명>` · 예: `feat: MUL-123 주문 확정 API 구현`
- PR 제목 — `[MUL-<번호>] <설명>` · base 는 `dev`
- PR 본문 — [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md) (Background / Tasks / Affected / Test)
- 타입 — `feat` · `fix` · `refactor` · `test` · `docs` · `chore`
- `MUL` 은 지라 프로젝트 키다. 티켓이 없는 작업(초기 세팅 등)은 키를 생략한다.

## Jira 연동

지라 이슈 조회·연결은 **Atlassian MCP**(프로젝트 `.mcp.json` 에 등록됨)로 한다.
각자 **자기 Atlassian 계정으로 OAuth 인증**한다 — 공용 토큰·`.env` 없음. 클론 후 최초 1회 Claude Code 에서 `/mcp` 로 로그인한다.

**티켓 키(예: `MUL-123`)를 받으면 이 순서로 작업한다:**

1. 해당 지라 티켓을 조회한다.
2. 요구사항과 acceptance criteria 를 확인한다.
3. 관련 코드를 탐색한다. 관련 문서는 `docs/index.yml` 에서 찾는다.
4. 구현 계획을 세운다.
5. 코드를 수정한다.
6. 테스트를 실행한다. 테스트 작성 규칙은 [docs/testing.md](docs/testing.md).
7. 완료 후 변경 내용을 요약한다.
8. **지라 상태 변경·댓글 작성은 사용자 확인을 받은 뒤** 수행한다.

- 티켓에 명시되지 않은 요구사항을 임의로 추가하지 않는다.
- 요구사항이 모호하면 진행하지 말고 **사용자에게 질문**한다.

## 문서 규칙

- 지식은 레포 안에 둔다. 코드 주석이 근거로 가리키는 문서는 **레포에 있어야** 한다.
- 새 문서를 만들면 `docs/index.yml` 에 등록한다.
- 이 파일은 **100줄 이내**로 유지한다. 길어지면 `docs/` 로 빼고 링크만 남긴다.
- `docs-local/`, `agent-local/` 은 gitignore 된 개인 폴더다. 올리지 않는다.
