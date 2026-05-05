---
name: Swagger / OpenAPI 작성 구조 (사용자 확정)
description: magicJar 컨트롤러/DTO에 OpenAPI 어노테이션을 어떤 구조로 달지 — 사용자가 자주 참조하는 1차 API 문서이므로 일관성·풍부한 한국어 설명 필수
type: feedback
originSessionId: e1de5065-8881-441d-9c09-cdb5579a83a9
---
사용자는 magicJar의 REST API를 README/마크다운보다 **Swagger UI에서 직접 확인**하는 빈도가 높다. 따라서 컨트롤러를 신규 추가하거나 수정할 때 다음 4단 구조를 기본값으로 사용한다.

**Why:** 사용자가 2026-04-28 명시적으로 "스웨거 작성 이렇게 구조대로 잘 할 수 있도록 기억해둬"라고 요청. 본인이 API 명세를 직접 들여다볼 때 한국어 설명 + 예시값이 들어있어야 빠르게 이해 가능. 컨트롤러만 갓 만들고 어노테이션 없이 두면 path/method만 노출되어 의미 파악 불가.

**How to apply:**

1. **모듈 단위로 `OpenApiConfig.kt`를 둔다** (`{module}/src/main/kotlin/com/{pkg}/config/`)
   - `OpenAPI` 빈에 `Info`(title·version·description·contact) + `Server` 등록.
   - `description`은 마크다운 표 1개 — 도메인별 엔드포인트 매핑 + 인증 정책 한 줄.
   - 모듈별로 분리(api, batch가 다른 포트/도메인이면 각자).

2. **컨트롤러 클래스에 `@Tag`** — `name`은 도메인 단어 PascalCase(`Backtest`·`TradeJournal`·`BatchJobTrigger`). `description`은 그 도메인의 흐름 + 표(엔드포인트 매핑).

3. **메서드마다 `@Operation` + `@ApiResponses`**
   - `summary`는 한 줄(메뉴 노출용).
   - `description`은 흐름·필드 의미·다른 시스템 연결(Kafka 토픽·STOMP 경로 등) 설명.
   - `@ApiResponses`는 200/201/202/400/404/413/500 등 실제 발생 가능한 코드별 한국어 설명.

4. **파라미터·DTO에 `@Parameter` / `@field:Schema`**
   - `description` 한국어 + `example` 값 필수(특히 종목 코드 `005930`, ISO date, runId UUID 등).
   - enum도 `@Schema(description=...)`을 각 값에 부여.

**예시 패턴 참조 파일** (2026-04-28 시점):
- `api/src/main/kotlin/com/mj/api/controller/BacktestController.kt` — 컨트롤러 풀 어노테이션
- `api/src/main/kotlin/com/mj/api/application/backtest/BacktestRequest.kt` — DTO `@field:Schema`
- `api/src/main/kotlin/com/mj/api/config/OpenApiConfig.kt` — 모듈 OpenAPI 설정
- `batch/src/main/kotlin/com/mj/batch/controller/JobTriggerController.kt` — inner data class에도 `@field:Schema` 적용

**Springdoc 의존성:** `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0` (Spring Boot 3.3.5 호환). 모든 servlet web 모듈(api, batch)에 추가.

**보안 충돌 주의:** SecurityConfig에서 `/swagger-ui/**`·`/v3/api-docs/**`이 `permitAll`인지 확인. 현재(Phase E)는 `anyRequest().permitAll()`이라 문제없음. Phase F 인증 도입 시 swagger 경로 명시 허용 추가.
