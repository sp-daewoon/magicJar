# PR-35 T-DSL 설계 가이드 (W2 진입 — Strategy DSL 텍스트 파서 + AST + 평가 엔진 + 자연어 변환 W4 이월)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-09
- 대상 PR 시리즈: PR-36a (strategy-engine — KisYamlDslParser 텍스트 파서 + AST visitor + JvmStrategyEvaluator + DSL 식별자→IndicatorSpec 매핑 + 단위 테스트 5종) · PR-36b (backend-core — domain/strategy/* AST sealed class + StrategyDefinition + StrategyComplexity + StrategyDisabledReason + sandbox 예외 sealed class + StrategyController CRUD/validate/import/export REST + Flyway V26·V27) · PR-37 (R3 — frontend Strategy Builder 텍스트 편집기 + complexity 표시 + KIS 10 프리셋 fixture e2e + 정확성 fixture 일괄 + AST 미리보기)
- 진입 prerequisite: v3.1.0 W1 풀세트 + W2 T-IND 풀세트(PR-33 머지 = 80지표 + IndicatorEngine + REST + Redis 캐시 + frontend overlay) main 위 정상 동작. main HEAD = `9693443` (Merge PR #253 — T-IND 통합 머지). T-PAT는 미진입 — 본 spec은 **T-PAT 미진입 가정** + `consecutive(up)`·`pattern(...)` 호출은 1차 stub(`PatternRecognizerStub.notSupported()`) 후 T-PAT 머지 시 1줄 교체.
- 정합 SoT:
  - **ADR 0022** Strategy DSL = `.kis.yaml` 호환 superset (본 PR-35 spec이 implementation 명세로 풀어냄, **신규 ADR 0건**)
  - **ADR 0047** DSL Evaluation Sandbox (static 검증 한도 + runtime sandbox + thread pool 격리 + auto-disable + complexity 응답)
  - **ADR 0021** Indicator Source 추상화 (T-DSL evaluator는 `IndicatorEngine.calculate()` 호출만 — sources 직접 의존 0)
  - **ADR 0020** KIS Strategy Builder 흡수 — DSL parser 11 클래스 + 10 프리셋 흡수 결정
  - **ADR 0026** AI 옵션 보조 (사용자 트리거 한정 — 자연어 변환은 W4 T-AI 합류, 본 PR 비범위 LOCK)
  - **ADR 0050** CHANGELOG 슬롯 채번룰 (본 spec PR이 sub-agent 슬롯 row 사전 발행 의무)
  - **ADR 0051** Repository default fun 함정 (본 PR-35 Strategy JPA Repository 신설 시 정독 의무)
  - `docs/architecture/master-architecture.md` v2 §B.7 (Strategy DSL) · §5-1(domain/strategy/) · §5-2 표(api strategy CRUD + consumer evaluator) · §10 ADR 인덱스
  - `docs/architecture/tracks-roadmap.md` §1 W2 · §2 T-DSL row · §3 T-DSL outline (선행 T-IND·T-PAT / 후행 T-SB·T-BT·T-SIG)
  - `docs/architecture/development-rules.md` 5건 (룰 2 단일 책임 · 룰 3 라운드 · 룰 4 R3 일괄 · **룰 5 — W2 LLM provider import 0 — 자연어 변환 W4 이월 LOCK 사유**)
  - `docs/architecture/code-convention.md`
  - `docs/architecture/policies/kis-first-raw-fetch.md` (자체 추론 0 — KIS `parser.py` 664 lines raw fetch 의무)
  - `_workspace/specs/pr-35-kis-dsl-raw-verification.md` (본 spec 진입 시 architect 1회 KIS-first raw fetch — 11 클래스 + token patterns + parse 우선순위 LOCK)
  - `_workspace/strategy/indicator-catalog.md` (T-IND 80지표 + DSL 식별자 alias)
  - `_workspace/arch/agent-charter.md` §3(KIS-first) · §4(CHANGELOG 슬롯) · §5(rebase) · §6(Repository default fun 함정)

- 작업 원칙 (개발 룰북 정합):
  - **룰 2 — 단일 책임**: PR-36a = strategy-engine 단일 (consumer strategy/ + dsl/). PR-36b = backend-core 단일 (domain strategy/* + api StrategyController + Flyway). 영역 LOCK.
  - **룰 3 — 라운드 분할**: 본 PR-35 = R0 spec only(코드 0). PR-36 = **R1+R2 통합** (T-IND 패턴 정합 — domain port + parser·evaluator 일괄 vs 분리는 비효율). PR-37 = R3 (frontend + KIS 10 프리셋 e2e + 정확성 fixture 일괄).
  - **룰 4 — 코드 먼저 R3 일괄**: PR-36a/b는 단위 테스트 5종(parser AST + evaluator basic + complexity calc + sandbox timeout + import KIS yaml)만 의무. KIS 10 프리셋 정합 + e2e backtest cross-check는 **R3 PR-37 일괄**.
  - **룰 5 — AI 호출 0 (W2 LLM import 0건)**: T-DSL은 W2 — **자연어 → DSL 변환은 본 PR 비범위 + W4 이월 LOCK**. consumer/api/domain 어디에도 spring-ai·anthropic·openai import 0건. fitness gap §9 검증.

---

## §1. T-DSL 한 줄 목표 + DoD

### 1-1. 목표

> **사용자가 KIS Strategy Builder GUI에서 만든 `.kis.yaml`을 magicJar에 import하면 그대로 동작하고, magicJar GUI(T-SB) 또는 텍스트 편집기에서 작성한 magicJar superset DSL도 동일 평가기에서 동작한다. DSL은 KIS 텍스트 표현식 1줄 형식(예: `ma(5) crosses_above ma(20) AND volume > volume_ma(20) * 1.5`)을 채택하여 호환성 100% 보장 — JSON 트리는 폐기(자체 추론 위반). KisYamlDslParser는 정규식 토크나이저(21 token) + 6단 재귀 하강 파서(_parse_or → _parse_and → _parse_condition → _parse_arithmetic → _parse_term → _parse_factor)로 텍스트를 AST로 변환(KIS raw `parser.py` 664 lines 1:1 port). AST는 sealed class 트리(IndicatorRef·LiteralValue·ArithmeticExpr·Condition·CompositeCondition·DslStrategyDefinition + 5 enum). JvmStrategyEvaluator는 candles + IndicatorEngine 의존(ADR 0021 정합 — sources 직접 의존 0)으로 AST를 walk하여 BUY/SELL/HOLD 시그널 + 강도 + 사유를 반환. ADR 0047 sandbox 100% 적용 — static 검증(AST depth ≤ 32·노드 ≤ 10000·indicator 호출 ≤ 50·키워드 화이트리스트), runtime은 strategy-eval-pool(4 fixed thread) 경유 + timeout 100ms·CPU budget 50ms·timeout 1회 → auto-disable + risk.violation.v1 publish(T-RISK·T-NOTIFY 합류 시점), complexity 응답 4단(LOW·MEDIUM·HIGH·CRITICAL). 자연어 변환은 ADR 0022 §3 + ADR 0026 + 룰 5 정합 W4 이월 LOCK — 본 PR-35 비범위. T-PAT 미진입 시점이라 `consecutive()`·`pattern()` 호출은 PatternRecognizerStub으로 1차 stub + T-PAT 머지 시 1줄 교체.**

### 1-2. DoD (Definition of Done) — PR-36 + PR-37 모두 머지 후 검증

| # | 검증 항목 | 명령·시나리오 | 기대 결과 |
|---|---|---|---|
| 1 | KisYamlDslParser 부팅 등록 | `docker logs magicjar-consumer-app | grep KisYamlDslParser` | "Loaded DSL parser with 21 token patterns" log |
| 2 | KIS 텍스트 표현식 8 fixture 정합 (KIS raw 정합 의무) | `./gradlew :consumer:test --tests "*KisDslParserFixtureTest"` | 8개 fixture(crossover·threshold·breakout·pattern·composite·arithmetic·unary minus·percent) GREEN |
| 3 | DSL 등록 + complexity 응답 (ADR 0047 §2-1-B 정합) | `curl -X POST http://localhost:8080/api/strategy -d '{"name":"goldcross","entry":"ma(5) crosses_above ma(20)","exit":"ma(5) crosses_below ma(20)"}'` | 200 OK + body `{"strategyId":N,"complexity":{"astDepth":3,"nodeCount":7,"indicatorCallCount":2,"estimatedEvalTimeMs":4,"complexityClass":"LOW"}}` |
| 4 | static 검증 한도 위반 (depth) | astDepth=33 짜리 식 등록 시도 | 400 + `{"error":"DslDepthExceededException","detail":"depth=33 limit=32"}` |
| 5 | static 검증 한도 위반 (노드 수) | 노드 10001건 식 등록 시도 | 400 + `{"error":"DslNodeCountExceededException"}` |
| 6 | static 검증 한도 위반 (indicator 호출 수) | indicator 51건 식 등록 시도 | 400 + `{"error":"DslIndicatorCallExceededException"}` |
| 7 | 화이트리스트 위반 (UnknownDslSymbolException) | `system_exit() > 0` 식 등록 시도 | 400 + `{"error":"UnknownDslSymbolException","detail":"Unknown identifier 'system_exit'"}` |
| 8 | 평가기 + IndicatorEngine 호출 (ADR 0021 정합) | candle 100건 입력 + 식 `ma(5) crosses_above ma(20)` evaluate | 시그널 enum 1건 + 사유 `{trigger:"CROSSOVER",lhs:"ma(5)",rhs:"ma(20)",lhsValue:1234.56,rhsValue:1230.12}` |
| 9 | runtime sandbox timeout (ADR 0047 §2-2-A) | 무거운 식(indicator 50건) evaluate시 100ms 초과 시뮬레이션 | StrategyTimeoutException + strategy.disabled_reason='TIMEOUT' DB update + (T-RISK 합류 시) risk.violation.v1 publish |
| 10 | strategy-eval-pool 격리 (ADR 0047 §2-3) | bean 등록 + thread name | `strategy-eval-pool` Bean + thread name `strategy-eval-N` |
| 11 | KIS .kis.yaml import (ADR 0022 §2-3) | `curl -X POST http://localhost:8080/api/strategy/import?format=kis-yaml -F "file=@golden_cross.kis.yaml"` | 200 OK + magicJar 형식 등록 + 동일 식 평가 결과 KIS와 동일 (R3 e2e) |
| 12 | KIS 10 프리셋 e2e (R3 PR-37 일괄) | KIS 10 프리셋 yaml 일괄 import + 백테스트 (T-BT 의존 — 본 PR 비범위 시 import만 검증) | 10개 프리셋 모두 import 200 OK + AST depth/노드 수 모두 한도 내 |
| 13 | LLM provider import 0 (룰 5 W2 LOCK) | `grep -rE "spring-ai\|anthropic\|openai" {api,consumer,batch,domain}/src/main` | 0건 |
| 14 | Flyway V26 + V27 적용 (ADR 0047 §2-6) | `psql -c "\d strategy"` + `\d strategy_eval_metric` | strategy 테이블에 disabled_reason·last_disabled_at·eval_metric_summary 컬럼 + strategy_eval_metric 신규 테이블 |
| 15 | Repository default fun 함정 부재 (ADR 0051) | `grep -rE "interface.*JpaRepository.*\{" -A 100 api/src` review | body 가진 fun 0건 (StrategyRepository는 abstract fun만) |
| 16 | DSL 식별자 → IndicatorSpec 매핑 (T-IND 합류) | DSL `bb_upper(20, 2)` evaluate | IndicatorEngine.calculate(IndicatorSpec("bb", {period:20,std:2})) 호출 + outputKey="upper" 추출 (raw L94-L96 정합) |
| 17 | T-PAT 미진입 graceful (T-PAT stub) | DSL `consecutive(up) >= 5` evaluate | PatternRecognizerStub → `EvalResult.HOLD(reason="T-PAT not yet implemented")` (T-PAT 머지 시 1줄 교체) |
| 18 | yaml export comment fallback (ADR 0022 §2-3) | magicJar 확장 키워드 포함 strategy export `?format=kis-yaml` | 확장 키워드 모두 `# magicJar:` comment 처리 + KIS 핵심은 그대로 |
| 19 | Graceful Shutdown (ADR 0045 정합) | `docker stop magicjar-consumer-app` | strategy-eval-pool 진행 중 평가 ≤ 2000ms 대기 + 신규 평가 reject |
| 20 | 자연어 변환 W4 이월 LOCK 검증 (룰 5) | `grep -rE "naturalLanguage\|nlToDsl\|prompt" {api,consumer,batch,domain}/src/main` | 0건 (W4 T-AI에서 진입) |

### 1-3. 비즈니스 가치

- **W2 핵심 — T-SB·T-BT·T-SIG 모든 후속 트랙의 prerequisite**. DSL 없이는 사용자가 전략을 정의할 방법 0.
- **사용자 가치 1**: KIS Strategy Builder GUI에서 만든 .kis.yaml 파일 그대로 magicJar에 import → 동일 평가 결과 (검증된 KIS 자산 가치 100% 보존).
- **사용자 가치 2**: 텍스트 한 줄 식(`ma(5) crosses_above ma(20)`)으로 간결한 전략 표현 — JSON 트리보다 읽기 쉬움 + KIS 호환.
- **사용자 가치 3**: ADR 0047 sandbox로 안전 보장 — 사용자가 부주의한 식 등록해도 시스템 전체 마비 0.
- **자동매매 fitness (사용자 메모리 `feedback_v3_skepticism_no_regression.md` 정합)**: DSL은 quant trading 본체의 의사결정 언어. v1/v2 잔재 0건 (PoC v1 시점에는 DSL 0 — 단일 RSI 룰 hardcode. v2 ADR 0022·0047 정합 superset DSL은 첫 도입). 자연어 변환은 W4 이월 — 룰 본체에 LLM 호출 0(룰 5).

---

## §2. ADR 0022·0047·0021 준수 항목 체크리스트

본 PR-35(spec) → PR-36(코드)이 ADR 0022 §2-1~§2-5 + ADR 0047 §2-1~§2-7 + ADR 0021 §2-2를 그대로 implementation 한다. **신규 ADR 0건** — ADR 본문은 이미 충분(PR-32 T-IND 패턴 정합).

### 2-1. ADR 0022 (Strategy DSL `.kis.yaml` superset) — 의무 항목

| ADR 0022 §위치 | 결정 사항 | PR-36 구현 위치 | 담당 |
|---|---|---|---|
| §2-1 KIS 호환 핵심 | 11 클래스 1:1 port (5 enum + 6 dataclass) | `domain/.../strategy/dsl/{ConditionType,Operator,LogicalOperator,ArithmeticOperator,IndicatorRef,LiteralValue,ArithmeticExpr,Condition,CompositeCondition,DslStrategyDefinition}.kt` | **PR-36b backend-core** |
| §2-2 magicJar 확장 키워드 | tf·time.session·time.windowKst·universe.market·universe.filter·sizing·trailingStop·fixedStopLoss | `domain/.../strategy/StrategyDefinition.kt` (DslStrategyDefinition 위 wrapper — magicJar 확장 + KIS DSL inner) | **PR-36b backend-core** |
| §2-3 import/export | `POST /api/strategy/import?format=kis-yaml` + `GET /api/strategy/{id}?format=kis-yaml` (확장 키워드 comment fallback) | `api/.../controller/StrategyController.kt` | **PR-36b backend-core** |
| §2-4 평가 흐름 | `KisYamlDslParser.parse(yaml) → DslStrategyDefinition` + `JvmStrategyEvaluator.evaluate(strategy, candles, ctx) → StrategyEvalResult` | `consumer/.../application/strategy/{KisYamlDslParser,JvmStrategyEvaluator}.kt` | **PR-36a strategy-engine** |
| §2-5 검증·테스트 | `POST /api/strategy/validate` + KIS 10 프리셋 cross-check (R3 PR-37) | StrategyController.validate + R3 fixture | **PR-36b + R3** |

### 2-2. ADR 0047 (DSL Evaluation Sandbox) — 의무 항목

| ADR 0047 §위치 | 결정 사항 | PR-36 구현 위치 | 담당 |
|---|---|---|---|
| §2-1-1 AST depth ≤ 32 | `MAGICJAR_DSL_AST_DEPTH_LIMIT=32` env | `KisYamlDslParser.validateStatic()` AST visitor | **PR-36a strategy-engine** |
| §2-1-2 노드 수 ≤ 10000 | `MAGICJAR_DSL_NODE_COUNT_LIMIT=10000` | 동일 visitor | **PR-36a strategy-engine** |
| §2-1-3 indicator 호출 ≤ 50 | `MAGICJAR_DSL_INDICATOR_CALL_LIMIT=50` | 동일 visitor | **PR-36a strategy-engine** |
| §2-1-A 키워드 화이트리스트 | DSL 식별자만 허용 — UnknownDslSymbolException | `KisYamlDslParser._parse_indicator()` 진입 시 화이트리스트 검증 | **PR-36a strategy-engine** |
| §2-1-B Estimated complexity 응답 | `complexity` 4단(LOW/MEDIUM/HIGH/CRITICAL) | `domain/.../strategy/StrategyComplexity.kt` + `StrategyController.create` 응답 | **PR-36b backend-core** (domain 타입) + **PR-36a strategy-engine** (계산 로직) |
| §2-2-A timeout 100ms (realtime) / 1000ms (backtest) | `MAGICJAR_DSL_EVAL_TIMEOUT_MS=100` / `MAGICJAR_DSL_BACKTEST_TIMEOUT_MS=1000` | `JvmStrategyEvaluator.evaluate()` `Future.get(timeout)` | **PR-36a strategy-engine** |
| §2-2-B CPU budget 50ms | `MAGICJAR_DSL_CPU_BUDGET_MS=50` | ThreadMXBean.getThreadCpuTime() | **PR-36a strategy-engine** |
| §2-2-D timeout 1회 → auto-disable | strategy.disabled_reason='TIMEOUT' update + risk.violation.v1 publish (T-RISK·T-NOTIFY 합류 시점) | `JvmStrategyEvaluator` + `StrategyService.autoDisable()` | **PR-36a strategy-engine** + **PR-36b backend-core** (Service) |
| §2-3 strategy-eval-pool (4 fixed) | `@Bean strategyEvalPool` ThreadPoolTaskExecutor | `consumer/.../config/StrategyEvalPoolConfig.kt` | **PR-36a strategy-engine** |
| §2-6 Flyway V26·V27 | strategy 컬럼 추가(disabled_reason·last_disabled_at·eval_metric_summary) + strategy_eval_metric 신규 | `api/src/main/resources/db/migration/V26__strategy_disabled_reason.sql` + `V27__strategy_eval_metric.sql` | **PR-36b backend-core** |
| §2-7 환경변수 10건 | `.env.example` 추가 (시크릿 아님 — ADR 0046 비위반) | `.env.example` + `application.yml` mapping | **PR-36b backend-core** |
| §2-8 Graceful Shutdown | strategy-eval-pool SmartLifecycle stop 2000ms timeout | StrategyEvalPoolConfig.@PreDestroy 또는 Bean lifecycle | **PR-36a strategy-engine** |

### 2-3. ADR 0021 (Indicator Source 추상화) 정합

| ADR 0021 §위치 | T-DSL 의존 |
|---|---|
| §2-2 IndicatorEngine | JvmStrategyEvaluator는 `IndicatorEngine.calculate(spec, candles)` 단일 진입점만 사용 — sources 직접 의존 0 |
| §2-1 IndicatorSpec | DSL `indicator(name, params)` → `IndicatorSpec(name, params)` 변환 매핑 — DSL 식별자(`bb_upper`) → IndicatorSpec(`name="bb",outputKey="upper"`) 매핑 테이블 의무 (raw verification §3 정합) |
| §2-4 multi-output | `bb_upper` → `IndicatorPoint.values["upper"]` 추출 |

### 2-4. KIS-first raw fetch 의무 (헌법 §3)

본 PR-35 spec 작성 시 — KIS Strategy Builder `parser.py` 직접 raw fetch 의무. 자체 추론 0.

```bash
# raw fetch 명령 (architect 본 spec 작성 시 1회 실행 — `pr-35-kis-dsl-raw-verification.md` 산출 완료)
curl -sL https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/strategy_builder/strategy_core/dsl/parser.py
```

- 11 클래스 시그니처 (param 이름·default 값·return shape) → `pr-35-kis-dsl-raw-verification.md` §2 매트릭스 LOCK
- 21 token patterns 정규식 → `pr-35-kis-dsl-raw-verification.md` §2-3 정합
- 6단 재귀 하강 우선순위 → `pr-35-kis-dsl-raw-verification.md` §2-4 정합
- ConditionType 결정 룰 (raw L361-L390) → `pr-35-kis-dsl-raw-verification.md` §2-5 정합
- get_required_days() (raw L283-L296) → `pr-35-kis-dsl-raw-verification.md` §2-6 정합
- DSL 식별자 → IndicatorSpec 매핑 (raw L72-L107) → `pr-35-kis-dsl-raw-verification.md` §3 정합
- sub-agent PR-36a strategy-engine은 `parser.py` raw fetch 후 각 클래스 KDoc에 `Source: parser.py:line_NN` 인용 의무

---

## §3. 모듈 경계 결정 (사용자 결재 필요 항목)

### 3-1. domain — DSL AST + StrategyDefinition + 도메인 sealed class (Spring 의존 0)

`domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/strategy/`:

```
strategy/
├── Strategy.kt                  ← 매개 도메인(name·universe·entry·exit·sizing·timeFilter — magicJar 확장)
├── StrategyDefinition.kt         ← KIS 호환 inner DslStrategyDefinition + magicJar 확장 wrapper
├── StrategyComplexity.kt         ← data class (astDepth·nodeCount·indicatorCallCount·estimatedEvalTimeMs·complexityClass: ComplexityClass)
├── ComplexityClass.kt            ← enum (LOW·MEDIUM·HIGH·CRITICAL)
├── StrategyDisabledReason.kt     ← enum (NULL·TIMEOUT·CPU_BUDGET_EXCEEDED·MANUAL)
├── StrategyEvalResult.kt         ← data class (signal: Signal·strength: BigDecimal·reason: Map<String,Any>·evaluatedAt·evalTimeMs)
├── Signal.kt                     ← enum (BUY·SELL·HOLD)
├── StrategyException.kt          ← sealed class (DslDepthExceededException·DslNodeCountExceededException·DslIndicatorCallExceededException·UnknownDslSymbolException·UnknownDslPatternException·DslSyntaxException·StrategyTimeoutException)
└── dsl/
    ├── ConditionType.kt          ← enum (CROSSOVER·THRESHOLD·BREAKOUT·PATTERN·COMPOSITE) — KIS raw L23
    ├── Operator.kt               ← enum (GT·LT·GTE·LTE·EQ·NEQ·CROSSES_ABOVE·CROSSES_BELOW·BREAKS) — KIS raw L34
    ├── LogicalOperator.kt        ← enum (AND·OR) — KIS raw L46
    ├── ArithmeticOperator.kt     ← enum (ADD·SUB·MUL·DIV) — KIS raw L52
    ├── DslNode.kt                ← sealed class (IndicatorRef·LiteralValue·ArithmeticExpr·Condition·CompositeCondition)
    ├── IndicatorRef.kt           ← KIS Indicator → IndicatorRef rename (ADR 0021 도메인 Indicator 충돌 회피)
    ├── LiteralValue.kt           ← KIS Value → LiteralValue rename (Kotlin Value 키워드 충돌 회피)
    ├── ArithmeticExpr.kt         ← KIS raw L235
    ├── Condition.kt              ← KIS raw L251 + requiredDays() port (raw L283-L296)
    ├── CompositeCondition.kt     ← KIS raw L283
    └── DslStrategyDefinition.kt  ← KIS StrategyDefinition rename (Kotlin Definition 충돌 회피)
```

**판단 근거**:
- master §B.7 Strategy DSL 의무 위치 = domain (Spring 의존 0)
- ADR 0022 §2-1 11 클래스 sealed class port = domain 책임
- ADR 0047 §3-2 domain 컨테이너 영향 = sealed class + enum + data class 의무
- KIS raw 클래스 이름 vs Kotlin 도메인 충돌 회피 — 3건 rename: Indicator → IndicatorRef, Value → LiteralValue, StrategyDefinition → DslStrategyDefinition (KDoc에 KIS raw 이름 매핑 의무)

### 3-2. consumer — KisYamlDslParser + JvmStrategyEvaluator + sandbox

`consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/application/strategy/`:

```
strategy/
├── KisYamlDslParser.kt              ← 정규식 토크나이저 + 6단 재귀 하강 파서 (KIS parser.py 1:1 port)
├── JvmStrategyEvaluator.kt          ← AST visitor + IndicatorEngine 호출 + sandbox 적용
├── DslIdentifierMapper.kt           ← DSL 식별자(bb_upper) → IndicatorSpec(name="bb",outputKey="upper") 매핑 (raw verification §3 LOCK)
├── PatternRecognizerStub.kt         ← T-PAT 미진입 1차 stub — `consecutive(up)`·`pattern("BULLISH_ENGULFING")` 호출 시 EvalResult.HOLD(reason="T-PAT not yet")
├── StrategyEvalPoolConfig.kt        ← @Configuration @Bean strategyEvalPool (ThreadPoolTaskExecutor 4 fixed)
├── StrategyEvalMetricRecorder.kt    ← strategy_eval_metric ring buffer 기록 (마지막 N=100건)
└── preset/
    ├── PresetStrategies.kt          ← KIS 10 프리셋 (strategy_NN_*.py) Kotlin port — R3 PR-37에서 본격 등록
    └── (R3에서 strategy_01_golden_cross.kt 등 10 파일 추가)
```

**판단 근거**: master §5-2 표 — Strategy의 consumer 책임 = "DSL parse + evaluate + sandbox 적용". sub-agent strategy-engine 메인.

### 3-3. api — REST 컨트롤러 + Flyway + StrategyService

`api/src/main/kotlin/kr/spdaewoon/magicjar/api/`:

```
controller/
└── StrategyController.kt             ← @RestController CRUD + validate + import + export (Swagger 4단 + 한국어)

application/strategy/
└── StrategyService.kt                ← Spring @Service: 등록 시 KisYamlDslParser → static 검증 → complexity 계산 → DB persist + autoDisable(strategyId) helper

persistence/strategy/
├── StrategyJpaEntity.kt              ← @Entity (V26 컬럼 추가 — disabled_reason·last_disabled_at·eval_metric_summary jsonb)
├── StrategyRepository.kt             ← interface JpaRepository (abstract fun만 — ADR 0051 함정 회피)
├── StrategyEvalMetricJpaEntity.kt    ← @Entity (V27 신규 테이블)
├── StrategyEvalMetricRepository.kt   ← interface JpaRepository
└── StrategyJpaAdapter.kt             ← StrategyRepositoryPort 구현(domain port 의존)

src/main/resources/db/migration/
├── V26__strategy_disabled_reason.sql      ← strategy 테이블 컬럼 추가
└── V27__strategy_eval_metric.sql          ← strategy_eval_metric 신규 테이블
```

**중요**: api 모듈에 KisYamlDslParser bean 별도 등록 (T-IND 패턴 정합 — consumer/api 양쪽 scope). 또는 api 모듈은 JvmStrategyEvaluator 호출 안 함 (등록·검증만 — KisYamlDslParser는 호출 필요). architect 결정: **api 모듈에 KisYamlDslParser만 별도 bean** (parser는 stateless — multi-instance OK), JvmStrategyEvaluator는 consumer 단독.

**대안 검토**:
- (a) api → consumer Kafka 호출로 등록·검증 위임 — overhead 큼, 1차 폐기
- (b) api·consumer 양쪽에 KisYamlDslParser bean (parser stateless) — **채택**
- (c) parser 코드 복사 (api·consumer 별도 발행) — 1차 폐기 (T-IND처럼 consumer dep 추가 권장)

**circular dep 검증 의무**: PR-36a strategy-engine sub-agent가 `./gradlew :api:bootJar` 빌드 검증. 만약 `:api → :consumer` 추가 시 circular 발견되면 architect 호출 → fallback (api 모듈에 별도 KisYamlDslParser 위치 — KIS raw `parser.py`는 1 파일 단위 self-contained라 복사 비용 낮음).

### 3-4. batch — T-BT 진입 시점에 평가기 호출

batch는 본 PR-35 비범위. T-BT(W3) 진입 시 백테스트 잡에서 `JvmStrategyEvaluator` 호출 가능 (post-T-DSL).

### 3-5. frontend — 텍스트 편집기 + complexity 표시 (R3 PR-37)

`frontend/src/pages/Strategy/`:

```
Strategy/
├── Builder.tsx                  ← R3 PR-37 — 텍스트 편집기 (Monaco editor 또는 textarea + syntax highlight) + AST 미리보기 + complexity 4단 색상 표시
├── List.tsx                     ← R3 — strategy CRUD list
└── Detail.tsx                   ← R3 — strategy 상세 + 마지막 N=20회 평가 metric + auto-disable 빨간 배너 + [다시 활성화] 버튼

frontend/src/components/strategy/
├── DslEditor.tsx                ← textarea + 식별자 자동완성 (DSL 식별자 + 80지표 picker)
├── ComplexityBadge.tsx          ← 4단 색상 (LOW=녹색·MEDIUM=노란색·HIGH=주황색·CRITICAL=빨간색+모달)
├── AstPreview.tsx               ← 등록 전 AST 시각화 + depth/노드 수 실시간
└── YamlImportButton.tsx         ← .kis.yaml 파일 업로드 → POST /api/strategy/import
```

### 3-6. sub-agent 영역 분배 (메인=strategy-engine·보조=backend-core)

**사용자 결재 항목**: T-DSL 메인 책임은 strategy-engine OR backend-core?

**architect 권장 = strategy-engine 메인 + backend-core 보조** (T-IND PR-32 패턴 정합):
- 근거 1: tracks-roadmap §2 T-DSL row 담당 = `architect+backend-core` (당시 v2 시점 — strategy-engine 분리 전). v3.1.0 sub-agent 세분화 후 strategy-engine이 indicator·DSL·signal 영역 메인.
- 근거 2: master §5-2 표 — DSL·Signal·Screener·Backtest 모두 strategy-engine 영역.
- 근거 3: backend-core는 도메인 sealed class·port·인프라(JPA·Flyway) 책임 — domain/strategy/* + StrategyController + Flyway V26·V27 = backend-core.
- 근거 4: T-IND가 strategy-engine 메인이었으므로 일관성 유지 — 사용자 인지 흐름 자연스러움.

**보조 결정**: backend-core (domain port·StrategyController·Flyway·StrategyService Spring layer)

**market-data 미참여**: candle DB read 의존만. market-data 디스패치 0건.

**ai-engineer 미참여 LOCK**: 자연어 변환 W4 이월 — 룰 5 정합. 본 PR-35는 ai-engineer 디스패치 0건.

---

## §4. PR 분할 매트릭스 (R1+R2 통합 + R3 일괄 — T-IND 패턴 정합)

### 4-1. PR 시리즈 개요

| PR | 담당 | 분량 | 라운드 | 영역 | 우선순위 |
|---|---|---|---|---|---|
| **PR-35** | architect | M | spec only(R0) | docs(`_workspace/specs/` 5 파일 + CHANGELOG 슬롯 사전 발행) | **현재** (본 PR) |
| **PR-36a** | strategy-engine | L | **R1+R2 통합** | consumer strategy/ — KisYamlDslParser + JvmStrategyEvaluator + DslIdentifierMapper + PatternRecognizerStub + StrategyEvalPoolConfig + StrategyEvalMetricRecorder + 단위 테스트 5종(parser fixture·evaluator basic·complexity·sandbox timeout·import KIS yaml) | **PR-35 머지 후 병렬 1** |
| **PR-36b** | backend-core | M~L | **R1+R2 통합** | domain strategy/* sealed class + api StrategyController + StrategyService + StrategyJpaEntity·Repository·Adapter + Flyway V26·V27 + .env.example 10 변수 | **PR-35 머지 후 병렬 1** |
| **PR-37** | frontend (메인) + strategy-engine + backend-core (보조) | M~L | R3 일괄 | frontend Builder/List/Detail + DslEditor/ComplexityBadge/AstPreview/YamlImportButton + KIS 10 프리셋 fixture import e2e + 정확성 fixture 일괄 | PR-36a/b 머지 후 |

**라운드 분할 일탈 사유 (룰 3 정합 사전 보고)**:
- 표준 룰 3은 R1(domain port·시그니처) → R2(어댑터·영속) → R3(UI·테스트). 이론상 PR-36b R1 → PR-36a R2 직렬.
- 본 spec은 **R1+R2 통합** 권장 — DSL 11 클래스 + parser + evaluator + sandbox + REST + Flyway 동시 발행이 자연스러움 (parser·evaluator·controller가 서로 의존 — 빈 인터페이스만 R1로 분리하면 R2까지 시작 못함). PR-36b가 domain sealed class + REST + Flyway 동시 발행, PR-36a가 parser + evaluator + sandbox 동시 발행. 양쪽 인터페이스 동결 시점 = 본 spec PR-35 머지.
- T-IND PR-32에서 같은 R1+R2 통합 권장 + 완전 병렬로 성공 (1.5일 → 5시간 단축) — **재현 의무 (사용자 메모리 `feedback_orchestrator_active_parallel.md` 정합)**.

### 4-2. 병렬 가능 매트릭스 (PR-36a ⊕ PR-36b)

| 영역 | PR-36a strategy-engine | PR-36b backend-core | 충돌 위험 |
|---|---|---|---|
| domain/strategy/ | (read-only — sealed class import) | **새 파일 add-only** (15+ 파일) | 0 (PR-36a는 PR-36b 머지 후 import) |
| consumer/.../strategy/KisYamlDslParser·JvmStrategyEvaluator·... | **신규 add-only** (7 파일) | (touch 0) | 0 |
| consumer/.../strategy/StrategyEvalPoolConfig.kt | **신규 add-only LOCK** | (touch 0) | 0 |
| api/.../controller/StrategyController.kt | (read-only — KisYamlDslParser·StrategyService import) | **신규 add-only** | 0 (PR-36a 머지 후 import) |
| api/.../application/strategy/StrategyService.kt | (touch 0) | **신규 add-only** | 0 |
| api/.../persistence/strategy/* | (touch 0) | **신규 add-only** | 0 |
| api/src/main/resources/db/migration/V26·V27 | (touch 0) | **신규 add-only** | 0 |
| api/build.gradle.kts | **수정 가능 — `:consumer` dep 추가** | (touch 0) | strategy-engine 단독 LOCK |
| .env.example | (touch 0) | **modify-shared — 10 변수 추가 (add-only insert)** | 0 (다른 sub-agent 동시 .env.example 변경 없음) |
| application.yml (3 모듈) | (touch 0) | **modify-shared — DSL env mapping 추가** | strategy-engine 단독 LOCK 또는 backend-core 단독 LOCK 둘 중 1 |
| CHANGELOG.md | **본인 row in-place edit (작성: strategy-engine)** | **본인 row in-place edit (작성: backend-core)** | 0 (ADR 0050 정합) |

**결론**: §7 충돌 행렬에서 application.yml modify-shared 우려 → **backend-core 단독 LOCK** (DSL env mapping은 backend-core 책임 — strategy-engine은 .env env 호출만).

**권장 디스패치 모드**: **완전 병렬 (시나리오 A) — T-IND 정합**. orchestrator는 본 spec PR-35 머지 즉시 PR-36a·PR-36b 동시 background 발사.

---

## §5. KIS DSL 텍스트 표현식 LOCK (사용자 결재 #1 — KIS-first 결정)

`pr-35-kis-dsl-raw-verification.md` §1·§2 raw fetch 결과 정합. 본 spec에서 LOCK한다. sub-agent는 자체 추론 0.

### 5-1. DSL 1 줄 표현식 형식 (KIS 호환 LOCK)

```
# Crossover (raw L1-L16 docstring 정합)
ma(5) crosses_above ma(20)
ema(12) crosses_below ema(26)

# Threshold
disparity(20) < 90
rsi(14) < 30
rsi(14) > 70

# Breakout
close breaks high(250)

# Pattern (T-PAT 미진입 시 stub)
consecutive(up) >= 5

# Composite (AND/OR + 우선순위 OR < AND < condition)
close > ma(60) AND change > 0
rsi(14) < 30 OR cci(20) < -100
(close > ma(60) AND change > 0) OR rsi(14) < 30

# Arithmetic (* / 우선순위 > + -)
volume > volume_ma(20) * 1.5
close < ma(20) - atr(14) * 2

# 단항 마이너스
returns(1) > -3.5

# Percent (raw L218 Value.is_percent — 정수/소수 → / 100 변환)
returns(5) > 5%
```

### 5-2. magicJar 확장 키워드 (ADR 0022 §2-2 정합 — yaml level)

DSL 한 줄 식 외 `.kis.yaml` 파일 level의 magicJar 확장 키워드 (yaml field level — 텍스트 식 외부):

```yaml
name: my_strategy
universe:
  market: KOSPI               # 또는 KOSDAQ·KONEX
  filter:
    sector: ["반도체", "이차전지"]
    minMarketCap: 1000000000000
tf: D1                        # default timeframe
entry:
  conditions:
    - tf: D1                  # 다중 timeframe
      expr: "ma(5) crosses_above ma(20)"  # 텍스트 1 줄
    - tf: 60m
      expr: "rsi(14) < 30"
time:
  session: REGULAR            # PRE_MARKET·POST_MARKET 옵션
  windowKst: ["09:30", "14:50"]
exit:
  conditions:
    - expr: "ma(5) crosses_below ma(20)"
  trailingStop: 0.05
  fixedStopLoss: 0.03
sizing:
  type: FIXED_FRACTIONAL
  fraction: 0.05
```

### 5-3. KIS .kis.yaml 1차 import 형식 (호환성 100%)

```yaml
# .kis.yaml 그대로 import 가능 — KIS Strategy Builder 산출물
name: golden_cross_5_20
name_ko: "골든크로스 5·20"
universe: KOSPI200             # KIS 단순 형식 — magicJar는 universe.market=KOSPI universe.filter=null로 변환
buy_condition: "ma(5) crosses_above ma(20)"   # 텍스트 1 줄 (raw L555 StrategyDefinition.buy_condition)
sell_condition: "ma(5) crosses_below ma(20)"
params:
  initial_capital: 10000000
  position_size: 0.1
```

magicJar import 결과 → DslStrategyDefinition + magicJar 확장 default(tf=D1·session=REGULAR·windowKst=null·trailingStop=null) 자동 적용.

### 5-4. magicJar export 시 KIS 호환 fallback (ADR 0022 §2-3 정합)

magicJar 확장 키워드(tf·time·sizing·trailingStop·fixedStopLoss·universe.filter)는 **YAML comment(#)로 처리** — KIS 파서가 무시:

```yaml
name: my_strategy
universe: KOSPI                                     # magicJar: universe.filter.sector=["반도체"], minMarketCap=1조
buy_condition: "ma(5) crosses_above ma(20)"
sell_condition: "ma(5) crosses_below ma(20)"
# magicJar:tf=D1
# magicJar:time.session=REGULAR
# magicJar:time.windowKst=["09:30","14:50"]
# magicJar:sizing.type=FIXED_FRACTIONAL fraction=0.05
# magicJar:trailingStop=0.05
# magicJar:fixedStopLoss=0.03
params:
  initial_capital: 10000000
  position_size: 0.05
```

### 5-5. DSL 식별자 → IndicatorSpec 매핑 LOCK (T-IND 합류 — raw verification §3 정합)

**자체 추론 0** — KIS raw `parser.py` L72-L107 + L196-L210 정합. PR-36a strategy-engine sub-agent는 다음 매핑 표를 그대로 `DslIdentifierMapper.kt`에 LOCK:

| DSL 식별자 | IndicatorSpec.name | outputKey | params (default) | 비고 |
|---|---|---|---|---|
| `ma(period)` | `ma` | `ma` | period | T-IND 80지표 |
| `ema(period)` | `ema` | `ema` | period | |
| `rsi(period)` | `rsi` | `rsi` | period=14 | |
| `cci(period)` | `cci` | `cci` | period=20 | |
| `adx(period)` | `adx` | `adx` | period=14 | |
| `mfi(period)` | `mfi` | `mfi` | period=14 | |
| `obv()` | `obv` | `obv` | (없음) | params 0 |
| `atr(period)` | `atr` | `atr` | period=14 | |
| `macd(fast,slow,signal)` | `macd` | `macd` | fast=12,slow=26,signal=9 | multi-output - DSL `macd()` → `IndicatorPoint.values["macd"]` |
| `macd_signal(fast,slow,signal)` | `macd` | `signal` | 동일 | DSL alias — `IndicatorPoint.values["signal"]` |
| `macd_hist(fast,slow,signal)` | `macd` | `histogram` | 동일 | DSL alias — `IndicatorPoint.values["histogram"]` |
| `bb_upper(period,std)` | `bb` | `upper` | period=20,std=2 | DSL alias |
| `bb_lower(period,std)` | `bb` | `lower` | period=20,std=2 | DSL alias |
| `bb_middle(period)` | `bb` | `middle` | period=20 | DSL alias |
| `stoch_k(period)` | `stochastic_k` | `k` | period=14 | DSL alias |
| `stoch_d(period)` | `stochastic_d` | `d` | period=14 | DSL alias |
| `volume_ma(period)` | `volume_ma` | `volume_ma` | period=20 | |
| `disparity(period)` | `disparity` | `disparity` | period=20 | |
| `volatility(period)` | `volatility` | `volatility` | period=20 | KIS 자체 |
| `returns(period)` | `returns` | `returns` | period=1 | KIS 자체 — IndicatorEngine 등록 의무 (T-IND가 80지표 다 등록) |
| `consecutive(direction)` | (T-PAT 합류) | (T-PAT) | direction=up|down | **T-PAT 미진입 시 PatternRecognizerStub.notSupported() — EvalResult.HOLD(reason="T-PAT not yet")** |
| `change()` | `change` | `change` | (없음) | KIS 일변동률 |
| `highest(period)` | `highest` | `highest` | period | |
| `lowest(period)` | `lowest` | `lowest` | period | |
| `close`·`prev_close`·`high`·`low`·`volume` | (특수 — candle 직접) | - | - | indicator 라우팅 X — Candle 객체 직접 접근 |

**T-IND 80지표 모두 DSL에서 호출 가능**: 80지표 IndicatorSpec.name이 곧 DSL 식별자 (위 alias 제외). 추가 식별자(예: `rvi`·`tsi` 등)는 raw L196-L210 화이트리스트 정합 — PR-36a sub-agent가 raw `parser.py` 직접 읽고 80개 + alias 모두 매핑 의무.

### 5-6. KIS 10 프리셋 fixture (R3 PR-37 일괄)

KIS open-trading-api repo `strategy_builder/strategies/strategy_NN_*.py` 10건. R3 PR-37에서 `consumer/src/test/resources/kis-presets/strategy_*.kis.yaml` fixture 일괄 import + 평가 결과 KIS와 동일 cross-check. 본 PR-35 비범위.

```bash
# R3 PR-37 frontend·strategy-engine sub-agent 의무 — raw fetch
for i in $(seq -f "%02g" 1 10); do
    curl -sL "https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/strategy_builder/strategies/strategy_${i}_*.py"
done
```

---

## §6. 데이터 흐름 + 인터페이스 동결

### 6-1. 호출 시퀀스 (사용자 등록 → 평가 → 시그널)

```
[frontend] DslEditor "ma(5) crosses_above ma(20) AND volume > volume_ma(20) * 1.5"
   │
   ▼
[frontend] POST /api/strategy {name, entry, exit, ...}
   ▼
[api] StrategyController.create
   │
   ▼
[api/consumer] KisYamlDslParser.parse(dslText) → DslStrategyDefinition (AST)
   │ (api 모듈에 KisYamlDslParser bean 등록 — stateless)
   ▼
[api] StrategyService.validateStatic(ast)
   │ - AST visitor — depth·노드 수·indicator 호출 수 카운트
   │ - 키워드 화이트리스트 검증 (UnknownDslSymbolException)
   │ - 한도 위반 시 400 + sealed exception
   │ - complexity 계산 (LOW/MEDIUM/HIGH/CRITICAL)
   ▼
[api] StrategyJpaAdapter.save(strategy + complexity) → DB persist
   ▼
[api] HTTP 200 + body {strategyId, complexity}
   ▼
[frontend] ComplexityBadge 색상 + (CRITICAL 시) 경고 모달

---- (사용자 strategy enable 후) ----

[consumer] SignalGenerator (T-SIG 합류 시) tick or candle event
   │
   ▼
[consumer] strategyEvalPool.submit { JvmStrategyEvaluator.evaluate(strategy, candles, ctx) }
   │ - timeout 100ms (Future.get)
   │ - CPU budget 50ms 측정 (ThreadMXBean)
   ▼
[consumer] AST visitor walk
   │ - IndicatorRef → DslIdentifierMapper → IndicatorSpec
   │ - IndicatorEngine.calculate(spec, candles) → IndicatorResult
   │ - Condition 평가 (operator + lhs/rhs)
   │ - CompositeCondition AND/OR 합성
   ▼
[domain] StrategyEvalResult(signal, strength, reason, evalTimeMs)
   │
   ▼ (timeout 발생 시)
[consumer] StrategyService.autoDisable(strategyId, reason="TIMEOUT")
   │ - DB strategy.disabled_reason='TIMEOUT' update
   │ - (T-RISK 합류 시) risk.violation.v1 publish — Kafka
   │ - (T-NOTIFY 합류 시) Slack 통지 (ADR 0044)
   ▼
[consumer] StrategyEvalMetricRecorder.record(strategyId, evalTimeMs, outcome)
   │ - strategy_eval_metric ring buffer N=100
```

### 6-2. domain sealed class 시그니처 LOCK (PR-36b 책임)

```kotlin
// domain/.../strategy/dsl/ConditionType.kt — KIS raw L23 1:1 port
enum class ConditionType { CROSSOVER, THRESHOLD, BREAKOUT, PATTERN, COMPOSITE }

// domain/.../strategy/dsl/Operator.kt — KIS raw L34
enum class Operator(val symbol: String) {
    GT(">"), LT("<"), GTE(">="), LTE("<="), EQ("=="), NEQ("!="),
    CROSSES_ABOVE("crosses_above"), CROSSES_BELOW("crosses_below"), BREAKS("breaks"),
}

// domain/.../strategy/dsl/LogicalOperator.kt — KIS raw L46
enum class LogicalOperator { AND, OR }

// domain/.../strategy/dsl/ArithmeticOperator.kt — KIS raw L52
enum class ArithmeticOperator(val symbol: String) { ADD("+"), SUB("-"), MUL("*"), DIV("/") }

// domain/.../strategy/dsl/DslNode.kt — sealed class root
sealed class DslNode

// domain/.../strategy/dsl/IndicatorRef.kt — KIS raw L60 (Indicator → IndicatorRef rename)
data class IndicatorRef(val name: String, val params: List<Any>) : DslNode() {
    /** raw L283 get_required_days() port */
    fun requiredDays(): Int = when (name) {
        "macd", "macd_signal", "macd_hist" -> {
            val slow = (params.getOrNull(1) as? Number)?.toInt() ?: 26
            val signal = (params.getOrNull(2) as? Number)?.toInt() ?: 9
            slow + signal
        }
        else -> (params.firstOrNull() as? Number)?.toInt() ?: 0
    }
}

// domain/.../strategy/dsl/LiteralValue.kt — KIS raw L218 (Value → LiteralValue rename)
data class LiteralValue(val value: BigDecimal, val isPercent: Boolean = false) : DslNode() {
    /** isPercent 시 / 100 변환 (raw L228) */
    fun normalized(): BigDecimal = if (isPercent) value.divide(BigDecimal(100)) else value
}

// domain/.../strategy/dsl/ArithmeticExpr.kt — KIS raw L235
data class ArithmeticExpr(val left: DslNode, val operator: ArithmeticOperator, val right: DslNode) : DslNode()

// domain/.../strategy/dsl/Condition.kt — KIS raw L251
data class Condition(
    val left: DslNode,
    val operator: Operator,
    val right: DslNode,
    val conditionType: ConditionType = ConditionType.THRESHOLD,
) : DslNode() {
    /** raw L283-L296 */
    fun requiredDays(): Int {
        fun extract(node: DslNode): Int = when (node) {
            is IndicatorRef -> node.requiredDays()
            is ArithmeticExpr -> maxOf(extract(node.left), extract(node.right))
            else -> 0
        }
        return maxOf(10, extract(left) + 10, extract(right) + 10)
    }
}

// domain/.../strategy/dsl/CompositeCondition.kt — KIS raw L283
data class CompositeCondition(
    val left: DslNode,
    val operator: LogicalOperator,
    val right: DslNode,
) : DslNode() {
    fun requiredDays(): Int = when {
        left is Condition && right is Condition -> maxOf(left.requiredDays(), right.requiredDays())
        else -> 10
    }
}

// domain/.../strategy/dsl/DslStrategyDefinition.kt — KIS raw L555 (StrategyDefinition → DslStrategyDefinition rename)
data class DslStrategyDefinition(
    val name: String,
    val nameKo: String,
    val buyCondition: DslNode?,
    val sellCondition: DslNode?,
    val params: Map<String, Any> = emptyMap(),
)

// domain/.../strategy/StrategyDefinition.kt — magicJar 확장 wrapper (ADR 0022 §2-2)
data class StrategyDefinition(
    val dsl: DslStrategyDefinition,         // KIS 호환 inner
    val universe: Universe,                  // magicJar 확장
    val tf: Timeframe = Timeframe.D1,
    val timeFilter: TimeFilter? = null,
    val sizing: Sizing? = null,
    val trailingStop: BigDecimal? = null,
    val fixedStopLoss: BigDecimal? = null,
)

// domain/.../strategy/Strategy.kt — DB 영속 entity 짝
data class Strategy(
    val id: Long,
    val userId: Long,
    val definition: StrategyDefinition,
    val complexity: StrategyComplexity,
    val disabledReason: StrategyDisabledReason = StrategyDisabledReason.NULL,
    val lastDisabledAt: Instant? = null,
    val evalMetricSummary: Map<String, Any>? = null,
)

// domain/.../strategy/StrategyComplexity.kt — ADR 0047 §2-1-B
data class StrategyComplexity(
    val astDepth: Int,
    val nodeCount: Int,
    val indicatorCallCount: Int,
    val estimatedEvalTimeMs: Int,
    val complexityClass: ComplexityClass,
)

// domain/.../strategy/ComplexityClass.kt
enum class ComplexityClass { LOW, MEDIUM, HIGH, CRITICAL }

// domain/.../strategy/StrategyDisabledReason.kt
enum class StrategyDisabledReason { NULL, TIMEOUT, CPU_BUDGET_EXCEEDED, MANUAL }

// domain/.../strategy/Signal.kt
enum class Signal { BUY, SELL, HOLD }

// domain/.../strategy/StrategyEvalResult.kt
data class StrategyEvalResult(
    val signal: Signal,
    val strength: BigDecimal,            // 0.0~1.0
    val reason: Map<String, Any>,         // {trigger:"CROSSOVER", lhs:"ma(5)", rhs:"ma(20)", lhsValue:..., rhsValue:...}
    val evaluatedAt: Instant,
    val evalTimeMs: Long,
)

// domain/.../strategy/StrategyException.kt — sealed class (ADR 0047 §2-1)
sealed class StrategyException(message: String) : RuntimeException(message) {
    class DslSyntaxException(detail: String) : StrategyException("DSL syntax error: $detail")
    class DslDepthExceededException(depth: Int, limit: Int = 32) : StrategyException("AST depth $depth > limit $limit")
    class DslNodeCountExceededException(count: Int, limit: Int = 10000) : StrategyException("AST node count $count > limit $limit")
    class DslIndicatorCallExceededException(count: Int, limit: Int = 50) : StrategyException("Indicator calls $count > limit $limit")
    class UnknownDslSymbolException(symbol: String) : StrategyException("Unknown DSL identifier: $symbol")
    class StrategyTimeoutException(strategyId: Long, timeoutMs: Long) : StrategyException("Strategy $strategyId timeout ${timeoutMs}ms")
    class InsufficientCandlesException(required: Int, actual: Int) : StrategyException("Insufficient candles: required=$required actual=$actual")
}
```

### 6-3. consumer parser·evaluator 시그니처 LOCK (PR-36a 책임)

```kotlin
// consumer/.../application/strategy/KisYamlDslParser.kt
@Component
class KisYamlDslParser {
    /**
     * KIS Strategy Builder DSL parser (Source: parser.py:L301-L470, License: MIT)
     * 정규식 토크나이저(21 token) + 6단 재귀 하강 (_parse_or → _parse_and → _parse_condition →
     *   _parse_arithmetic → _parse_term → _parse_factor)
     */
    fun parse(dslText: String): DslNode { ... }

    /** ADR 0047 §2-1 static 검증 — depth·노드 수·indicator 호출 수·화이트리스트 */
    fun validateStatic(ast: DslNode): StrategyComplexity { ... }

    /** .kis.yaml 또는 magicJar yaml 파일 → StrategyDefinition (Jackson YAML) */
    fun parseYaml(yamlContent: String, format: YamlFormat): StrategyDefinition { ... }
}

// consumer/.../application/strategy/JvmStrategyEvaluator.kt
@Component
class JvmStrategyEvaluator(
    private val indicatorEngine: IndicatorEngine,                  // ADR 0021 정합
    private val patternRecognizerStub: PatternRecognizerStub,      // T-PAT 합류 시 PatternRecognizer로 교체
    private val identifierMapper: DslIdentifierMapper,
    @Qualifier("strategyEvalPool") private val evalPool: ThreadPoolTaskExecutor,
    private val metricRecorder: StrategyEvalMetricRecorder,
    private val strategyService: StrategyService,                  // autoDisable 호출용
    @Value("\${magicjar.dsl.eval.timeout-ms:100}") private val timeoutMs: Long,
    @Value("\${magicjar.dsl.cpu-budget-ms:50}") private val cpuBudgetMs: Long,
) {
    fun evaluate(strategy: Strategy, candles: List<Candle>, ctx: EvalContext): StrategyEvalResult { ... }
}

// consumer/.../application/strategy/DslIdentifierMapper.kt
@Component
class DslIdentifierMapper {
    /** spec §5-5 LOCK — 자체 추론 0 */
    fun toIndicatorSpec(ref: IndicatorRef): IndicatorSpec { ... }
    fun extractValue(result: IndicatorResult, ref: IndicatorRef): BigDecimal { ... }
}

// consumer/.../application/strategy/PatternRecognizerStub.kt — T-PAT 미진입 1차 stub
@Component
@ConditionalOnMissingBean(name = ["patternRecognizer"])  // T-PAT 머지 시 진짜 bean이 우선
class PatternRecognizerStub {
    fun recognize(name: String, candles: List<Candle>): Boolean = false
    fun consecutive(direction: String, candles: List<Candle>): Int = 0
    // EvalResult.HOLD(reason="T-PAT not yet implemented")
}

// consumer/.../application/strategy/StrategyEvalPoolConfig.kt — ADR 0047 §2-3
@Configuration
class StrategyEvalPoolConfig {
    @Bean("strategyEvalPool")
    fun strategyEvalPool(
        @Value("\${magicjar.dsl.eval-pool.size:4}") poolSize: Int,
        @Value("\${magicjar.dsl.eval-pool.queue:100}") queueSize: Int,
    ): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = poolSize
        maxPoolSize = poolSize
        queueCapacity = queueSize
        setThreadNamePrefix("strategy-eval-")
        setRejectedExecutionHandler(java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy())
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(2)
        initialize()
    }
}
```

### 6-4. REST endpoint 시그니처 LOCK (PR-36b 책임)

```kotlin
// api/.../controller/StrategyController.kt
@RestController
@RequestMapping("/api/strategy")
@Tag(name = "Strategy", description = "전략 DSL CRUD + 검증 + KIS .kis.yaml import/export")
class StrategyController(
    private val strategyService: StrategyService,
    private val kisYamlDslParser: KisYamlDslParser,
) {

    @PostMapping
    @Operation(summary = "전략 등록", description = "텍스트 DSL 1줄을 등록합니다. ADR 0047 정합 — static 검증 후 complexity 응답 반환.")
    fun create(@RequestBody req: CreateStrategyRequest): CreateStrategyResponse = ...

    @GetMapping("/{id}")
    @Operation(summary = "전략 조회", description = "format=magicjar(default) 또는 format=kis-yaml(KIS 호환 export, 확장 키워드 comment fallback).")
    fun get(@PathVariable id: Long, @RequestParam(defaultValue = "magicjar") format: String): StrategyResponse = ...

    @PostMapping("/validate")
    @Operation(summary = "DSL 식 검증 (등록 전 미리보기)", description = "AST depth·노드 수·indicator 호출·complexity 응답.")
    fun validate(@RequestBody req: ValidateRequest): StrategyComplexity = ...

    @PostMapping("/import")
    @Operation(summary = "KIS .kis.yaml import", description = "KIS Strategy Builder GUI 산출물 그대로 등록.")
    fun import(
        @RequestParam(defaultValue = "kis-yaml") format: String,
        @RequestPart file: MultipartFile,
    ): CreateStrategyResponse = ...

    @PutMapping("/{id}/enable")
    @Operation(summary = "auto-disabled 전략 다시 활성화", description = "사용자가 식 수정 후 명시 활성화.")
    fun enable(@PathVariable id: Long): StrategyResponse = ...

    @GetMapping("/{id}/eval-metric")
    @Operation(summary = "마지막 N=20회 평가 metric 조회", description = "auto-disable 사유 추적용.")
    fun getEvalMetric(@PathVariable id: Long): EvalMetricSummaryDto = ...
}

data class CreateStrategyRequest(
    val name: String,
    val nameKo: String? = null,
    val entry: String,                        // 텍스트 DSL — 등록 시 KisYamlDslParser.parse()
    val exit: String,
    val universe: UniverseDto = UniverseDto.kospi(),
    val tf: String = "D1",
    val timeFilter: TimeFilterDto? = null,
    val sizing: SizingDto? = null,
    val trailingStop: BigDecimal? = null,
    val fixedStopLoss: BigDecimal? = null,
)

data class CreateStrategyResponse(
    val strategyId: Long,
    val complexity: StrategyComplexityDto,
)
```

### 6-5. Flyway V26·V27 (PR-36b 책임 — ADR 0047 §2-6 정합)

```sql
-- api/src/main/resources/db/migration/V26__strategy_disabled_reason.sql
ALTER TABLE strategy
    ADD COLUMN IF NOT EXISTS disabled_reason VARCHAR(32),
    ADD COLUMN IF NOT EXISTS last_disabled_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS eval_metric_summary JSONB;

COMMENT ON COLUMN strategy.disabled_reason IS 'NULL·TIMEOUT·CPU_BUDGET_EXCEEDED·MANUAL — ADR 0047 §2-2-D';
COMMENT ON COLUMN strategy.eval_metric_summary IS 'last N evaluations summary (avg ms, timeout count, cpu_violations) — ring buffer summary';

-- V27__strategy_eval_metric.sql
CREATE TABLE strategy_eval_metric (
    id BIGSERIAL PRIMARY KEY,
    strategy_id BIGINT NOT NULL REFERENCES strategy(id) ON DELETE CASCADE,
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    eval_time_ms INTEGER NOT NULL,
    cpu_time_ms INTEGER,
    outcome VARCHAR(32) NOT NULL,    -- OK·TIMEOUT·CPU_EXCEEDED·UNKNOWN_SYMBOL·SYNTAX_ERROR
    error_msg TEXT
);

CREATE INDEX idx_strategy_eval_metric_strategy_id_evaluated_at
    ON strategy_eval_metric(strategy_id, evaluated_at DESC);

COMMENT ON TABLE strategy_eval_metric IS 'ADR 0047 §2-6 — 마지막 N=100건 ring buffer (별도 cron retention)';
```

### 6-6. Kafka 영향

본 PR-35·PR-36 Kafka 영향 0건. (T-RISK·T-NOTIFY 합류 시점에 `risk.violation.v1` payload type 확장 — `STRATEGY_TIMEOUT`·`STRATEGY_CPU_BUDGET_EXCEEDED`·`EVAL_POOL_OVERLOAD` 추가. 본 PR 비범위.)

### 6-7. SoT 변경 시 처리

본 spec(`pr-35-w2-t-dsl-design.md` + `pr-35-w2-t-dsl-plan.md` + `pr-35-dispatch-spec.md` + `pr-35-conflict-matrix.md` + `pr-35-kis-dsl-raw-verification.md`)이 머지된 후 sub-agent 진행 중 시그니처 변경 필요 시:
1. sub-agent 즉시 중단
2. orchestrator → architect 디스패치
3. architect spec 갱신 → 양쪽 sub-agent 재개

본 PR-35 작업 중엔 spec 변경 0건 의무.

---

## §7. 충돌 행렬 (modify-shared 사전 점검)

별도 `pr-35-conflict-matrix.md` 참조 (요약):

| 파일 | PR-36a strategy-engine | PR-36b backend-core | 결론 |
|---|---|---|---|
| `domain/.../strategy/*` | (read-only) | **신규 add-only** (15+ 파일) | 0 |
| `consumer/.../application/strategy/*` | **신규 add-only** (7 파일) | (touch 0) | 0 |
| `api/.../controller/StrategyController.kt` | (touch 0) | **신규 add-only** | 0 |
| `api/.../application/strategy/StrategyService.kt` | (touch 0) | **신규 add-only** | 0 |
| `api/.../persistence/strategy/*` | (touch 0) | **신규 add-only** | 0 |
| `api/src/main/resources/db/migration/V26·V27` | (touch 0) | **신규 add-only** | 0 |
| `api/build.gradle.kts` | **수정 가능 — `:consumer` dep 추가** | (touch 0) | strategy-engine 단독 LOCK |
| `application.yml` (3 모듈) | (touch 0) | **수정 가능 — DSL env mapping 추가** | backend-core 단독 LOCK |
| `.env.example` | (touch 0) | **수정 가능 — 10 변수 추가 (add-only insert)** | backend-core 단독 LOCK |
| `CHANGELOG.md` | **본인 row in-place edit (작성: strategy-engine)** | **본인 row in-place edit (작성: backend-core)** | 0 (ADR 0050 정합) |

**결론**: **시나리오 A 적극 병렬 권장 (T-IND 정합)** — modify-shared 3건이지만 모두 단독 LOCK으로 충돌 0. add-only 100%. orchestrator는 PR-35 머지 즉시 PR-36a·PR-36b 동시 background 발사.

---

## §8. 함정·운영 가드 (sub-agent 의무 인지)

### 8-1. ADR 0051 — Repository default fun 함정

PR-36b backend-core sub-agent는 **`StrategyRepository`·`StrategyEvalMetricRepository` interface body fun 0건** 의무. body 가진 helper 필요 시 → `StrategyJpaAdapter` private fun 또는 `StrategyService` 위치 (헌법 §6 ADR 0051 §정합).

### 8-2. KIS-first raw fetch (헌법 §3)

PR-36a strategy-engine sub-agent는 KisYamlDslParser 구현 전 다음 raw fetch 의무:

```bash
curl -sL https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/strategy_builder/strategy_core/dsl/parser.py > /tmp/kis-parser.py
wc -l /tmp/kis-parser.py  # 664 lines 검증
```

각 클래스 구현 시:
- 클래스명 → Kotlin 클래스명 (rename 정합 §6-2)
- token patterns 정규식 → 1:1 정합 (`pr-35-kis-dsl-raw-verification.md` §2-3 LOCK)
- _parse_xxx 6단 재귀 하강 우선순위 → 정합
- ConditionType 결정 룰 → raw L361-L390 정합
- get_required_days() → raw L283-L296 정합
- DSL 식별자 → IndicatorSpec 매핑 → spec §5-5 LOCK
- 자체 추론 0 — 애매한 경우 raw 출처 줄번호 인용 KDoc

### 8-3. BigDecimal 정밀도

DSL `LiteralValue.value: BigDecimal`. KIS Python float (IEEE 754 double) — Kotlin port 시 `BigDecimal` 사용 + `RoundingMode.HALF_EVEN` (banker's rounding) + scale=8. T-IND `IndicatorPoint.values: Map<String, BigDecimal>`과 동일.

### 8-4. T-PAT 미진입 graceful (DoD #17)

`consecutive(up) >= 5`·`pattern("BULLISH_ENGULFING")` 같은 T-PAT 의존 식이 등록될 수 있음. 1차 정책:
- **등록 시**: `KisYamlDslParser.validateStatic()`은 화이트리스트 통과(`consecutive`·`pattern` 식별자 허용) — 등록 자체는 200 OK
- **평가 시**: `JvmStrategyEvaluator`가 `PatternRecognizerStub.recognize()` 호출 → false 또는 0 반환 → `EvalResult.HOLD(reason="T-PAT not yet implemented — strategy will not generate signals")` + log WARN
- T-PAT 머지 후: `PatternRecognizer` 진짜 bean이 우선 (ConditionalOnMissingBean) — 1줄 교체로 활성화

### 8-5. 자연어 변환 W4 이월 LOCK (룰 5 정합)

본 PR-35는 자연어 → DSL 변환 코드 0건. 룰 5 (W2 LLM provider import 0건):

| 위치 | 정책 |
|---|---|
| consumer/.../strategy/ | spring-ai·anthropic·openai import 0건 LOCK |
| api/.../strategy/ | LLM 호출 endpoint 0건 LOCK |
| domain/.../strategy/ | 자연어 관련 sealed class 0건 LOCK |

W4 T-AI 진입 시 `ai-engineer/AINaturalLanguageToDslConverter.kt` 신설 (별도 모듈 또는 ai 패키지) — magicJar `StrategyController.import?format=natural` endpoint 추가 + AnthropicProvider 호출 → text → DSL → KisYamlDslParser.parse → DslStrategyDefinition. 본 PR 비범위.

ADR 0022 §3 + ADR 0026 §2 + ADR 0047 §3-6 (자연어 → DSL 번역 W4 후속) 모두 W4 명시. 신규 ADR 0건.

### 8-6. circular dep 처리 (api → consumer)

PR-36a strategy-engine sub-agent가 `api/build.gradle.kts`에 `:consumer` dep 추가 시 circular 검증 의무 (`./gradlew :api:bootJar`). T-IND PR-32에서 동일 패턴 검증 완료 — circular 0 확인됨. 만약 circular 발견 시 architect 호출 → fallback (api 모듈에 별도 KisYamlDslParser 위치, 코드 복사 — KIS parser.py 1 파일 self-contained라 복사 비용 낮음).

### 8-7. timeout·CPU budget 측정 시 함정

ADR 0047 §2-2-A timeout = `Future.get(100, MILLISECONDS)` 후 `future.cancel(true)` 호출. ThreadMXBean.getThreadCpuTime는 JVM threadId 기반 — strategy-eval-pool thread name prefix `strategy-eval-`로 식별.

`Future.cancel(true)` interrupt만 발동 — InterruptedException 처리는 evaluator 내부 명시 의무 (`Thread.interrupted()` 체크 또는 `InterruptedException` rethrow).

### 8-8. .kis.yaml YAML parser 의존

YAML parsing은 Jackson Dataformat YAML (`com.fasterxml.jackson.dataformat:jackson-dataformat-yaml`) — Spring Boot 3.3 기본 포함. consumer/api 둘 다 사용 OK. domain은 YAML 의존 0 (텍스트 → AST 변환은 consumer 단독 책임).

### 8-9. Graceful Shutdown (ADR 0045 + 0047 §2-8)

strategy-eval-pool은 `setWaitForTasksToCompleteOnShutdown(true)` + `setAwaitTerminationSeconds(2)` 의무 (StrategyEvalPoolConfig). ADR 0045 §2-1 consumer phase 4 직전 합류.

### 8-10. KIS Rate Limit 영향 0

T-DSL은 KIS REST 호출 0건 — IndicatorEngine 호출 (in-process) + DB read만. KIS Rate Limit 영향 없음.

### 8-11. Repository default fun 함정 검증 (ADR 0051)

PR-36b backend-core sub-agent는 다음 grep 자체 검증 의무:

```bash
grep -rE "interface .*Repository.*\{" --include="*.kt" -A 100 api/src/main/kotlin/ | grep -B 5 "fun.*\{" | head -30
# body 가진 fun 0건이어야 함
```

`StrategyRepository`·`StrategyEvalMetricRepository`는 abstract fun만 (Spring Data JPA query inference). helper는 `StrategyService` 또는 `StrategyJpaAdapter` private fun.

### 8-12. KIS DSL parser parse 우선순위 함정 (raw L412-L470)

재귀 하강 파서는 OR < AND < condition < arithmetic(* /) < term(+ -) < factor 우선순위 LOCK. `_parse_or_expression`이 entry point. PR-36a sub-agent가 우선순위 자체 결정 시 KIS와 다른 AST 구조 발생 risk → R3 fixture cross-check 실패. 자체 추론 0 — raw L412-L530 그대로 port 의무.

---

## §9. fitness gap 검증 (사용자 메모리 `feedback_v3_skepticism_no_regression.md` 정합)

> **lens**: "자동매매에 필요한가? v1/v2 잔재 아닌가? 자연어 변환을 W2에 넣는 게 옳은가?"

| 항목 | 판정 | 근거 |
|---|---|---|
| **DSL 텍스트 vs JSON 트리** | **텍스트 LOCK 정합** | KIS-first raw fetch 결과 (`pr-35-kis-dsl-raw-verification.md`) — KIS는 텍스트 표현식. JSON은 자체 추론 위반 + KIS 호환 0. ADR 0022 §2-1 호환성 LOCK. |
| **자연어 변환 W2 vs W4** | **W4 이월 LOCK 정합** | (1) 룰 5 — W1·W2·W3 LLM provider import 0건 (development-rules §1-5 LOCK). (2) ADR 0022 §3 비범위 명시 — "자연어 → DSL 번역 (W4 후속 옵션)". (3) ADR 0026 — AI 옵션 보조 + 사용자 트리거 한정. (4) ADR 0047 §3-6 — DSL syntax v2 W4 후속 합류. (5) tracks-roadmap §3 T-DSL outline 비범위 명시. ★ 5건 권위 정합 — W4 이월 LOCK 의문 0. |
| **신규 ADR 0건 vs 1~3건** | **0건 LOCK 정합** | ADR 0022·0047·0021 implementation 명세로 충분. 헌법 §3 KIS-first 정합 + ADR 0030 SoT 운영 규약 — "새 ADR은 master §10 + tracks-roadmap §3에 위치한 결정만 발행". 본 PR-35는 implementation 명세 — 새 결정 0. T-IND PR-32에서 동일 ADR 0건 결정 + 성공. |
| **R1+R2 통합 vs R1·R2 분리** | **R1+R2 통합 정합** | T-IND PR-32 정합 — 80지표 일괄에서 R1만 분리는 비효율. T-DSL은 11 클래스 + parser + evaluator 동시 발행이 자연스러움. add-only 100% + modify-shared 3건 단독 LOCK으로 완전 병렬 가능. |
| **strategy-eval-pool 4 fixed** | **정합** | ADR 0047 §2-3 결정. 사용자 1명 + 활성 strategy 평균 5~10개 → capacity 충분. CallerRunsPolicy로 backpressure. |
| **timeout 1회 → auto-disable (보수적)** | **정합** | ADR 0047 §2-2-D — 자동매매 본체 fail-safe. 한 strategy hang이 전체 마비를 막아야 함. 사용자 신뢰 우선. |
| **KIS .kis.yaml import 1차** | **정합** | ADR 0022 §2-3 + 사용자 가치 1 (KIS GUI 자산 보존). 호환성 100% — magicJar 자체 형식 강제 폐기. |
| **T-PAT 미진입 stub** | **정합** | T-PAT은 W2 후속 — 의존 차단보다 graceful 우선. PatternRecognizerStub 1줄 교체로 T-PAT 머지 후 즉시 활성화. |
| **AI 호출 0 (룰 5)** | **정합** | T-DSL은 W2 — LLM provider import 0건. 자연어 변환 코드 0건. |

**결론**: T-DSL spec은 자동매매 본체 quant trading의 의사결정 언어 — v1/v2 잔재 0건. 자연어 변환의 W4 이월은 5건 권위 정합 (룰 5 + ADR 0022·0026·0047 + tracks-roadmap). PR-35 ADR 0건 결정은 PR-32 T-IND 패턴 100% 정합 — implementation 명세로 충분.

**post-MVP 후속 (1차 비범위)**:
- W4 T-AI: 자연어 → DSL 변환 (`AINaturalLanguageToDslConverter`, sub-agent ai-engineer 메인). ADR 0022 §3 + ADR 0026 + ADR 0047 §3-6 정합.
- W3 T-RISK·T-NOTIFY: `risk.violation.v1` payload type 확장 (`STRATEGY_TIMEOUT` 등) + Slack 통지 합류.
- W3 T-BT: 백테스트 timeout 1000ms + abort 정책 본격 가동 (ADR 0047 §2-5).
- post-W2: per-thread heap allocation tracking (ADR 0047 §2-2-C, 가칭 ADR 0090+).
- post-MVP: 사용자 정의 indicator (custom indicator 등록, 가칭 ADR 0091+).
- post-MVP: DSL syntax v2 — 자연어 호환 + 추가 키워드 (가칭 ADR 0092+).

---

## §10. 사용자 결재 받을 결정 사항 (PR-35 본문에 명시)

본 spec PR 머지 전 사용자가 결재할 항목:

| # | 항목 | architect 권장 (KIS-first 결과) | 영향 |
|---|------|------|---|
| 1 | **DSL 문법 — JSON vs 자체 언어 vs 텍스트** | **KIS 정합 텍스트 DSL LOCK** (`"ma(5) crosses_above ma(20)"`) — 자체 추론 0 의무 (헌법 §3 KIS-first) + ADR 0022 §2-1 호환성 LOCK | 11 클래스 1:1 port + token patterns + parse 우선순위 모두 raw 정합 |
| 2 | **자연어 변환 W2 vs W4** | **W4 이월 LOCK** — 룰 5 + ADR 0022·0026·0047 + tracks-roadmap 5건 권위 정합 | 본 PR-35는 LLM provider import 0건 + 자연어 코드 0건 |
| 3 | **메인 sub-agent 결정** | **strategy-engine 메인 + backend-core 보조** (T-IND PR-32 정합) | PR-36 디스패치 분배 |
| 4 | **R1+R2 통합 권장 OK?** | **통합 (T-IND 정합)** — DSL 11 클래스 + parser + evaluator 동시 발행이 자연스러움 + add-only 100% 병렬 안전 | PR 개수 (PR-36 = 2 PR 병렬) |
| 5 | **신규 ADR 0건 OK?** | **0건 LOCK** — ADR 0022·0047·0021 implementation 명세 충분. PR-32 T-IND 정합 (ADR 0건 성공 사례) | ADR 인덱스 변경 0 |
| 6 | **1차 operator 우선** | **9 operator + 2 logical + 4 arithmetic = 15종 (KIS LOCK)** — `>`·`<`·`>=`·`<=`·`==`·`!=`·`crosses_above`·`crosses_below`·`breaks` + AND·OR + `+`·`-`·`*`·`/` (raw L34-L57 정합) | static 검증 + evaluator 분기 매트릭스 |
| 7 | **T-IND 의존 인터페이스** | **`IndicatorEngine.calculate(spec, candles)` 단일 진입점** + DSL 식별자 매핑 테이블 (spec §5-5 LOCK) | ADR 0021 §2-2 정합 |
| 8 | **T-PAT 미진입 정책** | **PatternRecognizerStub 1차 stub + EvalResult.HOLD(reason)** — T-PAT 머지 시 1줄 교체 | PR-36a 1 파일 추가 + T-PAT 진입 시 1줄 교체 |
| 9 | **strategy-eval-pool 4 fixed OK?** | **4 fixed (ADR 0047 §2-3 LOCK)** | consumer @Bean |
| 10 | **timeout 1회 auto-disable OK?** | **1회 즉시 disable (ADR 0047 §2-2-D 보수적 fail-safe)** | strategy.disabled_reason DB update |

**사용자 결재 후**: orchestrator가 본 spec PR-35 머지 → PR-36a·PR-36b 동시 background 발사.

---

## §11. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-09 | PR-35 T-DSL 설계 가이드 v1 발행. ADR 0022/0047/0021 implementation 명세 풀어냄 — 신규 ADR 0건 (T-IND PR-32 패턴 정합). domain/strategy/* 15+ sealed class · enum · data class 분할(KIS 11 클래스 1:1 port + 3 rename 정합) + consumer/.../strategy/ 7 파일(KisYamlDslParser·JvmStrategyEvaluator·DslIdentifierMapper·PatternRecognizerStub·StrategyEvalPoolConfig·StrategyEvalMetricRecorder·preset/) + api/.../controller/StrategyController + StrategyService + StrategyJpaEntity·Repository·Adapter + Flyway V26·V27. KIS 텍스트 DSL LOCK (raw verification 정합 — 자체 추론 0). 자연어 변환 W4 이월 LOCK (룰 5 + ADR 0022·0026·0047 + tracks-roadmap 5건 권위 정합). R1+R2 통합 권장(11 클래스 + parser + evaluator 일괄 자연스러움) + R3 PR-37 일괄. 충돌 행렬 시나리오 A 적극 병렬 권장 (modify-shared 3건 단독 LOCK + add-only 100%). fitness gap 검증 — v1/v2 잔재 0건 + 자연어 W4 이월 5건 권위 정합. (architect, opus 4.7 1M) |
