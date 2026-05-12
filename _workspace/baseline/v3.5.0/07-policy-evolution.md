# 07 — MD 룰 정책 진화 (v3.4.0 → v3.5.0)

> **작성자**: architect (PR-53)
> **시점**: 2026-05-12 v3.5.0 태그 컷 직후
> **사용자 명시 의도**: "우리 md 룰 정책의 변화로 더 나아지고 있다는 점을 어필 할 수 있는 목록" (v3.1.0 baseline에서 도입 — v3.5.0까지 누적 갱신)

본 문서는 **v3.4.0 → v3.5.0 정책 진화**를 추적한다. 추측 0 — 모든 룰은 운영 헌법(`_workspace/arch/agent-charter.md`) 7 § + Living Policy(`docs/architecture/policies/`) + ADR 0049(Superseded)~0067 + backlog 처리 매트릭스로 추적 가능.

각 § 본문은 **도입 전 / 도입 후 / 효과 / 사례 PR** 4단 구조다 (v3.1.0·v3.2.0·v3.2.2·v3.3.0·v3.4.0 baseline 정합).

**v3.4.0 baseline 보존**: §1~§5 정책 진화 8건(v3.2.2 5건 + v3.3.0 2건 + v3.4.0 3건) + §6~§7 다음 Wave + 누적 매트릭스는 v3.5.0 사이클에서 **갱신 0** (안정화 + 보존). 본 v3.5.0에서는 §1·§2·§3 3건 신규 정책 진화 + §6 다음 Wave 정책 후보 갱신 + §7 누적 매트릭스 갱신.

**v3.5.0 사이클 정책 진화 3건**:
1. **ADR 0067 — T-RISK 리스크 관리 게이트 (Signal → Risk Gateway consumer → Order intent 의무 게이트 + 6 룰 short-circuit + 4 영속 + STOMP 알림 + auto-PAUSE)** — 자동매매 본체 자본 보호 안전망 노드 가동 baseline (W3 세 번째 트랙)
2. **README 굴직 재작성 (사용자 메모리 "프로젝트의 꽃" 정합)** — 옛 PoC·v2 잔재 0건 + 사용자 비전(룰 기반 quant trading 본체 + AI 옵션 보조 + MCP 외부 client) 풀세트 정합 + 5 baseline 진화 timeline + Mermaid 시스템 전경 + md 4 매트릭스
3. **`scripts/release.sh` 신규 (GHA 폐기 후속 alternative)** — ADR 0049 Superseded(v3.4.0 사용자 결제 폐기) 후속 자동 승격 헬퍼 + 사용자 메모리 `feedback_no_github_actions.md` 정합 + Living Policy `docs/architecture/policies/kis-first-raw-fetch.md` 본문 보강 (PR #349, 5단계 의무 절차 명시 + 위반 baseline 흡수)

---

## §0. 운영 헌법 7 § (보존 — v3.1.0·v3.2.0·v3.2.2·v3.3.0·v3.4.0 baseline 그대로)

운영 헌법 7 §는 v3.1.0 PR-26(#224)에서 통합 발행된 후 v3.2.0·v3.2.2·v3.3.0·v3.4.0 사이클에서 보존 + v3.5.0 사이클에서 **§3 KIS-first raw fetch 본문 간략화** (charter는 간단 인용 + Living Policy `docs/architecture/policies/kis-first-raw-fetch.md`가 권위 본문 LOCK — PR #349 정합). 본문 갱신 의의는 charter §3 중복 정리(2~3줄) 정합. 안정화 검증 — 본 v3.5.0 19 PR 사이클에서 다음 영역 모두 헌법 정합:

| § | 제목 | v3.5.0 사이클 검증 |
|---|------|---------------------|
| §1 | task.mjs 4단계 의무 | 모든 sub-agent 디스패치 사이클(PR #348·#351·#353·#354·#355·#356·#352·#359·#358·#360) start·check·done 4단계 호출 — 누적 task archive PR #347·#350 정합 |
| §2 | 머지 후 정리 4단계 | PR #348~#356 머지 후 즉시 worktree 제거 + task archive (orchestrator가 v3.5.0 cut PR #357 + baseline init PR #360 동시에 chore archive 묶음 처리 — 헌법 §2 정합) |
| §3 | KIS-first raw fetch 의무 | v3.5.0 사이클은 KIS 외부 데이터 규격 신규 결정 0건 — T-RISK는 Kafka 내부 토픽만 listen + KIS API 추가 호출 0. 헌법 §3 면제 cycle. **PR #349 charter §3 중복 정리 + Living Policy 본문 5단계 의무 절차 보강 — 헌법 §3 정합 강화** |
| §4 | CHANGELOG 슬롯 채번룰 | PR #348~#360 모두 anchor 사전 발행 + sub-agent in-place edit. modify-shared 충돌 0건. **본 PR-53 baseline 사이클도 anchor 4건(`<!-- pr-53-slot-architect/backend-core/market-data/frontend -->`) 사전 발행 정합** (`orchestrator` slot 1건 포함하여 총 5 anchor) |
| §5 | PR 생성 직전 rebase 의무 | 본 PR-53 포함 모든 사이클 적용 — 통합 빌드 fail 0건 |
| §6 | JPA Repository default fun 함정 | ADR 0067 §11-1 sub-agent 디스패치 매트릭스에서 `RiskConfigJpaAdapter` · `RiskEventJpaAdapter` · `PositionJpaAdapter` · `SignalRiskStatusJpaAdapter` (ADR 0051 default fun 함정 회피 — abstract 함수 body 없음) 정합 명시. PR #354 본격 구현에 inline 주석 검증 |
| §7 | sub-agent fail 분기 룰 | PR #348·#351·#353·#354·#355·#356·#352·#358·#359·#360 모든 본격 사이클 — 재디스패치 0건. V18 hotfix(#355)는 sub-agent fail이 아니라 마이그레이션 자체 결함 정합 즉시 fix — orchestrator 자율 진행(헌법 §7 자율 진행 정합 + 사용자 메모리 `feedback_conflict_resolution_autonomy.md`) |

**baseline 정합**: v3.2.0 시점 통합한 헌법이 v3.2.2·v3.3.0(실시간 fanout) + v3.4.0(T-SIG + GHA 폐기) + v3.5.0(T-RISK + README 굴직 + release.sh) 사이클까지 완주 — 갱신 의제 0건 (§3 charter 중복 정리는 본문 보강이 아닌 SoT 책임 경계 명시).

---

## §1. ADR 0067 — T-RISK 리스크 관리 게이트 (자동매매 본체 자본 보호 안전망 노드 가동)

### 도입 전

v3.4.0 baseline 시점 자동매매 본체 자본 보호 안전망 노드 **부재**:
- ADR 0024(Risk Guard chain 6단) 골격 권위만 발행 — 실제 가동 0
- ADR 0042(Position Bootstrap) 부팅 시 KIS 잔고 워밍만 — 시계열 영속·룰 게이트 0
- ADR 0043(KillSwitch 행동 정의)·ADR 0044(Notification System) 사전 자산만 — 통합 게이트 0
- T-SIG(ADR 0066 v3.4.0 마감) signal `signal.generated.v1` Kafka publish → 직접 T-O 진입 시 자본 보호 0

**자동매매 본체 안전성 결함**:
- **매매 결정 → 주문 직진 risk** — T-SIG false positive 발생 시 자본 손실 직접
- **동시 시그널 burst 시 over-leverage** — boundary 09:05 동시 BUY 5 종목 → 자본 100% 풀 진입 risk
- **일일 누적 손실 cap 0** — 사용자 명시 정지 부재 시 -10%·-20%·-30%까지 진입 가능
- **strategy 폭주 미차단** — 단일 strategy 분단위 5건+ 주문 → 수수료 누적 + 슬리피지 손실
- **AI 호출 risk** — risk verdict가 deterministic 룰이 아니면 v2 잔재 회귀 risk (ADR 0023 §2-4 정합 위반 risk)

### 도입 후

ADR 0067 (PR #348 plan + PR #351 Accepted + PR #353 Phase 1 + PR #354 Phase 2 + PR #355 V18 hotfix + PR #356 Phase 3) — 사용자 결정 의제 8건 권장안 채택:

**의제 A 포지션 한도**: A-1 절대 금액 + 자본 비율 % 둘 다 + `min()` 적용 채택 — 자본 변동 대응(자본 증가 시 비율 자동 확장) + 신규 사용자 안전 default(자본 < 2M 시 절대 우선) + 보수적 보호.

**의제 B 일일 손실 한도**: B-1 전역 + 절대 OR % 택1 + 자정 reset 채택 — 자본 일일 보호 본질 + UX 단순(NULL 한쪽 enforce) + KST 00:00:00 reset + position close 시 realized P&L 누적.

**의제 C 동시 종목 한도**: C-1 사용자 설정 + default 5 채택 — 자본 분산(5종목 × 20% = 100% 풀 진입 시점) + UI 슬라이더 1~20 + safe default.

**의제 D 거래 빈도 한도**: D-1 strategy당 분당 + 일별 dual + Redis sliding window 채택 — strategy 격리 + dual cover(분당 burst 차단 + 일별 누적 차단) + O(log N) `ZADD trades:freq:{...}:{minute|day} {ts} {signalId}` + safe default strategy당 3/min + 50/day.

**의제 E 게이트 위치**: E-1 Kafka `signal.generated.v1` 후 별도 RiskGateway consumer 채택 — strategy 패턴 일관(T-SIG sandbox + 본 ADR risk-eval-pool — 단 risk는 deterministic이라 별도 pool 불필요) + async + retry + audit trail(Kafka 7d retention) + signal·risk·order 분리 명확 + T-O가 후행 consumer로 단순 decoupled 진입.

**의제 F 위반 시 처리**: F-1 signal에 `risk_status` 컬럼 + risk_event INSERT + STOMP 알림 + auto-PAUSE strategy(반복 위반 시) 채택 — 명시적 audit + 사용자 즉시 인지 + 폭주 strategy 자동 보호(연속 10회 BLOCKED 시 strategy.status=PAUSED).

**의제 G 영속 매트릭스**: G-1 risk_config + risk_event + signal.risk_status + position 4 도메인 dedicated 채택 — 도메인 분리 명확 + ADR 0061 T-BT 3 테이블 분리 패턴 정합 + JOIN 부담 최소 + position 시계열 영속 보강(ADR 0042 baseline 합류).

**의제 H 설정 source**: H-1 사용자별 risk_config + 전역 default seed 채택 — multi-tenant 정합(ADR 0010) + safe default(신규 사용자 즉시 안전망 가동) + REST PUT으로 사용자 override.

**`domain/.../risk` + `domain/.../position` 본체** (PR #353):
- `RiskConfig` data class(8 필드 + init require 검증 + Companion `defaultFor(userId)` seed: maxPositionAmount 1,000,000원 + maxPositionPercent 10% + dailyLossLimitPercent 3% + maxConcurrentPositions 5 + maxTradesPerMinute 3 + maxTradesPerDay 50 + autoPauseThreshold 10)
- `RiskEvent` data class(userId·signalId·strategyId·rule·verdict·reason·contextJson)
- `RiskRule` enum 7단(POSITION_LIMIT·DAILY_LOSS_LIMIT·MAX_CONCURRENT_POSITIONS·TRADE_FREQUENCY_MINUTE·TRADE_FREQUENCY_DAY·KILL_SWITCH_ON·USER_DISABLED)
- `RiskVerdict` enum(ALLOWED·BLOCKED)
- `RiskGatewayResult` sealed(Allowed·Blocked) — verdict 반환 타입
- `RiskContext` data class(userId·config·currentCapital·currentPositions·dailyRealizedPnl·recentTradesMinute·recentTradesDay·killSwitchOn·nowKst)
- `Position` data class + `TradingMode`(MOCK·REAL) + `PositionRepository` port 7 메서드
- 5 port(RiskConfigRepository · RiskEventRepository · RiskGateway · RiskNotifier · SignalRiskStatusUpdater) Pure Kotlin

**`RiskGatewayEvaluator`** (PR #353, consumer @Component) — ADR 0067 §7-2 1:1 매핑:
1. `evaluate(signal: Signal, context: RiskContext): RiskGatewayResult`
2. 6 룰 순차 평가 (short-circuit, 첫 BLOCKED 시 즉시 반환):
   - a. KILL_SWITCH_ON → killSwitchOn=true 시 BLOCKED 즉시
   - b. DAILY_LOSS_LIMIT → `dailyRealizedPnl <= -limit` 시 BLOCKED
   - c. MAX_CONCURRENT_POSITIONS → `currentPositions.size >= maxConcurrentPositions` 시 BLOCKED (BUY only, SELL=청산 통과)
   - d. TRADE_FREQUENCY_MINUTE → `recentTradesMinute >= maxTradesPerMinute` 시 BLOCKED
   - e. TRADE_FREQUENCY_DAY → `recentTradesDay >= maxTradesPerDay` 시 BLOCKED
   - f. POSITION_LIMIT → `signal.priceAtSignal × quantity > min(maxPositionAmount, currentCapital × maxPositionPercent/100)` 시 BLOCKED
3. ALLOWED 분기:
   - `SignalRiskStatusUpdater.markAllowed(signalId)`
   - `TradeFrequencyRedisGuard.consume(strategyId, userId, signalId, ts)` sliding window 누적
   - `OrderIntentKafkaProducer.publish(order.intent.v1, key=userId)` → T-O 진입
4. BLOCKED 분기:
   - `RiskEventRepository.save(RiskEvent(rule, verdict=BLOCKED, reason, contextJson))`
   - `SignalRiskStatusUpdater.markBlocked(signalId, rule, reason)`
   - `StompRiskNotifier.notifyViolation(userId, riskEvent)`
   - `RiskViolationKafkaProducer.publish(risk.violation.v1, key=userId)` → ADR 0044 Notification 진입점
   - `AutoPauseGuard.checkAndPauseIfNeeded(strategyId)` 연속 10회 BLOCKED 시 strategy.status=PAUSED + ADR 0066 RedisStrategyCache evict + STOMP AUTO_PAUSED fanout

**`SignalGeneratedKafkaListener`·`TradeFrequencyRedisGuard`·`DailyPnlCalculator`·`CapitalSnapshotProvider`·`AutoPauseGuard`·`RiskViolationKafkaProducer`·`OrderIntentKafkaProducer`** (PR #353·#354):
- SignalGeneratedKafkaListener `signal.generated.v1` partition by userId — 동일 사용자 단일 thread 순차 처리 race 0
- TradeFrequencyRedisGuard Redis ZSET sliding window O(log N)
- DailyPnlCalculator + Redis cache `daily_pnl:{userId}:{date}` TTL 자정까지
- CapitalSnapshotProvider ADR 0042 잔고 cache 활용
- AutoPauseGuard 연속 10회 BLOCKED 시 strategy PAUSED
- RiskViolationKafkaProducer `risk.violation.v1` retention 7d partition by userId (ADR 0044 진입점)
- OrderIntentKafkaProducer `order.intent.v1` retention 7d partition by userId (T-O 진입점)

**Flyway V16~V19** (PR #354 + V18 hotfix #355):
- V16 `risk_config` user_id PK + 8 컬럼 + CHECK 6건 + dailyLossLimit xor 제약
- V17 `risk_event` BIGSERIAL + signal_id FK ON DELETE SET NULL + 3 index 중 `idx_risk_event_strategy_blocked_recent` partial WHERE BLOCKED (auto-PAUSE path 가속)
- V18 `position` (ADR 0042 baseline 존재 → V18 원본 CREATE 충돌 → PR #355 hotfix로 ALTER 정정 + closed_at·mode·unrealized_pnl 컬럼 add only + 인덱스 3건 partial)
- V19 `signal.risk_status` ALTER VARCHAR(16) NOT NULL DEFAULT 'PENDING' + idx_signal_user_risk_ts

**REST 7 endpoint** (PR #354):
- RiskConfig 3: GET·PUT upsert·POST reset
- RiskEvent 2: GET list 페이징(?userId·strategyId·rule·verdict·from·to·page·size)·GET detail
- Position 2: GET current(closedAt IS NULL)·GET history(?from·to·page·size)

**frontend `/risk-config`·`/risk-events`·`/positions` 3 페이지** (PR #356):
- RiskConfigPage(8 입력 필드 + 의제 B 택1 UI + slider C·D + safe default seed)
- RiskEventListPage(시계열 + rule 7단 필터 + verdict 필터 + 페이징)
- PositionPage(현재 보유 + 청산 이력 탭)
- 5 컴포넌트: RiskConfigForm · RiskEventBadge · PositionCard · PnlBadge · StatusToast
- useStompRisk(BLOCKED + AUTO_PAUSED 토스트 분기) + useRiskConfig·useRiskEvents·usePositions REST hook + routing 확장 + AppHeader 메뉴 3건

**fitness gap 분석 (ADR 0067 §1-3)**:
- **자동매매에 필수** — magicJar 비전 정의 "룰 기반 quant trading 본체". 본 ADR이 그 자본 보호 안전망. 부재 시 T-SIG false positive → T-O 직진 → 자본 손실 risk 직접
- **v1/v2 잔재 아님** — ADR 0024(2026-05-03)·ADR 0042~0044(2026-05-07) 모두 v3.x 발행. 본 ADR이 그 자산의 W3 구현 권위
- **사용자 비전 정합** — 사용자=최고 관리자/설계자 risk_config 사용자별 영속 + REST CRUD UI · 룰 기반 deterministic verdict(AI 호출 0) · MCP 외부 client query 정합 후속

### 효과

- **자동매매 본체 자본 보호 안전망 가동** — 6 룰 dual cover(포지션·일일 손실·동시 종목·분당·일별·KillSwitch) — 사용자 자본 손실 risk 최소화
- **ADR 0024 권위 W3 구현** — Risk Guard chain 6단 골격 → 본 ADR이 실제 가동 + AI 호출 0 검증 의무 (`grep -rE "import (org\.springframework\.ai|com\.anthropic|com\.openai|com\.google\.cloud\.aiplatform)"` T-RISK 영역 결과 0건)
- **ADR 0042 Position Bootstrap 시계열 영속 보강** — 부팅 시 KIS 잔고 워밍 + 본 ADR이 시계열 영속(현재 보유 + 누적 P&L 산출 baseline) + V18 hotfix로 ALTER 정합
- **ADR 0066 T-SIG signal.generated.v1 진입점 활용** — Kafka 1 hop으로 race 0 + audit trail + decoupled
- **사용자 UX 완성** — `/risk-config` + `/risk-events` + `/positions` 3 페이지 + 실시간 토스트 + 시계열 audit + status 토글
- **T-O·T-NOTIFY 후행 의존 자산 LOCK** — `order.intent.v1` Kafka topic이 T-O 진입점 + `risk.violation.v1`이 T-NOTIFY(ADR 0044) 진입점
- **MCP 외부 client 정합 후속** — risk_event/position 시계열 endpoint(`GET /api/risk-events`·`/api/positions`)를 후행 ADR 0027(MCP)에서 노출 가능
- **운영 영향 미미** — DB ~70K row/년 BLOCKED only(5MB) · Kafka 0.012 msg/s · STOMP 0.0023 msg/s · Redis ~650KB · KIS REST 추가 호출 0건 · risk-eval-pool 별도 pool 불필요(<20ms 평가)
- **end-to-end latency** — T-SIG 100ms + T-RISK 75ms = <175ms 총합 (boundary 마감 → 사용자 알림 + order intent)

### 사례 PR

- **PR #348** (architect plan) — ADR 0067 plan 발행 (Status `Proposed`, 사용자 결정 의제 8건 대기)
- **PR #351** (architect Accepted) — ADR 0067 Proposed → Accepted (사용자 A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1 채택)
- **PR #353** (strategy-engine Phase 1 본격) — domain Risk·Position 14건 + RiskGatewayEvaluator + 6 룰 short-circuit + Kafka listener·producer + 단위 테스트
- **PR #354** (backend-core Phase 2 본격) — Flyway V16~V19 + JPA 3쌍 + REST 7 endpoint + RiskConfigService + StompRiskNotifier + 통합 테스트
- **PR #355** (hotfix V18 position CREATE → ALTER) — ADR 0042 baseline 정합 fix + 사용자 dogfooding evidence 즉시 흡수
- **PR #356** (frontend Phase 3 본격) — `/risk-config`·`/risk-events`·`/positions` 3 페이지 + 5 컴포넌트 + useStompRisk 토스트 + REST hook + routing + AppHeader
- **PR #357** (release) — v3.5.0 minor release T-RISK W3 세 번째 트랙 마감

---

## §2. README 굴직 재작성 (사용자 메모리 "프로젝트의 꽃" 정합)

### 도입 전

v3.4.0 baseline 시점 `README.md` 상태:
- v2 PoC 시절(2026-05-05 이전) 잔재 본문 일부 누적 — 옛 Quick Start·옛 Architecture·옛 명령 매트릭스
- 사용자 비전(`project_system_vision.md` 룰 기반 quant trading 본체 + AI 옵션 보조 + MCP) 정합 부분 미흡
- v3.1.0~v3.4.0 baseline 진화 timeline 부재
- 3단 hierarchy(사용자 + orchestrator + 7 sub-agent) 본문 부재
- Living Policy(`docs/architecture/policies/`) 진입 경로 부재
- 운영 헌법 7 § 요약 부재
- md 4 매트릭스(SoT / 결정 이력 / 진행 중 / snapshot) 매트릭스 부재

**사용자 명시 의도 (memory)**:
- README는 magicJar **프로젝트의 꽃** — 외부 자원(GitHub 방문자·MCP client·미래 사용자)이 첫 만나는 SPA
- 사용자 비전 정합 풀세트 + Architecture Mermaid + Agent hierarchy + 진화 timeline + 진입 경로 명시 의무

### 도입 후

PR #352 plan + PR #359 본격 (architect 단독 PR 2건):

**PR #352 plan** — 사용자 결정 의제 5건:
- **A 본문 흐름**: A-1 6단(핵심 가치 → Quick Start → Architecture → Agents & Workflow → Documentation → Commands) 채택
- **B 시각화 매트릭스**: B-2 Mermaid 시스템 전경 + 통계 매트릭스 + 5 baseline 진화 timeline 풀세트 채택
- **C 사용자 비전 정합 수준**: C-3 자동매매 본체 + MCP 외부 client baseline + AI 옵션 보조 풀세트 채택
- **D 진입 경로**: D-3 SoT 인덱스 + 결정 이력 5 질문 + md 4 매트릭스 풀세트 채택
- **E 옛 README v2 PoC 잔재 처리**: E-1 완전 삭제 채택 (사용자 명시 "옛 잔재 0건")

**PR #359 본격 — 373 lines 재작성** (`docs(architect): README 굴직 본문 재작성 — 프로젝트의 꽃`):

| 섹션 | 본문 |
|---|---|
| 핵심 가치 | "개인 맞춤형 한국 주식 자동매매 시스템" + 룰 기반 quant trading 본체 + AI 옵션 보조 + MCP 외부 client baseline |
| Quick Start (5분 안에 첫 차트) | 사전 요구사항 + 3 명령 기동(`.env` + `scripts/build-and-deploy.sh` + 접속 URL) + 첫 dogfooding 시나리오 + 개발 모드(IDE bootRun + infra container) |
| Architecture — 시스템 전경 Mermaid | JVM 4 모듈(domain·api·batch·consumer) + 외부 4(KIS·DART·Anthropic·Yahoo @Deprecated) + 인프라 3 + 디버깅 2 |
| Architecture — E2E 데이터 흐름 | 시세(KIS WS) → 시그널(T-SIG) → 리스크(T-RISK) → 주문(T-O 후행) 4 단계 본문 |
| Architecture — 기술 스택 매트릭스 | Kotlin 2.0 K2 + Spring Boot 3.3 + JDK 21 + React 19 + Vite + LWC v4 + PostgreSQL 16 + Redis 7 + Kafka 3.7 KRaft + Docker compose |
| Architecture — 운영 정책 한 줄 인용 | KIS-only · QA on-demand · AI 호출 자제(W4 한정) · MAGICJAR_MODE 이중 게이트 |
| Agents & Workflow — 3단 hierarchy | 사용자(최고 관리자) + orchestrator(`magicjar-orchestrator` 스킬) + 7 sub-agent 명시 |
| Agents & Workflow — 7 sub-agent 매트릭스 | architect · backend-core · market-data · strategy-engine · ai-engineer · frontend · qa 각 책임 + 모델 차등(opus/sonnet) |
| Agents & Workflow — orchestrator 일대기 | 4 Phase(A 설계 · B Wave 트랙 · C release · D archive) |
| Agents & Workflow — 운영 헌법 7 § | `_workspace/arch/agent-charter.md` 권위 인용 |
| Agents & Workflow — 진화 5 baseline | v3.1.0(MD 정리 베이스라인) → v3.2.0(pattern-engine + Strategy Builder) → v3.2.2~v3.3.0(boundary fanout) → v3.4.0(T-SIG) → v3.5.0(T-RISK) timeline + 핵심 ADR 인용 |
| Documentation — md 4 매트릭스 | 현행 규약 / 결정 이력 / 진행 중 / snapshot 4 매트릭스 |
| Documentation — SoT | `docs/architecture/master-architecture.md` v2 (ADR 0030) + `_workspace/INDEX.md` 첫 lookup |
| Documentation — 결정 이력 진입 경로 5 질문 | "왜 X 결정했는가?" → ADR 인덱스 / "지금 X 정책은?" → Living Policy / "X 트랙 진행 중?" → backlog / "X 시점 무엇이 가능?" → baseline / "X 사이클 작업 중?" → task |
| Commands — 핵심 명령 | `./gradlew check` · `./gradlew :api:bootJar` · `scripts/build-and-deploy.sh` · `scripts/smoke.sh` · `node scripts/task.mjs` · `scripts/release.sh` (v3.5.0 신규) |

**옛 README PoC·v2 잔재 0건 검증** — `git diff release/v3.4.0..main README.md` 결과 v2 PoC 본문 완전 삭제.

### 효과

- **외부 자원 첫 SPA 정합** — GitHub 방문자/MCP client/미래 사용자가 README 1회 읽기로 magicJar 비전·아키텍처·workflow·진화 timeline 풀세트 인지
- **사용자 비전 정합 풀세트** — 룰 기반 quant trading 본체 + AI 옵션 보조 + MCP 외부 client baseline 명시
- **3단 hierarchy 본문** — 사용자 + orchestrator + 7 sub-agent 명시로 외부에 운영 모델 인지 가능
- **진화 timeline 명시** — v3.1.0 → v3.5.0 5 baseline 누적 진화 + 핵심 ADR 인용 — 외부 자원이 magicJar의 누적 진화도 인지
- **진입 경로 5 질문** — 결정 이력/현행 정책/진행 중/snapshot/task 4 매트릭스 + 5 질문으로 외부 자원 자율 탐색 가능

### 사례 PR

- **PR #352** (architect plan) — README 굴직 비전 plan 발행 (사용자 결정 5건 의제)
- **PR #359** (architect 본격) — README 굴직 본문 재작성 373 lines (A-1·B-2·C-3·D-3·E-1 채택)

---

## §3. `scripts/release.sh` 신규 (GHA 폐기 후속 alternative) + Living Policy 본문 보강

### 도입 전

v3.4.0 baseline 시점:
- ADR 0049 Superseded (2026-05-12 사용자 결정 "GitHub Actions 결제 안 함") — `.github/workflows/release.yml` 삭제
- release 자동화 0 — release tag 발행 후 사용자 직접 `scripts/build-and-deploy.sh` + docker compose 매번 수동 처리
- GHA 외부 의존 비용 0 도달 — 단순 로컬 운영 정합
- 단, `gh release create draft` 자동 생성 부재 — 사용자 매번 수동 release note 작성 부담

**Living Policy `docs/architecture/policies/kis-first-raw-fetch.md` 본문 상태**:
- v3.3.0 baseline 시점 권위 본문 LOCK
- 운영 헌법 `_workspace/arch/agent-charter.md` §3과 본문 중복 누적 — charter도 본문 전체 + policies도 본문 전체
- 책임 경계 불명확 — 외부 자원이 "어디가 권위인가?" 혼선

### 도입 후

PR #358 + PR #349 정합 정리:

**PR #358 `scripts/release.sh` 신규** — `feat(scripts): release.sh — GHA 없이 GitHub Release 자동 승격`:

```bash
# scripts/release.sh (요약)
1. tag 검증 (semver vX.Y.Z 매칭)
2. git status clean 확인
3. `./gradlew clean :api:bootJar :batch:bootJar :consumer:bootJar`
4. `docker build` api · batch · consumer · frontend (4 컨테이너 이미지)
5. `docker tag ... ghcr.io/sp-daewoon/magicjar/{api|batch|consumer|frontend}:vX.Y.Z`
6. `docker push ghcr.io/...` (4 이미지 ghcr.io 발행)
7. `gh release create vX.Y.Z --draft --generate-notes --title "magicJar vX.Y.Z"`
```

특징:
- **CI 의존 0** — 로컬 docker 단독 + ghcr.io push + gh CLI (사용자 결정 정합)
- **release immutable tag 매커니즘(ADR 0031) 그대로** — tag 발행 직후 자동 draft 생성 → 사용자가 검토 후 publish
- **`--generate-notes`** — 이전 release 이후 PR 자동 인용
- **사용자 결정 명시 정합** — 사용자 메모리 `feedback_no_github_actions.md` LOCK

**PR #349 charter §3 중복 정리 + Living Policy 본문 보강** — `docs(arch): charter §3 중복 정리 + policies/INDEX 책임 경계 명시`:

(a) `_workspace/arch/agent-charter.md` §3 본문:
- 기존 KIS-first raw fetch 의무 본문 전체(절차·금지·면제) 그대로
- 변경: **간단 인용(2~3줄) + Living Policy `docs/architecture/policies/kis-first-raw-fetch.md` 권위 본문 LOCK 인용**

(b) `docs/architecture/policies/kis-first-raw-fetch.md` 본문 보강:
- **5단계 의무 절차 명시**: (1) KIS 공식 문서 raw fetch (2) tr_id·endpoint 매트릭스 추출 (3) 자체 추론 검증 (4) 코드 baseline 반영 (5) baseline 흡수 PR
- **위반 baseline 흡수**: 절차 위반 발견 시 즉시 baseline PR 발행 + 사용자 메모리 `feedback_kis_first_policy.md` 정합 + 사용자 메모리 `feedback_v3_skepticism_no_regression.md` (자체 추론 lens 의심 의무) 인용

(c) `_workspace/INDEX.md` + `docs/architecture/policies/README.md`:
- **두 폴더 책임 경계 명시**:
  - `_workspace/arch/agent-charter.md` = **운영 의무 헌법** (7 § + sub-agent 책임 매트릭스)
  - `docs/architecture/policies/` = **현행 권위 정책 본문** (KIS credential·로그 레벨·KIS-first raw fetch·task cycle·mermaid 등)
- 첫 lookup 매트릭스에 두 폴더 차이 명시

### 효과

- **release 자동 승격 alternative** — GHA 결제 폐기 후속 + 사용자 결정 명시 정합 + tag 발행 직후 draft 자동 생성
- **CI 의존 0 보존** — 외부 의존 비용 0 + 로컬 운영 정합 보존
- **다중 환경 운영 진입 시 alternative CI 재검토 ADR 발행 의무 LOCK** — 후속 사이클
- **운영 의무 헌법 vs Living Policy 책임 경계 명시** — 외부 자원이 "어디가 권위인가?" 혼선 해소
- **Living Policy 본문 보강** — 5단계 의무 절차 + 위반 baseline 흡수 — sub-agent 디스패치 시 자체 추론 회피 정합 강화
- **사용자 메모리 정합 매트릭스 다층 LOCK** — `feedback_no_github_actions.md` + `feedback_kis_first_policy.md` + `feedback_v3_skepticism_no_regression.md` 3건 동시 정합

### 사례 PR

- **PR #358** (meta · release helper) — `scripts/release.sh` 신규 + GHA 폐기 후속 alternative + 사용자 결정 명시 정합
- **PR #349** (architect · charter/policies 정리) — charter §3 중복 정리 + Living Policy 본문 보강(5단계 의무 절차 + 위반 baseline 흡수) + INDEX 책임 경계 명시
- **PR #350** (meta · task archive PR #349 머지 후속)

---

## §4. ~~ADR 0049 GHA 폐기~~ (v3.4.0 baseline 보존 — v3.5.0 갱신 0)

v3.4.0 baseline 07-policy-evolution.md §3 보존. v3.5.0 사이클에서 ADR 0049 status `Superseded`는 그대로 + `scripts/release.sh` 신규(§3 위)로 alternative 가동.

사용자 메모리 `feedback_no_github_actions.md`(v3.4.0 신규) 정합 보존.

---

## §5. ~~ADR 0057 KIS-only~~ · ~~ADR 0062·0063·0064~~ · ~~ADR 0061·0066~~ (v3.4.0 baseline 보존 — v3.5.0 갱신 0)

v3.4.0 baseline 07-policy-evolution.md §4·§5 보존. v3.5.0 사이클에서 본 정책은 모두 보존:
- ADR 0057 KIS-only — T-RISK는 KIS REST/WS 직접 호출 0건이라 정책 영향 0
- ADR 0062 합산 가공 plan — boundary fanout 트랙 완료 후속 정합 보존
- ADR 0063 NXT/통합거래 cover — 08:00~20:00 KST cover baseline 보존
- ADR 0064 boundary fanout — Kafka `market.candle.{interval}.closed.v1` 5종 + STOMP — **T-SIG `BoundaryClosingKafkaListener` consumer + T-RISK 후속 `SignalGeneratedKafkaListener`(signal.generated.v1 consume) 정합 LOCK**
- ADR 0061 T-BT 영속 baseline — T-RISK 4 테이블 + REST 7 endpoint 패턴이 본 ADR 3 테이블 + REST 5 endpoint baseline 정합
- ADR 0066 T-SIG signal 영속 — T-RISK `signal.risk_status` 컬럼 ALTER (V19) 정합 + `signal.generated.v1` Kafka 진입점 정합

**v3.6.x minor 진입 시 architect 단독 PR 의제** (ADR 0019·0023·0024·0029·0042·0043·0044·0047·0048·0056·0062·0064·0066 본문 갱신 + T-RISK 합류 정합 LOCK):
- ADR 0023 §2-1·§2-2 본문 — Risk Gateway 의무 게이트 단계 추가 + Signal 도메인 본문에 risk_status 컬럼 인용
- ADR 0024 §2 골격 본문 — 본 ADR 0067 권위 참조 + verdict path 0067 §7-2 1:1 매핑 정합
- ADR 0042 §결정 본문 — Position 도메인 합류 + closedAt 정합 + V18 hotfix evidence
- ADR 0043 §결정 본문 — `RiskRule.KILL_SWITCH_ON` 룰 진입 정합
- ADR 0044 §결정 본문 — `risk.violation.v1` payload 매트릭스 rule 7건 cover row
- ADR 0047 §2-6 V26·V27 → V14~V19 baseline 재할당 + §3-1 T-SIG·T-RISK 합류 매트릭스 본 ADR 권위
- ADR 0066 §11-4 + §6-2 — 본 ADR 0067 row + signal `risk_status` 컬럼 인용 (V19)
- ADR 0019 §2-1 — 본체 의무 게이트(`risk.violation.v1`·`order.intent.v1`) 진입점 명시

---

## §6. 다음 Wave 정책 후보 (W3 잔여 진입 시 발행 예정)

W3 잔여 진입(Screener · Order Executor · Reconciliation · Performance · Notification) 시 발행 예정 ADR/정책:

### 6-1. ADR 후보

| ADR 후보 | 주제 | 트리거 |
|---------|------|--------|
| ADR (1건) | ADR 0019·0023·0024·0029·0042·0043·0044·0047·0048·0056·0062·0064·0066 본문 갱신 통합 (T-RISK 합류 후속 정합 + V14~V19 baseline 재할당 + 매트릭스 NXT/합산/fanout/signal/strategy/risk/position) | v3.6.x minor 진입 시 architect 단독 PR |
| **ADR 0065** | **Screener 표준 (universe 분류·매트릭스 응답)** | W3 T-SC 본격 사이클 시 (slot 보존) |
| **ADR 0068** | **Order Executor 표준 (TTTC8001U·VTTC0801U 분기·idempotency·재시도·KIS 체결통보 H0STCNI0 합류·`order.intent.v1` consume)** | W3 T-O 본격 사이클 시 — **ADR 0067 `order.intent.v1` Kafka topic이 baseline 진입점 LOCK** |
| ADR 0069 | Notification 표준 (Slack webhook·outbox 패턴·`risk.violation.v1`·`signal.generated.v1` 합류 ADR 0044) — T-SIG + T-RISK 후속 다채널 확장(이메일·Web Push) baseline | W3 T-NOTIFY 본격 사이클 시 |
| ADR 0070 | Performance 표준 (Sharpe·MDD·Calmar·Sortino 계산 baseline — ADR 0056·0067 합류) | W3 T-PERF 본격 사이클 시 |
| ADR 0071 | Reconciliation 표준 (체결 정합·KIS H0STCNI0 vs internal order_audit 비교 + position 동기) | W3 T-RECON 본격 사이클 시 |
| ADR (slot) | signal monthly partition (row ≥ 5M 도달 시 발동 — ADR 0066 §9-1 후속) | 사용자 dogfooding row 누적 +5년 도달 시 |
| ADR (slot) | risk-eval-pool 별도 도입 (T-RISK 평가 wall-clock > 50ms 도달 시 — ADR 0067 §7-4 후속) | 사용자 dogfooding 누적 후 |

### 6-2. 정책 진화 후보

| 정책 후보 | 주제 |
|----------|------|
| Yahoo Finance 완전 삭제 (v3.6.x minor 일괄) | `YahooCandleFetcherAdapter.kt` · `YahooFinanceClient.kt` · `application.yml` yahoo.* · 일회성 SQL · 테스트 mock 케이스 모두 삭제 |
| strategy_eval_metric V20+ 발행 (ADR 0047 §2-6 V27 재할당) | backend-core 단일 PR — V14~V19 후속 (별도 사이클) |
| frontend `/screener`·`/dashboard`·`/orders` 라우트 발행 | W3 잔여 본격 사이클 |
| **W3 T-O (Order Executor) 본격 — ADR 0067 `order.intent.v1` baseline 활용** | T-O가 `order.intent.v1` consume → KIS TTTC/VTTC 분기 → 체결 → position 갱신 |
| **W3 T-NOTIFY (Slack webhook + `risk.violation.v1`·`signal.generated.v1` 합류) — ADR 0044 정합 + T-SIG·T-RISK 후속** | signal/risk/order 멀티채널 알림 |
| MCP 서버 노출 (Spring AI MCP starter 활용) — ADR 0027 | W4 본격 사이클 — **ADR 0066 signal/strategy + ADR 0067 risk_event/position REST API가 MCP 외부 client baseline LOCK** |
| KIS 1m 과거 데이터 cover 확장 (현재 30 거래일 → 60·90·180 거래일) | 백테스트 long-period 정합 + KIS Rate Limit 영향 측정 |
| alternative CI 도입 재검토 (ADR 0049 Superseded 후속) | 다중 환경 운영 진입 시점 — Cloud Build·CircleCI·Jenkins 등 후보 평가 |
| T-RISK 후속 sample 위반 시나리오 dogfooding 검증 | qa 트랙 또는 사용자 dogfooding 사이클 (6 룰 each BLOCKED evidence + auto-PAUSE 임계 도달 evidence + ALLOWED happy path) |

### 6-3. v3.5.0 baseline 보존 정합 의무

W3 잔여 진입 시점에 본 v3.5.0 baseline은 `_workspace/baseline/v3.5.0/` immutable(ADR 0031). v3.6.x 또는 v3.7.0 신규 baseline 컷(`_workspace/baseline/v3.X.X/`) 시 본 §1·§2·§3 정책 진화 3건은 신규 baseline 07-policy-evolution.md에 **누적 인용** — `_workspace/baseline/v3.5.0/07-policy-evolution.md §X` 직접 참조 + 신규 정책만 add.

---

## §7. v3.0.1 → v3.5.0 누적 정책 진화 요약 (6 baseline cumulative)

본 baseline 시점까지 누적된 MD 룰 정책 진화는 **v3.1.0 (4건)** + **v3.2.0 (4건)** + **v3.2.2 (5건)** + **v3.3.0 (2건)** + **v3.4.0 (3건)** + **v3.5.0 (3건)** = **21건**. 모두 ADR·Living Policy·사용자 메모리로 추적 가능:

| baseline | 사이클 | 정책 진화 | ADR 신규 |
|---------|--------|-----------|----------|
| v3.1.0 | PR-26~PR-29 | 운영 헌법 7 § 통합 + ADR 0049 baseline + ADR 0050 슬롯 채번룰 + ADR 0051 Repository default fun | 0049·0050·0051 |
| v3.2.0 | PR-31~PR-45 | ADR 0052 baseline 사이클 정형화 + ADR 0053 Living Policy Docs + ADR 0054 pattern-engine 모듈 분리 + ADR 0055 Strategy Builder UI 컴포넌트 스택 | 0052·0053·0054·0055 |
| v3.2.2 | PR-47~PR-48 | ADR 0056 W3 T-BT + ADR 0057 KIS-only + ADR 0062 합산 가공 + ADR 0063 NXT cover + 사용자 메모리 보강 2건 | 0056·0057·0062·0063 |
| v3.3.0 | PR-49 | ADR 0064 boundary fanout + v3.2.3 minor doc cut | 0064 |
| v3.4.0 | PR-50 + PR-51 | ADR 0061 T-BT 영속 baseline + ADR 0066 T-SIG W3 두 번째 트랙 가동 baseline + ADR 0049 Superseded GHA 폐기 (사용자 메모리 `feedback_no_github_actions.md` 신규) | 0061·0066 (실제 신규 2건) + 0049 Superseded |
| **v3.5.0** | **PR-52 + PR-53** | **ADR 0067 T-RISK W3 세 번째 트랙 가동 baseline + README 굴직 재작성(사용자 메모리 "프로젝트의 꽃" 정합) + `scripts/release.sh` 신규(GHA 폐기 후속 alternative) + Living Policy `kis-first-raw-fetch.md` 본문 5단계 의무 절차 보강 + charter §3 중복 정리(책임 경계 명시)** | **0067 (실제 신규 1건)** |

**누적 ADR 64건** (v3.1.0 베이스 51건 → v3.2.0 55건 → v3.2.2 60건 → v3.3.0 61건 → v3.4.0 63건 → **v3.5.0 64건**, 실제 신규 8건 + 슬롯 4건 + Superseded 1건). ADR 0019 부분 supersede revoke 지속(boundary fanout + T-SIG + T-RISK 트랙 완료 → v3.6.x minor §2 본문 재통합 architect 단독 PR 예정).

**baseline cumulative 정합**: v3.1.0 baseline `07-policy-evolution.md` (4건) + v3.2.0 baseline (4건) + v3.2.2 baseline (5건) + v3.3.0 baseline (2건) + v3.4.0 baseline (3건) + 본 v3.5.0 baseline (3건) = **21건 모두 누적 보존** + ADR/Living Policy/메모리/헌법 추적 가능. 사용자 명시 의도 "md 룰 정책의 변화로 더 나아지고 있다는 점을 어필 할 수 있는 목록" 정합.

**v3.5.0 사이클 핵심 메시지**: **T-RISK 리스크 관리 게이트 가동(ADR 0067) — 자동매매 본체 자본 보호 안전망 노드 진입** + **README 굴직 재작성(사용자 메모리 "프로젝트의 꽃" 정합) — 외부 자원 첫 SPA 풀세트** + **`scripts/release.sh`(GHA 폐기 alternative) + Living Policy `kis-first-raw-fetch.md` 5단계 의무 절차 보강 + charter §3 책임 경계 명시** — magicJar 비전 "룰 기반 quant trading 본체 + AI 옵션 보조 + MCP 외부 client" 정합 풀세트. AI 호출 0 grep 검증 지속(ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8) — magicJar = 룰 기반 본체, AI는 옵션 보조 분리 보존. W3 잔여(T-SC·T-O·T-NOTIFY·T-PERF·T-RECON) 진입의 baseline이 본 ADR 0066·0067로 LOCK (T-O 진입점 = `order.intent.v1` Kafka topic).

**v3.5.0 사이클 사용자 메모리 정합 매트릭스**:
- 새 신규 메모리 0건 (v3.4.0 `feedback_no_github_actions.md` + `feedback_kis_key_realonly_for_data.md` 보존)
- `feedback_v3_skepticism_no_regression.md` — ADR 0067 fitness gap 분석 보고 의무 정합 (자동매매 안전망 필수·v1/v2 잔재 0·회귀 risk 0·사용자 비전 정합 모두 통과)
- `feedback_kis_first_policy.md` — Yahoo `@Deprecated` 유지 + KIS 단일 채널 보존 + PR #349 Living Policy 본문 5단계 의무 절차 강화 정합
- `feedback_3tier_hierarchy.md` — architect plan PR(ADR 0067·README plan) → 사용자 결정 → sub-agent 직렬·병렬 디스패치 (Phase 1·2·3 직렬 + V18 hotfix) 정합
- `feedback_spec_plan_scope.md` — T-RISK W3 신규 트랙 + README 굴직 재작성 둘 다 spec/plan 발행 의무 충족 (hotfix·결함 정정·작은 묶음 면제 조건 미해당 — V18 hotfix만 spec/plan 면제)
- `feedback_conflict_resolution_autonomy.md` — modify-shared 충돌 0건 + sub-agent fail 분기 재디스패치 0건 + V18 hotfix orchestrator 자율 처리 정합
- `feedback_dev_priority_qa_batch.md` — 본 사이클 hotfix 1건만 + 단일 사이클 12 PR 본격 진행 (개발 진도 우선 정합)
- `feedback_verification_strict.md` — PR #353·#354·#355·#356 모두 `:domain:check`·`:consumer:check`·`:api:check`·`pnpm test/build` GREEN 인용 + V18 hotfix 시 사용자 dogfooding evidence(PostgreSQL log "table position already exists") 즉시 인용 정합
- `feedback_no_github_actions.md` — `scripts/release.sh`(PR #358) alternative + GHA 폐기 보존 정합
- `feedback_no_mock_data.md` — frontend Phase 3(PR #356) 0건 시 "데이터 없음" 표시 정합
- `feedback_honorific.md`·`feedback_swagger_pattern.md` — Phase 2·3 backend Swagger 4단 + 한국어 존댓말 정합
