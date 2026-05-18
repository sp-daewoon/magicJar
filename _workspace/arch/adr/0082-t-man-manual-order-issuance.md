# ADR 0082 — T-MAN 사용자 수동 주문 발행 트랙 (Manual Order Issuance — 가상계좌 통합테스트 dogfooding + 사용자 최고 관리자 통제)

- 상태: **Proposed (2026-05-18 — 사용자 결함 진단 위임 "주문을 하는 화면이 없어 --;; 잔액조회도" · "확장형으로 별도 페이지 있어도될거같은데?" · "가상계좌 잔고 등 통합테스트 하려면 이게 있어야해" / Phase 1a·1b·1c·2 머지 후 Accepted 격상 예정)**
- 작성일: 2026-05-18
- 정정일: 2026-05-18 (B-1 → **B-2-extended** 채택 — 사용자 결정 정합 "데이터 상에선 사용자 주문이랑 자동매매주문의 타입이 구분되어야하는거 필수")
- 작성자: architect
- 결정자: 사용자(최고 관리자) — 결정 위임 (옵션 A "확장형 별도 페이지" + 추가 결정 "orderType 데이터 구분 필수") / architect 자율 결정 5 의제 (A·C·D·E·F)
- 진입 트랙: **T-MAN (W3 보강 트랙, v3.6.15 patch cut 후보)**
- 관계:
  - **ADR 0068 (T-O 주문 실행)** — 본 ADR이 그 자동 path의 사용자 수동 진입점 확장. `OrderRouterKafkaListener` 12 step 100% 재사용 + `KisOrderHttpAdapter` 재사용 + `TradingModeGateImpl` 3중 게이트 동일 적용. **ADR 0068 §14-2·§14-3 본문 변경 0**. cross-ref only.
  - **ADR 0067 (T-RISK Risk Guard chain 6단)** — 본 ADR은 T-RISK Guard chain **우회** 결정 (의제 C-1) — 사용자 명시 액션이므로 룰 안전망 외부 path. KillSwitch만 재검증.
  - **ADR 0024 (KillSwitch Risk Guard)** — 본 ADR도 동일 적용 — 수동 주문이라도 kill_switch_state.enabled=true 시 즉시 차단.
  - **ADR 0043 (KillSwitch 5대 행동)** — 본 ADR 진입 직전 재검증 의무 (자동 주문과 동일).
  - **ADR 0044 (Notification)** — order.requested.v1·order.filled.v1·order.rejected.v1·order.cancelled.v1 STOMP 알림 동일 흡수.
  - **ADR 0046 (Onboarding & Credential)** — user_kis_credential REAL/MOCK fetch 정합.
  - **ADR 0048 (KIS WS 41 slot)** — 본 ADR은 WS 신규 구독 0건 (체결통보 H0STCNI0/H0STCNI9는 T-O baseline reuse).
  - **ADR 0081 (T-ACCT — 잔액·예수금 사용자 노출)** — 본 ADR 케이스 9 dogfooding이 T-ACCT DashboardPage 잔고 변화 evidence path와 합류 (cross-ref).
  - **ADR 0010 (Multi-tenant)** — userId 격리 의무 (수동 주문도 userId scoped).
  - **ADR 0023 (Signal Engine AI 호출 0)** — 본 ADR도 동일 정책 계승. LLM 호출 0건.
  - **ADR 0030 (master-architecture SoT)** — §10 ADR 0082 row 추가 동반.
  - **ADR 0037 (KIS Rate Limit 20 req/s)** — 본 ADR KIS API 신규 호출 0건 (`KisOrderHttpAdapter` baseline reuse).
  - **ADR 0049 (KIS credential identity)** — 본 ADR userId scope 동일.
  - **ADR 0050 (CHANGELOG 슬롯 채번룰)** — 본 spec PR + Phase 1·2 dispatch 시 3 slot 사전 발행 의무.

> **AI 호출 0 검증 의무 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8 정합)**: 본 ADR 범위 내 모든 컴포넌트 코드에 LLM provider import 0건. sub-agent 디스패치 시 architect 1회 grep 검증 의무 + 코드 inline 주석 정책 명시.

> **KIS-first raw 인용 의무 (헌법 §3)**: 본 ADR은 KIS endpoint 신규 호출 0건. raw 인용은 **ADR 0068 §14-5 cross-ref**로 흡수 (TTTC0012U·VTTC0012U·TTTC0011U·VTTC0011U raw 출처 `examples_llm/domestic_stock/order_cash/order_cash.py` LOCK).

> **자본 path 단일 LOCK (사용자 비전 정합)**: 본 ADR은 자동 path(T-O)와 **동일 OrderRouterKafkaListener 12 step**을 통과. 3중 게이트·KillSwitch·멱등성·KIS REST submit·체결 추적·STOMP·audit 모두 단일 권위. 자본 path 분기 발생 0.

---

## 1. 컨텍스트

### 1-1. 사용자 결함 진단 (2026-05-18 dogfooding)

> "주문을 하는 화면이 없어 --;; 잔액조회도"
> "C가 권위적이긴 하지만 실제로 자동매매 하는거 결국엔 호가, 가격, 수량, 포지션을 넘겨서 주문을 성사 할테니 확장형으로 별도 페이지 있어도될거같은데?"
> "하려는 이유는 가상계좌 잔고 등 통합테스트 하려면 이게 있어야해"
> — 사용자 (최고 관리자) 2026-05-18 KST

### 1-2. T-MAN 트랙 위치 (tracks-roadmap §1·§2)

W3 보강 트랙 (T-ACCT와 동일 패턴):
- T-BT (Backtest) — v3.3.1 마감
- T-SIG (Signal) — v3.4.0 마감
- T-RISK (Risk) — v3.5.0 마감
- T-O (Order Execution) — **v3.6.13 본 트랙 마감 (ADR 0068 Accepted 2026-05-18)**
- T-RECON (Reconcile) — v3.6.2 patch
- T-NOTIFY (Slack) — v3.6.2 patch
- T-J (Journal) — v3.6.4 patch
- T-PERF (Performance) — v3.6.4 patch
- T-NEWS (Disclosure·News) — v3.6.7 patch
- **T-ACCT (Account Balance — ADR 0081 Proposed)** — v3.6.14 patch 진행 중
- **T-MAN (Manual Order Issuance — 본 ADR)** — **v3.6.15 patch cut 후보**

T-MAN은 **W3 보강 fitness gap 해소 트랙**:
1. 가상계좌(MOCK) 통합테스트 dogfooding 의제 (사용자 명시)
2. 룰이 신호를 못 잡는 종목·시점에 사용자 직접 주문할 권한 (최고 관리자 통제)
3. T-ACCT 잔고 변화 evidence path 합류 (cross-ref)

### 1-3. 현재 main 상태 (사전 자산 — ADR 0068 마감 후)

자산 확보 완료:
- **OrderRouterKafkaListener 12 step LOCK** — 3중 게이트·KillSwitch·멱등성·KIS REST submit·체결 추적·STOMP·audit
- **KisOrderHttpAdapter** — REAL/MOCK 분기 + TTTC0012U·VTTC0012U·TTTC0011U·VTTC0011U + raw 인용 LOCK
- **TradingModeGateImpl** — 3중 게이트 (env × 2 + consent_at)
- **OrderIntent·OrderRequest·OrderAuditEvent 도메인** — 17 컬럼 baseline
- **OrdersPage·OrderDetailPage·OrderCard** — frontend baseline + STOMP `/topic/order.user.{userId}` 4 event 구독
- **InstrumentSearchInput** — 종목 검색 재사용
- **SettingsModal** — `real_trading_consent_at` 토글 UI

본 ADR은 **위 자산 위에 수동 entry point 추가** (자본 path 단일 LOCK · 본체 코드 0 touch).

### 1-4. fitness gap 분석 (사용자 메모리 `feedback_v3_skepticism_no_regression` lens)

#### 1-4-1. 자동매매에 필요한가?

**W3 보강 필수** — 3 사유:
- (a) 가상계좌 통합테스트 dogfooding (사용자 명시) — signal seed SQL 부담 해소
- (b) 사용자 = 최고 관리자 비전 정합 — 룰이 못 잡는 종목·시점 수동 주문 권한
- (c) T-ACCT 잔고 변화 evidence path 합류

#### 1-4-2. v1/v2 잔재 아닌가?

**잔재 아님** — v3 신규(현재 사용자 노출 0건). v1/v2 시점 수동 주문 UI 코드 0건(PoC v3.0.0 전체 정리).

#### 1-4-3. 사용자 비전 정합 (`project_system_vision.md`)

- magicJar = 룰 기반 본체 → 본 ADR 룰 path(`OrderRouterKafkaListener` 12 step) **0 touch** + 수동 entry point만 추가
- 사용자 = 최고 관리자 → 본 ADR 수동 주문 권한 부여 + 3중 게이트 + UI 안전 layer 4중
- AI = 옵션 보조 → 본 ADR LLM 호출 0건 검증 의무
- MCP 서버(ADR 0027) → 본 ADR endpoint가 외부 MCP client 호출 가능한 baseline (미래)
- **정보 수집 = 실전 키 단일** (`feedback_kis_key_realonly_for_data`) → 본 ADR은 주문 액션이므로 3중 게이트 의무 (정보 수집 분기 무관)

---

## 2. 결정 의제 (6건)

### 2-1. 의제 A — 동기 vs 비동기 path

| 옵션 | 설명 |
|---|---|
| **A-1 Kafka 비동기 path (자동 path 동일)** ⭐ | `POST /api/orders/manual` → `OrderIntent` 생성 → `order.intent.v1` publish → `OrderRouterKafkaListener` 동일 12 step |
| A-2 동기 path (Kafka 우회) | 즉시 KisOrderHttpAdapter.submit + 즉시 응답 |
| A-3 dual (옵션 토글) | request body `async: true/false` |

**architect 결정 = A-1**. 사유: 자본 path 단일 LOCK · OrderRouter 12 step 검증된 흐름 재사용 · 자동·수동 동일 audit/멱등성/STOMP 일관성.

### 2-2. 의제 B — signalId·strategyId 처리 + orderType 데이터 구분 (OrderIntent NOT NULL)

**사용자 추가 결정 (최고 관리자 2026-05-18 PR #495 정독 후)**:
> "옵션 1으로 가자 그런데 우리 데이터 상에선 사용자 주문이랑 자동매매주문의 타입이 구분되어야하는거 필수!"

도메인·DB·DTO·UI 일관 구분 의무 → B-1 폐기, **B-2-extended 채택**.

| 옵션 | 설명 |
|---|---|
| B-1 sentinel + Kafka payload field only ❌ | sentinel 0L + Kafka `source="MANUAL"` 필드만 add-only. DB·도메인·DTO·UI 변경 0. **사용자 결정 위반** |
| B-2 nullable signalId + Flyway DROP NOT NULL | `OrderIntent.signalId: Long?` + Flyway migration. signalId NULL 여부로 manual 추론 (의미 흐림) |
| **B-2-extended sentinel + `OrderSource` enum 1급 도메인 + DB 컬럼 + DTO 필드 + UI badge·filter 일관** ⭐ | sentinel 0L 유지 + **신규 `OrderSource { AUTO, MANUAL }` enum** + `OrderIntent.source`·`OrderRequest.source` 1급 필드 + DB `order_request.source VARCHAR(20) NOT NULL DEFAULT 'AUTO'` + 인덱스 `idx_order_request_source` + DTO `source: 'AUTO'\|'MANUAL'` + frontend badge·filter |
| B-3 signal seed auto-INSERT | 수동 주문 시 manual signal row 자동 채번 (audit 잔재) |

**architect 결정 = B-2-extended (사용자 결정 정합 LOCK)**. 사유:
1. **사용자 명시 결정 정합** — "데이터 상에선 사용자 주문이랑 자동매매주문의 타입이 구분되어야하는거 필수". Kafka payload + audit_event JSON 마커만으로는 DB 직접 쿼리·집계·UI 일관 부재.
2. **OrderSource enum 1급 도메인** — `enum class OrderSource { AUTO, MANUAL }` 도메인 의미 명시 + 향후 확장(MCP·CLI 등) baseline.
3. **DB 컬럼 + 인덱스** — `order_request.source` + `CREATE INDEX idx_order_request_source` → manual 주문 빠른 lookup · 집계 · audit trail 명시.
4. **OrderRouter step 5 INSERT 시 source 영속** — Kafka payload deserialize → OrderRequest.source 채번 → INSERT. 자동 path default `AUTO` 흡수 (add-only).
5. **DTO·UI 일관** — `OrderListItemDto.source` + frontend `'AUTO'|'MANUAL'` 리터럴 union + OrderCard badge + OrdersPage filter toggle.
6. **sentinel 0L 유지** — signalId=0L·strategyId=0L sentinel은 그대로 LOCK. `source==MANUAL && signalId==0L` 이중 검증.
7. **B-1 폐기** — 사용자 결정 위반.
8. **B-2 폐기** — signalId null 분기는 source 의미 우회.
9. **B-3 폐기** — signal 테이블 오염.

**Flyway V34·V35 의무** (V31~V33 점유 — V31 realized_pnl·V32 disclosure·V33 article):
- V34__order_request_add_source.sql — source 컬럼 + 인덱스 (LOCK)
- V35__order_request_fk_relax.sql — sentinel 0L INSERT 허용 (V20 FK constraint DROP, 옵션-Y 권장)

**검증 의무**: backend-core dispatch 시 V20 본문 + `\d order_request` 실행으로 정확한 FK constraint name 인용 의무. signal·strategy 테이블 id=0 row 부재 검증.

### 2-3. 의제 C — T-RISK Guard chain 우회 여부

| 옵션 | 설명 |
|---|---|
| **C-1 T-RISK 우회 + KillSwitch만 재검증** ⭐ | OrderIntent publish 시 T-RISK consumer 진입 0 + OrderRouter step 4 KillSwitch 재검증만 |
| C-2 T-RISK Guard chain 동일 적용 | signal.generated.v1 minimal publish → T-RISK 평가 → ALLOWED 시만 OrderIntent publish |
| C-3 dual (사용자 선택) | request body `bypassRisk: true/false` |

**architect 결정 = C-1**. 사유:
- 사용자 = 최고 관리자 비전 정합
- UX 직관 (사용자 명시 액션이 룰 차단되면 혼란)
- KillSwitch는 동일 적용 (최고 위험 안전망 유지)
- 3중 게이트 + UI 이중 confirm + 60초 cooldown + KillSwitch = 4 layer 안전망 보상

### 2-4. 의제 D — position 검증

| 옵션 | 설명 |
|---|---|
| **D-1 1차 MVP 미도입 (KIS 자체 검증 흡수) + D-2 후속** ⭐ | 매도 수량 > 보유 시 KIS REJECT 흡수 + T-ACCT 머지 후 frontend 사전 검증 추가 |
| D-2 frontend 사전 검증 (T-ACCT API 의존) | 1차 MVP부터 도입 |
| D-3 backend 사전 검증 (KIS 동기 호출) | 1차 MVP부터 도입 |

**architect 결정 = D-1 + D-2 후속**. 사유: KIS 단일 출처 정합 + MVP 단순 + T-ACCT 머지 후 backlog §66 후속.

### 2-5. 의제 E — 호가·현재가 표시

| 옵션 | 설명 |
|---|---|
| **E-1 1차 MVP 표시 0 + E-3 후속** ⭐ | UI는 종목·side·priceType·quantity·가격 input만 + 후속 KIS `inquire-price` REST 1회 호출 |
| E-2 호가 stream WS 구독 | KIS WS H0STASP0 호가 실시간 |
| E-3 현재가 REST 1회 호출 | 진입 시 1회 호출 + manual refresh |

**architect 결정 = E-1 + E-3 후속**. 사유: MVP 단순 + WS slot 영향 회피 + 사용자 의제 핵심은 dogfooding이지 거래 UX 풍부 아님.

### 2-6. 의제 F — ADR 신설 vs 0068 §확장

| 옵션 | 설명 |
|---|---|
| F-1 ADR 0068 §확장 | T-O 본문 확장 |
| **F-2 신규 ADR 0082** ⭐ | T-MAN 독립 ADR + ADR 0068 cross-ref |

**architect 결정 = F-2**. 사유:
- 트랙 ID 신규 (W3 보강)
- ADR 0068 본 트랙 마감 evidence(2026-05-18) 훼손 회피
- `feedback_spec_plan_scope` 정합 (신규 트랙 → 신규 ADR 의무)

---

## 3. 명세 LOCK (spec 본문 인용)

본 ADR은 spec `_workspace/specs/2026-05-18-t-man-manual-order-issue-spec.md` §3을 권위로 흡수. 핵심 매트릭스만 재인용:

### 3-1. backend endpoint

```
POST /api/orders/manual
Content-Type: application/json

Request:
{
  "userId": "<8자리>",
  "code": "005930",
  "side": "BUY" | "SELL",
  "priceType": "MARKET" | "LIMIT",
  "quantity": 1,
  "limitPrice": "70500"  // LIMIT 시 not null
}

Response 202 Accepted:
{
  "orderId": null,        // OrderRouter step 5 INSERT 후 채번 (STOMP에서 후속)
  "status": "ACCEPTED",   // endpoint 응답 한정 (OrderRequest.status 아님)
  "idempotencyKey": "manual:74683223:1716073800",
  "message": "수동 주문이 접수되었습니다. /orders 페이지에서 상태를 확인하세요."
}
```

### 3-2. 컴포넌트 매트릭스 (B-2-extended LOCK)

**domain 모듈 (신규 1 파일 + 수정 add-only 2 파일)** — Phase 1a:
- `domain/.../order/execution/OrderSource.kt` (신규) — `enum class OrderSource { AUTO, MANUAL }`
- `domain/.../order/execution/OrderIntent.kt` (수정 add-only) — `source: OrderSource = OrderSource.AUTO` 필드 add
- `domain/.../order/execution/OrderRequest.kt` (수정 add-only) — `source: OrderSource = OrderSource.AUTO` 필드 add

**api 모듈 (신규 4 파일 + 수정 add-only 2 파일 + Flyway V34·V35)** — Phase 1b:
- `api/controller/order/ManualOrderController.kt` (신규)
- `api/service/order/ManualOrderService.kt` (신규)
- `api/messaging/order/ManualOrderIntentKafkaProducer.kt` (신규)
- `api/controller/order/dto/ManualOrderRequest.kt` (신규)
- `api/controller/order/dto/ManualOrderResponse.kt` (신규)
- `api/controller/order/dto/OrderListItemDto.kt` (수정 add-only) — `source: String` 필드 add
- `api/controller/order/OrderQueryController.kt` (수정 add-only) — `toListItem()` 매핑에 `source = req.source.name` + `list()` query param `source: String?` filter
- `api/src/main/resources/db/migration/V34__order_request_add_source.sql` (신규 LOCK)
- `api/src/main/resources/db/migration/V35__order_request_fk_relax.sql` (신규 — 옵션-Y 권장)

**consumer 모듈 (수정 add-only 1 파일 + JPA 매핑 정합 1 파일)** — Phase 1c:
- `consumer/order/OrderRouterKafkaListener.kt` (수정 add-only) — `deserialize()` source 필드 + OrderIntent source 채번 + step 5 INSERT source 영속 + audit_event.payloadJson source 마커 이중
- consumer JPA 어댑터 (OrderRequest JPA Entity 매핑) — source 컬럼 매핑 add (backend-core가 영속 어댑터 위치 발견 후 정합)

**frontend (신규 4 파일 + 수정 add-only 6 파일)** — Phase 2:
- `frontend/src/pages/orders/NewOrderPage.tsx` (신규)
- `frontend/src/components/order/OrderPreviewModal.tsx` (신규)
- `frontend/src/api/hooks/useManualOrderMutation.ts` (신규)
- `frontend/src/api/types/OrderListItemDto.ts` (신규 또는 inline type 갱신) — `source: 'AUTO' \| 'MANUAL'` 추가
- `frontend/src/components/order/OrderCard.tsx` (수정 add-only) — source badge 표시
- `frontend/src/pages/orders/OrdersPage.tsx` (수정 add-only) — [신규 주문] 버튼 + source filter toggle
- `frontend/src/api/order.ts` (수정 add-only) — `manualIssue()` + DTO
- `frontend/src/api/hooks/useOrders.ts` (수정 add-only) — source query param 인자
- `frontend/src/routes/...` (수정 add-only) — `/orders/new` 1줄

**라인 수 estimate**: 760 LOC (B-1 630에서 +130 — domain enum + DB 영속 + UI badge·filter).

### 3-3. UI 안전 layer 매트릭스

| Layer | 검증 | 위반 시 |
|---|---|---|
| 1 | 종목코드 6자리 | input disable |
| 2 | 수량 ≥ 1 | submit disable |
| 3 | LIMIT 시 가격 > 0 | submit disable |
| 4 | 3중 게이트 표시 | REAL 미허용 시 SettingsModal 안내 |
| 5 | KillSwitch 상태 | ON 시 발행 disable |
| 6 | 미리보기 → 이중 confirm | checkbox 미체크 시 [발행] disable |
| 7 | 60초 cooldown | UI 카운터 + 차단 |
| 8 | REAL 모드 시 강조 | "실 자본 변동 발생" 붉은색 |

### 3-4. AI 호출 0 LOCK 검증

```bash
# spec / Phase 1 / Phase 2 / Phase 3 발행 시 architect grep 검증 의무
grep -rE "import (anthropic|openai|com.anthropic|com.openai|org.springframework.ai)" \
  api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/order/ \
  api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/order/ \
  api/src/main/kotlin/kr/spdaewoon/magicjar/api/messaging/order/ \
  consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/order/ \
  frontend/src/pages/orders/ \
  frontend/src/components/order/ \
  frontend/src/api/

# 기대: 0 hit
```

---

## 4. dogfooding 매트릭스 (spec §4 인용)

### 4-1. T-O Phase 4 통합 — 케이스 6·7·8 강화

| 기존 케이스 | T-MAN 합류 후 |
|---|---|
| 케이스 6 (시그널 발행 + 체결 풀체인) | UI 수동 발행으로 signal seed SQL 부담 0 |
| 케이스 7 (OrdersPage 조회) | 수동 주문이 source="MANUAL" badge로 표시 |
| 케이스 8 (OrdersPage 취소) | 동일 path |

### 4-2. T-MAN 신규 케이스 9·10

#### 케이스 9: 수동 MOCK 주문 발행 (가상계좌 통합테스트)

**전제**: T-MAN Phase 1·2 머지 + env MAGICJAR_MODE=MOCK + MOCK credential 등록
**Step 1~8**: `/orders/new` 진입 → 종목 선택 → BUY MARKET 수량 1 → 미리보기 → 발행 → /orders redirect → STOMP REQUESTED·ACK 토스트 → (옵션) H0STCNI9 체결 → FILLED → T-ACCT DashboardPage 잔고 변화 evidence

**evidence**: brower screenshot + backend log (12 step) + DB order_request + order_audit_event source="MANUAL" + STOMP payload

#### 케이스 10: 3중 게이트 차단

**전제**: env MAGICJAR_MODE=REAL + ALLOW_REAL=false
**기대**: UI 발행 버튼 disabled + API precheck 403 REAL_GATE_DENIED + 자본 변동 0

---

## 5. sub-agent dispatch plan (B-2-extended LOCK)

| Phase | sub-agent | 영역 | LOC est. | 우선순위 | 의존 |
|---|---|---|---|---|---|
| **1a** | backend-core | **domain** — OrderSource enum + OrderIntent·OrderRequest source 필드 add (default AUTO) | ~30 | HIGH | baseline (선행 의무) |
| **1b** | backend-core | **api** — ManualOrderController + ManualOrderService + ManualOrderIntentKafkaProducer + DTO(5 파일) + OrderListItemDto source + OrderQueryController + Flyway V34·V35 | ~280 | HIGH | Phase 1a 머지 후 (1c와 병렬 가능) |
| **1c** | backend-core | **consumer** — OrderRouterKafkaListener source deserialize/persist + audit_event source 마커 + JPA 매핑 | ~50 | HIGH | Phase 1a 머지 후 (1b와 병렬 가능) |
| **2** | frontend | NewOrderPage + OrderPreviewModal + OrderCard badge + OrdersPage filter + useManualOrderMutation + useOrders source param + OrderListItemDto types + routes | ~400 | HIGH | Phase 1a·1b 머지 후 (DTO 정합) |

**Phase 의존 그래프**:

```
Phase 1a (domain baseline)  ─┬─→  Phase 1b (api + Flyway)  ──┐
                              │                              ├─→  Phase 2 (frontend)
                              └─→  Phase 1c (consumer)  ─────┘
```

**dispatch 절차**:
1. orchestrator → backend-core: Phase 1a 단독 dispatch (domain baseline LOCK)
2. Phase 1a 머지 후 → backend-core 2회 병렬 dispatch (Phase 1b + Phase 1c)
3. Phase 1b·1c 머지 후 → frontend Phase 2 dispatch
4. Phase 2 머지 후 dogfooding 케이스 9·10 evidence 수집
5. ADR 0082 Proposed → Accepted 격상 (별도 architect PR)

**CHANGELOG slot 5 anchor 사전 발행 의무** (헌법 §4):
- `anchor-spec-t-man-manual-order-issuance`
- `pr-N-slot-backend-core-1a` (domain)
- `pr-N-slot-backend-core-1b` (api)
- `pr-N-slot-backend-core-1c` (consumer)
- `pr-N-slot-frontend` (Phase 2)
- `pr-N-slot-end`

---

## 6. release cut 시점

**v3.6.15 patch 권장** (사용자 메모리 `feedback_release_policy_patch_first` 정합):

| 사이클 | 트랙 |
|---|---|
| v3.6.13 | T-O 본 트랙 마감 (ADR 0068) |
| v3.6.14 | T-ACCT (ADR 0081) — 진행 중 |
| **v3.6.15** | **T-MAN (본 ADR)** |
| v3.7.0 | W4 진입 (T-AI LOCK) |

T-ACCT와 묶음 cut 안 함 사유: 영역 분리 + dogfooding 검증 사이클 분리 + `feedback_dev_priority_qa_batch` 정합.

---

## 7. 비범위

본 ADR 1차 MVP 비범위 (spec §8 인용):
- 호가·현재가 실시간 표시 (E-3 후속 backlog §66)
- frontend position 사전 검증 (D-2 후속 — T-ACCT Phase 4 후 backlog §66)
- 주문 정정 (RVSE_CNCL_DVSN_CD="01") — ADR 0068 §2-5 후속과 동일
- 분할 발행
- 예약 주문
- 신용·대출·해외 주식
- 인증·권한 강화 (T-AUTH backlog)

---

## 8. AI 호출 0 검증 (ADR 0023·0066·0067·0068 §8 정합)

본 ADR 범위 컴포넌트 (api/controller/order + api/service/order + api/messaging/order + api/dto + consumer/order + frontend/src/{pages,components,api}/order) 모두 LLM provider import 0.

검증 시점:
- spec 발행 시 (architect): 0 (코드 변경 0)
- Phase 1 머지 전 (backend-core): grep PASS evidence
- Phase 2 머지 전 (frontend): grep PASS evidence
- Phase 3 머지 전 (backend-core): grep PASS evidence
- ADR Accepted 격상 시 (architect): 전체 grep PASS 최종 검증

---

## 9. Rollback

### 9-1. ADR/spec rollback (본 PR)

문서 only — `_workspace/arch/adr/0082-*.md` + spec + backlog §65 + tracks-roadmap row + master §10 row + ADR README + CHANGELOG row 모두 single revert. 자본 영향 0.

### 9-2. Phase 1a·1b·1c·2 머지 후 rollback (운영 코드 — B-2-extended)

| Phase | rollback | 회귀 risk |
|---|---|---|
| Phase 1a (domain) | OrderSource enum 삭제 + OrderIntent·OrderRequest source 필드 삭제 | default AUTO이므로 호출 사이트 영향 0 · Phase 1b·1c·2 모두 revert 동반 의무 |
| Phase 1b (api + Flyway V34·V35) | api 4 신규 + 2 수정 revert + Flyway V34·V35 down (V36 micro `DROP COLUMN source; DROP INDEX idx_order_request_source; ADD CONSTRAINT fk_order_request_signal` 의무) | 자동 path 영향 0 · DB source 컬럼 데이터 손실 |
| Phase 1c (consumer) | OrderRouterKafkaListener source deserialize/persist revert + audit_event JSON 마커 revert | default "AUTO" fallback — revert 시 manual 주문이 자동 attribution (audit 흔적만, 자본 영향 0) |
| Phase 2 (frontend) | frontend 4 신규 + 6 수정 revert | UI 회귀 — /orders/new 404 + source badge·filter 0 |

**revert 순서 LOCK** (의존 역순): Phase 2 → 1c → 1b → 1a. single revert PR 불가능 (4 PR 묶음 revert 의무 — architect 책임).

4 Phase 모두 add-only로 자본 path 영향 0. revert 시 source 컬럼 데이터 손실만 발생 (audit trail 의미적 손실).

---

## 10. KIS-first raw 인용

본 ADR은 KIS endpoint 신규 호출 0건. raw 인용은 **ADR 0068 §14-5 cross-ref**로 흡수:

| 항목 | 인용 |
|---|---|
| 매수/매도 raw 출처 | `examples_llm/domestic_stock/order_cash/order_cash.py` (def order_cash) — ADR 0068 §14-5 |
| TR_IDs | TTTC0012U(real BUY) · TTTC0011U(real SELL) · VTTC0012U(demo BUY) · VTTC0011U(demo SELL) |
| endpoint | POST /uapi/domestic-stock/v1/trading/order-cash |
| request body | CANO · ACNT_PRDT_CD · PDNO · ORD_DVSN · ORD_QTY · ORD_UNPR · EXCG_ID_DVSN_CD · SLL_TYPE · CNDT_PRIC |
| response output | KRX_FWDG_ORD_ORGNO · ODNO · ORD_TMD |

**raw fetch 시점 evidence**: 2026-05-18 WebFetch 시점 확인 — `def order_cash(env_dv, ord_dv, cano, acnt_prdt_cd, pdno, ord_dvsn, ord_qty, ord_unpr, excg_id_dvsn_cd, sll_type="", cndt_pric="")` 시그니처 정합 LOCK.

본 ADR은 raw 인용 신규 0 (cross-ref만) — 자체 추론 0 의무 정합.

---

## 11. 변경 이력

| 날짜 | 변경 | 사유 |
|------|------|------|
| 2026-05-18 | **정정 — B-1 폐기 → B-2-extended 채택 (사용자 결정 정합)** + OrderSource enum 신설 + OrderIntent·OrderRequest source 필드 + Flyway V34·V35 + DTO·UI 일관 + Phase 1a baseline 신설 (1a → 1b·1c → 2) | 사용자 추가 결정 (최고 관리자 2026-05-18 PR #495 정독 후): "옵션 1으로 가자 그런데 우리 데이터 상에선 사용자 주문이랑 자동매매주문의 타입이 구분되어야하는거 필수!". B-1 sentinel + Kafka payload만으로는 DB 직접 쿼리·집계·UI 일관 부재 — 사용자 결정 위반. B-2-extended로 domain·DB·DTO·UI 1급 시민 구분. 결정 의제 매트릭스 6 의제 중 B 의제만 정정 — A·C·D·E·F 결정 본문 변경 0. (architect) |
| 2026-05-18 | 본 ADR 신규 발행 (Proposed) | 사용자 결함 진단 (최고 관리자) "주문을 하는 화면이 없어 --;; 잔액조회도" · "확장형으로 별도 페이지 있어도될거같은데?" · "가상계좌 잔고 등 통합테스트 하려면 이게 있어야해" 정합. T-MAN W3 보강 트랙 신설. spec 본문 발행 동반(`_workspace/specs/2026-05-18-t-man-manual-order-issue-spec.md`). 6 의제 architect 결정 (A-1 비동기 + B-1 sentinel + C-1 T-RISK 우회 + D-1 KIS 흡수 + E-1 표시 0 + F-2 신규 ADR). v3.6.15 patch cut 권장. (architect) |

---

## 12. 사용자 메모리 정합 매트릭스

| 사용자 메모리 | 본 ADR 정합 |
|---|---|
| `project_system_vision` | 룰 path 0 touch + 사용자 최고 관리자 권한 부여 + AI 호출 0 |
| `feedback_kis_first_policy` | KIS endpoint 신규 호출 0 (ADR 0068 §14-5 cross-ref) |
| `feedback_kis_key_realonly_for_data` | 주문 액션 3중 게이트 동일 적용 정합 |
| `feedback_v3_skepticism_no_regression` | fitness gap 분석 §1-4 의무 수행 (필요 + v1/v2 잔재 아님) |
| `feedback_no_mock_data` | UI mock 합성 0 — 시장가 "체결 후 확정" 명시 |
| `feedback_release_policy_patch_first` | v3.6.15 patch cut (v3.7=W4 LOCK) |
| `feedback_spec_plan_scope` | 신규 트랙 → spec + ADR 발행 의무 정합 |
| `feedback_orchestrator_active_parallel` | Phase 1·2 병렬 dispatch 권장 |
| `feedback_swagger_pattern` | §3 endpoint Swagger 4단 명시 |
| `feedback_verification_strict` | §4 evidence 매트릭스 명시 |
| `feedback_3tier_hierarchy` | architect 단독 발행 + sub-agent dispatch orchestrator |
| `feedback_dev_priority_qa_batch` | T-ACCT와 묶음 cut 안 함 (영역 분리) |

이상.
