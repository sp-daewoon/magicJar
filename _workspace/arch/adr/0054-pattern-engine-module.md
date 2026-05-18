# ADR 0054: pattern-engine 라이브러리 모듈 분리 — 도메인 순수성 보존 + api·consumer 공유

- 상태: Accepted
- 작성일: 2026-05-10
- 결정자: 사용자 sp-daewoon (최고 관리자) + architect 합의
- 관계: ADR 0004(MSA 모듈 재구성)를 **갱신**(supersede 아님 — 4 모듈 → 5 모듈, 의존 방향·도메인 순수성 정책 보존)

## 맥락

W2 T-PAT PR-39b(2026-05-09 머지)에서 패턴 인식기 구현체 62파일을 `consumer/src/main/kotlin/.../application/pattern/` 하위에 배치했다. 결과:

- `JvmPatternRecognizer`(`@Component("patternRecognizer")`) bean이 `consumer` 컨테이너에서만 등록됨
- `api/src/main/kotlin/.../controller/PatternController`는 `domain.pattern.port.PatternRecognizer`에 의존하지만, api 컨테이너 부팅 시 빈 미발견 → ApplicationContext fail
- v3.1.2 hotfix(PR #261)에서 `PatternController`에 `@ConditionalOnProperty(name="magicjar.pattern.enabled", havingValue="true", matchIfMissing=false)` 임시 게이트 + `PatternControllerTest`에 `@TestPropertySource` 추가로 응급 봉합

사용자(최고 관리자) 인식: "API 모듈은 consumer를 의존하면 안 됨, MSA 정합 위배". 정확한 인식 — ADR 0004 §의존 방향 명시: "api/batch/consumer는 서로 직접 의존하지 않는다. 상호작용은 Kafka 또는 HTTP".

근원: `domain` 모듈은 ADR 0004로 "Spring·JPA 의존 0" 정책이 LOCK이라 `@Component` 인식 구현체 등록 자리가 아님. 그러나 `api`·`consumer` 둘 다 `PatternRecognizer` 구현 빈을 필요로 함. 결과적으로 4 JVM 모듈 구조로는 모든 분배가 정책 위반.

## 검토한 대안

| 대안 | 장점 | 단점 |
|---|---|---|
| A. api → consumer 의존 추가 | 변경량 최소 (build.gradle 1줄) | ADR 0004 §의존 방향 위반 + Kafka 이중 소비 risk + `consumer/.../KisWebSocketBridge` 같은 KafkaListener 빈이 api 컨테이너에서도 등록되어 동일 토픽 이중 소비 |
| **B-1. pattern-engine 라이브러리 모듈 신설** (본 결정) | domain 순수성 보존 + api·consumer 동일 빈 공유 + 컨테이너 수 불변 (api·batch·consumer 3개 그대로) + Hexagonal 정석 정합 | 모듈 1개 신설 비용 (settings.gradle.kts·build.gradle.kts·디렉토리) |
| B-2. domain에 Spring `@Component` 흡수 | 모듈 추가 0 | ADR 0004 "domain Spring 의존 0" 정책 깨짐 — domain은 어떤 응용도 참조 0 의무 + Hexagonal port 순수성 위반 |
| C. PatternController 영구 비활성화 (`@ConditionalOnProperty` 유지) | hotfix 그대로 + 코드 변경 0 | T-PAT 카탈로그(W2) 사용자 미노출 = 트랙 효과 0. 영구 게이트는 결함 봉합 응급 한정 |

## 결정

**`pattern-engine` 라이브러리 모듈을 신설한다.** 

- 위치: `pattern-engine/` (root 직속, domain과 동일 레벨)
- 종류: Kotlin + Spring Context (Spring Boot main 0, 라이브러리 jar)
- 패키지 루트: `kr.spdaewoon.magicjar.pattern.*`
- 의존: `pattern-engine` → `domain`만. `api`·`consumer` → `pattern-engine` 의존 추가
- 컨테이너: 0 (라이브러리 jar — 컨테이너 수는 api·batch·consumer 3개 유지)

### 모듈 매트릭스 (5모듈 = ADR 0004 갱신)

```
.
├─ domain/         # Kotlin java-library (Spring 0 — 도메인 모델·port·DSL·정책)
├─ pattern-engine/ # ← 신설 — Kotlin + Spring Context (Boot main 0)
├─ api/            # Spring Boot — implementation(":pattern-engine")
├─ batch/          # Spring Boot — pattern 미사용 (의존 추가 0)
└─ consumer/       # Spring Boot — implementation(":pattern-engine")
```

### 의존 방향 (단방향, ADR 0004 정합 보존)

- `domain` ← 모든 모듈 의존 (이전과 동일)
- `pattern-engine` ← `api`, `consumer` 의존
- `pattern-engine` → `domain`만 의존
- `api`, `batch`, `consumer`는 **여전히 서로 직접 의존하지 않는다** (ADR 0004 §의존 방향 보존)

### 책임

`pattern-engine`은 다음 자원을 보유:

- 57 detector (`detector/single/*` 14 + `detector/triple/*` 27 + `detector/pair/*` 16)
- `JvmPatternRecognizer` (`@Component("patternRecognizer")` — bean name LOCK)
- 패턴 헬퍼 (`ConsecutiveDirectionCalculator`, `SimpleTripleHelper`)
- 캔들 수학 (`CandleMath`)
- Redis 캐시 어댑터 (`RedisPatternCacheAdapter` — 분봉 300s / 일봉 86400s TTL, api·consumer unify 단일 구현)

근거 출처: KIS Strategy Builder `candlestick.py` 1:1 port (W2 T-PAT spec PR-38 + raw 검증 `pr-38-kis-pattern-raw-verification.md`).

## 결과

긍정:
- ADR 0004 §의존 방향 정합 보존 (api·consumer 직접 의존 0)
- domain 순수성 정책 보존 (Spring 의존 0)
- 컨테이너 수 불변 (api·batch·consumer 3개)
- KafkaListener 격리 보존 (consumer 단독 KafkaListener 등록)
- v3.1.2 hotfix `@ConditionalOnProperty` 임시 게이트 자연 제거 → 정상 부팅
- Hexagonal 정석 정합 — domain port + pattern-engine 구현 분리

부정:
- 모듈 1개 신설 비용 (settings.gradle.kts·build.gradle.kts·디렉토리 1회)
- 62파일 패키지 리네이밍 마이그레이션 (PR-42 strategy-engine 영역, ~200 라인 import + 67 git mv)
- 향후 `indicator-engine`·strategy 평가기 등에 동일 패턴 검토 부담 (단, 본 ADR은 pattern-engine 한정)

## 후속

- ADR 0004 갱신 동반 (4 → 5 모듈 매트릭스)
- master-architecture.md §5·§6 갱신 (architect 단독, 본 PR-41)
- PR-41 (architect spec/plan) → PR-42 (strategy-engine 코드 이동) + PR-43 (backend-core 빌드 그래프 + 게이트 제거) 분배
- 머지 정책: 두 PR 모두 ready 후 동시 머지 (시나리오 A — `pr-41-pattern-engine-plan.md` §2 정합)
- 환경변수 `magicjar.pattern.enabled` 자연 무효화 (`.env.example`·`application-*.yml` cleanup은 PR-43 또는 후속 hotfix)
- 향후 indicator-engine 동일 패턴 적용 검토 — backlog 추가 (별도 ADR 필요 시점에 발급, 본 ADR 비대상)

## Links

- ADR 0004 (MSA 모듈 재구성) — 본 ADR이 갱신 대상
- ADR 0030 (master-architecture SoT) — §5·§6 갱신 정합
- ADR 0050 (CHANGELOG 슬롯 채번) — PR-41 슬롯 3건 발행 정합
- ADR 0083 (indicator-impl 모듈 신설 — 본 ADR §후속 "indicator-engine 동일 패턴 적용 검토" 완수, 2026-05-18)
- `_workspace/specs/pr-41-pattern-engine-design.md` (본 결정의 sub-agent 디스패치 spec)
- `_workspace/specs/pr-41-pattern-engine-plan.md` (분할 plan)
- `_workspace/specs/pr-41-conflict-matrix.md` (충돌 행렬)
- `_workspace/specs/pr-38-w2-t-pat-design.md` (W2 T-PAT 본 spec)
- `_workspace/specs/pr-38-kis-pattern-raw-verification.md` (KIS candlestick.py raw 검증)
- 헌법 §3 KIS-first (본 사이클 raw fetch 발동 0 — 코드 이동만)
- PR #261 v3.1.2 hotfix — 본 ADR이 정정 대상 잔재
