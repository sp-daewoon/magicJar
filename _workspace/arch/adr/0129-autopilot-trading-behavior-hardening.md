# ADR 0129 — autopilot 매매행위 하드닝 (첫 손실일 복기 기반 5 레버)

- **상태**: Accepted (2026-06-23 — 최고 관리자 결재 + orchestrator 위임)
- **작성**: architect (opus 4.8 1M)
- **입력 evidence**: 2026-06-23 라이브 복기 (orchestrator 확정, KIS 진실원 대조 `matched=true`) — autopilot REAL 첫 손실일 실현손익 **−29,382원 (전 종목 손실)**
- **spec SoT**: [`_workspace/specs/2026-06-23-autopilot-trading-hardening.md`](../../specs/2026-06-23-autopilot-trading-hardening.md)
- **관련 ADR**: 0067(AutoPauseGuard·RiskGateway) · 0114(주문 집행·청산 보장) · 0115(포지션 단일 권위) · 0128(분할매수 자본 재설계) · charter §12(주문 승인) · §13(롱온리) · `project_autopilot_initiative.md`(자율 증대 루프)
- **자본 결정권**: 본 문서는 설계만(코드 0). 자본경로 최고위험 — 보수적 default·owner 격리·롤백 명시.

---

## 1. 배경 — 2026-06-23 첫 손실일 라이브 복기

magicjar-autopilot(Claude 직접 운영 완전자율 REAL 자기증대 루프 — `project_autopilot_initiative.md`)이 2026-06-23 처음으로 **전 종목 손실 −29,382원**을 기록했다. orchestrator가 KIS(브로커=진실원, 사용자 메모리 `feedback_kis_ground_truth_crosscheck.md`)와 대조해 `matched=true`로 확정한 복기 결과, 근본 원인은 **시스템 버그가 아니라 전략 행위(behavior) 결함** 3종 + 증폭요인 1종이다.

### 1-1. 손실 분해 (evidence)

| 손실원 | 종목 | 손실 | 비중 | 행위 결함 |
|--------|------|------|------|-----------|
| ③ 오버나잇 갭다운 | 080220 | −16,137 | 55% | 어제 15:15 늦게 진입(@134,600) → TP 못 함 → 보유 → 오늘 갭다운 |
| ③ 오버나잇 갭다운 | 035720 | −1,072 | 4% | 동일 (오버나잇 2종목 합 59%) |
| ①② 추격+휩쏘 | 006340·067290·091590 외 | 나머지 | ~41% | 09:00~09:33 개장 변동성 집중 |

### 1-2. 행위 결함 3종 + 증폭요인

- **① 가격무관 추격매수(chasing)**: 006340을 11,070(09:00) → TP → **12,340(09:20·+11%) → 12,210(09:21)** 세 전략(100·102·104)이 *더 비싸게* 재추격. 067290·091590도 동일. 기존 15분 재진입 쿨다운(follow-up I, #1109)이 풀리면 **가격 인식 없이** 같은 이름을 재추격한다. dedup(개선 C, #1100)은 *같은 cycle* 동시 중복만 차단 → 시간차 추격(가격 상승분)은 직교 갭으로 남는다.
- **② 모멘텀 진입 ↔ 평균회귀 청산 구조적 휩쏘**: 진입=COMPOSITE(개장 모멘텀), 청산=THRESHOLD(close vs vwap/ma20). 개장 변동성에서 vwap 위아래 진동 → 사자마자 청산 → 작은 손실 반복(09:00~09:33 집중). 진입 논리와 청산 논리의 시간축 전제가 어긋난다(모멘텀=추세 지속 기대 / 평균회귀=즉시 되돌림 기대).
- **③ 오버나잇 갭 = 최대 단일 손실**: 080220 한 종목이 전체 손실의 55%. 늦은 오후 진입분이 당일 청산되지 못하고 보유로 넘어가 갭다운 리스크에 노출.
- **증폭요인**: 6전략(100~105)이 같은 16종목 universe를 공유 → 같은 이름에 몰려 추격. 추가로, 080220 좀비 포지션(#507, 이미 #1113으로 fix)이 풀을 잠그자 POSITION_LIMIT 20건이 12분 만에 5/6 전략을 auto BUY_BLOCKED로 죽였다(자기억제 가드의 과민 회귀).

---

## 2. 결정 — 5 레버 (전부 보수적 default + owner=AUTOPILOT 격리 + 롤백 명시)

본 ADR은 행위 결함을 닫는 5개 독립 레버를 결정한다. **레버 1~4는 strategy-engine 영역**, **레버 5는 backend-core 영역**(이미 결정된 B+C 통합 기록). 행위 결함 A(좀비 포지션)는 이미 #1113으로 해소되어 본 ADR 대상이 아니다.

### 레버 1 — 오버나잇 홀드 = 전략별 설정 (★ 사용자 결재: 전면 금지 아님)

> 결함 ③ — 오버나잇 갭이 손실 59%. **그러나 사용자(최고 관리자)는 전면 금지가 아니라 전략별 토글을 명시 결재.**

- **결정**: `strategy`에 **per-strategy 플래그 `allow_overnight` (boolean)** 추가. EOD 청산 워커가 `allow_overnight=false` AND `owner=AUTOPILOT` 인 전략의 보유 포지션을 장마감 전 **`magicjar.autotrading.eod-liquidation.cutoff`(default 15:15 KST)** 에 **시장가 SELL 청산**한다. 같은 토글이 **14:30 이후 신규 BUY 진입 금지**(파라미터 `late-entry-cutoff`, default 14:30 KST)도 게이트한다.
- **default 정책 (보수적 + zero-regression 양립)**:
  - `allow_overnight` 컬럼 **DDL default = `true`** (NOT NULL). → 기존 USER/SYSTEM 전략 + 이미 존재하는 AUTOPILOT 전략은 **현행 오버나잇 거동 보존**(byte-동일·zero-regression).
  - **단, 신규 AUTOPILOT 전략 시드(`V89` 류 템플릿·자율 생성 루프)는 `allow_overnight=false`로 명시 생성** — autopilot은 본질이 단타(intraday)이므로 갭 리스크를 default로 회피. 기존 AUTOPILOT 전략은 사용자/orchestrator가 의도적으로 false 전환할 때까지 true 유지(소급 변경 0).
  - **EOD 청산·late-entry 게이트 발동은 owner=AUTOPILOT 한정** (전체 적용 아님). USER 전략은 `allow_overnight` 값과 무관하게 EOD 청산 워커·late-entry 게이트를 절대 타지 않는다(owner 격리 LOCK — 사용자 스윙 전략 보호).
- **기존 자원 재사용**: 청산 메커니즘은 **신규 0** — 기존 `AutopilotLiquidationService.liquidateAll()` + `AutopilotLiquidator` 포트 + `OrderIntentKafkaProducer.publishExitIntent` + `AutoOrderIdempotencyKeys` + `StrategyPositionJpaRepository.findAutopilotHeldPositions`를 그대로 재사용한다. 신규는 ① `allow_overnight=false` 필터를 추가한 repository 쿼리 변형(`findAutopilotHeldPositionsNoOvernight`) + ② @Scheduled EOD 워커(`EntryTimeoutCancelWorker` cron 패턴 선례) 둘뿐.

### 레버 2 — 개장 직후 진입 금지 윈도우

> 결함 ② — 개장 변동성에서 모멘텀 진입이 vwap 진동에 사자마자 청산.

- **결정**: **`magicjar.autotrading.open-blackout` 윈도우(default 09:00~09:15 KST, 파라미터화)** 동안 owner=AUTOPILOT 신규 BUY 억제. **청산(SELL)·손절·익절은 절대 차단하지 않는다** — 보유분 보호는 항상 보장(롱온리 charter §13). 위치는 `applyAutopilotBuyGuard` 가드 체인(BUY 전용 도달 지점)에 삽입.
- **owner 한정 vs 전체**: **owner=AUTOPILOT 한정**(전체 적용 아님). USER/SYSTEM 전략은 가드 체인의 비-AUTOPILOT early-return에서 빠지므로 구조적으로 본 윈도우를 타지 않는다(zero-regression).
- **default 게이트**: `magicjar.autotrading.open-blackout.enabled` (default **true** — autopilot 한정이므로 enabled=false인 autopilot 미가동 상태와 동일). 윈도우 = `open-blackout.start`(09:00) ~ `open-blackout.end`(09:15). 시각 비교는 KST(Asia/Seoul) `LocalTime`.

### 레버 3 — 추격 방지 재진입 가드 (가격 기반 축)

> 결함 ① — 시간 쿨다운(#1109)이 풀린 뒤 *더 비싼 가격*에 같은 종목 재추격.

- **결정**: 기존 `AutopilotReentryCooldownGuard`(시간 기반)에 **가격 기반 축**을 추가한다. owner=AUTOPILOT 가 세션 내 같은 code를 **직전 청산가(또는 직전 진입가) 대비 `magicjar.autopilot.reentry-price-guard-pct`(default 1.0%) 이상 높은 가격**에 재매수하려 하면 suppress.
- **메커니즘 (기존 자원 확장)**: 현 `recordLiquidation`이 Redis 쿨다운 키에 청산 *시각*(epoch-millis)만 기록한다. 이를 **청산 시각 + 청산가(또는 직전 진입 평단)** 동반 기록으로 확장(JSON 또는 `ts:price` 포맷). `evaluate`에 현재 BUY 가격(`signal.priceAtSignal`) 인자를 추가하고, 쿨다운 만료 여부와 **무관하게**(가격 축은 시간 축과 직교) `currentPrice > recordedExitPrice × (1 + pct/100)`이면 Suppress.
  - **시간 축(기존)**: 청산 직후 윈도우 내 재진입 = churn 억제 (현행 유지).
  - **가격 축(신규)**: 윈도우 경과 후라도 직전 청산가보다 X% 비싸면 추격 차단. → "더 싸게 또는 같은 가격에만 재진입 허용"이 추세추종-비용 균형의 보수적 디폴트.
- **fail-open**: Redis 결함·기록 부재 시 가격 가드 = Proceed(시간 가드와 동일 fail-open 정책 — 분산·비용 최적화이지 자본 안전 게이트가 아니다). 자본 안전은 `AutopilotBuyGuard`(풀/캡) + dedup이 별도 보장.
- **owner 격리**: 현 `evaluate`/`recordLiquidation`이 이미 owner=AUTOPILOT 확정 후에만 호출됨(가드 내부 owner 판정 LOCK). 가격 축도 동일 격리 상속.

### 레버 4 — 모멘텀 ↔ 회귀 청산 정합

> 결함 ② — 진입·청산 논리의 시간축 전제 불일치로 즉시 청산 휩쏘.

- **결정**: **기존 자원 `min_hold_bars`(strategy 컬럼) + `MinHoldGuard`(strategy-engine, 기구현)를 autopilot 시드 전략에 적용**. 신규 코드 0 — autopilot 템플릿 전략의 `min_hold_bars`를 **default `≥3봉`(1분봉 기준 3분)** 으로 시드해, DSL THRESHOLD vwap-cross 즉시 청산을 N봉 동안 무시한다. **손절 라인(tick watcher·ScaleInExit stop)·익절 라인(TP 선주문)은 절대 차단하지 않는다** — `MinHoldGuard.shouldSkipDslSell`이 이미 DSL SELL에만 적용하고 STOP_LOSS·TAKE_PROFIT 경로는 우회하므로 안전망 우선 의미론 보존(기구현 LOCK).
- **대안 검토 (ATR 기반 손절)**: vwap-cross 즉시청산을 ATR 변동성 손절로 대체하는 안은 **신규 지표 계산·DSL 확장 필요 → post-MVP로 밀어냄**. min_hold_bars는 기존 자원만으로 즉시 효과를 내므로 1차 레버로 채택. ATR 손절은 후속 트랙(backlog) 후보.
- **owner 격리**: min_hold_bars는 전략 단위 설정값이라 owner 무관 안전(USER 전략이 자체 설정 시에만 적용 — 기존 거동). autopilot 시드에만 default 주입하므로 USER 전략 zero-regression.

### 레버 5 — auto-pause 거버넌스 (autopilot 공유풀) — B+C 통합 기록

> 증폭요인 — 좀비 포지션(080220, #507)이 풀 잠금 → POSITION_LIMIT 20건 + 자기억제 룰 → 개장 12분 만에 5/6 전략 auto BUY_BLOCKED. orchestrator가 별도 결정한 B+C를 본 ADR에 통합 기록.

- **B) 자기억제 가드를 auto-pause 임계에서 제외**: `AUTOPILOT_REENTRY_COOLDOWN`·`AUTOPILOT_CODE_DEDUP`를 `AutoPauseGuard.BENIGN_AUTO_PAUSE_EXCLUDED_RULES` set에 추가. **사유**: 이 두 룰은 autopilot이 *스스로 churn·중복을 억제*하는 정상 거동이다. 그런데 BLOCKED 카운트에 기여하면 "자기억제 가드가 임계를 채워 전략을 죽이는" 회귀가 발생한다(F9와 동형 — 정상 가드가 자동정지를 유발). benign set 제외로 임계 산식은 무손상(threshold·전환 메커니즘 변경 0), SQL 카운트 단계에서만 제외.
- **C) owner=AUTOPILOT은 `POSITION_LIMIT`·`MAX_CONCURRENT_POSITIONS`를 auto-pause 임계에서 제외**: 공유풀 N전략 구조에서는 **"풀이 참(full)"이 정상 상태**다. 6전략이 공유풀 한도에 닿아 POSITION_LIMIT BLOCKED를 내는 것은 위험 신호가 아니라 설계대로의 거동이며, 그것 때문에 하루 전체 전략을 비활성화하는 것은 과민하다.
  - **구현 방향**: benign set은 **owner 무관 전역**이라 C를 단순히 set에 추가하면 USER 전략도 영향(POSITION_LIMIT은 USER에선 실제 위험 신호 — 격리 위반). 따라서 C는 **owner=AUTOPILOT 한정 제외**여야 한다. `AutoPauseGuard.checkAndPauseIfNeeded`가 owner를 인지하도록 하거나, autopilot 경로 전용 excluded set(`AUTOPILOT_AUTO_PAUSE_EXCLUDED_RULES = BENIGN ∪ {POSITION_LIMIT, MAX_CONCURRENT_POSITIONS}`)을 owner=AUTOPILOT일 때만 적용. **USER owner는 현행 BENIGN set 그대로**(owner 격리 LOCK).
- **threshold 재평가**: 현 `autoPauseThreshold` default가 공유풀에 과민한지(=5가 6전략 공유풀에 너무 낮은지) 평가. **결정**: threshold 값 자체는 유지하되 B+C의 카운트 제외로 과민 회귀를 닫는다(threshold 변경은 USER 전략에도 영향 → 보수적으로 카운트 모수만 정정). owner=AUTOPILOT 전용 threshold 분리는 후속 평가 backlog.

---

## 3. 대안 · 트레이드오프

| 레버 | 채택안 | 기각 대안 | 기각 사유 |
|------|--------|-----------|-----------|
| 1 | per-strategy `allow_overnight` 토글 + EOD 워커 | 오버나잇 전면 금지 | 사용자 명시 — USER 스윙 전략 zero-regression 필요 |
| 1 | 신규 컬럼 default=true + autopilot 시드만 false | 컬럼 default=false | 기존 전략 소급 거동 변경 = 회귀 risk |
| 2 | open-blackout 윈도우(BUY만) | 변동성 필터(ATR/표준편차 게이트) | 신규 지표 계산 필요 → post-MVP. 시각 윈도우가 즉시·결정론 |
| 3 | 시간 축 + 가격 축 직교 결합 | 시간 쿨다운만 연장(예: 60분) | 정당 추세 재진입까지 막음(메모리 follow-up I 근거). 가격 축이 추격만 정밀 차단 |
| 4 | min_hold_bars(기존 자원) | ATR 기반 손절 신규 | 신규 지표·DSL 확장 → post-MVP backlog. min_hold_bars 즉시 효과 |
| 5C | owner=AUTOPILOT 한정 제외 | benign set 전역 추가 | POSITION_LIMIT은 USER에선 실제 위험 신호 — 격리 위반 |
| 5 | 카운트 모수 제외 | threshold 상향 | threshold는 USER에도 영향 — 보수적으로 모수만 정정 |

---

## 4. owner 격리 매트릭스 (zero-regression 의무)

| 레버 | USER 전략 영향 | SYSTEM 전략 영향 | AUTOPILOT 전략 영향 |
|------|---------------|------------------|---------------------|
| 1 EOD 청산·late-entry | **0** (워커 owner=AUTOPILOT 필터·USER는 게이트 미통과) | **0** | `allow_overnight=false`만 청산. 기존 전략 true 유지·신규 시드 false |
| 1 `allow_overnight` 컬럼 | DDL default true = 현행 거동 보존 | 동일 | 신규 시드만 false |
| 2 open-blackout | **0** (가드 체인 비-AUTOPILOT early-return) | **0** | BUY만 09:00~09:15 억제·청산 무영향 |
| 3 가격 재진입 가드 | **0** (가드 owner=AUTOPILOT 확정 후만 호출) | **0** | 직전 청산가 +X% 추격만 차단 |
| 4 min_hold_bars | 자체 설정 시에만(기존 거동) | 동일 | 시드 default 주입 |
| 5B benign set | benign 2룰은 USER에 본래 미발생(autopilot 전용 룰) | 동일 | churn/dedup 자기억제 카운트 제외 |
| 5C POSITION_LIMIT 제외 | **0** (owner=AUTOPILOT 한정 — USER는 현행 BENIGN set) | **0** | 공유풀 full = 정상 → auto-pause 제외 |

---

## 5. 자원 audit (ADR 0102 5단계 · feedback_spec_audit 정합)

| 자원 | main 점유 현황 | 본 ADR 신규 |
|------|----------------|-------------|
| **Flyway** | 최신 `V93__autopilot_paper_shadow.sql` (형제 미점유) | **V94** — `strategy.allow_overnight BOOLEAN NOT NULL DEFAULT true` 추가 + autopilot 시드 전략 `allow_overnight=false` UPDATE(owner=AUTOPILOT) + (선택) autopilot 시드 `min_hold_bars` default 주입. **rollback**: `ALTER TABLE strategy DROP COLUMN allow_overnight` (컬럼 사용만 제거하면 거동 복원) |
| **ENV/yml** | `magicjar.autopilot.*`(properties) · `magicjar.autotrading.*`(take-profit 등) | `magicjar.autotrading.eod-liquidation.{enabled,cutoff,late-entry-cutoff}` · `magicjar.autotrading.open-blackout.{enabled,start,end}` · `magicjar.autopilot.reentry-price-guard-pct` — 전부 보수적 default + 게이트 |
| **Redis** | `kis:ws:*` · autopilot reentry cooldown 키 | 기존 reentry cooldown 키 **값 포맷 확장**(ts → ts:price). 신규 키 0. |
| **Kafka** | `kafka.shared.cmd.order.intent.v1` (publishExitIntent) | **0** — EOD 청산은 기존 intent 토픽 재사용 |
| **STOMP/REST** | — | **0** (UI 노출은 후속 — 본 사이클 백엔드 거동만) |
| **KIS** | 기존 자동청산 경로(OrderRouter→KisOrderHttpAdapter) | **0** — 신규 KIS endpoint 0 (EOD 청산은 기존 SELL 경로) |
| **AI** | — | **0** (룰 기반 — ADR 0023 §2-4 정합) |
| **mock 합성** | — | **0** (feedback_no_mock_data 정합) |

**기존 자원 재사용 우선 결과**: 레버 1 청산 메커니즘·레버 3 쿨다운 가드·레버 4 MinHoldGuard·레버 5 AutoPauseGuard benign set 전부 기존 자원 확장. 신규 코드는 **EOD @Scheduled 워커 1개 + repository 쿼리 변형 1개 + open-blackout 게이트 1개 + 가격 축 1개 + benign set 확장**으로 최소.

---

## 6. 운영 ADR 양식 3 슬롯 (charter §9-5)

### 6-1. SLO 영향
- §9-1 SLO 6건에 **저하 0**. 레버 1·2·3·5는 BUY 억제·청산 추가이므로 시그널→주문 지연·KIS WS·account API·Outbox에 무영향. EOD 청산은 15:15 1회 배치성(장중 SLO 무관). KIS rate limit은 BUY 억제로 오히려 호출 감소 방향.

### 6-2. rollback plan (자본경로 — 명시 의무)
- **롤백 앵커**: **v3.14.0** (`project_autopilot_initiative.md` 명시 앵커 — autopilot 도입 baseline). 본 ADR 5 레버 전체 되돌림 시 v3.14.0 거동으로 복원.
- **레버별 즉시 토글 롤백** (배포 revert 없이):
  - 레버 1: `magicjar.autotrading.eod-liquidation.enabled=false` → EOD 청산·late-entry 게이트 중단. 컬럼은 DDL default true라 잔존해도 거동 무해.
  - 레버 2: `magicjar.autotrading.open-blackout.enabled=false` → 개장 윈도우 해제.
  - 레버 3: `magicjar.autopilot.reentry-price-guard-pct=0` 또는 음수 → 가격 축 비활성(시간 축만 유지).
  - 레버 4: autopilot 전략 `min_hold_bars=0`/null UPDATE → DSL 즉시청산 복원.
  - 레버 5: benign set / owner 제외 코드 revert (add-only이라 set 원복).
- **DB down**: `V94` rollback = `ALTER TABLE strategy DROP COLUMN allow_overnight` (단방향 Flyway이므로 수동 down SQL을 spec에 명시).
- **자본 안전 LOCK**: 모든 레버는 **BUY 억제 + 보유분 청산 보장** 방향(롱온리 §13). 어떤 레버도 청산·손절·익절을 막지 않으므로 롤백 실패 시에도 자본 보호 방향(보수적 fail-safe).

### 6-3. 관측 매트릭스
- 레버 1: EOD 청산 워커 `log.warn("autopilot EOD 청산 ...")` + 청산 SELL intent 건수 metric. late-entry 차단 카운트.
- 레버 2: open-blackout suppress 카운트(`AUTOPILOT_OPEN_BLACKOUT` 사유).
- 레버 3: 가격 축 suppress 카운트(`AUTOPILOT_REENTRY_PRICE_GUARD` 사유 — dedup·시간 쿨다운과 구분).
- 레버 5: auto-pause 발동 빈도 변화(B+C 적용 후 개장 12분 5/6 죽음 → 0 기대). POSITION_LIMIT BLOCKED는 정상 카운트되되 auto-pause 미기여 검증.
- **Visual 회귀**: 본 사이클 frontend 노출 0 → qa Visual 시나리오 면제(백엔드 거동만). UI 노출(전략 빌더 `allow_overnight` 토글·EOD 청산 표시)은 후속 트랙에서 qa 동반.

---

## 7. 구현 plan 요약 (에이전트별 task)

세부는 spec §라운드 + plan 문서. 행위 결함 A(좀비)는 #1113으로 기해소.

| Task | 에이전트 | 레버 | 핵심 |
|------|----------|------|------|
| T1 | strategy-engine | 1·2·3·4 | `allow_overnight` 도메인 필드 + V94 + EOD 청산 워커 + open-blackout 게이트 + 가격 축 + autopilot 시드 min_hold_bars |
| T2 | backend-core | 5 (B+C) | AutoPauseGuard benign set 확장(B) + owner=AUTOPILOT 한정 POSITION_LIMIT·MAX_CONCURRENT 제외(C) |
| (완료) | — | A | 좀비 포지션 = #1113 기해소 |

- 라운드 경계: R1(도메인 필드 + V94 + 시그니처) → R2(어댑터·워커·가드 wiring) → R3(테스트 일괄). 레버 5는 add-only set 확장이라 단일 PR.
- 배포: **orchestrator가 장마감 후** (자본경로 critical — charter §12 + 사용자 결재). 본 PR은 ADR+spec+plan 발행까지(코드 0).

---

## 8. 변경 이력

| 날짜 | 변경 | 사유 |
|------|------|------|
| 2026-06-23 | 본 ADR 신설 — autopilot 매매행위 하드닝 5 레버 (Accepted) | 2026-06-23 첫 손실일 −29,382원 복기(KIS matched=true). 행위 결함 3종(추격·휩쏘·오버나잇갭) + 증폭요인(공유풀 과민 auto-pause). 사용자 결재: 오버나잇 전면금지 아닌 전략별 토글. orchestrator B+C 통합 기록. (architect) |
