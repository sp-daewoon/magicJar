# plan — 단타 발굴(Discovery) 레이어 구현 라운드 분할

- 작성: 2026-06-09, architect
- spec: `_workspace/specs/daytrade-discovery-spec.md` (계약 LOCK 권위)
- ADR: ADR 0117
- 개발 룰 정합: R1 도메인+시그니처 → R2 어댑터+UI → R3 측정 (development-rules.md §3). 한 PR = 한 영역 (§2).

## 의존 그래프

```
{ R1-A (market-data) ∥ R1-B (strategy-engine) ∥ R3-검증 (strategy-engine) }
        │
        ▼
    R2 (strategy-engine 엔진+프리셋 → frontend 화면 → qa Visual)
        │
        ▼
    R3-측정 (strategy-engine 백테스트 run + alpha 리포트)
```

## 라운드별 산출물 + 게이트

### R1-A — 체결강도 KIS raw 흡수 (market-data)
- **브랜치**: `agent/market-data/tick-trade-intensity`
- **파일**:
  - `domain/.../market/tick/TickEvent.kt` — `tradeIntensity`·`turnoverAccum` nullable add (spec §3-R1)
  - `consumer/.../ws/KisWsMessageParser.kt` — IDX 14·18·19·20 상수 add + `parseH0Stcnt0`·`parseH0Nxcnt0` 흡수 + 게이트 `<21` 보장 + KDoc raw URL
- **테스트**: R1은 코드 먼저(개발룰 R1·R4) — 파서 흡수 단위 테스트 1건 권장(CTTR 추출·결손 null·fallback 19/20 비). add-only라 기존 테스트 무변경.
- **게이트**: `:domain:check` + `:consumer:check` GREEN. 기존 TickEvent 생성자 호출부 add-only nullable라 컴파일 무영향.
- **KIS-first 의무**: 파서 KDoc에 raw URL 인용 (헌법 §3 위반 시 reject).

### R1-B — DSL `trade_intensity` 식별자 (strategy-engine)
- **브랜치**: `agent/strategy/dsl-trade-intensity`
- **파일**: `strategy-engine/.../KisYamlDslParser.kt` — `ALLOWED_IDENTIFIERS` add("trade_intensity") + 평가기 graceful(tick 부재 시 false, throw 금지)
- **의존**: R1-A 선머지 권장(TickEvent 필드 import 시) — 단 식별자 등록 자체는 독립. rebase로 흡수.
- **게이트**: `:strategy-engine:check` GREEN. candle-only 백테스트 경로에서 `trade_intensity` 안전(false) 단위 테스트 1건.

### R3-검증 — 과최적화 탐지 (strategy-engine, R1과 병렬)
- **브랜치**: `agent/strategy/backtest-overfit-detection`
- **파일**:
  - `domain/.../backtest/BacktestRequest.kt` — `outOfSampleSplit: LocalDate? = null` add (add-only — null이면 현행 단일 run)
  - `domain/.../backtest/OverfitDiagnostics.kt`·`SensitivityRequest.kt` 신규 (spec §3-R3 시그니처 LOCK)
  - strategy-engine 백테스트 엔진 — IS/OOS 2-run 래핑 + 그리드 루프 (ADR 0056 코어 산식 무변경)
- **게이트**: `:domain:check` + `:strategy-engine:check` GREEN. IS/OOS degradation·plateau 판정 단위 테스트.

### R2 — 발굴 스크리너 + 화면 (strategy-engine + frontend + qa)
- **브랜치**: `agent/strategy/daytrade-discover-engine` → `agent/frontend/daytrade-discover-ui` → qa Visual
- **파일**:
  - strategy-engine/api: `/api/screener/daytrade-discover` endpoint + `DaytradeDiscoverRequest/ResultDto` + 빌트인 단타 setup 프리셋(`BuiltinResearchCandidates.DEFINITIONS` add — spec §7-2 옵션 A) + STRATEGY owner 연결
  - frontend: 발굴 화면(후보 TOP N 표시 + 장중 감시 시작 액션 → STRATEGY owner 등록, **watchlist write 0**)
  - qa: Playwright Visual 회귀 (CLAUDE.md §9-2 frontend 핵심 화면 의무)
- **의존**: R1 흡수 후.
- **게이트**: 전체 `./gradlew check` + frontend `pnpm typecheck/build` GREEN. watchlist 자동삽입 0 코드 리뷰 게이트. LLM import 0 (개발룰 5 — 발굴은 룰 기반).

### R3-측정 — 단타 백테스트 alpha 리포트 (strategy-engine)
- **브랜치**: `agent/strategy/daytrade-backtest-alpha`
- **산출물**: 후보 A(5m) 백테스트 run + IS/OOS 분할 + `BacktestPerformance` 수집 → alpha 판정 리포트 `_workspace/strategy/sample-backtest/`
- **의존**: R2(프리셋 universe) + R3-검증(과최적화 탐지) 후.
- **게이트**: OOS 기대값>0 && 거래≥100 && MDD≤8% 판정 보고. 체결강도 백테스트 재현 불가 명시(B층 MOCK A/B는 post-R3 backlog).

## 검증 evidence 의무 (메모리 feedback_verification_strict)
- R1: 실제 KIS WS tick 로그에서 CTTR 추출 확인(MOCK 모드) 또는 파서 단위 테스트 실데이터.
- R2: 발굴 endpoint 응답 + STRATEGY owner 등록 로그(`realtime demand enter: owner=STRATEGY`) + watchlist write 0 확인.
- R3: 백테스트 run DB(`backtest_run`) row + 성과 지표 실측.

## 자원 점유 LOCK (spec §2 재확인)
- Flyway: 0 (옵션 A 코드 권위). 옵션 B 시 V75.
- REST: `/api/screener/daytrade-discover` (신규 1). Kafka·STOMP: 0.
- Redis: `screener:discover:*` (선택). ENV: `MAGICJAR_DAYTRADE_*` (선택, add-only).
- DSL 식별자: `trade_intensity` add-only. TickEvent: 2필드 add-only nullable.
