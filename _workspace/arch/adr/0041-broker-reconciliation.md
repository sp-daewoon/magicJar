# ADR 0041 — Broker Reconciliation Job (KIS 잔고 ↔ 내부 position 정합)

- 상태: Accepted
- 작성일: 2026-05-07
- 결정자: architect (사용자 위임 + orchestrator dispatch · Q2=A Critical 7건 중 1)
- 관계: ADR 0011 보강(주문 실행기 3-mode) · ADR 0024 보강(Risk Guard chain·KillSwitch) · ADR 0037 정합(KIS rate limit) · ADR 0010 정합(Multi-tenant)

---

## 1. 컨텍스트 (왜 이 결정이 필요한가)

ADR 0011·0024는 KIS WebSocket 체결통보(`H0STCNI0`)를 받아 내부 `position` 테이블·Redis `position:current:{userId}`를 갱신하는 단일 경로만 가정한다. 그러나 **WS 끊긴 사이 KIS 서버에는 체결됐는데 우리 DB에는 반영 안 된 상태(drift)** 가 발생할 수 있다. 발생 사례:

- consumer JVM 재시작 → WS 재연결 사이 1~30초 공백
- KIS WS 서버 일시 장애 (우리쪽 코드 문제 아님)
- 네트워크 지연·패킷 누락
- KIS WS 동시 구독 41건 한도 초과로 일부 종목 미구독

drift가 검출되지 않으면 다음 시그널이 **이미 산 종목 또 사거나 / 이미 판 종목 또 파는 사고**로 직결. ADR 0024 PositionLimitGuard도 잘못된 position 값으로 무력화.

**필요한 결정**:
- KIS REST 잔고를 주기적으로 가져와 내부 DB와 비교
- drift 검출 시 자동 차단 + 사용자 통지
- multi-tenant + KIS rate limit 정합

## 2. 결정

### 2-1. 핵심 명세

**`batch:BalanceReconcileJob` 신설** — Spring Batch + `@Scheduled` cron.

| 시점 | cron | 사유 |
|---|---|---|
| 시장 OPEN | `0 */5 9-15 * * MON-FRI` (5분 주기) | 매매 활성 — drift 빠른 감지 필요 |
| 시장 CLOSED | `0 0 * * * *` (1시간 주기) | 매매 0건 — 야간·주말 catch-up 용 |
| 사용자 manual trigger | `POST /api/risk/reconcile` (api 모듈) | drift 의심 시 즉시 |

**KIS endpoint** (open-trading-api 정합):

| 항목 | 값 | 출처 |
|---|---|---|
| URL | `/uapi/domestic-stock/v1/trading/inquire-balance` | `examples_llm/domestic_stock/inquire_balance/inquire_balance.py` |
| TR_ID (실전) | `TTTC8434R` | 동 |
| TR_ID (모의) | `VTTC8434R` | 동 |
| 페이지 한도 | 50건/요청(실전), 20건/요청(모의) — 페이징 처리 의무 | 동 |
| 주요 request | `CANO`, `ACNT_PRDT_CD`, `AFHR_FLPR_YN=N`, `INQR_DVSN=02`, `UNPR_DVSN=01`, `FUND_STTL_ICLD_YN=N`, `FNCG_AMT_AUTO_RDPT_YN=N`, `PRCS_DVSN=00`, `CTX_AREA_FK100`, `CTX_AREA_NK100` | 동 |

`docs/operations/kis-api-reference.md` §2 정합 — 본 ADR에서 첫 사용 endpoint.

### 2-2. 데이터 흐름 / 시퀀스

```
[BalanceReconcileJob fires (cron)]
  ↓
[1] 활성 user_id 순회 (KIS 키 등록·KillSwitch OFF·trading_mode ∈ {MOCK_KIS, REAL_KIS})
  ↓
[2] KisRateLimiterPort.acquire()  ← ADR 0037 통과 의무
  ↓
[3] KisBalanceAdapter.fetchBalance(userId)
        → /uapi/domestic-stock/v1/trading/inquire-balance
        → 전 페이지 페이징 흡수 (CTX_AREA_NK100 loop)
  ↓
[4] BalanceReconciler.compare(kisBalance, internalPosition)
        → diff 계산 (종목별 보유수량·평균단가)
  ↓
[5a] diff = 0   → Kafka publish: position.reconciled.v1 (audit only)
[5b] diff > 0   → drift 처리 (3 단계 동시):
        i.   DB position upsert (KIS 값 = 신뢰원)
        ii.  Redis position:current:{userId} SET (TTL 24h)
        iii. Kafka publish: position.drift.v1
                → consumer KillSwitchAutoToggler: kill_switch_state.enabled = true
                → api STOMP /topic/risk/{userId}: risk.violation.v1 push
                → audit row: risk_event (type=POSITION_DRIFT, kis_qty/internal_qty diff)
```

**drift 검출 임계치**:
- 수량 diff ≥ 1주 → 즉시 drift 처리
- 평균단가 diff ≥ 0.5% → audit only (Kafka에는 reconciled.v1로 publish, KillSwitch toggle 안 함)
- 종목 자체 누락 (KIS에는 있는데 내부 0주) → 즉시 drift

### 2-3. multi-tenant / KIS rate limit / 정합

| 항목 | 정책 |
|---|---|
| **multi-tenant** | user_id 단위로 reconcile. 활성 사용자 N명일 때 5분에 N회 호출. |
| **KIS rate limit** | ADR 0037 `KisRateLimiterPort.acquire()` 통과 의무. batch share=3/10 → 동시 3 user 처리 + 나머지 큐잉. |
| **OAuth 토큰** | ADR 0038 분산락 정합 — `kis:token:{userId}` Redis 캐시 재사용. |
| **모의/실전 분리** | user_id의 `trading_mode`에 따라 `TTTC8434R` / `VTTC8434R` 자동 분기. |
| **OAuth 401** | 1회 강제 토큰 재발급 후 재시도. 2회 연속 실패 시 사용자별 KillSwitch auto-ON + Slack/Stomp alert. |
| **EGW00201 (TPS 초과)** | RateLimiter 통과 후에도 발생 시 1초 backoff + 1회 재시도. 그 후 실패는 다음 cron으로. |
| **idempotency** | drift detect 후 동일 user_id + 5분 내 재실행 시 직전 결과 캐시 (Redis `reconcile:lastrun:{userId}` TTL 5분). |

### 2-4. 데이터 영향

| 테이블 | 변경 | Flyway |
|---|---|---|
| `position` | 행 upsert(qty·avg_price 갱신) | 기존 V18 활용 |
| `risk_event` | `type=POSITION_DRIFT` 신설 | V18 보강 (DDL 변경 없음 — type ENUM만 추가) |
| `reconcile_run` (신규) | `id, user_id, started_at, finished_at, drift_count, status` — audit 필수 | **V22** (T-RECON 진입 시) |

**Kafka topic 신규**:

| topic | producer | consumer | retention |
|---|---|---|---|
| `position.reconciled.v1` | batch | (audit) | 7d |
| `position.drift.v1` | batch | consumer(KillSwitchAutoToggler), api(STOMP fanout) | 30d |

### 2-5. KillSwitch 자동 토글 정책

drift 검출 → **자동 KillSwitch ON**. 행동 명세는 ADR 0043 정합. 사용자가 명시적으로 OFF 토글하기 전까지 신규 주문 차단.

**예외**: 평균단가 diff ≤ 0.5%·수량 diff = 0 (재계산 노이즈) → KillSwitch 토글 X. audit만.

## 3. 결과·영향

### 3-1. 트랙 영향

- **T-O (W3)** — 본 ADR이 OrderExecutor·Position projection의 안전망. T-O 진입 시 본 ADR 의무 통합.
- **T-RISK (W3)** — `risk_event.type=POSITION_DRIFT` 처리 + KillSwitch auto-toggle 합류.
- **신규 트랙 후보**: **T-RECON (W3)** — `BalanceReconcileJob` + `BalanceReconciler` 도메인 + `KisBalanceAdapter`. 또는 T-O 흡수도 가능 (사이즈 추정 M).

### 3-2. 컨테이너 영향

| 컨테이너 | 영향 |
|---|---|
| `batch` | `BalanceReconcileJob` 신설 (`@Scheduled` + `JobLauncher`) |
| `consumer` | `KillSwitchAutoToggler` listener (position.drift.v1 sub) |
| `api` | manual trigger `POST /api/risk/reconcile/{userId}` + STOMP `/topic/risk/{userId}` fanout |
| `domain` | `BalanceReconciler` 도메인 + `KisBalancePort` interface |

### 3-3. 운영 영향

| 모니터링 | 임계 |
|---|---|
| `reconcile_run` 5분 lag | 활성 user의 직전 `finished_at`이 7분 이상이면 alert |
| drift_count 누적 | 1시간에 user별 ≥3회 drift → 사용자 알림 + KIS WS 구독 상태 점검 |
| KIS API 401 누적 | 사용자별 5분에 3회 → KillSwitch auto-ON (ADR 0043 trigger 정합) |

### 3-4. 트레이드오프

| 검토안 | 채택 여부 | 사유 |
|---|---|---|
| (a) **WS 체결통보만 신뢰** (현재) | 폐기 | drift 사고 위험 (WS 끊김 = blind) |
| (b) **REST 폴링 only** (WS 폐기) | 폐기 | 실시간성 손실 (5분 lag) — UX 후퇴 |
| (c) **WS + REST 병행 reconcile** (채택) | ✅ | 실시간 + 안전망 — 산업 표준 (대부분 broker SDK 정합) |

## 4. 후속 ADR 후보

- **0046 (가칭)** — KIS WS 동시 구독 41건 한도 초과 시 우선순위 정책 (Watchlist top-N 우선)
- **0047 (가칭)** — Reconcile drift threshold 사용자 설정화 (현재는 1주·0.5% 고정)

## 5. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | ADR 0041 신규 발행 — Critical fitness 갭 1번. Q2=A 사용자 결정 정합. (architect) |
| 2026-05-13 | **ADR 0070 묶음 spec 정밀화** — Phase 분리 LOCK (T-RECON Phase 1 strategy-engine → Phase 2 backend-core 직렬 디스패치), sub-agent 디스패치 정밀 명세 권위는 ADR 0070 §2-2·§2-3 + spec `_workspace/specs/pr-392-recon-notify-spec.md` §2-2·§2-3로 이관. Kafka 토픽 명명 갱신 — 본 ADR §2-4의 `position.drift.v1` → **`reconcile.drift.v1`** (semantic이 reconcile job 산출 결과이며 position 자체 변동 `order.filled.v1`과 구분 명확화). 신규 publish 2건 추가 LOCK — `reconcile.run.v1` (7d retention audit) + `killswitch.engaged.v1` (30d, KillSwitchAutoToggler가 publish). KIS-first raw 인용 4건 LOCK (TTTC8434R·VTTC8434R inquire-balance + CTRP6548R inquire-account-balance 보조 + H0STCNI0 cross-ref). Flyway 슬롯 V22 → V25·V26 시프트 (T-O가 V20~V24 사용 — ADR 0068 §15-1 LOCK). 본 ADR §1~§4 결정 권위 보존 (supersede 0 — reuse + 정밀화). (architect) |
