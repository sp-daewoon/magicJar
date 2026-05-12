# ADR 0067 — T-RISK 리스크 관리 (Signal → Risk Gateway → Order 의무 게이트 + 영속 + 사용자 알림)

- 상태: **Accepted (2026-05-12 — 사용자 결정 A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1 모두 채택)**
- 작성일: 2026-05-12
- 작성자: architect
- 결정자: 사용자(최고 관리자) — 결정 의제 8건 검토 위임
- 진입 트랙: T-RISK (W3 세 번째 트랙, v3.5.0 미러스톤 후보)
- 관계:
  - **ADR 0024 (Risk Guard chain 6단)** — 본 ADR이 그 결정의 W3 구현 권위. PositionLimit·DrawdownStop·DailyLossLimit·TradeFrequencyLimit·MaxConcurrentPositions·KillSwitch 6단 골격 인용
  - **ADR 0066 (T-SIG 실시간 시그널 엔진)** — `signal.generated.v1` Kafka 토픽을 본 ADR RiskGateway consumer가 listen. T-SIG 시그널이 본 ADR Risk verdict를 통과해야 T-O 주문 진입 가능
  - **ADR 0023 (Signal Engine 룰 매칭 only, AI 호출 0)** — 본 ADR 동일 정책 계승. risk verdict 룰만, LLM 호출 0
  - **ADR 0042 (Position Bootstrap & Recovery)** — 부팅 시 KIS 잔고 워밍 + 본 ADR Position 도메인 정합 (잔고 ↔ position 동기)
  - **ADR 0043 (KillSwitch 행동 정의)** — 본 ADR 위반 누적 시 자동 KillSwitch ON 정합 (의제 F-2 옵션)
  - **ADR 0044 (Notification System)** — `risk.violation.v1` Kafka 토픽 신규 publish — Slack 통지 정합. P0/P1/P2 priority 매트릭스 확장
  - **ADR 0045 (Graceful Shutdown)** — RiskGateway Kafka listener container + risk-eval-pool SmartLifecycle 정합
  - **ADR 0061 (Backtest 결과 영속)** — 3 테이블 분리 + REST 5 endpoint 패턴 baseline 적용
  - **ADR 0066 §11-4 영향 ADR 매트릭스** — 본 ADR이 그 후속 합류 정합
  - **ADR 0030 (master-architecture SoT)** — §10 ADR 0067 row 추가 동반
  - **ADR 0007 (Kafka 이벤트 백본)** — `signal.generated.v1` listen + `risk.violation.v1` publish + `order.intent.v1` publish 정합
  - **ADR 0010 (Multi-tenant)** — userId 격리 의무 (risk_config·risk_event·position 모두 userId scoped)
  - **ADR 0051 (Spring Data Repository default fun 함정)** — 회피 패턴 의무
  - **ADR 0057 (KIS-only)** — 본 ADR은 KIS REST/WS 직접 호출 0건 (Kafka 내부만)

> **AI 호출 0 검증 의무 (ADR 0023 §2-4 + ADR 0066 §8 정합)**: 본 ADR 범위 내 모든 컴포넌트 코드에 LLM provider import(Anthropic SDK · Spring AI ChatClient · Gemini SDK · OpenAI SDK) 0건. sub-agent 디스패치 시 architect 1회 grep 검증 의무 + 코드 inline 주석 정책 명시. risk verdict는 **deterministic 룰만**.

> **KIS 키 정책 (ADR 0057 정합)**: 본 ADR은 KIS REST/WS 직접 호출 0건 — Kafka `signal.generated.v1` listen + Kafka `risk.violation.v1` publish + DB I/O만. Position 도메인은 ADR 0042 부트스트랩 시 KIS 잔고 동기(별도 트랙) 후 본 ADR이 그 영속 데이터를 사용. Rate Limit(ADR 0037) 영향 0.

---

## 1. 컨텍스트

### 1-1. T-RISK 트랙 위치 (tracks-roadmap §1·§2)

W3 자동 매매 본체 트랙 (룰만, AI 호출 0):
- T-BT (Backtest Engine) — **v3.3.1 마감**
- T-SIG (Signal Engine) — **v3.4.0 마감**
- **T-RISK (Risk Guard chain)** — 본 ADR 진입 (W3 세 번째 트랙)
- T-SC (Screener) — 후행
- T-O (Order Dispatch) — 후행 (본 ADR Risk verdict 통과 의무)
- T-RECON·T-NOTIFY·T-PERF — 후행

T-RISK는 **자동매매 본체의 안전망 노드**:
1. T-SIG 시그널이 무방비로 T-O 주문에 진입하면 사용자 자본 손실 risk 직접 노출
2. boundary 마감 시 동시 시그널 다발(시그널 burst) → 동시 진입 한도 부재 시 자본 over-leverage
3. 일일 누적 손실 사용자 설정 한도 초과 시 자동 진입 차단 의무
4. 거래 빈도 한도(rate limit) 부재 시 단일 strategy 폭주 가능
5. **AI 호출 0** (ADR 0023 §2-4 정합) — risk verdict는 deterministic 룰만

### 1-2. 현재 main 상태 (사전 자산 — v3.4.0 cut 완료)

자산 확보 완료:
- **ADR 0024** — Risk Guard chain 6단 골격 권위 (PositionLimit·DrawdownStop 등)
- **ADR 0066** — T-SIG 시그널 엔진 + `signal.generated.v1` Kafka topic + signal 테이블 영속
- **ADR 0061** — T-BT 백테스트 영속 (3 테이블 + REST 5 endpoint 패턴 baseline)
- **ADR 0042** — Position Bootstrap (부팅 시 KIS 잔고 워밍 baseline — 본 ADR Position 도메인 합류 진입점)
- **ADR 0044** — Notification System (`risk.violation.v1` payload type 확장 baseline)
- **ADR 0047** — DSL Sandbox (strategy-eval-pool 격리 — 본 ADR risk-eval-pool 패턴 차용)
- **Strategy 영속** (strategy 테이블, status 4단 + Redis cache)
- **Signal 영속** (signal 테이블, DB UQ + Redis SETNX 30s)
- **Kafka 백본** (`signal.generated.v1` · `risk.violation.v1` · `order.intent.v1` 정합)
- **STOMP 인프라** (boundary fanout + signal fanout — `/topic/risk.user.{userId}` 신규 row 추가)

본 ADR이 **위 자산을 Risk Gateway path에 통합 + 사용자 자본 보호 안전망 가동**.

### 1-3. fitness gap 분석 (사용자 메모리 `feedback_v3_skepticism_no_regression.md` lens)

#### 1-3-1. 자동매매에 필요한가?

**필수** — magicJar 비전 정의 (`project_system_vision.md`):
> "룰 기반 quant trading 본체 + AI 옵션 보조. 매매=룰/시그널"

T-RISK가 그 룰 본체의 **자본 보호 안전망**. 부재 시:
- T-SIG 시그널 → T-O 주문 직진 — 시그널 false positive 발생 시 자본 손실 직접
- 동시 시그널 burst 시 over-leverage (예: 5m boundary 09:05 동시 BUY 5종목 → 자본 100% 진입)
- 일일 누적 손실 -10% 도달 시 사용자가 명시 정지하지 않으면 -20%·-30%까지 진입 가능
- 단일 strategy 폭주 시 분단위 5건+ 주문 → 수수료 누적 + 슬리피지 손실

**자동매매 본체 안전성의 필수 노드**. T-O 진입 전 의무 게이트.

#### 1-3-2. v1/v2 잔재 아닌가?

**잔재 아님** — v3.x baseline 신규. ADR 0024(2026-05-03 Accepted, baseline 골격만 정의)·ADR 0042(2026-05-07 Accepted)·ADR 0043(2026-05-07 Accepted)·ADR 0044(2026-05-07 Accepted) 모두 v3.x 발행. 본 ADR이 그 자산의 W3 실제 구현 권위.

#### 1-3-3. 사용자 비전 정합

- magicJar = 룰 기반 본체 → 본 ADR risk verdict는 deterministic 룰만 (AI 호출 0)
- 사용자 = 최고 관리자/설계자 → 본 ADR `risk_config` 사용자별 영속 + REST CRUD UI 의무
- AI는 옵션 보조 → 본 ADR LLM 호출 0건 검증 의무 (§8)
- MCP 서버 노출(ADR 0027) → 향후 외부 client `risk_event` query API 정합

---

## 2. 결정 의제 (사용자 결정 대기)

architect 권장안 8건 + 각 의제 대안 매트릭스. 본 ADR `Accepted` 갱신 후 sub-agent 디스패치 진입.

### 2-1. 의제 A — 포지션 한도 (Position Limit)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **A-1 절대 금액 + 자본 비율 % 둘 다 (max(absolute, percent_of_capital))** ⭐ | `maxPositionAmount`(절대) + `maxPositionPercent`(자본 대비 %) 두 컬럼. 진입 시 두 값의 **min** 적용 (보수적) | 자본 변동 대응(자본 증가 시 비율 자동 확장) + 신규 사용자 안전 default(예: 비율 5% but 절대 100,000원 시 — 자본 < 2M이면 절대 우선) | 두 필드 사용자 입력 → UX 복잡 (한 줄 설명 의무) |
| A-2 절대 금액만 (`maxPositionAmount`) | 단일 컬럼 — 사용자가 명시 (예: 1,000,000원) | UX 단순 | 자본 변동 대응 0 — 자본 +50% 시에도 동일 한도 → 기회 손실 |
| A-3 자본 비율 %만 (`maxPositionPercent`) | 단일 컬럼 — 자본 대비 % (예: 10%) | 자본 변동 자동 대응 | 자본 변동 시점 race (현재 자본 fetch 의무 — 정확한 자본 산출 logic 의무) · 신규 사용자 자본 작을 때 비율 작아 진입 불가 |

**architect 권장 = A-1 둘 다 + min() 적용**

사유:
1. **자본 변동 대응 + 신규 사용자 안전** 동시 — 절대 한도가 안전 cap, 비율이 자본 확장 시 자동 확장
2. **기존 quant 모범** — 다수 quant 시스템(예: TradingView Pine alerts·QuantConnect)이 둘 다 지원
3. **UX** — UI에 "절대 한도 X원" + "또는 자본의 Y% (둘 중 작은 값 적용)" 한 줄 설명으로 cover
4. **min() 적용 사유** — 보수적 (자본 손실 보호 본질). max() 시 한쪽 한도 무력화 risk
5. **사용자 자본 fetch** — ADR 0042 Position Bootstrap 시점 잔고 cache(`balance:user:{userId}`) 활용 — risk verdict 시 1회 SELECT

### 2-2. 의제 B — 일일 손실 한도 (Daily Loss Limit, DLL)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **B-1 전역 + 절대 금액 또는 % (사용자 택1) + 자정 reset** ⭐ | `dailyLossLimitAmount` OR `dailyLossLimitPercent` (양자택일·NULL 한쪽) + KST 자정(00:00:00) reset. 도달 시 당일 모든 신규 시그널 BLOCKED | 사용자 자본 일일 보호 (대표 risk 룰) · 자정 reset 자동 · UX 단순 (택1) | 자본 일중 변동 시 `daily_pnl_cache` 정확성 의무 — position close 시 누적 |
| B-2 strategy별 + 전역 둘 다 | strategy당 `dailyLossLimit` + 전역 `dailyLossLimit` AND 적용 | 세밀 — 한 strategy 폭주 격리 가능 | 복잡도 ↑ · UI 폭주 (strategy 10개 × 한도 컬럼) · MVP 가치 미달 |
| B-3 strategy별만 | 전역 부재, strategy당만 | 격리 명확 | 전역 자본 보호 부재 — 5 strategy × 일일 -2% = 전역 -10% 가능 |

**architect 권장 = B-1 전역 + 사용자 택1 (절대 OR %) + 자정 reset**

사유:
1. **자본 일일 보호 본질** — 전역 한도가 사용자 자본 최대 보호 룰. 후속 ADR에서 strategy별 추가 가능
2. **택1 단순화** — 사용자 UX 부담 최소화. 둘 다 입력 가능하게 두면 충돌 risk (어느 게 우선?). 택1 + NULL 한쪽 enforce
3. **자정 reset** — KST 00:00:00 (장 시작 전) — `daily_pnl_cache:user:{userId}` Redis 키 TTL 또는 scheduled job
4. **`daily_pnl_cache` 정확성** — position close 시점 realized P&L 누적. unrealized P&L은 보조 cache (옵션 후속)
5. **B-2 폐기** — MVP 복잡도 과다 (사용자 8 strategy × DLL 입력 부담). v3.6.0+ 후속 검토
6. **B-3 폐기** — 전역 자본 보호 부재로 자본 erode risk

### 2-3. 의제 C — 동시 보유 종목 한도 (Max Concurrent Positions)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **C-1 사용자 설정 + default 5** ⭐ | `maxConcurrentPositions` INT column + UI 슬라이더(1~20) + DB default 5 | 사용자 명시 + 안전 default · 자본 분산 (5종목 균등 = 종목당 20%) · over-leverage 차단 | 사용자 입력 의무 (default 있어 부담 0) |
| C-2 고정 5종목 hardcode | 시스템 단일 default | UX 0 입력 | 사용자 자본·전략 다양성 무시 (자본 큰 사용자 5종목 vs 작은 사용자 5종목 동일) |
| C-3 자본 비율 (포지션당 % 역산) | `position_size_pct=10%` 시 자동 10종목 한도 | 자본 자동 분산 | 의제 A 포지션 한도와 conflict (둘 다 % 사용 시 어느 게 우선?) — 분리 명확 의무 |

**architect 권장 = C-1 사용자 설정 + default 5**

사유:
1. **safe default 5종목** — magicJar dogfooding 단계 기준 자본 보호 적정 (5종목 × 20% = 100% 풀 진입 시점)
2. **UI 슬라이더 1~20** — 사용자 자본·전략에 맞춰 조정 가능
3. **C-2 폐기** — 다양성 무시
4. **C-3 폐기** — 의제 A와 conflict 명확 분리 의무 (A=포지션 size · C=동시 종목 수 — orthogonal 룰)

### 2-4. 의제 D — 거래 빈도 한도 (Trade Frequency Limit, Rate Limit)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **D-1 strategy당 + 분당 + 일별 둘 다 + Redis sliding window** ⭐ | `maxTradesPerMinute`(strategy당, default 3) + `maxTradesPerDay`(strategy당, default 50) + Redis sliding window (`trades:freq:{strategyId}:{userId}:{window}`) | strategy 폭주 격리 (D-1 strategy당) · 분당 + 일별 dual cover (burst 차단 + 누적 차단) · Redis sliding window O(1) | 키 2건/strategy — Redis 부담 cumulative (strategy 10 × 2 = 20 키) — 가벼움 |
| D-2 전역만 + 분당만 | `maxTradesPerMinute` 전역 default 5 | 단순 | strategy별 격리 0 — 폭주 strategy가 다른 strategy까지 차단 |
| D-3 비활성 (1차) | 본 사이클 거래 빈도 한도 0 (의제 A·B·C로 충분 가정) | MVP 단순 | strategy false positive burst 미차단 — dogfooding 결함 risk |

**architect 권장 = D-1 strategy당 + 분당 + 일별 dual + Redis sliding window**

사유:
1. **strategy 격리 + dual cover** — 분당(burst 차단) + 일별(누적 차단) 매트릭스 cover
2. **Redis sliding window** — `ZADD trades:freq:{strategyId}:{userId}:minute {ts} {signalId}` + `ZRANGEBYSCORE` count. O(log N) 단순
3. **safe default** — strategy당 3/min + 50/day. 사용자 dogfooding 후 조정 가능
4. **D-2 폐기** — strategy 격리 부재 시 한 strategy 폭주가 사용자 전체 거래 차단 → UX 결함
5. **D-3 폐기** — MVP 본질 위반 (strategy false positive burst 미차단 risk)

### 2-5. 의제 E — 리스크 게이트 위치 (Risk Gateway Placement)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **E-1 Kafka `signal.generated.v1` 후 별도 RiskGateway consumer (Signal → Risk → Order intent 흐름)** ⭐ | RiskGateway consumer — `signal.generated.v1` listen → risk verdict → `order.intent.v1` publish (or BLOCKED 발행) | strategy 패턴 일관 (T-SIG sandbox + 본 ADR risk-eval-pool) · async + retry · audit trail (Kafka 영속) · 시그널 영속 + 리스크 verdict 영속 분리 명확 · T-O가 후행 consumer로 단순 진입 | Kafka 1 hop 추가 (latency ~20ms) — 사용자 자본 보호 본질 대비 미미 |
| E-2 T-SIG SignalEngine 내부 통합 (signal 발행 직전 immediate reject) | RealtimeSignalGenerator 내부에서 risk verdict 호출 → reject 시 signal 미발행 | latency 최소 (0 hop) | T-SIG와 T-RISK 도메인 혼선 · ADR 0023 §2-1 Signal 도메인 본질 위반 (signal = 룰 매칭 결과, risk = 자본 보호) · 시그널 영속 부재 → audit 손실 ("어떤 시그널이 어떤 risk로 차단됐는지" 불가) |
| E-3 T-O OrderRouter 직전 (가장 늦은 시점) | OrderRouter 진입 직전 risk verdict | T-O 도메인 일원화 | T-O가 T-RISK 도메인 의존 — T-O 트랙 진입 전 본 ADR 부재 시 안전망 0 · signal 영속에는 ALLOWED 상태가 부재 (검증 추적 부재) |

**architect 권장 = E-1 Kafka consumer (Signal → Risk → Order intent 흐름)**

사유:
1. **strategy 패턴 일관** — T-SIG가 Kafka `signal.generated.v1` publish → 본 ADR consumer가 listen → verdict 후 `order.intent.v1` publish. 후행 T-O는 또 다른 consumer (단순 chain)
2. **audit trail** — signal 영속(ADR 0066) + risk_event 영속(본 ADR) — 어떤 시그널이 어떤 룰로 ALLOWED/BLOCKED 됐는지 시계열 추적
3. **retry + async** — Kafka 백본 활용 → consumer 재시작 시 미처리 시그널 자동 재처리
4. **latency 미미** — Kafka 1 hop ~20ms. 자본 보호 본질 대비 무시
5. **T-O 단순화** — T-O는 `order.intent.v1` listen만 — risk 도메인 의존 0 (decoupled)
6. **E-2 폐기** — 도메인 혼선 + audit 손실
7. **E-3 폐기** — T-O 의존성 + signal verdict 추적 부재

### 2-6. 의제 F — 위반 시 처리 (Violation Handling)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **F-1 signal에 `risk_status` 컬럼 + risk_event INSERT + STOMP 알림 + auto-PAUSE strategy (반복 위반 시)** ⭐ | (a) signal.risk_status = ALLOWED·BLOCKED 컬럼 갱신 (b) risk_event INSERT (verdict 이력) (c) STOMP `/topic/risk.user.{userId}` BLOCKED payload (d) 동일 strategy 연속 N=10회 BLOCKED 시 auto-PAUSED + STOMP 알림 | 명시적 audit + 사용자 즉시 인지 + 폭주 strategy 자동 보호 (사용자 부재 시에도 자본 보호) | signal 컬럼 추가 + risk_event 테이블 + auto-PAUSE 로직 — 구현 행수 ~150 LOC (가벼움) |
| F-2 silent skip (시그널 무시, 알림 0) | risk_event 영속도 0 | 코드 최소 | audit 부재 + 사용자 인지 불가 — 안전망 본질 위반 |
| F-3 strategy 자동 DISABLED (auto-PAUSED보다 강한 처리) | 단 1회 BLOCKED 시 strategy DISABLED + 사용자 명시 [다시 활성화] 의무 | 폭주 강제 차단 | over-reaction risk (시장 변동 시 정상 BLOCKED 패턴도 즉시 DISABLED → 사용자 혼란) |

**architect 권장 = F-1 4단 처리 (signal column + risk_event + STOMP + auto-PAUSE 반복 시)**

사유:
1. **명시적 audit** — risk_event 시계열 영속 + signal.risk_status 컬럼 (signal 시계열 조회 시 risk verdict 함께)
2. **사용자 즉시 인지** — STOMP `/topic/risk.user.{userId}` 토스트 + risk_event 시계열 페이지
3. **auto-PAUSE 반복 시** — 연속 10회 BLOCKED 시 strategy 자동 PAUSED (DISABLED 아님 — 사용자 즉시 재활성화 가능) + STOMP 알림. 폭주 strategy 자동 격리
4. **F-2 폐기** — silent 처리 시 사용자 자본 손실 인지 불가 — 안전망 본질 위반
5. **F-3 폐기** — over-reaction — 단 1회 BLOCKED는 정상 패턴(예: 일일 한도 도달 후 마감 시점 시그널). 반복(N=10)이 폭주 신호

**threshold 10회 산출 사유**: 사용자 dogfooding 단계 기준 strategy 1개당 평균 시그널 ~50/day. 그 중 10회 연속 BLOCKED = 5분 burst 미만 polluting strategy 격리. 후속 사용자 설정 가능(`auto_pause_threshold` 컬럼)

### 2-7. 의제 G — 영속 매트릭스 (Persistence Matrix)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **G-1 risk_config 신규 (사용자별 1 row) + risk_event 신규 (위반 이력) + signal.risk_status 컬럼 추가 + position 신규 (현재 보유)** ⭐ | 4 테이블/컬럼 dedicated | 도메인 분리 명확 · risk_event 시계열 audit · position projection 분리 (ADR 0042 정합) · signal.risk_status로 시그널-리스크 cross-ref O(1) | 테이블 3개 + 컬럼 1건 신규 — 운영 부담 가벼움 |
| G-2 risk_config + risk_event만 (signal·position 컬럼 추가 0) | 2 테이블 신규 | 운영 부담 최소 | signal-risk cross-ref 시 JOIN 2건 의무 (signal·risk_event) — 조회 latency ↑ · position 현재 보유 매트릭스 부재 (ADR 0042 Position Bootstrap만 부팅 시점 — 본 ADR이 시계열 영속 보강 필요) |
| G-3 단일 risk_log JSONB 테이블 (config·event·position 통합) | 1 테이블 + JSONB type 컬럼 | 운영 부담 최소 | type별 조회 JSONB query (성능 ↓) · 도메인 혼선 · ADR 0061 T-BT 3 테이블 분리 패턴 회귀 |

**architect 권장 = G-1 4 테이블/컬럼 dedicated (risk_config + risk_event + signal.risk_status + position)**

사유:
1. **도메인 분리** — config(사용자 설정)·event(위반 이력)·signal.risk_status(시그널 verdict 결과)·position(현재 보유) — 4 도메인 분리 명확
2. **ADR 0061 T-BT 패턴 정합** — 3 테이블(backtest_run·backtest_trade·backtest_position) baseline + 본 ADR도 4 테이블 분리 일관
3. **JOIN 부담 최소** — signal.risk_status 컬럼으로 signal list 조회 시 verdict 동반 표시 (JOIN 0)
4. **position 신규** — ADR 0042 Position Bootstrap은 부팅 시점 워밍만. 본 ADR이 시계열 영속(현재 보유 + 누적 P&L 산출 baseline). 후행 T-O 트랙이 진입/청산 시 갱신
5. **G-2 폐기** — signal-risk JOIN 부담 + position 부재
6. **G-3 폐기** — JSONB query 성능 + 도메인 혼선

### 2-8. 의제 H — 설정 source (RiskConfig Default + Override)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **H-1 사용자별 risk_config 테이블 + 전역 default seed (system_config 또는 SQL seed)** ⭐ | risk_config (userId UQ) 사용자별 단일 row · 부재 시 system_config default seed 적용 · UI 첫 진입 시 default fill | 사용자 격리 + safe default · 신규 사용자도 시그널 진입 즉시 안전망 가동 · multi-tenant(ADR 0010) 정합 | system_config 또는 SQL seed 매트릭스 유지 의무 |
| H-2 strategy별 inline (strategy 테이블에 risk 컬럼 추가) | strategy 등록 시 risk 파라미터 동반 | 세밀 — strategy별 risk 차별화 | strategy 10개 × risk 7 컬럼 = 70 컬럼 strategy 테이블 폭증 + 전역 자본 보호 부재 |
| H-3 전역 default only (사용자 override 0) | 전역 하드코드 또는 system_config 단일 | 가장 단순 | 사용자 자본·전략 다양성 무시 — 자본 1억 vs 100만 동일 한도 부조리 |

**architect 권장 = H-1 사용자별 risk_config + 전역 default seed**

사유:
1. **multi-tenant 정합** — ADR 0010 + ADR 0046 (사용자별 격리) 일관
2. **safe default** — 신규 사용자 risk_config 부재 시 system_config의 보수적 default(예: 일일 -3% · 동시 5종목 · 분당 3건) 적용 → 즉시 안전망 가동
3. **REST PUT으로 사용자 override** — UI에서 사용자 직접 조정
4. **H-2 폐기** — strategy 테이블 폭증 + 전역 보호 부재
5. **H-3 폐기** — 사용자 다양성 무시

---

## 3. 권장 결정 종합

| 의제 | 권장안 | 핵심 사유 |
|---|---|---|
| A. 포지션 한도 | **A-1** 절대 + % 둘 다 min() 적용 | 자본 변동 대응 + 신규 사용자 안전 + 보수적 |
| B. 일일 손실 한도 | **B-1** 전역 + 택1 + 자정 reset | 자본 일일 보호 본질 + UX 단순 + safe default |
| C. 동시 종목 한도 | **C-1** 사용자 설정 + default 5 | 자본 분산 + UI 슬라이더 + safe default |
| D. 거래 빈도 한도 | **D-1** strategy당 분당 + 일별 dual + Redis sliding | strategy 격리 + burst·누적 cover · O(1) |
| E. 게이트 위치 | **E-1** Kafka consumer (Signal → Risk → Order) | strategy 패턴 일관 + audit trail + T-O decoupled |
| F. 위반 처리 | **F-1** 4단(column + event + STOMP + auto-PAUSE) | audit + 사용자 인지 + 폭주 격리 |
| G. 영속 매트릭스 | **G-1** 4 테이블/컬럼 dedicated | 도메인 분리 + ADR 0061 baseline 일관 |
| H. 설정 source | **H-1** 사용자별 + 전역 default seed | multi-tenant + safe default + 신규 사용자 안전 |

---

## 4. 채택 시 REST API 설계 (G-1 + H-1 정합)

### 4-1. RiskConfig CRUD (사용자 risk 설정 관리)

| 메서드 | 경로 | 기능 | request | response |
|---|---|---|---|---|
| `GET` | `/api/risk-config` | 사용자 risk 설정 조회 (부재 시 default seed 반환) | (auth) | `{userId, maxPositionAmount, maxPositionPercent, dailyLossLimitAmount?, dailyLossLimitPercent?, maxConcurrentPositions, maxTradesPerMinute, maxTradesPerDay, autoPauseThreshold, updatedAt}` |
| `PUT` | `/api/risk-config` | 사용자 risk 설정 갱신 (upsert) | `{maxPositionAmount, maxPositionPercent, ...}` | (위와 동일) |
| `POST` | `/api/risk-config/reset` | default 값으로 reset | (auth) | (위와 동일) |

### 4-2. RiskEvent 조회 (위반 이력 시계열)

| 메서드 | 경로 | 기능 | request | response |
|---|---|---|---|---|
| `GET` | `/api/risk-events` | 시계열 페이징 | `?userId&strategyId&rule&verdict&from&to&page&size` | `{content: [...], totalElements, totalPages}` |
| `GET` | `/api/risk-events/{id}` | 단건 (rule·verdict·reason·signalId·context) | (auth) | `{id, userId, signalId, strategyId, rule, verdict, reason, contextJson, createdAt}` |

### 4-3. Position 조회 (현재 보유)

| 메서드 | 경로 | 기능 | request | response |
|---|---|---|---|---|
| `GET` | `/api/positions/current` | 현재 보유 포지션 목록 | (auth) | `[{code, quantity, avgPrice, currentPrice, unrealizedPnl, openedAt, mode}]` |
| `GET` | `/api/positions/history` | 청산 이력 페이징 | `?from&to&page&size` | `{content: [...], totalElements}` |

### 4-4. 인증·인가

- userId는 JWT 또는 session 기반 (T-ONBOARD ADR 0046 정합)
- multi-tenant(ADR 0010) — risk_config.userId · risk_event.userId · position.userId 격리. 다른 사용자 조회 차단(404 — 존재 여부 노출 금지, ADR 0061 §2-7 패턴)

**총 7 endpoint** (RiskConfig 3 + RiskEvent 2 + Position 2). ADR 0061 5 endpoint + ADR 0066 8 endpoint 패턴 정합.

---

## 5. 채택 시 도메인 모델 (Pure Kotlin — domain 모듈)

### 5-1. RiskConfig 본체

```kotlin
// domain/.../risk/RiskConfig.kt
data class RiskConfig(
    val userId: String,
    val maxPositionAmount: BigDecimal,         // 절대 금액 (의제 A)
    val maxPositionPercent: BigDecimal,        // 자본 비율 % (의제 A)
    val dailyLossLimitAmount: BigDecimal?,     // 일일 손실 한도 절대 (의제 B, NULL 또는)
    val dailyLossLimitPercent: BigDecimal?,    // 일일 손실 한도 % (의제 B, NULL 또는)
    val maxConcurrentPositions: Int,           // 동시 보유 종목 한도 (의제 C, default 5)
    val maxTradesPerMinute: Int,               // strategy당 분당 (의제 D, default 3)
    val maxTradesPerDay: Int,                  // strategy당 일별 (의제 D, default 50)
    val autoPauseThreshold: Int,               // 연속 BLOCKED N회 시 auto-PAUSE (의제 F, default 10)
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(maxPositionAmount > BigDecimal.ZERO) { "maxPositionAmount must be positive" }
        require(maxPositionPercent in BigDecimal("0.01")..BigDecimal("100.00")) { "maxPositionPercent must be 0.01~100.00" }
        require(maxConcurrentPositions in 1..50) { "maxConcurrentPositions must be 1~50" }
        require(maxTradesPerMinute in 1..60) { "maxTradesPerMinute must be 1~60" }
        require(maxTradesPerDay in 1..1000) { "maxTradesPerDay must be 1~1000" }
        require(autoPauseThreshold in 1..100) { "autoPauseThreshold must be 1~100" }
        require((dailyLossLimitAmount != null) xor (dailyLossLimitPercent != null) || (dailyLossLimitAmount == null && dailyLossLimitPercent == null)) {
            "dailyLossLimit: amount or percent (xor) or both null"
        }
    }

    companion object {
        // H-1 전역 default seed
        fun defaultFor(userId: String, now: Instant): RiskConfig = RiskConfig(
            userId = userId,
            maxPositionAmount = BigDecimal("1000000"),     // 100만원
            maxPositionPercent = BigDecimal("10.00"),      // 10%
            dailyLossLimitAmount = null,
            dailyLossLimitPercent = BigDecimal("3.00"),    // 일일 -3%
            maxConcurrentPositions = 5,
            maxTradesPerMinute = 3,
            maxTradesPerDay = 50,
            autoPauseThreshold = 10,
            createdAt = now,
            updatedAt = now,
        )
    }
}
```

### 5-2. RiskEvent 본체

```kotlin
// domain/.../risk/RiskEvent.kt
data class RiskEvent(
    val id: Long,
    val userId: String,
    val signalId: Long?,                       // 시그널 cross-ref (자동 BLOCKED 시 not null)
    val strategyId: Long?,                     // strategy cross-ref
    val rule: RiskRule,                        // 위반 룰
    val verdict: RiskVerdict,                  // ALLOWED·BLOCKED
    val reason: String,                        // 사람 읽을 수 있는 사유
    val contextJson: String,                   // 평가 context (현재 잔고·일일 P&L·동시 종목 수 등)
    val createdAt: Instant,
)

enum class RiskRule {
    POSITION_LIMIT,         // 의제 A
    DAILY_LOSS_LIMIT,       // 의제 B
    MAX_CONCURRENT_POSITIONS, // 의제 C
    TRADE_FREQUENCY_MINUTE, // 의제 D 분당
    TRADE_FREQUENCY_DAY,    // 의제 D 일별
    KILL_SWITCH_ON,         // ADR 0043 정합 후속
    USER_DISABLED,          // strategy.status=DISABLED 도달 시
}

enum class RiskVerdict { ALLOWED, BLOCKED }
```

### 5-3. RiskVerdict sealed class (의제 E-1 RiskGateway 반환 타입)

```kotlin
// domain/.../risk/RiskGatewayResult.kt
sealed class RiskGatewayResult {
    data class Allowed(val signalId: Long) : RiskGatewayResult()
    data class Blocked(val signalId: Long, val rule: RiskRule, val reason: String, val context: Map<String, Any>) : RiskGatewayResult()
}
```

### 5-4. Position 본체

```kotlin
// domain/.../position/Position.kt
data class Position(
    val id: Long,
    val userId: String,
    val code: String,
    val quantity: Int,
    val avgPrice: BigDecimal,                  // 평균 매수가
    val currentPrice: BigDecimal?,             // 마지막 평가가 (Redis 시세 cache)
    val realizedPnl: BigDecimal,               // 실현 P&L (청산 누적)
    val unrealizedPnl: BigDecimal?,            // 미실현 P&L (currentPrice 기반)
    val openedAt: Instant,
    val closedAt: Instant?,                    // null이면 보유 중
    val mode: TradingMode,                     // MOCK · REAL (ADR 0011 정합)
    val updatedAt: Instant,
)

enum class TradingMode { MOCK, REAL }
```

### 5-5. Port 인터페이스 (헥사고날)

```kotlin
// domain/.../risk/RiskConfigRepository.kt
interface RiskConfigRepository {
    fun findByUserId(userId: String): RiskConfig?
    fun save(config: RiskConfig): RiskConfig
}

// domain/.../risk/RiskEventRepository.kt
interface RiskEventRepository {
    fun save(event: RiskEvent): RiskEvent
    fun findByUser(userId: String, from: Instant, to: Instant, pageable: Pageable): Page<RiskEvent>
    fun findById(id: Long): RiskEvent?
    fun countRecentBlockedByStrategy(strategyId: Long, windowMinutes: Long): Int  // auto-PAUSE 판정용
}

// domain/.../position/PositionRepository.kt
interface PositionRepository {
    fun findCurrentByUser(userId: String): List<Position>             // closedAt IS NULL
    fun findByUserAndCode(userId: String, code: String): Position?
    fun findHistoryByUser(userId: String, from: Instant, to: Instant, pageable: Pageable): Page<Position>
    fun save(position: Position): Position
    fun countCurrentByUser(userId: String): Int                       // 의제 C 동시 보유 수
    fun sumDailyRealizedPnl(userId: String, kstMidnight: Instant): BigDecimal // 의제 B 일일 P&L
}

// domain/.../risk/RiskGateway.kt — verdict 결정 본체 port (의제 E-1)
interface RiskGateway {
    fun evaluate(signal: Signal, context: RiskContext): RiskGatewayResult
}

// domain/.../risk/RiskNotifier.kt — STOMP fanout port
interface RiskNotifier {
    fun notifyViolation(userId: String, event: RiskEvent)
    fun notifyAutoPaused(userId: String, strategyId: Long, reason: String)
}

// domain/.../signal/SignalRiskStatusUpdater.kt — signal 컬럼 갱신 port
interface SignalRiskStatusUpdater {
    fun markAllowed(signalId: Long)
    fun markBlocked(signalId: Long, rule: RiskRule, reason: String)
}
```

### 5-6. RiskContext

```kotlin
// domain/.../risk/RiskContext.kt
data class RiskContext(
    val userId: String,
    val config: RiskConfig,
    val currentCapital: BigDecimal,            // 사용자 잔고 (ADR 0042 cache)
    val currentPositions: List<Position>,      // 현재 보유 (PositionRepository.findCurrentByUser)
    val dailyRealizedPnl: BigDecimal,          // KST 자정 ~ 현재 누적 (PositionRepository.sumDailyRealizedPnl)
    val recentTradesMinute: Int,               // Redis sliding window count
    val recentTradesDay: Int,                  // Redis sliding window count
    val killSwitchOn: Boolean,                 // ADR 0043 정합
    val nowKst: Instant,
)
```

---

## 6. 채택 시 영속 매트릭스 (Flyway V16·V17·V18 + signal 컬럼 추가)

> **Flyway 번호 정합**: ADR 0066이 V14·V15 발행. ADR 0061이 V12·V13. 본 ADR이 V16·V17·V18·V19 사용 (단, ADR 0066 §6-3 strategy_eval_metric V16 예약 검토 의무 — 사용자 결정 시점에 재할당 또는 V20·V21·V22로 시프트).

### 6-1. `risk_config` 테이블 (의제 H-1 신규 V16)

```sql
-- Flyway V16
CREATE TABLE risk_config (
    user_id                   VARCHAR(40) PRIMARY KEY,
    max_position_amount       NUMERIC(15,2) NOT NULL,
    max_position_percent      NUMERIC(7,4) NOT NULL,
    daily_loss_limit_amount   NUMERIC(15,2),
    daily_loss_limit_percent  NUMERIC(7,4),
    max_concurrent_positions  INT NOT NULL DEFAULT 5,
    max_trades_per_minute     INT NOT NULL DEFAULT 3,
    max_trades_per_day        INT NOT NULL DEFAULT 50,
    auto_pause_threshold      INT NOT NULL DEFAULT 10,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),

    CHECK (max_position_amount > 0),
    CHECK (max_position_percent BETWEEN 0.01 AND 100.00),
    CHECK (max_concurrent_positions BETWEEN 1 AND 50),
    CHECK (max_trades_per_minute BETWEEN 1 AND 60),
    CHECK (max_trades_per_day BETWEEN 1 AND 1000),
    CHECK (auto_pause_threshold BETWEEN 1 AND 100),
    CHECK (
        (daily_loss_limit_amount IS NULL AND daily_loss_limit_percent IS NULL)
        OR (daily_loss_limit_amount IS NOT NULL AND daily_loss_limit_percent IS NULL)
        OR (daily_loss_limit_amount IS NULL AND daily_loss_limit_percent IS NOT NULL)
    )
);
```

### 6-2. `risk_event` 테이블 (의제 G-1 신규 V17)

```sql
-- Flyway V17
CREATE TABLE risk_event (
    id              BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(40) NOT NULL,
    signal_id       BIGINT REFERENCES signal(id) ON DELETE SET NULL,
    strategy_id     BIGINT REFERENCES strategy(id) ON DELETE SET NULL,
    rule            VARCHAR(32) NOT NULL,    -- POSITION_LIMIT · DAILY_LOSS_LIMIT · MAX_CONCURRENT_POSITIONS · TRADE_FREQUENCY_MINUTE · TRADE_FREQUENCY_DAY · KILL_SWITCH_ON · USER_DISABLED
    verdict         VARCHAR(8) NOT NULL,     -- ALLOWED · BLOCKED
    reason          TEXT NOT NULL,
    context_json    JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_risk_event_user_ts ON risk_event (user_id, created_at DESC);
CREATE INDEX idx_risk_event_strategy_blocked_recent ON risk_event (strategy_id, created_at DESC) WHERE verdict = 'BLOCKED';
CREATE INDEX idx_risk_event_signal ON risk_event (signal_id);
```

### 6-3. `position` 테이블 (의제 G-1 신규 V18)

```sql
-- Flyway V18
CREATE TABLE position (
    id              BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(40) NOT NULL,
    code            VARCHAR(8) NOT NULL,
    quantity        INT NOT NULL,
    avg_price       NUMERIC(15,4) NOT NULL,
    current_price   NUMERIC(15,4),
    realized_pnl    NUMERIC(15,4) NOT NULL DEFAULT 0,
    unrealized_pnl  NUMERIC(15,4),
    opened_at       TIMESTAMPTZ NOT NULL,
    closed_at       TIMESTAMPTZ,
    mode            VARCHAR(8) NOT NULL,     -- MOCK · REAL
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_position_user_current ON position (user_id) WHERE closed_at IS NULL;
CREATE INDEX idx_position_user_code_open ON position (user_id, code) WHERE closed_at IS NULL;
CREATE INDEX idx_position_user_closed_ts ON position (user_id, closed_at DESC) WHERE closed_at IS NOT NULL;
```

### 6-4. `signal` 테이블 컬럼 추가 (의제 F-1 신규 V19)

```sql
-- Flyway V19
ALTER TABLE signal
    ADD COLUMN risk_status VARCHAR(16) NOT NULL DEFAULT 'PENDING';   -- PENDING · ALLOWED · BLOCKED

-- 인덱스 — risk_status 페이징 조회 시
CREATE INDEX idx_signal_user_risk_ts ON signal (user_id, risk_status, ts DESC);
```

기존 signal row는 default 'PENDING' (본 ADR 채택 시점 이전 시그널). 채택 후 신규 시그널은 RiskGateway consumer가 'ALLOWED' 또는 'BLOCKED'로 갱신.

---

## 7. 채택 시 실행 엔진 매트릭스 (RiskGateway consumer + 평가 흐름)

### 7-1. 컴포넌트 매트릭스

| 컴포넌트 | 위치 | 책임 |
|---|---|---|
| **SignalGeneratedKafkaListener** | `consumer/.../risk/kafka/SignalGeneratedKafkaListener.kt` | `signal.generated.v1` listen → RiskGatewayEvaluator 위임. partition key = userId (동일 사용자 순차 처리) |
| **RiskGatewayEvaluator** | `consumer/.../risk/RiskGatewayEvaluator.kt` | RiskGateway port 구현 본체. 6 룰 순차 평가 (POSITION_LIMIT → MAX_CONCURRENT_POSITIONS → TRADE_FREQUENCY_MINUTE → TRADE_FREQUENCY_DAY → DAILY_LOSS_LIMIT → KILL_SWITCH_ON) — short-circuit (첫 BLOCKED 발생 시 즉시 반환) |
| **TradeFrequencyRedisGuard** | `consumer/.../risk/TradeFrequencyRedisGuard.kt` | 의제 D Redis sliding window (`trades:freq:{strategyId}:{userId}:minute` ZSET + `trades:freq:{strategyId}:{userId}:day` ZSET). `tryConsume(strategyId, userId, signalId, ts)` 반환 true/false |
| **DailyPnlCalculator** | `consumer/.../risk/DailyPnlCalculator.kt` | 의제 B — `PositionRepository.sumDailyRealizedPnl(userId, kstMidnight)` 호출 + Redis cache(`daily_pnl:{userId}:{date}` TTL 자정까지) |
| **CapitalSnapshotProvider** | `consumer/.../risk/CapitalSnapshotProvider.kt` | 사용자 잔고 fetch (ADR 0042 `balance:user:{userId}` cache 우선 → miss 시 DB) |
| **AutoPauseGuard** | `consumer/.../risk/AutoPauseGuard.kt` | 의제 F — strategy 연속 BLOCKED 검사. `RiskEventRepository.countRecentBlockedByStrategy(strategyId, windowMinutes=10)` ≥ autoPauseThreshold 시 strategy.status=PAUSED 전환 |
| **RiskEventPersister** | `api/.../persistence/risk/RiskEventJpaAdapter.kt` | risk_event INSERT |
| **RiskConfigPersister** | `api/.../persistence/risk/RiskConfigJpaAdapter.kt` | risk_config upsert (사용자별 1 row) |
| **PositionPersister** | `api/.../persistence/position/PositionJpaAdapter.kt` | position 영속 (closedAt NULL = 보유 중) |
| **StompRiskNotifier** | `api/.../stomp/StompRiskNotifier.kt` | `/topic/risk.user.{userId}` STOMP fanout (BLOCKED · AUTO_PAUSED 2 event type) |
| **RiskViolationKafkaProducer** | `consumer/.../risk/kafka/RiskViolationKafkaProducer.kt` | `risk.violation.v1` Kafka publish (ADR 0044 Notification 진입점) |
| **OrderIntentKafkaProducer** | `consumer/.../risk/kafka/OrderIntentKafkaProducer.kt` | ALLOWED 시 `order.intent.v1` publish (T-O 후행 consumer 진입점) |
| **RiskConfigController** | `api/.../controller/RiskConfigController.kt` | REST 3 endpoint (GET·PUT·POST reset) |
| **RiskEventController** | `api/.../controller/RiskEventController.kt` | REST 2 endpoint (GET list·GET single) |
| **PositionController** | `api/.../controller/PositionController.kt` | REST 2 endpoint (GET current·GET history) |

### 7-2. 평가 흐름 (의제 E-1 정합)

```
Kafka signal.generated.v1 (partition by userId)
   ↓
SignalGeneratedKafkaListener.onMessage(signalPayload)
   ├─ signal payload deserialize (signalId · userId · strategyId · code · interval · side · strength · ...)
   └─ RiskGatewayEvaluator.evaluate(signal)
        ↓
1. RiskConfigRepository.findByUserId(userId)
   └─ 부재 시 RiskConfig.defaultFor(userId) seed (의제 H-1)
2. RiskContext 빌드:
   - CapitalSnapshotProvider.currentCapital(userId) (Redis cache → DB)
   - PositionRepository.findCurrentByUser(userId)
   - DailyPnlCalculator.dailyRealizedPnl(userId, kstMidnight)
   - TradeFrequencyRedisGuard.recentCounts(strategyId, userId)
   - KillSwitchRepository.isOn(userId) (ADR 0043 정합)
3. 6 룰 순차 평가 (short-circuit, 첫 BLOCKED 시 즉시 반환):
   a. KILL_SWITCH_ON — killSwitchOn=true 시 BLOCKED 즉시
   b. DAILY_LOSS_LIMIT — dailyRealizedPnl <= -limit 시 BLOCKED
   c. MAX_CONCURRENT_POSITIONS — currentPositions.size >= maxConcurrentPositions 시 BLOCKED (BUY만, SELL은 청산 → 통과)
   d. TRADE_FREQUENCY_MINUTE — recentTradesMinute >= maxTradesPerMinute 시 BLOCKED
   e. TRADE_FREQUENCY_DAY — recentTradesDay >= maxTradesPerDay 시 BLOCKED
   f. POSITION_LIMIT — signal.priceAtSignal × quantity > min(maxPositionAmount, currentCapital × maxPositionPercent/100) 시 BLOCKED
4. RiskGatewayResult 분기:
   - Allowed(signalId):
     - RiskEventRepository.save(RiskEvent(rule=null, verdict=ALLOWED, ...))  // audit (optional, 성능 부담 시 sample)
     - SignalRiskStatusUpdater.markAllowed(signalId)  // signal.risk_status = 'ALLOWED'
     - TradeFrequencyRedisGuard.consume(strategyId, userId, signalId, ts)  // sliding window 누적
     - OrderIntentKafkaProducer.publish(order.intent.v1, key=userId)  // T-O 후행 진입
   - Blocked(signalId, rule, reason, context):
     - RiskEventRepository.save(RiskEvent(rule, verdict=BLOCKED, reason, contextJson))
     - SignalRiskStatusUpdater.markBlocked(signalId, rule, reason)  // signal.risk_status = 'BLOCKED'
     - StompRiskNotifier.notifyViolation(userId, riskEvent)  // /topic/risk.user.{userId}
     - RiskViolationKafkaProducer.publish(risk.violation.v1, key=userId)  // ADR 0044 Notification 진입
     - AutoPauseGuard.checkAndPauseIfNeeded(strategyId):
         └─ countRecentBlockedByStrategy(strategyId, 10min) >= autoPauseThreshold 시
             ├─ Strategy.status = PAUSED + disabled_reason = AUTO_RISK_PAUSED
             ├─ StompRiskNotifier.notifyAutoPaused(userId, strategyId)
             └─ ADR 0066 StrategyCache evict
```

### 7-3. latency 예측

| 단계 | 예상 latency |
|---|---|
| Kafka `signal.generated.v1` deliver | <20ms |
| SignalGeneratedKafkaListener → RiskGatewayEvaluator | <5ms |
| RiskConfigRepository.findByUserId (Redis cache hit) | <2ms |
| RiskContext 빌드 (Capital + Positions + DailyPnl + RedisGuard counts) | <15ms (Redis 4 hit + DB count 1) |
| 6 룰 순차 평가 (in-memory) | <5ms |
| RiskEvent INSERT + Signal UPDATE + Kafka publish + STOMP fanout | <25ms |
| **end-to-end (signal.generated.v1 publish → order.intent.v1 publish)** | **<75ms** |

ADR 0066 §7-3 signal pipeline <100ms + 본 ADR <75ms = end-to-end **<175ms** (boundary 마감 → 사용자 알림 + 주문 진입 의도).

### 7-4. 비동기 · concurrency

- Kafka listener container — 1 thread per consumer (partition by userId — 동일 사용자 순차 처리, race 0)
- risk-eval-pool 별도 필요성? — **불필요**. 평가 자체가 in-memory + Redis/DB 단순 (<20ms). ADR 0047 strategy-eval-pool 패턴과 달리 deterministic 룰만이라 sandbox 격리 불필요.
- 다중 사용자 동시 진입 — Kafka partition by userId — 사용자 N명 동시 처리 시 N partition 분산

---

## 8. AI 호출 0 검증 (ADR 0023 §2-4 + ADR 0066 §8 의무)

본 ADR scope 내 코드 의무:
- T-RISK 영역 코드 어디에도 LLM provider import 0건
- sub-agent 디스패치 시 architect 1회 grep 검증 의무
- 검증 대상 import 패턴:
  - `import org.springframework.ai.*`
  - `import com.anthropic.*`
  - `import com.openai.*`
  - `import com.google.cloud.aiplatform.*`
- 코드 inline 주석 정책: `// AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + 본 ADR §8)`

W4 트랙(T-AI) 진입 전까지 동일 정책 유지.

---

## 9. 운영 영향

### 9-1. DB ROW 부담

| 테이블 | 가정 | 예상 row |
|---|---|---|
| `risk_config` | 사용자 1명 = 1 row | ~1~10 (사용자 수만큼) |
| `risk_event` | 사용자 1명 시그널 1,000/day × ALLOWED 80% + BLOCKED 20% = 1,000 event/day · BLOCKED만 영속 시 200/day | ~70K/year (BLOCKED only) 또는 ~365K/year (ALL) |
| `position` | open + close 약 50/month (사용자 dogfooding 단계) | ~600/year per user |

**architect 권장**: ALLOWED verdict는 `risk_event` 영속 0 (signal.risk_status 컬럼으로 cross-ref 가능) — BLOCKED만 영속. 1년 ~70K row · 5MB. partition 불필요.

### 9-2. Kafka 부하

| topic | msg/day | 비고 |
|---|---|---|
| `signal.generated.v1` (listen) | ~1,000 | 본 ADR scope X (ADR 0066 publish) |
| `risk.violation.v1` (publish) | ~200 (BLOCKED 20%) | ADR 0044 Notification 진입점 |
| `order.intent.v1` (publish) | ~800 (ALLOWED 80%) | T-O 후행 consumer 진입점 |

**총 부하** ≈ 1,000 msg/day = 0.012 msg/s. 가벼움.

### 9-3. STOMP 부하

| topic | msg/day |
|---|---|
| `/topic/risk.user.{userId}` | ~200 (BLOCKED만 토스트) |

0.0023 msg/s — 매우 가벼움.

### 9-4. Redis 부담

| 키 | 용도 | 메모리 |
|---|---|---|
| `risk_config:{userId}` | RiskConfig cache (TTL 1h) | ~500B × 사용자 N = <10KB |
| `trades:freq:{strategyId}:{userId}:minute` ZSET | 의제 D 분당 sliding | strategy 10 × 사용자 N × ~1KB peak = <100KB |
| `trades:freq:{strategyId}:{userId}:day` ZSET | 의제 D 일별 sliding | strategy 10 × 사용자 N × ~5KB peak = <500KB |
| `daily_pnl:{userId}:{date}` | 일일 P&L cache (TTL 자정까지) | <100B × 사용자 N = <1KB |
| `balance:user:{userId}` | 잔고 cache (ADR 0042 정합) | ~200B × 사용자 N = <2KB |

**합** ≈ <650KB. ADR 0048 41 한도 무관(별도 namespace).

### 9-5. KIS API 영향

본 ADR은 **KIS REST/WS 직접 호출 0건** — Kafka listen + DB + Redis만. Rate Limit(ADR 0037) 영향 0. (Position 자체는 ADR 0042 Bootstrap이 KIS 잔고 fetch — 본 ADR이 그 결과를 활용)

### 9-6. risk-eval-pool 부담

**별도 pool 불필요** — Kafka listener container thread 단일 처리. ADR 0047 strategy-eval-pool과 달리 deterministic 룰만(<20ms 평가). 사용자 dogfooding 누적 시 평균 wall-clock > 50ms 도달 시 별도 pool 후속 검토.

---

## 10. Rollback 시나리오

### 10-1. 본 ADR Supersede 또는 Revoke case

1. **false positive BLOCKED 누적** — risk_config default가 너무 보수적 시 정상 시그널 다수 BLOCKED → 사용자 부담 → default 완화 후속 ADR (예: 분당 3 → 5)
2. **auto-PAUSE over-reaction** — autoPauseThreshold=10 너무 낮아 정상 strategy 자동 PAUSED 누적 → 사용자 설정 override 가이드 + threshold 상향 검토
3. **end-to-end latency 초과** — signal → risk → order intent > 200ms 도달 시 RiskContext 빌드 최적화 의무 (Redis cache TTL 연장 + parallel fetch)
4. **위반 사례 0 (over-conservative)** — 1주일 사용자 dogfooding 시 BLOCKED 0건 → default 룰 무력화 검증 후 사용자 input 의제 재검토

### 10-2. Rollback 절차

1. 본 ADR 상태 `Superseded by ADR XXXX, YYYY-MM-DD` 헤더 갱신
2. `SignalGeneratedKafkaListener` `@KafkaListener` 비활성 (또는 `@ConditionalOnProperty` 게이트)
3. `signal.risk_status` 컬럼은 보존 (audit) + 신규 verdict 0 — 모든 신규 signal `risk_status='PENDING'` 유지
4. `risk_event` 테이블 row 보존 (audit) + 신규 INSERT 0
5. `position` 테이블 — ADR 0042 Bootstrap 자산이므로 본 ADR rollback과 무관
6. T-O 직접 진입 path 재가동 (signal → order 직접, risk gateway 우회) — `feature-flag.risk-gateway.enabled=false`
7. STOMP `/topic/risk.user.{userId}` fanout 자연 휘발
8. ADR 0024·0042·0043·0044·0066 baseline 정합 본문 LOCK 표기 (변경 0건)
9. CHANGELOG row 추가 + master §10 ADR 인덱스 갱신

---

## 11. 채택 시 후속 작업 (별도 PR — 본 ADR `Accepted` 갱신 후 sub-agent 디스패치)

본 plan PR은 **architect 단독 — ADR + spec/plan 문서만**. 실무 코드 변경은 별도 sub-agent PR 매트릭스.

### 11-1. sub-agent 디스패치 매트릭스

| Phase | sub-agent | 영역 | 산출물 | 의존성 |
|---|---|---|---|---|
| **Phase 1** | **strategy-engine** | (1) `domain/.../risk/RiskConfig.kt` · `RiskEvent.kt` · `RiskRule` enum · `RiskVerdict` enum · `RiskGatewayResult` sealed · `RiskContext.kt` · port 6건(RiskConfigRepository · RiskEventRepository · RiskGateway · RiskNotifier · SignalRiskStatusUpdater + ADR 0066 SignalRepository 정합 확장). (2) `domain/.../position/Position.kt` · `TradingMode` enum · `PositionRepository.kt` port. (3) `consumer/.../risk/RiskGatewayEvaluator.kt` — 6 룰 순차 short-circuit 평가 본체. (4) `consumer/.../risk/TradeFrequencyRedisGuard.kt` — Redis sliding window ZSET. (5) `consumer/.../risk/DailyPnlCalculator.kt` + cache. (6) `consumer/.../risk/CapitalSnapshotProvider.kt` — ADR 0042 cache 활용. (7) `consumer/.../risk/AutoPauseGuard.kt` — 연속 BLOCKED 검사 + strategy.status=PAUSED 전환 + ADR 0066 StrategyCache evict. (8) `consumer/.../risk/kafka/SignalGeneratedKafkaListener.kt` — `signal.generated.v1` listen → RiskGatewayEvaluator 위임. (9) `consumer/.../risk/kafka/RiskViolationKafkaProducer.kt` + `OrderIntentKafkaProducer.kt`. (10) 단위 테스트 — RiskGatewayEvaluator 6 룰 each (POSITION_LIMIT BLOCKED · DAILY_LOSS_LIMIT BLOCKED · MAX_CONCURRENT BLOCKED · TRADE_FREQ_MINUTE BLOCKED · TRADE_FREQ_DAY BLOCKED · KILL_SWITCH BLOCKED · ALLOWED happy path) + AutoPauseGuard 임계 도달 시 PAUSED 검증 | 본 ADR §5·§7 1:1 매핑 + AI 호출 0 grep 검증 inline 주석 의무 |
| **Phase 2** | **backend-core** | (1) `api/.../persistence/risk/RiskConfigJpaEntity.kt`·`RiskConfigJpaRepository.kt`·`RiskConfigJpaAdapter.kt` (ADR 0051 default fun 함정 회피 의무). (2) `api/.../persistence/risk/RiskEventJpaEntity.kt`·`RiskEventJpaRepository.kt`·`RiskEventJpaAdapter.kt`. (3) `api/.../persistence/position/PositionJpaEntity.kt`·`PositionJpaRepository.kt`·`PositionJpaAdapter.kt`. (4) `api/.../persistence/signal/SignalJpaEntity.kt` 확장 (risk_status 컬럼) + `SignalRiskStatusJpaAdapter.kt` 신규. (5) `api/.../controller/RiskConfigController.kt` 3 endpoint + `RiskEventController.kt` 2 endpoint + `PositionController.kt` 2 endpoint (Swagger 4단 어노테이션 + 한국어 설명). (6) `api/.../application/risk/RiskConfigService.kt` — getOrDefault · save · reset. (7) `api/.../application/risk/RiskEventQueryService.kt` · `api/.../application/position/PositionQueryService.kt`. (8) `api/.../stomp/StompRiskNotifier.kt` — `/topic/risk.user.{userId}` STOMP fanout (BLOCKED · AUTO_PAUSED 2 event type). (9) Flyway V16(risk_config) · V17(risk_event) · V18(position) · V19(signal.risk_status 컬럼 + index). (10) 통합 테스트 — REST 7 endpoint + RiskConfig upsert + RiskEvent 페이징 + Position current/history + signal.risk_status 갱신 검증 | 본 ADR §4·§5·§6·§7 1:1 매핑 + AI 호출 0 inline 주석 의무 |
| **Phase 3** | **frontend** | (1) `frontend/src/pages/risk/RiskConfigPage.tsx` — `/risk-config` 페이지 (8 입력 필드 + safe default + 변경 사항 미리보기). (2) `frontend/src/pages/risk/RiskEventListPage.tsx` — `/risk-events` (시계열 테이블 + rule 필터 + verdict 필터 + 페이징). (3) `frontend/src/pages/position/PositionPage.tsx` — `/positions` (현재 보유 + 청산 이력 탭). (4) `frontend/src/components/risk/RiskConfigForm.tsx` (의제 A·B 택1 UI + slider C·D). (5) `frontend/src/components/risk/RiskEventBadge.tsx` (BLOCKED 색상 + rule 아이콘). (6) `frontend/src/components/position/PositionCard.tsx` + `PnlBadge.tsx` (수익 녹색 / 손실 빨강). (7) `frontend/src/hooks/useStompRisk.ts` — STOMP `/topic/risk.user.{userId}` subscribe + 토스트 (BLOCKED type + AUTO_PAUSED type 분기). (8) `frontend/src/hooks/useRiskConfig.ts` · `useRiskEvents.ts` · `usePositions.ts` — REST hook (TanStack Query). (9) routing 확장 (`/risk-config` · `/risk-events` · `/positions`) + AppHeader 메뉴 3건 추가. (10) frontend test 7건 (RiskConfig PUT · 택1 검증 · RiskEvent 페이징 · Position 현재 · STOMP BLOCKED 토스트 · AUTO_PAUSED 토스트 · default seed 첫 진입) | 본 ADR §4 REST 7 endpoint + F-1 STOMP 2 event type 1:1 매핑 |
| (선택) **qa** | sample 위반 시나리오 dogfooding 검증 — 6 룰 each BLOCKED evidence(DB SELECT + Kafka log inspect + STOMP log) + auto-PAUSE 임계 도달 evidence + ALLOWED happy path order.intent.v1 publish evidence | spec test 또는 dogfooding evidence 보고 |

### 11-2. 디스패치 모드

**직렬 권장 (Phase 1 → 2 → 3)**:
- Phase 1 머지 후 Phase 2 진입 — domain port + RiskGatewayEvaluator 본체 의존
- Phase 2 머지 후 Phase 3 진입 — REST API + STOMP fanout 의존

**병렬 가능 case** (architect 충돌 행렬 점검 후 결정):
- Phase 1·Phase 2가 add-only 충돌 0 명확 시 (domain 신규 파일 + api 신규 파일 + Flyway 신규 파일).
- 단 `SignalJpaEntity` 컬럼 추가(V19)는 Phase 2가 단독 (Phase 1은 ADR 0066 entity 건드리지 않음) — 영역 분리 가능

### 11-3. CHANGELOG 슬롯 사전 발행 (헌법 §4)

본 plan PR diff에 sub-agent별 CHANGELOG 슬롯 row + anchor 사전 prepend 의무:
- `<!-- pr-N-slot-strategy-engine -->` row
- `<!-- pr-N-slot-backend-core -->` row
- `<!-- pr-N-slot-frontend -->` row
- `<!-- pr-N-slot-end -->`

면제 조건 점검 (사용자 메모리 `feedback_spec_plan_scope.md`):
- hotfix? **아님** (T-RISK W3 신규 트랙)
- 결함 정정? **아님** (신규 안전망 기능)
- 작은 묶음? **아님** (신규 트랙 + 다중 ADR 합류 + 4 테이블)

→ **슬롯 사전 발행 의무 (헌법 §4 정합)**.

### 11-4. 영향 ADR/문서 매트릭스 (정합 갱신 지시)

| 문서 | 갱신 내용 | 갱신 시점 |
|---|---|---|
| **ADR 0024 (Risk Guard chain 6단)** | 본 ADR이 W3 구현 권위로 인용 — §2 골격 본문에 본 ADR 권위 참조 + verdict path 본 ADR §7-2 1:1 매핑 정합 | 본 ADR `Accepted` 갱신 시 동시 (architect 단독) |
| **ADR 0042 (Position Bootstrap)** | §결정 본문에 본 ADR Position 도메인 합류 매트릭스 인용 (시계열 영속 + closedAt 정합) | 본 ADR `Accepted` 갱신 시 동시 |
| **ADR 0043 (KillSwitch 행동 정의)** | §결정 본문에 본 ADR `RiskRule.KILL_SWITCH_ON` 룰 진입 정합 (KillSwitch ON 시 risk verdict 첫 룰로 BLOCKED) | 본 ADR `Accepted` 갱신 시 동시 |
| **ADR 0044 (Notification System)** | §결정 본문 `risk.violation.v1` payload 매트릭스에 본 ADR rule 7건 cover row 추가 | 본 ADR `Accepted` 갱신 시 동시 |
| **ADR 0066 (T-SIG Signal Engine)** | §11-4 영향 ADR 본 ADR row 추가 + §6-2 signal 테이블 row의 `risk_status` 컬럼 인용 (V19) | 본 ADR `Accepted` 갱신 시 동시 |
| **ADR 0030 (master-architecture SoT) + master §10** | ADR 0067 인덱스 row 추가 + §C.13 Risk·Position 본문 본 ADR 권위 인용 | 본 plan PR 동시 |
| `_workspace/arch/adr/README.md` | ADR 0067 index row 추가 (§6 전략·신호·리스크 7→8건) + §11 후속 0068+ 시프트 + §12 후속 예정 row | 본 plan PR 동시 |
| `_workspace/backlog.md` | §41 신규 등록 (T-RISK R1·R2·R3 트랙) | 본 plan PR 동시 |
| `docs/architecture/tracks-roadmap.md` | T-RISK row 본 ADR 권위 인용 + 진입 표기 + §5 W3 진행 순서에 T-RISK row 본 ADR 합류 + §8 변경 이력 row | 본 plan PR 동시 |
| `docs/architecture/development-rules.md` | 룰 5 영역 본 ADR scope 1 PR 한계 검증 (Phase 1·2·3 분리 권장) | 본 plan PR 동시 |
| `CHANGELOG.md` | spec/plan PR row 추가 + sub-agent 슬롯 3건 + anchor | 본 plan PR 동시 |

### 11-5. external-api-policy 영향

본 ADR 채택 시 외부 API 정책 영향 0 — KIS API 추가 호출 0건 + Kafka 내부 토픽만 활용.

---

## 12. 결과 (채택 시)

- **자동매매 본체 자본 보호 안전망 가동** — 6 룰 dual cover (포지션·일일 손실·동시 종목·분당·일별·KillSwitch) — 사용자 자본 손실 risk 최소화
- **ADR 0024 권위 W3 구현** — Risk Guard chain deterministic 룰만 · AI 호출 0 검증 의무
- **ADR 0042 Position Bootstrap 합류** — 시계열 영속 보강 + closedAt NULL=보유중 정합
- **ADR 0066 Signal Engine 연결** — `signal.generated.v1` listen + signal.risk_status 컬럼 verdict 갱신
- **ADR 0044 Notification 진입** — `risk.violation.v1` publish + Slack 통지 정합
- **T-O 후행 의존 자산 LOCK** — `order.intent.v1` Kafka topic이 T-O 트랙 진입점
- **사용자 UX** — `/risk-config` 페이지 + `/risk-events` 시계열 + `/positions` 현재 보유 + STOMP BLOCKED 토스트
- **strategy 폭주 자동 격리** — 연속 BLOCKED 10회 시 strategy auto-PAUSED + 사용자 알림
- **MCP 외부 client 정합 후속** — risk_event 시계열 + position endpoint를 후행 ADR 0027(MCP)에서 노출 가능

---

## 13. 참고

- ADR 0001 — 시스템 비전 (룰 본체 + AI 옵션)
- ADR 0007 — Kafka 이벤트 백본 (`signal.generated.v1` listen + `risk.violation.v1` publish + `order.intent.v1` publish)
- ADR 0010 — Multi-tenant (userId 격리)
- ADR 0011 — 주문 실행기 3-mode + 안전 게이트 (TradingMode enum 정합)
- ADR 0023 — Signal Engine 룰 매칭 only (AI 호출 0 — 본 ADR 동일 정책)
- ADR 0024 — Risk Guard chain 6단 (본 ADR W3 구현 권위)
- ADR 0027 — MCP 서버 미래 노출 (후속 risk_event·position query API)
- ADR 0030 — master-architecture SoT
- ADR 0037 — KIS Rate Limit (영향 0)
- ADR 0042 — Position Bootstrap (Position 도메인 합류)
- ADR 0043 — KillSwitch 행동 정의 (RISK_RULE.KILL_SWITCH_ON 정합)
- ADR 0044 — Notification System (`risk.violation.v1` payload 확장)
- ADR 0045 — Graceful Shutdown (Kafka listener container)
- ADR 0046 — Onboarding & Credential (multi-tenant 정합)
- ADR 0051 — Spring Data Repository default fun 함정 (회피 패턴 의무)
- ADR 0057 — KIS-only (KIS REST/WS 호출 0건)
- ADR 0061 — Backtest 결과 영속 (3 테이블 + 5 endpoint 패턴 baseline)
- ADR 0066 — T-SIG 실시간 시그널 엔진 (`signal.generated.v1` 진입점)
- `docs/architecture/master-architecture.md` §C.13 (Risk·Position)
- `docs/architecture/tracks-roadmap.md` §2 T-RISK row
- `_workspace/backlog.md` §41 (본 ADR plan 발행 신규 등록)
- 사용자 메모리 `feedback_v3_skepticism_no_regression.md` (fitness gap lens) · `feedback_kis_first_policy.md` (KIS-only) · `feedback_3tier_hierarchy.md` (architect plan → sub-agent 병렬) · `feedback_spec_plan_scope.md` (작은 묶음 제외 정합) · `project_system_vision.md` (룰 기반 quant trading + 사용자 자본 보호)
