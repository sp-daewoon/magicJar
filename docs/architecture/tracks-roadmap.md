# magicJar 트랙 로드맵

> **선행**: `docs/architecture/master-architecture.md`
> **작성일**: TBD

---

## §1. Wave 구조

| Wave | 범위 | 트랙 ID | AI 호출 | 오프라인 동작 |
|---|---|---|---|---|
| **W1 — Watchlist + 데이터 기반** (오프라인 1순위) | **관심종목 등록·Ingestion (최우선)** + 종목 마스터·시세 fallback·세션·corp action | **T-W1·T-W2·T-S1** + T-MS1·T-IM1·T-CA1 | 0 | 필수 |
| **W2 — 분석 도구** | 80지표·57패턴·DSL·Strategy Builder UI | T-IND·T-PAT·T-DSL·T-SB | 0 | 필수 |
| **W3 — 자동 매매 본체** (룰만) | 백테스트·screener·signal·risk·order 통합·perf | T-BT·T-SC·T-SIG·T-RISK·T-PERF | 0 | 필수 |
| **W4 — AI 옵션 + MCP 서버** | 사용자 트리거 AI 코멘트·기업 분석·MCP server | T-AI·T-MCP | 옵션 (사용자 트리거만) | 필수 (AI 끊겨도 본체 영향 0) |
| **W5 — 사용자 학습 + 일원화** | LWC 확장 시각화·사용자 학습 시나리오·데이터 정합성 검증 | T-LWC·T-LEARN | 0 | 필수 |

**핵심**:
1. **W1 진입 시 T-W1(Watchlist CRUD UI) → T-W2(Ingestion) → T-S1(Symbol Detail) 순서가 최우선** (ADR 0040 정합). 이 흐름이 끝나면 사용자가 종목 등록·차트·시세까지 가능.
2. W1·W2·W3 끝나면 magicJar 룰 본체 풀 가동 — AI 0건. W4·W5는 옵션·마무리.

---

## §2. 트랙 목록표 (총 17 트랙)

| ID | 이름 | 마스터 §위치 | 목표 한 줄 | 선행 | 후행 | Wave | 사이즈 | 담당 |
|---|---|---|---|---|---|---|---|---|
| **T-W1** ★ | **Watchlist CRUD (UI + REST + DB)** | §D.15 | 관심종목 등록 화면(최우선) + REST + Flyway watchlist 테이블 | - | T-W2·T-S1·T-SB | **W1 ★** | M | frontend+backend-core |
| **T-W2** ★ | **Watchlist Ingestion Worker** | §A.2 | consumer 1~5초 polling + KIS WS sub + Yahoo cold-start fetch | T-W1 + ADR 0028·0037·0038 | T-S1·W3 | **W1 ★** | L | market-data+backend-core |
| **T-S1** ★ | **Symbol Detail (현재가·종목상세·차트)** | §A.2·§D.17 | Watchlist row 클릭 → 상세 화면 (현재가 + PER/PBR/시총 + LWC 차트) | T-W2 + ADR 0019·0029 | T-SB·T-O | **W1 ★** | M | frontend+market-data |
| T-MS1 | Market Session + Data Fallback | §A.2·§3.2 | 장 상태 인지·시세 fallback·REJECTED audit | T-W2 | W3 | W1 | M | backend-core+market-data |
| T-IM1 | Instrument Master | §A.1 | KOSPI·KOSDAQ·ETF·ETN universe + 검색 + 13시각 cron sync | - | T-CA1·T-IND·T-SC | W1 | M | backend-core+market-data |
| T-CA1 | Corporate Action & Adjusted Price | §A.3 | 액면분할·증자·배당락 보정 + retroactive 재계산 | T-IM1 | T-BT·T-SC | W1 | M | market-data |
| T-IND | Technical Indicators (80) + Source 추상화 | §B.5 | 80지표 일괄 + `IndicatorSource` plug-in 인터페이스 | T-MS1·T-IM1 | T-PAT·T-DSL·T-SIG | W2 | XL | backend-core+ai-engineer |
| T-PAT | Candlestick Patterns (57) | §B.6 | 57개 패턴 인식 엔진 + 차트 마커 | T-IND | T-DSL·T-SIG | W2 | M | backend-core |
| T-DSL | Strategy DSL | §B.7 | `.kis.yaml` 호환 superset 파서·평가기 | T-IND·T-PAT | T-SB·T-BT·T-SIG | W2 | L | architect+backend-core |
| T-SB | Strategy Builder UI (GUI 블록 빌더) | §B.8 | GUI 블록 빌더 + 발굴 결과 → Watchlist 등록 | T-W1·T-DSL | T-BT | W2 | XL | frontend |
| T-BT | Backtest Engine 강화 | §B.9 | 룩어헤드·세금·수수료·슬리피지 모델 + 성과 카드 | T-CA1·T-DSL | T-SIG·T-PERF | W3 | L | backend-core |
| T-SC | Screener | §C.10 | universe × DSL → 후보+랭킹 + 캐시 | T-IM1·T-CA1·T-DSL | T-SIG | W3 | M | backend-core |
| T-SIG | Signal Engine (룰 매칭 only, AI 호출 0) | §C.11 | Watchlist × 활성 전략 → BUY/SELL/HOLD + 강도 | T-MS1·T-IND·T-PAT·T-DSL·T-SC | T-RISK·T-O·T-PERF | W3 | M | strategy-engine |
| T-RISK | Risk & Position (Guard chain 6단) | §C.13 | PositionLimit·DrawdownStop·Position projection | T-MS1·T-SIG | T-O·T-PERF | W3 | L | backend-core |
| T-O | Order (KIS Order Adapter + multi-tenant + 안전 게이트) | §C.12 | 시그널 → Guard → KisOrderSender → 체결 → audit | T-W2·T-SIG·T-RISK | T-J·T-PERF | W3 | XL | backend-core+market-data |
| T-J | Trading Journal | §D.16 | 모든 주문·체결·시그널·차트 스냅샷 영속 + 사용자 메모 | T-O | T-PERF | W3 | M | backend-core+frontend |
| T-PERF | Performance Loop (오프라인 통계) | §C.14 | 일/주 P&L·MDD·승률 | T-J·T-SIG·T-RISK | W4 | W3 | M | backend-core |
| T-LWC | LWC 확장 + 자체 도형/주석/패턴 | §D.17 | TradingView Advanced 의존 0 + 자체 overlay layer | T-PAT·T-SIG | - | W5 | M | frontend |
| T-AI | AI Option Console (W4) | §D.18 | 사용자 트리거 LLM 호출 — 종목 코멘트·기업 분석·차트 해석·지표 의미 | W3 완료 | T-MCP | W4 | L | ai-engineer+frontend |
| T-MCP | MCP Server expose (W4) | §D.19 | magicJar MCP server + tool 5종 | T-AI | - | W4 | M | architect+backend-core |
| T-LEARN | 사용자 학습 + 데이터 정합성 검증 (W5) | §운영 | 사용자와 데이터 흐름·아키 의도·표시 의미 검증 | W4 완료 | - | W5 | M | architect+사용자 |

---

## §3. 트랙별 outline (8 슬롯: 마스터 위치 + 목표 + 선행/후행 + KIS 흡수 + ADR + 데이터 + 산출물 + 비범위)

### T-MS1 — Market Session + Data Fallback (트랙2 spec/plan 그대로 살림, AI 가정 제거)

- **마스터 §위치**: §A.2·§3.2·§7
- **목표**: 장 상태 인지 FSM + 시세 fallback (live → cache → last-day-close) + REJECTED audit + EGW00202 hotfix + dev 환경 모의투자 정상 동작
- **선행/후행**: 트랙1 / W1·W2·W3 모든 트랙의 전제 (장 상태 인지 의무)
- **오프라인 동작**: 필수 (정적 캘린더 시드 + last-day-close fallback + MockTickGenerator)
- **KIS Strategy Builder 흡수**: 없음 (자체 도메인)
- **ADR**: 0013·0014·0015·0016 (4건 일괄)
- **데이터**: `market_calendar` V10·`order_reject_audit` V11. Redis `quote:last:*`·`session:cache:current`. Kafka `order.rejected.v1`·`market.session.v1`
- **산출물 핵심**: 룰 기반 세션 FSM + REJECTED Audit + last-day-close fallback (AI 의사결정 가정 배제)
- **비범위**: KRX 캘린더 자동 동기화 (정적 시드 1차), 분봉 backfill 강화 (별도 트랙), 알림 통합 (post-MVP)

### T-IM1 — Instrument Master

- **마스터 §위치**: §A.1
- **목표**: KOSPI·KOSDAQ·KONEX·ETF·ETN·우선주 universe + KIS·KRX·ISIN 매핑 + 검색 인덱스. 상장폐지 보존(생존자 편향 차단)
- **선행/후행**: 없음 / T-CA1·T-IND·T-DSL·T-BT·T-SC·T-SIG
- **오프라인 동작**: 필수 (정적 시드 fallback + KIS sync는 옵션)
- **KIS 흡수**: KIS `genfile_*.py` 종목 마스터 다운로더 로직 → Kotlin batch job으로 port
- **ADR**: 0017 · 0039 (Cron 갱신)
- **데이터**: `instrument` (V12) · `instrument_alias`. Redis `instrument:search:*`·`instrument:list:*`. Kafka `instrument.updated.v1`
- **산출물**:
  - `domain/.../instrument/` 도메인 + port
  - `batch/.../job/instrument/InstrumentSyncJob.kt` (cron 17:00)
  - `consumer/.../infrastructure/kis/KisInstrumentMasterAdapter.kt`
  - `api/.../controller/InstrumentController.kt` 보강 (검색 + universe 필터)
  - `frontend/src/components/InstrumentSearch.tsx` 자동완성
- **비범위**: 해외 종목·암호화폐, 뉴스·재무 통합 (post-MVP)

### T-CA1 — Corporate Action & Adjusted Price

- **마스터 §위치**: §A.3
- **목표**: 액면분할·증자·배당·합병 보정 + raw + adjusted 양 컬럼 + retroactive 재계산 (룩어헤드 차단의 전제)
- **선행/후행**: T-IM1 / T-BT·T-SC·T-SIG
- **오프라인 동작**: 필수 (일별 batch + 정적 시드)
- **KIS 흡수**: KIS 종목 정보 변경 + DART 공시 양쪽
- **ADR**: 0018
- **데이터**: `corp_action` (V13) · `candle.close_raw`·`close_adjusted` (V14). Kafka `corp_action.applied.v1`
- **산출물**:
  - `domain/.../instrument/CorpAction.kt`
  - `batch/.../job/corp_action/CorpActionSyncJob.kt`
  - `batch/.../job/corp_action/CandleAdjustmentJob.kt` (retroactive 재계산)
  - api 외부 어댑터 — DART 스크래핑 endpoint
- **비범위**: 외국인·기관 한도 변경 추적 (post-MVP)

### T-IND — Technical Indicators (80 일괄) + Source 추상화

- **마스터 §위치**: §B.5
- **목표**: 80지표 일괄 구현 + **`IndicatorSource` plug-in 인터페이스 의무** — KIS 외에서도 받을 수 있게
- **선행/후행**: T-MS1·T-IM1 / T-PAT·T-DSL·T-SIG
- **오프라인 동작**: 필수 (전부 in-process 계산)
- **KIS 흡수**: `strategy_builder/core/indicators.py` 80개 함수 명세 그대로 — Kotlin port. KisStrategyBuilderSource adapter로 등록
- **ADR**: 0021 (source 추상화 핵심) · 0020 (KIS Strategy Builder 흡수 명세 port)
- **데이터**: Redis `indicator:cache:{code}:{interval}:{ind}:{params}` TTL 5분(분봉)/1일(일봉). Kafka 영향 없음
- **산출물**:
  - `domain/.../indicator/IndicatorSource.kt` (port)
  - `domain/.../indicator/IndicatorEngine.kt` (registered source 라우팅)
  - `consumer/.../application/indicator/source/KisStrategyBuilderSource.kt` (1차 — 80개)
  - `consumer/.../application/indicator/source/NativeIndicatorSource.kt` (자체 — 차츰 추가)
  - `consumer/.../application/indicator/source/TaLibSource.kt` (선택, JNI 또는 HTTP — post-W2)
  - `api/.../controller/IndicatorController.kt` `GET /api/indicators?code=...&type=...&params=...&source=...`
  - 정확성 검증: 단위 테스트 fixture 기반 — 표준 입력에 대한 expected output 비교. cross-check 옵션은 트랙 진입 시 별도 검토.
- **비범위**: 사용자 정의 지표 (post-MVP)

### T-PAT — Candlestick Patterns (57)

- **마스터 §위치**: §B.6
- **목표**: 57개 패턴 인식 엔진 + 차트 마커
- **선행/후행**: T-IND / T-DSL·T-SIG (DSL이 패턴을 조건으로 사용, T-LWC가 시각화 강화)
- **오프라인 동작**: 필수 (in-process 룰)
- **KIS 흡수**: 57개 패턴 명세만 (KIS doc 기반)
- **ADR**: T-PAT 진입 시 발행 (0041 후보 — 57 캔들 패턴 표준, README §12 정합)
- **데이터**: 영속 안 함 (실시간 인식 + 차트 마커만). Redis 캐시 선택
- **산출물**:
  - `domain/.../pattern/` (port·spec·match)
  - `consumer/.../application/pattern/JvmPatternRecognizer.kt`
  - `api/.../controller/PatternController.kt` `GET /api/patterns?code=...&from=...&to=...`
  - `frontend/src/components/chart/PatternMarker.tsx` (T-LWC에서 본격 시각화)
- **비범위**: 사용자 정의 패턴 (post-MVP)

### T-DSL — Strategy DSL

- **마스터 §위치**: §B.7
- **목표**: `.kis.yaml` 호환 + magicJar 확장(다중 TF·시간 필터·종목 그룹·패턴 조건)
- **선행/후행**: T-IND·T-PAT / T-SB·T-BT·T-SIG
- **오프라인 동작**: 필수 (in-process 평가)
- **KIS 흡수**:
  - `.kis.yaml` 포맷 그대로
  - `strategy_core/dsl/parser.py` 11개 클래스 (ConditionType·Operator·LogicalOperator·ArithmeticOperator·Indicator·Value·ArithmeticExpr·Condition·CompositeCondition·StrategyDSLParser·StrategyDefinition) → Kotlin sealed class
  - `strategy_core/registry.py` `@register` → Kotlin annotation + `StrategyRegistry`
  - 10개 프리셋 (strategy_NN_*.py) → Kotlin `PresetStrategies` 등록
- **ADR**: 0022 (Strategy DSL = .kis.yaml superset)
- **데이터**: `strategy` 테이블 본격(V15) · `strategy_dsl_yaml`(raw YAML)
- **산출물**:
  - `domain/.../strategy/` 도메인 + AST + port
  - `consumer/.../application/strategy/KisYamlDslParser.kt`
  - `consumer/.../application/strategy/JvmStrategyEvaluator.kt`
  - `consumer/.../application/strategy/preset/PresetStrategies.kt` (10개)
  - `api/.../controller/StrategyController.kt` CRUD + `POST /api/strategy/validate` + `POST /api/strategy/import?format=kis-yaml`
- **비범위**: 자연어 → DSL 번역 (W4 후속 옵션)

### T-SB — Strategy Builder UI (GUI 블록 빌더 1차)

- **마스터 §위치**: §B.8
- **목표**: **사용자 명시 — GUI 블록 빌더 1차** (지표 5종 + 진입/청산 1줄 골격) + yaml export/import 동시
- **선행/후행**: T-DSL / T-BT
- **오프라인 동작**: 필수 (frontend in-browser)
- **KIS 흡수**: KIS `frontend/` (Next.js) 흡수 안 함. UX 컨셉(블록·드래그·미리보기) 참고만
- **ADR**: 0009 (frontend SPA) · 0040 (관심종목 우선 UI 흐름)
- **데이터**: 없음 (read·write는 T-DSL의 `strategy` 테이블)
- **산출물**:
  - `frontend/src/pages/Strategy/Builder.tsx` — **GUI 블록 빌더 1차** (드래그·드롭 + 지표 picker + 연산자 + 논리 조합 + 파라미터)
  - `frontend/src/components/strategy/{ConditionEditor,IndicatorPicker,LogicComposer,YamlExportImport}.tsx`
  - `frontend/src/components/strategy/BacktestPreview.tsx` (T-BT와 결합)
  - **YAML export/import 동시 지원** — GUI에서 만든 전략을 .kis.yaml 다운로드 + .kis.yaml 업로드 → GUI 블록 자동 재구성
  - 2차 (post-W2): 풀 GUI (모든 80지표 picker · 패턴 picker · 다중 TF · 시간 필터 GUI)
- **비범위**: 다중 사용자 공유 (post-MVP)

### T-BT — Backtest Engine 강화

- **마스터 §위치**: §B.9
- **목표**: 룩어헤드 차단·세금·수수료·슬리피지 모델 + 성과 카드 (Sharpe·Sortino·MDD·CAGR·승률·평균 수익/손실·turnover)
- **선행/후행**: T-CA1·T-DSL / T-SIG·T-PERF
- **오프라인 동작**: 필수 (영속 candle만 있으면)
- **KIS 흡수**: KIS는 백테스트 엔진 미공개. magicJar 자체 (현행 BacktestRunJob 확장)
- **ADR**: T-BT 진입 시 발행 (0042 후보 — Backtest 표준, README §12 정합)
- **데이터**: `backtest_run` 현행 + `backtest_summary` 보강
- **산출물**:
  - `batch/.../job/backtest/BacktestRunJob.kt` v2 (T-CORP-ACTION adjusted 사용)
  - `domain/.../perf/PerformanceMetrics.kt` + port
  - `api/.../controller/BacktestController.kt` 보강
  - `frontend/src/pages/Backtest/index.tsx` 차트 + 통계
- **비범위**: monte carlo simulation (post-MVP)

### T-SC — Screener

- **마스터 §위치**: §C.10
- **목표**: universe × DSL 조건식 → 후보 + 랭킹. 5분 캐시. 일별 batch + 분봉 실시간 두 모드
- **선행/후행**: T-IM1·T-CA1·T-DSL / T-SIG
- **오프라인 동작**: 필수 (영속 candle + 룰만)
- **KIS 흡수**: 자체 — KIS는 screener 부재
- **ADR**: T-SC 진입 시 발행 (0043 후보 — Screener 표준, README §12 정합)
- **데이터**: `screener_query`·`screener_result` (V16). Redis `screener:result:{queryHash}` TTL 5분. Kafka `screener.result.v1`
- **산출물**:
  - `domain/.../screener/` 도메인
  - `consumer/.../application/screener/JvmScreenerEngine.kt` (분봉 실시간)
  - `batch/.../job/screener/DailyScreenerJob.kt` (일별 batch)
  - `api/.../controller/ScreenerController.kt`
  - `frontend/src/pages/Screener/index.tsx`
- **비범위**: 사용자 정의 universe (1차는 KOSPI·KOSDAQ·KOSPI200)

### T-SIG — Signal Engine (룰 매칭 only, AI 호출 0)

- **마스터 §위치**: §C.11
- **목표**: watch list × 활성 전략 → BUY/SELL/HOLD + 강도 + reason. **AI 호출 0**. 시그널 → 주문은 룰 평가 결과 직접
- **선행/후행**: T-MS1·T-IND·T-PAT·T-DSL·T-SC / T-RISK·T-PERF
- **오프라인 동작**: 필수 (in-process 룰)
- **KIS 흡수**: KIS `core/signal.py` Action·Signal·strength → Kotlin
- **ADR**: 0023 (룰 매칭 only — AI 호출 0 명시)
- **데이터**: `signal` 테이블 (V17). Kafka `signal.generated.v1`. Redis `signal:active:{code}` TTL 5분
- **산출물**:
  - `domain/.../signal/`
  - `consumer/.../application/signal/RealtimeSignalGenerator.kt` (tick listen → 활성 전략 평가)
  - `consumer/.../application/signal/BatchSignalGenerator.kt` (T-SC screener 결과 → 후속 전략)
  - `api/.../controller/SignalController.kt`
  - `frontend/src/pages/Dashboard/SignalsPanel.tsx`
- **비범위**: AI 컨설트 (W4 옵션 분리). 사용자 알림 (W5)

### T-RISK — Risk & Position (Guard chain 6단)

- **마스터 §위치**: §C.13
- **목표**: PositionLimit·DrawdownStop·Position projection. Guard chain 6단 합류
- **선행/후행**: T-MS1·T-SIG / T-PERF
- **오프라인 동작**: 필수 (in-process 정책)
- **KIS 흡수**: KIS `core/position_manager.py` 참조
- **ADR**: 0024 (Risk Guard chain 6단)
- **데이터**: `position`·`pnl_daily`·`risk_event` (V18). Kafka `risk.event.v1`
- **산출물**:
  - `domain/.../position/`·`risk/`
  - `consumer/.../application/guard/PositionLimitGuard.kt` (`@Order(250)`)
  - `consumer/.../application/guard/DrawdownStopGuard.kt` (`@Order(280)`)
  - `consumer/.../application/risk/JvmRiskCalculator.kt`
  - `consumer/.../messaging/listener/PositionUpdateListener.kt` (KIS 체결 → position 갱신)
  - `batch/.../job/risk/DailyDrawdownCheckJob.kt`
  - `api/.../controller/{PositionController,RiskController}.kt`
  - `frontend/src/pages/{Positions,Risk}/index.tsx`
- **비범위**: VaR·CVaR 정밀 (post-MVP)

### T-PERF — Performance Loop (오프라인 통계 + Journal Auto-screenshot)

- **마스터 §위치**: §C.14·§D.15
- **목표**: 일/주 단위 P&L·MDD·승률 통계 + 매매일지 자동 차트 스냅샷. **AI 복기는 W4 옵션 분리**
- **선행/후행**: T-SIG·T-RISK / W4
- **오프라인 동작**: 필수 (영속 데이터만)
- **KIS 흡수**: 없음
- **ADR**: T-PERF 진입 시 발행 (0044 후보 — Performance 표준, README §12 정합)
- **데이터**: `pnl_daily` (V18) · `chart_snapshot` 영속 강화. Kafka `journal.recorded.v1`
- **산출물**:
  - `batch/.../job/perf/DailyPerformanceJob.kt` (오프라인 통계만)
  - `consumer/.../messaging/listener/JournalScreenshotTriggerListener.kt`
  - `frontend/src/pages/Journal/index.tsx` (성과 + 자동 스냅샷)
- **비범위**: AI 복기·튠 제안 (W4 트랙 T-AI에서)

### T-LWC — LWC 확장 + 자체 도형/주석/패턴 시각화 (TradingView Advanced 폐기)

- **마스터 §위치**: §D.16
- **목표**: TradingView Advanced 의존 0 + LWC v4 공식 API 한정 + **자체 overlay layer**로 도형·주석·패턴 시각화
- **선행/후행**: T-PAT·T-SIG / -
- **오프라인 동작**: 필수 (frontend in-browser)
- **KIS 흡수**: 없음
- **ADR**: 0025 (LWC 확장 — TradingView Advanced 폐기 명시)
- **데이터**: 없음 (frontend 한정)
- **산출물**:
  - `frontend/src/components/chart/AdvancedChart.tsx` — LWC 확장 main
  - `frontend/src/components/chart/overlay/{ShapeOverlay,AnnotationOverlay,PatternOverlay}.tsx` — Canvas 또는 SVG layer over LWC chart
  - 다중 timeframe 동기 차트 (D + 60m)
  - 패턴 시각화 (T-PAT 통합)
  - 종목 비교 overlay
  - Replay (시점 거슬러 가며 시그널 재생)
- **비범위**: 사용자 정의 그리기 도구 풀세트 (post-MVP — 1차는 사전 정의 도형만)

### T-AI — AI Option Console (W4 — 사용자 트리거 LLM 호출)

- **마스터 §위치**: §D.17
- **목표**: **사용자 버튼 클릭 시** LLM 호출 — 종목 코멘트·기업 분석·차트 해석·지표 의미 설명. **매매 트리거 0**
- **선행/후행**: W3 완료 / T-MCP
- **오프라인 동작**: **필수 (AI 끊겨도 본체 흐름 영향 0)** — frontend는 "AI 응답 없음" 표시 + 본체 데이터는 그대로 표시
- **KIS 흡수**: 없음
- **ADR**: 0026 (AI 옵션 보조 — 사용자 트리거 한정)
- **데이터**: `ai_comment_log` (V20) · `prompt_template` (V20). Kafka `ai.comment.requested.v1`·`ai.comment.responded.v1`. Redis `ai:prompt:cache:{hash}` TTL 5분
- **산출물**:
  - `domain/.../ai/AiCommentRequest·Response.kt` (코멘트만, 의사결정 X)
  - `domain/.../ai/port/AiCommentProvider.kt`
  - `consumer/.../application/ai/AnthropicCommentAdapter.kt`
  - `consumer/.../application/ai/GeminiCommentAdapter.kt`
  - `consumer/.../application/ai/OpenAiCommentAdapter.kt` (선택)
  - `consumer/.../application/ai/AiCommentDispatcher.kt` (5분 cache + provider toggle)
  - `consumer/.../application/ai/PromptBuilder.kt` (지표·패턴·뉴스 컨텍스트 조립)
  - `api/.../controller/AiCommentController.kt` `POST /api/ai/explain` + STOMP fanout
  - `frontend/src/components/AiCard.tsx` (사용자 클릭 → 응답 표시)
  - `frontend/src/pages/Symbol/SymbolDetail.tsx`에 [AI 분석 요청] 버튼 추가
- **비범위**:
  - **자동 호출** — 사용자 트리거만, 자동 X
  - **AI 의사결정** — 매매 트리거 절대 X
  - 자연어 → DSL 번역 (post-W4)
  - RAG·fine-tune (post-MVP)

### T-MCP — MCP Server expose (W4 — magicJar가 server)

- **마스터 §위치**: §D.18·§1-5
- **목표**: magicJar의 도메인을 MCP protocol로 외부 AI 클라이언트(Claude·Cursor)에 노출. tool 5종
- **선행/후행**: T-AI / -
- **오프라인 동작**: 필수 (read 전용 — DB·캐시 직접 접근, AI provider 의존 0)
- **KIS 흡수**: 없음
- **ADR**: 0027 (MCP 서버 미래 노출)
- **데이터**: `mcp_audit` (V21) — 호출 로그
- **산출물**:
  - `domain/.../mcp/McpToolSpec.kt` · `port/McpToolHandler.kt`
  - `api/.../mcp/` 또는 별도 sidecar 컨테이너 (사용자 결정 §5):
    - `get_candles` tool
    - `run_screener` tool
    - `get_journal` tool
    - `explain_indicator` tool (지표·차트·패턴의 정의·계산식·해석을 reference로 반환 — LLM 호출 X, 정적 catalog)
    - `backtest_strategy` tool
  - tool schema (JSON Schema)
  - 인증: API key (1차) + OAuth (post-MVP)
  - 1차 localhost 한정
- **비범위**:
  - write tool (post-MVP — 안전성 검증 후)
  - 다중 사용자 권한 (post-MVP)

### T-LEARN — 사용자 학습 + 데이터 정합성 검증 (W5)

- **마스터 §위치**: §운영
- **목표**: 사용자와 함께 magicJar 데이터 흐름·아키 의도·표시 의미 검증. 교육 시점. 일원화
- **선행/후행**: W4 완료 / -
- **오프라인 동작**: 필수
- **산출물**:
  - `docs/user-guide/` — 사용자 학습 시나리오 (10개 시나리오)
  - `docs/data-flow-checklist.md` — 데이터 정합성 검증 체크리스트
  - `_workspace/sessions/` — 사용자와의 세션 기록
  - `frontend/src/pages/About/SystemTour.tsx` — 시스템 투어 (각 페이지에서 "이 데이터는 어디서 왔는가" 표시)
- **비범위**: 비디오 튜토리얼 (post-MVP)

---

## §4. 의존성 그래프

```
                      트랙1 (DONE — KIS Order)
                       │
                       ▼
                      T-MS1 (Market Session + Fallback) ★ W1
                       │
       ┌───────────────┼───────────────┐
       │               │               │
       ▼               ▼               ▼
      T-IM1 (Instrument) T-RISK (Risk·Position)  ──┐
       │               ▲                     │
       ▼               │                     │
      T-CA1 (Corp Action) │                     │
       │               │                     │
       ▼               │                     │
       │   ┌── T-IND (Indicators 80 + Source 추상화) ★ W2
       │   │           │
       │   │           ├── T-PAT (Patterns 57)
       │   │           │           │
       │   │           ▼           ▼
       │   │          T-DSL (DSL) ◄─────┘
       │   │           │
       │   │      ┌────┼────┐
       │   │      ▼    ▼    │
       │   │     T-SB   T-BT    │
       │   │  (Builder UI) (Backtest v2)
       │   │      │    │   │
       │   ▼      │    ▼   │
       │  T-SC (Screener) ──┘
       │           │
       └───────────┼─────► T-SIG (Signal Engine — 룰 only) ★ W3
                   │           │
                   │           ▼
                   │          T-RISK (Risk·Position 추가)
                   │           │
                   │           ▼
                   │          T-PERF (Perf Loop + Auto-screenshot)
                   │           │
                   │           ▼
                   │     ┌─ W4 ─┐
                   │     ▼      ▼
                   │   T-AI    T-MCP (MCP server)
                   │  (AI Option Console)
                   │     │
                   │     ▼
                   │    W5 ─ T-LWC (LWC 확장) + T-LEARN (사용자 학습)
```

W4는 W3 완료 후 진입. W5는 옵션 (T-LWC는 W3와 병렬 가능).

---

## §5. 권장 진행 순서 (Wave 그래프 + 병렬 가능 매트릭스)

### §5-1. Wave 진행

| Wave | 트랙 | 기간 추정 | 사용자 검증 게이트 |
|---|---|---|---|
| **W1 — 데이터 기반** | T-MS1 + T-IM1 (병렬) → T-CA1 | ~3주 | 토요일 dogfooding 통과 + 종목 검색·universe 정상 + 차트 adjusted 정확 |
| **W2 — 분석 도구** | T-IND + T-PAT (병렬) → T-DSL → T-SB | ~5주 | 80지표 정확성·차트 overlay + GUI 블록 빌더 yaml export/import 동작 |
| **W3 — 자동 매매 본체** (룰 only) | T-BT + T-SC (병렬) → T-SIG → T-RISK → T-PERF | ~5주 | 시그널 → 주문 e2e 1사이클 + 일별 perf 기록 + AI 호출 0 검증 |
| **W4 — AI 옵션 + MCP** | T-AI → T-MCP | ~3주 | 사용자 클릭 시 AI 응답 + MCP tool 5종 외부 호출 가능 |
| **W5 — 사용자 학습 + LWC 확장** | T-LWC + T-LEARN (병렬) | ~3주 | 사용자 시스템 투어 완료 + 데이터 정합성 체크리스트 통과 |

총 추정: ~19주 (5개월). 단일 인력 + 사용자 검증 시간 + 외부 자원 발급 시간 별도.

**현실 보정**:
- KIS API 호출 한도 도달 시 W1·W3 늘어남
- LLM 비용 부담 시 W4 1주 짧게 (cache 적극)
- 사용자 dogfooding 빈도에 따라 W3 길어질 가능성 (1주 dogfooding 단위 추가)

### §5-2. 병렬 가능 매트릭스

| 동시 가능 조합 | 충돌 위험 | 비고 |
|---|---|---|
| T-MS1 ⊕ T-IM1 | 낮음 | 모듈 영역 분리 (T-MS1=session·order audit, T-IM1=instrument table). Flyway 번호 협의 (V10·V11 vs V12) |
| T-CA1 ⊕ T-IND ⊕ T-PAT | 낮음 | T-CA1=batch·persistence, T-IND=consumer·application(80지표), T-PAT=consumer·application(패턴). 패키지 영역 다름 |
| T-SB ⊕ T-BT | 중간 | 둘 다 frontend·api 영향. T-DSL 산출물(API spec) 확정 후 진입 |
| T-SIG ⊕ T-RISK | 중간 | T-RISK가 T-SIG의 Guard chain에 합류. Order 값(@Order(250) 등)만 협의 |
| T-LWC ⊕ W4 | 낮음 | T-LWC는 frontend 단독, W4는 ai-engineer 단독 |
| T-AI ⊕ T-MCP | 높음 | T-MCP은 T-AI의 LLM provider 추상화 활용. 순차 권장 |

---

## §6. 사이클별 PR 사이즈 가이드 (라운드 분할)

본 마스터는 1번 트랙(20 task XL) 경험 반영해 각 트랙은 R1·R2·R3 라운드 분할 권장. **테스트는 R3 마지막 단계에서 일괄** (사용자 명시 — 코드 먼저 완성, 테스트 마지막).

| 트랙 | R1 (1차) | R2 (2차) | R3 (3차 + 일괄 테스트) |
|---|---|---|---|
| T-MS1 | EGW00202 hotfix + 도메인 + 캘린더 | Guard + audit + resolver | Tick fallback + REST + UI + **테스트 일괄** |
| T-IM1 | 도메인 + Flyway + Sync Job stub | KIS 어댑터 real + 검색 | UI 자동완성 + **테스트 일괄** |
| T-IND | 80지표 일괄 JVM 1차 + Source 추상화 인터페이스 | KisStrategyBuilderSource adapter 등록 + 정확성 cross-check | 캐시 + UI 통합 + **테스트 일괄** (정확성 + 성능 회귀) |
| T-DSL | 파서 + AST | 평가기 + 프리셋 10 | 사용자 import + 검증 + **테스트 일괄** |
| T-SB | GUI 블록 빌더 1차 골격 (지표 5종 + 진입/청산 1줄) | 지표 picker 80개 + 패턴 + 다중 TF | 풀 GUI + **테스트 일괄** |
| T-SIG | 룰 매칭 흐름 | watch list × 활성 전략 + Kafka | UI 패널 + **테스트 일괄** |
| T-AI | Anthropic adapter + ai_comment_log | Gemini·OpenAI dual + 5분 cache | AI Card UI + **테스트 일괄** |

---

## §7. 사용자 결정 필요 사항 (트랙 시작 전)

| # | 결정 | 영향 트랙 |
|---|---|---|
| 1 | T-IM1 진입 시 KIS 종목 마스터 다운로드 endpoint 사용 OK? (KIS 모의 키 보유 중) | T-IM1 |
| 2 | T-IND 1차 80지표 일괄 vs 30개 우선? (사용자 명시 = 80개 일괄) | T-IND |
| 3 | T-DSL 1차 .kis.yaml 호환 우선 vs magicJar 확장 동시? | T-DSL |
| 4 | T-SB 빌더 UI 1차 GUI 블록 빌더 골격 깊이 (지표 5종 vs 80지표 picker) | T-SB |
| 5 | T-AI AI provider 1차 (Anthropic / Gemini free / OpenAI 중) | T-AI |
| 6 | T-MCP MCP server 위치 (api 모듈 endpoint vs 별도 sidecar) | T-MCP |
| 7 | T-RISK PositionLimit·DrawdownStop default (예: 종목당 10%·일일 -3%) | T-RISK |
| 8 | T-LWC LWC 확장 깊이 (1차 사전 정의 도형 vs 풀 그리기 도구) | T-LWC |

---

## §8. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-03 (v1) | 13 트랙 + 5 Wave (architect, opus 4.7 1M) — "AI Decision Layer T-RISK" 가정. 사용자 비전 정정으로 폐기. |
| 2026-05-03 (v2) | **풀 재작성** — 14 트랙 + 5 Wave. AI 트랙(W4 T-AI·T-MCP) 분리. T-LWC LWC 확장(TradingView Advanced 폐기). T-LEARN 사용자 학습 신설. (architect, opus 4.7 1M) |
| 2026-05-07 (PR-2) | §3 outline ADR 매핑 정합 — 옛 0032·0033·0034·0035·0036·0037·0038·0039·0040·0041·0042·0044·0045·0046 → 현행 0013·0014·0015·0016·0017·0018·0020·0021·0022·0023·0024·0025·0026·0027 재매핑. T-PAT/T-BT/T-SC/T-PERF 후속 후보 0048~0051 → 0041~0044 (README §12 정합). (architect) |
