# _legacy/

P1에서 임시 백업한 코드. P2~P3에서 신규 위치로 이전 후 본 디렉토리는 정리.

| 디렉토리 | 출처 | 신규 위치 (예정) | Phase |
|---------|------|----------------|-------|
| `application.yml.bak` | `app/src/main/resources/application.yml` | `api/src/main/resources/application.yml` 등 4개로 분할 | P2 |
| `market-kis/` | `market-data/src/main/kotlin/.../marketdata/kis/` | api 또는 consumer의 `infrastructure/kis/` (Spring 의존 클라이언트는 도메인 외부) | P2 |
| `market-persistence/` | `market-data/src/main/kotlin/.../marketdata/persistence/` | api 또는 batch의 `infrastructure/persistence/` (Spring Data JPA 어댑터) | P2 |
| `market-kis-test/` | 위 kis 테스트 | 위 kis와 동일 위치 | P2 |
| `market-persistence-test/` | 위 persistence 테스트 | 위 persistence와 동일 위치 | P2 |

**도메인 결정 (P1 중 발견)**: domain 모듈은 java-library + Spring AI core만. `@Service`/`@Repository`/`@Transactional`/`JpaRepository` 의존하는 코드는 domain에 둘 수 없음. spec §6의 "market-data/kis-rest → domain/market" 매핑은 **port(인터페이스)만 domain**, **구현체는 앱 infrastructure**로 정정 — hexagonal 정석.

## P1 Task 1.6 (strategy) 추가분

- `strategy-journal/` — 전체 6 파일 (Spring + JPA). P2~P3에서 application layer로 이전 (TradeJournalRecorder는 batch/consumer, Adapter는 api infrastructure).
- `strategy-backtest-app/` — Spring 의존 backtest 파일 (BacktestEngine, BacktestOrderSender). 순수 알고리즘은 domain/strategy/backtest에 보존(BacktestExecutionContext, MetricsCalculator).
- `strategy-dsl-app/` — Spring 의존 DSL evaluator. 순수 컴파일러(Lexer, Parser, AST, Evaluator, IndicatorRegistry, BarStrategyEvaluator, BarEvaluationContext, Token)는 domain에 보존.

**왜 이렇게 분리?** 미래 퀀트 트레이너(quant-trainer) 모듈이 domain/strategy 라이브러리만 의존해 학습할 수 있어야 함. Spring/JPA/orchestration이 도메인에 섞이면 학습 파이프라인이 더러워짐.

## P1 Task 1.10 (구 7 모듈 일괄 삭제) 추가분

| 디렉토리 | 출처 | 신규 위치 (예정) | Phase |
|---------|------|----------------|-------|
| `app/MagicJarApplication.kt` | `app/src/main/kotlin/.../MagicJarApplication.kt` | `api/src/main/kotlin/com/mj/api/MagicJarApiApplication.kt` (또는 비슷한 명) — 4앱은 각자 main | P2 |
| `app-config/` | `app/src/main/kotlin/.../config/` | api·consumer·batch 각자 적절 영역 (보안·AI·JPA·OrderSender) | P2 |
| `app-resources/application*.yml` | `app/src/main/resources/application*.yml` | api/batch/consumer 각 모듈의 application.yml로 분할 (모드별 yml 포함) | P2 |
| `app-resources/logback-spring.xml` | 동일 | 모든 앱 공통 (root resources 또는 각 앱) | P2 |
| `app-resources/db/migration/V0__init.sql` | 동일 | api/batch 중 한 곳에서 Flyway 실행 | P2 |
| `market-data-resources/` | `market-data/src/main/resources/{application-*.yml, kis-samples/, db/migration/}` | api/consumer infrastructure (kis-samples는 통합 테스트 fixture) | P2 |
| `web/kotlin/` | `web/src/main/kotlin/` (controller, websocket, service, api, backtest) | **참고용**. P3 frontend(React)에서 의도 보존 재작성. 영구 위치 없음. | P3 |
| `web-templates/` | `web/src/main/resources/templates/` (Thymeleaf) | **참고용**. React 페이지 디자인 의도 보존. | P3 |
| `web-resources/static/` | `web/src/main/resources/static/{css,js}/` | **참고용** | P3 |
| `web-resources/application-web.yml` | 동일 | api/application.yml로 통합 흡수 | P2 |
