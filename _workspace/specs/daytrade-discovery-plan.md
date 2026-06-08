# plan — 단타 발굴(Discovery) 레이어 구현 라운드 분할

- 작성: 2026-06-09, architect (2026-06-09 최고 관리자 결정 2건 정정 — ① R2 우선 순서 ② 프리셋 권위 V75 시드)
- spec: `_workspace/specs/daytrade-discovery-spec.md` (계약 LOCK 권위)
- ADR: ADR 0117
- 개발 룰 정합: 한 라운드 = 한 layer 묶음 + 한 PR = 한 영역 (development-rules.md §2·§3). R2를 최우선 layer로 배치(데이터 완비 일봉 경로 — 최고 관리자 결정 1).

## 최고 관리자 결정 2건 (2026-06-09 LOCK)

1. **R2(장전 일봉 발굴) 먼저.** 옆 세션이 전 종목 일봉 전수 backfill 중 → 일봉 발굴이 데이터 완비된 견고한 1차 경로. 체결강도(R1 tick)·장중 분봉은 데이터 불완전 전제의 best-effort 보조 → 후순위. R2는 candle read-only라 옆 세션(일봉 ingestion write)과 충돌 최소.
2. **빌트인 단타 프리셋 권위 = V75 Flyway 시드.** 옵션 A(코드 권위) 폐기. `V75__daytrade_discovery_setup_seed.sql`이 `strategy_research_candidate`(V74 스키마)에 row INSERT. 코드는 read만.

## 의존 그래프 (R2 우선)

```
    R2 (V75 시드 ∥ strategy-engine 엔진 → frontend 화면 → qa Visual)   [최우선 — 일봉 read-only]
        │
        ▼
{ R1-A (market-data 체결강도 tick) ∥ R1-B (strategy-engine DSL) }       [보조 best-effort — 분봉/tick 미완비]
        │
        ▼
{ R3-검증 (과최적화 탐지) ∥ R3-측정 (백테스트 alpha 리포트) }            [수익 측정]
```

**R2 R1 의존 0 근거**: R2 일봉 entry_dsl(V75 시드)은 candle 표현 가능 식별자만 사용(`trade_intensity` tick-only 미포함 — spec §3-R2). R2는 R1 산출물 불요 → 단독 선행 성립.

## 라운드별 산출물 + 게이트 (R2 우선 순서)

### R2 — 장전 일봉 발굴 스크리너 + 화면 + V75 시드 (strategy-engine + frontend + qa) — **최우선**
- **브랜치**: `agent/strategy/daytrade-discover-engine` → `agent/frontend/daytrade-discover-ui` → qa Visual
- **파일**:
  - **V75 시드 (권위)**: `api/src/main/resources/db/migration/V75__daytrade_discovery_setup_seed.sql` — `strategy_research_candidate`(V74 스키마)에 단타 setup row INSERT (`DAYTRADE_*` key·sort_order 200~·`ON CONFLICT DO NOTHING`·entry_dsl은 일봉 표현 가능 식별자만 — spec §3-R2 row 계약 LOCK)
  - strategy-engine/api: `POST /api/screener/daytrade-discover`(룰 기반·`interval=1d` 장전 일봉) + `DaytradeDiscoverRequest/ResultDto` + 시드 row read(`ResearchCandidateProvider`) → ScreenerEngine 재사용 평가 + STRATEGY owner 연결
  - frontend: 발굴 화면(후보 TOP N 표시 + 장중 감시 시작 액션 → STRATEGY owner 등록, **watchlist write 0**)
  - qa: Playwright Visual 회귀 (CLAUDE.md §9-2 frontend 핵심 화면 의무)
- **의존**: **없음** — 일봉 candle 영속 read-only, R1(tick) 무관. 옆 세션 일봉 ingestion(write)과 충돌 0.
- **Flyway audit (2026-06-09 LOCK)**: `db/migration/` 최신 = V74. 옆 세션 PR #833 migration 0건 → **V75 가용 확정**. 머지 직전 재확인(옆 세션이 V75 선점 시 다음 가용으로 shift + 본 plan 정정).
- **게이트**: 전체 `./gradlew check` + frontend `pnpm typecheck/build` GREEN. V75 Flyway 적용 후 `strategy_research_candidate` DAYTRADE_* row 조회 검증. watchlist 자동삽입 0 코드 리뷰 게이트. LLM import 0 (개발룰 5 — 발굴은 룰 기반).

### R1-A — 체결강도 KIS raw 흡수 (market-data) — **보조 best-effort**
- **브랜치**: `agent/market-data/tick-trade-intensity`
- **파일**:
  - `domain/.../market/tick/TickEvent.kt` — `tradeIntensity`·`turnoverAccum` nullable add (spec §3-R1)
  - `consumer/.../ws/KisWsMessageParser.kt` — IDX 14·18·19·20 상수 add + `parseH0Stcnt0`·`parseH0Nxcnt0` 흡수 + 게이트 `<21` 보장 + KDoc raw URL
- **전제**: 분봉/tick 데이터 미완비 → graceful 흡수만(결손 시 필드 null·throw 0). 데이터 완비 전제 동작 강제 금지.
- **테스트**: 코드 먼저(개발룰 R4) — 파서 흡수 단위 테스트 1건 권장(CTTR 추출·결손 null·fallback 19/20 비). add-only라 기존 테스트 무변경.
- **의존**: 없음(독립). 단 **우선순위는 R2 뒤**. 옆 세션 PR #833(consumer/ws WS 시드)와 파일 인접 가능 → R2 후 착수로 충돌 risk 최소.
- **게이트**: `:domain:check` + `:consumer:check` GREEN. TickEvent add-only nullable라 호출부 컴파일 무영향.
- **KIS-first 의무**: 파서 KDoc에 raw URL 인용 (헌법 §3 위반 시 reject).

### R1-B — DSL `trade_intensity` 식별자 (strategy-engine) — **보조 best-effort**
- **브랜치**: `agent/strategy/dsl-trade-intensity`
- **파일**: `strategy-engine/.../KisYamlDslParser.kt` — `ALLOWED_IDENTIFIERS` add("trade_intensity") + 평가기 graceful(tick 부재 시 false, throw 금지)
- **의존**: R1-A 선머지 권장(TickEvent 필드 import 시) — 식별자 등록 자체는 독립. rebase 흡수. R2와 무관(R2 일봉 entry_dsl은 `trade_intensity` 미사용).
- **게이트**: `:strategy-engine:check` GREEN. candle-only 경로에서 `trade_intensity` 안전(false) 단위 테스트 1건.

### R3-검증 — 과최적화 탐지 (strategy-engine)
- **브랜치**: `agent/strategy/backtest-overfit-detection`
- **파일**:
  - `domain/.../backtest/BacktestRequest.kt` — `outOfSampleSplit: LocalDate? = null` add (add-only — null이면 현행 단일 run)
  - `domain/.../backtest/OverfitDiagnostics.kt`·`SensitivityRequest.kt` 신규 (spec §3-R3 시그니처 LOCK)
  - strategy-engine 백테스트 엔진 — IS/OOS 2-run 래핑 + 그리드 루프 (ADR 0056 코어 산식 무변경)
- **의존**: 없음(candle 백테스트 — R1·R2 무관). 순번상 R1 보조 라운드 뒤 배치.
- **게이트**: `:domain:check` + `:strategy-engine:check` GREEN. IS/OOS degradation·plateau 판정 단위 테스트.

### R3-측정 — 단타 백테스트 alpha 리포트 (strategy-engine)
- **브랜치**: `agent/strategy/daytrade-backtest-alpha`
- **산출물**: 단타 후보(일봉/5m) 백테스트 run + IS/OOS 분할 + `BacktestPerformance` 수집 → alpha 판정 리포트 `_workspace/strategy/sample-backtest/`
- **의존**: R2(V75 시드 프리셋이 universe·strategy 제공) + R3-검증(과최적화 탐지) 후.
- **게이트**: OOS 기대값>0 && 거래≥100 && MDD≤8% 판정 보고. 체결강도 백테스트 재현 불가 명시(B층 MOCK A/B는 post-R3 backlog).

## 검증 evidence 의무 (메모리 feedback_verification_strict)
- R2 (최우선): V75 적용 후 `strategy_research_candidate` DAYTRADE_* row 조회 + 발굴 endpoint 응답(일봉) + STRATEGY owner 등록 로그(`realtime demand enter: owner=STRATEGY`) + watchlist write 0 확인.
- R1: 실제 KIS WS tick 로그에서 CTTR 추출 확인(MOCK 모드) 또는 파서 단위 테스트 실데이터.
- R3: 백테스트 run DB(`backtest_run`) row + 성과 지표 실측.

## 자원 점유 LOCK (spec §2 재확인)
- **Flyway: V75** (`V75__daytrade_discovery_setup_seed.sql` — 최고 관리자 결정 2 권위). 최신 V74, 옆 세션 PR #833 migration 0건 → V75 가용 확정. 머지 직전 재확인 의무.
- REST: `/api/screener/daytrade-discover` (신규 1). Kafka·STOMP: 0.
- Redis: `screener:discover:*` (선택). ENV: `MAGICJAR_DAYTRADE_*` (선택, add-only).
- DSL 식별자: `trade_intensity` add-only (R1 보조). TickEvent: 2필드 add-only nullable (R1 보조).
