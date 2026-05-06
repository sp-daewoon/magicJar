# ADR 0043 — KillSwitch 행동 정의 (ON 시 신호·주문·통지·재시작 정책)

- 상태: Accepted
- 작성일: 2026-05-07
- 결정자: architect (사용자 위임 + orchestrator dispatch · Q2=A Critical 7건 중 3)
- 관계: ADR 0024 보강(Risk Guard chain — KillSwitchGuard) · ADR 0011 정합(주문 실행기) · ADR 0041 정합(drift auto-toggle) · ADR 0023 정합(Signal Engine)

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
| 1 | **신규 시그널 emit 즉시 정지** | `consumer` | `SignalGenerator`가 user_id별 KillSwitch 상태를 Redis 캐시에서 read → ON이면 시그널 evaluate skip. Kafka `signal.generated.v1` publish 0건. |
| 2 | **Pending 주문 cancel** | `consumer` | DB `orders` 중 status=PENDING\|PARTIAL_FILLED 행을 KIS REST `order-rvsecncl` 호출로 일괄 취소. 취소 실패는 `order_reject_audit` row + 사용자 통지. |
| 3 | **STOMP push** | `api` | `risk.killswitch.v1` Kafka topic sub → `/topic/risk/{userId}` fanout. frontend Banner ON. |
| 4 | **신규 주문 ingestion 차단** | `api` | REST `POST /api/orders` controller 진입 직전에 user_id KillSwitch 상태 확인 → ON이면 422 Unprocessable Entity 반환 + 에러 메시지 "KillSwitch 활성화 — 사용자 명시 OFF 필요". |
| 5 | **체결통보는 정상 수신·기록** | `consumer` | KIS WS `H0STCNI0` 체결통보는 KillSwitch와 무관하게 정상 수신 + DB·Redis 갱신. (이미 발사된 주문의 체결까지 막을 수는 없음 — audit 의무) |

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

## 3. 결과·영향

### 3-1. 트랙 영향

- **T-RISK (W3)** — Guard chain의 KillSwitchGuard 외에 본 ADR 5대 행동 명세 의무 통합. T-RISK 결정 항목 #7(default: 종목당 10%·일일 -3%) + 본 ADR 정합.
- **T-O (W3)** — Pending cancel 흐름 합류 — KisOrderCancelAdapter 신규.
- **T-SIG (W3)** — SignalGenerator의 KillSwitch read 의무.

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

## 5. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | ADR 0043 신규 발행 — Critical fitness 갭 3번. Q2=A 사용자 결정 정합. (architect) |
