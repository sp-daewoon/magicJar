# ADR 0043 — KillSwitch 행동 정의 (ON 시 신호·주문·통지·재시작 정책)

- 상태: **Amended** 2026-05-21 (§2-11 race-safe auto-ON policy A+B+C 추가 — false positive 차단)
- 작성일: 2026-05-07
- amend: 2026-05-21
- 결정자: architect (사용자 위임 + orchestrator dispatch · Q2=A Critical 7건 중 3) · **amend**: architect (사용자 dogfooding 결함 발견 직후 직접 정책 LOCK)
- 관계: ADR 0024 보강(Risk Guard chain — KillSwitchGuard) · ADR 0011 정합(주문 실행기) · ADR 0041 정합(drift auto-toggle) · ADR 0023 정합(Signal Engine) · **ADR 0070 §2-11 정합 (race-safe auto-ON LOCK 수신 — drift 검출 즉시 ON → A·B·C 가드 통과 시 ON으로 본 ADR이 권위)**

---

## 1. 컨텍스트 (왜 이 결정이 필요한가)

ADR 0024 §2 Guard chain의 `@Order(100) KillSwitchGuard`는 "사용자 KillSwitch DB row" 검사만 정의한다. 그러나 **KillSwitch ON 시 시스템이 어떻게 행동해야 하는가**는 모호:

- 진행 중인 주문은? Pending order cancel? 그대로 두기?
- 시그널 emit은 멈추는가? 룰 평가만 멈추는가?
- STOMP push는 멈추는가? 사용자에게 알림은 어떻게?
- 신규 주문 ingestion은 어디서 차단? Guard chain만? REST API 입구?
- OFF 토글은 사용자만? 자동 OFF 있는가?
- 어떤 trigger로 auto-ON?

이 모호함은 실제 사고 시 **KillSwitch ON 했지만 이미 발사된 주문이 체결되거나 / 다른 경로로 신규 주문이 새거나** 위험.

**필요한 결정**:
- ON 시 5가지 행동 명세 (시그널·pending·STOMP·ingestion·체결)
- OFF 토글 정책 (manual only · auto-trigger 종류)
- Redis 캐시·DB 정합

## 2. 결정

### 2-1. KillSwitch ON 시 5대 행동 명세

| # | 행동 | 책임 컨테이너 | 구현 |
|---|---|---|---|
| 1 | **신규 자동 주문 의도 즉시 정지** | `consumer` | RiskGateway가 user_id별 KillSwitch 상태를 read → ON이면 `KILL_SWITCH_ON`으로 BLOCKED. Kafka `order.intent.v1` publish 0건. T-SIG signal audit은 남을 수 있으나 주문 의도는 생성하지 않는다. |
| 2 | **Pending 주문 cancel** | `consumer` | DB `orders` 중 status=PENDING\|PARTIAL_FILLED 행을 KIS REST `order-rvsecncl` 호출로 일괄 취소. 취소 실패는 `order_reject_audit` row + 사용자 통지. |
| 3 | **STOMP push** | `api` | `risk.killswitch.v1` Kafka topic sub → `/topic/risk/{userId}` fanout. frontend Banner ON. |
| 4 | **신규 주문 ingestion 차단** | `api` | REST `POST /api/orders` controller 진입 직전에 user_id KillSwitch 상태 확인 → ON이면 422 Unprocessable Entity 반환 + 에러 메시지 "KillSwitch 활성화 — 사용자 명시 OFF 필요". |
| 5 | **체결통보는 정상 수신·기록** | `consumer` | KIS WS `H0STCNI0` 체결통보는 KillSwitch와 무관하게 정상 수신 + DB·Redis 갱신. (이미 발사된 주문의 체결까지 막을 수는 없음 — audit 의무) |

### 2-12. KillSwitch와 strategy `BUY_BLOCKED` 관계 (2026-06-02 amend)

| 항목 | KillSwitch | `BUY_BLOCKED` |
|---|---|---|
| 제어 레벨 | `user_id` 전체 하드 스톱 | 해당 `strategy_id` 신규 BUY 차단 |
| 적용 경로 | AUTO·MANUAL 주문 경로 전체 | 자동매매 전략 평가 경로 |
| BUY | 차단 | 차단 |
| SELL·청산 | 차단 | 유지 |
| strategy.status 변경 | 없음 | 해당 strategy만 `BUY_BLOCKED` |
| 해제 | 사용자 수동 OFF만 | 사용자 해당 전략 재개 |

정책 LOCK:
- KillSwitch가 ON이면 RiskGateway의 첫 룰 `KILL_SWITCH_ON`이 BUY·SELL 모두를 차단한다.
- OrderRouter는 RiskGateway 통과 후에도 KIS 전송 직전 KillSwitch를 재검증한다. RiskGateway 이후 ON 된 경우에도 KIS 주문은 나가지 않는다.
- KillSwitch ON·조회 실패·query port 미합류는 자본 경로에서 fail-closed ON으로 처리한다.
- 정상 등록 사용자는 `kill_switch_state(enabled=false)` row를 명시 보유한다. row 없음은 "OFF"가 아니라 상태 확인 불가로 본다.
- KillSwitch로 발생한 `KILL_SWITCH_ON` BLOCKED는 strategy AutoPause 누적에 포함하지 않는다. 사용자 전체 비상정지가 특정 strategy 상태를 바꾸면 제어 계층이 섞이기 때문이다.
- KillSwitch OFF는 `BUY_BLOCKED` 전략을 자동으로 ACTIVE로 돌리지 않는다. 상위 비상정지 해제와 전략별 신규 매수 재개는 별도 사용자 결정이다.

### 2-2. KIS endpoint (open-trading-api 정합) — Pending cancel 용

| 항목 | 값 | 출처 |
|---|---|---|
| URL | `/uapi/domestic-stock/v1/trading/order-rvsecncl` | `examples_user/domestic_stock/domestic_stock_examples.py` "주식주문(정정취소)[v1_국내주식-003]" |
| TR_ID (실전 매수정정/취소) | `TTTC0803U` | KIS 표준 |
| TR_ID (모의 매수정정/취소) | `VTTC0803U` | KIS 표준 |
| 주요 request | `CANO`, `ACNT_PRDT_CD`, `KRX_FWDG_ORD_ORGNO`(원주문 주문조직번호), `ORGN_ODNO`(원주문번호), `ORD_DVSN`, `RVSE_CNCL_DVSN_CD=02`(02=취소), `ORD_QTY`, `ORD_UNPR=0`(취소시 0), `QTY_ALL_ORD_YN=Y` | 동 |

`docs/operations/kis-api-reference.md` §2 후속 등재 필요 (`order-rvsecncl`).

**Pending 후보 조회**: ADR 0042 정합 — `inquire-psbl-rvsecncl`(`/uapi/domestic-stock/v1/trading/inquire-psbl-rvsecncl`, `TTTC0084R`)로 정정/취소가능 주문 list 가져온 뒤 일괄 cancel.

### 2-3. KillSwitch OFF 토글 정책

| 트리거 | 정책 |
|---|---|
| **Manual OFF** | 사용자 UI Banner의 OFF 버튼 → `POST /api/risk/killswitch/{userId}/off` → `kill_switch_state.enabled=false` + audit row |
| **Auto OFF 없음** | Critical 안전 — 자동 OFF 절대 없음. 사용자 명시 행동만. |
| **OFF 후 60초 cooldown** | OFF 직후에도 신규 시그널·주문은 60초 대기 (사용자 의도 확인용). frontend Banner "곧 활성화 60초"로 표시. |

### 2-4. KillSwitch Auto-ON 트리거 정책

| 트리거 | 출처 ADR | 임계 |
|---|---|---|
| **Manual ON** | 사용자 UI 버튼 | 즉시 |
| **Position drift detected** | ADR 0041 | 1주 이상 또는 종목 누락 |
| **Daily drawdown stop** | ADR 0024 DrawdownStopGuard | 일일 -3% (default) |
| **Daily loss cap (명목 KRW)** | ADR 0024 RiskBudget | 사용자 설정값 |
| **KIS API 인증 실패 누적** | ADR 0042 | 사용자별 5분에 OAuth 401 ≥ 3회 |
| **Reconcile failure 누적** | ADR 0041 | 사용자별 30분에 reconcile 실패 ≥ 5회 |
| **Signal engine error rate** | ADR 0023 | user별 1분에 룰 평가 exception ≥ 10회 |

각 trigger 발생 시 `risk_event` row + Kafka `risk.killswitch.v1` publish + STOMP fanout 의무.

### 2-5. Redis 캐시 정합

| key | TTL | 용도 |
|---|---|---|
| `killswitch:state:{userId}` | 24h | DB row의 hot read 캐시. SignalGenerator·OrderController가 매 평가마다 read. |
| `killswitch:cooldown:{userId}` | 60s | OFF 직후 cooldown — 존재 시 신규 주문 차단 유지 |
| `killswitch:trigger:{userId}:lastReason` | 7d | 마지막 ON 사유 (UI 표시 + 사용자가 사유 확인 후 OFF 결정) |

DB ↔ Redis 정합:
- DB write 시 동기 Redis write (write-through)
- consumer 부팅 시 모든 user의 DB row → Redis 워밍 (ADR 0042 bootstrap 정합)

### 2-6. 데이터 영향

| 테이블 | 변경 | Flyway |
|---|---|---|
| `kill_switch_state` (현행) | `last_triggered_reason VARCHAR(64)` 컬럼 추가 — auto-ON trigger 종류 기록 | V23 |
| `risk_event` | `type=KILLSWITCH_TOGGLED` 신설 ENUM | V18 보강(DDL 변경 없음) |

**Kafka topic 신규**:

| topic | producer | consumer | retention |
|---|---|---|---|
| `risk.killswitch.v1` | api·batch·consumer | api(STOMP fanout) | 30d |

ADR 0024 §2-4 `risk.event.v1`은 일반 risk 이벤트(reject·drawdown 등)용 유지. KillSwitch 토글은 별도 topic 분리 — 우선순위 push 보장.

### 2-11. 자동 ON race-safe policy (2026-05-21 amend — A+B+C LOCK)

#### 사용자 dogfooding evidence (결함 root cause)

**2026-05-21 09:43~11:05 시점 dogfooding** (사용자 직접 실거래 + 결함 발견):
- **09:43** — 사용자 본인 종목 매수 주문 발행 (REAL profile, MAGICJAR_MODE=REAL, MAGICJAR_ALLOW_REAL=true 게이트 통과)
- **09:43~09:44** — KIS 주문 접수 → `order.filled.v1` Kafka publish 시점까지 race window 발생
- **race window 본질**: 주문 발행 직후 KIS에는 보유 종목 갱신됐으나 내부 position 테이블에는 fill 이벤트 처리 직전 — `BalanceReconcileJob` 5분 cron이 이 race window 중간에 hit
- **11:05** — KillSwitch 자동 ON 발동 (ADR 0070 §2-11 step 5·6 "drift 검출 즉시 KillSwitch ON" 정합) — **false positive**

**결함의 본질**: 정상 매매 흐름의 race window를 drift로 오인. 사용자 자본 보호가 사용자 자본 운용을 차단하는 역설.

**사용자 결정**: A+B+C 3중 가드 채택 (2026-05-21 LOCK — 단독 결정 시점 명시).

#### A. Pending Order Exclusion (responsibility: reconcile worker)

**규칙**: `orders` 테이블 `status ∈ {PENDING, SUBMITTED, PARTIALLY_FILLED}` 인 종목은 reconcile drift 비교에서 **제외**.

**구현 위치**: `consumer/reconcile/BalanceReconcileJob` — `reconcile.drift.v1` Kafka publish **직전** 필터 단계 (drift detection 자체는 그대로 수행 → publish 단계에서 pending 종목 drop).

**왜 publish 단계?**: drift 검출 자체는 audit 가치 보존 (reconcile_run 행 그대로 INSERT — 통계 용도). KillSwitch 발동 의제만 차단 — drift 이벤트 소비자 중 KillSwitchAutoToggler에만 의미.

**대안 검토**:
- (i) drift detection 자체에서 제외: audit 손실
- (ii) publish 단계 필터 (채택): audit 보존 + 발동 차단
- (iii) KillSwitchAutoToggler 측에서 pending 조회: reconcile worker가 이미 orders 테이블 접근 가능 + listener 1·2·3에 중복 query risk → publish 단계 단일 책임이 정합

**구현 cardinality**: `SELECT symbol FROM orders WHERE user_id = ? AND status IN ('PENDING', 'SUBMITTED', 'PARTIALLY_FILLED')` — INDEX(user_id, status) 정합. 5분 cycle당 1회 호출 — 비용 영향 0.

#### B. Grace Window (responsibility: `KillSwitchAutoToggler`)

**규칙**: 마지막 `ORDER_FILLED` 이벤트 후 **5분간** drift 검출돼도 KillSwitch ON 보류.

**구현 위치**: `consumer/risk/KillSwitchAutoToggler.onDriftDetected` 진입 시 timestamp 체크.

**timestamp source**:
- 1차: `journal_event` 테이블 `WHERE user_id = ? AND type = 'ORDER_FILLED' ORDER BY occurred_at DESC LIMIT 1` (T-J 마감 시점 이후)
- 2차 (T-J 미정합 시): `orders` 테이블 `WHERE user_id = ? AND status = 'FILLED' ORDER BY filled_at DESC LIMIT 1`
- backend-core 자율 선택 — 어느 것을 1차로 쓸지 ADR 0068 (T-O 마감 SoT) 정합 확인 의무

**5분 결정 사유**: ADR 0070 §2-9 cron 시점이 5분 주기 — race window가 한 cycle 너비. 6분·10분 등은 KillSwitch 발동 지연 비용 ↑. 4분·3분은 race window 끝 직전 false positive risk.

**대안 검토**:
- (i) 1분: race window 못 cover (사용자 09:43 매수 → 09:44 까지 다 못 cover하는 경우)
- (ii) 3분: 5분 cron과 misalign — 한 cron 사이클 안에 grace 두 번 발동될 수 있음
- (iii) 5분 (채택): cron 한 cycle = race window 한 단위. 가장 명확한 LOCK
- (iv) 10분: 사용자 자본 보호 발동 지연 비용 과다

**race condition 추가 검증**: ORDER_FILLED 이벤트 자체가 지연 도착 가능성 (Kafka consumer lag) — 보수적으로 `lastFilledAt`은 **DB row의 `filled_at`** 사용 (Kafka publish 시점 아님). 실거래 보호 우선.

#### C. N-Strike (N=2, responsibility: `KillSwitchAutoToggler`)

**규칙**: 같은 `(userId, symbol)` 쌍에서 5분 sliding window 안에 drift **2회 이상** 검출 시만 KillSwitch ON. 1회만이면 카운터만 증가, KillSwitch 보류.

**구현 위치**: `consumer/risk/KillSwitchAutoToggler` per-`(userId, symbol)` strike 카운터.

**Phase 1 구현 (in-memory LOCK)**:
```kotlin
private val strikes = ConcurrentHashMap<Pair<Long, String>, MutableList<Long>>()
private val STRIKE_WINDOW_MS = 5 * 60 * 1000L
private const val STRIKE_THRESHOLD = 2

fun onDriftDetected(userId: Long, symbol: String): Boolean {
    val now = System.currentTimeMillis()
    val key = userId to symbol
    val list = strikes.computeIfAbsent(key) { mutableListOf() }
    synchronized(list) {
        list.removeAll { now - it > STRIKE_WINDOW_MS }  // time-based eviction
        list.add(now)
        return list.size >= STRIKE_THRESHOLD
    }
}
```

**가비지 컬렉션 LOCK**:
- size cap: `strikes.size > 10_000` 도달 시 oldest entries evict (LRU 패턴 — backend-core 자율 구현. Caffeine cache 사용 옵션 검토 가능)
- time-based eviction: 위 코드 `list.removeAll` 패턴 + 별도 `@Scheduled(fixedRate = 60_000)` cleanup task 권장

**Phase 1 in-memory LOCK 사유**: consumer 단일 인스턴스 가정 (현재 단일 JVM). multi-replica 진입 시 Phase 2에서 Redis ZSET 분산 카운터로 전환 — 본 ADR §4 후속 ADR 후보 등재.

**N=2 결정 사유**:
- N=1 (즉시): 본 false positive 패턴 그대로
- N=2 (채택): 한 cycle false positive 흡수 + 진짜 drift는 다음 cycle에서 반드시 재현되므로 ON
- N=3: 진짜 drift 발견 시점이 15분 후 — 자본 보호 지연 ↑
- N=2가 race window 1회 흡수 + 지속 drift 빠른 검출의 균형점

**가드 통과 순서 LOCK (3중 게이트)**:
```
[1] reconcile worker: pending order set 조회 → drift publish 직전 필터 (A)
  ↓ (drift 이벤트 publish — A 통과한 경우만)
[2] KillSwitchAutoToggler.onDriftDetected 진입
  ↓
[3] grace window 체크: now - lastFilledAt < 5min ? → 보류 + log INFO (B)
  ↓ (B 통과)
[4] N-strike 카운터 증가: list.size >= 2 ? → 보류 + log INFO + strike 카운터만 (C)
  ↓ (C 통과)
[5] KillSwitch ON 발동 (ADR 0043 §2-3 baseline 흐름) + journal_event row + Slack P0
```

**audit·관측성 LOCK**:
- 각 가드 보류 시 `log.info("KillSwitch auto-ON gate-{A,B,C} blocked: user=$userId symbol=$symbol reason=$reason")` 의무
- gate 별 보류 카운터 미터 (Micrometer `killswitch.auto_on.blocked` tag: `gate=A|B|C`) — 운영 모니터링용. 사용자별 차단 빈도가 통계로 누적되면 cron 주기·N·grace window 튜닝 evidence
- drift 이벤트는 그대로 publish — notification·alert·STOMP fanout 등 다른 listener는 영향 없음. **KillSwitch 발동만 gate**.

#### 트레이드오프 매트릭스 (4 옵션 비교 — A·B·C·D)

| 옵션 | 본질 | 효과 | 비용 | 채택 |
|---|---|---|---|---|
| **A. Pending Order Exclusion** | reconcile worker가 pending 종목 drift drop | 사용자 진행 중 주문 종목 false positive 완전 차단 | DB 1회 query (5분 1회 — 미미) | ✓ |
| **B. Grace Window (5분)** | 마지막 fill 후 N분 KillSwitch ON 보류 | race window N분 cover (lag 흡수) | DB 1회 query per drift 이벤트 | ✓ |
| **C. N-Strike (N=2)** | 5분 window 안 drift 2회 이상만 ON | 한 cycle false positive 흡수 + 지속 drift 검출 | in-memory state — 메모리 영향 미미 (10K 키 cap) | ✓ |
| **D. drift 임계치 raise (수량 ≥ 2주)** | 1주 diff 무시 | 부분체결·소수 단위 drift 흡수 | 진짜 1주 drift 사고 누락 risk ★ — 자본 보호 약화 | ✗ |

**A+B+C 3중 게이트 채택 사유**:
- 단독 A: pending 종목 외 race condition 못 cover (예: fill 이벤트 자체 처리 지연)
- 단독 B: pending 종목이지만 5분 초과한 long-pending 주문 false positive 못 cover
- 단독 C: 첫 false positive 시점에 즉시 ON 안되지만 두 번째 cycle (5분 후)에서 race window 풀린 후 drift 사라지면 가드 통과 — 다만 단일 false positive 1회만으로 종료되는 케이스가 일반적이므로 약함
- A+B+C 조합: 3 layer defense in depth — 사용자 자본 보호 강도 유지 + false positive 차단

**D 명시 폐기 사유**: 진짜 drift 1주(예: 외부 매도 누락 사고)도 무시되면 자본 보호 본질 훼손. ADR 0070 §2-10 임계치(수량 ≥ 1주) 그대로 LOCK.

#### 구현 위치 요약 (backend-core 참조)

| 가드 | 구현 위치 | 호출 시점 |
|---|---|---|
| A | `consumer/reconcile/BalanceReconcileJob` (또는 BalanceReconciler — backend-core 자율) | `reconcile.drift.v1` Kafka publish 직전 |
| B | `consumer/risk/KillSwitchAutoToggler.onDriftDetected` | drift listener 진입 직후 첫 번째 분기 |
| C | `consumer/risk/KillSwitchAutoToggler` strike 카운터 (in-memory ConcurrentHashMap) | grace window 통과 후 두 번째 분기 |

`KillSwitchAutoToggler`는 T-RECON Phase 1 baseline 컴포넌트 (ADR 0070 §2-1) — 본 amend는 해당 컴포넌트의 책임 확장 LOCK.

## 3. 결과·영향

### 3-1. 트랙 영향

- **T-RISK (W3)** — Guard chain의 KillSwitchGuard 외에 본 ADR 5대 행동 명세 의무 통합. T-RISK 결정 항목 #7(default: 종목당 10%·일일 -3%) + 본 ADR 정합.
- **T-O (W3)** — Pending cancel 흐름 합류 — KisOrderCancelAdapter 신규.
- **T-SIG (W3)** — Signal audit은 남을 수 있으나 RiskGateway가 `KILL_SWITCH_ON`으로 주문 의도를 차단한다.

### 3-2. 컨테이너 영향

| 컨테이너 | 영향 |
|---|---|
| `api` | OrderController 진입 시 KillSwitch 검사 + STOMP fanout + manual on/off endpoint |
| `consumer` | SignalGenerator KillSwitch read + KillSwitchOnHandler(pending cancel) |
| `batch` | (없음 — auto-trigger는 ADR 0041 reconcile job + ADR 0024 DailyDrawdownCheckJob 정합) |
| `domain` | `KisOrderCancelPort` 신규 + `KillSwitchCachePort` |
| `frontend` | Banner ON/OFF + last reason 표시 + 60초 cooldown 진행바 |

### 3-3. 운영 영향

| 모니터링 | 임계 |
|---|---|
| KillSwitch ON 빈도 | 1일 user별 ≥ 3회 ON → 사용자 + admin 알림 (잦은 trigger = 시스템 점검 필요) |
| Pending cancel 실패율 | KillSwitch ON 시 cancel 호출 중 실패 ≥ 10% → KIS API 장애 의심 |
| OFF 후 재 ON 빈도 | 60분 내 OFF→ON 사이클 ≥ 2회 → 자동 매매 비활성 권고 알림 |

### 3-4. 트레이드오프

| 검토안 | 채택 여부 | 사유 |
|---|---|---|
| (a) **DB row 토글만** (현재 모호) | 폐기 | 행동 정의 부재 — 사고 시 다른 경로로 새는 위험 |
| (b) **5대 행동 명세 + Auto-OFF 30초** | 폐기 | Auto-OFF는 사용자 의도 무시 — Critical 안전 위반 |
| (c) **5대 행동 명세 + Manual OFF only + 60초 cooldown** (채택) | ✅ | 사용자 명시 의도 확인 + 잠시 멈춘 후 활성 — 안전 |

## 4. 후속 ADR 후보

- **0050 (가칭)** — KillSwitch 시간 기반 자동 ON (특정 시간대 외 자동 차단 — post-MVP)
- **0051 (가칭)** — KillSwitch 글로벌(시스템 전체) vs user별 — 현재는 user별만, admin이 전 사용자 일괄 차단 필요 시 결정
- **§2-11 후속 (가칭)** — N-strike 카운터 in-memory → Redis ZSET 분산 카운터 전환 (consumer multi-replica 진입 시). Phase 1 in-memory LOCK이 단일 인스턴스 가정 — multi-instance KillSwitchAutoToggler 합류 시 strike 분산 정합 의제.
- **§2-11 후속 (가칭)** — grace window·N 사용자별 설정화 (현재 5min·N=2 시스템 LOCK). 운영 evidence 누적(블록 카운터 메트릭)으로 사용자별 튜닝 필요 시.

## 5. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | ADR 0043 신규 발행 — Critical fitness 갭 3번. Q2=A 사용자 결정 정합. (architect) |
| 2026-05-21 | **Amended** — §2-11 race-safe auto-ON policy (A+B+C) 추가. 사용자 본인 dogfooding(09:43 매수 → 11:05 KillSwitch false positive) 직후 직접 정책 LOCK. ADR 0070 §2-11 step 5·6 "drift 검출 즉시" → "A·B·C 가드 통과 시" 권위 위임 수신. A=pending order exclusion (reconcile worker publish 단계 필터) + B=grace window 5분 (`KillSwitchAutoToggler` lastFilledAt 체크) + C=N-strike N=2 (`KillSwitchAutoToggler` per-userId·symbol in-memory ConcurrentHashMap, Phase 2 Redis 분산). D=drift 임계치 raise 명시 폐기 (자본 보호 본질 훼손). (architect) |
