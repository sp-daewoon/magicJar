# magicJar 트랙 로드맵 v2

> **선행**: `docs/architecture/master-architecture.md` v2
> **상태**: DRAFT v2 — 사용자 검토 대기
> **작성일**: 2026-05-03
> **작성자**: architect (opus 4.7 1M)
> **선행 폐기**: 1차안 v1 트랙 로드맵(396줄). "AI Decision Layer T12" 등 AI 결정권 가정 트랙 폐기 후 룰 본체 트랙으로 재정렬.

---

## §1. Wave 구조 (사용자 명시)

| Wave | 범위 | 트랙 ID | AI 호출 | 오프라인 동작 |
|---|---|---|---|---|
| **W1 — 데이터 기반** (오프라인 1순위) | 종목 마스터·시세 fallback·세션·corp action | T2·T3·T4 | 0 | 필수 |
| **W2 — 분석 도구** | 80지표·57패턴·DSL·Strategy Builder UI | T5·T6·T7·T8 | 0 | 필수 |
| **W3 — 자동 매매 본체** (룰만) | 백테스트·screener·signal·risk·order 통합·perf | T9·T10·T11·T12·T14 | 0 | 필수 |
| **W4 — AI 옵션 + MCP 서버** | 사용자 트리거 AI 코멘트·기업 분석·MCP server | T16·T17 | 옵션 (사용자 트리거만) | 필수 (AI 끊겨도 본체 영향 0) |
| **W5 — 사용자 학습 + 일원화** | LWC 확장 시각화·사용자 학습 시나리오·데이터 정합성 검증 | T15·T18 | 0 (W5 본체 트랙) | 필수 |

**핵심**: W1·W2·W3 끝나면 magicJar 룰 본체 풀 가동 — AI 0건. W4·W5는 옵션·마무리.

---

## §2. 트랙 목록표 (총 14 트랙)

| ID | 이름 | 마스터 §위치 | 목표 한 줄 | 선행 | 후행 | Wave | 사이즈 | 담당 |
|---|---|---|---|---|---|---|---|---|
| T1 (DONE) | KIS Order Adapter | §C.12 | 주문 어댑터·multi-tenant·Risk Guard 1차 | - | T2·T12 | (이미) | XL | architect |
| **T2** | Market Session + Data Fallback | §A.2·§3.2 | 장 상태 인지·시세 fallback·REJECTED audit·EGW00202 hotfix | T1 | W1·W3 | **W1 ★** | XL | architect+backend-core+market-data+frontend |
| T3 | Instrument Master | §A.1 | KOSPI·KOSDAQ·ETF·ETN universe + 검색 | - | T4·T5·T10 | W1 | M | backend-core+market-data |
| T4 | Corporate Action & Adjusted Price | §A.3 | 액면분할·증자·배당락 보정 + retroactive 재계산 | T3 | T9·T10 | W1 | M | market-data |
| T5 | Technical Indicators (80) + Source 추상화 | §B.5 | 80지표 일괄 + `IndicatorSource` plug-in 인터페이스 | T2·T3 | T6·T7·T11 | W2 | XL | backend-core+ai-engineer |
| T6 | Candlestick Patterns (57) | §B.6 | 57개 패턴 인식 엔진 + 차트 마커 | T5 | T7·T11 | W2 | M | backend-core |
| T7 | Strategy DSL | §B.7 | `.kis.yaml` 호환 superset 파서·평가기 | T5·T6 | T8·T9·T11 | W2 | L | architect+backend-core |
| T8 | Strategy Builder UI (GUI 블록 빌더) | §B.8 | GUI 블록 빌더 1차 골격 + yaml export/import 동시 | T7 | T9 | W2 | XL | frontend |
| T9 | Backtest Engine 강화 | §B.9 | 룩어헤드·세금·수수료·슬리피지 모델 + 성과 카드 | T4·T7 | T11·T14 | W3 | L | backend-core |
| T10 | Screener | §C.10 | universe × DSL → 후보+랭킹 + 캐시 | T3·T4·T7 | T11 | W3 | M | backend-core |
| T11 | Signal Engine (룰 매칭 only, AI 호출 0) | §C.11 | watch list × 활성 전략 → BUY/SELL/HOLD + 강도 | T2·T5·T6·T7·T10 | T12·T14 | W3 | M | strategy-engine |
| T12 | Risk & Position (Guard chain 6단) | §C.13 | PositionLimit·DrawdownStop·Position projection | T2·T11 | T14 | W3 | L | backend-core |
| T14 | Performance Loop (오프라인 통계) | §C.14·§D.15 | 일/주 P&L·MDD·승률 + journal auto-screenshot | T11·T12 | W4 | W3 | M | backend-core+pyworker |
| T15 | LWC 확장 + 자체 도형/주석/패턴 시각화 | §D.16 | TradingView Advanced 의존 0 + 자체 overlay layer | T6·T11 | - | W5 | M | frontend |
| **T16** | AI Option Console (W4) | §D.17 | 사용자 트리거 LLM 호출 — 종목 코멘트·기업 분석·차트 해석·지표 의미 | W3 완료 | T17 | **W4** | L | ai-engineer+frontend |
| **T17** | MCP Server expose (W4) | §D.18·§1.MCP | magicJar MCP server 노출 + tool 5종 (get_candles·run_screener·get_journal·explain_indicator·backtest_strategy) | T16 | - | **W4** | M | architect+backend-core |
| **T18** | 사용자 학습 + 데이터 정합성 검증 (W5) | §운영 | 사용자와 데이터 흐름·아키 의도·표시 의미 검증 + 체크리스트 | W4 완료 | - | **W5** | M | architect+사용자 |

총 14 트랙 (T1 완료 + T13 폐기 — 1차안 T13은 트랙1 + T12로 흡수 분리).

---

## §3. 트랙별 outline (8 슬롯: 마스터 위치 + 목표 + 선행/후행 + KIS 흡수 + ADR + 데이터 + 산출물 + 비범위)

### T2 — Market Session + Data Fallback (트랙2 spec/plan 그대로 살림, AI 가정 제거)

- **마스터 §위치**: §A.2·§3.2·§7
- **목표**: 장 상태 인지 FSM + 시세 fallback (live → cache → last-day-close) + REJECTED audit + EGW00202 hotfix + dev 환경 모의투자 정상 동작
- **선행/후행**: T1 / W1·W2·W3 모든 트랙의 전제 (장 상태 인지 의무)
- **오프라인 동작**: 필수 (정적 캘린더 시드 + last-day-close fallback + MockTickGenerator)
- **KIS Strategy Builder 흡수**: 없음 (자체 도메인)
- **ADR**: 0032·0033·0034·0035 (4건 일괄)
- **데이터**: `market_calendar` V10·`order_reject_audit` V11. Redis `quote:last:*`·`session:cache:current`. Kafka `order.rejected.v1`·`market.session.v1`
- **산출물 핵심**: 트랙2 spec/plan 22 task 그대로 살림 + AI 의사결정 가정 부분만 제거. spec `docs/superpowers/specs/2026-05-02-track2-market-session-and-data-mgmt-design.md` (969줄), plan `docs/superpowers/plans/2026-05-02-track2-market-session-and-data-mgmt.md` (573줄)
- **비범위**: KRX 캘린더 자동 동기화 (정적 시드 1차), 분봉 backfill 강화 (별도 트랙), 알림 통합 (post-MVP)

### T3 — Instrument Master

- **마스터 §위치**: §A.1
- **목표**: KOSPI·KOSDAQ·KONEX·ETF·ETN·우선주 universe + KIS·KRX·ISIN 매핑 + 검색 인덱스. 상장폐지 보존(생존자 편향 차단)
- **선행/후행**: 없음 / T4·T5·T7·T9·T10·T11
- **오프라인 동작**: 필수 (정적 시드 fallback + KIS sync는 옵션)
- **KIS 흡수**: KIS `genfile_*.py` 종목 마스터 다운로더 로직 → Kotlin batch job으로 port
- **ADR**: 0037
- **데이터**: `instrument` (V12) · `instrument_alias`. Redis `instrument:search:*`·`instrument:list:*`. Kafka `instrument.updated.v1`
- **산출물**:
  - `domain/.../instrument/` 도메인 + port
  - `batch/.../job/instrument/InstrumentSyncJob.kt` (cron 17:00)
  - `consumer/.../infrastructure/kis/KisInstrumentMasterAdapter.kt`
  - `api/.../controller/InstrumentController.kt` 보강 (검색 + universe 필터)
  - `frontend/src/components/InstrumentSearch.tsx` 자동완성
- **비범위**: 해외 종목·암호화폐, 뉴스·재무 통합 (post-MVP)

### T4 — Corporate Action & Adjusted Price

- **마스터 §위치**: §A.3
- **목표**: 액면분할·증자·배당·합병 보정 + raw + adjusted 양 컬럼 + retroactive 재계산 (룩어헤드 차단의 전제)
- **선행/후행**: T3 / T9·T10·T11
- **오프라인 동작**: 필수 (일별 batch + 정적 시드)
- **KIS 흡수**: KIS 종목 정보 변경 + DART 공시 양쪽
- **ADR**: 0038
- **데이터**: `corp_action` (V13) · `candle.close_raw`·`close_adjusted` (V14). Kafka `corp_action.applied.v1`
- **산출물**:
  - `domain/.../instrument/CorpAction.kt`
  - `batch/.../job/corp_action/CorpActionSyncJob.kt`
  - `batch/.../job/corp_action/CandleAdjustmentJob.kt` (retroactive 재계산)
  - pyworker 보강 — DART 스크래핑 endpoint
- **비범위**: 외국인·기관 한도 변경 추적 (post-MVP)

### T5 — Technical Indicators (80 일괄) + Source 추상화

- **마스터 §위치**: §B.5
- **목표**: 80지표 일괄 구현 + **`IndicatorSource` plug-in 인터페이스 의무** — KIS 외에서도 받을 수 있게
- **선행/후행**: T2·T3 / T6·T7·T11
- **오프라인 동작**: 필수 (전부 in-process 계산)
- **KIS 흡수**: `strategy_builder/core/indicators.py` 80개 함수 명세 그대로 — Kotlin port. KisStrategyBuilderSource adapter로 등록
- **ADR**: 0039 (source 추상화 핵심)
- **데이터**: Redis `indicator:cache:{code}:{interval}:{ind}:{params}` TTL 5분(분봉)/1일(일봉). Kafka 영향 없음
- **산출물**:
  - `domain/.../indicator/IndicatorSource.kt` (port)
  - `domain/.../indicator/IndicatorEngine.kt` (registered source 라우팅)
  - `consumer/.../application/indicator/source/KisStrategyBuilderSource.kt` (1차 — 80개)
  - `consumer/.../application/indicator/source/NativeIndicatorSource.kt` (자체 — 차츰 추가)
  - `consumer/.../application/indicator/source/TaLibSource.kt` (선택, JNI 또는 HTTP — post-W2)
  - `api/.../controller/IndicatorController.kt` `GET /api/indicators?code=...&type=...&params=...&source=...`
  - 정확성 검증: pyworker가 KIS `indicators.py`를 그대로 가지고 있어 cross-check 모드 옵션 (dev `MAGICJAR_INDICATOR_CROSSCHECK=true` 시 KIS Python vs Kotlin 비교, drift 0.001% 이상이면 ALERT) — **단 이는 옵션, default off**
- **비범위**: 사용자 정의 지표 (post-MVP)

### T6 — Candlestick Patterns (57)

- **마스터 §위치**: §B.6
- **목표**: 57개 패턴 인식 엔진 + 차트 마커
- **선행/후행**: T5 / T7·T11 (DSL이 패턴을 조건으로 사용, T15가 시각화 강화)
- **오프라인 동작**: 필수 (in-process 룰)
- **KIS 흡수**: 57개 패턴 명세만 (KIS doc 기반)
- **ADR**: T6 진입 시 발행 (0048 후보)
- **데이터**: 영속 안 함 (실시간 인식 + 차트 마커만). Redis 캐시 선택
- **산출물**:
  - `domain/.../pattern/` (port·spec·match)
  - `consumer/.../application/pattern/JvmPatternRecognizer.kt`
  - `api/.../controller/PatternController.kt` `GET /api/patterns?code=...&from=...&to=...`
  - `frontend/src/components/chart/PatternMarker.tsx` (T15에서 본격 시각화)
- **비범위**: 사용자 정의 패턴 (post-MVP)

### T7 — Strategy DSL

- **마스터 §위치**: §B.7
- **목표**: `.kis.yaml` 호환 + magicJar 확장(다중 TF·시간 필터·종목 그룹·패턴 조건)
- **선행/후행**: T5·T6 / T8·T9·T11
- **오프라인 동작**: 필수 (in-process 평가)
- **KIS 흡수**:
  - `.kis.yaml` 포맷 그대로
  - `strategy_core/dsl/parser.py` 11개 클래스 (ConditionType·Operator·LogicalOperator·ArithmeticOperator·Indicator·Value·ArithmeticExpr·Condition·CompositeCondition·StrategyDSLParser·StrategyDefinition) → Kotlin sealed class
  - `strategy_core/registry.py` `@register` → Kotlin annotation + `StrategyRegistry`
  - 10개 프리셋 (strategy_NN_*.py) → Kotlin `PresetStrategies` 등록
- **ADR**: 0040
- **데이터**: `strategy` 테이블 본격(V15) · `strategy_dsl_yaml`(raw YAML)
- **산출물**:
  - `domain/.../strategy/` 도메인 + AST + port
  - `consumer/.../application/strategy/KisYamlDslParser.kt`
  - `consumer/.../application/strategy/JvmStrategyEvaluator.kt`
  - `consumer/.../application/strategy/preset/PresetStrategies.kt` (10개)
  - `api/.../controller/StrategyController.kt` CRUD + `POST /api/strategy/validate` + `POST /api/strategy/import?format=kis-yaml`
- **비범위**: 자연어 → DSL 번역 (W4 후속 옵션)

### T8 — Strategy Builder UI (GUI 블록 빌더 1차)

- **마스터 §위치**: §B.8
- **목표**: **사용자 명시 — GUI 블록 빌더 1차** (지표 5종 + 진입/청산 1줄 골격) + yaml export/import 동시
- **선행/후행**: T7 / T9
- **오프라인 동작**: 필수 (frontend in-browser)
- **KIS 흡수**: KIS `frontend/` (Next.js) 흡수 안 함. UX 컨셉(블록·드래그·미리보기) 참고만
- **ADR**: 0036 ("GUI 블록 빌더 1차" 명시)
- **데이터**: 없음 (read·write는 T7의 `strategy` 테이블)
- **산출물**:
  - `frontend/src/pages/Strategy/Builder.tsx` — **GUI 블록 빌더 1차** (드래그·드롭 + 지표 picker + 연산자 + 논리 조합 + 파라미터)
  - `frontend/src/components/strategy/{ConditionEditor,IndicatorPicker,LogicComposer,YamlExportImport}.tsx`
  - `frontend/src/components/strategy/BacktestPreview.tsx` (T9와 결합)
  - **YAML export/import 동시 지원** — GUI에서 만든 전략을 .kis.yaml 다운로드 + .kis.yaml 업로드 → GUI 블록 자동 재구성
  - 2차 (post-W2): 풀 GUI (모든 80지표 picker · 패턴 picker · 다중 TF · 시간 필터 GUI)
- **비범위**: 다중 사용자 공유 (post-MVP)

### T9 — Backtest Engine 강화

- **마스터 §위치**: §B.9
- **목표**: 룩어헤드 차단·세금·수수료·슬리피지 모델 + 성과 카드 (Sharpe·Sortino·MDD·CAGR·승률·평균 수익/손실·turnover)
- **선행/후행**: T4·T7 / T11·T14
- **오프라인 동작**: 필수 (영속 candle만 있으면)
- **KIS 흡수**: KIS는 백테스트 엔진 미공개. magicJar 자체 (현행 BacktestRunJob 확장)
- **ADR**: T9 진입 시 발행 (0049 후보 — Backtest 표준)
- **데이터**: `backtest_run` 현행 + `backtest_summary` 보강
- **산출물**:
  - `batch/.../job/backtest/BacktestRunJob.kt` v2 (T-CORP-ACTION adjusted 사용)
  - `domain/.../perf/PerformanceMetrics.kt` + port
  - `api/.../controller/BacktestController.kt` 보강
  - `frontend/src/pages/Backtest/index.tsx` 차트 + 통계
- **비범위**: monte carlo simulation (post-MVP)

### T10 — Screener

- **마스터 §위치**: §C.10
- **목표**: universe × DSL 조건식 → 후보 + 랭킹. 5분 캐시. 일별 batch + 분봉 실시간 두 모드
- **선행/후행**: T3·T4·T7 / T11
- **오프라인 동작**: 필수 (영속 candle + 룰만)
- **KIS 흡수**: 자체 — KIS는 screener 부재
- **ADR**: T10 진입 시 발행 (0050 후보 — Screener 표준)
- **데이터**: `screener_query`·`screener_result` (V16). Redis `screener:result:{queryHash}` TTL 5분. Kafka `screener.result.v1`
- **산출물**:
  - `domain/.../screener/` 도메인
  - `consumer/.../application/screener/JvmScreenerEngine.kt` (분봉 실시간)
  - `batch/.../job/screener/DailyScreenerJob.kt` (일별 batch)
  - `api/.../controller/ScreenerController.kt`
  - `frontend/src/pages/Screener/index.tsx`
- **비범위**: 사용자 정의 universe (1차는 KOSPI·KOSDAQ·KOSPI200)

### T11 — Signal Engine (룰 매칭 only, AI 호출 0)

- **마스터 §위치**: §C.11
- **목표**: watch list × 활성 전략 → BUY/SELL/HOLD + 강도 + reason. **AI 호출 0**. 시그널 → 주문은 룰 평가 결과 직접
- **선행/후행**: T2·T5·T6·T7·T10 / T12·T14
- **오프라인 동작**: 필수 (in-process 룰)
- **KIS 흡수**: KIS `core/signal.py` Action·Signal·strength → Kotlin
- **ADR**: 0041 (룰 매칭 only — AI 호출 0 명시)
- **데이터**: `signal` 테이블 (V17). Kafka `signal.generated.v1`. Redis `signal:active:{code}` TTL 5분
- **산출물**:
  - `domain/.../signal/`
  - `consumer/.../application/signal/RealtimeSignalGenerator.kt` (tick listen → 활성 전략 평가)
  - `consumer/.../application/signal/BatchSignalGenerator.kt` (T10 screener 결과 → 후속 전략)
  - `api/.../controller/SignalController.kt`
  - `frontend/src/pages/Dashboard/SignalsPanel.tsx`
- **비범위**: AI 컨설트 (W4 옵션 분리). 사용자 알림 (W5)

### T12 — Risk & Position (Guard chain 6단)

- **마스터 §위치**: §C.13
- **목표**: PositionLimit·DrawdownStop·Position projection. Guard chain 6단 합류
- **선행/후행**: T2·T11 / T14
- **오프라인 동작**: 필수 (in-process 정책)
- **KIS 흡수**: KIS `core/position_manager.py` 참조
- **ADR**: 0042
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

### T14 — Performance Loop (오프라인 통계 + Journal Auto-screenshot)

- **마스터 §위치**: §C.14·§D.15
- **목표**: 일/주 단위 P&L·MDD·승률 통계 + 매매일지 자동 차트 스냅샷. **AI 복기는 W4 옵션 분리**
- **선행/후행**: T11·T12 / W4
- **오프라인 동작**: 필수 (영속 데이터만)
- **KIS 흡수**: 없음
- **ADR**: T14 진입 시 발행 (0051 후보 — Performance 표준)
- **데이터**: `pnl_daily` (V18) · `chart_snapshot` 영속 강화. Kafka `journal.recorded.v1` → pyworker `screenshot.captured.v1`
- **산출물**:
  - `batch/.../job/perf/DailyPerformanceJob.kt` (오프라인 통계만)
  - `consumer/.../messaging/listener/JournalScreenshotTriggerListener.kt`
  - `frontend/src/pages/Journal/index.tsx` (성과 + 자동 스냅샷)
- **비범위**: AI 복기·튠 제안 (W4 트랙 T16에서)

### T15 — LWC 확장 + 자체 도형/주석/패턴 시각화 (TradingView Advanced 폐기)

- **마스터 §위치**: §D.16
- **목표**: TradingView Advanced 의존 0 + LWC v4 공식 API 한정 + **자체 overlay layer**로 도형·주석·패턴 시각화
- **선행/후행**: T6·T11 / -
- **오프라인 동작**: 필수 (frontend in-browser)
- **KIS 흡수**: 없음
- **ADR**: 0044 (TradingView Advanced 폐기 명시)
- **데이터**: 없음 (frontend 한정)
- **산출물**:
  - `frontend/src/components/chart/AdvancedChart.tsx` — LWC 확장 main
  - `frontend/src/components/chart/overlay/{ShapeOverlay,AnnotationOverlay,PatternOverlay}.tsx` — Canvas 또는 SVG layer over LWC chart
  - 다중 timeframe 동기 차트 (D + 60m)
  - 패턴 시각화 (T6 통합)
  - 종목 비교 overlay
  - Replay (시점 거슬러 가며 시그널 재생)
- **비범위**: 사용자 정의 그리기 도구 풀세트 (post-MVP — 1차는 사전 정의 도형만)

### T16 — AI Option Console (W4 — 사용자 트리거 LLM 호출)

- **마스터 §위치**: §D.17
- **목표**: **사용자 버튼 클릭 시** LLM 호출 — 종목 코멘트·기업 분석·차트 해석·지표 의미 설명. **매매 트리거 0**
- **선행/후행**: W3 완료 / T17
- **오프라인 동작**: **필수 (AI 끊겨도 본체 흐름 영향 0)** — frontend는 "AI 응답 없음" 표시 + 본체 데이터는 그대로 표시
- **KIS 흡수**: 없음
- **ADR**: 0046
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

### T17 — MCP Server expose (W4 — magicJar가 server)

- **마스터 §위치**: §D.18·§1-5
- **목표**: magicJar의 도메인을 MCP protocol로 외부 AI 클라이언트(Claude·Cursor)에 노출. tool 5종
- **선행/후행**: T16 / -
- **오프라인 동작**: 필수 (read 전용 — DB·캐시 직접 접근, AI provider 의존 0)
- **KIS 흡수**: 없음
- **ADR**: 0045
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

### T18 — 사용자 학습 + 데이터 정합성 검증 (W5)

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
                      T1 (DONE — KIS Order)
                       │
                       ▼
                      T2 (Market Session + Fallback) ★ W1
                       │
       ┌───────────────┼───────────────┐
       │               │               │
       ▼               ▼               ▼
      T3 (Instrument) T12 (Risk·Position)  ──┐
       │               ▲                     │
       ▼               │                     │
      T4 (Corp Action) │                     │
       │               │                     │
       ▼               │                     │
       │   ┌── T5 (Indicators 80 + Source 추상화) ★ W2
       │   │           │
       │   │           ├── T6 (Patterns 57)
       │   │           │           │
       │   │           ▼           ▼
       │   │          T7 (DSL) ◄─────┘
       │   │           │
       │   │      ┌────┼────┐
       │   │      ▼    ▼    │
       │   │     T8   T9    │
       │   │  (Builder UI) (Backtest v2)
       │   │      │    │   │
       │   ▼      │    ▼   │
       │  T10 (Screener) ──┘
       │           │
       └───────────┼─────► T11 (Signal Engine — 룰 only) ★ W3
                   │           │
                   │           ▼
                   │          T12 (Risk·Position 추가)
                   │           │
                   │           ▼
                   │          T14 (Perf Loop + Auto-screenshot)
                   │           │
                   │           ▼
                   │     ┌─ W4 ─┐
                   │     ▼      ▼
                   │   T16    T17 (MCP server)
                   │  (AI Option Console)
                   │     │
                   │     ▼
                   │    W5 ─ T15 (LWC 확장) + T18 (사용자 학습)
```

W4는 W3 완료 후 진입. W5는 옵션 (T15는 W3와 병렬 가능).

---

## §5. 권장 진행 순서 (Wave 그래프 + 병렬 가능 매트릭스)

### §5-1. Wave 진행

| Wave | 트랙 | 기간 추정 | 사용자 검증 게이트 |
|---|---|---|---|
| **W1 — 데이터 기반** | T2 + T3 (병렬) → T4 | ~3주 | 토요일 dogfooding 통과 + 종목 검색·universe 정상 + 차트 adjusted 정확 |
| **W2 — 분석 도구** | T5 + T6 (병렬) → T7 → T8 | ~5주 | 80지표 정확성·차트 overlay + GUI 블록 빌더 yaml export/import 동작 |
| **W3 — 자동 매매 본체** (룰 only) | T9 + T10 (병렬) → T11 → T12 → T14 | ~5주 | 시그널 → 주문 e2e 1사이클 + 일별 perf 기록 + AI 호출 0 검증 |
| **W4 — AI 옵션 + MCP** | T16 → T17 | ~3주 | 사용자 클릭 시 AI 응답 + MCP tool 5종 외부 호출 가능 |
| **W5 — 사용자 학습 + LWC 확장** | T15 + T18 (병렬) | ~3주 | 사용자 시스템 투어 완료 + 데이터 정합성 체크리스트 통과 |

총 추정: ~19주 (5개월). 단일 인력 + 사용자 검증 시간 + 외부 자원 발급 시간 별도.

**현실 보정**:
- KIS API 호출 한도 도달 시 W1·W3 늘어남
- LLM 비용 부담 시 W4 1주 짧게 (cache 적극)
- 사용자 dogfooding 빈도에 따라 W3 길어질 가능성 (1주 dogfooding 단위 추가)

### §5-2. 병렬 가능 매트릭스

| 동시 가능 조합 | 충돌 위험 | 비고 |
|---|---|---|
| T2 ⊕ T3 | 낮음 | 모듈 영역 분리 (T2=session·order audit, T3=instrument table). Flyway 번호 협의 (V10·V11 vs V12) |
| T4 ⊕ T5 ⊕ T6 | 낮음 | T4=batch·persistence, T5=consumer·application(80지표), T6=consumer·application(패턴). 패키지 영역 다름 |
| T8 ⊕ T9 | 중간 | 둘 다 frontend·api 영향. T7 산출물(API spec) 확정 후 진입 |
| T11 ⊕ T12 | 중간 | T12가 T11의 Guard chain에 합류. Order 값(@Order(250) 등)만 협의 |
| T15 ⊕ W4 | 낮음 | T15는 frontend 단독, W4는 ai-engineer 단독 |
| T16 ⊕ T17 | 높음 | T17은 T16의 LLM provider 추상화 활용. 순차 권장 |

---

## §6. 사이클별 PR 사이즈 가이드 (라운드 분할)

본 마스터는 1번 트랙(20 task XL) 경험 반영해 각 트랙은 R1·R2·R3 라운드 분할 권장. **테스트는 R3 마지막 단계에서 일괄** (사용자 명시 — 코드 먼저 완성, 테스트 마지막).

| 트랙 | R1 (1차) | R2 (2차) | R3 (3차 + 일괄 테스트) |
|---|---|---|---|
| T2 | EGW00202 hotfix + 도메인 + 캘린더 | Guard + audit + resolver | Tick fallback + REST + UI + **테스트 일괄** |
| T3 | 도메인 + Flyway + Sync Job stub | KIS 어댑터 real + 검색 | UI 자동완성 + **테스트 일괄** |
| T5 | 80지표 일괄 JVM 1차 + Source 추상화 인터페이스 | KisStrategyBuilderSource adapter 등록 + 정확성 cross-check | 캐시 + UI 통합 + **테스트 일괄** (정확성 + 성능 회귀) |
| T7 | 파서 + AST | 평가기 + 프리셋 10 | 사용자 import + 검증 + **테스트 일괄** |
| T8 | GUI 블록 빌더 1차 골격 (지표 5종 + 진입/청산 1줄) | 지표 picker 80개 + 패턴 + 다중 TF | 풀 GUI + **테스트 일괄** |
| T11 | 룰 매칭 흐름 | watch list × 활성 전략 + Kafka | UI 패널 + **테스트 일괄** |
| T16 | Anthropic adapter + ai_comment_log | Gemini·OpenAI dual + 5분 cache | AI Card UI + **테스트 일괄** |

---

## §7. 사용자 결정 필요 사항 (트랙 시작 전)

| # | 결정 | 영향 트랙 |
|---|---|---|
| 1 | T3 진입 시 KIS 종목 마스터 다운로드 endpoint 사용 OK? (KIS 모의 키 보유 중) | T3 |
| 2 | T5 1차 80지표 일괄 vs 30개 우선? (사용자 명시 = 80개 일괄) | T5 |
| 3 | T7 1차 .kis.yaml 호환 우선 vs magicJar 확장 동시? | T7 |
| 4 | T8 빌더 UI 1차 GUI 블록 빌더 골격 깊이 (지표 5종 vs 80지표 picker) | T8 |
| 5 | T16 AI provider 1차 (Anthropic / Gemini free / OpenAI 중) | T16 |
| 6 | T17 MCP server 위치 (api 모듈 endpoint vs 별도 sidecar) | T17 |
| 7 | T12 PositionLimit·DrawdownStop default (예: 종목당 10%·일일 -3%) | T12 |
| 8 | T15 LWC 확장 깊이 (1차 사전 정의 도형 vs 풀 그리기 도구) | T15 |

---

## §8. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-03 (v1) | 13 트랙 + 5 Wave (architect, opus 4.7 1M) — "AI Decision Layer T12" 가정. 사용자 비전 정정으로 폐기. |
| 2026-05-03 (v2) | **풀 재작성** — 14 트랙 + 5 Wave. AI 트랙(W4 T16·T17) 분리. T15 LWC 확장(TradingView Advanced 폐기). T18 사용자 학습 신설. (architect, opus 4.7 1M) |
