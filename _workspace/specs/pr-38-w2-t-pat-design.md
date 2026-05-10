# PR-38 T-PAT 설계 가이드 (W2 진입 — 차트 패턴 57종 인식 + PatternRecognizerPort + DSL stub 1줄 교체)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-10
- 대상 PR 시리즈: PR-39a (strategy-engine — JvmPatternRecognizer + R1 10 detector + 47 stub + Map registry 등록 + 단위 테스트 5종) · PR-39b (backend-core — domain/pattern/* port + DTO + REST 컨트롤러 + Redis 캐시 어댑터 + DSL stub 교체) · PR-40 (R3 — frontend Pattern overlay + LWC markers + KIS 패턴 fixture 일괄 검증)
- 진입 prerequisite: v3.1.0 W1 풀세트 + W2 T-IND 풀세트(PR-33) + W2 T-DSL 풀세트(PR-36) main 위 정상 동작. main HEAD = `d811743` (Living Policy Docs 도입). T-DSL의 `PatternRecognizerStub` (`@ConditionalOnMissingBean(name = ["patternRecognizer"])`)이 본 PR에서 진짜 PatternRecognizer로 1줄 교체 의도.
- 정합 SoT:
  - **ADR 0021** Indicator Source 추상화 (T-PAT는 Indicator 영역과 별도 — Pattern은 candle-only, indicator 의존 0)
  - **ADR 0020** KIS Strategy Builder 흡수 = 명세 port (`candlestick.py` 65 detector 1:1 port — 9 제외 Lean 정합 = 57)
  - **ADR 0022** Strategy DSL = .kis.yaml superset (DSL `pattern("doji")` · `consecutive(up)` 호출 → PatternRecognizer 라우팅)
  - **ADR 0047** DSL Evaluation Sandbox (T-PAT detector도 strategy-eval-pool 내부에서 호출 — 100ms timeout 내 완료 의무)
  - **ADR 0050** CHANGELOG 슬롯 채번룰 (본 spec PR이 sub-agent 슬롯 row 사전 발행 의무)
  - **ADR 0051** Repository default fun 함정 (본 PR-38은 신규 JPA Repository 추가 0건 — 함정 발동 0)
  - `docs/architecture/master-architecture.md` v2 §B.6 (캔들스틱 패턴 57) · §5-1(domain/pattern/) · §5-2 표(consumer JvmPatternRecognizer + api PatternController) · §10 ADR 인덱스
  - `docs/architecture/tracks-roadmap.md` §1 W2 · §2 T-PAT row · §3 T-PAT outline (선행 T-IND / 후행 T-DSL·T-SIG·T-LWC)
  - `docs/architecture/development-rules.md` 5건(룰 2 단일 책임 · 룰 3 라운드 · 룰 4 R3 일괄 · 룰 5 AI 호출 0)
  - `docs/architecture/code-convention.md`
  - `docs/architecture/policies/kis-first-raw-fetch.md` (자체 추론 0 — KIS `candlestick.py` 755 lines raw fetch 의무)
  - `_workspace/specs/pr-38-kis-pattern-raw-verification.md` (본 spec 진입 시 architect 1회 KIS-first raw fetch — 57패턴 LOCK + 7 helper + 진입점 + R1 10건 줄번호 LOCK)
  - `_workspace/strategy/pattern-catalog.md` (57패턴 catalog — Single 14 + Double 16 + Triple+ 27 LOCK + R1 10건 명시)
  - `_workspace/arch/agent-charter.md` §3(KIS-first) · §4(CHANGELOG 슬롯) · §5(rebase) · §6(Repository default fun 함정)

- 작업 원칙 (개발 룰북 정합):
  - **룰 2 — 단일 책임**: PR-39a = strategy-engine 단일 (consumer pattern/ + 57 detector). PR-39b = backend-core 단일 (domain pattern/* + api PatternController + DSL stub 1줄 교체). 영역 LOCK.
  - **룰 3 — 라운드 분할**: 본 PR-38 = R0 spec only(코드 0). PR-39 = **R1+R2 통합** (T-IND·T-DSL 패턴 정합 — domain port + 57 detector + REST + DSL 통합 일괄). PR-40 = R3 (frontend overlay + LWC markers + 57 fixture e2e + 정확성 fixture 일괄).
  - **룰 4 — 코드 먼저 R3 일괄**: PR-39a/b는 단위 테스트 5종(R1 10건 핵심 — doji·hammer·engulfing·morning_star·three_white_soldiers)만 의무. 57 fixture 전수 + LWC e2e + 정확성 cross-check는 **R3 PR-40 일괄**.
  - **룰 5 — AI 호출 0 (W2 LLM import 0건)**: T-PAT는 W2 — consumer/api/domain 어디에도 spring-ai·anthropic·openai import 0건. fitness gap §9 검증.

---

## §1. T-PAT 한 줄 목표 + DoD

### 1-1. 목표

> **사용자가 LWC 차트에서 캔들스틱 패턴 overlay 토글하거나 DSL 식 `pattern("hammer") AND consecutive(up) >= 3` 평가 시, magicJar consumer가 in-process로 57개 KIS 캔들스틱 패턴을 인식하여 결과 + 방향성(BULL/BEAR) + 시간 범위를 반환한다. PatternRecognizer는 domain port + consumer JvmPatternRecognizer 구현체 + 57 @Component PatternDetector 자동 수집(Map registry) 구조 — KIS `candlestick.py` 755 lines + 65 PATTERN_DETECTORS dict 1:1 port (9 Lean 제외 = 57 정합 catalog). R1 10 detector는 실제 매칭(catalog §"R1 인식기" 명시 — doji·hammer·hanging_man·inverted_hammer·shooting_star·engulfing·morning_star·evening_star·three_white_soldiers·three_black_crows), 47개는 stub(메서드 노출 + `Direction.NONE` 반환 — R2 사이클 보강). REST `GET /api/patterns?code=...&interval=...&patterns=...&from=...&to=...` 단일 endpoint로 frontend overlay·DSL evaluator 동일 인터페이스. T-DSL의 `PatternRecognizerStub`(@ConditionalOnMissingBean) 1줄 교체로 활성화 — JvmStrategyEvaluator 코드 수정 0건. Redis 캐시는 단순 mode (`pattern:cache:{code}:{interval}:{patternsHash}` TTL 5분 분봉 / 1일 일봉) — T-IND 정합. AI 호출 0(룰 5).**

### 1-2. DoD (Definition of Done) — PR-39 + PR-40 모두 머지 후 검증

| # | 검증 항목 | 명령·시나리오 | 기대 결과 |
|---|---|---|---|
| 1 | JvmPatternRecognizer 부팅 등록 | `docker logs magicjar-consumer-app | grep PatternRecognizer` | "Registered 57 pattern detectors (R1 active=10, stub=47)" log + "patternRecognizer" bean replace stub |
| 2 | DSL stub 자동 비활성화 (DoD 핵심) | `docker logs magicjar-consumer-app | grep PatternRecognizerStub` | 부팅 후 stub bean 등록 0건 — `@ConditionalOnMissingBean` 정합 |
| 3 | REST GET 단일 패턴 인식 | `curl 'http://localhost:8080/api/patterns?code=005930&interval=1d&patterns=hammer&from=2024-01-01&to=2024-12-31'` | 200 OK + `{"matches":[{"pattern":"hammer","startTime":"2024-MM-DDT...","endTime":"2024-MM-DDT...","direction":"BULL","confidence":1.0}]}` |
| 4 | REST GET 다중 패턴 동시 | `curl '...&patterns=hammer,engulfing,doji'` | 200 OK + 3개 detector 동시 인식 결과 합쳐서 반환 |
| 5 | 미지원 패턴 graceful | `curl '...&patterns=unknown_pattern'` | 200 OK + `{"matches":[]}` (KIS L52 정합 — `PATTERN_DETECTORS.get(pattern_id) is None` → 0 반환 = 미감지) |
| 6 | R2 stub graceful (47건) | `curl '...&patterns=tristar'` (R2 stub) | 200 OK + `{"matches":[]}` — stub detector는 NEUTRAL 반환 → 결과 미포함 |
| 7 | 캔들 부족 graceful | `curl '...&patterns=morning_star&from=2024-12-30'` (3 캔들만) | 200 OK + `{"matches":[]}` — KIS L48 `len(df) < 5` 정합 |
| 8 | DSL stub 1줄 교체 검증 (DoD 핵심) | DSL strategy `pattern("hammer")` evaluate (T-DSL 코드 수정 0건) | `EvalResult.signal=BUY` (hammer 인식 시) — stub 시점 항상 HOLD에서 정상 분기 활성화 |
| 9 | DSL `consecutive(up) >= 3` evaluate | T-DSL stub 호출 → 진짜 PatternRecognizer.consecutiveDirection() | 3봉 연속 양봉 시 true 반환 (KIS는 자체 — magicJar 자체 helper로 port) |
| 10 | Redis 캐시 hit | 같은 호출 2회 — 두 번째 SET 0건 | `redis-cli MONITOR` 첫 호출 SET, 두 번째 GET only |
| 11 | 캐시 TTL 일봉 1일 | `redis-cli TTL pattern:cache:005930:1d:abc123...` | ≤ 86400, ≥ 86000 |
| 12 | 캐시 TTL 분봉 5분 | `redis-cli TTL pattern:cache:005930:1m:abc123...` | ≤ 300, ≥ 280 |
| 13 | LLM provider import 0 | `grep -rE "spring-ai|anthropic|openai" {api,consumer,batch,domain}/src/main` | 0건 |
| 14 | 57 detector @Component 등록 (R3 PR-40 검증) | `./gradlew :consumer:test --tests "*PatternRegistryTest"` | `assertEquals(57, detectors.size)` GREEN |
| 15 | R1 10 detector 정확성 (R3 PR-40 일괄) | `./gradlew :consumer:test --tests "*PatternFixtureTest"` | 10개 fixture(KIS raw L81-L502 1:1 정합) GREEN |
| 16 | Repository default fun 함정 부재 (ADR 0051) | `grep -rE "interface.*JpaRepository.*\{" -A 100 api/src consumer/src` review | body 가진 fun 0건 (T-PAT은 신규 Repository 0건이라 무관) |
| 17 | strategy-eval-pool 내부 호출 (ADR 0047 정합) | DSL `pattern("hammer")` evaluate 시 thread name | `strategy-eval-N` thread에서 PatternRecognizer 호출 — timeout 100ms 내 완료 |
| 18 | KIS 65 PATTERN_DETECTORS 매핑 검증 (R3) | raw verification §2 매트릭스 ↔ JvmPatternRecognizer.registry 비교 | 65 entry 중 9 제외 = 57 LOCK 정합 |
| 19 | LWC marker overlay (R3 PR-40) | UI에서 패턴 overlay 토글 | LWC 차트에 markers (위치=startTime, 색상=BULL 녹색·BEAR 빨강) 표시 |
| 20 | Graceful Shutdown (ADR 0045 정합) | `docker stop magicjar-consumer-app` | PatternRecognizer in-flight 호출 ≤ 2000ms 완료 + 신규 호출 reject |

### 1-3. 비즈니스 가치

- **W2 핵심 — T-DSL의 stub 활성화 + T-LWC(W5) 시각화 prerequisite**. 패턴 없이는 사용자가 "RSI < 30 AND hammer 발생" 같은 룰 본체 표현 0.
- **사용자 가치 1**: KIS Strategy Builder에서 검증된 65 detector 그대로 magicJar에서 동작 (자산 보존 100%).
- **사용자 가치 2**: LWC 차트에 패턴 발생 markers — 사용자가 "왜 시그널이 났는가" 시각적 인식.
- **사용자 가치 3**: DSL의 `pattern(...)` 키워드가 진짜 동작 — golden_cross + hammer 같은 복합 룰 표현 가능.
- **자동매매 fitness (사용자 메모리 `feedback_v3_skepticism_no_regression.md` 정합)**: 캔들스틱 패턴은 quant trading 본체의 시각적 신경계 — 룰 본체(W2-W3)의 "왜 매매하는가" 답변. v1/v2 잔재 0건 (PoC v1 시점에는 패턴 인식 0 — 단일 RSI 룰 hardcode. v2 ADR 0020·catalog 57패턴 정합 1차 도입). AI 호출 0 (룰 5).

---

## §2. ADR 0020·0022·0047 준수 항목 체크리스트

본 PR-38(spec) → PR-39(코드)이 ADR 0020 §3-2(KIS Strategy Builder 흡수) + ADR 0022 §2-2(DSL pattern·consecutive 키워드) + ADR 0047 §2-3(strategy-eval-pool 내부 호출)을 그대로 implementation 한다. **신규 ADR 0건** — ADR 본문은 이미 충분(T-IND PR-32·T-DSL PR-35 패턴 정합).

### 2-1. ADR 0020 (KIS Strategy Builder 흡수) — 의무 항목

| ADR 0020 §위치 | 결정 사항 | PR-39 구현 위치 | 담당 |
|---|---|---|---|
| §3-1 57패턴 — 명세·로직 port | `candlestick.py` 65 detector 1:1 Kotlin port (9 Lean 제외) | **PR-39a strategy-engine** — `consumer/.../application/pattern/detector/{single,double,triple}/{XxxDetector}.kt` 57 파일 |
| §3-1 7 helper port | `_body`·`_upper_shadow`·`_lower_shadow`·`_range`·`_is_bullish`·`_is_bearish`·`_avg_body` | **PR-39a strategy-engine** — `consumer/.../application/pattern/util/CandleMath.kt` (object) |
| §3-4 KIS 코드 라이선스·출처 표기 | 각 detector KDoc에 `Source: candlestick.py:line_NN · License: MIT` 명시 | KDoc 헤더 의무 |
| §3-5 AI hook 명시 제거 | T-PAT 코드에서 LLM provider import 0건 (룰 5 정합) | DoD #13 |
| §4-4 검증·정확성 | 57 detector fixture 정확성 — 표준 입력에 대한 expected output 비교 | **PR-40 R3 일괄** (R1 10건은 PR-39a 단위 테스트 의무) |
| §4-5 AI 호출 차단 검증 | T-PAT 코드 grep으로 LLM provider import 0건 검증 | **PR-38·PR-39·PR-40 모두** (DoD #13) |

### 2-2. ADR 0022 (Strategy DSL) — DSL stub 활성화

| ADR 0022 §위치 | 결정 사항 | PR-39 구현 위치 | 담당 |
|---|---|---|---|
| §2-2 magicJar 확장 키워드 | `pattern("name")` · `consecutive("up"|"down")` DSL 호출 | **이미 T-DSL에서 처리** — JvmStrategyEvaluator는 `patternRecognizer` bean 호출만 |
| stub 활성화 | T-DSL의 `PatternRecognizerStub` `@ConditionalOnMissingBean(name = ["patternRecognizer"])` → 진짜 bean 등록 시 자동 비활성화 | **PR-39b backend-core** — `JvmPatternRecognizer` `@Component(name = "patternRecognizer")` + 호환 메서드(`recognize`·`consecutive`) 노출 |

### 2-3. ADR 0047 (DSL Evaluation Sandbox) — strategy-eval-pool 내부 호출

| ADR 0047 §위치 | 결정 사항 | PR-39 구현 위치 | 담당 |
|---|---|---|---|
| §2-3 strategy-eval-pool | 본 thread pool 내부에서 PatternRecognizer 호출 — timeout 100ms 내 완료 의무 | JvmStrategyEvaluator 호출 경로 변경 0건 (T-DSL이 이미 풀 적용 — pool 안에서 stub 호출 → 진짜 호출로 1줄 교체) |
| §2-2-A timeout | 단일 detector ≤ 2ms (57개 동시도 ≤ 100ms) — KIS detector 본문 ≤ 5줄 평균 | DoD #17 |

### 2-4. KIS-first raw fetch 의무 (헌법 §3)

본 PR-38 spec 작성 시 — KIS Strategy Builder `candlestick.py` 직접 raw fetch 의무. 자체 추론 0.

```bash
# raw fetch 명령 (architect 본 spec 작성 시 1회 실행 — `pr-38-kis-pattern-raw-verification.md` 산출 완료)
curl -sL https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/strategy_builder/core/candlestick.py
```

- 65 detector 시그니처 → `pr-38-kis-pattern-raw-verification.md` §2 매트릭스 LOCK
- 7 helper → §3 매트릭스 LOCK
- detect_pattern 진입점 (L48 5캔들 minimum + L57 try/except) → §4 LOCK
- R1 10건 KIS 줄 → §5 LOCK
- alias delegation (simple_triple·tasuki_gap) → §7 LOCK
- sub-agent PR-39a strategy-engine은 `candlestick.py` raw fetch 후 각 detector KDoc에 `Source: candlestick.py:line_NN` 인용 의무

---

## §3. 모듈 경계 결정 (사용자 결재 필요 항목)

### 3-1. domain — port + 도메인 모델 (Spring 의존 0)

`domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/pattern/`:

```
pattern/
├── PatternMatch.kt           ← data class(pattern, startTime, endTime, direction, confidence, params?)
├── Direction.kt               ← enum (BULL, BEAR, NEUTRAL)
├── DetectorResult.kt          ← data class(direction, confidence, params?) — detector 내부 반환
├── PatternCatalog.kt          ← 57 spec 등록 (id·category·minCandles·defaultDirection) — read-only data
├── PatternException.kt        ← sealed class (UnknownPatternException · InsufficientCandlesException)
└── port/
    ├── PatternRecognizer.kt   ← interface (★ T-DSL stub 호환 메서드 정의 — recognize / detect / consecutive)
    └── PatternDetector.kt     ← interface (★ 57 @Component이 구현)
```

**판단 근거**: master §5-1 domain/pattern/ 트리 정합. ADR 0020 §3-1에서 PatternRecognizer가 domain 위치로 명시. PatternDetector는 1차 stub 시점에는 port가 아니었지만 본 PR에서 추출 — 57 detector를 spring auto-collect하기 위한 마커 interface.

### 3-2. consumer — PatternDetector 구현체 + JvmPatternRecognizer + 캐시

`consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/application/pattern/`:

```
application/pattern/
├── JvmPatternRecognizer.kt          ← PatternRecognizer 구현 (@Component name="patternRecognizer" — stub 자동 비활성화)
├── util/
│   └── CandleMath.kt                ← KIS 7 helper (_body·_upper_shadow·...) — object
├── helper/
│   ├── SimpleTripleHelper.kt        ← KIS L562 _simple_triple port — 7 detector 공유
│   └── ConsecutiveDirectionCalculator.kt ← magicJar 자체 — `consecutive(up)` 호환
├── detector/
│   ├── single/                      ← 14 @Component (DojiDetector·HammerDetector·...)
│   │   ├── DojiDetector.kt          ← R1 active
│   │   ├── HammerDetector.kt        ← R1 active
│   │   ├── HangingManDetector.kt    ← R1 active (직전 5봉 상승 추세 룰 포함)
│   │   ├── InvertedHammerDetector.kt ← R1 active
│   │   ├── ShootingStarDetector.kt  ← R1 active (직전 5봉 상승 추세 룰 포함)
│   │   ├── DragonflyDojiDetector.kt ← R2 stub
│   │   ├── GravestoneDojiDetector.kt ← R2 stub
│   │   ├── LongLeggedDojiDetector.kt ← R2 stub
│   │   ├── MarubozuDetector.kt      ← R2 stub
│   │   ├── ClosingMarubozuDetector.kt ← R2 stub
│   │   ├── SpinningTopDetector.kt   ← R2 stub
│   │   ├── BeltHoldDetector.kt      ← R2 stub
│   │   ├── HighWaveDetector.kt      ← R2 stub
│   │   └── RickshawManDetector.kt   ← R2 stub
│   ├── double/                      ← 16 @Component (EngulfingDetector·HaramiDetector·...)
│   │   ├── EngulfingDetector.kt     ← R1 active
│   │   ├── HaramiDetector.kt        ← R2 stub
│   │   ├── HaramiCrossDetector.kt   ← R2 stub
│   │   └── ...                      ← 13 R2 stub (Piercing·DarkCloudCover·Counterattack·OnNeck·InNeck·Thrusting·SeparatingLines·Kicking·KickingByLength·MatchingLow·GapSideBySideWhite·HomingPigeon·DojiStar)
│   └── triple/                      ← 27 @Component (MorningStarDetector·EveningStarDetector·...)
│       ├── MorningStarDetector.kt   ← R1 active
│       ├── EveningStarDetector.kt   ← R1 active
│       ├── ThreeWhiteSoldiersDetector.kt ← R1 active
│       ├── ThreeBlackCrowsDetector.kt ← R1 active
│       └── ...                      ← 23 R2 stub
└── cache/
    └── RedisPatternCacheAdapter.kt  ← Redis 캐시 read/write (T-IND 정합)
```

**판단 근거**: master §5-2 표 — Pattern은 consumer가 "in-process 실시간 인식" 책임. consumer가 패턴 본체.

### 3-3. api — REST 컨트롤러 + DTO

`api/src/main/kotlin/kr/spdaewoon/magicjar/api/`:

```
controller/
└── PatternController.kt             ← @RestController GET /api/patterns (frontend·DSL 호출)

controller/dto/
└── PatternMatchDto.kt               ← 직렬화 (Instant → ISO String, BigDecimal → String)
```

**중요 (T-IND 패턴 정합)**: api 모듈에 JvmPatternRecognizer bean 별도 등록 (`api/.../config/PatternRecognizerConfig.kt` — consumer와 동일 detectors 등록). 1차 폐기 가능 — JvmPatternRecognizer는 stateless이고 detectors는 Spring auto-collect로 양쪽 모듈 등록 시 자연스럽게 같은 동작.

**대안 검토**:
- (a) api → consumer Kafka 호출 — overhead 큼, 1차 폐기
- (b) api·consumer 양쪽에 JvmPatternRecognizer bean (detectors stateless) — **채택** (T-IND IndicatorEngine 정합)
- (c) 별도 pattern-service 모듈 분리 — 모듈 추가 부담, 1차 폐기

**circular dep 검증 의무**: PR-39a strategy-engine sub-agent가 `./gradlew :api:bootJar` 빌드 검증. 만약 `:api → :consumer` 추가 시 circular 발견되면 architect 호출 → fallback (api 모듈에 detector 별도 위치 — 단일 파일 self-contained).

### 3-4. batch — 변경 0

batch는 PatternRecognizer 호출 없음. T-BT(W3) 진입 시 백테스트 잡에서 호출 가능 (post-T-PAT).

### 3-5. frontend — LWC marker overlay (R3 PR-40)

`frontend/src/components/chart/PatternMarker.tsx` (PR-40 R3 일괄):
- LWC v4 `setMarkers([{time, position: 'aboveBar' | 'belowBar', color, shape, text}])` 호출
- BULL → 녹색 화살표 (위치=belowBar, shape=arrowUp), BEAR → 빨강 화살표 (위치=aboveBar, shape=arrowDown), NEUTRAL은 결과 미포함
- `frontend/src/components/chart/PatternToggleMenu.tsx` — 사용자가 57개 패턴 picker (catalog id 정합)
- TanStack Query `usePatterns(code, interval, patterns, from, to)` hook

### 3-6. sub-agent 영역 분배 (메인=strategy-engine·보조=backend-core)

**사용자 결재 항목**: T-PAT 메인 책임은 strategy-engine OR backend-core?

**architect 권장 = strategy-engine 메인 + backend-core 보조** (T-IND PR-32 + T-DSL PR-35 패턴 정합):
- 근거 1: tracks-roadmap §2 T-PAT row 담당 = `backend-core` (당시 v2 시점 — strategy-engine 분리 전). v3.1.0 sub-agent 세분화 후 strategy-engine이 indicator·DSL·signal·pattern 영역 메인 일관성.
- 근거 2: master §5-2 표 — Indicator·DSL·Signal·Screener·Backtest·**Pattern** 모두 strategy-engine 영역.
- 근거 3: backend-core는 도메인 sealed class·port·인프라(Redis·DTO) 책임 — domain/pattern/* + REST controller + Redis 캐시 어댑터 = backend-core.
- 근거 4: T-IND·T-DSL이 strategy-engine 메인이었으므로 일관성 — 사용자 인지 흐름 자연스러움.

**보조 결정**: backend-core (domain port·PatternController·DTO·RedisPatternCacheAdapter·DSL stub 1줄 교체)

**market-data 미참여**: candle DB read 의존만. market-data 디스패치 0건. (단 사용자 결재 항목 #6에서 catalog 갱신을 market-data가 담당할 수도 있음 — architect 권장 = market-data 미참여 + catalog 갱신은 backend-core가 spec 정합으로 in-place edit)

**ai-engineer 미참여 LOCK**: AI 호출 0 — 룰 5 정합. 본 PR-38은 ai-engineer 디스패치 0건.

---

## §4. PR 분할 매트릭스 (R1+R2 통합 + R3 일괄 — T-IND·T-DSL 패턴 정합)

### 4-1. PR 시리즈 개요

| PR | 담당 | 분량 | 라운드 | 영역 | 우선순위 |
|---|---|---|---|---|---|
| **PR-38** | architect | M | spec only(R0) | docs(`_workspace/specs/` 5 파일 + CHANGELOG 슬롯 사전 발행) | **현재** (본 PR) |
| **PR-39a** | strategy-engine | L (~1500-2000 LOC) | **R1+R2 통합** | consumer pattern/ — JvmPatternRecognizer + 57 detector(R1 10 + R2 47) + 7 helper + SimpleTripleHelper + ConsecutiveDirectionCalculator + 단위 테스트 5종 | **PR-38 머지 후 병렬 1** |
| **PR-39b** | backend-core | M (~600-900 LOC) | **R1+R2 통합** | domain pattern/* (port·DTO·catalog·exception) + api PatternController + DTO + RedisPatternCacheAdapter + DSL stub 1줄 교체 (`@ConditionalOnMissingBean` annotation 제거 — 또는 stub 파일 자체 삭제) | **PR-38 머지 후 병렬 1** |
| **PR-40** | frontend (메인) + strategy-engine + backend-core (보조) | M~L (~1000-1300 LOC) | R3 일괄 | frontend PatternMarker + PatternToggleMenu + 57 fixture e2e + LWC marker e2e + 정확성 fixture 일괄 | PR-39a/b 머지 후 |

**라운드 분할 일탈 사유 (룰 3 정합 사전 보고)**:
- 표준 룰 3은 R1(domain port·시그니처) → R2(어댑터·영속) → R3(UI·테스트). 이론상 PR-39b R1 → PR-39a R2 직렬.
- 본 spec은 **R1+R2 통합** 권장 — 57 detector 일괄이라 R1만 따로 PR 분리하면 빈 인터페이스 + R2까지 시작 못 함. PR-39b가 domain port + REST + 캐시 + DSL stub 교체 동시 발행, PR-39a가 57 detector + helper 동시 발행. 양쪽 인터페이스 동결 시점 = 본 spec PR-38 머지.
- T-IND·T-DSL에서 같은 R1+R2 통합 권장 + 완전 병렬로 성공 — **재현 의무 (사용자 메모리 `feedback_orchestrator_active_parallel.md` 정합)**.

### 4-2. 병렬 가능 매트릭스 (PR-39a ⊕ PR-39b)

| 영역 | PR-39a strategy-engine | PR-39b backend-core | 충돌 위험 |
|---|---|---|---|
| domain/pattern/ | (read-only — interface import) | **새 파일 add-only** (8 파일) | 0 (PR-39a는 PR-39b 머지 후 import) |
| consumer/.../pattern/detector/{single,double,triple}/ | **신규 add-only** (57 파일) | (touch 0) | 0 |
| consumer/.../pattern/util/CandleMath.kt | **신규 add-only** | (touch 0) | 0 |
| consumer/.../pattern/helper/* | **신규 add-only** | (touch 0) | 0 |
| consumer/.../pattern/JvmPatternRecognizer.kt | **신규 add-only** | (touch 0) | 0 |
| consumer/.../pattern/cache/RedisPatternCacheAdapter.kt | (touch 0) | **신규 add-only** | 0 |
| consumer/.../strategy/PatternRecognizerStub.kt | (touch 0) | **수정 또는 삭제 — 1줄 교체 LOCK** | **modify-shared 의심** — §7 충돌 행렬 참조 |
| api/.../controller/PatternController.kt | (touch 0) | **신규 add-only** | 0 |
| api/.../controller/dto/PatternMatchDto.kt | (touch 0) | **신규 add-only** | 0 |
| api/.../config/PatternRecognizerConfig.kt | **신규 add-only — strategy-engine 단독 LOCK (T-IND IndicatorEngineConfig 정합)** | (touch 0) | 0 |
| application.yml (3 모듈) | (touch 0) | (touch 0 — Redis URL 기존 사용) | 0 |
| CHANGELOG.md | **본인 row in-place edit (작성: strategy-engine)** | **본인 row in-place edit (작성: backend-core)** | 0 (ADR 0050 정합) |

**결론**: §7 충돌 행렬에서 PatternRecognizerStub 수정 영역은 **backend-core 단독 LOCK** (DSL stub 교체는 backend-core 책임 — strategy-engine은 stub 파일 touch 0).

**권장 디스패치 모드**: **완전 병렬 (시나리오 A) — T-IND·T-DSL 정합**. orchestrator는 본 spec PR-38 머지 즉시 PR-39a·PR-39b 동시 background 발사.

---

## §5. T-DSL stub 1줄 교체 LOCK (사용자 결재 #5 — 핵심 결정)

T-DSL의 `consumer/.../strategy/PatternRecognizerStub.kt` (60 lines)는 본 PR-39에서 다음 중 1로 처리:

### 5-A. 권장: stub 파일 전체 삭제 + JvmPatternRecognizer가 동일 인터페이스 노출

**근거**: stub 파일 잔재는 미래에 사용자가 보면 헷갈림 + ConditionalOnMissingBean annotation 의존성 제거.

**작업** (PR-39b backend-core 단독 LOCK):
1. `consumer/.../strategy/PatternRecognizerStub.kt` **삭제**
2. `consumer/.../pattern/JvmPatternRecognizer.kt` (PR-39a 신규 — domain PatternRecognizer port 구현) — bean name = "patternRecognizer" + 동일 메서드 시그니처(`recognize(name, candles)`·`consecutive(direction, candles)`) 추가 의무
3. `consumer/.../strategy/JvmStrategyEvaluator.kt` 코드 변경 0건 — DI 시 `@Qualifier` 또는 bean name 매칭 정합

### 5-B. 대안: stub 파일 유지 + ConditionalOnMissingBean 그대로

**근거**: T-DSL 시점 stub의 의도(graceful degradation) 보존 — 만약 PatternRecognizer bean 등록 실패 시 자동 fallback.

**작업**: stub 코드 변경 0건. JvmPatternRecognizer만 신규 등록 → ConditionalOnMissingBean이 자동 비활성화.

**architect 결정 = 5-A 채택** (사용자 메모리 `feedback_v3_skepticism_no_regression.md` 정합 — 잔재 0건):
- v3.1.0 시점 v1/v2 잔재 청소 강조 — stub은 1차 우회 코드, 본격 진입 시 청소 의무
- ConditionalOnMissingBean 자동 비활성화 매커니즘은 동작하지만 사용자가 "왜 stub 파일이 있나" 의심할 가능성
- 단 PR-39b 머지 시점에 JvmPatternRecognizer가 모든 stub 메서드 동치 구현 의무 (recognize + consecutive). DSL JvmStrategyEvaluator code path 보존 100%

### 5-C. 양쪽 sub-agent 분담 LOCK

| 항목 | 담당 |
|---|---|
| `PatternRecognizerStub.kt` 삭제 | **PR-39b backend-core 단독 LOCK** |
| `JvmStrategyEvaluator.kt` 변경 | **0건** (PR-39a·PR-39b 모두 touch 0 — domain port 의존만 변경 안 함) |
| `DslIdentifierMapper.kt` 변경 | **0건** (T-DSL 매핑 표 그대로 — `consecutive(up)` 호출이 PatternRecognizer.consecutiveDirection() 라우팅) |
| `JvmPatternRecognizer` bean name | "patternRecognizer" — `@Component(name = "patternRecognizer")` 또는 `@Bean(name = "patternRecognizer")` (PR-39a strategy-engine 의무) |

---

## §6. 데이터 흐름 + 인터페이스 동결

### 6-1. 호출 시퀀스 (사용자 차트 패턴 overlay 토글 → 응답)

```
[frontend] PatternToggleMenu "hammer" 토글
   │
   ▼
[frontend] usePatterns('005930', '1d', ['hammer'], fromDate, toDate)
   │ HTTP GET /api/patterns?code=005930&interval=1d&patterns=hammer&from=...&to=...
   ▼
[api] PatternController.get(...)
   │
   ▼
[api/consumer] JvmPatternRecognizer.recognize(candles, patterns=Set("hammer"))
   │ 1. RedisPatternCacheAdapter.get(cacheKey) — hit 시 즉시 반환
   │ 2. miss 시 candles.size < 5 가드 → 빈 리스트
   │ 3. registry["hammer"] = HammerDetector → detect(candles)
   │ 4. RedisPatternCacheAdapter.put(cacheKey, result, ttl=86400s)
   ▼
[domain] List<PatternMatch> 반환
   │
   ▼
[frontend] LWC chart.setMarkers([{time, position: 'belowBar', color: 'green', shape: 'arrowUp', text: 'hammer'}])
```

### 6-2. DSL evaluator 호출 시퀀스 (T-DSL 정합 — PR-38에서 stub 활성화)

```
[consumer] SignalGenerator (T-SIG 합류 시) tick or candle event
   │
   ▼
[consumer] strategyEvalPool.submit { JvmStrategyEvaluator.evaluate(strategy, candles, ctx) }
   │ - timeout 100ms (Future.get)
   ▼
[consumer] AST visitor walk
   │ - DSL `pattern("hammer")` → PatternRecognizer.recognize("hammer", candles) (Boolean)
   │ - DSL `consecutive("up") >= 5` → PatternRecognizer.consecutiveDirection("up", candles) (Int)
   ▼
[domain] StrategyEvalResult(signal, strength, reason, evalTimeMs)
```

**T-DSL 호환 메서드 시그니처 LOCK** (PR-39a strategy-engine 의무 — stub 동치):

```kotlin
// domain/.../pattern/port/PatternRecognizer.kt — sub-agent PR-39b 신규
interface PatternRecognizer {
    /**
     * 패턴 검출 — REST·DSL evaluator 공용.
     * @return matches — direction=NEUTRAL 또는 미감지는 미포함 (KIS L52 정합)
     */
    fun recognize(candles: List<Candle>, patterns: Set<String>): List<PatternMatch>

    /**
     * DSL `pattern("name")` 호환 — boolean 응답 (PR-36 stub 시그니처 정합).
     * @return true if pattern 인식 (BULL 또는 BEAR), false otherwise
     */
    fun recognize(name: String, candles: List<Candle>): Boolean

    /**
     * DSL `consecutive("up"|"down")` 호환 — Int 응답 (PR-36 stub 시그니처 정합).
     * @return 마지막 캔들부터 직전으로 같은 방향 연속 횟수
     */
    fun consecutive(direction: String, candles: List<Candle>): Int
}
```

### 6-3. domain port 시그니처 LOCK (PR-39b 책임)

```kotlin
// domain/.../pattern/port/PatternDetector.kt — 57 @Component이 구현
interface PatternDetector {
    val id: String                              // catalog id 정합 (e.g., "doji"·"hammer"·"engulfing"·"morning_star")
    val minCandles: Int get() = 5               // KIS L48 minimum
    fun detect(candles: List<Candle>): DetectorResult
}

// domain/.../pattern/DetectorResult.kt
data class DetectorResult(
    val direction: Direction,
    val confidence: BigDecimal = BigDecimal.ONE,
    val params: Map<String, BigDecimal>? = null,
)

// domain/.../pattern/PatternMatch.kt — REST·DSL 응답 공용
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

// domain/.../pattern/PatternCatalog.kt
object PatternCatalog {
    data class Spec(val id: String, val category: String, val minCandles: Int = 5, val defaultDirection: Direction)
    val ALL: List<Spec> = listOf(
        // Single 14
        Spec("doji", "INDECISION", 5, Direction.NEUTRAL),
        // ... pattern-catalog.md 57개 LOCK
    )
    init { check(ALL.size == 57) }
    fun byId(id: String): Spec? = ALL.find { it.id == id }
}

// domain/.../pattern/PatternException.kt
sealed class PatternException(message: String) : RuntimeException(message) {
    class UnknownPatternException(id: String) : PatternException("Unknown pattern '$id' (not in catalog 57)")
    class InsufficientCandlesException(required: Int, actual: Int) :
        PatternException("Insufficient candles: required=$required, actual=$actual")
}
```

### 6-4. REST endpoint 시그니처 LOCK (PR-39b 책임)

```kotlin
// api/.../controller/PatternController.kt
@RestController
@RequestMapping("/api/patterns")
@Tag(name = "Patterns", description = "캔들스틱 패턴 57종 인식 (KIS Strategy Builder candlestick.py 정합)")
class PatternController(
    private val patternRecognizer: PatternRecognizer,
    private val candleQueryService: CandleQueryService,
    private val cacheAdapter: RedisPatternCacheAdapter,
) {
    @GetMapping
    @Operation(summary = "패턴 인식 시계열", description = "57패턴 중 다중 패턴을 candle 시계열 위에서 인식하여 매칭 결과를 반환합니다. 결과는 Redis 5분(분봉)/1일(일봉) 캐시. 미지원 패턴은 결과 미포함(graceful, KIS 정합).")
    fun getPatterns(
        @RequestParam @Parameter(description = "종목코드(6자리)", example = "005930") code: String,
        @RequestParam @Parameter(description = "캔들 interval", example = "1d") interval: String,
        @RequestParam @Parameter(description = "패턴 ID 콤마 구분 (예: hammer,engulfing,doji)", example = "hammer,engulfing") patterns: String,
        @RequestParam(required = false) from: String?,  // ISO Instant
        @RequestParam(required = false) to: String?,    // ISO Instant
    ): PatternResponseDto = ...
}

data class PatternResponseDto(
    val matches: List<PatternMatchDto>,
) {
    data class PatternMatchDto(
        val pattern: String,
        val startTime: String,
        val endTime: String,
        val direction: String,
        val confidence: String,
        val params: Map<String, String>? = null,
    )
}
```

### 6-5. Redis 캐시 키 LOCK

| 키 패턴 | TTL | 작성자 | 형식 |
|---|---|---|---|
| `pattern:cache:{code}:{interval}:{patternsHash}` | 분봉 300s / 일봉 86400s | PR-39b `RedisPatternCacheAdapter` | JSON `PatternResponseDto` |

**TTL 매트릭스** (T-IND 정합):
- `1m·5m·15m·30m·1h` → 300s (5분)
- `1d·1wk·1mo·1y` → 86400s (1일)
- `90m` → 300s (분봉 분류)

**patternsHash 계산 정책** (T-IND `paramsHash` 정합):
```kotlin
fun patternsHash(patterns: Set<String>, from: String?, to: String?): String {
    val sorted = patterns.sorted().joinToString(",")
    val key = "$sorted|$from|$to"
    return MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(16)
}
```

**evict 시점**: candle 영속 시 자동 invalidate 안 함 (TTL 만료 자연 갱신). 사용자 명시 무효화 필요 시 post-MVP.

### 6-6. Kafka 영향

본 PR-38·PR-39은 Kafka 영향 0건. T-PAT은 in-process 동기 호출. (post-MVP `pattern.detected.v1` topic은 옵션 — 본 PR 비범위)

### 6-7. SoT 변경 시 처리

본 spec(`pr-38-w2-t-pat-design.md` + `pr-38-dispatch-spec.md` + `pr-38-conflict-matrix.md` + `pr-38-kis-pattern-raw-verification.md`)이 머지된 후 양쪽 sub-agent 진행 중 시그니처 변경 필요 시:
1. sub-agent 즉시 중단
2. orchestrator 호출 → architect 디스패치
3. architect가 spec 갱신 → 양쪽 sub-agent 재개

본 PR-38 작업 중엔 spec 변경 0건 의무.

---

## §7. 충돌 행렬 (modify-shared 사전 점검)

별도 `pr-38-conflict-matrix.md` 참조 (요약):

| 파일 | PR-39a strategy-engine | PR-39b backend-core | 결론 |
|---|---|---|---|
| `domain/.../pattern/*.kt` | (read-only) | **신규 add-only** (8 파일) | 0 |
| `consumer/.../pattern/detector/*` | **신규 add-only** (57 파일) | (touch 0) | 0 |
| `consumer/.../pattern/util/CandleMath.kt` | **신규 add-only** | (touch 0) | 0 |
| `consumer/.../pattern/helper/*` | **신규 add-only** (2 파일) | (touch 0) | 0 |
| `consumer/.../pattern/JvmPatternRecognizer.kt` | **신규 add-only** | (touch 0) | 0 |
| `consumer/.../pattern/cache/RedisPatternCacheAdapter.kt` | (touch 0) | **신규 add-only** | 0 |
| `consumer/.../strategy/PatternRecognizerStub.kt` | (touch 0) | **삭제 — backend-core 단독 LOCK** | 0 — backend-core 단독 |
| `api/.../controller/PatternController.kt` | (touch 0) | **신규 add-only** | 0 |
| `api/.../controller/dto/PatternMatchDto.kt` | (touch 0) | **신규 add-only** | 0 |
| `api/.../config/PatternRecognizerConfig.kt` | **신규 add-only — strategy-engine 단독 LOCK** | (touch 0) | 0 |
| `application.yml` (3 모듈) | (touch 0) | (touch 0) | 0 |
| `CHANGELOG.md` | **본인 row in-place edit (작성: strategy-engine)** | **본인 row in-place edit (작성: backend-core)** | 0 (ADR 0050 정합) |

**결론**: **시나리오 A 적극 병렬 권장** — modify-shared 1건(PatternRecognizerStub.kt — backend-core 단독 LOCK), add-only 100%. orchestrator는 PR-38 머지 즉시 PR-39a·PR-39b 동시 background 발사.

---

## §8. 함정·운영 가드 (sub-agent 의무 인지)

### 8-1. ADR 0051 — Repository default fun 함정

본 PR-38에서는 **신규 JPA Repository 0건** — Redis 어댑터는 `RedisPatternCacheAdapter` 단일 클래스(StringRedisTemplate 직접 사용). JPA Repository 0건이라 ADR 0051 함정 발동 0건.

### 8-2. KIS-first raw fetch (헌법 §3)

PR-39a strategy-engine sub-agent는 57 detector 구현 전 다음 raw fetch 의무:

```bash
curl -sL https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/strategy_builder/core/candlestick.py > /tmp/kis-candlestick.py
wc -l /tmp/kis-candlestick.py    # 약 755 라인
```

각 57개 detector 구현 시:
- 함수명 → Kotlin 클래스명 (`_doji` → `DojiDetector.detect`)
- KIS detector 본문 1:1 정합 (R1 10건은 매칭 + R2 47건은 stub but 함수 시그니처 출처 의무)
- alias delegation (simple_triple·tasuki_gap) → `pr-38-kis-pattern-raw-verification.md` §7 정합
- 자체 추론 0 — 애매한 경우 raw 출처 줄번호 인용 KDoc

### 8-3. BigDecimal 정밀도

T-IND ADR 0021 §2-1 정합 — `DetectorResult.confidence: BigDecimal`. KIS는 Python float (IEEE 754 double) — Kotlin port 시 `BigDecimal` 사용 권장. 단 detector 내부 비율 계산(`_body / _range`)은 BigDecimal divide 시 scale=8 + RoundingMode.HALF_EVEN 의무 (Indicator와 동일 정밀도).

### 8-4. 캔들 부족 처리 (KIS L48 정합)

`detect_pattern` 진입점에 `len(df) < 5: return 0` — magicJar는 JvmPatternRecognizer.recognize() 진입에서 `if (candles.size < 5) return emptyList()` 의무. detector 별 minCandles 변경 가능 (e.g., breakaway 5캔들·three_line_strike 4캔들 등은 `override val minCandles = 5` 또는 `4`).

### 8-5. KIS try/except 정합 (KIS L57)

KIS는 `detect_pattern`이 detector 호출 시 `try: detector(df) except: return 0` — magicJar는 JvmPatternRecognizer.recognize() 안의 `try-catch (Exception) { null }` 정합 (개별 detector 예외 swallow + 미감지). log.debug만 (사용자 화면 응답 끊지 않음).

### 8-6. R1 10건 vs R2 47건 stub 처리 LOCK

R2 stub detector는 함수 본체에 다음 default:

```kotlin
@Component
class TristarDetector : PatternDetector {
    override val id = "tristar"
    override fun detect(candles: List<Candle>): DetectorResult {
        // R2 사이클 보강 — KIS L636 _tristar 본문 port 후 활성화
        return DetectorResult(direction = Direction.NEUTRAL)
    }
}
```

KDoc에 `Source: candlestick.py:L636 (R2 stub — 활성 시 KIS 본문 port)` 명시 의무. 47개 stub은 `Direction.NEUTRAL` 반환 → 결과에 미포함 (REST `matches=[]`).

### 8-7. consecutive(up|down) 호환 메서드 — KIS 미존재

KIS `candlestick.py`는 `consecutive(direction)` 함수 부재. T-DSL stub의 `consecutive(direction, candles)` 메서드는 magicJar 자체 룰:

```kotlin
// consumer/.../pattern/helper/ConsecutiveDirectionCalculator.kt
fun consecutive(direction: String, candles: List<Candle>): Int {
    if (candles.isEmpty()) return 0
    val isBullish: (Candle) -> Boolean = when (direction) {
        "up" -> { c -> c.close > c.open }
        "down" -> { c -> c.close < c.open }
        else -> return 0
    }
    var count = 0
    for (i in candles.indices.reversed()) {
        if (isBullish(candles[i])) count++ else break
    }
    return count
}
```

JvmPatternRecognizer가 이 helper 호출. KIS 출처 없음 — magicJar 자체 룰이라 KDoc에 `magicJar own — KIS 미존재` 명시.

### 8-8. 빌드 영향 + dependency

- domain 모듈 — JPA·Spring 의존 0 유지 (kotlinx-datetime, java.math.BigDecimal만)
- consumer — 기존 Redis dep 재사용. 신규 dep 0건
- api — 기존 Spring Web + OpenAPI 재사용. 신규 dep 0건
- 단 api 모듈에서 PatternRecognizer bean 등록 시 consumer 모듈 스캔 또는 별도 config 필요 — circular dep 검증 의무 (PR-39a strategy-engine sub-agent 책임 — `./gradlew :api:bootJar` 빌드 검증)

### 8-9. KIS Rate Limit 영향 0

T-PAT은 candle DB read 의존 — KIS REST 호출 0건. KIS Rate Limit 영향 없음.

---

## §9. fitness gap 검증 (사용자 메모리 `feedback_v3_skepticism_no_regression.md` 정합)

> **lens**: "자동매매에 필요한가? v1/v2 잔재 아닌가?"

| 항목 | 판정 | 근거 |
|---|---|---|
| **57패턴 일괄 vs 10개 우선** | **57 일괄 정합** | catalog §"57패턴 LOCK" 정합 + ADR 0020 §3-1 KIS 자산 흡수 결정 + R1 10 active + R2 47 stub 분리는 룰 4(R3 일괄) 정합. 단순 10개 만들고 47개 빈손이면 v1 회귀 (v1 시점 patterns 0). |
| **R1 10 + R2 47 stub 1차** | **정합** | catalog §"R1 인식기 구현 범위" 명시 — 10건이 차트 빈도·실효성 최상위. 나머지 47은 메서드 노출만 — DSL `pattern("tristar")` 호출 graceful (NEUTRAL) — 사용자 인지 흐름 자연스러움. |
| **PatternRecognizer port** | **정합** | T-DSL stub 1줄 교체 의도 정합. ConditionalOnMissingBean → 진짜 bean 자동 활성화 — magicJar 헥사고날 정합. |
| **DSL `pattern(...)`·`consecutive(...)` 활성화** | **정합** | T-DSL stub은 사용자 명시 — "T-PAT 머지 시 1줄 교체". 본 PR이 그 1줄 교체 본체. |
| **Redis 캐시** | **정합** | T-IND 정합 + 사용자 차트 overlay 5분 캐시는 응답성에 직결. quant trading 본체에서 동일 candle 위 동일 패턴 재계산은 자원 낭비. |
| **REST 단일 endpoint** | **정합** | T-DSL·차트·screener·signal 모두 동일 endpoint 사용. v1 시점 patterns 0이라 회귀 risk 없음 — 신규 도입. |
| **AI 호출 0** | **정합** | 룰 5 + ADR 0023 정합. T-PAT은 W2 — LLM provider import 0건. |
| **57 detector 정확성 R3 일괄** | **정합** | 룰 4 정합. R3 PR-40에서 57 fixture + LWC e2e 일괄. PR-39은 R1 10건만 단위 테스트 (정확성 검증 동력 보장). |
| **stub 파일 삭제 (5-A)** | **정합** | v1/v2 잔재 청소 의무. JvmPatternRecognizer가 stub 메서드 동치 구현 후 stub 파일 삭제 — DSL evaluator 코드 변경 0건 보장. |

**결론**: T-PAT spec은 자동매매 본체 quant trading의 시각적 신경계 — v1/v2 잔재 0건. 57패턴은 룰 본체(W2-W3) 진정한 first-class citizen. ADR 0020·0022·0047·0050 결정 정합 100%.

**post-MVP 후속 (1차 비범위)**:
- R2 47 stub → active port (post-T-PAT — 사용자 dogfooding 빈도 따라 우선순위)
- 신뢰도 모델 (ML-based confidence — KIS 0/1 binary 대비 0.0~1.0 그라데이션) — post-MVP
- 사용자 정의 패턴 (Spring auto-collect 확장) — post-MVP
- pattern.detected.v1 Kafka publish (시그널 발생 시 고지) — post-MVP T-NOTIFY 합류

---

## §10. 사용자 결재 받을 결정 사항 (PR-38 본문에 명시)

본 spec PR 머지 전 사용자가 결재할 항목:

| # | 항목 | architect 권장 | 영향 |
|---|---|---|---|
| 1 | **메인 sub-agent 결정** | strategy-engine 메인 + backend-core 보조 (§3-6 정합) | PR-39 디스패치 분배 |
| 2 | **R1 10 active + R2 47 stub OK?** | 채택 (catalog §"R1 인식기" 정합 — R1 doji·hammer·hanging_man·inverted_hammer·shooting_star·engulfing·morning_star·evening_star·three_white_soldiers·three_black_crows) | PR-39a 분량 |
| 3 | **R1+R2 통합 권장 OK?** | 통합 (T-IND·T-DSL 패턴 정합 + 57 일괄이라 분리 비효율 + add-only 충돌 100%) | PR 개수 |
| 4 | **57 detector 정확성 검증 시점** | R3 PR-40 일괄 (R1 10건은 PR-39a 단위 테스트 의무) | 룰 4 정합 |
| 5 | **PatternRecognizerStub 처리** | **5-A 삭제 + JvmPatternRecognizer 동치 구현** (§5 정합 — v1/v2 잔재 0건) | backend-core 단독 LOCK |
| 6 | **신규 ADR 0건 OK?** | OK — ADR 0020 §3-1 + ADR 0022 §2-2 + ADR 0047 §2-3 implementation 명세로 충분. 신규 ADR 발행 시 fitness gap 위반 risk (T-IND·T-DSL 패턴 정합) | ADR 인덱스 |
| 7 | **frontend overlay 1차 범위** | **markers (LWC v4 setMarkers)** 채택 (catalog 57 id × 위치/방향만 — 박스 영역 시각화는 T-LWC W5 본격 후속) | PR-40 R3 분량 |
| 8 | **catalog 갱신은 누가?** | **backend-core가 spec 정합으로 in-place edit** (`_workspace/strategy/pattern-catalog.md` §"R2 사이클 보강 대상" 47건 명시 — 본 PR-39 시점 stub 등록 후 R2 작업) — market-data 디스패치 0건 권장 | docs 책임 |
| 9 | **KIS-first raw 패턴 출처 — KIS open-trading-api OK?** | 채택 — `candlestick.py` 755 lines + 65 detector 1:1 port (raw verification §2 매트릭스 LOCK) | sub-agent KIS-first 의무 |
| 10 | **Kafka publish (`pattern.detected.v1`) 1차 폐기 OK?** | 채택 — T-NOTIFY W3 합류 후 본격 (사용자 알림 통합 시점). 본 PR-39은 in-process 호출만 | post-MVP |

**사용자 결재 후**: orchestrator가 본 spec PR-38 머지 → PR-39a·PR-39b 동시 background 발사.

---

## §11. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-10 | PR-38 T-PAT 설계 가이드 v1 발행. ADR 0020/0022/0047/0050 implementation 명세 풀어냄 — 신규 ADR 0건. domain/pattern/* port 8개 + consumer/.../pattern/detector/{single,double,triple}/ 57 분할 + util/CandleMath + helper/SimpleTripleHelper·ConsecutiveDirectionCalculator + JvmPatternRecognizer + RedisPatternCacheAdapter + PatternController REST endpoint LOCK. KIS 65 PATTERN_DETECTORS → magicJar 57 매핑 (제외 9건 — Lean 미지원 8 + 통합 중복 1) raw verification §2에 LOCK. R1+R2 통합 권장(57 일괄 비효율 회피) + R3 PR-40 일괄. 충돌 행렬 시나리오 A 적극 병렬 권장 (modify-shared 1건 — backend-core 단독 LOCK). T-DSL stub 1줄 교체 LOCK §5에 명시 (5-A 채택 — stub 파일 삭제 + JvmPatternRecognizer 동치). fitness gap 검증 — v1/v2 잔재 0건. (architect, opus 4.7 1M) |
