# 1번 트랙 — KIS 주문 API 어댑터 설계

- 작성일: 2026-05-02
- 작성자: architect (메인 세션, ADR 0030)
- 상태: Draft (사용자 review 대기)
- 관련 ADR: 0006(OrderSender 분기), 0011(QA), 0017(git author), 0021(외부 어댑터), 0026(MinIO/MSA)

## 1. 목적

magicJar의 자동매매 진입 토대 5개 영역 중 **1번 트랙**. KIS 주문 API 어댑터 + Kafka 기반 주문 흐름 + 멀티테넌트 확장 path를 갖춘 골격을 1차 사이클에서 만든다.

핵심 산출물:
- `OrderSender`의 MOCK 활성 구현체 (KIS 모의계좌 작동)
- `KisOrderPort` 도메인 포트 + `RealKisOrderAdapter` 인프라 어댑터
- `KisHttpSession` (REST 토큰 공유, PR #32 자산 추출) + `KisWsSession` (체결통보 WS)
- `KisExecutionNoticeListener` (H0STCNI9 push) + `OrderExecutionSweep` (REST 보강)
- `OrderIntentConsumer` + `MockOrderSender` + Guard chain (KillSwitch + DailyOrderLimit + PriceSanity)
- `users` + `user_trading_config` + `orders` + `order_events` 테이블 (멀티테넌트 확장 path 포함)
- profile 분기(`mock`/`dev`/`prd`) 정정 + 시드 마이그 (.env → DB)
- 운영 가이드 `_workspace/operations/order-policy.md`

비범위 (다음 사이클):
- 인증 layer (ISSUE-006 후속 트랙)
- 매수가능조회 endpoint (잔고로 근사)
- 미체결주문 별도 조회 (체결조회로 근사)
- ETF/선물옵션·신용·해외 주식
- 야간 정정·예약주문
- 다계좌·다전략 라우팅
- KIS Pro·실전 키 발급 (사용자 별도 절차 후 후속 트랙)
- 인증 도입 시 SecurityContext·OAuth 통합

## 2. 기존 자산

이미 도메인 레벨에서 상당 부분 설계되어 있음 (Phase D-P2 잔존):

| 파일 | 상태 |
|---|---|
| `domain/common/Order.kt` `OrderResult.kt` `OrderType.kt` `OrderStatus.kt` `Side.kt` | 완비 (PENDING/PARTIALLY_FILLED/FILLED/CANCELED/REJECTED, MARKET/LIMIT) |
| `domain/order/port/OrderSender.kt` | 인터페이스 (`send(Order): OrderResult`) — ADR 0006 BACKTEST/MOCK/REAL 분기 명시 |
| `domain/order/port/BrokerGateway.kt` | 시세+주문 통합 포트 — **deprecate 예정** (Q1 결정) |
| `domain/market/port/KisRestPort.kt` | 시세 전용 포트 |
| `batch/.../infrastructure/kis/RealKisRestAdapter.kt` | 시세 KIS REST 구현 (PR #32) — 토큰 401 invalidate+재발급 로직 보유. **consumer로 이전 예정** (Q5 결정) |
| `batch/.../infrastructure/kis/StubKisRestAdapter.kt` | E-P2 stub — `mock` profile 시 활성 |
| `batch/.../infrastructure/kis/KisRestProperties.kt` | KIS 설정 빈딩 |
| `batch/.../infrastructure/kis/KisApiException.kt` | 카테고리 분류 보강 예정 (Q9) |
| `domain/order/event/{OrderIntent,OrderExecuted}Event.kt` | Kafka `order.intent.v1`·`order.executed.v1` 스키마 |
| `batch/.../config/OrderSenderConfig.kt` | `BatchPlaceholderOrderSender` 폴백 — 모든 주문 REJECTED |

## 3. Architectural Decisions (12건 합의)

### D1. 포트 분리 + 인프라 공유 (Q1: C)

도메인 측에선 `KisRestPort`(시세) + 신규 `KisOrderPort`(주문) 분리. 인프라 측에선 `KisHttpSession` 공통 컴포넌트로 토큰·재발급·HTTP 클라이언트 공유. `BrokerGateway`는 deprecate.

### D2. 주문 endpoint 6 메서드 (Q2: B)

| 메서드 | KIS endpoint | TR_ID (real / demo) |
|---|---|---|
| `placeOrder(LIMIT/MARKET)` | `POST /uapi/domestic-stock/v1/trading/order-cash` | TTTC0011U·TTTC0012U / VTTC0011U·VTTC0012U |
| `cancelOrder` | `POST /uapi/domestic-stock/v1/trading/order-rvsecncl` | TTTC0013U / VTTC0013U |
| `modifyOrder` | (위와 동일 endpoint, `RVSE_CNCL_DVSN_CD=01`) | 동일 |
| `fetchBalance` | `GET /uapi/domestic-stock/v1/trading/inquire-balance` | TTTC8434R / VTTC8434R |
| `fetchExecutions(today)` | `GET /uapi/domestic-stock/v1/trading/inquire-daily-ccld` | TTTC0081R / VTTC0081R (3개월 이내) |
| `subscribeExecutionNotice` (WS) | KIS WSS endpoint | H0STCNI0 / H0STCNI9 |

매수가능조회·미체결 별도 조회·시장가 매수가능수량은 후속 사이클.

### D3. WS Primary + REST Sweep (Q3: D)

체결 추적은 H0STCNI9 WebSocket이 1차 (즉시 push), 30초마다 REST `inquire-daily-ccld` sweep로 누락 보강. 멱등 키 = `(kis_mode, kis_order_no, cumulative_qty)`.

### D4. Minimal Real Guard (Q4: A, 사용자 정정 반영)

1차 가드 3개 — 한도값 매우 느슨, env property로 노출, 정상 매매 차단 0:
- **KillSwitch** (default ON) — `magicjar.guard.kill-switch=on|off`
- **DailyOrderLimit** — `magicjar.guard.daily-order-limit-count=1000` / `magicjar.guard.daily-order-limit-value-krw=100000000`
- **PriceSanity** — 전일종가 ±30% 벗어난 LIMIT 차단

**1차 방벽은 가드가 아니라 Order 도메인 invariants** (qty>0, LIMIT 시 price 필수, code 6자리). 가드는 비상 정지 성격의 2차 안전망. 정책 진화는 `_workspace/operations/order-policy.md`에서 풀어 씀.

### D5. KIS 외부 시스템 전체 consumer 통합 (Q5: D)

`batch` 모듈명이 misleading (실시간 외부 push 받기엔 부적합). KIS 외부 어댑터 + 시세 backfill + 주문 어댑터 모두 **consumer 모듈**로 통합. `batch`는 BacktestRunJob + JobTriggerController만 남겨 단일 책임.

| 모듈 | 1번 트랙 후 책임 |
|---|---|
| `consumer` | KisHttpSession·KisWsSession·RealKisRestAdapter(시세·이전)·RealKisOrderAdapter(주문·신규)·KisExecutionNoticeListener·OrderExecutionSweep·OrderIntentConsumer·MockOrderSender·Guard chain·KisDailyCandleBackfill(이전)·시세 WS 구독·STOMP fanout·AI 분석 |
| `batch` | `BacktestRunJob` + `JobTriggerController` |
| `api` | REST + STOMP relay (변화 없음) |

### D6. Profile 분기 mock/dev/prd + 사용자 등록 데이터 (Q6, Q7-1, 사용자 정공법)

| Profile | 외부 호출 | 주문 처리 | 사용자 데이터 |
|---|---|---|---|
| `mock` | 0 | in-memory `MockOrderSender` | 시드 1명 |
| `dev` | 시세·뉴스·종목 = 실 KIS 모의 서버. **주문만 stub** | in-memory | 시드 1명, 통합 dev 환경 |
| `prd` | 사용자 등록 키 기반 KIS 호출 | 사용자 `trading_mode` (`MOCK_KIS`/`REAL_KIS`)에 따라 모의·실전 | 사용자별 등록 |

**prd는 "운영 환경"이며, 모의·실전 모두 prd 내부에서 사용자 등록 데이터로 분기.** profile 단계가 곧 실거래가 아님. KillSwitch는 모든 profile에서 default ON으로 부팅.

명명 통일:
- 기존 `@Profile("stub-kis")` `@Profile("real-kis")` → `@Profile("mock")` / `@Profile("dev | prd")`로 정리
- `MAGICJAR_MODE` env 폐기 → `SPRING_PROFILES_ACTIVE`로 일원화

부팅 로그 (강제 출력):
- mock: `[KIS_MODE] active=MOCK (no external calls)`
- dev: `[KIS_MODE] active=DEV (KIS 모의계좌 ${account})`
- prd: `[KIS_MODE] active=PRD (trading_mode=${mode}) account=${account} ⚠ ${LIVE_TRADING|MOCK_TRADING}`

### D7. DB 통합 + 계좌/모드 컬럼 (Q7-차원1, 사용자 제안)

DB 자체는 단일 postgres. 시세·종목·뉴스·매매일지·차트 스냅샷·백테스트 결과는 mode 무관 공통. 계좌 의존 데이터(orders, order_events, position_snapshot, balance_snapshot, kill_switch_state, daily_order_counter)에만 `user_id` + `account_no` + `kis_mode` 컬럼.

### D8. B′ — orders + order_events (Q7-차원2)

`orders` (mutable projection, fast read) + `order_events` (append-only audit trail). `order_executions`는 별도 두지 않고 `order_events`에 흡수 (event_type=`EXECUTED` + cumulative_qty). dedup 멱등 키 = `(kis_mode, kis_order_no, event_type, cumulative_qty)`.

### D9. KIS 키 암호화 (Q8: A)

env master key + AES-GCM 암호화. `SecretCipher.encrypt(plain)/decrypt(cipher)` 인터페이스 추상화 — 후속 사이클에 Vault로 교체 시 인터페이스 무변경.

- master key: env `MAGICJAR_SECRET_MASTER_KEY` (`.env` 비밀, gitignore)
- 알고리즘: AES-256-GCM, IV는 row마다 랜덤 12B, ciphertext는 base64
- DB 컬럼: `kis_app_key_encrypted TEXT`, `kis_app_secret_encrypted TEXT`

### D10. KIS 에러 카테고리 매핑 (Q9: B)

`KisApiException`에 `category` enum 추가:
- `AUTH` — 토큰 만료/잘못된 키 (msg_cd: EGW00121, EGW00123 등)
- `RATE_LIMIT` — 호출 한도 (msg_cd: EGW00201)
- `INSUFFICIENT_FUNDS` — 매수가능금액 부족
- `INVALID_ORDER` — 호가/수량 오류, 정정 가능 수량 초과
- `MARKET_CLOSED` — 장 외 시간
- `SERVER` — 5xx
- `UNKNOWN` — 카테고리 미분류

`category`별 retry 정책: AUTH = 1회 재발급 후 retry / RATE_LIMIT = exponential backoff / SERVER = 최대 3회 retry / 나머지 = retry 없음.

### D11. 테스트 전략 (Q10)

| 영역 | 도구 | 시나리오 |
|---|---|---|
| KIS REST 어댑터 단위 | WireMock IT (PR #32 패턴) | 매수/매도/정정/취소/잔고/체결 6+ 시나리오 + 401 재발급 + 429 backoff |
| KIS WS Listener 단위 | embedded WS server (`spring-test-websocket`) + fake `KisWsSession` mock | 정상/부분체결/거부/연결끊김/재접속 5 시나리오 |
| AES256 복호화 | KIS 공식 wikidocs sample fixture | 1건 단위 검증 |
| OrderSender 분기 | profile별 `@SpringBootTest(properties=...)` | mock/dev profile 부팅 + bean 활성 확인 |
| End-to-end 모의 사이클 | Testcontainers(postgres+kafka) + WireMock | OrderIntent → place → 체결 → order.executed publish 왕복 |
| dogfooding | 5번 트랙 ISSUE 양식 | ISSUE-009: 모의계좌 1주 매수 → 체결 확인 → 1주 매도 → 잔고 0 |

### D12. UI/관측 (Q11)

| 항목 | 1차 |
|---|---|
| frontend `/orders` 신규 | 미체결 + 당일 체결 + 잔고. STOMP `/topic/orders/{userId}` 실시간 |
| Dashboard 주문 패널 | 수동 매수/매도 입력 (1번 트랙 dogfooding 입구) |
| 로그 | structured JSON — `kis_mode`, `account_no`, `order_id`, `kis_order_no`, `latency_ms`, `event_type` |
| 부팅 로그 | `[KIS_MODE] active=...` 강제 |
| 관측 | actuator `/actuator/metrics` — `kis.api.calls`, `kis.api.errors{category}`, `kis.ws.disconnects`, `orders.placed{mode}`, `orders.filled{mode}`, `executions.dedup_hits` 카운터 |

grafana·prometheus 도입은 별도 트랙.

## 4. Data Model

### 4.1 신규 테이블 (Flyway V9)

```sql
-- 사용자 (1차 시드 1명, 인증 도입 시 컬럼 추가만)
CREATE TABLE users (
  user_id UUID PRIMARY KEY,
  username VARCHAR(64) UNIQUE NOT NULL,
  display_name VARCHAR(128),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);

-- 사용자별 KIS 키·계좌·모드 등록 데이터
CREATE TABLE user_trading_config (
  user_id UUID PRIMARY KEY REFERENCES users(user_id),
  trading_mode VARCHAR(16) NOT NULL,        -- MOCK_KIS | REAL_KIS
  account_no VARCHAR(20) NOT NULL,           -- KIS CANO (8자리)
  account_product_code VARCHAR(2) NOT NULL,  -- KIS ACNT_PRDT_CD (보통 "01")
  kis_app_key_encrypted TEXT NOT NULL,
  kis_app_secret_encrypted TEXT NOT NULL,
  hts_id VARCHAR(64) NOT NULL,               -- WS 체결통보 subscribe key
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CHECK (trading_mode IN ('MOCK_KIS','REAL_KIS'))
);

-- 주문 메타 (mutable projection, fast read)
CREATE TABLE orders (
  order_id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(user_id),
  account_no VARCHAR(20) NOT NULL,
  kis_mode VARCHAR(8) NOT NULL,              -- MOCK_KIS | REAL_KIS (in-memory mock은 row 없음)
  intent_id UUID NOT NULL,
  kis_order_no VARCHAR(20) NOT NULL,
  krx_fwdg_ord_orgno VARCHAR(8) NOT NULL,    -- 정정/취소에 필요한 거래소조직번호
  code VARCHAR(7) NOT NULL,                  -- ETN 7자리 대비
  side VARCHAR(8) NOT NULL,                  -- BUY | SELL
  qty INT NOT NULL CHECK (qty > 0),
  price NUMERIC(15,2),                       -- LIMIT일 때 NOT NULL
  order_type VARCHAR(8) NOT NULL,            -- MARKET | LIMIT
  status VARCHAR(20) NOT NULL,
  filled_qty INT NOT NULL DEFAULT 0,
  avg_fill_price NUMERIC(15,2),
  reject_reason TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  UNIQUE (kis_mode, kis_order_no)
);

-- 주문 라이프사이클 audit trail (append-only, INSERT only)
CREATE TABLE order_events (
  event_id UUID PRIMARY KEY,
  order_id UUID NOT NULL REFERENCES orders(order_id),
  user_id UUID NOT NULL,
  account_no VARCHAR(20) NOT NULL,
  kis_mode VARCHAR(8) NOT NULL,
  kis_order_no VARCHAR(20) NOT NULL,
  event_type VARCHAR(20) NOT NULL,           -- PLACED|MODIFIED|CANCELED|EXECUTED|REJECTED|RECEIPT_ACCEPTED
  cumulative_qty INT,                        -- EXECUTED 시 누적 체결 수량
  partial_qty INT,                           -- EXECUTED 시 이번 체결분
  exec_price NUMERIC(15,2),
  source VARCHAR(8),                         -- WS | REST_SWEEP | INTERNAL
  payload JSONB,                             -- KIS 응답 raw 보존
  occurred_at TIMESTAMPTZ NOT NULL,
  ingested_at TIMESTAMPTZ NOT NULL,
  -- 멱등 dedup: 같은 체결이 WS·REST 둘 다 들어와도 한 번만 INSERT
  UNIQUE (kis_mode, kis_order_no, event_type, cumulative_qty)
);

-- 일일 주문 카운터 (DailyOrderLimit Guard용)
CREATE TABLE daily_order_counter (
  counter_date DATE NOT NULL,
  user_id UUID NOT NULL,
  account_no VARCHAR(20) NOT NULL,
  count INT NOT NULL DEFAULT 0,
  total_value_krw NUMERIC(18,2) NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (counter_date, user_id, account_no)
);

-- KillSwitch 상태 (db row 1개로 모든 사용자 공통, 향후 user-scoped 가능)
CREATE TABLE kill_switch_state (
  scope VARCHAR(16) PRIMARY KEY,             -- 'GLOBAL' (1차)
  is_active BOOLEAN NOT NULL DEFAULT TRUE,   -- default ON
  reason TEXT,
  toggled_by VARCHAR(64),
  toggled_at TIMESTAMPTZ NOT NULL
);
INSERT INTO kill_switch_state (scope, is_active, reason, toggled_by, toggled_at)
VALUES ('GLOBAL', TRUE, 'initial — guard layer default ON', 'system', NOW());

-- 인덱스
CREATE INDEX idx_orders_user_created ON orders(user_id, created_at DESC);
CREATE INDEX idx_orders_pending ON orders(kis_mode, status) WHERE status IN ('PENDING','PARTIALLY_FILLED');
CREATE INDEX idx_order_events_order ON order_events(order_id, occurred_at);
CREATE INDEX idx_order_events_user ON order_events(user_id, occurred_at DESC);
```

### 4.2 멀티테넌트 확장 path

| 확장 포인트 | 1번 트랙 1차 | 멀티테넌트 진입 시 |
|---|---|---|
| user 식별 | 시드 사용자 1명 (`DEFAULT_USER_ID`) | 인증 도입 + SecurityContext |
| repository 강제 필터 | 모든 user-scoped query에 `WHERE user_id=:current` (Spring Data Specification) | 그대로 |
| KIS 키 관리 | DB 컬럼 + master key env | Vault 구현체로 교체 (`SecretCipher`/`KisCredentialProvider` 인터페이스 무변경) |
| candles | 단일 테이블 | partition by date + TimescaleDB hypertable |
| order_events 누적 | 단일 테이블 | 일/월 partition (`PARTITION BY RANGE (occurred_at)`) |
| KIS 호출 5회/초 | 단일 사용자 1 토큰 | 사용자별 키 + KIS Pro + 호출 큐잉 |

### 4.3 시드 마이그레이션 (V9 Flyway, Task 1)

`.env`의 `KIS_MOCK_APP_KEY` / `KIS_MOCK_APP_SECRET` / `KIS_ACCOUNT_CANO` / `KIS_ACCOUNT_PRDT` → DB seed.

흐름:
1. 부팅 시 `MAGICJAR_SECRET_MASTER_KEY` 검증 (없으면 fail-fast)
2. `users(user_id=DEFAULT_USER_ID, username='default')` INSERT
3. `KIS_MOCK_*` 평문 → AES-GCM 암호화 → `user_trading_config(trading_mode='MOCK_KIS', account_no=KIS_ACCOUNT_CANO, ...)` INSERT
4. 시드 후 `.env`에서 `KIS_MOCK_*`·`KIS_ACCOUNT_*` 제거하고 `MAGICJAR_SECRET_MASTER_KEY`만 남김 (수동 단계, plan에 명시)
5. `KIS_APP_KEY`·`KIS_APP_SECRET`은 실전용이라 시드 안 함, .env에 보존 (또는 향후 별도 등록)

`hts_id`는 KIS 토큰 발급 응답 또는 사용자 명시. 1차는 placeholder + 운영 가이드에서 사용자가 등록하도록 명시.

## 5. KIS API Endpoints (MCP 검증된 사양)

### 5.1 주식주문 (현금) — `placeOrder`

- URL: `POST /uapi/domestic-stock/v1/trading/order-cash`
- TR_ID: `TTTC0011U`(real sell) `TTTC0012U`(real buy) / `VTTC0011U`(demo sell) `VTTC0012U`(demo buy)
- Headers: `authorization: Bearer ${access_token}`, `appkey`, `appsecret`, `tr_id`, `custtype: P`(개인)
- Body (JSON, KEY 대문자):
  ```json
  {
    "CANO": "${account_no}",
    "ACNT_PRDT_CD": "${product_code}",
    "PDNO": "005930",
    "ORD_DVSN": "00",        // 00:지정가, 01:시장가, ...
    "ORD_QTY": "1",
    "ORD_UNPR": "70000",     // 지정가만 의미. 시장가 시 "0"
    "EXCG_ID_DVSN_CD": "KRX",
    "SLL_TYPE": "01",        // 매도주문 시: 01:일반매도
    "CNDT_PRIC": ""
  }
  ```
- Response output (성공 시 200, `rt_cd=0`):
  ```json
  { "KRX_FWDG_ORD_ORGNO": "06010", "ODNO": "0000123456", "ORD_TMD": "143025" }
  ```

### 5.2 주문 정정/취소 — `modifyOrder` / `cancelOrder`

- URL: `POST /uapi/domestic-stock/v1/trading/order-rvsecncl`
- TR_ID: `TTTC0013U` / `VTTC0013U`
- Body 핵심:
  ```json
  {
    "CANO": "...",
    "ACNT_PRDT_CD": "...",
    "KRX_FWDG_ORD_ORGNO": "06010",     // place 응답에서 보관
    "ORGN_ODNO": "0000123456",          // 원주문번호
    "ORD_DVSN": "00",
    "RVSE_CNCL_DVSN_CD": "01",          // 01:정정, 02:취소
    "ORD_QTY": "1",
    "ORD_UNPR": "55000",                // 취소 시 무시
    "QTY_ALL_ORD_YN": "Y",              // Y:전량, N:일부
    "EXCG_ID_DVSN_CD": "KRX"
  }
  ```
- Response output: `KRX_FWDG_ORD_ORGNO`, `ODNO`(새 주문번호), `ORD_TMD`

### 5.3 잔고조회 — `fetchBalance`

- URL: `GET /uapi/domestic-stock/v1/trading/inquire-balance`
- TR_ID: `TTTC8434R` / `VTTC8434R`
- Query params: `CANO`, `ACNT_PRDT_CD`, `AFHR_FLPR_YN=N`, `OFL_YN=`, `INQR_DVSN=02`(종목별), `UNPR_DVSN=01`, `FUND_STTL_ICLD_YN=N`, `FNCG_AMT_AUTO_RDPT_YN=N`, `PRCS_DVSN=00`, `CTX_AREA_FK100=`, `CTX_AREA_NK100=`
- Response:
  - `output1` (array): 종목별 보유 — `pdno`(종목코드), `prdt_name`, `hldg_qty`(보유수량), `ord_psbl_qty`(주문가능수량), `pchs_avg_pric`(매입평균가), `prpr`(현재가), `evlu_amt`(평가금액), `evlu_pfls_amt`(평가손익)
  - `output2` (object 1행): 계좌 합계 — `dnca_tot_amt`(예수금총금액), `nxdy_excc_amt`(D+1), `prvs_rcdl_excc_amt`(D+2), `tot_evlu_amt`(총평가금액), `nass_amt`(순자산금액), `evlu_pfls_smtl_amt`(평가손익합계)
- 페이지네이션: 모의 1회 최대 20건, `tr_cont`/`FK100`/`NK100` 연속조회

### 5.4 일별 주문체결조회 — `fetchExecutions`

- URL: `GET /uapi/domestic-stock/v1/trading/inquire-daily-ccld`
- TR_ID (3개월 이내): `TTTC0081R` / `VTTC0081R`
- Query params: `CANO`, `ACNT_PRDT_CD`, `INQR_STRT_DT=YYYYMMDD`, `INQR_END_DT=YYYYMMDD`, `SLL_BUY_DVSN_CD=00`, `PDNO=`, `CCLD_DVSN=02`(미체결만), `INQR_DVSN=00`, `INQR_DVSN_3=00`, `EXCG_ID_DVSN_CD=KRX`, `CTX_AREA_FK100=`, `CTX_AREA_NK100=`, `ORD_GNO_BRNO=`, `ODNO=`(특정 주문 조회), `INQR_DVSN_1=`
- Response:
  - `output1` (array): 주문/체결 라인 — `ord_dt`, `ord_gno_brno`, `odno`(주문번호), `orgn_odno`(원주문번호), `pdno`, `ord_qty`, `ord_unpr`, `tot_ccld_qty`(총체결수량), `tot_ccld_amt`, `cncl_yn`, `rmn_qty`(잔여수량), `rjct_qty`(거부수량), `ccld_cndt_name`
  - `output2` (object): 합계
- 페이지네이션: 모의 1회 최대 15건

### 5.5 체결통보 WebSocket — `subscribeExecutionNotice`

- TR_ID: `H0STCNI0`(real) / `H0STCNI9`(demo)
- WSS endpoint (KIS 공식): `ws://ops.koreainvestment.com:21000` (실전), `ws://ops.koreainvestment.com:31000` (모의)
- subscribe 흐름:
  1. REST `/oauth2/Approval` → `approval_key` 받음 (auth_ws 별도)
  2. WS connect → SUBSCRIBE 프레임 전송
     ```json
     { "header": {"approval_key": "...", "tr_type": "1", "custtype": "P", "content-type": "utf-8"},
       "body":   {"input": {"tr_id": "H0STCNI9", "tr_key": "${hts_id}"}} }
     ```
  3. SUBSCRIBE SUCCESS 응답에 `iv`, `key` 포함 → AES256 복호화에 사용
  4. 실 데이터 프레임은 `|` 구분: `${암호화여부}|${tr_id}|${데이터건수}|${암호화된 페이로드}`
  5. 페이로드는 `^` 구분 26 컬럼 — `CUST_ID|ACNT_NO|ODER_NO|OODER_NO|SELN_BYOV_CLS|RCTF_CLS|ODER_KIND|ODER_COND|STCK_SHRN_ISCD|CNTG_QTY|CNTG_UNPR|STCK_CNTG_HOUR|RFUS_YN|CNTG_YN|ACPT_YN|...`
  6. **`CNTG_YN`** = "1": 주문/정정/취소/거부 접수통보 / "2": 체결통보 → event_type 분기

### 5.6 KIS 토큰 발급 (PR #32 자산)

- URL: `POST /oauth2/tokenP`
- Body: `{"grant_type":"client_credentials","appkey":"...","appsecret":"..."}`
- Response: `access_token`, `expires_in`(보통 86400s), `token_type:Bearer`
- 401 응답 시 → 토큰 invalidate + 재발급 → 1회 retry (PR #32 패턴)

`auth_ws` 별도 endpoint (`POST /oauth2/Approval`) — `approval_key` 발급, WS subscribe에만 사용.

## 6. Component Architecture

### 6.1 consumer 모듈 신규/이전 파일 (1번 트랙 후)

```
consumer/src/main/kotlin/com/mj/consumer/
├── infrastructure/
│   ├── kis/
│   │   ├── KisHttpSession.kt              [신규: PR #32 토큰 로직 추출]
│   │   ├── KisWsSession.kt                [신규: WS 연결/heartbeat/AES복호]
│   │   ├── RealKisRestAdapter.kt          [이전: batch에서 옮김]
│   │   ├── RealKisOrderAdapter.kt         [신규: KisOrderPort 구현]
│   │   ├── StubKisRestAdapter.kt          [이전]
│   │   ├── KisRestProperties.kt           [이전]
│   │   ├── KisApiException.kt             [이전+카테고리 보강]
│   │   └── KisOrderResponseMapper.kt      [신규: KIS output → domain]
│   ├── persistence/
│   │   ├── OrderJpaAdapter.kt             [신규]
│   │   ├── OrderEventJpaAdapter.kt        [신규]
│   │   ├── DailyOrderCounterJpaAdapter.kt [신규]
│   │   ├── KillSwitchJpaAdapter.kt        [신규]
│   │   └── UserTradingConfigJpaAdapter.kt [신규]
│   └── secret/
│       ├── AesGcmSecretCipher.kt          [신규]
│       └── DbKisCredentialProvider.kt     [신규]
├── application/
│   ├── order/
│   │   ├── OrderIntentConsumer.kt         [신규: order.intent.v1 listener]
│   │   ├── MockOrderSender.kt             [신규: in-memory FILLED]
│   │   ├── KisOrderSender.kt              [신규: dev/prd profile 활성]
│   │   ├── OrderSenderFactory.kt          [신규: profile 분기]
│   │   ├── ExecutionPublisher.kt          [신규: order.executed.v1 발행]
│   │   ├── KisExecutionNoticeListener.kt  [신규: H0STCNI9 WS]
│   │   └── OrderExecutionSweep.kt         [신규: @Scheduled REST]
│   ├── guard/
│   │   ├── OrderGuard.kt                  [신규: 인터페이스]
│   │   ├── KillSwitchGuard.kt             [신규]
│   │   ├── DailyOrderLimitGuard.kt        [신규]
│   │   ├── PriceSanityGuard.kt            [신규]
│   │   └── GuardChain.kt                  [신규: composite]
│   └── backfill/
│       └── KisDailyCandleBackfill.kt      [이전: batch에서 @Scheduled로]
└── config/
    ├── ConsumerKisConfig.kt               [신규: Bean 분기]
    └── application-dev.yml                [profile별 설정]
```

### 6.2 domain 모듈 신규 파일

```
domain/src/main/kotlin/com/mj/domain/
├── order/port/
│   ├── KisOrderPort.kt                    [신규]
│   ├── OrderRepository.kt                 [신규]
│   ├── OrderEventRepository.kt            [신규]
│   └── OrderSender.kt                     [기존, 변경 없음]
├── order/event/
│   ├── OrderIntentEvent.kt                [기존]
│   ├── OrderExecutedEvent.kt              [기존, payload 보강]
│   └── OrderLifecycleEvent.kt             [신규: 도메인 이벤트]
├── user/
│   ├── User.kt                            [신규]
│   ├── UserTradingConfig.kt               [신규]
│   └── port/
│       ├── UserRepository.kt              [신규]
│       ├── UserTradingConfigRepository.kt [신규]
│       └── KisCredentialProvider.kt       [신규: Vault 교체 인터페이스]
└── secret/
    └── SecretCipher.kt                    [신규: 암호화 추상]
```

### 6.3 api 모듈 변경

- `OrderController.kt` 신규 — `GET /api/orders` (주문 목록) + `POST /api/orders` (수동 주문 트리거, dev/prd dogfooding)
- `BalanceController.kt` 신규 — `GET /api/balance` (잔고)
- STOMP 토픽 추가: `/topic/orders/{userId}` — order_events 발생 시 fanout

### 6.4 frontend 변경

- `/orders` 신규 페이지 — 미체결/당일체결/잔고
- Dashboard 수동 주문 패널 (1번 트랙 dogfooding 입구)
- types.ts: `OrderResponse`, `BalanceResponse`, `OrderEventMessage` 신규

### 6.5 batch 모듈 변경

- `RealKisRestAdapter.kt`·`StubKisRestAdapter.kt`·`KisRestProperties.kt`·`KisApiException.kt` 등 KIS 관련 파일을 consumer로 이전
- `BacktestRunJob.kt`만 잔존, JpaConfig·DTO·Entity는 그대로
- batch는 KIS 의존 0가 됨

## 7. Order Lifecycle 시퀀스

### 7.1 정상 매수 흐름 (dev profile)

```
[strategy/AI] OrderIntentEvent (intent_id, code, side=BUY, qty=1, price=70000, type=LIMIT)
              ↓ Kafka order.intent.v1
[consumer.OrderIntentConsumer]
              ↓
[GuardChain] KillSwitch.check → DailyOrderLimit.check → PriceSanity.check
              ↓ pass
[KisOrderSender] (dev profile 활성)
              ↓
[KisOrderPort.placeOrder] → RealKisOrderAdapter
              ↓
[KisHttpSession] POST order-cash (TR_ID=VTTC0012U, demo)
              ↓ KIS 응답 ODNO=0000123456, KRX_FWDG_ORD_ORGNO=06010
[OrderJpaAdapter] orders INSERT (status=PENDING)
[OrderEventJpaAdapter] order_events INSERT (event_type=PLACED)
[ExecutionPublisher] order.executed.v1 publish (status=PENDING)
              ↓ STOMP /topic/orders/{userId}
[frontend] 미체결 row 추가

(이후 KIS 체결까지 대기)
[KIS WS H0STCNI9] 체결통보 push (CNTG_YN=2, CNTG_QTY=1, CNTG_UNPR=70000)
              ↓ AES256 복호
[KisExecutionNoticeListener]
              ↓ INSERT 시도 order_events (event_type=EXECUTED, cumulative_qty=1)
              ↓ UNIQUE 제약 통과 (첫 ingest)
[OrderProjectionUpdater] orders UPDATE (status=FILLED, filled_qty=1, avg_fill_price=70000)
[ExecutionPublisher] order.executed.v1 publish (status=FILLED)
              ↓ STOMP
[frontend] 미체결 → 당일체결로 이동
```

### 7.2 WS 누락 + REST Sweep 보강 (멱등 검증)

```
[KIS WS] 연결 끊김 (heartbeat fail)
[KisExecutionNoticeListener] 자동 재접속 시도
              (이 사이 체결 발생, push 누락)
[OrderExecutionSweep @Scheduled(30s)]
              ↓ REST inquire-daily-ccld (CCLD_DVSN=01 체결만)
              ↓ output1 array — odno=0000123456, tot_ccld_qty=1
              ↓ INSERT 시도 order_events (event_type=EXECUTED, cumulative_qty=1, source=REST_SWEEP)
              ↓ UNIQUE 제약 통과 → INSERT
[OrderProjectionUpdater] orders UPDATE
[ExecutionPublisher] order.executed.v1 publish

(WS 재접속 후 같은 체결 재push되면)
[KisExecutionNoticeListener] INSERT 시도
              ↓ UNIQUE 충돌 → ON CONFLICT DO NOTHING (멱등)
              ↓ 무시
```

## 8. Security & Secret Management

### 8.1 master key

- env `MAGICJAR_SECRET_MASTER_KEY` (base64 32B AES key)
- `.env`에만 보관, gitignore (사용자 발급)
- 부팅 시 검증 — 없으면 fail-fast (mock profile 제외)

### 8.2 AES-GCM 암호화

```kotlin
class AesGcmSecretCipher(private val key: SecretKey) : SecretCipher {
    override fun encrypt(plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ct)
    }
    override fun decrypt(encoded: String): String { ... }
}
```

### 8.3 KisCredentialProvider 추상

```kotlin
interface KisCredentialProvider {
    fun forUser(userId: UUID): KisCredential
}

data class KisCredential(
    val tradingMode: TradingMode,    // MOCK_KIS | REAL_KIS
    val accountNo: String,
    val accountProductCode: String,
    val appKey: String,              // 복호화된 평문 (in-memory만)
    val appSecret: String,           // 동일
    val htsId: String,
)
```

1차: `DbKisCredentialProvider` — `UserTradingConfigRepository` + `SecretCipher.decrypt`. 인증 도입 시: `AuthAwareKisCredentialProvider`로 SecurityContext.getCurrentUser() 기반.

### 8.4 로그/응답 누출 차단

- 어떤 로그·응답·예외 메시지에도 `appKey`·`appSecret`·`access_token` 평문 출력 금지
- `KisCredential.toString()` override → masked
- pre-commit hook 추가 후보: `git diff` 에서 BASE64 문자열 32바이트 이상 + `KIS_` 키워드 매치 시 경고 (별도 트랙)

## 9. Error & Retry Policy

### 9.1 KIS 응답 분류

```kotlin
enum class KisErrorCategory {
    AUTH,                  // 401, msg_cd EGW00121/EGW00123
    RATE_LIMIT,            // 429, msg_cd EGW00201
    INSUFFICIENT_FUNDS,    // 매수가능금액 부족
    INVALID_ORDER,         // 호가/수량 오류, 정정가능수량 초과
    MARKET_CLOSED,         // 장 외
    SERVER,                // 5xx
    UNKNOWN
}

class KisApiException(
    val rtCd: String,
    val msgCd: String,
    val msgRaw: String,
    val category: KisErrorCategory,
    val httpStatus: Int,
) : RuntimeException("[$category][$msgCd] $msgRaw")
```

### 9.2 retry 정책

| Category | Retry |
|---|---|
| AUTH | 1회 토큰 invalidate+재발급 후 retry |
| RATE_LIMIT | exponential backoff (1s, 2s, 4s) 최대 3회 |
| SERVER | exponential backoff 최대 3회 |
| INSUFFICIENT_FUNDS | 0 (즉시 OrderResult.REJECTED) |
| INVALID_ORDER | 0 (즉시 REJECTED) |
| MARKET_CLOSED | 0 (즉시 REJECTED) |
| UNKNOWN | 0 (alert + REJECTED) |

### 9.3 Guard 거부 시

OrderResult 즉시 REJECTED + reject_reason 명시 (`KILL_SWITCH_ON` / `DAILY_LIMIT_EXCEEDED` / `PRICE_OUT_OF_RANGE`). KIS 호출 자체 안 감.

## 10. Operational Guide References

신규 운영 가이드: `_workspace/operations/order-policy.md`

목차:
1. KillSwitch ON/OFF 절차
2. DailyOrderLimit 한도값 조정 (env property)
3. profile 전환 (mock → dev → prd) 사용자 액션 매트릭스
4. trading_mode 전환 (MOCK_KIS → REAL_KIS) 절차 + 안전 단계
5. KIS 토큰 갱신·재발급
6. KIS WS 연결 모니터링 (`/actuator/metrics kis.ws.*`)
7. 응급상황 대응 (KillSwitch ON → 미체결 일괄 취소 → 잔고 확인)
8. 가드 정책 진화 계획 (3번 트랙 통합 시 어떻게 정밀화될지)
9. KIS API 호출 한도(5회/초) 운영 팁
10. master key rotation 절차 (모든 user_trading_config 재암호화)

## 11. Implementation Phases

### Phase 1 — DB 시드 + 비밀 관리 (Task 1~3)
- T1: V9 Flyway (users, user_trading_config, orders, order_events, daily_order_counter, kill_switch_state) — V8은 분봉 시드로 점유됨
- T2: AesGcmSecretCipher + 단위테스트
- T3: 시드 마이그 (.env → DB INSERT) + 부팅 시 master key 검증 + .env 정리 안내

### Phase 2 — 인프라 컴포넌트 (Task 4~6)
- T4: KisHttpSession (PR #32 토큰 로직 추출 + 단위테스트)
- T5: KisWsSession (WSS 연결/heartbeat/AES256 복호 + 단위테스트)
- T6: RealKisRestAdapter·StubKisRestAdapter·KisRestProperties·KisApiException(카테고리) batch → consumer 이전 + 회귀 검증

### Phase 3 — 주문 도메인 + 어댑터 (Task 7~10)
- T7: domain — KisOrderPort, OrderRepository, OrderEventRepository, KisCredentialProvider, SecretCipher (도메인 인터페이스)
- T8: RealKisOrderAdapter (placeOrder/cancelOrder/modifyOrder/fetchBalance/fetchExecutions) + WireMock IT
- T9: OrderJpaAdapter, OrderEventJpaAdapter, DailyOrderCounterJpaAdapter, KillSwitchJpaAdapter, UserTradingConfigJpaAdapter
- T10: DbKisCredentialProvider + 단위테스트

### Phase 4 — Guard + OrderSender 분기 (Task 11~13)
- T11: GuardChain·KillSwitchGuard·DailyOrderLimitGuard·PriceSanityGuard + 단위테스트
- T12: MockOrderSender (in-memory FILLED 시뮬) + KisOrderSender (실 어댑터 호출) + profile 분기 OrderSenderFactory + 부팅 로그 강제 + ConsumerKisConfig
- T13: 시드 사용자 1명에 대한 OrderSender 활성 통합테스트 (dev profile)

### Phase 5 — Kafka 흐름 (Task 14~15)
- T14: OrderIntentConsumer + ExecutionPublisher + OrderLifecycleEvent + Testcontainers 왕복 IT
- T15: KisExecutionNoticeListener (WS push → order_events INSERT 멱등) + OrderExecutionSweep (@Scheduled REST 보강)

### Phase 6 — UI + 관측 (Task 16~17)
- T16: api OrderController + BalanceController + STOMP /topic/orders + frontend /orders 페이지 + Dashboard 수동 주문 패널
- T17: actuator metrics 카운터 + structured logging

### Phase 7 — 운영 가이드 + Dogfooding (Task 18~20)
- T18: `_workspace/operations/order-policy.md` 작성
- T19: profile 명명 정리 (`stub-kis`/`real-kis` → `mock`/`dev|prd`) + `MAGICJAR_MODE` env 폐기 + docker-compose dev/prd yml
- T20: dogfooding ISSUE-009 — dev profile 모의계좌 1주 매수 → 체결 확인 → 1주 매도 → 잔고 0. 운영 가이드 첫 절차 검증.

## 12. ADR

신규/개정 후보:
- **ADR 0031 (신규)** — Profile 분기 정정 (mock/dev/prd) + 사용자 등록 기반 trading_mode
- **ADR 0032 (신규)** — KIS Order 어댑터 hexagonal 구조 + WS+REST 하이브리드 멱등성
- **ADR 0033 (신규)** — 멀티테넌트 확장 path (user_id 처음부터, KisCredentialProvider 추상)
- **ADR 0006 개정** — OrderSender 분기 정책에 `dev` profile 추가 (in-memory MockOrderSender 의미 정정)
- **ADR 0011 개정 (작은 단위)** — QA dogfooding 시나리오에 1번 트랙 ISSUE-009 양식 명시

## 13. 사용자 액션 (1번 트랙 진행 중 외부 자원 필요)

| 시점 | 액션 | 비고 |
|---|---|---|
| Task 1 진입 전 | `MAGICJAR_SECRET_MASTER_KEY` 발급 + .env 등록 | `openssl rand -base64 32` |
| Task 3 후 | `.env`에서 `KIS_MOCK_*`·`KIS_ACCOUNT_*` 제거 (시드 마이그됨) + 검증 | architect가 안내 |
| Task 5 진입 전 | `hts_id` 입력 (KIS 사용자 ID) — user_trading_config 갱신 | 사용자 KIS HTS·MTS 사용자 ID |
| Task 19 후 | `docker-compose.dev.yml` 활성 운영 + 9 컨테이너 healthy 확인 | architect가 healthy 검증 |
| Task 20 dogfooding | 사용자 직접 frontend Dashboard에서 1주 매수 클릭 → 체결 확인 + 운영 가이드 절차 따라 수행 | 사용자 검증 |
| 후속 트랙 진입 시 | 실전 KIS 키 발급 + IP 화이트리스트 (KIS 발급절차) → user_trading_config trading_mode=REAL_KIS 전환 | 1번 트랙 비범위 |

## 14. 참조

- KIS open-trading-api 공식: https://github.com/koreainvestment/open-trading-api
- 관련 PR: #32 (RealKisRestAdapter 시세, 토큰 재발급)
- 5번 트랙 spec: `docs/superpowers/specs/2026-05-01-qa-defect-loop-design.md`
- 운영 가이드 (신규): `_workspace/operations/order-policy.md`
