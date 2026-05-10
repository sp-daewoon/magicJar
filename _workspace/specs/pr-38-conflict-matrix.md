# PR-38 충돌 행렬 (T-PAT R1+R2 — 완전 병렬 권장 시나리오 A)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-10
- 정합 SoT: `_workspace/specs/pr-38-w2-t-pat-design.md` v1 + `pr-38-dispatch-spec.md` v1 + `pr-38-kis-pattern-raw-verification.md` v1
- 본 문서: PR-39a (strategy-engine) + PR-39b (backend-core) 병렬 디스패치 시 modify-shared·add-add 충돌 사전 점검 매트릭스.
- 헌법 §4 (CHANGELOG 슬롯 채번룰) + 사용자 메모리 `feedback_orchestrator_active_parallel.md` (적극 병렬 디스패치) 정합.

---

## §1. 결론 (TL;DR)

**시나리오 A — 완전 병렬 권장**. PR-39a·PR-39b 동시 background 발사 안전.

- modify-shared 1건 (PatternRecognizerStub.kt) — backend-core 단독 LOCK + 머지 시점 조정으로 충돌 회피
- add-add 0건 (둘 다 신규 생성하는 동일 파일 0)
- CHANGELOG add-add는 ADR 0050 in-place edit으로 충돌 회피 (anchor + slot row 사전 발행 정합)
- 인터페이스 동결: PR-39b가 domain port 신규 add-only → PR-39a는 read-only import만

orchestrator는 본 spec PR-38 머지 즉시 PR-39a·PR-39b 동시 background 발사 가능. 직렬 권장 답습 금지 (사용자 메모리 정합).

**머지 순서 권장**: PR-39a 먼저 → PR-39b (stub 삭제는 PR-39a 머지 후 rebase 시점). 또는 동시 머지 시점 조정.

---

## §2. modify-shared 매트릭스 (기존 파일 동시 수정)

| 파일 | PR-39a strategy-engine | PR-39b backend-core | 충돌 위험 | 해결 |
|---|---|---|---|---|
| `domain/build.gradle.kts` | (touch 0) | (touch 0 — kotlinx-datetime·java.math 기존 dep 충분) | 0 | - |
| `consumer/build.gradle.kts` | (touch 0 — Redis·Spring 기존 dep 충분) | (touch 0) | 0 | - |
| `api/build.gradle.kts` | (touch 0 — `:consumer` dep 이미 T-IND·T-DSL에서 추가됨) | (touch 0) | 0 | - |
| `consumer/src/main/resources/application.yml` | (touch 0 — Redis URL 기존) | (touch 0) | 0 | - |
| `api/src/main/resources/application.yml` | (touch 0) | (touch 0) | 0 | - |
| `settings.gradle.kts` | (touch 0 — 기존 :api·:consumer·:domain·:batch 그대로) | (touch 0) | 0 | - |
| `consumer/.../strategy/PatternRecognizerStub.kt` | **(touch 0)** | **삭제** | **modify-shared 1건** | backend-core 단독 LOCK + 머지 시점 조정 (PR-39a 머지 후 rebase 시점에 stub 삭제 commit 추가) |
| `consumer/.../strategy/JvmStrategyEvaluator.kt` | (touch 0 — DI bean name 정합 보존) | (touch 0 — stub 삭제는 코드 호출 변경 0건) | 0 | - |
| `consumer/.../strategy/DslIdentifierMapper.kt` | (touch 0 — T-DSL 매핑 표 그대로) | (touch 0) | 0 | - |
| `_workspace/strategy/pattern-catalog.md` | (touch 0) | **수정 — R1·R2 매트릭스 갱신** | 0 | backend-core 단독 |
| `CHANGELOG.md` | **본인 row in-place edit (작성: strategy-engine)** | **본인 row in-place edit (작성: backend-core)** | 0 | ADR 0050 — `(작성: ...)` 마커 row 1줄만 수정. anchor `<!-- pr-38-slot-* -->` hunk 분리. git diff line 다름 인식. |

**총 modify-shared 1건 (PatternRecognizerStub.kt — backend-core 단독 LOCK)** — strategy-engine touch 0 보장 시 충돌 0.
**CHANGELOG 1건** — ADR 0050 in-place edit으로 충돌 0.
**catalog 1건** — backend-core 단독 LOCK이라 충돌 0.

---

## §3. add-add 매트릭스 (둘 다 신규 생성 동일 파일)

| 파일 | PR-39a strategy-engine | PR-39b backend-core | 충돌 위험 |
|---|---|---|---|
| `domain/.../pattern/PatternMatch.kt` | (touch 0) | **신규** | 0 |
| `domain/.../pattern/Direction.kt` | (touch 0) | **신규** | 0 |
| `domain/.../pattern/DetectorResult.kt` | (touch 0) | **신규** | 0 |
| `domain/.../pattern/PatternCatalog.kt` | (touch 0) | **신규** | 0 |
| `domain/.../pattern/PatternException.kt` | (touch 0) | **신규** | 0 |
| `domain/.../pattern/port/PatternRecognizer.kt` | (touch 0) | **신규** | 0 |
| `domain/.../pattern/port/PatternDetector.kt` | (touch 0) | **신규** | 0 |
| `consumer/.../pattern/JvmPatternRecognizer.kt` | **신규** | (touch 0) | 0 |
| `consumer/.../pattern/util/CandleMath.kt` | **신규** | (touch 0) | 0 |
| `consumer/.../pattern/helper/SimpleTripleHelper.kt` | **신규** | (touch 0) | 0 |
| `consumer/.../pattern/helper/ConsecutiveDirectionCalculator.kt` | **신규** | (touch 0) | 0 |
| `consumer/.../pattern/detector/single/{14 파일}.kt` | **신규** | (touch 0) | 0 |
| `consumer/.../pattern/detector/double/{16 파일}.kt` | **신규** | (touch 0) | 0 |
| `consumer/.../pattern/detector/triple/{27 파일}.kt` | **신규** | (touch 0) | 0 |
| `consumer/.../pattern/cache/RedisPatternCacheAdapter.kt` | (touch 0) | **신규** | 0 |
| `api/.../controller/PatternController.kt` | (touch 0) | **신규** | 0 |
| `api/.../controller/dto/PatternMatchDto.kt` | (touch 0) | **신규** | 0 |
| `api/.../config/PatternRecognizerConfig.kt` | **신규 (LOCK)** | (touch 0) | 0 |

**총 add-add 0건** — 양쪽 영역 add-only로 완전 분리.

**테스트 add-add**: PR-39a strategy-engine 단위 테스트 5종 신규 (consumer/src/test/kotlin/.../pattern/) — backend-core는 테스트 0건이라 충돌 0.

**Detector 57 파일 add-add**: 모두 PR-39a strategy-engine 단독. backend-core는 detector 0건이라 충돌 0.

---

## §4. CHANGELOG hunk 분리 (ADR 0050 정합)

본 spec PR-38 머지 시점에 sub-agent별 anchor + slot row 사전 발행:

```markdown
| 2026-05-10 | architect spec row | docs/spec | ... |
<!-- pr-38-slot-architect -->
| 2026-05-10 | TBD ... (작성: strategy-engine) — TBD | consumer/api | TBD |
<!-- pr-38-slot-strategy-engine -->
| 2026-05-10 | TBD ... (작성: backend-core) — TBD | domain/api/consumer | TBD |
<!-- pr-38-slot-backend-core -->
| 2026-05-10 | TBD ... (작성: frontend, R3 PR-40) — TBD | frontend/consumer | TBD |
<!-- pr-38-slot-frontend -->
<!-- pr-38-slot-end -->
```

### 4-1. 머지 순서별 충돌 시나리오

#### 시나리오 1 — strategy-engine 먼저 머지 (architect 권장)

1. strategy-engine PR-39a 머지 → CHANGELOG `(작성: strategy-engine)` row 채워짐 (Line N)
2. backend-core PR-39b는 main rebase → 본인 row `(작성: backend-core)` 위치 그대로 (Line N+1, anchor `<!-- pr-38-slot-backend-core -->` 변경 0)
3. backend-core fillout 후 push → CHANGELOG diff = backend-core row만 수정 → 충돌 0
4. backend-core rebase 시점에 PatternRecognizerStub.kt 삭제 commit 추가 (PR-39a 머지 후라 stub 삭제해도 빌드 OK)
5. backend-core push → 충돌 0 (stub 파일은 PR-39a touch 0)

#### 시나리오 2 — backend-core 먼저 머지

1. backend-core PR-39b 머지 (단 stub 삭제 commit은 보류)
   - backend-core는 PR-39a 머지 전이라 stub 삭제 시 부팅 시 PatternRecognizer bean 0건 → DSL pattern() 호출 NullPointerException risk
   - **해결**: backend-core가 stub 삭제 commit을 별도로 분리 → PR-39a 머지 후 hotfix commit으로 적용. 또는 stub은 그대로 두고 PR-39a JvmPatternRecognizer가 진짜 bean 등록 시 ConditionalOnMissingBean 자동 비활성화 (5-B fallback).
2. strategy-engine PR-39a는 main rebase → JvmPatternRecognizer 신규 add + ConditionalOnMissingBean 정합
3. strategy-engine push → 충돌 0 (domain port는 backend-core가 이미 머지)
4. strategy-engine 머지 후 backend-core가 stub 삭제 hotfix commit (PR-39c 또는 5-A LOCK 후속)

**architect 결정 = 시나리오 1 권장 (PR-39a 먼저)**. backend-core는 도메인 port + REST + 캐시 + catalog 갱신 작업만 머지 + stub 삭제는 PR-39a 머지 후 rebase 시점.

---

## §5. 인터페이스 동결 시점 + 시그니처 LOCK

본 spec PR-38 머지 즉시 — 양쪽 sub-agent가 다음 시그니처를 자체 추론 0으로 LOCK:

### 5-1. domain port 시그니처 (PR-39b 신규 add)

```kotlin
// domain/.../pattern/port/PatternRecognizer.kt
interface PatternRecognizer {
    fun recognize(candles: List<Candle>, patterns: Set<String>): List<PatternMatch>
    fun recognize(name: String, candles: List<Candle>): Boolean
    fun consecutive(direction: String, candles: List<Candle>): Int
}

// domain/.../pattern/port/PatternDetector.kt
interface PatternDetector {
    val id: String
    val minCandles: Int get() = 5
    fun detect(candles: List<Candle>): DetectorResult
}

// domain/.../pattern/PatternMatch.kt
data class PatternMatch(
    val pattern: String,
    val startTime: Instant,
    val endTime: Instant,
    val direction: Direction,
    val confidence: BigDecimal,
    val params: Map<String, BigDecimal>? = null,
)

// domain/.../pattern/Direction.kt
enum class Direction { BULL, BEAR, NEUTRAL }

// domain/.../pattern/DetectorResult.kt
data class DetectorResult(
    val direction: Direction,
    val confidence: BigDecimal = BigDecimal.ONE,
    val params: Map<String, BigDecimal>? = null,
)
```

### 5-2. REST endpoint 시그니처 (PR-39b 신규 add)

```
GET /api/patterns?code=...&interval=...&patterns=hammer,engulfing&from=...&to=...
→ 200 OK + PatternResponseDto { matches: List<PatternMatchDto> }
```

### 5-3. Redis 캐시 키 (PR-39b 신규 add)

```
pattern:cache:{code}:{interval}:{patternsHash}    TTL 분봉 300s / 일봉 86400s
```

### 5-4. JvmPatternRecognizer bean name (PR-39a 신규 add)

```kotlin
@Component("patternRecognizer")     // T-DSL stub @ConditionalOnMissingBean 자동 비활성화
class JvmPatternRecognizer(detectors: List<PatternDetector>) : PatternRecognizer { ... }
```

본 시그니처는 PR-38 머지 후 양쪽 sub-agent 진행 중 변경 0건 의무. 변경 필요 시 architect 호출 → spec 갱신.

---

## §6. 머지 후 검증 의무 (양쪽 sub-agent 모두)

각 sub-agent는 본인 PR 머지 후 다음 검증 의무 (orchestrator 또는 본인):

### 6-1. PR-39a strategy-engine 머지 후

- [ ] `docker logs magicjar-consumer-app | grep "PatternRecognizer"` — "Registered 57 pattern detectors" log
- [ ] `curl 'http://localhost:8080/api/patterns?code=005930&interval=1d&patterns=hammer'` — 200 OK (DSL stub은 아직 활성 — backend-core stub 삭제 전)
- [ ] DSL strategy `pattern("hammer")` evaluate — JvmPatternRecognizer bean 호출 (bean name 정합 검증)

### 6-2. PR-39b backend-core 머지 후 (PR-39a 머지 후)

- [ ] `docker logs magicjar-consumer-app | grep "PatternRecognizerStub"` — 0건 (stub 삭제 정합)
- [ ] `curl 'http://localhost:8080/api/patterns?code=005930&interval=1d&patterns=hammer'` — 200 OK
- [ ] DSL `pattern("hammer")` evaluate — `EvalResult.signal=BUY` (hammer 인식 시) — stub 시점 항상 HOLD에서 정상 분기 활성화
- [ ] DSL `consecutive("up") >= 3` evaluate — true (3봉 연속 양봉 시)

---

## §7. 함정 사전 점검

### 7-1. ADR 0051 — Repository default fun 함정

본 PR-38은 신규 JPA Repository 0건 — Redis 어댑터(StringRedisTemplate 직접 사용)만 사용. ADR 0051 함정 발동 0건.

### 7-2. PatternRecognizer bean name 충돌

T-DSL의 `PatternRecognizerStub`은 `@ConditionalOnMissingBean(name = ["patternRecognizer"])`로 등록. PR-39a JvmPatternRecognizer는 `@Component("patternRecognizer")` 의무 — 이름 정합 시 자동 비활성화.

만약 sub-agent가 `@Component`(default name = "jvmPatternRecognizer") 로만 등록하면 ConditionalOnMissingBean이 매칭 안 함 → stub bean도 동시 등록 → DSL evaluator가 어느 bean 사용할지 모호 → NullPointerException 또는 NoUniqueBeanDefinitionException risk.

**해결**: PR-39a strategy-engine sub-agent는 spec §5-C LOCK 정합 — `@Component(name = "patternRecognizer")` 또는 `@Bean(name = "patternRecognizer")` 명시 의무.

### 7-3. detectors.size != 57 빌드 시점 검증

`init { require(detectors.size == 57) }` 강제. 56개 또는 58개 등록 시 부팅 실패 → 명확한 에러 메시지로 sub-agent 자체 수정.

### 7-4. circular dep — api → consumer

T-IND PR-32에서 `:api`가 `:consumer` 의존 추가됨. 본 PR-38은 그 dep 재사용. 새 dep 추가 0건이라 circular dep risk 0건. 단 PatternRecognizerConfig (api 모듈)이 consumer 패키지의 PatternDetector 구현체 component scan 영역에 포함되는지 검증 의무 — `./gradlew :api:bootJar` 빌드 검증.

---

## §8. 분배 모드 결정 (architect 권장)

### 8-1. 권장 모드: 시나리오 A — 완전 병렬

| 결정 항목 | 값 |
|---|---|
| 디스패치 모드 | **완전 병렬** (사용자 메모리 `feedback_orchestrator_active_parallel.md` 정합) |
| 발사 시점 | PR-38 머지 즉시 |
| 동시 sub-agent | 2 (strategy-engine + backend-core) |
| 머지 순서 권장 | strategy-engine 먼저 → backend-core (stub 삭제 시점 정합) |
| wallclock 단축 | ~23% (PR-39a 비중 압도적 — 직렬 ~13h vs 병렬 ~10h) |
| 위험도 | **낮음** (modify-shared 1건 backend-core 단독 LOCK + add-only 100%) |

### 8-2. fallback: 시나리오 B — 직렬

사용자 명시 시 또는 architect 사후 detection 시:
1. PR-39b backend-core 먼저 머지 (domain port + REST + catalog) — stub 보존
2. PR-39a strategy-engine 머지 (JvmPatternRecognizer) — ConditionalOnMissingBean 자동 비활성화
3. 별도 PR-39c (backend-core) — stub 파일 삭제 hotfix

이 모드는 1 PR 추가 발생 + wallclock 길어짐. architect 비권장.

---

## §9. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-10 | PR-38 충돌 행렬 v1 발행. modify-shared 1건(PatternRecognizerStub backend-core 단독 LOCK) + add-add 0건 + CHANGELOG ADR 0050 in-place edit 정합. 시나리오 A 완전 병렬 권장 — 머지 순서 strategy-engine 먼저 → backend-core stub 삭제. 인터페이스 동결 시점 = PR-38 머지. 함정 사전 점검 4건. (architect, opus 4.7 1M) |
