# v3.12.0 기능 명세 (트랙별 표준 9 항목)

- baseline: v3.12.0
- 작성일: 2026-06-08
- 작성자: backend-core (bot+backend-core@magicjar.local)
- SoT: `docs/architecture/master-architecture.md` + ADR INDEX (`_workspace/arch/adr/README.md`) + ADR 0094 (운영 단계) + ADR 0107·0110·0111·0112·0113·0114 (분할매수·AI 발굴·캔들·주문 집행 사이클)
- 기준: v3.9.0 04-feature-spec.md 전체 이월 + **T-EXEC 신규** (주문 집행 정책 — marketable limit·청산 항상 보장 — ADR 0114) + **T-SCALE 신규** (전략별 포지션 귀속 + 분할매수/평단 exit — ADR 0110·0111) + **T-AIDISC 신규** (AI 종목발굴 Claude CLI bridge — ADR 0112) + **T-SIM 신규** (시나리오 리플레이 샌드박스 — ADR 0107) + ADR 6건 신설(0107·0110·0111·0112·0113·0114) + Flyway 22건(V53~V74)

---

## 🔄 v3.9.0 → v3.12.0 기능 매트릭스 진화 요약 (리팩토링맨 lens)

| 트랙 | v3.9.0 | v3.12.0 변경 |
|------|--------|------------|
| **T-EXEC** ★★ | 0건 (진입 시장가 고정 · 손절 1m 4중 게이트 단일) | **★ v3.12.0 신규 — 진입 marketable limit(EntryPricePolicy 순수 계산기·스프레드 게이트·시장가 fallback 0) + 청산 항상 보장(tick 손절 안전망·position-driven·HOLDING owner evict 금지·익절 LIMIT 선주문) + entry_policy(V72)·min_hold_bars(V73) (ADR 0114)** |
| **T-SCALE** ★★ | 0건 (position user+code 합산 단일) | **★ v3.12.0 신규 — strategy_position 전략별 lot ledger(V64) + scale-in plan + 평단 기준 stop/take(ScaleInExitCalculator) + partial exits(V65) (ADR 0110·0111)** |
| **T-AIDISC** ★★ | 0건 (5 진입점 API provider만) | **★ v3.12.0 신규 — AI 종목발굴 Claude CLI bridge(`claude -p`/local bridge · API key 미사용) + 프리셋 추천(V70) + Core Wrapper 편집(V71) + 발굴 state(V67) + 코멘트 4번째 provider(claude-cli) (ADR 0112)** |
| **T-SIM** ★ | 0건 | **★ v3.12.0 신규 — 시나리오 리플레이 샌드박스(scenario_* 격리 namespace · live FK 0 — ADR 0107)** |
| **T-AI** | 5 진입점 × 3 provider(Anthropic·Gemini·OpenAI) | + **claude-cli 4번째 provider** (종목상세 차트해석 — 구독 기반·비용 0·prompt_template type=chart 무변경) |
| **T-MARKET** | 4 지수 + KRX 21 섹터 + 사용자 MA periods | v3.9.0 LOCK 이월 (+ market_calendar_event V55 — ADR 0104) |
| **T-MCP** | 6 tool read-only | v3.9.0 LOCK 이월 |
| **T-CC 캔들** | 5m·15m·30m·90m·1h | + **3m(V58)·4h·12h(V57) interval** + **1m 장기 보존 tiering**(ADR 0113) + **수동 캔들 가져오기**(종목 상세 재수집 path) |
| T-O 주문 실행 | KillSwitch race-safe + SystemEvent | + **EXTERNAL source(V66)** 외부 HTS 주문 흡수 + 청산 intent priceType 분기 |
| T-RISK | KillSwitch chain + KillSwitchQueryPort | + **BUY_BLOCKED status(V61)** 신규 BUY 차단(SELL/익절/손절 평가 유지) + kill_switch_state seed(V62) |
| T-BT | strategy-engine 실 evaluator + trade breakdown | + **EntryPricePolicy/ScaleInExitCalculator 공유**(슬리피지·exit 모델 정합) + **다종목 백테스트(V68)** + 리서치 후보 DB화(V74) |
| T-OPS | P0 OPS 6건 + S-AI-1 후보 | + **pg-dump cron + restore 실집행**(ADR 0096 §2-1) |
| T-LWC 차트 | ADR 0093 Proposed 이월 | + **trendline 영속(V53·V54)** |

✨ **트랙 수**: v3.9.0 19 트랙 → v3.12.0 **23 트랙** (T-EXEC + T-SCALE + T-AIDISC + T-SIM 4 신규 합류). 핵심 변화는 **자본 path 안전 보강** — v3.9.0 read-only 옵션 path 합류 후, 운영 dwell evidence 위에서 진입 가격 정책·청산 항상 보장·분할매수/평단 exit를 자본 path에 본격 합류(단 안전 방향으로만 — AI 결정권은 여전히 0).

---

## 표준 항목 구조

각 트랙은 아래 9 항목으로 기술합니다.

1. **REST** — 노출 엔드포인트
2. **도메인** — 핵심 domain 클래스 + 포트 인터페이스
3. **영속** — JPA 엔티티 + Flyway 마이그레이션
4. **Kafka** — 발행/소비 토픽
5. **UI** — 프론트엔드 페이지/컴포넌트
6. **PR** — 관련 PR/사이클
7. **ADR** — 의존 ADR
8. **한계** — 미구현·보류 사항
9. **NXT 영향** — ADR 0063·0091 결합 영향 (해당 트랙만)

---

## ✨ T-EXEC — 주문 집행 정책 + 청산 항상 보장 (v3.12.0 신규 트랙 — ADR 0114)

v3.12.0 자본 path 보강 1순위. 사용자 2질문("시장가로만 사고 있지 않나" + "포지션 보유 중 긴장 상태인가") evidence 기반 — 진입은 슬리피지 캡 보호, 청산은 "항상" 보장.

### 1. REST

- 전용 신규 endpoint 0 (전략 exit/entry 설정은 기존 strategy controller에 add-only 필드).
- 진입 스킵·청산 audit은 기존 order/signal audit fanout 재사용(ADR 0114 §10-4 신규 STOMP topic 0).

### 2. 도메인 (순수 계산기 + 포트)

```
strategy-engine/strategy/entry/
├── EntryPricePolicy (순수 계산기 — evaluate + takeProfitLimitPrice · live·백테스트·리플레이 단일 출처)
├── EntryPriceInput (호가·기준가·설정 주입 · 함수 내부 조회 0)
├── EntryPriceDecision (sealed — Enter / Skip.PriceCapExceeded·SpreadTooWide·ThinLiquidity·NoQuote)
└── KrxTickSize (KRX 호가단위 floor 단일 출처 — 손절가·익절가 산정 공유)

domain/market/port/
├── EntryQuotePort (interface — 진입 호가 동기 read · 부수효과 0)
└── EntryQuote (ask1·bid1·askQty1·asOf — EntryPriceInput과 1:1)

domain/strategy/
├── EntryPolicy (maxEntrySlippagePct·entryTimeoutSec·maxSpreadPct — nullable override)
└── ResolvedEntryPolicy (전역 default 병합 결과 · DEFAULTS = 0.2/15/0.3)

consumer/risk/
├── EntryOrderPriceResolver (호출 배선 — EntryPolicy resolved + EntryQuotePort read + evaluate)
└── EntryOrderDecision (sealed — Enter(limitPrice, timeout) / Skip(auditCode, 호가 메타))

consumer/order/
├── PositionStopLossTickWatcher (market.tick.v1 → tick 손절 안전망 · 4중 게이트 우회)
├── AutoExitOrderCoordinator (order.filled.v1 → 익절 LIMIT 선주문 + HOLDING 등록/해제)
├── RestingSellOrderCanceller (같은 종목 open SELL 선취소 공용 · 수동/타전략 침범 금지)
└── HoldingPositionIndex (인메모리 보유 인덱스 · ReentrantLock 동시성 + mutationSeq)

consumer/ingestion/
└── HoldingDemandRegistrar (HOLDING owner 등록/해제 — RealtimeSubscriptionRouter evict 금지)

consumer/infrastructure/kis/
├── CompositeEntryQuoteAdapter (Redis 1차 → KIS REST 2차 → null)
├── RedisOrderbookTopCache (orderbook:top:{code} write-through/read — H0STASP0)
└── KisRestAskingPriceAdapter (FHKST01010200 fallback 1회)
```

#### EntryPriceDecision sealed (ADR 0114 §6-1 — audit 코드 컴파일 타임 안전)

```kotlin
sealed interface EntryPriceDecision {
    data class Enter(val limitPrice: BigDecimal) : EntryPriceDecision   // priceType=LIMIT 발행
    sealed interface Skip : EntryPriceDecision {
        data object PriceCapExceeded : Skip   // ask1 > cap → SIGNAL_SKIPPED_ENTRY_PRICE_CAP
        data object SpreadTooWide : Skip      // 스프레드 > maxSpreadPct → SIGNAL_SKIPPED_SPREAD
        data object ThinLiquidity : Skip      // 매도1호가 잔량 약함
        data object NoQuote : Skip            // 호가 미가용/stale → SIGNAL_SKIPPED_NO_QUOTE (시장가 fallback 금지)
    }
}
```

### 3. 영속 (Flyway 2건 — V72·V73 add-only)

| Flyway | 변경 | 핵심 | ADR |
|--------|------|------|-----|
| **V72** | `strategy.entry_policy` (JSONB) | maxEntrySlippagePct·entryTimeoutSec·maxSpreadPct 묶음 객체 · null=전역 default env(하위호환 LOCK · ScaleInPlan 선례 1:1) | 0114 §5 |
| **V73** | `strategy.min_hold_bars` | 진입 체결 후 N캔들 DSL 매도만 무시(손절·익절은 항상 작동) · null=비활성(현행) | 잔익절 방어 B안 |

✨ **add-only 하위호환 LOCK**: 두 컬럼 모두 null = 현행 동작(전역 default env `magicjar.order.entry.*` 적용 / DSL SELL 즉시 평가) → 기존 전략 0 변경.

### 4. Kafka (신규 topic 0 · 기존 topic 재소비)

| 토픽 | 발행/소비 | 컴포넌트 |
|------|----------|---------|
| `order.intent.v1` (기존) | 발행 | 진입 LIMIT(priceType=LIMIT) + 청산 intent(`publishExitIntent` — 손절 MARKET / 익절 LIMIT) add-only |
| `market.tick.v1` (기존) | 소비 | `PositionStopLossTickWatcher` (4중 게이트 우회 손절) |
| `order.filled.v1` (기존) | 소비 | `AutoExitOrderCoordinator` (익절 선주문 + HOLDING 등록/해제) |

✨ **멱등 LOCK**: `auto-sl:`/`auto-tp:` 멱등키 → OrderRouter `idem:order:{key}`가 1m 시그널 경로와 tick/coordinator 경로 동시 발화를 한 번만 주문되도록 흡수.

### 5. UI

- 전략 편집 폼: entry_policy(슬리피지·타임아웃·스프레드) + minHoldBars 설정 add-only
- **잔익절 경고**: 진입 직후 minHoldBars 미설정 + 단타 전략 조합 시 잔존 익절 라인 경고(B안 — 손절·익절 라인은 항상 작동 명시)
- 진입 스킵 사유(SIGNAL_SKIPPED_*)는 기존 시그널/주문 audit 화면 노출

### 6. PR (v3.12.0)

- ADR 0114 신설 (architect — Proposed, Codex 병렬 후 비교 머지 시 Accepted 승격)
- R1: EntryPricePolicy + KrxTickSize + EntryQuotePort + EntryPolicy(strategy-engine·domain)
- R2: EntryOrderPriceResolver·CompositeEntryQuoteAdapter·PositionStopLossTickWatcher·AutoExitOrderCoordinator·RestingSellOrderCanceller·HoldingPositionIndex·HoldingDemandRegistrar (consumer)
- 적대 리뷰 R3 fix: HoldingPositionIndex ReentrantLock + mutationSeq 동시성(backend-core 2026-06-07)

### 7. ADR

- ADR 0114 (주문 집행 정책 + 청산 항상 보장 권위)
- ADR 0110 (strategy_position — 청산 평가 1차 권위 P2) · ADR 0111 (ScaleInExitCalculator — 평단 exit)
- ADR 0105 (readiness gate — P1 분화 대상) · ADR 0106 (RiskPlan 필수 — 진입 선행 게이트)
- ADR 0048 (호가 LRU — orderbook:top source) · ADR 0067·0068 (Risk Gateway·Order Execution) · ADR 0023 (룰 온리 AI 0)

### 8. 한계

- 호가 잔량 "약함" 임계(askQty1) · 호가 stale 판정 기준 · 미체결 취소 워커 위치/트리거 — R2/운영 데이터 후 조정(ADR 0114 §9 U-2~U-4)
- 익절 LIMIT 미체결 처리(유지 default — 손절 게이트 하한 보호) · 전략 정의 자체 삭제 시 청산 fallback(KillSwitch alert default) — R2 위임(U-5·U-6)
- NXT sell-enabled default true 유지(NXT 유동성 risk — 사용자 운영 데이터 후 전환 결재 U-9)
- 보유 종목 1m 폴링 fallback(P5) post-MVP (HOLDING owner evict 차단으로 대부분 cover U-7)

### 9. NXT 영향

- P4 NXT 시간대 SELL 평가 게이트(`sell-enabled` env) — NXT 유동성 얇음 → 손절 시장가 슬리피지 risk 명기. ADR 0091 NXT resolver와 결합(NXT instrument resolver는 v3.8.0 LOCK).

---

## ✨ T-SCALE — 전략별 포지션 귀속 + 분할매수/평단 exit (v3.12.0 신규 트랙 — ADR 0110·0111)

v3.12.0 자본 path 보강 2순위. "전략이 살아 있어야 청산"이 아니라 "포지션이 있으면 청산" 불변식 전환의 토대.

### 1. REST

- 전략 편집: scale_in_config(분할매수 plan) + partial_exits(단계 익절) + 평단 기준 stop/take 설정 add-only
- strategy_position 조회 (전략별 lot ledger 평단/수량)

### 2. 도메인

```
domain/strategy/ — StrategyPosition (userId·strategyId·code·quantity·avgPrice·updatedAt — 전략별 lot ledger)
                   StrategyPositionPort (청산 평가 진입점 1차 권위)
strategy-engine/ — ScaleInExitCalculator (평단 기준 StopLoss/PartialTakeProfit/TakeProfit 순수 함수)
                   executedExitStages (단계 익절 state 소유 — 1m 경로가 stage 발화)
```

### 3. 영속 (Flyway 4건 — V56·V59·V60·V64·V65)

| Flyway | 변경 | 핵심 | ADR |
|--------|------|------|-----|
| **V56** | `position` account_type unique | 모의/실전 계좌 분리 | 0110 정합 |
| **V59** | `strategy.take_profit_pct` | 전량 익절 % add-only | 0111 |
| **V60** | `strategy.fixed_stop_loss_pct` | 고정 손절 % add-only | 0111 |
| **V64** | `strategy_position` | **전략별 lot ledger** (기존 position user+code 합산 유지 + 그 아래 전략별) | 0110 |
| **V65** | `strategy.scale_in_config`·`partial_exits` (JSONB) | 분할매수 plan + 단계 익절 add-only(null=단일 진입+전량 익절 현행) | 0111 |

### 4. Kafka

- `order.intent.v1` — ScaleInExitCalculator exit 결정에 priceType 입힘(손절 MARKET / 익절 LIMIT — ADR 0114 결합)
- `order.filled.v1` 소비 — strategy_position upsert(체결 수량만 인정 · executedQuantity.coerceAtLeast(0))

### 5. UI

- 전략 편집 폼: 분할매수 단계·금액 + 단계별 익절(partial exits) + 평단 stop/take UI
- 전략 상세: strategy_position 평단·수량·미실현 손익 노출

### 6. PR (v3.12.0)

- spec `_workspace/specs/2026-06-05-scale-in-exit-ownership-spec.md` (분할매수 R1)
- ADR 0110·0111 신설 (architect — 2026-06-05 Accepted)
- V64·V65 (orchestrator 확정 — origin/main 최신 V63 기준 V64로 당김, ADR 0102 재확인)

### 7. ADR

- ADR 0110 (strategy_position 1급 도메인) · ADR 0111 (RiskPlan scale-in/평단 exit — ADR 0106 amend)
- ADR 0106 (RiskPlan 필수 — SELL 가용 수량) · ADR 0067 (Risk Gateway) · ADR 0042 (PositionRepository) · ADR 0114 (exit 가격 유형)

### 8. 한계

- partial exits 다단계 stage 상태는 ScaleInExitCalculator가 소유 — resting 선주문 시 1m 경로와 이중 발화 → coordinator는 단일 전량 익절만 선주문(분할 익절은 1m 경로 stage 발화)
- 전략 정의 삭제 시 보유분 청산 fallback(전역 default stop vs KillSwitch alert) — R2 위임

### 9. NXT 영향

- 0 (포지션 귀속은 NXT 미해당 — KIS 잔고 권위)

---

## ✨ T-AIDISC — AI 종목발굴 Claude CLI bridge (v3.12.0 신규 트랙 — ADR 0112)

v3.12.0 AI 보조 확장. API key 미사용(`claude -p`/local bridge) · 자본 path 결정권 0 · 종목 master 매핑 필수(hallucination 차단).

### 1. REST (v3.12.0 신규)

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/strategy-research/ai-discovery` | 자연어 조건 → Claude 후보 발굴 → instrument master 매핑(matched/unmatched) |
| `GET` | `/api/strategy-research/ai-discovery/state` | 사용자별 마지막 발굴 조건·후보 상태(KEPT/DISMISSED) |
| (preset CRUD) | `/api/strategy-research/...` | AI 발굴 intent prompt **프리셋 추천** 관리(검증 가설 템플릿 · 매수 추천 아님) |
| (core prompt 편집) | `/api/strategy-research/...` | AI 발굴 **Core Wrapper**(전역 system prompt) 운영 중 편집 |

### 2. 도메인

```
api/strategyresearch/
├── StrategyResearchAiDiscoveryService (자연어 → 후보 → 매핑 → state)
├── StrategyResearchAiTextClient (claude -p 직접 실행 또는 bridge POST /discover)
└── StrategyResearchController
api/strategybuilder/
└── StrategyBuilderAiGenerateService (AI 전략 생성 — 동일 Claude CLI bridge)
consumer/ai/
└── ClaudeCliCommentAdapter (코멘트 4번째 provider — type=chart 종목상세 차트해석)
scripts/
└── claude-discovery-bridge.mjs (POST /discover → claude -p 실행 → stdout)
```

✨ **AI 4종 Claude CLI bridge 매트릭스**:
| # | 기능 | 위치 | provider |
|---|------|------|----------|
| 1 | 종목발굴 (자연어 → 종목 후보) | api StrategyResearchAiTextClient | claude-cli / claude-cli-bridge |
| 2 | 프리셋 추천 (intent prompt 템플릿 — V70) | api StrategyResearch | claude-cli |
| 3 | 전략 생성 (StrategyBuilder) | api StrategyBuilderAiGenerateService | claude-cli |
| 4 | 종목상세 차트해석 코멘트 | consumer ClaudeCliCommentAdapter | claude-cli (구독 기반·비용 0) |

### 3. 영속 (Flyway 3건 — V67·V70·V71)

| Flyway | 테이블 | 핵심 |
|--------|-------|------|
| **V67** | `strategy_research_ai_discovery_state` | 사용자별 마지막 조건 + 후보 상태(KEPT 유지·DISMISSED 제외 프롬프트) |
| **V70** | `strategy_research_ai_discovery_preset` | AI 발굴 intent prompt preset(Screener/Backtest 검증용 가설 템플릿) |
| **V71** | `strategy_research_ai_discovery_core_prompt` | **Core Wrapper**(전역 system prompt) 단일행 — 코드 상수 → DB 승격(운영 중 편집) |

### 4. Kafka

- 발굴/프리셋/전략 생성: Kafka 0 (동기 REST + claude -p ProcessBuilder/bridge)
- 종목상세 코멘트: `ai.comment.requested.v1` 소비 → ClaudeCliCommentAdapter routing → `ai.comment.responded.v1`(v3.9.0 T-AI 경로 재사용)

### 5. UI

- `/strategy/research` 상단 **AI 종목발굴 패널** — 자연어 조건 입력 → matched/unmatched 후보 카드 → matched만 선택 가능
- Core Wrapper 편집 UI · 프리셋 추천 선택 UI
- 종목상세 AI 차트해석 카드(기존 AiCard type=chart — provider만 claude-cli)

### 6. PR (v3.12.0)

- ADR 0112 신설 (Codex — 2026-06-06 Accepted)
- V67·V70·V71 (발굴 state·preset·core prompt 영속)
- V74 리서치 후보 DB화 (코드 하드코딩 18 후보 → entry_dsl 통일)
- 코멘트 claude-cli provider 연동 (ai — 2026-06-08)

### 7. ADR

- ADR 0112 (AI 종목발굴 Claude CLI bridge + 전략 리서치 후보 탐색)
- ADR 0026 (AI 옵션 — 사용자 트리거 한정) · ADR 0071 (T-SC Screener — 룰 기반 발굴 유지) · ADR 0001 (룰 본체 + AI 옵션) · ADR 0107 (실주문 경로 분리)

### 8. 한계 (ADR 0112 §2-4 안전 경계 LOCK)

- KIS 주문 adapter 호출 0 · `order.intent.v1` 발행 0 · 전략 자동 저장 0 · watchlist 자동 등록 0 · 실시간 수요 자동 등록 0
- AI 후보는 "검증 후보" — 사용자 명시 선택해야 기존 리서치 form 진입
- Docker dev는 bridge 프로세스 별도(재기동/health 확인 절차 README)
- Claude CLI/bridge 장애 시 endpoint 503/504 + UI error(본체 자동매매·차트·기존 리서치 영향 0)

### 9. NXT 영향

- 0 (read-only 후보 탐색 · 자본 path 외)

---

## ✨ T-SIM — 시나리오 리플레이 샌드박스 (v3.12.0 신규 트랙 — ADR 0107)

실주문/모의주문 runtime과 격리된 QA replay namespace. EntryPricePolicy/ScaleInExitCalculator 순수 계산기 공유로 "리플레이≠라이브" drift 차단.

### 1. REST

- scenario replay 실행/조회 (scenario_* namespace)

### 2. 도메인

- scenario run/event 도메인 — live 주문·리스크 테이블 FK 0 (scenario_* 안에서만 CASCADE)
- EntryPricePolicy·ScaleInExitCalculator·KrxTickSize 순수 함수 공유(라이브와 단일 출처)

### 3. 영속 (Flyway 1건 — V63)

| Flyway | 테이블 | 핵심 |
|--------|-------|------|
| **V63** | `scenario_run`·`scenario_*` | 실주문/모의주문 runtime 분리 QA replay namespace (live FK 0 · scenario 내 CASCADE) |

### 4. Kafka

- 라이브 자본 topic과 격리 (scenario 전용)

### 5. UI

- 시나리오 리플레이 sandbox 화면 (진입 스킵·타임아웃 취소·DISABLED 청산·readiness 분화·NXT SELL 재현)

### 6. PR (v3.12.0)

- ADR 0107 신설 · V63 (v3.11.0 cut — scenario replay sandbox)

### 7. ADR

- ADR 0107 (Scenario Replay Sandbox — 실주문 경로 분리 원칙) · ADR 0114 (EntryPricePolicy 리플레이 공유) · ADR 0056 (백테스트 슬리피지 모델 정합)

### 8. 한계

- 1차 baseline (실 dump 기반 DR drill 자동 재현은 후속)

### 9. NXT 영향

- 0 (격리 sandbox)

---

## ✨ T-CC 캔들 — interval 확장 + 수동 가져오기 (v3.12.0 amend — ADR 0105·0113)

v3.9.0 캔들 정합 LOCK 이월 + intraday interval 확장 + 장기 보존 tiering.

### 1. REST

- **캔들 가져오기**: 종목 상세에서 수동 캔들 fetch(REST bootstrap/backfill — 부족 구간 재수집 path · mock 박스 금지 · 데이터 없으면 오류/준비/재수집 노출)

### 2. 도메인

- candle interval CHECK 확장 (3m·4h·12h 추가)
- 1m 장기 보존 retention tiering(ADR 0113 cases)

### 3. 영속 (Flyway 3건 — V57·V58·V69)

| Flyway | 변경 | ADR |
|--------|------|-----|
| **V57** | candle interval CHECK + 4h·12h | 0105 |
| **V58** | candle interval CHECK + 3m | 0105·0113 |
| **V69** | drop duplicate candle index | — |

### 4. Kafka

- `market.candle.*.closed.v1` (interval 확장 정합)

### 5. UI

- 종목 상세 캔들 가져오기 버튼 + 재수집 path · 차트 overlay는 실 indicator + 실 candle DB 기준(데이터 부족 시 오류 노출)

### 6. PR (v3.12.0)

- V57·V58·V69 (interval 확장) · ADR 0113 (retention tiering cases) · ADR 0105 (reconciliation)

### 7. ADR

- ADR 0105 (캔들 reconciliation + 1m 장기 보존) · ADR 0113 (retention tiering)

### 8. 한계

- 1m 장기 보존 tiering case별 retention 정책 운영 후 조정

### 9. NXT 영향

- ADR 0091 NXT resolver (v3.8.0 LOCK 이월)

---

## T-MARKET · T-AI · T-MCP · T-O · T-RISK · T-BT · T-OPS · T-LWC 외 (v3.9.0 LOCK 이월 + 증분)

| 트랙 | v3.12.0 변경 |
|------|------------|
| T-MARKET | v3.9.0 LOCK 이월 + market_calendar_event(V55 — ADR 0104) |
| T-AI | + claude-cli 4번째 provider(종목상세 차트해석 · 구독 기반 · 비용 0 · prompt_template type=chart 무변경) |
| T-MCP | v3.9.0 LOCK 이월 (6 tool read-only) |
| T-O 주문 실행 | + EXTERNAL source(V66) 외부 HTS 주문 흡수 + 청산 intent priceType 분기(ADR 0114) |
| T-RISK | + BUY_BLOCKED status(V61 — 신규 BUY 차단·SELL/익절/손절 평가 유지) + kill_switch_state seed(V62) |
| T-BT | + EntryPricePolicy/ScaleInExitCalculator 공유 + 다종목 백테스트(V68) + 리서치 후보 DB화(V74) |
| T-OPS | + pg-dump cron + restore 실집행(ADR 0096 §2-1 · 매일 03:00 KST · 7일 retention) |
| T-LWC 차트 | + trendline 영속(V53·V54) |
| T-IND·T-PAT·T-SIG·T-DSL·T-SC·T-NOTIFY·T-W1·T-ACCT·T-MAN·T-J·T-PERF·T-NEWS | v3.9.0 LOCK 이월 |

---

## ✨ 모달 공통 동작 (v3.12.0 frontend 정합 — 05-ui-inventory 권위)

본 트랙은 frontend 권위(05-ui-inventory.md)이나 backend 계약 정합으로 요약:

- **ESC 키 닫기** + **scroll lock**(모달 오픈 시 body scroll 차단) 공통 동작 — 전 모달(SettingsModal·전략 편집·AI 발굴 등) 일관
- 모달 내 form은 backend graceful 핸들러(400/422)와 정합 — 검증 실패 시 한국어 메시지 노출

---

## ADR 의존 요약 (v3.12.0 갱신)

| ADR | 영향 트랙 |
|-----|---------|
| ADR 0004 | 모든 트랙 (모듈 경계 9 — 진입/청산 순수 계산기 strategy-engine, port domain) |
| ADR 0007 | 모든 Kafka 트랙 (청산 intent도 order.intent.v1 Outbox add-only) |
| ADR 0023 | T-EXEC·T-SCALE·T-BT (룰 온리 + AI 호출 0 — 순수 계산기 AI 0 LOCK) |
| ADR 0026 | T-AI·T-AIDISC (AI 옵션 — 사용자 트리거·결정권 0) |
| ADR 0048 | T-EXEC (호가 LRU H0STASP0 — orderbook:top source) |
| ADR 0067·0068 | T-O·T-EXEC (Risk Gateway·Order Execution + priceType/limitPrice 발행) |
| ADR 0096 | T-OPS (DR/backup — pg-dump cron + restore 실집행) |
| ADR 0104 | T-MARKET (market_calendar_event V55) |
| ADR 0105 | T-CC·T-EXEC (캔들 reconciliation + readiness gate P1 분화) |
| ADR 0106 | T-EXEC·T-SCALE (RiskPlan 필수 — 진입 선행 게이트) |
| ADR 0107 | T-SIM (시나리오 리플레이 샌드박스 V63) |
| ADR 0110 | **T-SCALE·T-EXEC** (strategy_position V64 — 청산 평가 1차 권위 P2) |
| ADR 0111 | **T-SCALE** (RiskPlan scale-in/평단 exit V65 — ScaleInExitCalculator) |
| ADR 0112 | **T-AIDISC·T-AI** (AI Claude CLI bridge — 발굴·프리셋 V70·Core Wrapper V71·state V67·코멘트 provider) |
| ADR 0113 | T-CC (캔들 retention tiering — 3m·4h·12h interval V57·V58) |
| ADR 0114 | **T-EXEC** (주문 집행 정책 + 청산 항상 보장 — EntryPricePolicy·entry_policy V72·tick 손절·익절 선주문·HOLDING owner·min_hold_bars V73) |

---

## 💡 v3.12.0 리팩토링맨 lens 진보 평가 (트랙 매트릭스)

| 트랙 | v3.9.0 상태 | v3.12.0 상태 | 진보도 |
|------|-----------|-----------|-------|
| **T-EXEC** ★★ | 0건 (진입 시장가 고정 · 손절 4중 게이트 단일) | **★ marketable limit + tick 손절 안전망 + position-driven 청산 + HOLDING evict 금지 + 익절 LIMIT 선주문 + EntryPricePolicy 순수 계산기** | ★★★★★ |
| **T-SCALE** ★★ | 0건 (position 합산 단일) | **★ strategy_position lot ledger + scale-in plan + 평단 stop/take(ScaleInExitCalculator)** | ★★★★★ |
| **T-AIDISC** ★★ | 0건 | **★ Claude CLI bridge 4종(발굴·프리셋·전략 생성·코멘트) · API key 미사용 · 종목 master 매핑 · 자본 path 0** | ★★★★ |
| **T-SIM** ★ | 0건 | **★ 시나리오 리플레이 샌드박스(scenario_* 격리 · 순수 계산기 공유로 drift 차단)** | ★★★★ |
| T-AI | 3 provider | + claude-cli 4번째(구독 기반·비용 0) | ★★★ |
| T-CC | 5 interval | + 3m·4h·12h + 1m 장기 보존 + 수동 가져오기 | ★★★ |
| T-OPS | 표 cover만 | + pg-dump cron + restore 실집행 | ★★★★ |
| 순수 계산기 단일 출처 | (개별 구현) | **EntryPricePolicy·ScaleInExitCalculator·KrxTickSize·stopPriceOf — live·백테스트·리플레이 공유** | ★★★★★ |
| add-only 하위호환 | (트랙별) | **entry_policy·min_hold_bars·scale_in_config JSONB null = 현행(기존 전략 0 변경 LOCK)** | ★★★★★ |
| 동시성 견고화 | (단일 스레드 가정) | **HoldingPositionIndex ReentrantLock + mutationSeq(적대 리뷰 R3 fix)** | ★★★★ |

✨ **v3.12.0 cut 종합 평가**:
- v3.9.0이 read-only 옵션 path 완전 합류였다면, v3.12.0은 **자본 path 안전 방향 진화** — 19 → 23 트랙(T-EXEC·T-SCALE·T-AIDISC·T-SIM 4 신규).
- 사용자 2질문 evidence(시장가 고정·손절 게이트 의존) 직시 → 진입 슬리피지 캡 보호 + 청산 "항상" 보장(tick 안전망·position-driven·HOLDING evict 금지·익절 선주문).
- KIS 국내 OpenAPI 서버 스탑로스 부재 구조적 함의 직시 → consumer 자체가 스탑로스 장치임을 게이트 분화로 견고화.
- 순수 계산기 단일 출처(EntryPricePolicy·ScaleInExitCalculator·KrxTickSize)로 백테스트/리플레이/라이브 drift 의심 차단 — DRY 본질 정합.
- AI는 끝까지 결정권 0(Claude CLI bridge 4종도 후보 탐색·코멘트만 · 종목 master 매핑 hallucination 차단).

🔄 **backlog 매트릭스 (v3.12.x patch 또는 후속 후보)**:
- ADR 0114 §9 U-2~U-9 (호가 임계·stale 기준·취소 워커 위치·NXT sell-enabled default 전환 — 운영 데이터 후)
- ADR 0114 Codex 병렬 비교 머지 후 Accepted 승격 정합 검증
- 보유 종목 1m 폴링 fallback(P5 post-MVP)
- 진입 체결률/스킵률 관측 패널 (SIGNAL_SKIPPED_* audit)
- weekly backup + WAL archiving (v3.8.0 backlog 이월)
- ArchUnit 게이트(KIS REST strict gate · AI provider import grep)
