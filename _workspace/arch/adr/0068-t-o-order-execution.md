# ADR 0068 — T-O 주문 실행 (Order Execution — Risk ALLOWED → KIS REST 주문 → 체결 추적 → 정정/취소 + STOMP 알림)

- 상태: **Accepted (2026-05-13 — 사용자 결정 A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1 모두 채택)**
- 작성일: 2026-05-12
- 작성자: architect
- 결정자: 사용자(최고 관리자) — 결정 의제 8건 검토 위임
- 진입 트랙: **T-O (W3 네 번째 트랙, v3.6.0 미러스톤 후보)**
- 관계:
  - **ADR 0067 (T-RISK 리스크 관리)** — 본 ADR이 그 결정의 후속. Kafka `order.intent.v1` topic을 T-RISK ALLOWED 시 publish → 본 ADR consumer가 listen 진입점 LOCK
  - **ADR 0066 (T-SIG 실시간 시그널 엔진)** — Signal → Risk → **Order** 흐름의 종착 노드. signal.id cross-ref 의무
  - **ADR 0024 (Risk Guard chain 6단)** — KillSwitch ON 시 본 ADR 즉시 reject 정합
  - **ADR 0043 (KillSwitch 행동 정의 — 5대 행동)** — 본 ADR 진입 직전 KillSwitch 재검증 의무 (ADR 0067 ALLOWED 이후에도 부팅 후 토글 가능)
  - **ADR 0042 (Position Bootstrap)** — 부팅 시 KIS 잔고 워밍 + 본 ADR 체결 후 position 갱신 정합
  - **ADR 0041 (Broker Reconciliation)** — 본 ADR 체결 영속 ↔ KIS 잔고 drift 검출 baseline (T-RECON 트랙)
  - **ADR 0044 (Notification System)** — `order.requested.v1`·`order.filled.v1`·`order.rejected.v1` Slack 통지 정합 (P0/P1/P2 priority 매트릭스 확장)
  - **ADR 0045 (Graceful Shutdown)** — OrderRouter Kafka listener + KIS WS 체결통보 subscriber SmartLifecycle 정합
  - **ADR 0010 (Multi-tenant)** — userId 격리 의무 (order_request·order_execution·order_audit_event 모두 userId scoped)
  - **ADR 0023 (Signal Engine — AI 호출 0)** — 본 ADR도 동일 정책 계승. 주문 결정에 LLM 호출 0건
  - **ADR 0030 (master-architecture SoT)** — §10 ADR 0068 row 추가 동반
  - **ADR 0037 (KIS Rate Limit 20 req/s)** — 본 ADR 주문 API burst 영향 검증 의무 (§9 운영 영향)
  - **ADR 0046 (Onboarding & Credential Registration)** — `user_kis_credential` REAL/MOCK 두 종 fetch 정합. 본 ADR이 그 `account_type`에 따라 분기
  - **ADR 0048 (KIS WS 41건 구독 한도)** — 본 ADR H0STCNI0 체결통보 추가 시 한도 영향 검증 의무 (§9 운영 영향)
  - **ADR 0051 (Spring Data Repository default fun 함정)** — Repository 모든 fun은 abstract만, body는 Service/Adapter 위치 의무
  - **ADR 0057 (KIS-only data)** — 본 ADR도 KIS 단일 출처. 단, **데이터 출처 분기 0** (사용자 메모리 `feedback_kis_key_realonly_for_data`) — 정보 수집은 실전 키 단일, 본 ADR(주문 액션)만 `account_type` REAL/MOCK 분기
  - **ADR 0061 (Backtest 결과 영속)** — 3 테이블 분리 + REST endpoint 패턴 baseline 적용 (본 ADR도 3 테이블 분리 의제 G-1)

> **AI 호출 0 검증 의무 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 정합)**: 본 ADR 범위 내 모든 컴포넌트 코드에 LLM provider import(Anthropic SDK · Spring AI ChatClient · Gemini SDK · OpenAI SDK) 0건. sub-agent 디스패치 시 architect 1회 grep 검증 의무 + 코드 inline 주석 정책 명시. 주문 의사결정은 **deterministic 룰만** (signal → risk → order 직진 chain).

> **KIS-first raw 인용 의무 (헌법 §3 — 본 ADR 강조 영역)**: 본 ADR은 KIS 주문 endpoint·tr_id·체결 통보 WS·필드명을 **자체 추론 0**으로 raw 출처 인용. 자세한 인용 인벤토리는 §7-5. sub-agent 코드 inline 주석에 raw URL + line 인용 의무 (KDoc 또는 //).

> **실계좌 이중 게이트 의무 (CLAUDE.md 함정 4번 + 사용자 메모리 `feedback_kis_key_realonly_for_data`)**: REAL 주문 발행 조건 = `MAGICJAR_MODE=REAL` **AND** `MAGICJAR_ALLOW_REAL=true` **AND** 사용자 etc 사전 동의 토글(미래 §). 한 조건이라도 부재 시 — 진입 시점 즉시 BLOCKED + risk_event 영속 + STOMP 알림. dev/staging 환경은 항상 MOCK 강제.

---

## 1. 컨텍스트

### 1-1. T-O 트랙 위치 (tracks-roadmap §1·§2)

W3 자동 매매 본체 트랙 (룰만, AI 호출 0):
- T-BT (Backtest Engine) — **v3.3.1 마감**
- T-SIG (Signal Engine) — **v3.4.0 마감**
- T-RISK (Risk Guard chain) — **v3.5.0 마감 (ADR 0067 Accepted)**
- **T-O (Order Execution)** — **본 ADR 진입 (W3 네 번째 트랙)**
- T-SC (Screener) — 후행
- T-RECON·T-NOTIFY·T-PERF·T-J — 후행 (본 ADR 영속 매트릭스 합류 후)

T-O는 **자동매매 본체의 종착 노드**:
1. T-SIG 시그널 + T-RISK ALLOWED 게이트 통과 후 **실제 KIS REST 매수/매도 주문 발행**
2. 주문 후 체결 추적 (KIS WS H0STCNI0 체결통보 listen) → position 갱신
3. 정정/취소 (시간 초과 자동 OR 사용자 수동)
4. 영속 audit trail (의도·체결·이벤트 3 테이블 분리)
5. STOMP 사용자 알림 (REQUESTED·FILLED·REJECTED·CANCELLED 4 event)
6. **AI 호출 0** (ADR 0023 §2-4 정합) — 주문 의사결정은 deterministic 룰만 (signal → risk → order 직진 chain)
7. **실계좌 이중 게이트** — REAL 진입 시 environment 변수 두 개 + 사용자 동의 토글 검증

### 1-2. 현재 main 상태 (사전 자산 — v3.5.0 cut 완료)

자산 확보 완료:
- **ADR 0067** — T-RISK Accepted (PR #351·#353·#354·#355·#356·#357 머지)
- **Kafka `order.intent.v1` topic** — T-RISK ALLOWED 시 publish (본 ADR 진입점 LOCK)
- **ADR 0024** — Risk Guard chain 6단 (KillSwitch 재검증 baseline)
- **ADR 0042** — Position Bootstrap (KIS 잔고 워밍 + 본 ADR position 갱신 합류점)
- **ADR 0046** — Onboarding + `user_kis_credential` REAL/MOCK 2종 row baseline
- **ADR 0048** — KIS WS 구독 한도 41건 (본 ADR H0STCNI0 추가 영향 검증)
- **Signal·Risk 영속** (signal.risk_status='ALLOWED' → order intent publish)
- **STOMP 인프라** (`/topic/signal.user.{userId}`·`/topic/risk.user.{userId}` 패턴 baseline)
- **헌법 §3 KIS-first** — 본 ADR이 그 의무 영역 (주문 endpoint·체결통보 raw 인용)

본 ADR이 **위 자산을 Order 발행 path에 통합 + 사용자 자본 실제 변동 발생 노드 가동**.

### 1-3. fitness gap 분석 (사용자 메모리 `feedback_v3_skepticism_no_regression.md` lens)

#### 1-3-1. 자동매매에 필요한가?

**필수** — magicJar 비전 정의 (`project_system_vision.md`):
> "룰 기반 quant trading 본체 + AI 옵션 보조. 매매=룰/시그널"

T-O가 그 룰 본체의 **종착 실행 노드**. 부재 시:
- T-SIG 시그널 + T-RISK ALLOWED 게이트가 통과해도 **실제 주문 미발행** — 자동매매 본체 0
- 사용자가 수동으로 KIS HTS·MTS로 주문 입력 의무 — magicJar 가치 0
- audit trail 부재 — 시그널·리스크·주문 cross-ref 추적 불가

**자동매매 본체의 최종 실행 노드, 필수**. ADR 0067 §1-3-1 동일 lens.

#### 1-3-2. v1/v2 잔재 아닌가?

**잔재 아님** — v3.x baseline 신규. ADR 0024(2026-05-03)는 baseline 골격만 정의(KisOrderSender 클래스명 후보 인용). v1/v2 시점 실제 주문 코드 0건(PoC 코드 v3.0.0 전체 정리). 본 ADR이 그 자산의 W3 실제 구현 권위 — KIS REST + WS + 영속 + STOMP 통합.

#### 1-3-3. 사용자 비전 정합

- magicJar = 룰 기반 본체 → 본 ADR 주문 결정은 deterministic chain (signal.id → risk.allowed → order.intent → KIS REST)
- 사용자 = 최고 관리자/설계자 → 본 ADR `MAGICJAR_ALLOW_REAL` 이중 게이트 + 사용자 수동 정정/취소 UI 의무
- AI는 옵션 보조 → 본 ADR LLM 호출 0건 검증 의무 (§8)
- MCP 서버 노출(ADR 0027) → 향후 외부 client `order_request`·`order_execution` query API 정합
- **정보 수집 = 실전 키 단일** (사용자 메모리 `feedback_kis_key_realonly_for_data`) → 본 ADR 데이터 출처 분기 0. 본 ADR의 `MAGICJAR_MODE` 분기는 **주문 발행 액션 한정**

---

## 2. 결정 의제 (사용자 결정 대기)

architect 권장안 8건 + 각 의제 대안 매트릭스. 본 ADR `Accepted` 갱신 후 sub-agent 디스패치 진입.

### 2-1. 의제 A — 모의/실전 분기 (Trading Mode Gate)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **A-1 환경변수 이중 게이트 + 사용자 동의 토글 (3중 게이트)** ⭐ | (a) `MAGICJAR_MODE=MOCK\|REAL` 환경변수 (b) `MAGICJAR_ALLOW_REAL=true` 환경변수 (c) `user_account.real_trading_consent_at` 사용자 동의 토글 (SettingsModal 명시 체크) — 3개 모두 충족 시에만 REAL 주문 KIS endpoint 호출 | CLAUDE.md 함정 4번 정합 + 사용자 명시 동의 (3중) + dev/staging 자동 MOCK + 실수 방지 강화 | 사용자 SettingsModal 토글 1회 의무 — UX 약간 부담 (1회만, 안전 ↑ trade-off) |
| A-2 환경변수 이중만 (CLAUDE.md baseline 그대로) | `MAGICJAR_MODE=REAL` + `MAGICJAR_ALLOW_REAL=true` 2개만 | CLAUDE.md 단순 정합 + 코드 변경 최소 | 사용자 동의 명시 부재 — 환경변수만으로 실 자본 변동 risk |
| A-3 단일 게이트 (mode env만) | `MAGICJAR_MODE=REAL` 단일 | UX 최소 | 환경변수 1개 실수로 실주문 risk + CLAUDE.md 함정 4번 회귀 |

**architect 권장 = A-1 3중 게이트 (env × 2 + 사용자 동의 토글)**

사유:
1. **사용자 자본 실 변동 노드의 안전 의무** — 본 ADR이 첫 실 자본 변동 점. 한 layer 더 추가가 안전 ROI 압도적
2. **사용자 메모리 `project_kis_key_management.md` 정합** — SettingsModal 입력 baseline 이미 있어 추가 토글 작은 부담
3. **dev/staging 자동 MOCK** — `MAGICJAR_MODE` default = MOCK (기존 baseline 그대로). 본 ADR에서 default 변경 0
4. **A-2 폐기** — CLAUDE.md 함정 4번 정합이지만 사용자 명시 부재 — 1 layer 더 추가가 그 ROI
5. **A-3 폐기** — 단일 게이트 fragile

**구현 매트릭스**:
```kotlin
// domain/.../order/TradingModeGate.kt
class TradingModeGate(
    private val mode: TradingMode,                // env MAGICJAR_MODE
    private val allowReal: Boolean,               // env MAGICJAR_ALLOW_REAL
    private val userConsentAt: Instant?,          // user_account.real_trading_consent_at
) {
    fun resolveMode(userId: String): TradingMode = when {
        mode == TradingMode.MOCK -> TradingMode.MOCK
        mode == TradingMode.REAL && allowReal && userConsentAt != null -> TradingMode.REAL
        else -> throw RealTradingNotAllowedException(userId, mode, allowReal, userConsentAt)
    }
}
```

위반 시 `order_audit_event` INSERT(`REAL_GATE_DENIED`) + STOMP REJECTED 통지.

### 2-2. 의제 B — KIS REST 주문 endpoint + tr_id (Endpoint Matrix)

**자체 추론 0** — KIS-first raw 출처 인용 (헌법 §3).

**raw 출처**: `https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions.py` L10097~L10213 (`def order_cash(...)`)

| 액션 | 환경 | tr_id | endpoint |
|---|---|---|---|
| **매수** | REAL | `TTTC0012U` | `POST /uapi/domestic-stock/v1/trading/order-cash` |
| 매수 | MOCK | `VTTC0012U` | `POST /uapi/domestic-stock/v1/trading/order-cash` |
| **매도** | REAL | `TTTC0011U` | `POST /uapi/domestic-stock/v1/trading/order-cash` |
| 매도 | MOCK | `VTTC0011U` | `POST /uapi/domestic-stock/v1/trading/order-cash` |
| **정정/취소** | REAL | `TTTC0013U` | `POST /uapi/domestic-stock/v1/trading/order-rvsecncl` |
| 정정/취소 | MOCK | `VTTC0013U` | `POST /uapi/domestic-stock/v1/trading/order-rvsecncl` |
| **체결통보 WS** | REAL | `H0STCNI0` | WS subscribe (사용자별 1 구독) |
| 체결통보 WS | MOCK | `H0STCNI9` | WS subscribe |

**raw request body (order-cash)** — L10194~L10204 인용:
```
{
  "CANO": <종합계좌번호 8자리>,
  "ACNT_PRDT_CD": <계좌상품코드 2자리, 01>,
  "PDNO": <종목코드 6자리>,
  "ORD_DVSN": <주문구분 — 00:지정가, 01:시장가, ...>,
  "ORD_QTY": <주문수량 String>,
  "ORD_UNPR": <주문단가 String — 시장가는 "0">,
  "EXCG_ID_DVSN_CD": <거래소ID — KRX·NXT·SOR>,
  "SLL_TYPE": <매도유형 — 매도주문 시 01:일반매도>,
  "CNDT_PRIC": <조건가격 — 옵션>
}
```

**raw response output** — L10209 `res.getBody().output` — 핵심 필드:
- `KRX_FWDG_ORD_ORGNO` — 한국거래소전송주문조직번호 (정정/취소 시 의무 동반)
- `ODNO` — 주문번호 (KIS측 주문 ID)
- `ORD_TMD` — 주문시각 HHMMSS

**raw request body (order-rvsecncl)** — L10860~L10875 인용:
```
{
  "CANO": ...,
  "ACNT_PRDT_CD": ...,
  "KRX_FWDG_ORD_ORGNO": <원주문의 KRX_FWDG_ORD_ORGNO>,
  "ORGN_ODNO": <원주문번호>,
  "ORD_DVSN": ...,
  "RVSE_CNCL_DVSN_CD": <01:정정, 02:취소>,
  "ORD_QTY": ...,
  "ORD_UNPR": ...,
  "QTY_ALL_ORD_YN": <Y:전량, N:일부>,
  "EXCG_ID_DVSN_CD": "KRX"
}
```

**의제 B 옵션**:

| 옵션 | 설명 |
|---|---|
| **B-1 단일 OrderRouterPort + KisOrderAdapter (mode 분기 내부)** ⭐ | port 단일(`OrderRouterPort.submit(intent)`), adapter 내부에서 `TradingModeGate.resolveMode` 후 tr_id 선택 + KIS REST 호출 |
| B-2 strategy 패턴 (KisRealOrderAdapter + KisMockOrderAdapter 2 클래스) | `@Profile("real")` / `@Profile("mock")` 분기 — Spring profile baseline 의존 |
| B-3 reflection 기반 tr_id 매트릭스 lookup | 단일 클래스 + tr_id Map<(mode, side), String> lookup |

**architect 권장 = B-1 단일 port + adapter 내부 mode 분기**

사유:
1. **3중 게이트 흐름 일원화** — adapter 내부 `TradingModeGate` 호출 → REAL/MOCK 분기 + tr_id 선택 단일 path
2. **테스트 용이** — Mock 환경 분기는 KIS HTTP mock(VPS 호출 안 함)으로 단위 검증
3. **B-2 폐기** — Spring profile mode 분기는 default profile baseline(`dev,real-kis`)과 conflict + dev에서 MOCK 주문 가능해야 함(env 의존이 더 자연)
4. **B-3 폐기** — lookup overhead + 가독성 ↓ (Map vs `if/when` deterministic 코드)

### 2-3. 의제 C — 주문 가격 결정 (Price Type)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **C-1 ORD_DVSN 매트릭스 — 00 지정가 / 01 시장가 + 슬리피지 0 (1차)** ⭐ | signal.contextJson에서 priceType·limitPrice 읽음 — 시장가는 `ORD_UNPR="0"` + `ORD_DVSN="01"` / 지정가는 `ORD_UNPR=limitPrice` + `ORD_DVSN="00"` | 단순 + KIS-first 정합 + MVP 적정 | 슬리피지 cover 0 (시장가는 KIS 호가 즉시 진입) |
| C-2 C-1 + 사용자 슬리피지 % | 사용자 설정 슬리피지(예: 0.5%) — 시장가 진입 시 limit으로 변환 (signal.price × (1 + slippage)) | 슬리피지 cover | UX 복잡도 + signal.price 신뢰성 의존 (시그널 발행 시점 가격 ↔ 주문 발행 시점 가격 lag) |
| C-3 지정가 only — 시장가 폐기 | 모든 주문 limit 진입 | 가격 통제 명확 | 체결 미보장 — 호가 변동 시 미체결 risk |

**architect 권장 = C-1 ORD_DVSN 00·01 매트릭스 (시장가/지정가) — 1차 MVP**

사유:
1. **MVP 본질** — 시장가/지정가 baseline cover (사용자 strategy DSL에서 priceType 선택)
2. **signal.contextJson 정합** — T-SIG 시그널 발행 시 priceType·limitPrice 함께 payload (signal 도메인에 이미 존재 — ADR 0066 §5)
3. **C-2 후속** — backlog §43 등록 (슬리피지 트랙). 1차 MVP에서는 미도입
4. **C-3 폐기** — 체결 미보장 시 자동매매 본체 정지 risk

**구현 매트릭스**:
```kotlin
// domain/.../order/OrderPriceType.kt
enum class OrderPriceType(val ordDvsn: String) {
    MARKET("01"),     // 시장가 — ORD_UNPR="0"
    LIMIT("00"),      // 지정가 — ORD_UNPR=limitPrice
}
```

### 2-4. 의제 D — 체결 추적 (Execution Tracking)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **D-1 KIS WS H0STCNI0 체결통보 실시간 listen (1차)** ⭐ | OrderRouter 호출 후 KIS WS `ccnl_notice` subscribe — 체결통보 수신 시 `order_execution` INSERT + `order.filled.v1` publish + STOMP FILLED 알림 | 실시간 ~100ms latency + REST 잔고 polling 부담 0 + 체결-매수 1:N (분할 체결) cover | KIS WS 구독 한도 41건 영향 검증 의무 (ADR 0048) — 사용자별 1 구독 추가 |
| D-2 REST 잔고 polling (1분 주기) | inquire_balance API 1분 polling — diff 검출 | 단순 (WS 추가 0) | 1분 latency + KIS API 호출 빈도 ↑ (Rate Limit ADR 0037 부담) |
| D-3 D-1 + D-2 dual (WS 미수신 시 polling fallback) | 1차 WS, 5분 후 미체결 시 REST polling fallback | 신뢰성 ↑ | 복잡도 ↑ + MVP 과다 |

**architect 권장 = D-1 KIS WS H0STCNI0 실시간 (1차) — 사용자 메모리 정합**

사유:
1. **실시간 — 사용자 dogfooding UX 직접 영향** — 주문 후 체결 알림이 분 단위로 늦으면 자동매매 신뢰성 0
2. **KIS WS 구독 한도 영향** — ADR 0048 41건 baseline에서 사용자별 1 구독 추가 — 한도 영향 검증 §9
3. **분할 체결 cover** — H0STCNI0가 1주문 N체결 시 N개 메시지 발행 (raw L443 `CNTG_QTY`·L443 `CNTG_UNPR` per 체결)
4. **D-2 폐기** — latency 부담 + Rate Limit 영향
5. **D-3 후속** — backlog §44 등록 (D-1 dogfooding 결과 + 미체결 사례 누적 후 fallback 검토)

**raw 출처**: `examples_user/domestic_stock/domestic_stock_functions_ws.py` L389~L446 `def ccnl_notice(...)` — tr_id `H0STCNI0` (real) / `H0STCNI9` (demo). 26 컬럼 raw 인용:

```
CUST_ID, ACNT_NO, ODER_NO, OODER_NO, SELN_BYOV_CLS, RCTF_CLS,
ODER_KIND, ODER_COND, STCK_SHRN_ISCD, CNTG_QTY, CNTG_UNPR,
STCK_CNTG_HOUR, RFUS_YN, CNTG_YN, ACPT_YN, BRNC_NO, ODER_QTY,
ACNT_NAME, ORD_COND_PRC, ORD_EXG_GB, POPUP_YN, FILLER, CRDT_CLS,
CRDT_LOAN_DATE, CNTG_ISNM40, ODER_PRC
```

핵심 필드:
- `ODER_NO` — KIS 주문번호 (본 ADR `order_request.kis_order_no` cross-ref)
- `CNTG_QTY` — 체결수량 (분할 체결 시 누적 분)
- `CNTG_UNPR` — 체결단가
- `STCK_CNTG_HOUR` — 체결시각 HHMMSS
- `CNTG_YN` — 체결여부 ("1"=주문/정정/취소/거부 접수 통보, "2"=실제 체결 통보) — 본 ADR `OrderExecution` insert는 `CNTG_YN="2"`만
- `RFUS_YN` — 거부여부 ("Y"=거부 → `order_request.status=REJECTED` 갱신)

### 2-5. 의제 E — 정정/취소 (Modification / Cancellation)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **E-1 사용자 수동 (REST `POST /api/orders/{id}/cancel`) — 1차 MVP** ⭐ | UI 주문 카드의 [취소] 버튼 → `order-rvsecncl` 호출 (RVSE_CNCL_DVSN_CD="02") | UX 명시 + MVP 단순 + 사용자 통제 (최고 관리자 비전 정합) | 자동 취소 부재 — 미체결 누적 시 사용자 수동 의무 |
| E-2 자동 (지정가 N분 미체결 시 자동 취소) + 사용자 수동 둘 다 | 시간 초과 자동 취소 worker + 사용자 수동 둘 다 | 미체결 자동 정리 + UX 풍부 | scheduler + worker 추가 + MVP 과다 |
| E-3 자동만 (사용자 수동 0) | 시간 초과 자동만 | 단순 자동 | 사용자 통제 0 — magicJar 비전(최고 관리자) 위반 |

**architect 권장 = E-1 사용자 수동 only (1차 MVP)**

사유:
1. **사용자 = 최고 관리자 비전 정합** — `project_system_vision.md` "사용자=최고 관리자/설계자"
2. **MVP 단순** — 사용자가 미체결 인지 + UI 토스트 + [취소] 버튼 1 클릭으로 cover
3. **E-2 후속** — backlog §45 등록 (자동 취소 worker — 사용자 dogfooding 결과 누적 후 도입)
4. **E-3 폐기** — 사용자 통제 부재로 magicJar 비전 위반

**구현**: `OrderCancelService.cancel(orderId, userId)` → `order_request` SELECT → KIS `order-rvsecncl` 호출(RVSE_CNCL_DVSN_CD="02", QTY_ALL_ORD_YN="Y") → `order_request.status=CANCELLED` UPDATE + `order_audit_event` INSERT + STOMP CANCELLED 통지.

**정정 (RVSE_CNCL_DVSN_CD="01")** — 1차 MVP에서 미도입. 사용자가 [취소] → [신규 주문] 2 step으로 cover. 후속 backlog §46.

### 2-6. 의제 F — 영속 매트릭스 (Persistence Matrix)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **F-1 3 테이블 분리 (order_request + order_execution + order_audit_event)** ⭐ | (a) `order_request` (의도, status 5단) (b) `order_execution` (체결 분, 1:N) (c) `order_audit_event` (감사 시계열) | 도메인 분리 명확 · ADR 0061 T-BT 3 테이블 패턴 일관 · 분할 체결 1:N cover · audit trail 풍부 | 테이블 3개 — 운영 가벼움 |
| F-2 2 테이블 (order + audit) | 의도·체결 단일 테이블 + audit | 분리 미흡 | 분할 체결 1:N cover 부재 (체결가 컬럼 N건 — JSONB?) |
| F-3 단일 테이블 JSONB | 모든 의도·체결·audit 단일 + JSONB type | 운영 부담 0 | JSONB query 성능 ↓ + 도메인 혼선 |

**architect 권장 = F-1 3 테이블 분리 (ADR 0061·0067 패턴 일관)**

사유:
1. **ADR 0061 T-BT 패턴 baseline** — 3 테이블 분리 (`backtest_run`·`backtest_trade`·`backtest_position`)
2. **ADR 0067 T-RISK 패턴 일관** — `risk_config`·`risk_event`·`position` + `signal.risk_status` 컬럼 — 4 도메인 분리
3. **분할 체결 1:N cover** — KIS H0STCNI0가 분할 체결 시 N 메시지 (raw §2-4 인용) → `order_execution` N row INSERT 자연
4. **audit 풍부** — `order_audit_event` 시계열 (REQUESTED·KIS_ACK·KIS_REJECT·FILLED·CANCELLED·REAL_GATE_DENIED 등)
5. **F-2 폐기** — 분할 체결 1:N JSONB 처리 시 query 부담
6. **F-3 폐기** — 도메인 혼선

### 2-7. 의제 G — 알림 (Notification — STOMP)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **G-1 STOMP `/topic/order.user.{userId}` 4 event type — REQUESTED·FILLED·REJECTED·CANCELLED + ADR 0044 Slack 후속** ⭐ | STOMP 토픽 1 신규 + 4 event type discriminator(JSON `type` 필드) + 사용자 UI 토스트 + ADR 0044 합류 시 Slack | 사용자 즉시 인지 + UX 풍부 + ADR 0044 baseline 정합 | event type 4종 frontend 토스트 색상 매트릭스 의무 |
| G-2 STOMP 1 토픽 + 단일 type | 단순 | 사용자 토스트 차별화 부재 |
| G-3 STOMP 4 토픽 (각 event type 별 토픽) | 매트릭스 명확 | 토픽 수 폭증 (관리 부담 ↑) |

**architect 권장 = G-1 단일 토픽 + 4 event type discriminator**

사유:
1. **ADR 0067 T-RISK 패턴 일관** — `/topic/risk.user.{userId}` 단일 토픽 + BLOCKED·AUTO_PAUSED 2 type
2. **frontend useStompOrder hook 단일 subscriber** — 4 type 매트릭스 토스트 색상 분기 (REQUESTED=blue·FILLED=green·REJECTED=red·CANCELLED=gray)
3. **G-3 폐기** — 토픽 4개 frontend 4 hook 부담

### 2-8. 의제 H — 멱등성 (Idempotency Key)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **H-1 `order.intent.v1` payload에 idempotency_key (signal.id + userId hash) + Redis SETNX 60s + DB UQ (kis_order_no UQ)** ⭐ | (a) signalId 기반 멱등성 키 — Risk ALLOWED 후 idempotency_key 생성 (b) consumer가 Redis SETNX 60s — 이미 처리 시 skip (c) order_request UQ(signal_id) + KIS 응답 kis_order_no UQ | T-SIG signal 1건 = order 최대 1건 LOCK · Kafka consumer 재시작 시 중복 처리 회피 · KIS API 중복 호출 0 (자본 손실 risk 회피) | Redis 키 추가 + UQ 컬럼 추가 — 운영 가벼움 |
| H-2 DB UQ만 (Redis 0) | order_request UQ(signal_id) | 단순 | Redis SETNX 부재 시 Kafka consumer 재시작 중 race window 발생 (DB UQ INSERT는 한 쪽 fail) |
| H-3 idempotency 0 (Kafka at-least-once 그대로) | KIS 중복 호출 가능 | 운영 단순 | **금지** — 사용자 자본 중복 손실 risk |

**architect 권장 = H-1 Redis SETNX 60s + DB UQ 이중 (ADR 0066 §2-5 SignalDedupeGuard 패턴 baseline 정합)**

사유:
1. **사용자 자본 보호 본질** — KIS API 중복 호출 시 실 자본 N배 손실 risk — 회피 의무
2. **ADR 0066 §2-5 SignalDedupeGuard 패턴 baseline** — Redis SETNX 30s + DB UQ 이중. 본 ADR도 60s + UQ로 시간 확장 (주문 latency 100~500ms 대비 안전 margin)
3. **H-2 폐기** — Race window 존재 (Kafka consumer 재시작 직후 30초 window 같은 시그널 중복 listen 가능)
4. **H-3 금지** — 사용자 자본 손실 risk 본질 위반

**구현 매트릭스**:
```kotlin
// consumer/.../order/OrderIdempotencyGuard.kt
class OrderIdempotencyGuard(private val redis: StringRedisTemplate) {
    fun acquire(idempotencyKey: String): Boolean =
        redis.opsForValue().setIfAbsent("order:idem:$idempotencyKey", "1", Duration.ofSeconds(60)) ?: false
}
```

DB UQ: `order_request.idempotency_key UQ` (`signal_id` 또는 별도 hash).

---

## 3. 권장 결정 종합

| 의제 | 권장안 | 핵심 사유 |
|---|---|---|
| A. 모의/실전 분기 | **A-1** env × 2 + 사용자 동의 토글 (3중) | CLAUDE.md 함정 4번 + 사용자 명시 동의 |
| B. KIS endpoint | **B-1** 단일 port + adapter 내부 mode 분기 | KIS-first raw 인용 + 흐름 일원화 |
| C. 주문 가격 | **C-1** 시장가/지정가 (ORD_DVSN 00·01) | MVP 적정 + 슬리피지 후속 |
| D. 체결 추적 | **D-1** KIS WS H0STCNI0 실시간 | 실시간 + Rate Limit 영향 0 |
| E. 정정/취소 | **E-1** 사용자 수동 only (1차 MVP) | 사용자 통제 + 비전 정합 |
| F. 영속 | **F-1** 3 테이블 분리 (request+execution+audit) | ADR 0061·0067 패턴 + 1:N cover |
| G. 알림 | **G-1** 단일 STOMP + 4 event discriminator | UX + ADR 0067 패턴 일관 |
| H. 멱등성 | **H-1** Redis SETNX 60s + DB UQ 이중 | 자본 보호 본질 + ADR 0066 패턴 |

---

## 4. 채택 시 REST API 설계 (G-1 + F-1 정합)

### 4-1. Order CRUD/Query

| 메서드 | 경로 | 기능 | request | response |
|---|---|---|---|---|
| `GET` | `/api/orders` | 시계열 페이징 (의도+체결 cross-ref) | `?from&to&status&side&code&page&size` | `{content: [...], totalElements, totalPages}` |
| `GET` | `/api/orders/{id}` | 단건 (의도 + 체결 N건 + 감사 시계열) | (auth) | `{request, executions: [...], auditEvents: [...]}` |
| `POST` | `/api/orders/{id}/cancel` | 사용자 수동 취소 (의제 E-1) | `{reason?}` | `{request, auditEvent}` |
| `GET` | `/api/positions/current` | 현재 보유 (ADR 0067 §4-3 baseline 이미 main — 본 ADR 합류) | (auth) | `[{code, quantity, avgPrice, ...}]` |

### 4-2. 인증·인가

- userId는 JWT 또는 session 기반 (ADR 0046 정합)
- multi-tenant(ADR 0010) — order_request.userId · order_execution.userId · order_audit_event.userId 격리. 다른 사용자 조회 404 (ADR 0061 §2-7 패턴)

### 4-3. SettingsModal 보강 (의제 A-1 정합)

- `PUT /api/user/account/real-trading-consent` — `{consented: true|false}` → `user_account.real_trading_consent_at` UPDATE
- frontend SettingsModal "실거래 모드 동의" 토글 (default OFF). ON 시 경고 모달 "실 자본 변동 — KIS 실계좌 주문 발행"

**총 4 endpoint (orders 3 + position 0 — 0067 baseline 합류) + SettingsModal 1**. ADR 0061 5 + 0066 8 + 0067 7 패턴 정합.

---

## 5. 채택 시 도메인 모델 (Pure Kotlin — domain 모듈)

### 5-1. OrderIntent (주문 의도)

```kotlin
// domain/.../order/OrderIntent.kt
data class OrderIntent(
    val id: Long,
    val userId: String,
    val signalId: Long,                       // T-SIG cross-ref (의제 H-1 멱등성)
    val strategyId: Long,                     // strategy cross-ref
    val code: String,                         // 종목코드 6자리
    val side: OrderSide,                      // BUY·SELL
    val priceType: OrderPriceType,            // MARKET·LIMIT
    val quantity: Int,
    val limitPrice: BigDecimal?,              // LIMIT 시만 not null
    val status: OrderStatus,                  // REQUESTED·ACK·FILLED·PARTIALLY_FILLED·REJECTED·CANCELLED·EXPIRED
    val mode: TradingMode,                    // MOCK·REAL (의제 A-1 게이트 후 LOCK)
    val kisOrderNo: String?,                  // KIS ODNO 응답 (request 후 LOCK)
    val kisFwdgOrgno: String?,                // KIS KRX_FWDG_ORD_ORGNO (정정/취소 의무 동반)
    val idempotencyKey: String,               // UQ (의제 H-1)
    val rejectReason: String?,                // KIS reject 시 사유
    val requestedAt: Instant,
    val acknowledgedAt: Instant?,             // KIS ACK 응답 받은 시각
    val completedAt: Instant?,                // FILLED·REJECTED·CANCELLED 종료 시각
)

enum class OrderSide { BUY, SELL }

enum class OrderPriceType(val ordDvsn: String) {
    MARKET("01"),     // 시장가 — ORD_UNPR="0"
    LIMIT("00"),      // 지정가 — ORD_UNPR=limitPrice
}

enum class OrderStatus {
    REQUESTED,         // OrderRouter Kafka listen 진입
    ACK,               // KIS REST 응답 성공 (kisOrderNo 수신)
    FILLED,            // H0STCNI0 CNTG_YN=2 + qty 100% 도달
    PARTIALLY_FILLED,  // H0STCNI0 CNTG_YN=2 + qty 일부
    REJECTED,          // KIS REST 응답 실패 또는 H0STCNI0 RFUS_YN=Y
    CANCELLED,         // 사용자 [취소] 호출 → KIS order-rvsecncl 성공
    EXPIRED,           // (1차 미도입 — 후속 자동 취소 worker용)
}
```

### 5-2. OrderExecution (체결 분 — 의제 F-1, 분할 체결 1:N cover)

```kotlin
// domain/.../order/OrderExecution.kt
data class OrderExecution(
    val id: Long,
    val orderId: Long,                        // OrderIntent.id FK
    val userId: String,
    val kisOrderNo: String,                   // KIS ODNO
    val executedQuantity: Int,                // CNTG_QTY
    val executedPrice: BigDecimal,            // CNTG_UNPR
    val executedAt: Instant,                  // STCK_CNTG_HOUR + 오늘 date 합성
    val fee: BigDecimal?,                     // 수수료 (옵션 — KIS 별도 조회 의무, 1차 null 허용)
    val tax: BigDecimal?,                     // 매도세·증권거래세 (1차 null 허용)
    val createdAt: Instant,
)
```

### 5-3. OrderAuditEvent (감사 시계열 — 의제 F-1)

```kotlin
// domain/.../order/OrderAuditEvent.kt
data class OrderAuditEvent(
    val id: Long,
    val orderId: Long,
    val userId: String,
    val eventType: OrderAuditEventType,
    val payload: String,                      // JSON — KIS raw 응답·예외 등
    val createdAt: Instant,
)

enum class OrderAuditEventType {
    REQUESTED,              // OrderRouter Kafka listen 진입
    REAL_GATE_DENIED,       // 의제 A-1 3중 게이트 미충족
    IDEMPOTENCY_SKIPPED,    // Redis SETNX duplicate
    KIS_REQUEST,            // KIS POST 호출 직전
    KIS_ACK,                // KIS 응답 성공
    KIS_REJECT,             // KIS 응답 실패 (rt_cd != 0)
    KIS_EXCEPTION,          // KIS HTTP 5xx · timeout
    FILLED,                 // H0STCNI0 CNTG_YN=2 100%
    PARTIAL_FILL,           // H0STCNI0 CNTG_YN=2 일부
    USER_CANCEL_REQUEST,    // 사용자 [취소] REST 호출
    KIS_CANCEL_ACK,         // KIS order-rvsecncl 응답 성공
    KIS_CANCEL_REJECT,      // KIS order-rvsecncl 응답 실패
}
```

### 5-4. Port 인터페이스 (헥사고날)

```kotlin
// domain/.../order/OrderRequestRepository.kt
interface OrderRequestRepository {
    fun save(intent: OrderIntent): OrderIntent
    fun findById(id: Long): OrderIntent?
    fun findByUser(userId: String, filter: OrderQueryFilter, pageable: Pageable): Page<OrderIntent>
    fun findByIdempotencyKey(key: String): OrderIntent?
    fun findByKisOrderNo(kisOrderNo: String): OrderIntent?      // H0STCNI0 listen 시 lookup
    fun updateStatus(id: Long, status: OrderStatus, completedAt: Instant?, rejectReason: String?)
    fun updateKisRefs(id: Long, kisOrderNo: String, kisFwdgOrgno: String, acknowledgedAt: Instant)
}

// domain/.../order/OrderExecutionRepository.kt
interface OrderExecutionRepository {
    fun save(execution: OrderExecution): OrderExecution
    fun findByOrderId(orderId: Long): List<OrderExecution>
    fun sumExecutedQuantity(orderId: Long): Int                  // PARTIAL → FILLED 판정
}

// domain/.../order/OrderAuditEventRepository.kt
interface OrderAuditEventRepository {
    fun save(event: OrderAuditEvent): OrderAuditEvent
    fun findByOrderId(orderId: Long): List<OrderAuditEvent>
}

// domain/.../order/OrderRouterPort.kt — KIS REST 호출 추상 (의제 B-1)
interface OrderRouterPort {
    fun submit(intent: OrderIntent): KisOrderAck
    fun cancel(intent: OrderIntent, qtyAllYn: String): KisOrderAck
}

data class KisOrderAck(
    val kisOrderNo: String,
    val kisFwdgOrgno: String,
    val acknowledgedAt: Instant,
    val rawResponse: String,    // JSON — audit
)

// domain/.../order/OrderNotifier.kt — STOMP fanout port (의제 G-1)
interface OrderNotifier {
    fun notifyRequested(userId: String, intent: OrderIntent)
    fun notifyFilled(userId: String, intent: OrderIntent, execution: OrderExecution)
    fun notifyRejected(userId: String, intent: OrderIntent, reason: String)
    fun notifyCancelled(userId: String, intent: OrderIntent)
}

// domain/.../order/TradingModeGate.kt — 의제 A-1 3중 게이트
interface TradingModeGate {
    fun resolveMode(userId: String): TradingMode    // throws RealTradingNotAllowedException
}

// domain/.../order/PositionUpdatePort.kt — 체결 후 position 갱신 (ADR 0042·0067 정합)
interface PositionUpdatePort {
    fun applyExecution(execution: OrderExecution, intent: OrderIntent)
}
```

---

## 6. 채택 시 영속 매트릭스 (Flyway V20·V21·V22)

> **Flyway 번호 정합**: ADR 0067이 V16·V17·V18·V19 발행 (v3.5.0 마감). 본 ADR이 **V20·V21·V22** 사용 (3 테이블 신규).

### 6-1. `order_request` 테이블 (의제 F-1 신규 V20)

```sql
-- Flyway V20
CREATE TABLE order_request (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             VARCHAR(40) NOT NULL,
    signal_id           BIGINT NOT NULL,                        -- T-SIG cross-ref
    strategy_id         BIGINT NOT NULL,
    code                VARCHAR(10) NOT NULL,
    side                VARCHAR(8) NOT NULL,                    -- BUY · SELL
    price_type          VARCHAR(8) NOT NULL,                    -- MARKET · LIMIT
    quantity            INT NOT NULL,
    limit_price         NUMERIC(15,2),
    status              VARCHAR(20) NOT NULL,                   -- REQUESTED · ACK · FILLED · PARTIALLY_FILLED · REJECTED · CANCELLED · EXPIRED
    mode                VARCHAR(8) NOT NULL,                    -- MOCK · REAL
    kis_order_no        VARCHAR(20),                            -- KIS ODNO (ACK 후 LOCK)
    kis_fwdg_orgno      VARCHAR(20),                            -- KIS KRX_FWDG_ORD_ORGNO
    idempotency_key     VARCHAR(64) NOT NULL,
    reject_reason       VARCHAR(255),
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    acknowledged_at     TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,

    CONSTRAINT order_request_idem_uq UNIQUE (idempotency_key),
    CONSTRAINT order_request_kis_no_uq UNIQUE (kis_order_no),

    CHECK (side IN ('BUY','SELL')),
    CHECK (price_type IN ('MARKET','LIMIT')),
    CHECK (status IN ('REQUESTED','ACK','FILLED','PARTIALLY_FILLED','REJECTED','CANCELLED','EXPIRED')),
    CHECK (mode IN ('MOCK','REAL')),
    CHECK (quantity > 0),
    CHECK ((price_type = 'LIMIT' AND limit_price IS NOT NULL) OR (price_type = 'MARKET' AND limit_price IS NULL))
);

CREATE INDEX idx_order_request_user_requested ON order_request (user_id, requested_at DESC);
CREATE INDEX idx_order_request_signal ON order_request (signal_id);
CREATE INDEX idx_order_request_status_open ON order_request (user_id, status)
    WHERE status IN ('REQUESTED','ACK','PARTIALLY_FILLED');
```

### 6-2. `order_execution` 테이블 (의제 F-1 신규 V21, 분할 체결 1:N)

```sql
-- Flyway V21
CREATE TABLE order_execution (
    id                  BIGSERIAL PRIMARY KEY,
    order_id            BIGINT NOT NULL REFERENCES order_request(id),
    user_id             VARCHAR(40) NOT NULL,
    kis_order_no        VARCHAR(20) NOT NULL,
    executed_quantity   INT NOT NULL,
    executed_price      NUMERIC(15,2) NOT NULL,
    executed_at         TIMESTAMPTZ NOT NULL,
    fee                 NUMERIC(15,2),
    tax                 NUMERIC(15,2),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CHECK (executed_quantity > 0),
    CHECK (executed_price > 0)
);

CREATE INDEX idx_order_execution_order ON order_execution (order_id, executed_at);
CREATE INDEX idx_order_execution_user_time ON order_execution (user_id, executed_at DESC);
```

### 6-3. `order_audit_event` 테이블 (의제 F-1 신규 V22)

```sql
-- Flyway V22
CREATE TABLE order_audit_event (
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGINT NOT NULL REFERENCES order_request(id),
    user_id      VARCHAR(40) NOT NULL,
    event_type   VARCHAR(40) NOT NULL,
    payload      JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_order_audit_order ON order_audit_event (order_id, created_at);
```

### 6-4. `user_account.real_trading_consent_at` 컬럼 추가 (의제 A-1 신규 V23)

```sql
-- Flyway V23
ALTER TABLE user_account ADD COLUMN IF NOT EXISTS real_trading_consent_at TIMESTAMPTZ;
COMMENT ON COLUMN user_account.real_trading_consent_at IS 'ADR 0068 의제 A-1 — 실거래 사용자 명시 동의 시각. NULL 시 REAL 주문 BLOCKED.';
```

---

## 7. 채택 시 실행 엔진 매트릭스 (consumer 모듈 — ADR 0067 패턴 일관)

### 7-1. OrderRouterKafkaListener (consumer 모듈 신규)

- groupId `magicjar-consumer-order` · topic `order.intent.v1` listen
- payload = `OrderIntentEvent` (signalId·strategyId·userId·code·side·priceType·quantity·limitPrice·idempotencyKey·requestedAt)
- 흐름:
  1. **OrderIdempotencyGuard.acquire** (의제 H-1) — fail 시 `order_audit_event` INSERT(`IDEMPOTENCY_SKIPPED`) + return
  2. **TradingModeGate.resolveMode** (의제 A-1) — 3중 게이트 미충족 시 `order_request` INSERT(status=REJECTED) + `order_audit_event`(`REAL_GATE_DENIED`) + STOMP REJECTED + return
  3. **KillSwitch 재검증** (ADR 0024·0043) — ON 시 `order_request` INSERT(status=REJECTED) + STOMP REJECTED
  4. **order_request INSERT** (status=REQUESTED) → STOMP REQUESTED 통지
  5. **KisOrderRouter.submit** (의제 B-1 KisOrderAdapter) — KIS REST 호출
  6. **응답 성공** → `order_request.status=ACK` + `kis_order_no`·`kis_fwdg_orgno` UPDATE + `order_audit_event`(`KIS_ACK`)
  7. **응답 실패** → `order_request.status=REJECTED` + `reject_reason` UPDATE + STOMP REJECTED + `order_audit_event`(`KIS_REJECT`)

### 7-2. KisOrderHttpAdapter (consumer 모듈 신규)

- raw 출처 KDoc 의무:
  ```kotlin
  /**
   * raw 출처: https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions.py
   * line 10097~10213 (def order_cash)
   * tr_id: TTTC0012U (real BUY) · VTTC0012U (mock BUY) · TTTC0011U (real SELL) · VTTC0011U (mock SELL)
   * endpoint: POST /uapi/domestic-stock/v1/trading/order-cash
   */
  ```
- `TradingModeGate.resolveMode` 호출 → tr_id 선택
- `UserKisCredentialRepository.findByUserAndAccountType(userId, REAL|MOCK)` (ADR 0046 baseline)
- KisAuthAdapter.getToken (mode별 캐시) → Authorization header
- HTTP POST body 매트릭스 (CANO·ACNT_PRDT_CD·PDNO·ORD_DVSN·ORD_QTY·ORD_UNPR·EXCG_ID_DVSN_CD="KRX"·SLL_TYPE(매도)·CNDT_PRIC)
- 응답 파싱 — `rt_cd == "0"` 시 ACK / 그 외 REJECT
- `output.ODNO` → kis_order_no · `output.KRX_FWDG_ORD_ORGNO` → kis_fwdg_orgno

### 7-3. KisExecutionWsListener (consumer 모듈 신규 — 의제 D-1)

- KisWsBridge subscribe(`H0STCNI0` real / `H0STCNI9` mock) 사용자별 1 구독
- 메시지 수신:
  1. `CNTG_YN="1"` 접수 통보 — `order_audit_event` INSERT(`KIS_ACK` 또는 `KIS_REJECT`) (이미 §7-1 step 6에서 처리되었으나 비동기 통보)
  2. `CNTG_YN="2"` 체결 통보:
     - `OrderRequestRepository.findByKisOrderNo(ODNO)` lookup
     - `OrderExecution INSERT` (executed_qty=CNTG_QTY, executed_price=CNTG_UNPR, executed_at=STCK_CNTG_HOUR + today)
     - `OrderExecutionRepository.sumExecutedQuantity(orderId)` → orderIntent.quantity 비교
       - 동일 시 status=FILLED + completed_at + STOMP FILLED
       - 미달 시 status=PARTIALLY_FILLED + STOMP FILLED(partial)
     - `PositionUpdatePort.applyExecution(execution, intent)` 호출 (ADR 0042·0067 position 갱신)
     - `Kafka order.filled.v1 publish` (ADR 0044 NotificationDispatcher 진입)
  3. `RFUS_YN="Y"` 거부 통보 — `order_request.status=REJECTED` + STOMP REJECTED

### 7-4. OrderCancelService (api 모듈 — 의제 E-1)

- REST `POST /api/orders/{id}/cancel` 진입
- `OrderRequestRepository.findById(id)` + userId 격리 (ADR 0010)
- status ∈ {REQUESTED, ACK, PARTIALLY_FILLED} 만 cancel 가능 (FILLED·CANCELLED·REJECTED 제외 — 409)
- `KisOrderHttpAdapter.cancel(intent, qtyAllYn="Y")` 호출 (RVSE_CNCL_DVSN_CD="02")
- 응답 성공 → `order_request.status=CANCELLED` + `order_audit_event`(`KIS_CANCEL_ACK`) + STOMP CANCELLED
- 응답 실패 → `order_audit_event`(`KIS_CANCEL_REJECT`) + 사용자 토스트(409 + 사유)

### 7-5. KIS-first raw 인용 인벤토리 (헌법 §3 의무 산출물)

| 컴포넌트 | raw 출처 | line | tr_id |
|---|---|---|---|
| KisOrderHttpAdapter.submit (BUY) | `examples_user/domestic_stock/domestic_stock_functions.py` | L10097~L10213 | TTTC0012U / VTTC0012U |
| KisOrderHttpAdapter.submit (SELL) | 위와 동일 | 위와 동일 | TTTC0011U / VTTC0011U |
| KisOrderHttpAdapter.cancel | 위와 동일 (`def order_rvsecncl`) | L10771~L10884 | TTTC0013U / VTTC0013U |
| KisExecutionWsListener | `examples_user/domestic_stock/domestic_stock_functions_ws.py` (`def ccnl_notice`) | L389~L446 | H0STCNI0 / H0STCNI9 |

sub-agent 디스패치 §에서 코드 KDoc 인용 의무 명시 (헌법 §3 5단계).

### 7-6. 컴포넌트 매트릭스 요약

| 컴포넌트 | 모듈 | 책임 |
|---|---|---|
| OrderRouterKafkaListener | consumer | order.intent.v1 listen + 게이트 chain |
| OrderIdempotencyGuard | consumer | Redis SETNX 60s (의제 H-1) |
| TradingModeGate | domain (port) + consumer (adapter) | 3중 게이트 (의제 A-1) |
| KisOrderHttpAdapter | consumer | KIS REST (의제 B-1) |
| KisExecutionWsListener | consumer | KIS WS H0STCNI0 (의제 D-1) |
| OrderRequestPersister | consumer | order_request CRUD |
| OrderExecutionPersister | consumer | order_execution INSERT |
| OrderAuditEventPersister | consumer | order_audit_event INSERT |
| StompOrderNotifier | api | /topic/order.user.{userId} 4 event (의제 G-1) |
| OrderCancelService | api | REST cancel (의제 E-1) |
| OrderQueryService | api | REST list/single |
| RealTradingConsentController | api | SettingsModal 토글 (의제 A-1) |
| OrderFilledKafkaProducer | consumer | order.filled.v1 publish (ADR 0044 진입) |
| PositionUpdateAdapter | consumer | position 갱신 (ADR 0042·0067 정합) |

---

## 8. AI 호출 0 검증 의무

ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 정합. 본 ADR 범위 내 모든 컴포넌트 코드에 LLM provider import 0건. sub-agent 디스패치 시 architect 1회 grep 검증:

```bash
grep -r "import.*anthropic\|import.*openai\|import.*ai\.openai\|ChatClient\|Gemini" \
    consumer/src/main/kotlin/.../order \
    api/src/main/kotlin/.../order \
    domain/src/main/kotlin/.../order
# 결과: 0 lines 의무
```

신규 파일 모두 inline 주석 의무:
```kotlin
// AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
```

주문 의사결정 chain: `signal.id → risk.allowed → order.intent → KIS REST` — deterministic 100%.

---

## 9. 운영 영향

### 9-1. DB ROW 예상 (사용자 dogfooding 기준)

- **order_request**: 사용자당 ~50 주문/일 → 18K row/year (가벼움)
- **order_execution**: 분할 체결 평균 1.5배 → 75/일 → 27K row/year
- **order_audit_event**: 주문당 평균 5 event → 250/일 → 91K row/year
- **누적 ~140K row/year** — PostgreSQL 16 단일 instance 영향 0 (인덱스 매트릭스 §6 정합)

### 9-2. Kafka 토픽 매트릭스

| 토픽 | direction | retention | 영향 |
|---|---|---|---|
| `order.intent.v1` | listen (T-RISK publish, 본 ADR 신규 source 0 — 이미 main) | 7d | 0 (사전 자산) |
| `order.requested.v1` | publish (본 ADR 신규) | 30d | ADR 0044 NotificationDispatcher 진입 |
| `order.filled.v1` | publish (본 ADR 신규) | 30d | ADR 0044 + T-RECON 합류점 |
| `order.rejected.v1` | publish (본 ADR 신규) | 30d | ADR 0044 |
| `order.cancelled.v1` | publish (본 ADR 신규) | 30d | ADR 0044 |

신규 4 토픽. msg/s 예상 ~0.001 (50건/일/사용자 / 86400s) — Kafka 영향 0.

### 9-3. KIS API Rate Limit (ADR 0037)

- KIS Rate Limit = 20 req/s
- 본 ADR 주문 burst 예상:
  - 5m boundary 마감 시 시그널 burst → Risk ALLOWED → Order intent 동시 5건 가능
  - 5건 × ~200ms KIS REST latency = 1초 안에 5 req → Rate Limit 20 req/s 영향 0
- **margin 확보 필요 시점**: 단일 사용자 동시 동일 boundary 10+ 시그널 burst → 후속 backlog §47 (token bucket queue 도입)
- 본 ADR 1차 MVP에서는 직접 KIS REST 호출 (queue 0)

### 9-4. KIS WS 구독 한도 (ADR 0048 — 41건)

- 본 ADR H0STCNI0 추가 시 사용자별 1 구독 추가
- 현재 baseline (시세 + 호가) 사용자당 ~10건 → +1 = 11건 (margin 30건 OK)
- **margin 위험 시점**: 사용자가 watchlist 20+ + 호가 추가 시 — 후속 backlog §48 (priority eviction 정책)

### 9-5. Latency 예상

- signal.generated → risk evaluate (75ms, ADR 0067 §9) → order.intent publish (~10ms) → OrderRouterKafkaListener (~20ms) → KisOrderHttpAdapter (~200ms KIS REST) → ACK
- **시그널 → KIS ACK ~305ms** (acceptable)
- 체결 통보: KIS WS H0STCNI0 → KisExecutionWsListener → STOMP FILLED ~50ms (KIS 호가 체결 ~즉시)

### 9-6. 모의/실전 분기 (의제 A-1)

- dev/staging: `MAGICJAR_MODE=MOCK` default — KIS VPS endpoint(`https://openapivts.koreainvestment.com:29443`) + 모의 키
- prod: `MAGICJAR_MODE=REAL` + `MAGICJAR_ALLOW_REAL=true` + 사용자 동의 토글 — KIS prod endpoint(`https://openapi.koreainvestment.com:9443`) + 실 키
- 데이터 출처 분기 0 (사용자 메모리 정합) — 시세·캔들 등 정보 수집은 REAL 키 단일 그대로

---

## 10. Rollback 시나리오

### 10-1. 트리거 매트릭스

| 시나리오 | 인지 신호 | Rollback |
|---|---|---|
| 1. REAL 게이트 우회 결함 | `MAGICJAR_MODE=REAL` 미설정 환경에서 KIS prod 호출 로그 | `MAGICJAR_ALLOW_REAL=false` env 강제 + OrderRouterKafkaListener disable |
| 2. 중복 주문 발행 | order_request 동일 idempotency_key 2건 INSERT | OrderRouterKafkaListener disable + Redis SETNX TTL ↑ |
| 3. KIS API 5xx 누적 | KIS_EXCEPTION audit event 분당 5+ | OrderRouterKafkaListener disable + Kafka consumer group reset |
| 4. H0STCNI0 미수신 누적 | order_request status=ACK이지만 24h 후 FILLED 0 | D-2 polling fallback 도입 (backlog §44 활성화) |
| 5. 사용자 자본 손실 누적 | order.filled 누적 P&L 일별 -10% 도달 | T-RISK auto-PAUSE strategy + OrderRouterKafkaListener disable + 사용자 보고 |

### 10-2. Rollback 절차 (9 step)

1. 사용자 보고 (인지 신호 매트릭스 인용)
2. `MAGICJAR_ALLOW_REAL=false` 환경변수 강제 (REAL 주문 즉시 차단)
3. OrderRouterKafkaListener `@KafkaListener` disable (consumer pod 재시작 or `KafkaListenerEndpointRegistry.stop()`)
4. Kafka `order.intent.v1` topic 누적 메시지 보존 (replay 의무 시점)
5. order_request status=REQUESTED 누적 사용자 수동 cancel 또는 SQL UPDATE
6. 결함 원인 분석 + ADR 갱신 (재발 방지)
7. PR-fix 검증 + smoke
8. OrderRouterKafkaListener re-enable
9. CHANGELOG · backlog · 사용자 메모리 정합 갱신

---

## 11. 트랙 의존성 + sub-agent 디스패치 매트릭스 (채택 후)

### 11-1. 선행/후행 트랙

- **선행**: T-RISK (ADR 0067 Accepted, ALLOWED 게이트 통과 의무)
- **후행**: T-RECON (ADR 0041, 본 ADR 체결 영속 ↔ KIS 잔고 drift) · T-NOTIFY (ADR 0044, order.filled.v1 listen) · T-J (Trading Journal, 본 ADR audit trail 베이스) · T-PERF (성능 통계, 본 ADR order_execution 베이스)

### 11-2. sub-agent 디스패치 매트릭스 (Phase 1 → 2 → 3 직렬)

**Phase 1 — strategy-engine** (consumer + domain · Kafka 진입점·KIS 어댑터·체결 listener):
- 도메인 14건 (OrderIntent·OrderExecution·OrderAuditEvent·OrderSide·OrderPriceType·OrderStatus·OrderAuditEventType·KisOrderAck·OrderQueryFilter + 7 port — OrderRequestRepository·OrderExecutionRepository·OrderAuditEventRepository·OrderRouterPort·OrderNotifier·TradingModeGate·PositionUpdatePort)
- OrderRouterKafkaListener (groupId magicjar-consumer-order)
- OrderIdempotencyGuard (Redis SETNX 60s)
- TradingModeGateImpl (3중 게이트)
- KisOrderHttpAdapter (REST 매수/매도/정정·취소 + raw KDoc 인용)
- KisExecutionWsListener (H0STCNI0 listen)
- OrderFilledKafkaProducer (order.filled.v1)
- OrderRequestedKafkaProducer (order.requested.v1)
- OrderRejectedKafkaProducer (order.rejected.v1)
- OrderCancelledKafkaProducer (order.cancelled.v1)
- KafkaTopicConfig 4 topic 신규 (order.requested·filled·rejected·cancelled retention 30d)
- 단위 테스트 ~15건 (OrderRouterKafkaListenerTest, KisOrderHttpAdapterTest, OrderIdempotencyGuardTest, TradingModeGateTest, KisExecutionWsListenerTest)
- **AI 호출 0 검증**: 모든 신규 파일 inline 주석 + grep 검증

**Phase 2 — backend-core** (api · Flyway · JPA · REST · STOMP):
- Flyway V20 (order_request) · V21 (order_execution) · V22 (order_audit_event) · V23 (user_account.real_trading_consent_at ALTER)
- JPA 3쌍 (OrderRequestJpaEntity/Repository/Adapter + OrderExecutionJpaEntity/Repository/Adapter + OrderAuditEventJpaEntity/Repository/Adapter — 헌법 §6 abstract만)
- OrderQueryService (REST list + single)
- OrderCancelService (REST POST cancel)
- RealTradingConsentController (REST PUT consent)
- StompOrderNotifier (`/topic/order.user.{userId}` 4 event discriminator)
- REST 4 endpoint Swagger 4단 한국어 (controller/method/param/DTO)
- 단위 테스트 ~8건 (OrderQueryServiceTest, OrderCancelServiceTest, StompOrderNotifierTest)

**Phase 3 — frontend** (React + STOMP):
- `/orders` 페이지 (`OrdersPage.tsx`)
  - OrderFilterBar (status·side·code·date range)
  - OrderList (무한스크롤 테이블)
  - OrderCard (의도 + 체결 N건 + audit timeline)
- `/orders/:id` 상세 페이지 (단건)
- OrderStatusBadge 컴포넌트 (status 5단 색상)
- OrderCancelButton (status ∈ open 시만 활성)
- ExecutionTable (분할 체결 1:N 시각화)
- AuditEventTimeline (시계열)
- SettingsModal "실거래 모드 동의" 토글 (의제 A-1 + 경고 모달)
- REST client `order.ts` (4 endpoint) + `account.ts` (consent)
- useStompOrder hook (4 event discriminator 토스트 색상 — REQUESTED blue·FILLED green·REJECTED red·CANCELLED gray)
- useOrders·useOrder·useCancelOrder·useUpdateRealTradingConsent 4 hook
- AppHeader 메뉴 1 (주문 내역) 추가
- 라우팅 통합 (`/orders`·`/orders/:id`)
- typecheck + build GREEN

**Phase 4 — qa (선택)**:
- 모의 주문 dogfooding 1회 (MOCK 환경) — signal → risk → order → KIS VPS → H0STCNI9 → STOMP FILLED full chain
- Rate Limit margin 측정 (5 시그널 burst)
- 사용자 보고

### 11-3. 영역 분리 (충돌 매트릭스 add-only LOCK)

- Phase 1 — `consumer/.../order/**` · `domain/.../order/**` (신규 영역)
- Phase 2 — `api/.../order/**` · `db/migration/V20~V23` · `api/.../user/realtrading/**` (신규 영역)
- Phase 3 — `frontend/src/pages/orders/**` · `frontend/src/components/order/**` · `frontend/src/hooks/useStompOrder.ts` (신규 영역)

CHANGELOG sub-agent slot 3 anchor 사전 발행 (헌법 §4) — strategy-engine·backend-core·frontend.

---

## 12. 영향 ADR·문서 매트릭스

| 대상 | 갱신 |
|---|---|
| **본 ADR (0068)** | 신규 발행 (Status Proposed → 사용자 결정 후 Accepted) |
| `docs/architecture/master-architecture.md` §10 | ADR 0068 row 추가 (ADR 0030 SoT 정합) |
| `docs/architecture/master-architecture.md` §C.12 | "T-O (트랙 1 완료)" → "T-O ADR 0068 plan 발행" 정합 갱신 |
| `_workspace/arch/adr/README.md` §전략·신호·리스크 | 본 ADR row 추가 (8건 → 9건) + §11 후속 0069 시프트 |
| `docs/architecture/tracks-roadmap.md` §2 T-O row | "ADR 0068 plan 발행 (2026-05-12, 사용자 결정 대기)" 표기 추가 + 의존 트랙 매트릭스 갱신 |
| `_workspace/backlog.md` §43 | T-O 트랙 신규 등록 (본 ADR 인용) |
| `CHANGELOG.md` | architect plan row 1건 + sub-agent slot 3 anchor 사전 발행 (헌법 §4) |
| `docs/architecture/development-rules.md` | 변경 0 (룰 5건 정합 — AI 0건 의무 본 ADR 자체 §8 보강만) |
| `docs/architecture/policies/kis-first-raw-fetch.md` | 변경 0 (본 ADR §7-5 인용 인벤토리만 추가, policy 본문 영향 0) |
| `docs/architecture/policies/kis-credential-identity.md` | 변경 0 (본 ADR이 §2-1 baseline 사용 시점) — backlog §23 W3 T-O 진입 시 본격 정리 트리거 도달, 필요 시 별도 PR |

---

## 14. Phase 1 strategy-engine 정밀 명세서 (사용자 "확실히 오류 없이" 정합)

> **이 §의 목적**: §7·§11-2 outline 위에 sub-agent(strategy-engine)가 **자체 추론 0**으로 구현 가능한 정밀 명세 LOCK. 각 파일·시그니처·오류 분기 모두 architect 결정으로 박아둠. 사용자 자본 변동 발행 path이므로 **오류 risk 매트릭스 의무 (§14-4)**.

### 14-1. 파일 매트릭스 (consumer·domain 신규 영역, add-only)

**domain 모듈 (Spring·JPA 의존 0 — 순수 Kotlin)**:

| 파일 | 역할 | 시그니처 LOCK |
|---|---|---|
| `domain/order/OrderIntent.kt` | 주문 의도 (Kafka payload 도메인 모델) | data class — id·signalId·strategyId·userId·code·side(`OrderSide`)·priceType(`OrderPriceType`)·quantity·limitPrice?·idempotencyKey·requestedAt |
| `domain/order/OrderRequest.kt` | 영속 도메인 (의제 F-1 §6-1 정합) | data class — id·signalId·strategyId·userId·code·side·priceType·quantity·limitPrice?·status(`OrderStatus`)·mode(`TradingMode`)·kisOrderNo?·kisFwdgOrgno?·idempotencyKey·rejectReason?·requestedAt·acknowledgedAt?·completedAt? |
| `domain/order/OrderExecution.kt` | 분할 체결 1건 | data class — id·orderId·userId·kisOrderNo·executedQuantity·executedPrice·executedAt·fee?·tax? |
| `domain/order/OrderAuditEvent.kt` | audit row | data class — id·orderId·userId·eventType(`OrderAuditEventType`)·payloadJson·createdAt |
| `domain/order/OrderSide.kt` | enum | `BUY`·`SELL` |
| `domain/order/OrderPriceType.kt` | enum | `MARKET`·`LIMIT` |
| `domain/order/OrderStatus.kt` | enum | `REQUESTED`·`ACK`·`FILLED`·`PARTIALLY_FILLED`·`REJECTED`·`CANCELLED`·`EXPIRED` |
| `domain/order/OrderAuditEventType.kt` | enum | `REAL_GATE_DENIED`·`IDEMPOTENCY_SKIPPED`·`KILLSWITCH_DENIED`·`KIS_ACK`·`KIS_REJECT`·`KIS_EXCEPTION`·`FILL_RECEIVED`·`PARTIAL_FILL`·`FULL_FILL`·`KIS_CANCEL_ACK`·`KIS_CANCEL_REJECT`·`EXECUTION_TIMEOUT` |
| `domain/order/TradingMode.kt` | enum | `MOCK`·`REAL` |
| `domain/order/DenyReason.kt` | sealed class | `RealGateDenied(condition: String)`·`IdempotencyDuplicate`·`KillSwitchOn`·`KisRejected(rtCd, msg)` |
| `domain/order/port/OrderRequestRepository.kt` | 영속 port (헌법 §6 abstract만) | abstract fun save(req)·findById(id)·findByKisOrderNo(no)·findByIdempotencyKey(key)·updateStatus(id, status, reason?)·updateKisAck(id, no, orgno, at)·markCompleted(id, at, finalStatus)·findOpenByUserId(userId) — 모두 abstract |
| `domain/order/port/OrderExecutionRepository.kt` | 영속 port | abstract fun save(exec)·findByOrderId(orderId)·sumExecutedQuantityByOrder(orderId) — 모두 abstract |
| `domain/order/port/OrderAuditEventRepository.kt` | 영속 port | abstract fun save(event)·findByOrderId(orderId) — 모두 abstract |
| `domain/order/port/KisOrderPort.kt` | KIS REST 어댑터 port | `submit(intent: OrderIntent, mode: TradingMode): Either<DenyReason, KisOrderAck>`·`cancel(req: OrderRequest, mode: TradingMode): Either<DenyReason, Unit>` |
| `domain/order/port/KisOrderAck.kt` | KIS REST 응답 도메인 | data class — kisOrderNo·kisFwdgOrgno·rtCd·msg |
| `domain/order/port/TradingModeGate.kt` | 3중 게이트 port (의제 A-1) | `resolveMode(userId): Either<DenyReason.RealGateDenied, TradingMode>` |
| `domain/order/port/OrderIdempotencyGuard.kt` | Redis SETNX port (의제 H-1) | `acquire(key: String, ttlSec: Long = 60): Boolean` |
| `domain/order/port/OrderNotifier.kt` | STOMP port (의제 G-1) | `notifyRequested(req)·notifyFilled(req, exec, partial: Boolean)·notifyRejected(req, reason)·notifyCancelled(req)` — 4 fun |
| `domain/order/port/PositionUpdatePort.kt` | position 갱신 합류점 (ADR 0042·0067 정합) | `applyExecution(execution: OrderExecution, request: OrderRequest)` |

**consumer 모듈 (어댑터·실행 엔진)**:

| 파일 | 역할 | 핵심 흐름 |
|---|---|---|
| `consumer/order/OrderRouterKafkaListener.kt` | `@KafkaListener(topics=["order.intent.v1"], groupId="magicjar-consumer-order")` | §14-2 흐름 |
| `consumer/order/OrderIdempotencyGuardImpl.kt` | Redis SETNX 60s 어댑터 | `redisTemplate.opsForValue().setIfAbsent("idem:order:${key}", "1", Duration.ofSeconds(60))` |
| `consumer/order/TradingModeGateImpl.kt` | 3중 게이트 어댑터 | §14-3 검사 |
| `consumer/order/KisOrderHttpAdapter.kt` | KIS REST 어댑터 | §14-5 raw 인용 + tr_id 분기 |
| `consumer/order/KisExecutionWsListener.kt` | KIS WS H0STCNI0/H0STCNI9 listen | §14-6 26 컬럼 매핑 |
| `consumer/order/StompOrderNotifierImpl.kt` | api 모듈로 위임할 STOMP는 §15에 위치 (consumer는 publish 4 producer만) | — |
| `consumer/order/OrderRequestedKafkaProducer.kt` | `order.requested.v1` publish | — |
| `consumer/order/OrderFilledKafkaProducer.kt` | `order.filled.v1` publish (ADR 0044 NotificationDispatcher 진입) | — |
| `consumer/order/OrderRejectedKafkaProducer.kt` | `order.rejected.v1` publish | — |
| `consumer/order/OrderCancelledKafkaProducer.kt` | `order.cancelled.v1` publish | — |
| `consumer/order/PositionUpdateAdapter.kt` | ADR 0042 PositionRepository 합류 | `applyExecution()` 구현 |
| `consumer/config/OrderTopicConfig.kt` | 4 topic 등록 (retention 30d) | KafkaAdmin NewTopic 4건 |

### 14-2. OrderRouterKafkaListener 정밀 흐름 (12 step + 각 step 실패 분기)

```
1. payload deserialize → OrderIntent
   ├─ 실패 → log.error + audit X(order_request 0) + return (Kafka DLQ는 후속 backlog)
2. OrderIdempotencyGuard.acquire("order:${intent.idempotencyKey}", 60s)
   ├─ false → audit `IDEMPOTENCY_SKIPPED` (orderId=null, payload={intent.idempotencyKey}) + STOMP X + return
3. TradingModeGate.resolveMode(intent.userId)
   ├─ Left(RealGateDenied(cond)) → order_request INSERT(status=REJECTED, reject_reason=cond, mode=정해진 mode) + audit `REAL_GATE_DENIED` + STOMP REJECTED + Kafka order.rejected.v1 publish + return
   └─ Right(mode) → 진행
4. KillSwitch 재검증 (ADR 0024·0043)
   ├─ ON → order_request INSERT(status=REJECTED, reject_reason="KILLSWITCH_ON", mode) + audit `KILLSWITCH_DENIED` + STOMP REJECTED + Kafka order.rejected publish + return
5. order_request INSERT(status=REQUESTED, mode, idempotency_key=intent.idempotencyKey)
6. STOMP notifyRequested + Kafka order.requested.v1 publish (병렬)
7. KisOrderHttpAdapter.submit(intent, mode)
   ├─ KIS REST 5xx → catch → 1회 retry (500ms backoff)
   │   ├─ 2회 fail → audit `KIS_EXCEPTION` + order_request UPDATE(status=REJECTED, reject_reason="KIS_5XX") + STOMP REJECTED + Kafka order.rejected publish + return
   ├─ KIS REST 200 rt_cd != "0" → audit `KIS_REJECT` + order_request UPDATE(status=REJECTED, reject_reason="${rt_cd}:${msg}") + STOMP REJECTED + Kafka order.rejected publish + return
   └─ KIS REST 200 rt_cd == "0" → 진행
8. order_request UPDATE(status=ACK, kis_order_no=output.ODNO, kis_fwdg_orgno=output.KRX_FWDG_ORD_ORGNO, acknowledged_at=NOW())
9. audit `KIS_ACK` (payload={ODNO, KRX_FWDG_ORD_ORGNO})
10. (FILLED 통보는 별도 KisExecutionWsListener — 본 listener는 ACK 까지만)
11. Kafka commit (수동 ack — KafkaListenerContainerFactory.ackMode=MANUAL_IMMEDIATE)
12. Micrometer counter `magicjar_order_processed_total{result=ACK|REJECTED|SKIPPED}` 증가
```

**Kafka consumer 설정 LOCK** (자본 보호):
- `enable.auto.commit=false`
- `ack-mode=MANUAL_IMMEDIATE` (step 11 시점만 commit)
- `max.poll.records=1` (분당 burst 회피)
- `concurrency=1` per partition (멀티스레드 race 회피 — 자본 path)
- `isolation.level=read_committed`

### 14-3. TradingModeGateImpl 3중 게이트 정밀 (자본 보호 핵심)

```kotlin
override fun resolveMode(userId: String): Either<DenyReason.RealGateDenied, TradingMode> {
    val envMode = System.getenv("MAGICJAR_MODE") ?: "MOCK"
    val allowReal = System.getenv("MAGICJAR_ALLOW_REAL") == "true"

    // 조건 1: env mode가 REAL이 아니면 → MOCK 강제 (안전 default)
    if (envMode != "REAL") return Either.Right(TradingMode.MOCK)

    // 조건 2: ALLOW_REAL flag 필수
    if (!allowReal) return Either.Left(
        DenyReason.RealGateDenied("MAGICJAR_ALLOW_REAL=false")
    )

    // 조건 3: 사용자 명시 동의 필수 (의제 A-1)
    val user = userAccountRepository.findById(userId)
        ?: return Either.Left(DenyReason.RealGateDenied("USER_NOT_FOUND"))
    if (user.realTradingConsentAt == null) return Either.Left(
        DenyReason.RealGateDenied("CONSENT_NOT_GIVEN")
    )

    return Either.Right(TradingMode.REAL)
}
```

**검증 의무**: 단위 테스트 4 케이스 모두 GREEN (정상 REAL · MOCK fallback · ALLOW_REAL false · CONSENT_NOT_GIVEN).

### 14-4. 오류 risk 매트릭스 (사용자 자본 보호 LOCK)

| risk | 발생 시점 | 인지 | 대응 (자본 보호) |
|---|---|---|---|
| KIS REST 5xx 일시 장애 | KisOrderHttpAdapter.submit | HTTP status code | 1회 retry (500ms backoff) → fail 시 status=REJECTED + audit `KIS_EXCEPTION` |
| KIS REST 응답 invalid JSON | KisOrderHttpAdapter parse | JsonProcessingException | status=REJECTED + audit `KIS_INVALID_RESPONSE` + 사용자 토스트 |
| KIS REST rt_cd != "0" | submit 정상 응답 | rt_cd 필드 | status=REJECTED + reject_reason=rt_cd:msg + STOMP REJECTED |
| Kafka payload deserialize fail | OrderRouterKafkaListener step 1 | JsonProcessingException | log.error + audit X (order_request 미생성) + Kafka offset commit (poison pill 회피) |
| 중복 시그널 (idempotency_key 동일) | OrderIdempotencyGuard | Redis SETNX false | audit `IDEMPOTENCY_SKIPPED` + STOMP X (사용자 노이즈 회피) |
| Redis 장애 (SETNX 예외) | OrderIdempotencyGuard | RedisConnectionException | fallback: 진행 + audit `REDIS_DOWN_FALLBACK` (자본보다 가용성 우선 X — 거꾸로) → **결정: Redis 장애 시 즉시 REJECTED + 사용자 알림** (자본 보호 LOCK) |
| 3중 게이트 실패 | TradingModeGate | DenyReason.RealGateDenied | status=REJECTED + reject_reason=cond + STOMP REJECTED + audit `REAL_GATE_DENIED` |
| KillSwitch ON 토글 | OrderRouterKafkaListener step 4 | KillSwitchPort.isOn() | status=REJECTED + audit `KILLSWITCH_DENIED` + STOMP REJECTED |
| KIS WS H0STCNI0 미수신 (24h) | KisExecutionWsListener absent | order_request.status=ACK + 24h 경과 + executions=0 | audit `EXECUTION_TIMEOUT` + 사용자 알림 (T-O 1차는 자동 cancel X — 사용자 수동 only) — backlog §44 polling fallback 활성화 트리거 |
| 분할 체결 다중 수신 race | KisExecutionWsListener 동시 | H0STCNI0 2건 동시 도착 | order_execution INSERT가 N건 + sumExecutedQuantity 조회 후 status 결정 → 동일 메시지 멱등 (kis_execution_no UQ는 미사용 — KIS 일부 환경 미발급, sumExecutedQuantity 정합 OK) |
| executions 누적 합 > order.quantity | sumExecutedQuantity > intent.quantity | order_execution insert 후 비교 | log.error + audit `EXECUTION_OVERFLOW` + 사용자 알림 + position 갱신 skip |

### 14-5. KisOrderHttpAdapter raw 인용 정밀 (헌법 §3 LOCK)

```kotlin
package kr.spdaewoon.magicjar.consumer.order

/**
 * KIS REST 주식 현금 매수/매도 어댑터 (T-O ADR 0068 §14)
 *
 * raw 출처: https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions.py
 * line 10097~10213 (def order_cash)
 *
 * tr_id 매트릭스:
 * - TTTC0012U: 실전 주식 현금 매수
 * - VTTC0012U: 모의 주식 현금 매수
 * - TTTC0011U: 실전 주식 현금 매도
 * - VTTC0011U: 모의 주식 현금 매도
 *
 * endpoint: POST /uapi/domestic-stock/v1/trading/order-cash
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Component
class KisOrderHttpAdapter(...) : KisOrderPort {

    override fun submit(intent: OrderIntent, mode: TradingMode): Either<DenyReason, KisOrderAck> {
        val trId = resolveTrId(intent.side, mode)  // TTTC0012U / VTTC0012U / TTTC0011U / VTTC0011U
        val credential = userKisCredentialRepository.findByUserAndAccountType(
            userId = intent.userId,
            accountType = if (mode == TradingMode.REAL) "REAL" else "MOCK"
        ) ?: return Either.Left(DenyReason.RealGateDenied("CREDENTIAL_NOT_FOUND"))

        val token = kisAuthAdapter.getToken(credential, mode)
        val body = mapOf(
            "CANO" to credential.cano,
            "ACNT_PRDT_CD" to credential.acntPrdtCd,
            "PDNO" to intent.code,
            "ORD_DVSN" to if (intent.priceType == OrderPriceType.MARKET) "01" else "00",
            "ORD_QTY" to intent.quantity.toString(),
            "ORD_UNPR" to (intent.limitPrice?.toString() ?: "0"),
            "EXCG_ID_DVSN_CD" to "KRX",
            // SELL only — SLL_TYPE 매도구분 ("01" 일반매도, "02" 임의매매, "05" 대주)
            "SLL_TYPE" to if (intent.side == OrderSide.SELL) "01" else null,
            "CNDT_PRIC" to "0"
        ).filterValues { it != null }

        return try {
            val response = restClient.post()
                .uri(if (mode == TradingMode.REAL) PROD_URI else VPS_URI)
                .header("authorization", "Bearer $token")
                .header("appkey", credential.appKey)
                .header("appsecret", credential.appSecret)
                .header("tr_id", trId)
                .header("custtype", "P")
                .body(body)
                .retrieve()
                .body(KisOrderResponse::class.java)
            if (response?.rtCd == "0") {
                Either.Right(KisOrderAck(
                    kisOrderNo = response.output!!.odno,
                    kisFwdgOrgno = response.output.krxFwdgOrdOrgno,
                    rtCd = "0",
                    msg = response.msg1
                ))
            } else {
                Either.Left(DenyReason.KisRejected(response?.rtCd ?: "UNKNOWN", response?.msg1 ?: ""))
            }
        } catch (e: HttpServerErrorException) {
            // 5xx 1회 retry — OrderRouterKafkaListener step 7 분기
            throw e
        }
    }

    /**
     * 주문 정정·취소 어댑터
     * raw 출처: domestic_stock_functions.py L10771~L10884 (def order_rvsecncl)
     * tr_id: TTTC0013U (실전) · VTTC0013U (모의)
     * endpoint: POST /uapi/domestic-stock/v1/trading/order-rvsecncl
     * RVSE_CNCL_DVSN_CD: "01" 정정 · "02" 취소
     */
    override fun cancel(req: OrderRequest, mode: TradingMode): Either<DenyReason, Unit> { /* ... */ }
}
```

### 14-6. KisExecutionWsListener 26 컬럼 매핑 LOCK (헌법 §3)

raw 출처: `examples_user/domestic_stock/domestic_stock_functions_ws.py` L389~L446 (`def ccnl_notice`)

tr_id: **H0STCNI0** (실전 체결 통보) · **H0STCNI9** (모의 체결 통보)

26 컬럼 매핑 표 (KIS WS body, `^` delimiter):

| idx | KIS 필드 | magicJar 도메인 | 비고 |
|---|---|---|---|
| 0 | CUST_ID | userId 매핑 (사용자 ID, HTS 사용자 ID) | KIS subscribe 시 등록 |
| 1 | ACNT_NO | 계좌번호 | credential.cano 정합 검증 |
| 2 | ODER_NO | kis_order_no | order_request.kis_order_no lookup key |
| 3 | OODER_NO | 원주문번호 (정정·취소시) | cancel 흐름 cross-ref |
| 4 | SELN_BYOV_CLS | 매도매수구분 ("01"=매도, "02"=매수) | OrderSide 검증 |
| 5 | RCTF_CLS | 정정구분 | audit payload |
| 6 | ODER_KIND | 주문종류 (시장가/지정가) | OrderPriceType 검증 |
| 7 | ODER_COND | 주문조건 | audit payload |
| 8 | STCK_SHRN_ISCD | 종목코드 | OrderRequest.code 검증 |
| 9 | CNTG_QTY | executed_quantity | order_execution.executed_quantity |
| 10 | CNTG_UNPR | executed_price | order_execution.executed_price |
| 11 | STCK_CNTG_HOUR | 체결시간 HHMMSS | order_execution.executed_at (today + HHMMSS) |
| 12 | RFUS_YN | 거부여부 ("Y"=거부) | status=REJECTED 분기 |
| 13 | CNTG_YN | 체결구분 ("1"=주문/정정/취소/거부, "2"=체결) | 흐름 분기 핵심 |
| 14 | ACPT_YN | 접수여부 ("1"=주문접수, "2"=확인, "3"=취소) | audit |
| 15 | BRNC_NO | 지점번호 | audit |
| 16 | ODER_QTY | 주문수량 (cross-ref) | OrderRequest.quantity 검증 |
| 17 | ACNT_NAME | 계좌명 | audit |
| 18 | CNTG_ISNM | 체결종목명 | audit |
| 19 | CRDT_CLS | 신용구분 | audit |
| 20 | CRDT_LOAN_DATE | 신용대출일자 | audit |
| 21 | CNTG_ISNM40 | 체결종목명40 | audit |
| 22 | ODER_PRC | 주문가격 | audit |
| 23~25 | reserved | — | — |

**흐름 분기 LOCK**:
```
if (CNTG_YN == "1" && RFUS_YN == "Y") → 거부 통보 (이미 §14-2 step 7에서 처리되었으나 백업)
if (CNTG_YN == "1" && ACPT_YN == "1") → 주문 접수 통보 (audit `KIS_ACK` 백업, 중복 시 멱등)
if (CNTG_YN == "1" && ACPT_YN == "3") → 취소 접수 통보 (status=CANCELLED + audit `KIS_CANCEL_ACK`)
if (CNTG_YN == "2") → 체결 통보:
  - OrderRequestRepository.findByKisOrderNo(ODER_NO) → orderRequest
  - order_execution INSERT(executed_qty=CNTG_QTY, executed_price=CNTG_UNPR, executed_at=parseTime(STCK_CNTG_HOUR))
  - audit `FILL_RECEIVED`
  - sum = OrderExecutionRepository.sumExecutedQuantityByOrder(orderId)
  - if (sum < orderRequest.quantity) → status=PARTIALLY_FILLED + STOMP FILLED(partial=true) + audit `PARTIAL_FILL`
  - if (sum == orderRequest.quantity) → status=FILLED + completedAt=NOW() + STOMP FILLED + audit `FULL_FILL` + Kafka order.filled.v1 publish + PositionUpdatePort.applyExecution
  - if (sum > orderRequest.quantity) → log.error + audit `EXECUTION_OVERFLOW` (§14-4 risk 매트릭스)
```

### 14-7. 단위 테스트 LOCK (~15건)

| 테스트 | 케이스 |
|---|---|
| OrderRouterKafkaListenerTest | (a) intent ACK 흐름 정상 · (b) idempotency duplicate skip · (c) real gate denied · (d) killswitch denied · (e) KIS 5xx retry then fail · (f) KIS rt_cd != 0 reject |
| TradingModeGateImplTest | (a) MOCK fallback (env=MOCK) · (b) REAL 정상 · (c) ALLOW_REAL=false denied · (d) CONSENT_NOT_GIVEN denied |
| OrderIdempotencyGuardImplTest | (a) 첫 acquire true · (b) 중복 acquire false · (c) TTL 만료 후 재 acquire true |
| KisOrderHttpAdapterTest | (a) BUY MOCK tr_id=VTTC0012U · (b) SELL REAL tr_id=TTTC0011U · (c) MARKET ORD_DVSN=01 · (d) LIMIT ORD_DVSN=00 + ORD_UNPR · (e) rt_cd != 0 → Left(KisRejected) |
| KisExecutionWsListenerTest | (a) CNTG_YN=2 부분 체결 → PARTIALLY_FILLED · (b) 누적 합 == qty → FILLED · (c) RFUS_YN=Y → REJECTED · (d) CNTG_YN=1 ACPT_YN=3 → CANCELLED · (e) overflow → audit + skip |

**AI 호출 0 grep 검증**: §8 명시 grep 명령 sub-agent PR 본문에 evidence 첨부 의무.

---

## 15. Phase 2 backend-core 정밀 명세서 (Flyway DDL 전수 + REST 4 endpoint)

> **이 §의 목적**: §6·§7-4·§11-2 outline 위에 sub-agent(backend-core)가 V18 ADR 0042 충돌 패턴 재발 회피 + Flyway 4건 + JPA 3쌍 + REST 4건 + STOMP 4 event 정밀 명세 LOCK.

### 15-1. Flyway 매트릭스 (V20·V21·V22·V23 LOCK)

§6 기준 본문 그대로. 추가 LOCK 사항:
- **모든 신규 테이블에 `IF NOT EXISTS` 적용** (Flyway V18 ADR 0042 충돌 패턴 회피 — 사용자 메모리 정합)
- **기존 테이블 확장은 `ALTER ... ADD COLUMN IF NOT EXISTS`** (V23 user_account)
- `signal_id` FK는 **ON DELETE RESTRICT** (감사 정합 — signal 삭제 시 order 보존 의무)
- `order_id` FK는 **ON DELETE CASCADE** (order 삭제 시 execution·audit 정리)
- `kis_execution_no` UQ는 **미사용** (KIS 일부 환경 미발급, sumExecutedQuantity 정합 OK — §14-4 risk 매트릭스 정합)

DDL 본문은 §6-1·§6-2·§6-3·§6-4 그대로 LOCK (변경 0).

### 15-2. JPA 3쌍 + 1 ALTER (헌법 §6 default fun 함정 회피 — abstract만)

**JPA 매트릭스**:

| 파일 | 역할 |
|---|---|
| `api/order/persistence/OrderRequestJpaEntity.kt` | `@Entity @Table("order_request")` ID·signalId·strategyId·userId·code·side(@Enumerated)·priceType·quantity·limitPrice·status·mode·kisOrderNo·kisFwdgOrgno·idempotencyKey·rejectReason·requestedAt·acknowledgedAt·completedAt |
| `api/order/persistence/OrderRequestJpaRepository.kt` | `interface OrderRequestJpaRepository : JpaRepository<OrderRequestJpaEntity, Long>` — abstract fun만 (`findByKisOrderNo`, `findByIdempotencyKey`, `findByUserIdOrderByRequestedAtDesc(@Param ..., pageable)`, `@EntityGraph(attributePaths=["executions"]) findOpenByUserId`) |
| `api/order/persistence/OrderRequestRepositoryAdapter.kt` | `@Component class OrderRequestRepositoryAdapter(...) : OrderRequestRepository` — port 구현, mapping fun 위치 |
| `api/order/persistence/OrderExecutionJpaEntity.kt` | `@Entity @Table("order_execution")` |
| `api/order/persistence/OrderExecutionJpaRepository.kt` | abstract fun만 (`findByOrderIdOrderByExecutedAtAsc`, `@Query("SELECT COALESCE(SUM(e.executedQuantity), 0) FROM ... WHERE order_id=?")` sumExecutedQuantityByOrder) |
| `api/order/persistence/OrderExecutionRepositoryAdapter.kt` | port 구현 |
| `api/order/persistence/OrderAuditEventJpaEntity.kt` | `@Entity @Table("order_audit_event")` |
| `api/order/persistence/OrderAuditEventJpaRepository.kt` | abstract fun만 |
| `api/order/persistence/OrderAuditEventRepositoryAdapter.kt` | port 구현 |
| `api/user/persistence/UserAccountJpaEntity.kt` | **기존 entity 확장** — `realTradingConsentAt: Instant?` 추가 (V23 매핑) |

**헌법 §6 LOCK (ADR 0051 default fun 함정)**: 모든 `JpaRepository` 인터페이스의 fun은 abstract만. 기본값·실제 로직은 Adapter에 위치. lambda·default fun 0건.

### 15-3. REST 4 endpoint (Swagger 4단 한국어 의무)

```
GET    /api/orders                        # 목록 (필터 + 페이징)
GET    /api/orders/{id}                   # 단건 (request + executions + audit_events)
POST   /api/orders/{id}/cancel            # 사용자 수동 취소
PUT    /api/user-account/real-trading-consent  # 동의 토글
```

**파일 매트릭스**:

| 파일 | 역할 |
|---|---|
| `api/order/web/OrderQueryController.kt` | GET 2건 |
| `api/order/web/OrderCancelController.kt` | POST cancel |
| `api/order/web/RealTradingConsentController.kt` | PUT consent |
| `api/order/web/dto/OrderListItemDto.kt`·`OrderDetailDto.kt`·`OrderExecutionDto.kt`·`OrderAuditEventDto.kt`·`CancelOrderResponseDto.kt`·`ConsentToggleRequestDto.kt`·`ConsentToggleResponseDto.kt` | 7 DTO |
| `api/order/application/OrderQueryService.kt` | repo lookup + DTO mapping |
| `api/order/application/OrderCancelService.kt` | §7-4 흐름 |
| `api/order/application/RealTradingConsentService.kt` | userAccount update |

**Swagger 4단 (controller/method/param/DTO 한국어 + example)** — 사용자 메모리 `feedback_swagger_pattern.md` 정합:

```kotlin
@Tag(name = "주문 (T-O)", description = "자동매매 주문 의도·체결·취소 조회 API")
@RestController
@RequestMapping("/api/orders")
class OrderQueryController(...) {

    @Operation(
        summary = "주문 목록 조회",
        description = "사용자별 주문 의도 시계열. status·side·code·date range 필터 + 페이징."
    )
    @GetMapping
    fun list(
        @Parameter(description = "주문 상태 필터", example = "REQUESTED,ACK") @RequestParam(required = false) status: List<OrderStatus>?,
        @Parameter(description = "조회 시작 시점", example = "2026-05-13T00:00:00Z") @RequestParam(required = false) from: Instant?,
        @Parameter(description = "조회 종료 시점", example = "2026-05-13T23:59:59Z") @RequestParam(required = false) to: Instant?,
        @Parameter(description = "페이지", example = "0") @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "사이즈", example = "20") @RequestParam(defaultValue = "20") size: Int,
        @AuthenticationPrincipal user: UserPrincipal
    ): Page<OrderListItemDto> { ... }
}
```

**4 endpoint 검증 (Swagger UI 사용자 dogfooding)**: PR 본문에 Swagger 스크린샷 4 endpoint 캡처 의무.

### 15-4. StompOrderNotifier (api 모듈, 단일 토픽 + 4 event)

```kotlin
package kr.spdaewoon.magicjar.api.order.stomp

/**
 * Kafka order.* event → STOMP /topic/order.user.{userId} forward
 * (T-O ADR 0068 §15-4 + 의제 G-1 단일 토픽 결정 정합)
 *
 * AI 호출 0 — T-O 트랙 정책
 */
@Component
class StompOrderNotifier(private val messagingTemplate: SimpMessagingTemplate, private val objectMapper: ObjectMapper) {

    @KafkaListener(
        topics = ["order.requested.v1", "order.filled.v1", "order.rejected.v1", "order.cancelled.v1"],
        groupId = "magicjar-api-stomp-order"
    )
    fun onOrderEvent(record: ConsumerRecord<String, String>, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
        val eventType = when (topic) {
            "order.requested.v1" -> "REQUESTED"
            "order.filled.v1" -> "FILLED"
            "order.rejected.v1" -> "REJECTED"
            "order.cancelled.v1" -> "CANCELLED"
            else -> return  // 방어
        }
        val payload = objectMapper.readTree(record.value())
        val userId = payload.get("userId").asText()
        val message = OrderStompPayload(
            event = eventType,
            orderId = payload.get("orderId")?.asLong(),
            code = payload.get("code")?.asText(),
            side = payload.get("side")?.asText(),
            quantity = payload.get("quantity")?.asInt(),
            executedPrice = payload.get("executedPrice")?.asDouble(),
            partial = payload.get("partial")?.asBoolean() ?: false,
            reason = payload.get("rejectReason")?.asText(),
            ts = payload.get("ts")?.asText()
        )
        messagingTemplate.convertAndSend("/topic/order.user.$userId", message)
    }
}
```

### 15-5. 오류 risk 매트릭스 (backend-core 영역)

| risk | 대응 |
|---|---|
| Flyway V18 ADR 0042 충돌 패턴 재발 | 모든 신규 테이블 `IF NOT EXISTS` + 기존 테이블 확장 `ADD COLUMN IF NOT EXISTS` |
| signal_id FK CASCADE 결함 | DELETE RESTRICT 사용 (signal 삭제 시 order 보존 — 감사 정합) |
| order_execution UQ 결함 | kis_execution_no UQ 미사용 (KIS 일부 환경 미발급) — sumExecutedQuantity 정합으로 분할 체결 멱등 |
| REST GET 페이징 N+1 | `@EntityGraph(attributePaths=["executions"])` 적용 |
| Swagger 누락 (사용자 1차 문서) | 4 endpoint 모두 4단 어노테이션 + 한국어 + example 의무. PR Swagger 캡처 evidence |
| STOMP race (Kafka 멀티 토픽) | 단일 SimpMessagingTemplate + ConcurrentKafkaListenerContainerFactory concurrency=1 |
| consent toggle race (사용자 동시 클릭) | UserAccount `@Version` optimistic lock |
| AI 호출 grep 의무 누락 | PR 본문에 grep 0 lines evidence 의무 (§8 정합) |

### 15-6. 단위 테스트 LOCK (~8건)

| 테스트 | 케이스 |
|---|---|
| OrderQueryServiceTest | (a) 목록 페이징 정상 · (b) 필터 status · (c) userId 격리 (ADR 0010) |
| OrderCancelServiceTest | (a) status OPEN 시 cancel ACK · (b) status FILLED 시 409 · (c) userId 격리 |
| RealTradingConsentServiceTest | (a) toggle ON → real_trading_consent_at=NOW() · (b) toggle OFF → NULL |
| StompOrderNotifierTest | (a) 4 topic → 4 event discriminator |

---

## 16. Phase 3 frontend 정밀 명세서 (8 컴포넌트 + dogfooding 3 케이스)

> **이 §의 목적**: §11-2 Phase 3 outline 위에 sub-agent(frontend)가 React 19 + TS + STOMP 정밀 구현 + 사용자 검증 dogfooding 3 케이스 LOCK.

### 16-1. 파일 매트릭스 (frontend 영역)

| 파일 | 역할 |
|---|---|
| `frontend/src/pages/orders/OrdersPage.tsx` | `/orders` 목록 페이지 |
| `frontend/src/pages/orders/OrderDetailPage.tsx` | `/orders/:id` 상세 |
| `frontend/src/components/order/OrderCard.tsx` | 단건 카드 (status badge · side · code · qty · price · createdAt) |
| `frontend/src/components/order/OrderStatusBadge.tsx` | 색상 매트릭스 LOCK |
| `frontend/src/components/order/ExecutionTable.tsx` | 분할 체결 시계열 |
| `frontend/src/components/order/OrderAuditTimeline.tsx` | audit_event 시계열 |
| `frontend/src/components/order/CancelOrderButton.tsx` | 확인 모달 + POST |
| `frontend/src/components/settings/RealTradingConsentToggle.tsx` | SettingsModal 동의 토글 |
| `frontend/src/api/order.ts` | REST client 3 (list·single·cancel) |
| `frontend/src/api/account.ts` | REST client consent |
| `frontend/src/hooks/useOrders.ts` | `useQuery(['orders', filters], ...)` |
| `frontend/src/hooks/useOrder.ts` | `useQuery(['orders', id], ...)` |
| `frontend/src/hooks/useCancelOrder.ts` | `useMutation(...)` |
| `frontend/src/hooks/useUpdateRealTradingConsent.ts` | `useMutation(...)` |
| `frontend/src/hooks/useStompOrder.ts` | `/topic/order.user.{userId}` subscribe + 4 event 토스트 |
| `frontend/src/components/layout/AppHeader.tsx` | **수정** — "주문" 메뉴 추가 |
| `frontend/src/App.tsx` | **수정** — `/orders`·`/orders/:id` 라우팅 추가 |

### 16-2. OrderStatusBadge 색상 매트릭스 LOCK

| status | 색상 (Tailwind class) | 한국어 라벨 |
|---|---|---|
| REQUESTED | `bg-blue-100 text-blue-800` | 접수 |
| ACK | `bg-blue-200 text-blue-900` | KIS 접수 |
| PARTIALLY_FILLED | `bg-yellow-100 text-yellow-800` | 부분 체결 |
| FILLED | `bg-green-100 text-green-800` | 체결 완료 |
| REJECTED | `bg-red-100 text-red-800` | 거부 |
| CANCELLED | `bg-gray-100 text-gray-700` | 취소 |
| EXPIRED | `bg-gray-200 text-gray-800` | 만료 |

### 16-3. useStompOrder 4 event 토스트 매트릭스

```typescript
// useStompOrder.ts
useStompSubscribe(`/topic/order.user.${userId}`, (msg: OrderStompPayload) => {
  switch (msg.event) {
    case 'REQUESTED':
      toast.info(`주문 접수 — ${msg.code} ${sideLabel(msg.side)} ${msg.quantity}주`, { color: 'blue' });
      break;
    case 'FILLED':
      if (msg.partial) {
        toast.warning(`부분 체결 — ${msg.code} ${msg.executedQty}/${msg.quantity}주 @ ${msg.executedPrice}원`, { color: 'yellow' });
      } else {
        toast.success(`체결 완료 — ${msg.code} ${msg.quantity}주 @ ${msg.executedPrice}원`, { color: 'green' });
      }
      break;
    case 'REJECTED':
      toast.error(`주문 거절 — ${msg.code}: ${msg.reason}`, { color: 'red' });
      break;
    case 'CANCELLED':
      toast.info(`주문 취소 — ${msg.code} ${msg.quantity}주`, { color: 'gray' });
      break;
  }
  queryClient.invalidateQueries(['orders']);  // 목록 자동 새로고침
});
```

### 16-4. RealTradingConsentToggle UI 정밀 (사용자 자본 보호)

```tsx
// SettingsModal 내부
<section>
  <h3>실거래 모드</h3>
  <p className="text-sm text-gray-600">
    실거래 모드는 한국투자증권 실계좌 자본을 사용합니다.
    이 토글을 ON 하기 전, 환경 변수 MAGICJAR_MODE=REAL + MAGICJAR_ALLOW_REAL=true 둘 다 설정되어야 합니다.
    개발 단계는 OFF 유지 권장.
  </p>
  <Switch
    checked={!!consent.consentAt}
    onChange={(next) => {
      if (next) {
        if (!confirm('실거래 모드를 활성화합니다. 실제 자본이 사용됩니다. 동의하십니까?')) return;
        if (!confirm('재확인: 자본 손실 risk를 이해하고 동의합니다.')) return;
      }
      mutate({ consent: next });
    }}
  />
  {consent.consentAt && (
    <p className="text-xs text-red-600">동의 시각: {formatDate(consent.consentAt)}</p>
  )}
</section>
```

**이중 confirm 의무** — 자본 보호 UX LOCK.

### 16-5. dogfooding 3 케이스 (사용자 검증 LOCK)

**케이스 1 — 모의 주문 정상 흐름 (안전 검증)**:
```
사전 조건:
- MAGICJAR_MODE=MOCK 환경 부팅
- SettingsModal RealTradingConsentToggle = OFF (real_trading_consent_at IS NULL)
- KIS 모의계좌 credential row (user_kis_credential.account_type=MOCK) 등록 완료
- T-S1 strategy ACTIVE + T-SIG signal worker on

검증 절차:
1. /strategy/list → strategy status=ACTIVE 확인
2. 5m boundary 마감 대기 (또는 manual signal 발행)
3. T-RISK 모듈 LOG → ALLOWED 확인
4. /orders 페이지 진입 → REQUESTED 토스트 (파랑) 확인
5. ~5초 대기 → FILLED 토스트 (녹색) 확인 (KIS VPS H0STCNI9 통보)
6. /orders/{id} 상세 진입 → request + executions + audit timeline 모두 표시

검증 evidence (사용자 메모리 feedback_verification_strict.md 정합):
- DB: order_request 1 row (status=FILLED) + order_execution N row + order_audit_event ≥4 row
- Redis: idem:order:{key} TTL 만료 후 자동 정리
- STOMP: 4 event (REQUESTED·FILLED) 토스트 캡처
- 사용자 screen capture 첨부 (PR 본문)
```

**케이스 2 — 3중 게이트 BLOCKED (자본 보호 검증)**:
```
사전 조건:
- 케이스 1 정상 흐름 검증 완료
- env 변경: MAGICJAR_MODE=REAL (단 ALLOW_REAL=false 또는 RealTradingConsentToggle=OFF)

검증 절차:
1. SettingsModal RealTradingConsentToggle = OFF 유지
2. MAGICJAR_MODE=REAL + MAGICJAR_ALLOW_REAL=false 환경에서 consumer 재시작
3. signal manual 발행 → T-RISK ALLOWED → order.intent.v1 publish
4. OrderRouterKafkaListener 진입 → TradingModeGate.resolveMode → Left(RealGateDenied("MAGICJAR_ALLOW_REAL=false"))
5. /orders 페이지 → REJECTED 토스트 (빨강) "MAGICJAR_ALLOW_REAL=false" 표시

검증 evidence:
- DB: order_request 1 row (status=REJECTED, reject_reason="MAGICJAR_ALLOW_REAL=false")
- DB: order_audit_event 1 row (event_type=REAL_GATE_DENIED, payload={"condition":"MAGICJAR_ALLOW_REAL=false"})
- **KIS prod endpoint(`openapi.koreainvestment.com:9443`) 호출 0건** (자본 보호 LOCK — application 로그 grep 검증)
- STOMP REJECTED 토스트 캡처

추가 검증: env ALLOW_REAL=true로 변경 후 토글 OFF 상태 → 같은 path → reject_reason="CONSENT_NOT_GIVEN"
```

**케이스 3 — 분할 체결 정합 검증**:
```
사전 조건:
- 케이스 1 정상 흐름
- MOCK 환경

검증 절차:
1. 큰 수량 주문 (예: 100주) 모의 주문 발행
2. KIS VPS 분할 체결 (실제 KIS 모의 환경에서 발생 — 또는 KisExecutionWsListener mock으로 H0STCNI9 3건 강제 inject)
3. /orders/{id} 상세 진입 → ExecutionTable 3 row 표시 (50+30+20)
4. 누적 체결량 == 주문 수량 시점 → status=FILLED + 토스트 FILLED

검증 evidence:
- DB: order_request 1 row (status=FILLED, completed_at NOT NULL)
- DB: order_execution 3 row (executed_qty 합=100)
- 누적 합 표시 정합 (ExecutionTable footer sum)
- STOMP: PARTIALLY_FILLED 2건 (yellow) + FILLED 1건 (green) 시계열
```

### 16-6. 오류 risk 매트릭스 (UI 측)

| risk | 대응 |
|---|---|
| STOMP 메시지 도착 전 페이지 새로고침 | useStompOrder reconnect on mount + GET /api/orders 정합 (server state primary) |
| 분할 체결 토스트 spam | 동일 orderId 후속 update는 토스트 1회 + 후속은 silent invalidate |
| 동의 토글 OFF 상태에서 실계좌 모드 시도 | UI 단계에서 차단 (사용자 명시 동의 UX — 이중 confirm 의무 §16-4) |
| 취소 버튼 의도치 않은 클릭 | 확인 모달 의무 ("정말 취소하시겠습니까?" + 주문 정보 표시) |
| Tailwind 색상 매트릭스 불일치 | OrderStatusBadge 단일 컴포넌트 매트릭스 LOCK (§16-2) |
| STOMP race (Kafka 4 event 동시 도착) | event 시계열 ts 기준 정렬 (FILLED 후 늦은 REQUESTED 도착 시 무시) |
| 사용자 자본 손실 누적 시 화면 | /orders 페이지 헤더에 일별 P&L 표시 (T-RECON 후속 — 본 ADR 1차 backlog) |

### 16-7. 라우팅 + AppHeader 통합

```tsx
// App.tsx
<Route path="/orders" element={<OrdersPage />} />
<Route path="/orders/:id" element={<OrderDetailPage />} />

// AppHeader.tsx
<NavLink to="/orders">주문</NavLink>  // signals · risk · backtest 옆 신규 추가
```

### 16-8. typecheck + build 검증 의무

```bash
cd frontend
pnpm typecheck  # 0 errors
pnpm build      # GREEN
pnpm test       # OrderStatusBadge·useStompOrder 단위 테스트 (선택, 1차 MVP 면제 가능)
```

---

## 17. 통합 검증 매트릭스 (orchestrator 의무)

> **이 §의 목적**: 각 Phase 머지 후 orchestrator(상위 세션)가 dogfooding 검증 + 다음 Phase 진입 게이트 LOCK. 사용자 "확실히 오류 없이" 정합.

| Phase | 검증 항목 | 게이트 |
|---|---|---|
| Phase 1 (strategy-engine) | `:domain:check`·`:consumer:check` GREEN | 다음 Phase 진입 의무 조건 |
|  | 단위 테스트 ~15건 모두 GREEN | 동 |
|  | AI 호출 0 grep 검증 (§8 명령) — 0 lines | 동 |
|  | KIS-first raw 인용 inline KDoc 의무 (KisOrderHttpAdapter·KisExecutionWsListener) | PR 본문 evidence |
|  | Kafka 4 topic config 등록 (`order.requested·filled·rejected·cancelled.v1` retention 30d) | 동 |
| Phase 2 (backend-core) | `:api:check` GREEN | 동 |
|  | Flyway V20·V21·V22·V23 적용 (`flyway_schema_history` 4 row 신규) | DB 검증 |
|  | REST 4 endpoint smoke (curl GET/POST/PUT — 200 응답) | curl evidence |
|  | Kafka 4 listener 등록 (`magicjar-api-stomp-order` consumer group) | Kafka admin evidence |
|  | Swagger UI 4 endpoint 4단 한국어 + example 확인 | 스크린샷 |
|  | JPA Repository default fun 0건 grep (헌법 §6) | grep evidence |
| Phase 3 (frontend) | `pnpm typecheck && pnpm build` GREEN | 동 |
|  | dogfooding 케이스 1 (모의 주문 정상) 사용자 검증 1회 | 사용자 screen capture |
|  | dogfooding 케이스 2 (3중 게이트 BLOCKED) 자본 보호 검증 1회 | KIS prod 호출 0건 log evidence |
|  | dogfooding 케이스 3 (분할 체결 정합) 1회 | DB·UI 정합 |
| 통합 (post-Phase 3) | 사용자 dogfooding 전수 (3 케이스) | 사용자 ack |
|  | audit_event 정합 (event_type 매트릭스 §14-1 전수) | DB query |
|  | STOMP 4 event 시계열 (REQUESTED → FILLED → CANCELLED 시나리오) | UI capture |
|  | 자본 보호 LOCK 검증 (RealGateDenied → KIS prod 호출 0건) | log grep |

### 17-1. orchestrator 디스패치 매트릭스 (Phase 1 → 2 → 3 직렬 LOCK)

자본 위험 트랙이므로 **병렬 디스패치 금지**. Phase 1 머지 → 게이트 검증 → Phase 2 디스패치 → 게이트 검증 → Phase 3 → 사용자 dogfooding → 마감.

| Phase | 디스패치 sub-agent | 사전 게이트 | 사후 검증 |
|---|---|---|---|
| 1 | strategy-engine | T-RISK ADR 0067 Accepted (사전 자산 §1-2) | §17 Phase 1 게이트 5항 |
| 2 | backend-core | Phase 1 머지 + 게이트 GREEN | §17 Phase 2 게이트 6항 |
| 3 | frontend | Phase 2 머지 + 게이트 GREEN | §17 Phase 3 게이트 4항 |
| 통합 | 사용자 + orchestrator | Phase 3 머지 | §17 통합 4항 |

### 17-2. Rollback 통합 (§10 매트릭스 정합)

각 Phase 머지 후 결함 발견 시 §10 매트릭스 9 step 적용. Phase 단계 후행 트랙 진입 BLOCK 의무.

---

## 18. 변경 이력

| 날짜 | 변경 | 사유 | 작성자 |
|---|---|---|---|
| 2026-05-12 | 본 ADR 신규 발행 (Status Proposed) | T-O W3 네 번째 트랙 진입 + 결정 의제 8건 사용자 검토 위임 + KIS-first raw 인용 인벤토리 4건 (TTTC0012U·TTTC0011U·TTTC0013U·H0STCNI0) | architect |
| 2026-05-13 | Status Proposed → Accepted (사용자 A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1 모두 채택) | PR #371 머지 — 결정 ack | architect |
| 2026-05-13 | §14·§15·§16·§17 정밀 명세서 신규 추가 (Phase 1·2·3 sub-agent 디스패치용 + 오류 risk 매트릭스 3건 + dogfooding 3 케이스 + orchestrator 통합 검증 매트릭스 + §13 → §18 변경 이력 shift) | 사용자 "확실히 오류 없이 잘 낼 수 있게 설계하라" 명시 정합. 본 § 추가로 sub-agent 자체 추론 0 + KIS-first raw inline KDoc 의무 + 자본 보호 LOCK 의무 (3중 게이트 + KIS prod 호출 0건 검증) | architect |
