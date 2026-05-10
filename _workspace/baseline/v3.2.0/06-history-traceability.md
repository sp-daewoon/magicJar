# 06 — PR · ADR · 기능 추적성 매트릭스 (v3.1.0 → v3.2.0)

> **작성자**: architect (PR-46)
> **시점**: 2026-05-10 v3.2.0 태그 컷 직후 (main `950420b` + `release/v3.2`)
> **범위**: v3.1.0 (2026-05-08, main `6b83899`) → v3.2.0 (2026-05-10, main `950420b`) 사이 ~38 PR + 4 ADR 신규(0052·0053·0054·0055) + W2 4 트랙(T-IND·T-DSL·T-PAT·T-SB) + JVM 모듈 4→5 + Flyway V8→V11.

본 문서는 **PR·ADR·기능 3축 추적성 매트릭스**다. raw 출처:
- `git log v3.1.0..main --oneline` (worktree 직접 실행)
- `gh pr list --state merged --search "merged:>=2026-05-08"` 결과 인용
- `CHANGELOG.md` 시계열 row
- `_workspace/arch/adr/README.md` 인덱스 (51 → 55)
- `_workspace/backlog.md` § 단위 (§24 종목상세 묶음 · §25 빈 함정 · §26 pattern-engine 동반 신설)

---

## §1. W2 트랙별 PR 매핑 (v3.2.0 핵심)

`docs/architecture/tracks-roadmap.md` §2 트랙 정의 + v3.2.0 release row(CHANGELOG.md row 6) 풀 정합.

| 트랙 ID | ★ 등급 | 명칭 | 주요 PR | 부수 hotfix PR | 결과물 |
|---------|-------|------|---------|----------------|--------|
| **T-IND** | — | Indicator 80지표 + Source 추상화 | #252 (architect spec PR-32) → #253 (PR-33 단일 본격 — strategy-engine + backend-core + market-data + frontend 4 sub-agent 병렬) | — | domain/indicator port 7파일 · KisStrategyBuilderSource 80지표(KIS `indicators.py` 1507 lines 1:1) + 6 카테고리 파일(Trend·Momentum·Volume·Volatility·Oscillator·Other) · NativeIndicatorSource 5종(MA·EMA·RSI·MACD·BB) · IndicatorEngine cache-aside · RedisIndicatorCacheAdapter (`indicator:cache:{code}:{interval}:{name}:{paramsHash}` SHA-256 16자, 분봉 5m/일봉 1d TTL) · IndicatorController REST · IndicatorEngineConfig (api·consumer 양쪽) · frontend INDICATOR_CATALOG · IndicatorPanel 토글 · IndicatorOverlay (LWC v4 multi-output 분기) · _workspace/strategy/cross-check/kis-indicator-fixture.json (drift 0.001%) · 정확성 fixture 32건. ADR 0021 §2-1~§2-5 + ADR 0020 §3-1·§3-4 정합. |
| **T-DSL** | — | Strategy DSL 텍스트 파서 + 평가 엔진 + 자연어 helper(W4 이월 → 본 사이클 사용자 결재로 발행) | #254 (architect spec PR-35) → #255 (PR-36 단일 — strategy-engine + backend-core + ai-engineer 3 sub-agent) | — | domain/strategy 23+ sealed class · enum · data class (KIS `parser.py` 664 lines 1:1 port + 3 rename — IndicatorRef·LiteralValue·DslStrategyDefinition) · KisYamlDslParser 21 token 정규식 + 6단 재귀 하강(_parse_or→and→condition→arithmetic→term→factor) · AST 빌더 · static 검증 visitor · complexity 4단(LOW/MEDIUM/HIGH/CRITICAL) · JvmStrategyEvaluator(IndicatorEngine 단일 진입점 + sandbox timeout 100ms · CPU budget 50ms · strategy-eval-pool 4 fixed + Future.cancel + auto-disable) · DslIdentifierMapper 25+ · PatternRecognizerStub(@ConditionalOnMissingBean) · StrategyEvalPoolConfig · StrategyEvalMetricRecorder ring buffer N=100 · NaturalLanguageDslTranslator (Spring AI Anthropic + 1회 재시도 + 안전 화이트리스트 차단 + sanitize + UNTRANSLATABLE 인식) · DslTranslateController POST `/api/dsl/translate-from-natural` · MockDslHeuristic 9 패턴 fallback · `prompts/dsl-translate.md` few-shot 10건 · StrategyController 7 endpoint · StrategyService · StrategyJpaEntity·Repository(body fun 0 — ADR 0051) · Flyway V9·V10·V11 · `.env.example` 10 변수 · 단위 테스트 6건 + StrategyControllerTest 8건 + AI 22+ 케이스. ADR 0022·0047·0023·0026 정합. |
| **T-PAT** | — | 캔들스틱 패턴 57종 인식 + DSL pattern_detected 토큰 | #256 (architect spec PR-38) → #258 (PR-39 단일 — strategy-engine 메인 + backend-core 보조) → #268 (PR-40 R3 frontend) | #261 v3.1.2 hotfix 5건 (Hibernate6 NOW + 빈 함정 + healthcheck) — 본 hotfix가 ADR 0054 pattern-engine 모듈 분리 트리거 | domain/pattern 5 도메인(Direction·DetectorResult·PatternMatch·PatternException·PatternCatalog) + 7 port(PatternDetector·PatternRecognizer 등) + R1_ACTIVE_IDS Set · JvmPatternRecognizer(`@Component("patternRecognizer")` Map registry + `init { require(detectors.size == 57) }` + 5캔들 minimum + try/except graceful + recognize 2 overload + consecutive helper) · 57 detector(single 14 + pair 16 + triple 27 — KIS `candlestick.py` 755 lines 1:1, R1 active 10 + R2 stub 47, KDoc `Source: candlestick.py:Lxx` 인용 60파일 + magicJar own 1) · util/CandleMath KIS 7 helper · helper/SimpleTripleHelper(L562 `_simple_triple` 1:1) · helper/ConsecutiveDirectionCalculator(magicJar own — DSL `consecutive("up"/"down")` 호환) · RedisPatternCacheAdapter(분봉 5분/일봉 1일 TTL, patternsHash SHA-256 16자) · PatternController REST + PatternResponseDto · KisYamlDslParser 화이트리스트 갱신(`pattern_detected("name")` 토큰 추가 — magicJar alias `pattern("name")` 동치) · JvmStrategyEvaluator evalPattern when 분기 합류 · PatternRecognizerStub **삭제**(5-A LOCK — v1/v2 잔재 0) · frontend api/patterns.ts PATTERN_CATALOG 57 + R1_ACTIVE_IDS · usePatterns hook · PatternMarker(LWC v4 setMarkers BULL belowBar·#26a69a·arrowUp·"▲" / BEAR aboveBar·#ef5350·arrowDown·"▼" / NEUTRAL 제외) · PatternToggleMenu(57 catalog 3 섹션 + R1 active 체크 + R2 stub dim "(준비 중)" + localStorage + 전체선택/해제) · LightweightChart `onCandleSeriesReady` prop · SymbolChart 통합(0건 안내 "선택한 패턴이 해당 기간에서 발견되지 않았습니다") · 단위 테스트 5종 51건 + frontend 40건. ADR 0020 §3-1 + ADR 0022 §2-2 + ADR 0047 §2-3 정합. |
| **pattern-engine 모듈 신설 (B-1 사용자 결정 — v3.1.2 hotfix 후속 모듈 경계 정합)** | — | 라이브러리 jar 모듈 분리 (4→5 모듈) | #263 (architect spec PR-41 + ADR-0004 갱신 + ADR-0054 신규) → #265 (PR-43 backend-core 빌드 그래프 + 게이트 제거 ~80 LOC) + #266 (PR-42 strategy-engine 62 main + 4 test git mv + 패키지 리네이밍) (동시 머지) | #267 chore(ktlint): post PR-42·PR-43 자율 정정 — import-ordering 13 + no-consecutive-comments 1 | settings.gradle.kts `pattern-engine` include · pattern-engine/build.gradle.kts(kotlin("jvm")+spring plugin, Spring Boot plugin 미적용, `implementation(":domain")` + spring-context + spring-data-redis + jackson-module-kotlin + slf4j-api, bootJar 비활성) · api/build.gradle.kts + consumer/build.gradle.kts `implementation(":pattern-engine")` 추가 · api/.../adapter/pattern/RedisPatternCacheAdapter.kt **삭제**(unify) · PatternController `@ConditionalOnProperty` 제거 + import 제거 + KDoc B-1 완료 표기 · PatternControllerTest `@TestPropertySource` 제거 · pattern-engine/src/main/kotlin/kr/spdaewoon/magicjar/pattern/{JvmPatternRecognizer + cache/RedisPatternCacheAdapter + util/CandleMath + helper/{SimpleTripleHelper, ConsecutiveDirectionCalculator} + detector/single/{14} + detector/triple/{27} + detector/pair/{16}} 62 main 파일 git mv(히스토리 보존) · pattern-engine/src/test 4 test 파일 git mv (5번째 PatternDslTokenTest는 KisYamlDslParser 의존 때문에 consumer 잔류) · 66파일 패키지 선언 + import prefix 일괄 리네이밍 `kr.spdaewoon.magicjar.consumer.application.pattern.* → kr.spdaewoon.magicjar.pattern.*` (Edit replace_all 66 호출) · 인터페이스 동결 LOCK 보존 (bean name "patternRecognizer" + 57 catalog + KDoc Source 인용 60파일) · 영역 침범 0(strategy-engine은 코드만, backend-core는 build only). ADR 0004 §의존 방향 정합 보존(api·consumer 상호 직접 의존 0). ADR 0054 신규(B-1 채택 사유 + A·B-2·C 거부). |
| **T-SB** | — | Strategy Builder UI (GUI 블록 빌더 1차 + DSL 토글) | #269 (architect spec PR-44 + ADR 0055 신규) → #271 (PR-45 frontend 단일 — 시나리오 C) | — | 신규 27 frontend 파일 ~2900 LOC. lib/dsl/ 5파일(blockTree.ts BlockNode discriminated union 8종 + serializer.ts → DSL 1줄 + parser.ts 재귀 하강 6단 + yaml.ts KIS .kis.yaml 1MB 제한 + catalogIds.ts 80지표+57패턴 ID Set) + 단위 테스트 3파일 21건 · api/strategy.ts 7 endpoint + api/dslTranslate.ts(POST /api/dsl/translate-from-natural — ADR 0023 사용자 트리거만) · hooks/useStrategy.ts 7건 useMutation + useStrategyTranslate.ts(retry=0) · components/strategy/ 8 컴포넌트(BlockPalette 4탭+검색 / BlockNode 재귀 8분기 + 키보드 Tab/Enter/Delete 접근성 / StrategyBuilderCanvas entry/exit 두 탭 + DSL 미리보기 + 액션버튼 / DslTextEditor textarea — Monaco 없음 / NaturalLanguageInput 자연어 모달 4분기 / EvaluationResultCard BUY/SELL/HOLD + 강도 게이지 + 관심종목 추가 ADR 0040 + EvaluationEmptyCard 명시 / ComplexityCard 4단 + AST stats / StrategySaveModal name·nameKo·universe·tf 폼) + 테스트 3파일 24건 · pages/Strategy/StrategyBuilderPage 통합(팔레트+캔버스+결과 3단 + 검증→저장→평가 플로우 + YAML import/export + 자연어 모달 + DSL 직접 편집 모달 + 토스트 + 768px 이하 안내) + 테스트 5건 · App.tsx /strategy/builder 라우트 추가 · AppHeader "전략 빌더" 메뉴 추가 · pnpm typecheck/test 131 passed/build GREEN · 의존 추가 0 LOCK · LLM import 0 · INDICATOR_CATALOG 80건·PATTERN_CATALOG 57건 정합. ADR 0055 신규(B+C-lite 채택 — A Reactflow ~150KB 거부 · C Monaco ~2MB 거부 · D 풀 하이브리드 거부). |

---

## §2. PR 시계열 (#236 ~ #273, 38 PR)

`gh pr list --state merged --search "merged:>=2026-05-08"` raw 결과 + `git log v3.1.0..main --oneline` 정합 (70 commits).

> **PR Prefix 분류**: `docs(specs)` = architect spec/plan · `docs(arch)` = architect ADR · `feat(W2 ...)` = sub-agent 트랙 본격 구현 · `feat(adr)` = architect ADR · `fix(...)`·`hotfix(...)` = hotfix · `chore(meta)` = orchestrator 정리 · `chore(devlog)` = 일지 · `chore(ktlint)` = 자율 정정.

### 2-1. v3.1.0 baseline 직후 + PR-30 cleanup (2026-05-08)

| PR | mergedAt | head ref | 종류 | 요약 |
|----|----------|----------|------|------|
| #236 | 2026-05-08 | `agent/market-data/pr-30-baseline-md` | baseline | docs(baseline): PR-30 market-data — v3.1.0 baseline 03-use-sequences (KIS-first raw 출처 인용) |
| #237 | 2026-05-08 | `agent/frontend/pr-30-baseline-fe` | baseline | docs(baseline): PR-30 frontend — v3.1.0 baseline 05-ui-inventory |
| #238 | 2026-05-08 | `agent/backend-core/pr-30-baseline-backend` | baseline | docs(baseline): PR-30 backend-core — v3.1.0 baseline 02-data-flow + 04-feature-spec |
| #239 | 2026-05-08 | `agent/architect/pr-30-baseline-arch` | baseline | docs(baseline): PR-30 architect — v3.1.0 baseline 4 파일 (README + 01 + 06 + 07) |
| #240 | 2026-05-08 | `chore/pr-30-cleanup` | meta | chore(meta): PR-30 4 sub-agent 머지 후 정리 — task 4건 archive |

### 2-2. baseline 사이클 정형화 + Living Policy (2026-05-08)

| PR | mergedAt | head ref | 종류 | 요약 |
|----|----------|----------|------|------|
| #241 | 2026-05-08 | `chore/baseline-html-render` | meta | chore(script+docs): baseline v3.1.0 Mermaid HTML 변환 |
| #242 | 2026-05-08 | `fix/mermaid-syntax` | hotfix | fix(baseline+script): Mermaid 10 syntax error 일괄 정정 |
| #243 | 2026-05-08 | `chore/baseline-toolkit` | meta · ADR 0052 | feat(toolkit+adr): **baseline 사이클 정형화 풀세트 + ADR 0052** — `scripts/baseline-init.sh` · `baseline-render-html.sh` + slash command `/baseline-init`·`/baseline-render` + agent skill 문서화 |

### 2-3. v3.1.0 dogfooding hotfix 사이클 (2026-05-08)

| PR | mergedAt | head ref | 종류 | 요약 |
|----|----------|----------|------|------|
| #244 | 2026-05-08 | `hotfix/batch-boot-tx-and-roe-log` | hotfix | hotfix(batch+frontend): v3.1.0 dogfooding 부팅 노이즈 3건 + backlog §22(DART) |
| #245 | 2026-05-08 | `hotfix/consumer-noise-and-npe` | hotfix | hotfix(consumer+api+batch): v3.1.0 dogfooding 2차 — NPE·jsonb·로그 노이즈 5건 |
| #246 | 2026-05-08 | `hotfix/kis-credential-firstorinull` | hotfix | hotfix(consumer): KIS approval_key user_id 식별 규약 fallback (REAL/MOCK 불일치) |
| #247 | 2026-05-08 | `hotfix/batch-autoconfig-exclude` | hotfix | hotfix(batch): BatchAutoConfiguration exclude — boot WARN 제거 |

### 2-4. Living Policy + 단일 진입점 (ADR 0053) + 종목상세 결함 정리 (2026-05-08)

| PR | mergedAt | head ref | 종류 | 요약 |
|----|----------|----------|------|------|
| #248 | 2026-05-08 | `feat/living-policy-docs` | meta · ADR 0053 | feat(docs+adr): **Living Policy Docs 도입 + 단일 진입점 인덱스 (ADR 0053)** — `_workspace/INDEX.md` + `docs/architecture/policies/{kis-credential-identity·logging-levels·kis-first-raw-fetch·task-cycle·mermaid-conventions}` 5건 + ADR 0053 본체 |
| #249 | 2026-05-08 | `agent/frontend/symbol-detail-bugfix` | hotfix | fix(frontend): 종목상세 화면 결함 5건 정정 (PR-31a) — 차트 ts→UTCTimestamp · KST 포맷 · hover 툴팁 · 장마감 표시 · IntervalToggle 1wk/1mo/1y 확장 |
| #250 | 2026-05-08 | `agent/backend-core/candle-interval-extend` | hotfix | fix(backend): CandleQueryService supportedIntervals 1wk·1mo·1y 추가 (PR-31b) |
| #251 | 2026-05-08 | `chore/backlog-symbol-detail-followups` | meta | chore(backlog): §24 종목상세 dogfooding 결함 묶음 (v3.2 일괄) |

### 2-5. W2 T-IND 본격 사이클 (2026-05-09)

| PR | mergedAt | head ref | 종류 | 요약 |
|----|----------|----------|------|------|
| #252 | 2026-05-09 | `agent/architect/w2-t-ind-spec` | T-IND spec | docs(specs): PR-32 W2 T-IND 진입 — 80지표 + Source 추상화 spec/plan/dispatch/conflict-matrix 4종 (신규 ADR 0건) |
| #253 | 2026-05-09 | `agent/strategy-engine/w2-t-ind-pr33` | T-IND 본격 | feat(W2 T-IND): 지표 80종 + Source 추상화 + Native 5종 + REST + Redis cache + frontend overlay + 80지표 fixture (PR-33 단일) — 4 sub-agent(strategy-engine·backend-core·market-data·frontend) 병렬 산출물 통합 |

### 2-6. W2 T-DSL 본격 사이클 (2026-05-09 → 2026-05-10)

| PR | mergedAt | head ref | 종류 | 요약 |
|----|----------|----------|------|------|
| #254 | 2026-05-09 | `agent/architect/w2-t-dsl-spec` | T-DSL spec | spec(arch): W2 T-DSL 진입 — Strategy DSL 텍스트 파서 + 평가 엔진 + 자연어 변환 W4 이월 spec/plan + 신규 ADR 0건 (PR-35 spec) |
| #255 | 2026-05-10 | `agent/strategy-engine/w2-t-dsl-pr36` | T-DSL 본격 | feat(W2 T-DSL): KIS DSL 텍스트 파서 + 평가 엔진 + sandbox + domain sealed class + REST + Flyway V9·V10·V11 + 자연어 변환 helper (PR-36 단일) — 3 sub-agent(strategy-engine·backend-core·ai-engineer) 병렬 산출물 통합 |

### 2-7. W2 T-PAT 본격 사이클 (2026-05-10)

| PR | mergedAt | head ref | 종류 | 요약 |
|----|----------|----------|------|------|
| #256 | 2026-05-10 | `agent/architect/w2-t-pat-spec` | T-PAT spec | spec(arch): W2 T-PAT 진입 — 차트 패턴 57종 인식 + PatternRecognizerPort + DSL stub 1줄 교체 spec/plan + 신규 ADR 0건 (PR-38 spec) |
| #257 | 2026-05-10 | `chore/task-archive-batch` | meta | chore(meta): task archive 일괄 정리 (T-IND·T-DSL·T-PAT spec 사이클) |
| #258 | 2026-05-10 | `agent/strategy-engine/w2-t-pat-pr39` | T-PAT 본격 | feat(W2 T-PAT): JvmPatternRecognizer 57패턴 + R1 active 10 + DSL pattern_detected + REST + Redis cache (PR-39 단일) — strategy-engine 메인 + backend-core 보조 통합 |
| #259 | 2026-05-10 | `chore/task-archive-pr39` | meta | chore(meta): task archive 정리 (T-PAT PR-39 사이클) |
| #260 | 2026-05-10 | `chore/devlog-2026-05-10` | meta | chore(devlog): 2026-05-10 — v3.1.1 hotfix → W2 T-IND·T-DSL·T-PAT R1+R2 사이클 정리 |

### 2-8. v3.1.2 hotfix → pattern-engine 모듈 분리 (2026-05-10)

| PR | mergedAt | head ref | 종류 | 요약 |
|----|----------|----------|------|------|
| #261 | 2026-05-10 | `agent/orchestrator/v3.1.2-hotfix` | hotfix | fix: **v3.1.2 hotfix 5건 — 컨테이너 부팅 정상화** — (1) StrategyJpaRepository HQL `NOW()` → `CURRENT_TIMESTAMP` (Hibernate 6 dialect-passthrough) — (2) PatternController `@ConditionalOnProperty(magicjar.pattern.enabled)` 임시 게이트(api 모듈 PatternRecognizer 빈 미발견 — 모듈 경계 결함) — (3·4) `@Component` + `@ConditionalOnMissingBean` 자기 등록 차단 함정 (StrategyEvalMetricRecorder + NoopAutoDisableCallback) — (5) frontend Dockerfile HEALTHCHECK `127.0.0.1` (busybox IPv6 우선 → unhealthy 회피). 9 컨테이너 healthy 검증. spec/plan 0건. backlog §25 + §26 추가. |
| #262 | 2026-05-10 | `chore/task-archive-v3.1.2-hotfix` | meta | chore(meta): task archive — T-20260510 v3.1.2 hotfix |
| #263 | 2026-05-10 | `agent/architect/pr-41-pattern-engine` | architect spec · ADR 0054 | docs(arch): **PR-41 — pattern-engine 라이브러리 모듈 분리 spec/plan + ADR-0004 갱신 + ADR-0054 신규** — B-1 채택(라이브러리 jar 신설 4 → 5 모듈) · A·B-2·C 거부 사유 명시 · 컨테이너 수 불변 LOCK · 인터페이스 동결 bean name "patternRecognizer" LOCK · 시나리오 A 완전 병렬 + 두 PR 동시 머지 LOCK · CHANGELOG 슬롯 3건 |
| #264 | 2026-05-10 | `chore/task-archive-pr-41` | meta | chore(meta): task archive — T-20260510-1749 PR-41 architect |
| #265 | 2026-05-10 | `agent/backend-core/pr-43-pattern-engine-graph` | backend-core | feat(build+api): PR-43 — pattern-engine 빌드 그래프 + api·consumer 의존 + PatternController 게이트 제거 (~80 LOC, 7 파일) — settings.gradle.kts include + pattern-engine/build.gradle.kts 신설 + api/consumer build.gradle.kts implementation 추가 + api 측 RedisPatternCacheAdapter 삭제(unify) + PatternController `@ConditionalOnProperty` 제거 + Test `@TestPropertySource` 제거 |
| #266 | 2026-05-10 | `agent/strategy-engine/pr-42-pattern-engine-move` | strategy-engine | feat(consumer→pattern-engine): PR-42 — 62 main + 4 test 모듈 이동 + 패키지 리네이밍 — consumer.application.pattern → kr.spdaewoon.magicjar.pattern (Edit replace_all 66 호출). 인터페이스 동결 LOCK 보존(bean name + 57 catalog + KDoc Source 인용). 머지 동시성 — 단독 머지 시 main fail(settings include 누락) — orchestrator가 PR-43과 동시 머지 처리 |
| #267 | 2026-05-10 | `chore/orchestrator/post-pr-42-43-ktlint` | meta | chore(ktlint): post PR-42·PR-43 자율 정정 — import-ordering 13 + no-consecutive-comments 1 |

### 2-9. T-PAT R3 frontend 마무리 + W2 마지막 트랙 T-SB (2026-05-10)

| PR | mergedAt | head ref | 종류 | 요약 |
|----|----------|----------|------|------|
| #268 | 2026-05-10 | `agent/frontend/pr-40-w2-t-pat-r3` | T-PAT R3 | feat(frontend): PR-40 W2 T-PAT R3 — 캔들스틱 패턴 차트 마커 + 57 catalog picker + TanStack Query hook + SymbolDetail 통합 — api/patterns.ts + usePatterns + PatternMarker(LWC v4 setMarkers) + PatternToggleMenu 3 섹션 + LightweightChart prop 확장 + SymbolChart 통합 + 단위 테스트 40건 |
| #269 | 2026-05-10 | `agent/architect/pr-44-w2-t-sb` | T-SB spec · ADR 0055 | spec(arch): **W2 T-SB 진입 — Strategy Builder UI (GUI 블록 빌더) + ADR 0055 신규 + CHANGELOG 슬롯 (PR-44 spec)** — B+C-lite 하이브리드 채택(트리 폼 빌더 + textarea DSL 토글) · A Reactflow ~150KB 거부 · C Monaco ~2MB 거부 · 의존 추가 0 LOCK · 시나리오 C 단일 PR-45 채택 |
| #270 | 2026-05-10 | `chore/task-archive-w2-final` | meta | chore(meta): task archive 일괄 — PR-40·42·43·44 W2 마감 사이클 |
| #271 | 2026-05-10 | `agent/frontend/pr-45-w2-t-sb` | T-SB 본격 | feat(frontend): PR-45 W2 T-SB — Strategy Builder UI (B+C-lite 하이브리드, 시나리오 C 단일 PR) — 27 신규 frontend 파일 ~2900 LOC. lib/dsl/ 5파일 + api/strategy.ts 7 endpoint + hooks 2건 + 8 컴포넌트 + StrategyBuilderPage + /strategy/builder 라우트 + AppHeader 메뉴. pnpm 131 passed. 의존 추가 0. LLM import 0. |
| #272 | 2026-05-10 | `chore/task-archive-pr-45` | meta | chore(meta): task archive — T-20260510-2000-pr-45-w2-t-sb |

### 2-10. v3.2.0 baseline init (2026-05-10)

| PR | mergedAt | head ref | 종류 | 요약 |
|----|----------|----------|------|------|
| #273 | 2026-05-10 | `chore/orchestrator/baseline-v3.2.0-init` | meta · baseline | chore(baseline): **v3.2.0 init — 폴더 + CHANGELOG 슬롯 5 사전 발행** (`_workspace/baseline/v3.2.0/` README skeleton + 7 파일 인덱스 + CHANGELOG sub-agent 슬롯 4 anchor architect/backend-core/market-data/frontend) |
| (현재 PR-46 architect) | 진행 중 | `agent/architect/pr-46-baseline-arch` | baseline | docs(baseline): PR-46 architect — v3.2.0 baseline 4 파일 (README + 01-architecture + 06-history + 07-policy) |

→ **v3.2.0 태그** + `release/v3.2` 베이스라인 컷 예정.

---

## §3. ADR 시계열 (51 → 55, +4건)

`_workspace/arch/adr/README.md` §1~§10-4 + 파일 inspection. v3.1.0 시점 ADR 51건 → v3.2.0 시점 ADR 55건 (4건 신규 발행).

### 3-1. v3.1.0 시점 발행 완료 ADR (51건 — v3.1.0 baseline 06-history-traceability.md §3-1·§3-2 정합)

`_workspace/baseline/v3.1.0/06-history-traceability.md §3-1` (47건 v3 베이스) + §3-2 (4건 v3.1.0 사이클 신규 — 0048·0049·0050·0051) 그대로 보존. v3.2.0 사이클에서 본문 갱신 없음 (ADR 0004는 0054 발행 시점에 갱신 — 갱신/지시는 §3-3 추적).

### 3-2. v3.2.0 사이클 신규 발행 ADR (4건)

| ADR | 제목 | 발행 PR | 카테고리 | 사유 |
|-----|------|---------|----------|------|
| **0052** | Baseline Documentation Cycle (skill + slash + 스크립트 4종) | **PR-243 (#243)** — baseline 사이클 정형화 | 메타·문서화 | v3.1.0 baseline 컷 사이클에서 사용자 명시 — "다음 baseline은 같은 절차로 재생". `scripts/baseline-init.sh` + `baseline-render-html.sh` + slash command `/baseline-init`·`/baseline-render` + 9 sub-agent skill 문서화 통합. v3.2.0 본 사이클이 첫 적용 (orchestrator PR-273에서 init 실행). |
| **0053** | Living Policy Docs (현행 권위 단일 위치 + INDEX·policies/ 분리) | **PR-248 (#248)** — Living Policy 도입 | 메타·문서 정합 | 사용자 요청: "한 곳만 보면 되는구나 하고 바로 찾아갈 수 있는 인덱싱". `_workspace/INDEX.md` 단일 진입점(의도별 5축) + `docs/architecture/policies/{kis-credential-identity·logging-levels·kis-first-raw-fetch·task-cycle·mermaid-conventions}` 5건 신설. ADR 본체로 정책 진화 baseline. |
| **0054** | pattern-engine 라이브러리 모듈 분리 (도메인 순수성 보존 + api·consumer 공유) | **PR-263 (#263)** — pattern-engine spec | 모듈 구조 | v3.1.2 hotfix(PR #261) `@ConditionalOnProperty` 임시 게이트가 결함 봉합 — api 컨테이너 부팅 시 PatternRecognizer 빈 미발견 + ADR 0004 §의존 방향(api ↛ consumer) 위반 회피. **ADR 0004 갱신**(4 → 5 모듈, 의존 방향 정합 보존). B-1 채택 + A·B-2·C 거부 사유 명시. |
| **0055** | Strategy Builder UI 컴포넌트 스택 (B+C-lite 하이브리드 — 트리 폼 빌더 + textarea DSL 토글) | **PR-269 (#269)** — T-SB spec | 프론트엔드 | T-SB(W2 마지막 트랙) GUI 블록 빌더 1차 컴포넌트 스택 결정. A. Reactflow ~150KB · C. Monaco ~2MB · D. 풀 하이브리드 거부 사유 명시. 의존 추가 0 LOCK · 모바일 후속 자연 전환 · 키보드 접근성 자연 · AST 1:1 매핑. |

### 3-3. ADR 누적 갱신·정합 PR (본문 직접 갱신)

| PR | 갱신 대상 ADR | 종류 |
|----|--------------|------|
| #263 PR-41 | **ADR 0004 — 4 → 5 모듈 갱신** (의존 방향 정합 보존) + 0054 신규 | 모듈 구조 갱신 |
| #248 PR | ADR README §10-4 추가 — 0053 row | 인덱스 정합 |
| #243 PR | ADR README §10-4 추가 — 0052 row | 인덱스 정합 |
| #269 PR-44 | ADR README §3 (1 → 2건) — 0055 신규 + §11 후속 ADR 0056부터 + §12 시프트 | 인덱스 정합 |

### 3-4. 후속 예정 (Proposed) — `_workspace/arch/adr/README.md` §12

v3.2.0 시점 후보. W3 진입 시 발행 예정 (시프트 — 본 사이클에서 0052·0053·0054·0055 4건 발행으로 기존 §12 후보 4건이 0056~0059로 이동).

| 후보 번호 | 제목 (예정) | 진입 트랙 |
|----------|-----------|----------|
| 0056 | 57 캔들 패턴 표준 (T-PAT 머지 — 본문 ADR로 격상 검토) | T-PAT (W2 본격 머지 완료 — ADR 격상 의제) |
| 0057 | Backtest 표준 (룩어헤드·세금·수수료·슬리피지) | T-BT (W3) |
| 0058 | Screener 표준 | T-SC (W3) |
| 0059 | Performance 표준 | T-PERF (W3) |

---

## §4. Hotfix · Dogfooding 패턴 (v3.1.0 → v3.2.0)

v3.2.0 사이클의 hotfix는 **v3.1.0 dogfooding 잔재(2026-05-08) + W2 진입 후 모듈 경계 함정(2026-05-10) + ktlint 자율 정정** 3 카테고리. 핵심 결함이 **pattern-engine 모듈 분리(ADR 0054) 트리거**가 되었음.

### 4-1. v3.1.0 dogfooding 잔재 hotfix (2026-05-08)

| 결함 | hotfix PR | 원인 | 정정 패턴 |
|------|-----------|------|----------|
| batch 부팅 ROE 로그 노이즈 + 트랜잭션 시작 결함 + DART handshake_failure | PR-244 | KIS sentinel(-36100000) 로그 + tx 미시작 + DART TLS handshake 실패 | sanitize 보강 + tx 정합 + backlog §22 추가(W3 진입 시 정리) |
| consumer NPE + audit_trail jsonb 타입 + 로그 노이즈 5건 | PR-245 | 다양한 코너 결함 누적 | 일괄 정정 |
| KIS approval_key user_id 식별 규약 불일치 (REAL/MOCK) | PR-246 | KIS 자격증명 user_id mode 불일치 | firstOrNull fallback (`feedback`은 W3 T-O 진입 시 본격 정리 — backlog §23) |
| BatchAutoConfiguration boot WARN | PR-247 | Spring Batch auto-config가 batch 모듈만 동작해야 하나 api·consumer에도 등록 시도 | BatchAutoConfiguration exclude (api·consumer) |
| 종목상세 차트·시간포맷·hover툴팁·장마감 표시 5건 + IntervalToggle 1wk/1mo/1y | PR-249·PR-250 | LWC v4 ts ISO→UTCTimestamp 변환 누락 + KST locale 누락 + getPriceBadge 헬퍼 부재 + supportedIntervals 5종만 | UTCTimestamp 변환 + sv-SE locale + marketHours.ts 신설 + 화이트리스트 5→8종 (backlog §24 v3.2 일괄) |

### 4-2. W2 진입 후 모듈 경계 함정 (2026-05-10) → ADR 0054

| 결함 | hotfix PR | 원인 | 정정 패턴 |
|------|-----------|------|----------|
| **api 컨테이너 부팅 fail — `PatternRecognizer` 빈 미발견** | **PR-261 v3.1.2 hotfix** | T-PAT PR-39b에서 `JvmPatternRecognizer`(`@Component("patternRecognizer")`)를 `consumer/.../application/pattern/`에 배치 → consumer 컨테이너에서만 빈 등록. api 측 PatternController(`domain.pattern.port.PatternRecognizer` 의존)가 빈 미발견 → ApplicationContext fail. ADR 0004 §의존 방향(api ↛ consumer) 위반 회피 의무. | **임시 게이트 — `PatternController` `@ConditionalOnProperty(name="magicjar.pattern.enabled", havingValue="true", matchIfMissing=false)` + Test `@TestPropertySource`** (응급 봉합) → **본격 정정 PR-41 spec → PR-42·PR-43 동시 머지** = `pattern-engine` 라이브러리 모듈 신설 (ADR 0054) → 임시 게이트 자연 제거. |
| Hibernate 6 dialect-passthrough — `NOW()` 반환타입 `Object` 추론 → `Instant` 대입 fail | PR-261 | `StrategyJpaRepository.kt` HQL `NOW()` 사용 → `SemanticException: Cannot assign expression of type 'java.lang.Object' to target path 's.lastDisabledAt' of type 'java.time.Instant'` | `CURRENT_TIMESTAMP` 2곳 (HQL 표준 temporal 함수 — 정상 추론) |
| `@Component` + `@ConditionalOnMissingBean` 자기 등록 차단 함정 2건 | PR-261 | `StrategyEvalMetricRecorder` + `NoopAutoDisableCallback` — default 빈 이름 = 자기 자신과 일치 → 자기가 후보로 매칭 → 자기 등록 차단 | 함정 어노테이션 제거 (backlog §25 추가 — `SimpleMarketSessionResolver` 잠재 함정 점검 의제) |
| frontend healthcheck unhealthy (서비스는 정상) | PR-261 | busybox wget IPv6 `[::1]:80` 우선 시도, nginx IPv4만 listen | HEALTHCHECK `127.0.0.1` |

**ADR 0054 baseline 사례** — 위 함정이 ADR 발행 직접 트리거. 사용자(최고 관리자) 인식: "API 모듈은 consumer를 의존하면 안 됨, MSA 정합 위배". 정확한 인식 — ADR 0004 §의존 방향 정합 보존 의무. 4 → 5 모듈로 갱신 + 인터페이스 동결 LOCK + 컨테이너 수 불변(라이브러리 jar) + Hexagonal 정석 정합.

### 4-3. ktlint 자율 정정 (2026-05-10)

| 결함 | hotfix PR | 원인 | 정정 패턴 |
|------|-----------|------|----------|
| post PR-42·PR-43 자율 정정 — import-ordering 13 + no-consecutive-comments 1 | PR-267 | git mv 후 패키지 리네이밍 결과 import 순서 어긋남 + 연속 주석 잔재 | ktlintFormat 자율 호출 (orchestrator 모자) |

---

## §5. Backlog 처리 매트릭스 (v3.1.0 → v3.2.0)

`_workspace/backlog.md` § 단위 + CHANGELOG row 정합. v3.1.0 시점 8건 처리 완료 보존 + v3.2.0 시점 신규 §22~§26 + §24 일괄 정리.

### 5-1. v3.1.0 → v3.2.0 사이 신규 발생 § + 처리 상태

| § | 제목 | 처리 PR | 상태 |
|---|------|---------|------|
| §22 | DART OpenDART CorpCode ZIP — handshake_failure (v3.1.0 dogfooding) | PR-244 인지 | open — W3 T-O 진입 시 정리 의제 |
| §23 | KIS 자격증명 user_id 식별 규약 불일치 — REAL vs MOCK (v3.1.0 dogfooding) | PR-246 응급 fallback | open — W3 T-O 진입 시 본격 정리 |
| §24 | 종목상세 화면 dogfooding 결함 묶음 (v3.2 일괄 정정) | **PR-249·PR-250 + PR-251 close** | ✅ 처리 완료 |
| §25 | `@Component` + `@ConditionalOnMissingBean` 함정 회귀 방지 (v3.1.2 hotfix 잔재) | PR-261 부분 처리 | open — `SimpleMarketSessionResolver` 잠재 함정 점검 의제 |
| §26 | pattern-engine 모듈 신설 사이클 (B-1 결정, v3.1.2 직후) | **PR-263 spec → PR-265·PR-266 동시 머지 + PR-267 ktlint** | ✅ 처리 완료 (ADR 0054 발행) |

### 5-2. v3.1.0 시점 잔여 § (보존 + 일부 처리)

`_workspace/baseline/v3.1.0/06-history-traceability.md §5-2` 정합 + v3.2.0 사이클 갱신:

| § | 제목 | v3.2.0 시점 상태 |
|---|------|------------------|
| §1 | ADR 0050 슬롯 채번룰 보강 | open — 본 사이클 재발 0(완전 안정화) |
| §2 | KisRestPort 인터페이스 분리 | open — W3 T-O·T-RECON 진입 시 정리 |
| §3 | CLAUDE.md ADR 인덱스 SoT 갱신 | open — 본 baseline 사이클에서 처리 (master §10 ADR 0001~0055 갱신, ADR README §3 1→2건, §11 0056부터 시프트) |
| §4 | worktree-agent-* fallback 브랜치 정리 | open — 누적 미처리 (사용자 직접 승인 후 일괄 정리 의제) |
| §5 | backend-core 영역 침범 패턴 | 본 사이클 0건 (ADR 0050 슬롯 + LOCK 강화) |
| §6 | 사용자 첫 dogfooding 결과 흡수 | §22·§23·§24·§25 신규 누적 (지속 진행) |
| §7 | 후속 트랙 진입 가이드 | **W2 풀세트 클리어 (T-IND·T-DSL·T-PAT·T-SB)** → W3 진입 (T-BT·T-SC·T-SIG·T-RISK·T-O·T-RECON·T-PERF·T-J) |
| §8 | STOMP relay broker 도입 | post-MVP 보존 |
| §9 | frontend 모바일 반응형 | post-W3 보존 (T-SB 768px 이하 안내 add) |
| §10 | AI 영역 침범 ADR 누락 | 헌법 통합으로 흡수 (룰 5 LOCK + ADR 0023·0026) |

---

## §6. 결론 — v3.1.0 → v3.2.0 누적 결과

| 지표 | v3.1.0 (2026-05-08) | v3.2.0 (2026-05-10) | 증감 |
|------|---------------------|---------------------|------|
| **PR 수 (누적)** | #235 | #273 | +38 |
| **ADR 수** | 51 | 55 | +4 (0052·0053·0054·0055) |
| **Flyway 버전** | V8 | V11 | +V9·V10·V11 (T-DSL strategy) |
| **JVM 모듈** | 4 (domain·api·batch·consumer) | **5 (+pattern-engine)** | **+1 (ADR 0004 갱신)** |
| **W2 ★ 트랙 풀세트** | 0 | 4건 (T-IND·T-DSL·T-PAT·T-SB) | +4 |
| 운영 헌법 § 수 | 7 (§1~§7) | 7 (보존) | 0 |
| Living Policy 문서 | 0 | 6건 (kis-credential·logging·kis-first·task-cycle·mermaid + README) | +6 |
| baseline 정형화 스크립트 | 0 | 4건 (init·render-html + slash 2건) | +4 |
| 외부 통합 | KIS REST 8 + WS 2 + master ZIP + DART 2 + Yahoo 1 | + Anthropic 1 (T-DSL helper) | +1 |
| frontend 라우트 | 4 | 5 (+`/strategy/builder`) | +1 |
| KIS 함수 1:1 port | 0 (REST adapter만) | + indicators.py 80 + parser.py + candlestick.py 57 함수/패턴 | +138 |
| backlog § 처리 완료 | 8 | 10 (§24 + §26 추가 처리) | +2 |
| backlog § 신규 누적 | 0 | +5 (§22·§23·§24·§25·§26 — 2건 처리, 3건 open) | +5 |

**v3.2.0 = W2 마감 + 모듈 경계 정합(ADR 0054) + Living Policy 도입(ADR 0053) + baseline 정형화(ADR 0052) baseline**. 

**다음 Wave**: W3 진입 — Backtest · Screener · Signal · Risk · Order · Reconcile · Performance · Notification (8 트랙). 본 사이클 누적 fitness:
- v1/v2 잔재 0건 (T-PAT PatternRecognizerStub 5-A 삭제 + 신규 영역 add-only)
- 자체 추론 0건 (KIS-first raw fetch — indicators.py + parser.py + candlestick.py 검증 보고서 3건)
- LLM provider import 본체 0건 (룰 5 — AI는 `api/.../ai/dsl/` 5 파일 한정 + 사용자 트리거)
- mock 데이터 0건 (메모리 정합 — `EvaluationEmptyCard "데이터 없음"` 명시)
- 모듈 경계 정합 ADR 0054(pattern-engine 라이브러리 jar) — api·consumer 상호 직접 의존 0 보존

→ `07-policy-evolution.md` §1~§4 정합 — Living Policy + baseline 정형화 + 모듈 경계 ADR 발행 의무 + KIS-first 5단계 절차 anchor.
