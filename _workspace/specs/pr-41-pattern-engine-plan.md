# PR-41 plan: pattern-engine 모듈 분할 계획

- 작성: architect
- 날짜: 2026-05-10 (KST)
- 짝 spec: `pr-41-pattern-engine-design.md`

## 1. PR 분할 (2건)

| PR | 담당 | 영역 | LOC 추정 |
|---|---|---|---|
| **PR-43** | backend-core | 빌드 그래프 + RedisPatternCacheAdapter unify + 게이트 제거 | ~80 라인 (build files + import + 게이트) + 삭제 1파일 |
| **PR-42** | strategy-engine | 62파일(+5 test) 이동 + 패키지 리네이밍 + JvmStrategyEvaluator import 갱신 | ~200 라인 import 수정 + 67파일 git mv |

### 1-1. PR-43 (backend-core) — 빌드 그래프 + 게이트 정리

영역: build.gradle.kts·settings.gradle.kts·api 일부 (단일 책임 = 모듈 그래프 + api 정합).

작업 범위 (LOCK):

1. `settings.gradle.kts` — `include("pattern-engine")` 추가
2. `pattern-engine/build.gradle.kts` 신설 — design §4-4 시그니처 정합
3. `api/build.gradle.kts` — `implementation(project(":pattern-engine"))` 추가
4. `consumer/build.gradle.kts` — `implementation(project(":pattern-engine"))` 추가 (PR-42 이동 후 의존 보장 — 빈 등록 자동 발생)
5. `api/src/main/kotlin/.../adapter/pattern/RedisPatternCacheAdapter.kt` — **삭제** (pattern-engine으로 unify)
6. `api/src/main/kotlin/.../controller/PatternController.kt`:
   - `import kr.spdaewoon.magicjar.api.adapter.pattern.RedisPatternCacheAdapter` → `import kr.spdaewoon.magicjar.pattern.cache.RedisPatternCacheAdapter`
   - `@ConditionalOnProperty(name = ["magicjar.pattern.enabled"], ...)` 1줄 제거
   - `import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty` 미사용 import 제거
7. `api/src/test/kotlin/.../PatternControllerTest.kt`:
   - `import kr.spdaewoon.magicjar.api.adapter.pattern.RedisPatternCacheAdapter` → `import kr.spdaewoon.magicjar.pattern.cache.RedisPatternCacheAdapter`
   - `@TestPropertySource(properties = ["magicjar.pattern.enabled=true"])` 제거
   - `import org.springframework.test.context.TestPropertySource` 미사용 import 제거

검증:

- `./gradlew :api:compileKotlin` (PR-42 미머지 상태로는 fail 예상 — pattern-engine 빈 등록 0). 따라서 PR-43 단독 머지 금지 → §3 머지 순서 LOCK 참조
- 통합 시 `./gradlew check` 통과 후 `scripts/build-and-deploy.sh api-app` 검증

CHANGELOG 슬롯 row: `<!-- pr-41-slot-backend-core -->` (architect가 사전 발행).

### 1-2. PR-42 (strategy-engine) — 62파일 이동 + 패키지 리네이밍

영역: pattern-engine 모듈 (신설된 모듈 안 + consumer pattern import 갱신).

작업 범위 (LOCK):

1. `pattern-engine/src/main/kotlin/kr/spdaewoon/magicjar/pattern/` 디렉토리 신설
2. `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/application/pattern/` 62파일 git mv → `pattern-engine/src/main/kotlin/kr/spdaewoon/magicjar/pattern/`
   - JvmPatternRecognizer.kt
   - cache/RedisPatternCacheAdapter.kt
   - util/CandleMath.kt
   - helper/{ConsecutiveDirectionCalculator, SimpleTripleHelper}.kt
   - detector/single/*.kt (14파일)
   - detector/triple/*.kt (27파일)
   - detector/pair/*.kt (16파일)
3. 62파일 패키지 선언 갱신: `package kr.spdaewoon.magicjar.consumer.application.pattern.*` → `package kr.spdaewoon.magicjar.pattern.*`
4. 62파일 내부 import 갱신: `import kr.spdaewoon.magicjar.consumer.application.pattern.*` → `import kr.spdaewoon.magicjar.pattern.*`
5. `consumer/src/test/kotlin/.../application/pattern/` 5파일 git mv → `pattern-engine/src/test/kotlin/kr/spdaewoon/magicjar/pattern/` (PatternDslTokenTest는 import 추적 후 결정 — strategy-engine sub-agent가 판단)
6. `consumer/src/main/kotlin/.../strategy/JvmStrategyEvaluator.kt` import 갱신:
   - `import kr.spdaewoon.magicjar.consumer.application.pattern.*` 등 4건(추정) → `kr.spdaewoon.magicjar.pattern.*`
7. (만약) `consumer/src/main/kotlin/.../strategy/KisYamlDslParser.kt` import 추적 — strategy-engine sub-agent 판단

검증:

- `./gradlew :pattern-engine:check` 통과 (62파일 컴파일 + 5 test 통과)
- `./gradlew :consumer:check` 통과 (JvmStrategyEvaluator + KisYamlDslParser 컴파일)
- `./gradlew :pattern-engine:test --tests "kr.spdaewoon.magicjar.pattern.JvmPatternRecognizerTest"` 등 5 test class 통과
- `init { require(detectors.size == 57) }` 부팅 검증 — `./gradlew :consumer:bootJar` 후 컨테이너 부팅

KIS-first 의무 (헌법 §3):
- 옮기는 62파일의 KDoc 안에 KIS candlestick.py 줄 인용 보존 (`Source: https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/candlestick.py`)
- sub-agent 자체 추론 금지 — 패키지 선언·import 외 본문 변경 0
- 새 파일 추가 0 (이동만)

CHANGELOG 슬롯 row: `<!-- pr-41-slot-strategy-engine -->` (architect가 사전 발행).

## 2. 시나리오 비교

### 시나리오 A — 완전 병렬 (PR-42 + PR-43 동시 발사, 동시 머지 대기)

| 항목 | 평가 |
|---|---|
| 영역 침범 | 0 — backend-core(빌드 그래프 + api 모듈) vs strategy-engine(pattern-engine 모듈 + consumer import) 영역 깔끔 |
| modify-shared | CHANGELOG.md 슬롯 row 사전 발행으로 hunk 분리 (헌법 §4 정합) |
| 단독 빌드 | PR-43 단독 머지 시 pattern-engine 모듈 미존재로 빌드 fail. PR-42 단독 머지 시 빌드 그래프에 pattern-engine 미include로 빌드 fail |
| 머지 협조 | 둘 다 PR-ready 상태에서 동시 rebase + 동시 머지 필요 (orchestrator 영역) |
| 빌드 안전 | 두 PR 모두 머지된 후에야 main이 컴파일 통과 |

→ 권장: **시나리오 A** (영역 분리 깔끔 + add-only 위주). 단, 둘 다 PR ready 후 동시 머지 협조 의무.

### 시나리오 B — 직렬 (PR-43 먼저 → PR-42 나중)

| 항목 | 평가 |
|---|---|
| 빌드 안전 | PR-43이 pattern-engine 모듈 신설 + 빈 src 디렉토리 시 빌드 가능 (의존만 추가, 사용 0이면 unused dependency warning만) |
| 단독 빌드 | PR-43 머지 시점에 api 모듈은 pattern-engine RedisPatternCacheAdapter import — 빈 모듈이라 fail. → 빈 모듈에 stub 파일 1개 두는 방식이 부담 |
| 시간 | PR-43 머지 → PR-42 rebase → 머지. 직렬 = 시간 2배 |

→ 거부: 시나리오 B는 PR-43 단독 머지 시 fail. stub 임시 파일은 spec 비대상.

### 시나리오 C — 단일 PR (62파일 + 빌드 그래프 + 게이트 제거 모두 한 PR)

| 항목 | 평가 |
|---|---|
| 영역 침범 | 단일 책임 PR 룰(룰 2) 위반 — backend-core + strategy-engine 영역 동시 |
| LOC | ~280 라인 + 67파일 git mv = 단일 PR 부담 |
| 리뷰 부담 | 영역 혼합으로 리뷰 어려움 |

→ 거부: 룰 2 위반.

**결정: 시나리오 A 채택** — 두 PR 동시 발사 + ready 후 동시 머지 협조 (orchestrator 영역).

## 3. 머지 순서 LOCK

시나리오 A 채택에 따른 운영 룰:

1. **PR-42 + PR-43 동시 background dispatch** (orchestrator)
2. 두 PR 모두 sub-agent push 완료 + CI green 대기
3. 한 쪽 ready 후 다른 쪽 ready 대기 (block)
4. 둘 다 ready 후:
   - 첫 번째 머지 (예: PR-43 backend-core)
   - 두 번째 즉시 rebase + 머지 (예: PR-42 strategy-engine)
   - main 통합 후 `./gradlew check` 통합 검증
5. 통합 fail 시 orchestrator 자율 hotfix (헌법 §7)

대안 머지 순서 (더 안전):

- **두 PR을 architect가 한 번에 통합 머지**: orchestrator가 두 sub-agent ready 확인 후 양쪽 PR을 동시 머지(2분 이내). main에 두 PR이 동시 적용된 시점에서만 컴파일 통과.

## 4. 충돌 행렬 요약 (상세는 conflict-matrix.md)

modify-shared (강한 LOCK):
- `settings.gradle.kts` → backend-core 단독
- `consumer/build.gradle.kts` → backend-core 단독 (strategy-engine은 미수정)
- `consumer/src/main/kotlin/.../strategy/JvmStrategyEvaluator.kt` → strategy-engine 단독
- `api/.../PatternController.kt` → backend-core 단독
- `api/src/test/.../PatternControllerTest.kt` → backend-core 단독
- `CHANGELOG.md` → architect가 슬롯 prepend, sub-agent는 본인 row만 in-place edit (헌법 §4)

add-only:
- `pattern-engine/build.gradle.kts` → backend-core
- `pattern-engine/src/main/kotlin/kr/spdaewoon/magicjar/pattern/**` 62파일 → strategy-engine
- `pattern-engine/src/test/kotlin/kr/spdaewoon/magicjar/pattern/**` 5파일 → strategy-engine

delete (git mv):
- `consumer/src/main/kotlin/.../application/pattern/**` 62파일 → strategy-engine
- `consumer/src/test/kotlin/.../application/pattern/**` 5파일 → strategy-engine
- `api/.../adapter/pattern/RedisPatternCacheAdapter.kt` → backend-core

## 5. fail-fast 보고

- sub-agent가 영역 침범 시 (예: backend-core가 62파일 본문 수정) → orchestrator 즉시 reject + 재디스패치
- `./gradlew :pattern-engine:check` fail 시 → strategy-engine sub-agent 본인 PR 안에서 수정
- `./gradlew :api:bootJar` 후 컨테이너 부팅 fail (`No bean of type PatternRecognizer`) → 빌드 그래프 누락 검증 + backend-core hotfix

## 6. 인터페이스 동결 시점

본 spec PR-41 머지 직후 — 다음은 LOCK:

| 항목 | 변경 금지 (~PR-42·PR-43 머지까지) |
|---|---|
| `@Component("patternRecognizer")` bean name | LOCK |
| `domain.pattern.port.PatternRecognizer` 시그니처 | LOCK (consumer JvmStrategyEvaluator가 의존) |
| Detector init `require(detectors.size == 57)` | LOCK |
| Redis cache key `pattern:cache:{code}:{interval}:{patternsHash}` | LOCK |
| KDoc `Source: https://github.com/koreainvestment/...` 인용 | LOCK |

## 7. 산출물 체크리스트 (sub-agent별)

### PR-43 (backend-core)
- [ ] `settings.gradle.kts` include 추가
- [ ] `pattern-engine/build.gradle.kts` 신설
- [ ] `api/build.gradle.kts` implementation 추가
- [ ] `consumer/build.gradle.kts` implementation 추가
- [ ] `api/.../adapter/pattern/RedisPatternCacheAdapter.kt` 삭제
- [ ] `api/.../PatternController.kt` import + `@ConditionalOnProperty` 제거
- [ ] `api/.../PatternControllerTest.kt` import + `@TestPropertySource` 제거
- [ ] `./gradlew :api:check` 통과 (PR-42 통합 후)
- [ ] CHANGELOG `pr-41-slot-backend-core` row in-place edit

### PR-42 (strategy-engine)
- [ ] 62파일 git mv (consumer → pattern-engine)
- [ ] 62파일 패키지 선언 + import 갱신
- [ ] 5 test 파일 git mv + 패키지 갱신
- [ ] `consumer/.../JvmStrategyEvaluator.kt` import 갱신
- [ ] `consumer/.../KisYamlDslParser.kt` import 갱신 (필요 시)
- [ ] `init { require(detectors.size == 57) }` 보존
- [ ] KDoc KIS candlestick.py URL 인용 보존
- [ ] `./gradlew :pattern-engine:check` 통과
- [ ] `./gradlew :consumer:check` 통과 (PR-43 통합 후)
- [ ] CHANGELOG `pr-41-slot-strategy-engine` row in-place edit

## 8. 후속 사이클 (post-PR-41/42/43)

- `./gradlew check` 통합 검증 + scripts/build-and-deploy.sh로 api·consumer 컨테이너 부팅 smoke
- `magicjar.pattern.enabled` 환경변수 제거 (`.env.example`·`application-*.yml` 검색) — backend-core 후속 또는 본 PR-43 흡수
- v3.1.3 hotfix tag 또는 v3.2.0 minor — 사용자 결정
- backlog 추가 검토: indicator-engine 동일 패턴 적용 여부 (별도 사이클)

## 9. Links

- `pr-41-pattern-engine-design.md` (짝 spec)
- `pr-41-dispatch-spec.md` (sub-agent 디스패치 명세)
- `pr-41-conflict-matrix.md` (충돌 행렬)
- ADR-0004 (모듈 매트릭스 갱신)
- ADR-0054 (pattern-engine 신설)
- pr-38-w2-t-pat-design.md (W2 T-PAT 본 spec)
- 헌법 §4 (CHANGELOG 슬롯), §5 (rebase)
