# 테스트 가이드

이 레포 테스트가 **실제로 지키고 있는 규칙**을 적은 문서다. 새 테스트를 쓰거나 AI 에게 테스트를 시킬 때 이 문서를 기준으로 삼는다.
여기 적힌 규칙은 기존 코드에서 뽑았다. 아직 통일되지 않은 곳은 「현재 상태」로 따로 적었다.

## 실행

```bash
cd wholesale-api && ./gradlew test     # 도매 — 도커만 있으면 된다 (Testcontainers)
cd retail-api    && ./gradlew test     # 소매 — 로컬 DB 가 떠 있어야 한다 (아래 「DB」)

./gradlew test --tests '*OrderConfirmCancel*'              # 하나만
./gradlew test --tests '*Measurement*' -Dmeasure=true       # 측정용 (평소엔 안 돈다)
```

테스트가 끝나면 도매는 JaCoCo 리포트가 `build/reports/jacoco/` 에 생긴다. 커버리지 기준(게이트)은 아직 없다.

## 테스트 종류

| 종류 | 바닥 | 무엇을 보나 | 예 |
|---|---|---|---|
| 단위 | 순수 JUnit + Mockito `mock()` | 규칙·조립 로직. 스프링·DB 없음 | `OrderPlaceServiceTest`, `OrderStatusRuleTest`, `*ValidatorTest` |
| HTTP 계층 | `@WebMvcTest` + `@MockitoBean` | 형식 검증(Bean Validation)·에러 매핑·응답 봉투. **정책과 저장은 안 본다** | `ProductCreateApiTest` |
| 통합 | `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Transactional` | HTTP 요청부터 DB 까지. 상태 전이, 한 트랜잭션의 부수효과, 에러의 HTTP 매핑 | `*IntegrationTest` |
| 매핑 | `@SpringBootTest` + DB | JPA 엔티티가 실제 스키마와 맞는지 | `domain/*MappingTest` |
| 마이그레이션 | `@SpringBootTest` + DB | 빈 DB 에서 Flyway 가 V1 부터 통과하는지, 바뀐 스키마 제약 | `migration/*SchemaTest`, `MigrationSmokeTest` |
| 측정 | `@EnabledIfSystemProperty(measure)` | 동시성·재시도 정책 비교. 결과를 파일로 낸다 | `*Measurement` |

**한 테스트가 다른 테스트의 몫까지 보지 않는다.** 클래스 Javadoc 에 「여기서 보는 것 / 다른 테스트가 보는 것」을 적어 경계를 밝힌다.

### 현재 상태 — `*ApiTest` 가 둘로 섞여 있다

`*ApiTest` 13개 중 6개는 `@WebMvcTest`(HTTP 계층만), 7개는 `@SpringBootTest` + DB(사실상 통합)다.
새로 쓸 때는 이렇게 고른다.

- 컨트롤러만 보면 → `@WebMvcTest`, 이름은 `<기능>ApiTest`, `controller/` 패키지
- DB 까지 가면 → `@SpringBootTest`, 이름은 `<기능>IntegrationTest`, 기능 패키지 루트

기존 파일의 이름은 바꾸지 않는다(별도 티켓으로).

## 이름

- **메서드 이름은 한국어 행위 서술**이다. 조건과 결과를 같이 쓴다. 에러면 상태 코드와 에러 코드까지 쓴다.
  ```java
  void 잔량을_넘는_배분은_409_ALLOCATION_EXCEEDS_REMAINING이다()
  void 이름이_비면_VALIDATION_FAILED_400이다()
  void 빈_DB에서_마이그레이션이_전부_성공한다()
  ```
- 문장이 길거나 **이유**를 붙이고 싶으면 `@DisplayName` 을 쓴다 (주로 소매).
  ```java
  @DisplayName("한 곳이 거절해도 나머지는 접수된다 — 부분 성공은 에러가 아니다")
  void 부분_성공은_성공이다()
  ```
- 테스트 안의 상수·변수도 도메인 이름을 쓴다. 예: `소매처`, `무드온`, `라온`.

## 클래스 Javadoc

모든 테스트 클래스 위에 짧게 적는다.

```java
/**
 * 주문 확정·취소 API 통합 검증 (MUL-47).
 *
 * <p>배분·미송의 세부 규칙은 AllocationValidatorTest 가, 상세 조립은
 * OrderQueryIntegrationTest 가 이미 본다. 여기는 상태 전이와 한 트랜잭션의
 * 부수효과, 에러의 HTTP 매핑을 본다.
 */
```

1. 무엇을 검증하는지 한 줄 + **티켓 번호**
2. 다른 테스트에 맡긴 것
3. (필요하면) 왜 이렇게 검증하는지 — 잡고 싶은 사고

## 픽스처

- 공유 픽스처는 `support/*Fixture` 에 둔다 (도매: `MasterDataFixture`, `OrderFixture`, `InventoryFixture`, `OutboundFixture`).
- **`JdbcTemplate` 으로 SQL 을 직접 심는다.** 테스트 대상이 아닌 API(예: 소매 접수)를 거치지 않는다.
- 픽스처 메서드도 한국어다. 예: `상품_변형을_넣는다(...)`, `거래처를_넣는다(...)`. 만든 행의 id 를 돌려준다.
- 마스터(도매처·카테고리·색상) → 주문 → 출고 순으로 쌓는다.
- `docs-local/` 의 시드에 기대지 않는다. gitignore 라 CI 에도 남의 컴퓨터에도 없다.

## DB

| | 도매 | 소매 |
|---|---|---|
| 방식 | Testcontainers `postgres:16` (`PostgresTestSupport` 상속) | 로컬 compose DB `localhost:5433` |
| 로컬 준비 | 도커만 | `docker compose -f db/compose.yml up -d` |
| CI | 그대로 돈다 | 워크플로가 `services: postgres` 로 같은 값을 띄운다 |

- 도매 컨테이너는 `static` 이라 JVM 당 한 번만 뜬다. 정리는 Ryuk 이 맡는다.
- 도매 테스트는 로컬 DB 를 보지 않는다. `MigrationSmokeTest` 가 이걸 확인한다.
- 통합 테스트는 `@Transactional` 로 테스트마다 롤백한다.
- **현재 상태:** 소매가 외부 DB 에 기대는 건 알려진 빈틈이다. 도매처럼 Testcontainers 로 옮기는 건 별도 티켓.

## 환경 고정

- **테스트 JVM 은 KST(`Asia/Seoul`)다** (`build.gradle`). 운영 코드가 한국 날짜 경계를 전제한다. 러너는 UTC 라 고정하지 않으면 하루 중 9시간만 깨진다.
- **소매 재시도 스케줄러는 테스트에서 꺼져 있다.** 워커를 보는 테스트는 `run()` 을 직접 부른다. 무엇이 언제 도는지가 분명해야 한다.
- 측정용은 `-Dmeasure=true` 없이는 안 돈다. 일반 테스트와 섞이면 느려지고 결과가 흔들린다.

## 에이전트에게 테스트를 시킬 때

- 기능 하나에 위 종류 중 **무엇이 필요한지 먼저 정하고** 쓴다. 보통 통합 1개 + 필요하면 단위.
- 실패 케이스는 **에러 코드와 HTTP 상태를 둘 다** 단언한다.
- 새 테스트가 기존 테스트와 같은 것을 보면 쓰지 않는다. Javadoc 의 경계를 확인한다.
- `./gradlew test` 가 통과하기 전에는 끝났다고 하지 않는다.
