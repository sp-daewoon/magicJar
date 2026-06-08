# ADR 0117 — 단타 발굴(Discovery) 레이어 + 체결강도 KIS raw 흡수

- 상태: Proposed (사용자 "발굴 레이어 구축" 결재 — architect spec/plan 발행. 코드 반영 시 Accepted 전환)
- 작성일: 2026-06-09
- 결정자: architect (orchestrator dispatch · 사용자 "돈 버는 것으로 피벗" 후속)
- 관계: ADR 0048 정합(KIS WS owner 분리 — STRATEGY owner 재사용, watchlist 비삽입) · ADR 0056 정합(Backtest 엔진 — IS/OOS·민감도 add-only, 코어 산식 무변경) · ADR 0071 정합·책임분리(사용자 DSL 스크리너 vs 본 ADR 시스템 빌트인 룰 발굴) · ADR 0112 무관(AI 종목발굴 LLM — 본 ADR은 룰 기반·LLM 0) · ADR 0047 정합(DSL sandbox — `trade_intensity` add-only 화이트리스트)
- spec/plan: `_workspace/specs/daytrade-discovery-spec.md` · `_workspace/specs/daytrade-discovery-plan.md` (계약 LOCK 권위)
- 입력 자산: `_workspace/strategy/2026-06-08-daytrade-screener-design.md` (결재된 strategy-engine 제안서)

---

## §1. 컨텍스트 (왜 이 결정이 필요한가)

### 1-1. 발굴 레이어 위치 — "돈 버는 전략 먼저" 피벗 후속

사용자 "돈 버는 것으로 피벗" 선언 후, 단타 수익 스크리너 제안서가 결재됐다. 현 스크리너(`ScreenerEngineImpl.screen`)는 `criteria.strategyId`를 **필수**로 받아 사용자가 미리 저장한 DSL을 universe에 평가한다 (`ScreenerEngineImpl.kt:90`). 즉 "사용자가 전략을 먼저 작성해야" 동작한다.

발굴(Discovery) 레이어는 **"전략을 미리 작성하지 않아도 시스템이 오늘 단타 후보 TOP N을 수치로 제시"**하는 것이다. 이는 새 사용자 진입점이며 ADR 0071의 strategyId-필수 전제를 깨므로 별도 결정 기록 가치가 있다.

### 1-2. 사용자 비전 정합 fitness 검증 (메모리 `feedback_v3_skepticism_no_regression`)

- ★★★ **수익 발굴 자동화** — 사용자가 전략 작성 부담 없이 시스템 빌트인 단타 룰로 후보를 즉시 제시. "돈 버는 전략 먼저" 피벗의 진입 UX.
- ★★ **체결강도 = 단타 핵심 지표 gap 충당** — RVOL·ATR·거래대금은 기존 DSL 조합으로 표현 가능, 체결강도만 시스템에 부재(grep `trade_intensity`/`cttr` → 0건). KIS H0STCNT0[18] CTTR을 파서가 버리고 있을 뿐.
- **v1/v2 잔재 검증**: v1·v2는 수동 종목 등록. 본 발굴은 v3 신규(룰 기반 자동 발굴 + tick 체결강도). 잔재 0.
- **자동매매 결정권 0 검증**: 발굴은 후보 **출발점**(매매 결정 아님). 발굴 결과 DTO에 주문 트리거 필드 0. CLAUDE.md "전략 후보 프리셋은 자동매매 결정을 대신하지 않는다" 1:1.
- **AI 호출 0 검증**: 본 발굴은 룰 기반(KIS raw + DSL) — LLM import 0 의무 (개발룰 5). `/api/strategy-research/ai-discovery`(LLM)와 명확히 구분 — path도 `daytrade-discover`로 분리.
- **mock 합성 0**: 발굴은 instrument·candle 영속 + KIS WS tick 실데이터. mock 금지.

### 1-3. KIS-first raw 인용 (헌법 §3 — 외부 데이터 규격 결정)

본 ADR은 KIS H0STCNT0 신규 필드(CTTR 등)를 흡수하므로 **외부 데이터 규격 결정 → 헌법 §3 raw fetch 의무 발동**. 2026-06-09 `open-trading-api/.../domestic_stock_functions_ws.py` `ccnl_krx()` columns 배열 raw fetch 결과 (자체 추론 0):

| index | 필드 | 의미 |
|---|---|---|
| 14 | `ACML_TR_PBMN` | 누적거래대금(원) |
| 18 | `CTTR` | 체결강도 (KIS 계산값 — 매수/매도 체결량×100) |
| 19 | `SELN_CNTG_SMTN` | 총매도체결수량 (fallback 분모) |
| 20 | `SHNU_CNTG_SMTN` | 총매수체결수량 (fallback 분자) |

raw URL: `https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py`. 파서 흡수 시 KDoc inline 인용 의무.

---

## §2. 결정 (architect 자율 LOCK)

### 2-1. (A) 발굴 진입 모델 — **시스템 빌트인 룰 발굴 채택** (vs strategyId 필수 ScreenerEngine 재사용 only)

발굴은 `BuiltinResearchCandidates` 인프라(`ResearchCandidateDefinition.kt:46`, `isBuiltin=true`, V74 시드 + `ResearchCandidateProvider` 포트)를 단타 setup 프리셋으로 **확장**한다. 신규 도메인/테이블 신설 0 — 기존 빌트인 후보 18종에 단타 setup add-only. 사용자 DSL 전략(`strategy` 테이블)과 별도 테이블 공존(spec §1-3 매트릭스).

### 2-2. (B) 체결강도 — **KIS CTTR(index 18) 직흡수 채택** (vs 자체 계산)

CTTR은 KIS가 이미 계산 전송하는 완성값 → 계산 알고리즘 신설 0. `TickEvent.tradeIntensity: BigDecimal?` add-only(`priceChange`/`changeRate` nullable 패턴 1:1). CTTR 결손 시에만 ENV 게이트로 `SHNU(20)/SELN(19)×100` fallback(분모 0 → 상한 200). DSL 식별자 `trade_intensity` add-only.

### 2-3. (C) 발굴 endpoint — **`POST /api/screener/daytrade-discover` (룰 기반, LLM 0)**

`discover`/`ai-discovery` 단어는 LLM 발굴(`StrategyResearchController.kt:126`)이 점유 → 개념·path 충돌 회피 위해 `daytrade-discover` 명명. 동기 inline 평가(ADR 0071 A-1 패턴 재사용). Kafka·STOMP 신규 0.

### 2-4. (D) owner 분리 — **STRATEGY owner 재사용, watchlist 비삽입 LOCK**

발굴 후보 WS 수요는 `RealtimeDemandOwner.STRATEGY`(`RealtimeSubscriptionRouter.kt:258`, priority 40·TTL 30분)로만 등록. watchlist write 금지(CLAUDE.md 명문). 신규 owner enum 0.

### 2-5. (E) tick-only 백테스트 한계 — **체결강도 백테스트 재현 불가 LOCK + 2층 검증**

CTTR은 과거 candle에 없음 → 백테스트 불가. 수익 검증 = A층(candle 표현 가능: 갭·RVOL·거래대금 백테스트) + B층(체결강도 incremental은 MOCK 페이퍼 A/B). R3는 A층만.

### 2-6. (F) 과최적화 탐지 — **IS/OOS 분할 + 파라미터 민감도 add-only**

`BacktestRequest.outOfSampleSplit: LocalDate? = null` add(null=현행). `OverfitDiagnostics`·`SensitivityRequest/Result` 신규 DTO(spec §3-R3 시그니처 LOCK). ADR 0056 코어 산식 무변경. 판정 LOCK: OOS 기대값>0 && 거래≥100 && MDD≤8%.

---

## §3. 자원 audit (ADR 0102 정합 — main `f0df035b` v3.12.1)

| 자원 | 점유 | 결론 |
|---|---|---|
| Flyway | 0 (코드 권위 옵션 A) / 선택 V75 | 다음 가용 V75 (최신 V74). spec §7-2 사용자 확인 |
| REST | `POST /api/screener/daytrade-discover` 신규 1 | 충돌 0 (`evaluate`/`runs` 점유와 분리) |
| Kafka/STOMP | 0 | — |
| Redis | `screener:discover:*` (선택) | prefix 분리 충돌 0 |
| ENV | `MAGICJAR_DAYTRADE_*` add-only (선택) | 충돌 0 |
| DSL 식별자 | `trade_intensity` add-only | 화이트리스트 미존재 — 충돌 0 |
| TickEvent | `tradeIntensity`·`turnoverAccum` nullable add | 충돌 0 |

---

## §4. 라운드 분할 (plan 권위)

`{R1-A market-data ∥ R1-B strategy-engine ∥ R3-검증 strategy-engine}` → `R2 (strategy+frontend+qa)` → `R3-측정 strategy-engine`. 상세 plan 참조.

---

## §5. 결과 / 트레이드오프

**얻는 것**: 전략 작성 없는 수익 발굴 진입점 · 체결강도 단타 지표 충당(계산 신설 0) · 과최적화 가드. 신규 모듈 0 · 테이블 0(코드 권위) · KIS 호출 증가 0.

**감수**: 체결강도 백테스트 재현 불가(B층 MOCK A/B 보완) · 발굴 후보 STRATEGY WS slot 경합(priority 40으로 보유 구독 보호). time-of-day RVOL·당일 VWAP·ORB는 세션함수 부재로 현 불가 — 수익성 검증 후 별도 ADR(post-R3 backlog).

**롤백**: 모두 add-only — `MAGICJAR_DAYTRADE_DISCOVERY_ENABLED=false`로 endpoint off, DSL 식별자/TickEvent 필드는 미사용 시 무영향.
