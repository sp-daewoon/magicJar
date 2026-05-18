# spec — 사용자 수동 주문 발행 UI + endpoint 신규 트랙 (T-MAN)

- 발행: 2026-05-18 architect
- 사용자 의제 (최고 관리자, 2026-05-18):
  > "주문을 하는 화면이 없어 --;; 잔액조회도"
  > "C가 권위적이긴 하지만 실제로 자동매매 하는거 결국엔 호가, 가격, 수량, 포지션을 넘겨서 주문을 성사 할테니 확장형으로 별도 페이지 있어도될거같은데?"
  > "하려는 이유는 가상계좌 잔고 등 통합테스트 하려면 이게 있어야해"
- 사용자 결정: 자동매매 본체 비전 유지 + **수동 주문 UI 확장형 별도 페이지** 신설(옵션 A)
- 동기: 가상계좌(MOCK) 잔고 통합테스트 — MOCK 주문 발행 → KIS API 호출 → 잔고 변화 dogfooding
- 트랙 ID: **T-MAN** (Manual Order Issuance — W3 보강 트랙)
- 발행 PR: `agent/architect/t-man-manual-order-issue-spec` (운영 코드 0 touch · 문서·spec only)
- 신설 ADR: **0082 — Manual Order Issuance Track (T-MAN)**
- 인접 ADR: **0068**(T-O 본 트랙 — Order Router/Adapter 재사용) · **0067**(T-RISK — Guard chain 6단 우회 정책) · **0024**(KillSwitch 재검증) · **0044**(Notification) · **0046**(Credential REAL/MOCK) · **0048**(WS 41 slot) · **0081**(T-ACCT — 잔고 통합테스트 cross-ref)
- 사용자 메모리: `project_system_vision`(룰 본체 + 사용자=최고 관리자) · `feedback_kis_key_realonly_for_data`(정보 수집 실전 키 / 주문 액션만 MOCK·REAL 분기) · `feedback_v3_skepticism_no_regression`(fitness gap 검증) · `feedback_no_mock_data`(mock 합성 0) · `feedback_release_policy_patch_first`(v3.6.x patch — v3.7=W4 LOCK) · `feedback_spec_plan_scope`(신규 트랙 → spec/plan + ADR 의무)
- 헌법 §3 LOCK: KIS-first raw fetch 의무 — 본 spec §3 KIS endpoint 매트릭스(ADR 0068 §14-5 베이스라인 재사용 — 자체 추론 0)

---

## 1. 결함 진단 (사용자 dogfooding evidence)

### 1-1. 사용자 명시 dogfooding (2026-05-18)

> "주문을 하는 화면이 없어 --;; 잔액조회도"
> "하려는 이유는 가상계좌 잔고 등 통합테스트 하려면 이게 있어야해"
> — 사용자 (최고 관리자) 2026-05-18 KST

### 1-2. architect 진단 매트릭스

| 컴포넌트 | 현황 | 사용자 노출 |
|---|---|---|
| `OrderRouterKafkaListener.kt` (consumer) | **존재** — ADR 0068 §14-2 12 step LOCK. `signal.generated.v1` → `order.intent.v1` 자동 path | ❌ 자동 path only — 수동 진입점 0건 |
| `KisOrderHttpAdapter.kt` (consumer) | **존재** — KIS REST 매수/매도(TTTC0012U·VTTC0012U·TTTC0011U·VTTC0011U) + 정정/취소(TTTC0013U·VTTC0013U). raw 출처 LOCK | ✅ 재사용 가능 (KisOrderPort.submit/cancel) |
| `OrderIntentKafkaProducer.kt` (consumer/risk) | **존재** — T-RISK ALLOWED 분기 publish only | ❌ 수동 entry 부재 |
| `TradingModeGateImpl.kt` (consumer) | **존재** — 3중 게이트 (env MAGICJAR_MODE · env MAGICJAR_ALLOW_REAL · `user_account.real_trading_consent_at`) | ✅ 재사용 (수동 주문도 동일 3중 게이트) |
| `OrderRequest.kt`·`OrderIntent.kt` (domain) | **존재** — `signalId: Long`·`strategyId: Long` **NOT NULL** | ⚠ 수동 주문 진입 시 NULL 의제(§4-1 결정) |
| `OrderQueryController.kt` (api) | **존재** — `GET /api/orders` 조회 only | ✅ 재사용 (조회) |
| `OrderCancelController.kt` (api) | **존재** — `POST /api/orders/{id}/cancel` 사용자 수동 취소 | ✅ 재사용 |
| `OrdersPage.tsx` (frontend) | **존재** — read-only 목록 + 필터 + STOMP 토스트. **신규 주문 발행 button 0건** | ⚠ 부분 — 수동 주문 entry button 추가 의제 |
| `OrderDetailPage.tsx` (frontend) | **존재** — 단건 + Cancel 버튼 | ✅ 재사용 |
| `InstrumentSearchInput.tsx` (frontend) | **존재** — 종목 검색 컴포넌트 | ✅ 재사용 |
| `SettingsModal.tsx` (frontend) | **존재** — `real_trading_consent_at` 토글 UI | ✅ 재사용 (수동 주문 게이트 표시) |
| **POST `/api/orders/manual` endpoint** | **❌ 부재** | — |
| **`/orders/new` 페이지** | **❌ 부재** | — |
| **ManualOrderForm + OrderPreviewModal 컴포넌트** | **❌ 부재** | — |

### 1-3. fitness gap (`feedback_v3_skepticism_no_regression` lens)

#### 1-3-1. 자동매매에 필요한가?

**핵심 질문**: 룰 기반 자동매매 본체에 수동 주문 UI는 본질적으로 필수가 아니다. 그러나 다음 3 사유로 **W3 보강 트랙으로 필수**:

- (a) **가상계좌(MOCK) 통합테스트 의제 (사용자 명시)** — 신호 발행을 인위적 trigger하지 않고도 dogfooding 가능. T-O Phase 4 케이스 6·7·8 재현 시 signal seed SQL 수동 INSERT 부담 해소(§4.1 Step 4 흡수).
- (b) **자동매매 path 외부의 사용자 통제** — magicJar 비전(`project_system_vision.md`): "사용자=최고 관리자/설계자". 룰이 신호를 못 잡는 종목·시점에 사용자가 직접 주문할 권한이 매매 시스템 최고 관리자에게 부재하면 비전 위반.
- (c) **T-ACCT 통합 — 잔고 변화 evidence path** — 사용자 의제 직후 "잔액조회도" 언급. T-ACCT(ADR 0081 Proposed) endpoint가 30s Redis cache TTL이지만, 수동 주문으로 즉시 잔고 변화 실증 가능.

**v1/v2 잔재 아닌가?** — 잔재 아님. v3 신규(현재 사용자 노출 0건). v1/v2 시점 수동 주문 UI 코드 0건.

#### 1-3-2. 비전 정합 (`project_system_vision.md`)

- 룰 기반 quant trading **본체**는 유지 — `OrderRouterKafkaListener` 12 step·`signal.generated.v1` → `order.intent.v1` chain **0 touch**
- 수동 주문은 **본체 path 외부의 확장형 별도 path** — 옵션 A 사용자 결정 정합
- 사용자 = 최고 관리자 → 수동 주문 권한 부여 + 3중 게이트 동일 적용 (자본 보호 동일)
- AI 옵션 보조 → 수동 주문 path는 LLM 호출 0(룰 5 + ADR 0023 §2-4)
- MCP 서버(ADR 0027) → 외부 MCP client가 수동 주문 endpoint 호출 가능한 baseline(미래)

#### 1-3-3. 안전 정합 (CLAUDE.md 운영 함정 4)

- 수동 주문도 자동 주문과 **동일 3중 게이트** 의무 (env MAGICJAR_MODE × env MAGICJAR_ALLOW_REAL × `user_account.real_trading_consent_at`)
- dev/staging 자동 MOCK(env default)
- UI 단 추가 안전 layer: **이중 confirm**(미리보기 + 확인) + **60초 cooldown**(동일 종목 연속 주문 차단) + **mode badge**(MOCK/REAL 명시)

---

## 2. T-MAN 트랙 범위 (architect 결정)

### 2-1. 핵심 결정 의제

#### A. 동기 vs 비동기 path (architect 권장 = **A-1 Kafka 비동기 path 동일**)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **A-1 Kafka 비동기 path (자동과 동일)** ⭐ | `POST /api/orders/manual` → `OrderIntent` 생성 → `order.intent.v1` publish → `OrderRouterKafkaListener` 동일 12 step | 자동·수동 path 단일 LOCK · OrderRouter 검증된 흐름 재사용 · 3중 게이트·KillSwitch·멱등성·체결 추적·STOMP·audit 모두 자동 흡수 | 동기 응답 부재 — endpoint는 ACK 즉시 반환(`{ status: REQUESTED, orderId: pending }`) + WS 토스트로 후속 확인 |
| A-2 동기 path (Kafka 우회) | `POST /api/orders/manual` → 동기 KisOrderHttpAdapter.submit 호출 → 즉시 KIS REST + 즉시 응답 | API client 즉시 결과 받음 | OrderRouter 12 step 일부 우회(3중 게이트 + KillSwitch만 수동 호출) → 자본 path 분기 발생 · audit·멱등성·STOMP 일관성 깨짐 |
| A-3 dual (옵션 토글) | request body `async: true/false` | 사용자 옵션 | 분기 매트릭스 두 배 · 자본 path 두 개 · 검증 부담 ↑ |

**architect 권장 = A-1**. 사유:
1. **자본 path 단일 LOCK** — 자동·수동 모두 `OrderRouterKafkaListener` 12 step 단일. 3중 게이트·KillSwitch·멱등성·KIS REST submit·체결 추적·STOMP·audit 모두 동일.
2. **검증 부담 최소** — 기존 dogfooding 케이스 5·6·7·8 evidence 자동 흡수.
3. **응답 UX 정합** — Kafka publish 직후 endpoint는 `{ orderId, status: REQUESTED }` 반환. frontend는 OrdersPage로 redirect + STOMP `/topic/order.user.{userId}` 구독으로 ACK/REJECTED 후속 표시.
4. **A-2 폐기** — 자본 path 분기 발생 시 audit·멱등성·KillSwitch 검증 누락 risk. magicJar 비전 위배(자본 path 단일).
5. **A-3 폐기** — 옵션 분기 만들면 검증 매트릭스 두 배, 사용자 의제 단순.

#### B. signalId·strategyId 처리 (OrderIntent NOT NULL)

현재 `OrderIntent.signalId: Long`·`strategyId: Long` 모두 NOT NULL. 수동 주문은 signal·strategy 부재.

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **B-1 sentinel value (`signalId = 0L`·`strategyId = 0L`)** ⭐ | 수동 주문 sentinel = 0L. `OrderRouterKafkaListener.deserialize` 분기 + `manual.user.<userId>.<epoch>` idempotency key | OrderIntent·OrderRequest 도메인 변경 0 · 자동 path 영향 0 · UI 표시는 controller에서 `signalId==0L` 시 "수동 주문" 한국어 label | 0L FK 부재 검증 의무 (signal 테이블에 id=0 row 없음 LOCK · strategy 동일) · DB FK 제약 의제(§4-3) |
| B-2 nullable 컬럼 + 도메인 변경 | `OrderIntent.signalId: Long?`·`OrderRequest.signalId: Long?` + Flyway V31(`ALTER COLUMN signal_id DROP NOT NULL`) | 의미적 명확 | OrderIntent·OrderRequest 도메인 변경 + 6+ 호출 사이트 수정 + Flyway migration · 자동 path 영향 매트릭스 부담 ↑ |
| B-3 signal seed auto-INSERT (manual orderlistener 진입 시 minimal signal row INSERT) | 수동 주문 시 manual signal row 자동 채번 | FK 정합 깨짐 0건 | dogfooding signal seed가 영구 잔재 · signal 테이블 오염 · audit 잔재 |

**architect 권장 = B-1 sentinel 0L**. 사유:
1. **도메인 변경 최소** — `OrderIntent.signalId: Long` 그대로. 자동 path 영향 0.
2. **OrderRequest 영향 0** — `signal_id` 컬럼 그대로 (FK 제약은 §4-3 결정).
3. **manual.user 멱등성 key** — `idempotencyKey = "manual:${userId}:${epochSec}"` 형식 → 동일 사용자 1초 내 중복 주문 차단(`OrderIdempotencyGuard` Redis SETNX 60s 재사용).
4. **B-2 폐기** — Flyway V31 + 6+ 호출 사이트 수정은 spec 범위 과다 + 자동 path risk.
5. **B-3 폐기** — signal 테이블 오염은 audit 신뢰성 훼손.

**검증 의무**: §4-3에서 DB FK 제약 매트릭스 확인. 현재 `order_request` 테이블이 `signal_id`·`strategy_id`에 FK 보유 시 sentinel 0 INSERT 불가 — Flyway V31 micro `ALTER TABLE order_request DROP CONSTRAINT fk_order_request_signal · fk_order_request_strategy` 의제 발생.

#### C. 수동 주문 path가 T-RISK Guard chain을 통과해야 하는가?

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| C-1 T-RISK 우회 (수동 주문은 사용자 명시 액션 → 사용자 책임) | OrderIntent publish 시 `riskVerdict="MANUAL_BYPASS"` flag · OrderRouter는 KillSwitch만 재검증, T-RISK Guard 6단 skip | 사용자 = 최고 관리자 비전 정합 · UX 직관 (사용자가 명시 주문했는데 차단되면 혼란) · OrderRouter는 이미 T-RISK가 ALLOWED 검증 후만 진입한다고 가정 | 룰 안전망 사용자 통제 외부 (POSITION_LIMIT 룰 무력 → MAGICJAR_MODE=REAL 시 자본 risk) |
| **C-2 T-RISK Guard chain 동일 적용 (수동 주문도 룰 안전망 통과)** ⭐ | `POST /api/orders/manual` → `signal.generated.v1` minimal payload publish → T-RISK consumer가 risk_status 평가 → ALLOWED 시만 `order.intent.v1` publish | 룰 안전망 일관 · POSITION_LIMIT(보유 한도) + DAILY_LOSS_LIMIT + KILL_SWITCH_ON 모두 적용 · 사용자도 룰 위반 차단(자본 보호) | signal 테이블 sentinel row INSERT 부담 (manual signal source 분기 의제) · T-RISK consumer 진입 path 단일 가정 위배(현재 sandbox·screener도 진입 0) |
| C-3 dual (사용자 선택) | request body `bypassRisk: true/false` | 사용자 결정권 | 분기 매트릭스 부담 + 사용자 의제 단순 |

**architect 권장 = C-1 T-RISK 우회 + KillSwitch만 재검증**. 사유:
1. **사용자 = 최고 관리자 비전 정합** — `project_system_vision.md` 명시. 사용자가 명시 주문했는데 POSITION_LIMIT 룰로 차단되면 비전 위배.
2. **UX 직관** — 자동 신호 발생 안 한 종목을 수동으로 주문하는 의제이므로 룰 안전망 외부 액션이 자연.
3. **KillSwitch는 동일 적용** — 최고 위험 안전망(`kill_switch_state.enabled=true`)은 항상 차단. T-RISK Guard 6단 중 KILL_SWITCH_ON만 재검증.
4. **3중 게이트 + 60s idempotency + UI 이중 confirm + 60초 cooldown** — 4 layer 안전망으로 룰 안전망 결락 보상.
5. **C-2 폐기** — 사용자 비전 위배 + signal 테이블 오염.
6. **C-3 폐기** — 분기 매트릭스 부담.

**구현 매트릭스**:
- `OrderIntent` payload에 `source: "MANUAL" | "SIGNAL"` 필드 add-only (default "SIGNAL")
- `OrderRouterKafkaListener` step 4 KillSwitch 재검증은 기존대로 (source 무관)
- T-RISK Guard chain 우회 — 이미 OrderRouter는 T-RISK ALLOWED 가정으로 진입하므로 manual은 OrderIntent publish 직접 발사(T-RISK consumer 우회)
- frontend: 수동 주문 페이지에 **"룰 안전망 우회 — 사용자 책임"** warning + 3중 게이트 + KillSwitch 상태 LOCK 표시

#### D. position 검증 (매도 시 보유 수량 검증)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **D-1 1차 MVP 미도입 (KIS가 자체 검증)** ⭐ | 매도 수량 > 보유 시 KIS REST가 `rt_cd != "0"` 반환 → OrderRouter REJECTED path 흡수 | KIS 단일 출처 정합 · DB position 부정확 risk 회피 · MVP 단순 | 사용자 UX는 KIS REJECT 메시지 표시 (한국어 미번역) |
| D-2 frontend 사전 검증 (T-ACCT API 의존) | UI 매도 form에서 `GET /api/account/positions` 호출 → 보유 수량 > 매도 수량 검증 → 400 차단 | UX 명확 (사용자 즉시 인지) | T-ACCT Phase 1·2 머지 의존 + Redis cache 30s lag risk(부정확) |
| D-3 backend 사전 검증 (T-ACCT KisBalanceQueryPort 의존) | `POST /api/orders/manual` 진입 시 KIS REST 동기 호출(매도만) → 보유 검증 후 publish | 정확도 ↑ | KIS API 호출 빈도 ↑ (ADR 0037 Rate Limit 부담) + 동기 lag 발생 |

**architect 권장 = D-1 (1차 MVP) + D-2 (T-ACCT 머지 후 후속)**.

사유:
1. **KIS 단일 출처 정합** — `feedback_kis_first_policy.md`. KIS가 자체 보유 검증 → 1차 cycle은 그대로 흡수.
2. **T-ACCT 후속** — T-ACCT Phase 4 (DashboardPage·BalanceCard 머지) 후 D-2 frontend 사전 검증 추가 (backlog §66 신규).
3. **D-3 폐기** — Rate Limit 부담 + 동기 lag.

#### E. 주문 가격 표시 (호가·현재가)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **E-1 1차 MVP 표시 0 (사용자가 KIS HTS에서 가격 확인 후 입력)** ⭐ | UI는 종목 검색 + 수량 + MARKET/LIMIT + 가격 input만 (호가·현재가 0건 표시) | MVP 단순 · 기존 컴포넌트 재사용 · 외부 데이터 종속 0 | UX는 KIS HTS와 병행 의제 |
| E-2 호가 stream WS 구독 (T-S1 baseline reuse) | `/orders/new` 진입 시 KIS WS H0STASP0 호가 구독 + 현재가 표시 | UX 풍부 | WS slot 1 추가 (ADR 0048 41 slot 한도 영향) + KIS H0STCNT0 시세 stream 추가 의제 |
| E-3 현재가 REST 1회 호출 (KIS `inquire-price`) | UI 진입 시 1회 REST 호출 + manual refresh button | 중간 UX · WS slot 영향 0 | KIS API 호출 빈도 ↑ |

**architect 권장 = E-1 (1차 MVP) + E-3 (후속)**.

사유:
1. **MVP 단순** — 사용자 의제 핵심은 가상계좌 dogfooding이지 거래 UX 풍부가 아님.
2. **WS slot 영향 회피** — E-2는 ADR 0048 한도 1 추가. 1차 MVP 비범위.
3. **E-3 후속** — backlog §66 신규 (현재가 표시 + KIS `inquire-price` REST 1회 호출).

#### F. ADR 신설 vs ADR 0068 §확장

| 옵션 | 설명 |
|---|---|
| F-1 ADR 0068 §17(또는 §19) 본문에 T-MAN 확장 § 추가 | T-O 트랙 본문 확장 |
| **F-2 신규 ADR 0082 — Manual Order Issuance Track** ⭐ | T-MAN 독립 ADR + ADR 0068 cross-ref |

**architect 권장 = F-2 (신규 ADR 0082)**.

사유:
1. **트랙 ID 신규** — T-MAN은 W3 보강 트랙 (T-ACCT와 동일 패턴 — ADR 0081 신규 발행). tracks-roadmap §2에 T-MAN row 추가.
2. **ADR 0068 본 트랙 마감 evidence(2026-05-18)** — 0068 본문 확장은 마감 evidence 훼손 risk.
3. **사용자 메모리 `feedback_spec_plan_scope` 정합** — 신규 트랙 = spec/plan + 신규 ADR 의무.
4. **ADR 0082 = T-MAN 단독** + ADR 0068 cross-ref (재사용 path 명시).

### 2-2. 권장안 종합 표

| 의제 | 권장안 | 결정 |
|---|---|---|
| A. 동기 vs 비동기 | **A-1 Kafka 비동기 (자동 path 동일)** | LOCK |
| B. signalId 처리 | **B-1 sentinel 0L + `source="MANUAL"` 필드 add-only** | LOCK |
| C. T-RISK Guard 우회 | **C-1 T-RISK 우회 + KillSwitch만 재검증** | LOCK |
| D. position 검증 | **D-1 1차 MVP KIS 흡수 + D-2 후속 backlog** | LOCK + 후속 backlog §66 |
| E. 호가·현재가 표시 | **E-1 1차 MVP 0건 + E-3 후속 backlog** | LOCK + 후속 backlog §66 |
| F. ADR 신설 | **F-2 ADR 0082 신규** | LOCK |

---

## 3. 명세 LOCK (구현 매트릭스)

### 3-1. backend endpoint 매트릭스 (api 모듈)

```
POST /api/orders/manual
Content-Type: application/json
Authorization: (kept session — 현재 user identity는 onboarding userId)

Request body:
{
  "userId": "<8자리 KIS 식별자>",     // path param 아닌 body field — frontend 명시 전송
  "code": "005930",                    // 종목코드 6자리 (frontend InstrumentSearchInput에서 선택)
  "side": "BUY" | "SELL",
  "priceType": "MARKET" | "LIMIT",
  "quantity": 1,                       // 양수 LOCK (Bean Validation @Min(1))
  "limitPrice": "70500"                // LIMIT 시 not null (String — BigDecimal 정밀도) / MARKET 시 null
}

Response 202 Accepted:
{
  "orderId": null,                     // OrderIntent publish 시점이라 아직 채번 0 (OrderRouter step 5에서 INSERT)
  "status": "ACCEPTED",                // endpoint 응답 한정 — OrderRequest.status 아님 (혼선 회피)
  "idempotencyKey": "manual:74683223:1716073800",
  "message": "수동 주문이 접수되었습니다. /orders 페이지에서 상태를 확인하세요."
}

Response 400 Bad Request: validation 실패 (quantity ≤ 0 / limitPrice 형식 / code 6자리 위반)
Response 403 Forbidden: REAL_GATE_DENIED / KILLSWITCH_ON 즉시 차단 (UI fast feedback)
Response 409 Conflict: idempotency duplicate (1초 내 동일 사용자 동일 종목 중복)
Response 503 Service Unavailable: Kafka publish 실패 (재시도 권장)
```

**Swagger 4단 (사용자 메모리 `feedback_swagger_pattern` 정합)**:
1. Controller: `@Tag(name="Orders", description="사용자 주문 관리")`
2. Method: `@Operation(summary="사용자 수동 주문 발행", description="...")`
3. Parameter: `@Parameter(description="...", example="...")`
4. DTO: `@Schema(description="...", example="...")` (각 필드)

### 3-2. backend 컴포넌트 매트릭스

```
api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/order/
├─ ManualOrderController.kt              [신규] POST /api/orders/manual
└─ (기존 OrderQueryController·OrderCancelController·RealTradingConsentController 0 touch)

api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/order/
└─ ManualOrderService.kt                 [신규]
   - validate(request)                   — Bean Validation + DB FK 체크(userId 존재)
   - resolveMode(userId)                 — TradingModeGate.resolveMode 위임(api → consumer 직접 의존 불가 → api 측 fast precheck만)
   - precheckKillSwitch(userId)          — KillSwitchQueryPort 호출 (api 측 ObjectProvider graceful)
   - publish(intent)                     — ManualOrderIntentKafkaProducer 위임

api/src/main/kotlin/kr/spdaewoon/magicjar/api/messaging/order/
└─ ManualOrderIntentKafkaProducer.kt     [신규] — order.intent.v1 publish (consumer/risk/OrderIntentKafkaProducer 패턴 동일 + source="MANUAL")

api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/order/dto/
├─ ManualOrderRequest.kt                 [신규] @Schema 매트릭스 (Bean Validation 의무)
└─ ManualOrderResponse.kt                [신규] @Schema
```

**3중 게이트 fast precheck (API 측 UX 가속용)**:
- `POST /api/orders/manual` 진입 시 ManualOrderService가 `TradingModeGate.resolveMode(userId)` 결과 호출 시도
- **권위는 consumer OrderRouterKafkaListener step 3** (Kafka 흡수 후 재검증) — API 측 precheck는 UX 가속만(fail-fast)
- API 측 precheck에서 REAL_GATE_DENIED 검출 시 즉시 403 반환 + STOMP·audit 0 (consumer 진입 전이므로 자본 path 미발동)
- API 측 precheck PASS 후 → consumer가 다시 검증(권위 가정) → consumer는 OrderRouter 12 step 동일

### 3-3. consumer 측 변경 (최소)

```
consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/order/
└─ OrderRouterKafkaListener.kt   [수정 add-only]
   - deserialize() 본문에 source 필드 add — default "SIGNAL"
     val source = payload["source"]?.toString() ?: "SIGNAL"  // (add-only)
   - source="MANUAL" 시 signalId=0L·strategyId=0L sentinel 흡수
   - 12 step 본체 흐름 0 touch (step 3 게이트 / step 4 KillSwitch / step 5 INSERT / step 7 KIS submit / step 8 ACK / step 11 commit 모두 동일)
   - audit_event payload에 source="MANUAL" 또는 "SIGNAL" 추가(audit trail 명시)
```

**중요**: consumer는 controller·service 0 touch. Kafka payload 1 필드 add-only만.

### 3-4. domain 측 변경 (0건)

`OrderIntent` 도메인은 변경 0 — `source`는 Kafka payload field로만 (도메인 영속에는 audit_event JSON에 보존).

### 3-5. Flyway migration 매트릭스

```
V31__order_request_signal_strategy_fk_relax.sql   [신규]
-- 수동 주문 sentinel 0L 흡수 — FK NULL 허용 OR drop
ALTER TABLE order_request DROP CONSTRAINT IF EXISTS fk_order_request_signal;
ALTER TABLE order_request DROP CONSTRAINT IF EXISTS fk_order_request_strategy;
-- (FK 자체 제거 — sentinel 0L INSERT 가능 + audit_event.payloadJson.source="MANUAL" 마커가 추적 책임 흡수)
-- 자동 path 영향 0 (FK 없어도 signal·strategy 테이블 row는 그대로 존재 + 멤버십 검증은 application layer 책임)
```

**검증 의무**: 현재 `order_request` 테이블에 FK 제약 존재 여부 backend-core가 Flyway V20~V23 본문 확인 후 결정. **부재 시 V31 발행 불요**.

대안: FK가 존재한다면 Flyway V31 발행 (위 SQL). FK 부재면 V31 없이 진행.

### 3-6. frontend 매트릭스

```
frontend/src/pages/orders/
├─ OrdersPage.tsx                  [수정 add-only]
│  - 상단 헤더에 "신규 주문" 버튼 추가 (NavLink to "/orders/new")
│  - mode badge 옆에 배치
└─ NewOrderPage.tsx                [신규] /orders/new
   - 1단: 종목 검색 (InstrumentSearchInput 재사용) + 선택 종목 표시
   - 2단: 매수/매도 토글 (BUY/SELL — 시각 색상 명확)
   - 3단: 가격 유형 (MARKET/LIMIT)
   - 4단: 수량 input (number, min=1)
   - 5단: LIMIT 시 가격 input (BigDecimal 정밀도 — string)
   - 6단: 3중 게이트 상태 표시 (mode badge + ALLOW_REAL + consent 상태) + KillSwitch 상태 LOCK
   - 7단: 미리보기 → OrderPreviewModal (이중 confirm)
   - 8단: 발행 → POST /api/orders/manual → /orders 자동 navigate + 토스트

frontend/src/components/order/
└─ OrderPreviewModal.tsx            [신규]
   - 종목명·코드·side(BUY=빨강·SELL=파랑)·priceType·quantity·총액(추정) 명시
   - "룰 안전망 우회 — 사용자 책임" warning (REAL 모드 시 강조)
   - 60초 cooldown 카운터 (동일 종목 직전 주문 시 표시)
   - [취소] [발행] 두 버튼 (발행은 disable until checkbox 확인)

frontend/src/api/order.ts            [수정 add-only]
└─ orderApi.manualIssue(request: ManualOrderRequest): Promise<ManualOrderResponse>

frontend/src/api/hooks/
└─ useManualOrderMutation.ts        [신규]
   - TanStack Query useMutation
   - onSuccess: invalidate useOrders + navigate('/orders') + toast "수동 주문 접수됨"
   - onError 403 REAL_GATE_DENIED: toast "실거래 모드 미허용 — 설정에서 동의 토글 확인"
   - onError 403 KILLSWITCH_ON: toast "킬스위치 ON — 모든 주문 차단"
   - onError 409 IDEMPOTENCY: toast "1초 내 중복 — 잠시 후 재시도"

frontend/src/api/order.ts            [수정 add-only]
└─ type ManualOrderSource = 'MANUAL' | 'SIGNAL'   — DTO 명시 (audit 표시 용)

frontend/src/routes/                  [수정 add-only]
└─ /orders/new → NewOrderPage  (RouterProvider config 1줄 add)
```

### 3-7. UI 안전 layer 매트릭스

| Layer | 검증 항목 | 위반 시 |
|---|---|---|
| 1 | 종목코드 6자리 LOCK | input 형식 검증 |
| 2 | 수량 >= 1 | submit disable |
| 3 | LIMIT 시 가격 > 0 | submit disable |
| 4 | 3중 게이트 상태 표시 (mode·ALLOW_REAL·consent) | REAL 시 동의 미체결이면 SettingsModal 안내 |
| 5 | KillSwitch 상태 표시 | ON 시 발행 disable + 안내 |
| 6 | 미리보기 → OrderPreviewModal 이중 confirm | checkbox 미체크 시 [발행] disable |
| 7 | 60초 cooldown (동일 사용자 + 동일 종목) | UI 카운터 표시 + 발행 차단 |
| 8 | REAL 모드 시 강조 — "이 주문은 실 자본 변동을 발생시킵니다" | confirm 텍스트 확대·붉은색 |

### 3-8. STOMP·토스트 매트릭스 (재사용)

수동 주문도 OrderRouter 12 step 동일이므로 기존 STOMP `/topic/order.user.{userId}` 4 event 자동 흡수:
- REQUESTED (Kafka order.requested.v1)
- ACK (audit KIS_ACK)
- FILLED (Kafka order.filled.v1 — KisExecutionWsListener)
- REJECTED (Kafka order.rejected.v1)
- CANCELLED (Kafka order.cancelled.v1)

frontend는 `useStompOrder` hook이 이미 구독 — 추가 작업 0.

### 3-9. KIS-first raw 인용 (헌법 §3 — ADR 0068 §14-5 베이스라인 재사용)

본 spec은 KIS endpoint 신규 호출 0건 (`KisOrderHttpAdapter`가 권위). raw 인용은 ADR 0068 §14-5 인용 cross-ref만:

- **raw 출처**: `examples_llm/domestic_stock/order_cash/order_cash.py` (def order_cash)
- **TR_IDs**: TTTC0012U(real BUY) · TTTC0011U(real SELL) · VTTC0012U(demo BUY) · VTTC0011U(demo SELL)
- **endpoint**: `POST /uapi/domestic-stock/v1/trading/order-cash`
- **request body**: CANO · ACNT_PRDT_CD · PDNO · ORD_DVSN · ORD_QTY · ORD_UNPR · EXCG_ID_DVSN_CD · SLL_TYPE · CNDT_PRIC
- **response output**: KRX_FWDG_ORD_ORGNO · ODNO · ORD_TMD

검증 의무: WebFetch 2026-05-18 시점 확인 — `def order_cash(env_dv, ord_dv, cano, acnt_prdt_cd, pdno, ord_dvsn, ord_qty, ord_unpr, excg_id_dvsn_cd, sll_type="", cndt_pric="")` 시그니처 정합 LOCK.

---

## 4. dogfooding 통합 매트릭스

### 4-1. T-O Phase 4 통합 (spec `2026-05-18-to-main-track-entry-plan.md` §4)

기존 케이스 6·7·8은 signal seed SQL 수동 INSERT 의무(plan §4.1 Step 4). T-MAN 합류 후 이 부담 해소:

| 기존 케이스 | T-MAN 합류 후 |
|---|---|
| **케이스 6**: 시그널 발행 (Step 4 SQL seed) → MOCK 주문 + 체결 통보 풀체인 | **케이스 6 강화**: UI `/orders/new` → 수동 발행 → MOCK 주문 + 체결 통보 동일 풀체인 (signal seed SQL 부담 0) |
| **케이스 7**: OrdersPage 조회 | **케이스 7 강화**: 수동 주문이 목록에 표시되며 source="MANUAL" badge 노출 |
| **케이스 8**: OrdersPage 취소 | **케이스 8 동일** — Cancel button 동일 path (`POST /api/orders/{id}/cancel`) |

### 4-2. T-MAN 신규 dogfooding 케이스

#### 케이스 9: 수동 MOCK 주문 발행 (가상계좌 통합테스트 — 사용자 의제 핵심)

**전제**: T-MAN Phase 1·2 머지 + env MAGICJAR_MODE=MOCK + MOCK credential 등록

**Step 1**: 브라우저 `/orders` → [신규 주문] 클릭 → `/orders/new` 진입
- 3중 게이트 표시: mode=MOCK · ALLOW_REAL=false · consent=null → "MOCK 거래 모드"
- KillSwitch 상태: OFF (정상)

**Step 2**: InstrumentSearchInput에서 005930 검색 + 선택 → BUY + MARKET + 수량 1 입력

**Step 3**: [미리보기] 클릭 → OrderPreviewModal:
- 종목: 삼성전자(005930) BUY MARKET qty=1
- 추정총액: (KIS 호출 없이) "시장가 — 체결 후 확정"
- "룰 안전망 우회 — 사용자 책임" warning
- checkbox 확인 → [발행] 클릭

**Step 4**: POST `/api/orders/manual` → 202 Accepted + redirect `/orders`

**Step 5**: OrdersPage에서 새 주문 카드 표시 (status=REQUESTED 또는 ACK 갱신 — STOMP 토스트)

**Step 6**: KIS MOCK ACK 수신 → status=ACK + ODNO 채번

**Step 7**: (옵션) H0STCNI9 모의 체결 통보 → status=FILLED → 토스트

**Step 8**: T-ACCT 머지 후 → DashboardPage에서 잔고 변화 evidence(예수금 감소·보유 종목 추가)

**evidence 의무** (사용자 메모리 `feedback_verification_strict`):
- 브라우저 screenshot (NewOrderPage·OrderPreviewModal·OrdersPage)
- backend log (ManualOrderController·ManualOrderIntentKafkaProducer·OrderRouterKafkaListener 12 step)
- DB `order_request` row (source 흔적 — audit_event.payloadJson.source="MANUAL")
- DB `order_audit_event` row (KIS_REQUEST + KIS_ACK)
- STOMP DevTools (REQUESTED·ACK event payload)

**기대**: UI → backend → Kafka → consumer → KIS → DB → STOMP 풀체인 GREEN. **signal seed SQL 부담 0**.

**복구**: MOCK 주문 + 자본 영향 0. 단 케이스 9 누적 잔재 DB row는 케이스 6·7·8 cleanup SQL과 동일 처리.

#### 케이스 10: 수동 주문 3중 게이트 차단 (안전 검증)

**전제**: env MAGICJAR_MODE=REAL + ALLOW_REAL=false (또는 동의 토글 미체결)

**Step 1**: `/orders/new` 진입 → 3중 게이트 상태 표시 "REAL 거래 모드 - 허용 안 됨"
**Step 2**: [발행] 버튼 disabled + 안내 "설정에서 ALLOW_REAL 활성화 의무 또는 SettingsModal에서 동의 토글 의무"
**Step 3**: 사용자가 force 시도 → backend API precheck 403 REAL_GATE_DENIED → toast 차단

**기대**: 자본 변동 0 + 3중 게이트 첫 layer fail-fast.

### 4-3. 위험 매트릭스

| Risk | 영향 | 완화 |
|---|---|---|
| sentinel 0L FK 위반 | DB INSERT 실패 | §3-5 Flyway V31 micro `DROP CONSTRAINT` (FK 존재 시) · backend-core가 V20·V21·V22·V23 본문 확인 의무 |
| signal·strategy 테이블에 id=0 row 존재 | sentinel 충돌 | DB query 검증 — backend-core가 dispatch 시 `SELECT * FROM signal WHERE id=0` 검증 + 0 row LOCK |
| T-RISK 우회로 POSITION_LIMIT 룰 무력 | REAL 시 자본 risk | UI 명시 warning + 60s cooldown + 이중 confirm + KillSwitch 적용 4 layer 안전망으로 보상 |
| 60초 cooldown 우회 (다른 브라우저·다른 디바이스) | 동일 사용자 burst 주문 | OrderIdempotencyGuard Redis SETNX 60s가 backend 측 권위 — UI 우회해도 backend 차단 |
| 사용자 의제 외 unauthenticated 호출 | API 노출 risk | 현재 magicJar는 onboarding userId가 user identity baseline — 추가 인증 layer는 후속(T-AUTH backlog) |
| KIS REST 호출 burst (수동 주문 사용자가 자동매매와 동시) | Rate Limit 부담 | ADR 0037 baseline 20 req/s reuse · OrderRouter step 7 5xx 1회 retry baseline |
| frontend cooldown UI cache miss (새로고침 시 리셋) | 사용자 우회 시도 | 60s LocalStorage 또는 sessionStorage 저장 + page refresh 시 복원 (Phase 4 frontend 책임) |

---

## 5. sub-agent dispatch plan

### 5-1. Phase 매트릭스

| Phase | sub-agent | 영역 | 라인 수 estimate | 우선순위 | 병렬 가능 여부 |
|---|---|---|---|---|---|
| **1** | backend-core | api/controller/order + api/service/order + api/messaging/order + api/dto + Flyway V31(옵션) | ~250 LOC | HIGH | Phase 2와 병렬 가능 (영역 분리: api vs frontend) |
| **2** | frontend | pages/orders/NewOrderPage + components/order/OrderPreviewModal + api/order + hooks/useManualOrderMutation + routes | ~350 LOC | HIGH | Phase 1과 병렬 가능 (backend DTO spec 인용 의무) |
| **3** (옵션) | backend-core | consumer/order/OrderRouterKafkaListener.kt source 필드 add-only | ~30 LOC | MED | Phase 1·2 직후 |

**병렬 dispatch 사유**:
- Phase 1 = api 모듈 단독 (api/controller·api/service·api/messaging·api/dto)
- Phase 2 = frontend 단독 (frontend/src)
- 영역 add-only, modify-shared 충돌 risk 0
- DTO 명세는 spec §3-1·§3-6에 LOCK되어 frontend가 backend wait 0건

**Phase 3 직렬 사유**:
- consumer 측 source 필드 add-only는 Phase 1·2 머지 후 진입(테스트 회귀 risk)

### 5-2. CHANGELOG 슬롯 사전 발행 (헌법 §4)

본 spec PR이 sub-agent 디스패치 만들므로 CHANGELOG row prepend 의무:

```
<!-- anchor-spec-t-man-manual-order-issuance -->
| 2026-05-18 | **spec(arch): T-MAN 신규 트랙 — 사용자 수동 주문 발행 UI spec/plan 발행 + ADR 0082 Proposed** — ... | _workspace/specs/2026-05-18-t-man-manual-order-issue-spec.md · _workspace/arch/adr/0082-*.md · ... | 사용자 결함 진단 (최고 관리자 2026-05-18 KST) — 가상계좌 통합테스트 dogfooding 의제 |
<!-- pr-N-slot-backend-core -->
| 2026-05-18 | **feat(api): T-MAN Phase 1 — ManualOrderController + ManualOrderService + ManualOrderIntentKafkaProducer** — TBD (작성: backend-core) | ... | T-MAN spec §3-2·§3-5 LOCK 정합 |
<!-- pr-N-slot-frontend -->
| 2026-05-18 | **feat(frontend): T-MAN Phase 2 — NewOrderPage + OrderPreviewModal + useManualOrderMutation** — TBD (작성: frontend) | ... | T-MAN spec §3-6 LOCK 정합 |
<!-- pr-N-slot-end -->
```

### 5-3. dispatch 절차

1. orchestrator가 Phase 1·2 **병렬 background dispatch** (사용자 메모리 `feedback_orchestrator_active_parallel`)
2. 각 sub-agent는 본 spec §3 매트릭스 LOCK 인용 의무
3. Phase 1·2 머지 후 orchestrator가 Phase 3 직렬 dispatch (consumer source 필드)
4. Phase 3 머지 후 dogfooding 케이스 9·10 GREEN evidence 수집
5. ADR 0082 status Proposed → Accepted 격상 (별도 architect PR)

---

## 6. release cut 시점

### 6-1. 권장: v3.6.15 patch (W3 보강 — `feedback_release_policy_patch_first` 정합)

| 사이클 | 트랙 | 비고 |
|---|---|---|
| v3.6.13 | T-O 본 트랙 마감 (ADR 0068 Accepted) | 머지 완료 |
| v3.6.14 | **T-ACCT** (ADR 0081 Proposed) | 진행 중 (`backend-core` Phase 1·2 dispatch 의제) |
| v3.6.15 | **T-MAN** (ADR 0082 Proposed) | 본 spec |
| v3.7.0 | **W4 진입 — T-AI 시작 (LOCK)** | v3.7.0 minor cut LOCK |

**T-MAN과 T-ACCT 묶음 v3.6.14 cut 안 함** 사유:
- 영역 분리 (T-MAN=주문 액션 / T-ACCT=잔고 조회)
- 사용자 메모리 `feedback_dev_priority_qa_batch` — 단일 사이클 묶음 부담 회피
- 단일 PR cycle = T-ACCT 머지 → T-MAN dispatch 별도 사이클

### 6-2. T-MAN 마감 후 묶음 release

dogfooding 케이스 9·10 GREEN + Phase 1·2·3 머지 + ADR 0082 Accepted 격상 후 → v3.6.15 patch cut.

---

## 7. ADR 0082 cross-ref 매트릭스

본 spec은 ADR 0082(신규 발행)와 동반 dispatch.

ADR 0082 핵심 § 매트릭스:
- §1 컨텍스트 (사용자 의제·트랙 위치·v3 베이스라인)
- §2 결정 의제 6건 (§A·§B·§C·§D·§E·§F — 본 spec §2-1 baseline)
- §3 명세 LOCK (본 spec §3 인용)
- §4 dogfooding (본 spec §4 인용)
- §5 sub-agent dispatch (본 spec §5 인용)
- §6 release cut (본 spec §6 인용)
- §7 비전 정합 + fitness gap
- §8 AI 호출 0 grep LOCK
- §9 Rollback
- §10 KIS-first raw 인용 (ADR 0068 §14-5 cross-ref)
- §11 변경 이력
- §12 사용자 메모리 정합 매트릭스

---

## 8. 비범위 (1차 MVP 외)

본 spec 1차 MVP에서 **제외**:

- 호가·현재가 실시간 표시 (E-3 후속 — backlog §66)
- frontend position 사전 검증 (D-2 후속 — T-ACCT Phase 4 머지 후 backlog §66)
- 주문 정정 (REVISE — KIS RVSE_CNCL_DVSN_CD="01") — ADR 0068 §2-5 후속 동일 (1차 MVP는 취소만)
- 분할 주문 (수량 100 → 50+50 분할 발행) — ADR 0068 §2-4 분할 체결 baseline 흡수만, 사용자 분할 발행은 비범위
- 예약 주문 (장 시작·종가 등) — KIS 별도 endpoint 의제
- 신용·대출·해외 주식 — 본 spec은 domestic_stock/order_cash 한정
- 알림 채널 (Slack·이메일·SMS) — ADR 0044·0070 baseline 흡수 (T-NOTIFY 영역)
- 인증·권한 강화 — 본 spec은 onboarding userId baseline 한정 (T-AUTH backlog)

---

## 9. Rollback

### 9-1. spec 발행 rollback (본 PR)

문서 only — `_workspace/specs/2026-05-18-t-man-manual-order-issue-spec.md` + `_workspace/arch/adr/0082-*.md` + backlog §65 + tracks-roadmap row + master §10 row + CHANGELOG row 모두 단일 revert.

자본 영향 0. 운영 코드 0 touch.

### 9-2. Phase 1·2·3 머지 후 rollback (운영 코드)

| Phase | rollback | 회귀 risk |
|---|---|---|
| Phase 1 (backend) | api 모듈 신규 4 파일 revert + Flyway V31 down 의제 (없으면 0) | 자동 path 영향 0 (api 모듈 add-only) |
| Phase 2 (frontend) | frontend 신규 3 파일 revert + 라우팅 1줄 revert | UI 회귀 — /orders/new 404 fallback |
| Phase 3 (consumer) | source 필드 default "SIGNAL" fallback 흡수 — revert 시 manual 주문은 신호 주문으로 attribution 미스 (audit 흔적만) | 자본 영향 0 (12 step 본체 0 touch) |

3 Phase 모두 add-only로 자본 path 영향 0. revert는 single PR으로 가능.

---

## 10. 사용자 메모리 정합 매트릭스

| 사용자 메모리 | 본 spec 정합 |
|---|---|
| `project_system_vision.md` (룰 본체 + 사용자=최고 관리자) | 룰 path 0 touch + 수동 path 분리 + 사용자 최고 관리자 권한 부여 |
| `feedback_kis_first_policy.md` (KIS 단일 출처) | KIS endpoint 호출 0 신규(`KisOrderHttpAdapter` 재사용) · raw 인용 ADR 0068 §14-5 cross-ref |
| `feedback_kis_key_realonly_for_data.md` (정보 수집 실전 키 / 주문 액션만 MOCK·REAL 분기) | 본 spec은 주문 액션이므로 3중 게이트 동일 적용 정합 |
| `feedback_v3_skepticism_no_regression.md` (자동매매 fitness + v1/v2 잔재 검증) | §1-3 fitness gap 분석 의무 수행 — 자동매매 필수(가상계좌 통합테스트) + v1/v2 잔재 아님 |
| `feedback_no_mock_data.md` (mock 합성 0) | 본 spec UI는 mock 합성 0 — 시장가는 "체결 후 확정" 명시 |
| `feedback_release_policy_patch_first.md` (v3.7=W4 LOCK) | v3.6.15 patch cut 권장 |
| `feedback_spec_plan_scope.md` (신규 트랙 → spec/plan + ADR 의무) | 본 spec + ADR 0082 발행 |
| `feedback_orchestrator_active_parallel.md` (병렬 dispatch) | Phase 1·2 병렬 권장 |
| `feedback_swagger_pattern.md` (4단 어노테이션 + 한국어) | §3-1 Swagger 매트릭스 명시 |
| `feedback_verification_strict.md` (evidence 의무) | §4-2 evidence 매트릭스 명시 |
| `feedback_3tier_hierarchy.md` (orchestrator → architect → sub-agent) | 본 spec은 architect 단독 발행 + sub-agent dispatch는 orchestrator |
| `project_kis_key_management.md` (SettingsModal AES 암호화) | 본 spec은 credential lookup만 (기존 baseline 재사용) |

---

## 11. 변경 이력

| 날짜 | 변경 | 사유 |
|------|------|------|
| 2026-05-18 | 본 spec 신규 발행 + ADR 0082 Proposed 동반 | 사용자 결함 진단 (최고 관리자) "주문을 하는 화면이 없어 --;; 잔액조회도" · "확장형으로 별도 페이지 있어도될거같은데?" · "가상계좌 잔고 등 통합테스트 하려면 이게 있어야해" 정합 (architect) |

---

## 12. 종료 보고 매트릭스

**산출물**:
- `_workspace/specs/2026-05-18-t-man-manual-order-issue-spec.md` (본 문서)
- `_workspace/arch/adr/0082-t-man-manual-order-issuance.md`
- `_workspace/arch/adr/README.md` (인덱스 갱신)
- `_workspace/backlog.md` §65 신규
- `docs/architecture/master-architecture.md` §10 ADR 0082 row
- `docs/architecture/tracks-roadmap.md` §2 T-MAN row
- `CHANGELOG.md` slot 3 anchor

**검증 의무 (architect)**:
- ADR 0068 §14-2·§14-3 본문 변경 0 (재사용 정합)
- ADR 0067 본문 변경 0 (T-RISK 우회 cross-ref만)
- `./gradlew check` GREEN (코드 변경 0)
- KIS-first raw 인용 ADR 0068 §14-5 cross-ref LOCK
- AI 호출 0 LOCK (LLM provider import 0 — 본 spec 자체에 import 0)

**dispatch 준비**:
- orchestrator가 Phase 1(backend-core) + Phase 2(frontend) 병렬 background dispatch
- 각 sub-agent는 본 spec §3 매트릭스 인용 의무

이상.
