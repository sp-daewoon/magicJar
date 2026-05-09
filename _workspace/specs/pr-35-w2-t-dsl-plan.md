# PR-35 T-DSL 진행 plan (PR 시리즈 + 우선순위 + LOC 추정 + 디스패치 매트릭스)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-09
- 정합 SoT: `pr-35-w2-t-dsl-design.md` v1 + `pr-35-dispatch-spec.md` v1 + `pr-35-conflict-matrix.md` v1 + `pr-35-kis-dsl-raw-verification.md` v1
- 본 문서: orchestrator가 sub-agent 디스패치 시 우선순위·병렬·LOC 추정 의사결정에 사용.

---

## §1. PR 시리즈 개요 (T-IND PR-32 패턴 정합)

| PR | 담당 | 분량 | 라운드 | 우선순위 | 머지 게이트 |
|---|---|---|---|---|---|
| **PR-35** | architect | M (spec 5 파일 + CHANGELOG 슬롯 사전 발행) | spec only(R0) | **현재** (본 PR) | 사용자 1회 결재 (§10 결재 항목 10건) |
| **PR-36a** | strategy-engine | L (~1500-2000 LOC) | **R1+R2 통합** | **PR-35 머지 후 병렬 1** | 단위 테스트 5종 GREEN |
| **PR-36b** | backend-core | M~L (~800-1200 LOC) | **R1+R2 통합** | **PR-35 머지 후 병렬 1 (PR-36a와 동시 발사)** | Flyway V26·V27 적용 + REST endpoint 200 OK |
| **PR-37** | frontend (메인) + strategy-engine + backend-core (보조) | M~L (~1000-1500 LOC) | R3 일괄 | PR-36a/b 머지 후 | KIS 10 프리셋 e2e import + 정확성 fixture 일괄 GREEN |

---

## §2. PR-36a strategy-engine 분량 추정

### 2-1. 파일별 LOC 추정

| 파일 | 추정 LOC | 사유 |
|---|---|---|
| `consumer/.../strategy/KisYamlDslParser.kt` | ~600 | KIS `parser.py` 664 lines 1:1 Kotlin port. 정규식 토크나이저 + 6단 재귀 하강 + AST 빌더 |
| `consumer/.../strategy/JvmStrategyEvaluator.kt` | ~400 | AST visitor + IndicatorEngine 호출 + sandbox(timeout·CPU budget) + autoDisable 호출 |
| `consumer/.../strategy/DslIdentifierMapper.kt` | ~150 | spec §5-5 매핑 표 25+ 식별자 → IndicatorSpec 변환 + outputKey 추출 |
| `consumer/.../strategy/PatternRecognizerStub.kt` | ~50 | T-PAT 미진입 stub — `recognize()`·`consecutive()` 메서드 |
| `consumer/.../strategy/StrategyEvalPoolConfig.kt` | ~60 | @Configuration @Bean ThreadPoolTaskExecutor (ADR 0047 §2-3 정합) |
| `consumer/.../strategy/StrategyEvalMetricRecorder.kt` | ~120 | strategy_eval_metric ring buffer 기록 (DB INSERT + N=100 retention) |
| `consumer/src/test/kotlin/.../KisDslParserFixtureTest.kt` | ~200 | 8개 fixture (crossover·threshold·breakout·pattern·composite·arithmetic·unary minus·percent — KIS raw L1-L16 docstring 정합) |
| `consumer/src/test/kotlin/.../JvmStrategyEvaluatorTest.kt` | ~150 | basic evaluator 테스트 (mock IndicatorEngine + AST → signal 검증) |
| `consumer/src/test/kotlin/.../StrategyComplexityTest.kt` | ~100 | static 검증 (depth·노드 수·indicator·화이트리스트) |
| `consumer/src/test/kotlin/.../StrategyTimeoutSandboxTest.kt` | ~120 | sandbox timeout 시나리오 (Future.cancel + StrategyTimeoutException) |
| `consumer/src/test/kotlin/.../KisYamlImportTest.kt` | ~100 | KIS .kis.yaml 1건 import → DslStrategyDefinition 정합 |
| `CHANGELOG.md` (in-place edit) | ~1 row | ADR 0050 정합 (작성: strategy-engine) |
| **합계 추정** | **~2050 LOC** | KIS-first raw fetch 정합 의무 (자체 추론 0 — 600줄 parser 비중 높음) |

### 2-2. wallclock 추정

- KIS raw fetch + 11 클래스 매핑 정합 검증: 30분
- KisYamlDslParser 600줄 Kotlin port (정규식 + 재귀 하강): 3.5시간
- JvmStrategyEvaluator + DslIdentifierMapper + PatternRecognizerStub: 2시간
- StrategyEvalPoolConfig + StrategyEvalMetricRecorder: 30분
- 단위 테스트 5종: 1.5시간
- PR 본문 + CHANGELOG: 15분
- **wallclock 추정: ~7.5시간** (1일 작업)

### 2-3. 분량 초과 fallback

PR-36a 분량 초과 인지 시 (orchestrator 사전 동의 필요):
- PR-36a-1: KisYamlDslParser + 단위 테스트 (parser fixture 8건)
- PR-36a-2: JvmStrategyEvaluator + DslIdentifierMapper + PatternRecognizerStub + StrategyEvalPoolConfig + StrategyEvalMetricRecorder + 나머지 테스트

backend-core PR-36b는 PR-36a-1 진행 중에도 동시 가능 (domain sealed class 의존만).

---

## §3. PR-36b backend-core 분량 추정

### 3-1. 파일별 LOC 추정

| 파일 | 추정 LOC | 사유 |
|---|---|---|
| `domain/.../strategy/dsl/{ConditionType,Operator,LogicalOperator,ArithmeticOperator}.kt` | ~80 | 4 enum (KIS raw L23·L34·L46·L52 정합) |
| `domain/.../strategy/dsl/{DslNode,IndicatorRef,LiteralValue,ArithmeticExpr,Condition,CompositeCondition,DslStrategyDefinition}.kt` | ~250 | 7 sealed/data class (KIS raw 1:1 port + Kotlin rename 정합) + requiredDays() port |
| `domain/.../strategy/{Strategy,StrategyDefinition,StrategyComplexity,ComplexityClass,StrategyDisabledReason,StrategyEvalResult,Signal,StrategyException}.kt` | ~250 | 8 도메인 클래스 + magicJar 확장 wrapper |
| `domain/.../strategy/Universe.kt` + `TimeFilter.kt` + `Sizing.kt` + `Timeframe.kt` | ~100 | magicJar 확장 데이터 클래스 |
| `api/.../controller/StrategyController.kt` | ~250 | CRUD + validate + import + export + enable + getEvalMetric (Swagger 4단 + 한국어) |
| `api/.../controller/dto/{CreateStrategyRequest,CreateStrategyResponse,StrategyResponse,...}.kt` | ~150 | DTO 6~8개 |
| `api/.../application/strategy/StrategyService.kt` | ~200 | 등록 시 parser → static 검증 → complexity → DB persist + autoDisable() |
| `api/.../persistence/strategy/{StrategyJpaEntity,StrategyRepository,StrategyEvalMetricJpaEntity,StrategyEvalMetricRepository,StrategyJpaAdapter}.kt` | ~250 | 5 파일 (entity·repo abstract fun만·adapter) |
| `api/src/main/resources/db/migration/V26__strategy_disabled_reason.sql` | ~10 | 컬럼 3개 추가 |
| `api/src/main/resources/db/migration/V27__strategy_eval_metric.sql` | ~30 | 신규 테이블 + index + comment |
| `api/build.gradle.kts` | ~3 | `:consumer` dep 1줄 (architect 결정 시) |
| `api/src/main/resources/application.yml` | ~20 | DSL env 10건 mapping |
| `consumer/src/main/resources/application.yml` | ~10 | DSL eval env mapping |
| `.env.example` | ~12 | 10 env 추가 (시크릿 아님) |
| `CHANGELOG.md` (in-place edit) | ~1 row | ADR 0050 정합 (작성: backend-core) |
| **합계 추정** | **~1620 LOC** | domain sealed class 정합 + REST + Flyway 일괄 |

### 3-2. wallclock 추정

- domain sealed class 15+ 파일: 1.5시간
- StrategyController + DTO: 1시간
- StrategyService + JpaAdapter: 1시간
- StrategyJpaEntity·Repository·EvalMetric·V26·V27: 1시간
- application.yml + .env.example + build.gradle: 30분
- **wallclock 추정: ~5시간**

---

## §4. PR-37 R3 frontend·통합 분량 추정 (참고용 — 별도 dispatch 발행)

| 영역 | 추정 LOC | 사유 |
|---|---|---|
| `frontend/src/pages/Strategy/{Builder,List,Detail}.tsx` | ~600 | 3 페이지 |
| `frontend/src/components/strategy/{DslEditor,ComplexityBadge,AstPreview,YamlImportButton,EvalMetricChart}.tsx` | ~500 | 5 컴포넌트 |
| `frontend/src/api/strategy.ts` | ~100 | TanStack Query hooks |
| `consumer/src/test/resources/kis-presets/strategy_*.kis.yaml` | (raw fetch 후 fixture) | 10 파일 |
| `consumer/src/test/kotlin/.../KisPresetE2ETest.kt` | ~300 | 10 프리셋 import + AST + complexity 검증 |
| `consumer/src/test/kotlin/.../DslAccuracyFixtureTest.kt` | ~400 | 8 fixture(KIS raw 정합) + 10 프리셋 평가 결과 cross-check |
| **합계 추정** | **~1900 LOC** | R3 일괄 — 별도 dispatch-spec PR-36 머지 후 발행 |

---

## §5. 디스패치 매트릭스 (T-IND 정합)

### 5-1. 시나리오 A — 완전 병렬 (architect 권장)

```
T0: PR-35 spec 머지
   │
   ├─ T0+5min: PR-36a strategy-engine background 발사
   └─ T0+5min: PR-36b backend-core background 발사 (동시)
   │
T0+5h:  PR-36b 완료 (먼저 — backend-core 분량 적음)
   │
T0+5h+rebase: PR-36a 완료 + main rebase (CHANGELOG conflict 0 — ADR 0050)
   │
T0+5h+30min: 양쪽 머지 완료
   │
T0+5h+30min: PR-37 R3 dispatch-spec 발행 (architect 단독)
T0+6h:  PR-37 frontend·strategy-engine·backend-core 디스패치
T0+10h: PR-37 머지 — T-DSL 풀세트 가동
```

**병렬 이득**: 직렬(7.5h + 5h = 12.5h) → 병렬(max(7.5h, 5h) = 7.5h) → **40% wallclock 단축**

### 5-2. 시나리오 B — 직렬 (fallback, 권장 X)

PR-36b 먼저 → 머지 → PR-36a → 머지 (12.5h). circular dep 발견 시 또는 사용자 명시 시만 채택.

### 5-3. 디스패치 모드 결정

**시나리오 A LOCK** — 사용자 메모리 `feedback_orchestrator_active_parallel.md` 정합 (직렬 권장 답습 금지). 충돌 행렬 §6 결론 정합 (modify-shared 3건 단독 LOCK + add-only 100%).

---

## §6. sub-agent 분배 매트릭스 (사용자 결재 항목 #3)

### 6-1. 영역별 책임 (LOCK)

| 영역 | strategy-engine | backend-core | ai-engineer | frontend |
|---|---|---|---|---|
| domain/strategy/* | (read-only import) | **메인 (15+ 파일)** | - | - |
| consumer/.../strategy/parser·evaluator | **메인 (7 파일)** | - | - | - |
| consumer/.../strategy/sandbox·pool·metric | **메인** | - | - | - |
| api/.../controller/Strategy* | (read-only — parser bean inject) | **메인** | - | - |
| api/.../application/strategy/StrategyService | - | **메인** | - | - |
| api/.../persistence/strategy/* + Flyway V26·V27 | - | **메인** | - | - |
| application.yml + .env.example | - | **메인** | - | - |
| frontend/src/pages/Strategy/* + components/strategy/* | - | - | - | **메인 (R3 PR-37)** |
| **자연어 → DSL 변환** (W4 이월) | - | - | (W4 T-AI 진입 시) | - |

### 6-2. 정합 사유

- **strategy-engine 메인 (T-IND 정합)**: tracks-roadmap §2 T-DSL row 담당 = `architect+backend-core` 였으나 v3.1.0 sub-agent 세분화 후 strategy-engine이 indicator·DSL·signal 영역 메인 (master §5-2 표 정합).
- **backend-core 보조 (도메인 sealed class + 인프라)**: domain 책임 + REST + Flyway는 backend-core 영역 (T-IND PR-32 정합).
- **ai-engineer 미참여 LOCK**: 자연어 변환 W4 이월 — 룰 5 정합. 본 PR-35 디스패치 0건.
- **market-data 미참여**: candle DB read 의존만. 디스패치 0건.

---

## §7. 사용자 1회 결재 항목 (PR-35 본문)

| # | 항목 | 권장 | 영향 |
|---|---|---|---|
| 1 | DSL 텍스트 LOCK (KIS-first) | 텍스트 LOCK | 11 클래스 1:1 port |
| 2 | 자연어 변환 W4 이월 LOCK | W4 이월 LOCK | 룰 5 정합 |
| 3 | sub-agent 분배 (strategy-engine 메인) | 권장 | T-IND 정합 |
| 4 | R1+R2 통합 + R3 일괄 | 권장 | PR 개수 |
| 5 | 신규 ADR 0건 | LOCK | implementation 명세 |
| 6 | 1차 operator 15종 (KIS LOCK) | 권장 | static 검증 매트릭스 |
| 7 | T-IND 의존 (IndicatorEngine 단일 진입점) | 권장 | ADR 0021 정합 |
| 8 | T-PAT 미진입 stub | 권장 | 1차 graceful + 1줄 교체 |
| 9 | strategy-eval-pool 4 fixed | LOCK | ADR 0047 §2-3 |
| 10 | timeout 1회 auto-disable | LOCK | ADR 0047 §2-2-D |

---

## §8. CI/CD 게이트 (T-IND 정합)

### 8-1. PR-36a CI (strategy-engine)

- `./gradlew :consumer:check` GREEN
- `./gradlew :consumer:bootJar` GREEN
- ktlint 통과
- 단위 테스트 5종 GREEN

### 8-2. PR-36b CI (backend-core)

- `./gradlew :domain:check` GREEN (domain Spring·JPA 의존 0 검증 의무)
- `./gradlew :api:check` GREEN
- `./gradlew :api:bootJar` GREEN (`:consumer` dep 추가 후 circular 0 검증)
- ktlint 통과
- Flyway V26·V27 migration 검증 (마이그레이션 fixture 또는 testcontainer)

### 8-3. PR-37 R3 CI (R3 일괄)

- `./gradlew check` (모든 모듈) GREEN
- frontend `pnpm test/build` GREEN
- KIS 10 프리셋 import e2e GREEN
- DSL 정확성 fixture 일괄 GREEN

---

## §9. orchestrator 디스패치 절차 (PR-35 머지 후)

1. **PR-35 spec 머지 확인**: `git log origin/main --oneline -3 | grep "PR-35"` 존재 검증
2. **CHANGELOG slot row + anchor 검증**: `grep "pr-35-slot-" CHANGELOG.md` → 4건(architect·strategy-engine·backend-core·end) 존재
3. **task 파일 archive**: `node scripts/task.mjs archive T-20260509-1558-w2-t-dsl-spec`
4. **worktree·로컬 브랜치 정리** (헌법 §2)
5. **dispatch (시나리오 A 완전 병렬)**:
   - sub-agent 1 (strategy-engine): `agent/strategy-engine/pr-36a-w2-t-dsl-parser-evaluator` 새 worktree 생성 + background 발사
   - sub-agent 2 (backend-core): `agent/backend-core/pr-36b-w2-t-dsl-domain-rest-flyway` 새 worktree 생성 + background 발사
6. **양쪽 PR 모니터링**: 머지 순서 race 발생 시 두 번째가 main rebase (CHANGELOG conflict 0 — ADR 0050 in-place edit anchor 분리)
7. **양쪽 머지 후**: PR-37 R3 dispatch-spec 발행 + frontend 메인 + strategy-engine·backend-core 보조 디스패치

---

## §10. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-09 | PR-35 plan v1 발행. PR-36a strategy-engine ~2050 LOC + PR-36b backend-core ~1620 LOC + PR-37 R3 ~1900 LOC. 시나리오 A 완전 병렬 (40% wallclock 단축) LOCK. sub-agent 분배 매트릭스 + 사용자 결재 10건 LOCK. (architect, opus 4.7 1M) |
