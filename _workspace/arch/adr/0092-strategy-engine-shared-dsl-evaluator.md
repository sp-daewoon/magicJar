# ADR 0092: strategy-engine shared DSL evaluator module

- 상태: Accepted
- 작성일: 2026-05-20
- 결정자: architect (협의: strategy-engine, backend-core)

## 맥락(Context)

2026-05-19 main 자동매매 종합 리뷰에서 API 백테스트·스크리너·전략 CRUD가 실제 JVM DSL evaluator와 분리되어 `HOLD` stub 또는 빈 `IndicatorRef("stub")` AST를 사용할 수 있는 P0 결함이 확인되었다.
기존 `JvmStrategyEvaluator`·`KisYamlDslParser`는 consumer 모듈 내부에 있어 api가 직접 의존할 수 없었고, ADR 0004의 api ↔ consumer 직접 의존 금지 때문에 api 단독으로 실제 평가기를 주입하기 어려웠다.
그 결과 사용자는 전략을 등록·검증·평가하는 화면/API를 보지만, 백테스트·스크리너는 실제 매매 조건과 다른 경로를 탈 수 있었다.

## 검토한 대안(Options)

| 대안 | 장점 | 단점 |
|------|------|------|
| A. consumer-only 유지 + api fallback stub | 변경량이 가장 작음 | 리뷰 P0 결함을 유지한다. API 백테스트·스크리너가 실제 자동매매 판단과 계속 분리된다. |
| B. api에 evaluator 복제 | API를 빠르게 실평가 경로로 전환 가능 | evaluator/parser/metric/timeout 정책이 api·consumer에서 중복되어 drift가 재발한다. |
| C. `strategy-engine` 라이브러리 모듈 신설 | api·consumer가 같은 parser/evaluator bean을 공유한다. ADR 0054(pattern-engine), ADR 0083(indicator-impl), ADR 0088(persistence-shared) 패턴과 일관된다. | JVM 모듈 1개가 추가되고 Gradle 의존 그래프가 늘어난다. live candle merge 같은 앱별 decorator는 별도 정리가 필요하다. |

## 결정(Decision)

대안 C를 채택한다. `consumer.application.strategy`에 있던 DSL parser/evaluator/metric/timeout callback 코드를 `strategy-engine` 루트 라이브러리 모듈로 이동하고, api·consumer가 모두 `project(":strategy-engine")`에 의존한다.

`strategy-engine`은 Spring Boot main이 없는 Kotlin/JVM 라이브러리 jar이며, `domain`에만 직접 의존한다. 실제 indicator·pattern 구현은 기존처럼 `indicator-impl`·`pattern-engine` bean을 통해 런타임에 주입된다.

API 전략 CRUD는 이제 `KisYamlDslParser.parse()` + `validateStatic()`으로 실제 AST를 저장한다. `/api/strategy/{id}/evaluate`, BacktestEvaluatorBridge, ScreenerEngineImpl은 `StrategyEvaluatorPort`를 통해 `strategy-engine`의 `JvmStrategyEvaluatorAdapter`를 호출한다. `HoldStrategyEvaluatorStub`은 strategy-engine bean이 없는 테스트 fallback으로만 남긴다.

## 결과(Consequences)

- 긍정: API 등록·검증·평가·백테스트·스크리너와 consumer 실시간 신호 생성이 같은 JVM DSL parser/evaluator를 공유한다.
- 긍정: `HOLD` stub이 운영 기본 경로로 사용될 수 있는 P0 결함을 차단한다.
- 긍정: evaluator timeout auto-disable 콜백은 api `StrategyService`가 `@Primary` 구현체로 받아 DB auto-disable 경로와 연결된다.
- 부정: JVM 모듈이 1개 증가한다. master architecture와 Gradle settings의 모듈 목록을 지속 갱신해야 한다.
- 부정: API screener/evaluate의 캔들 입력은 아직 DB candle repository 기반이다. consumer의 `LiveCandleMergingSource`처럼 Redis `candle:today:{code}:1d`를 indicator 계산 직전에 합류시키는 공용 candle input policy는 후속 P0-2에서 별도 정리해야 한다.
- 후속 과제: `LiveCandleMerger`를 api·consumer 공용 정책으로 승격하거나, `CandleEvaluationSource` 포트를 신설해 당일 진행 중 캔들 합류를 evaluator 입력 전 단계에서 보장한다.
