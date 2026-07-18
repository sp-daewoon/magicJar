# ADR 0142 — KIS WebSocket Gate Recovery: prod 시세 세션 고정 + 실행 readiness 분리

- 상태: **Accepted (2026-07-16, 최고 관리자 Phase 0~1 직접 결재)**
- 작성일: 2026-07-16
- 작성자: architect
- 공식 KIS source pin: `koreainvestment/open-trading-api@885dd4e2f5c37e4f7e23dd63c15555a9967bc7bc`
- 구현 spec: `_workspace/specs/2026-07-16-kis-ws-gate-recovery-phase1.md`
- 관계: ADR 0068(주문 실행·체결통보), ADR 0124(H0UNCNT0·owner 우선순위·watchlist 비구독), ADR 0138(priority eviction), `docs/architecture/policies/kis-credential-identity.md`, `docs/architecture/external-api-policy.md`
- 범위: **Phase 0 문서 + Phase 1 구현 계약만**. Kotlin/YAML/DB/runtime 변경은 본 ADR에 포함하지 않는다.

---

## 1. 컨텍스트

현행 `KisWebSocketBridge`는 approval은 항상 REAL credential로 발급하면서 endpoint는 `magicjar.mode`에 따라 REAL=`:21000`, MOCK/PAPER=`:31000`으로 고른다. 같은 단일 세션에서 시장 데이터와 체결통보를 함께 구독하므로 MOCK 모드에서는 **REAL approval + VPS endpoint**가 결합된다. 또한 설정 하나인 `magicjar.kis.ws.user-id`가 credential row lookup owner와 체결통보 `tr_key`인 KIS HTS ID를 동시에 나타낸다.

이 결합은 이미 채택된 “정보 수집은 REAL credential 단일 채널” 정책과 충돌하며, 시장 데이터 gate와 실행 notice gate의 상태를 독립적으로 표현하지 못한다. Phase 1은 기존 검증된 prod 시장 데이터 세션을 회복하고 자본 인접 실행 경로는 fail-closed로 분리한다. 범용 WS supervisor 재작성이나 두 번째 물리 세션 도입은 하지 않는다.

### 1-1. 방향 가드

- **기존 방향**: 시세는 KIS REAL 단일 출처, H0UNCNT0 단일 체결가, owner priority와 비축출 규칙, 주문 REAL 이중 게이트, 청산 우선.
- **변경 의도**: 시장 데이터 endpoint를 prod로 고정하고, 실행 mode별 notice readiness를 논리적으로 분리한다.
- **방향 영향**: 기존 방향 유지 + 잘못된 환경 혼합 제거. 주문·시세 계약 확장이나 전략 변경은 없다.
- **주요 위험**: REAL 체결통보 누락, MOCK 신규 BUY 오허용, 시세 슬롯 회귀, credential owner/HTS ID 혼동, rollback 시 prod-approval+VPS 혼합 부활.

---

## 2. 근거 분류

### 2-1. 공식 evidence — immutable commit

아래 사실만 KIS 공식 evidence로 취급한다. 출처는 모두 commit `885dd4e2f5c37e4f7e23dd63c15555a9967bc7bc`에 고정한다.

| 사실 | 고정 출처 | 확인 내용 |
|---|---|---|
| WS endpoint | `kis_devlp.yaml` | `ops = ws://ops.koreainvestment.com:21000`, `vops = ws://ops.koreainvestment.com:31000` |
| 환경별 WS URL 선택 | `examples_user/kis_auth.py` | `my_url_ws = _cfg["ops" if svr == "prod" else "vops"]` |
| approval 발급 | `examples_user/kis_auth.py` `auth_ws` | 선택한 `svr`의 `/oauth2/Approval` 호출 |
| 체결통보 TR | `examples_user/domestic_stock/domestic_stock_functions_ws.py` `ccnl_notice` | REAL=`H0STCNI0`, MOCK=`H0STCNI9` |
| 체결통보 key | `examples_llm/domestic_stock/ccnl_notice/chk_ccnl_notice.py` | subscribe data=`trenv.my_htsid` — KIS HTS ID |
| 통합 체결가 | `examples_llm/domestic_stock/ccnl_total/ccnl_total.py` | `H0UNCNT0`, 세션 구분 필드 `NEW_MKOP_CLS_CODE` |
| WS 구독 동작 | `examples_llm/domestic_stock/{ccnl_total,asking_price_total,market_status_total,overtime_ccnl_krx,ccnl_notice}` | `tr_type="1"` 등록, `tr_type="0"` 해제 |

고정 raw URL 예시:

- `https://raw.githubusercontent.com/koreainvestment/open-trading-api/885dd4e2f5c37e4f7e23dd63c15555a9967bc7bc/kis_devlp.yaml`
- `https://raw.githubusercontent.com/koreainvestment/open-trading-api/885dd4e2f5c37e4f7e23dd63c15555a9967bc7bc/examples_user/kis_auth.py`
- `https://raw.githubusercontent.com/koreainvestment/open-trading-api/885dd4e2f5c37e4f7e23dd63c15555a9967bc7bc/examples_user/domestic_stock/domestic_stock_functions_ws.py`
- `https://raw.githubusercontent.com/koreainvestment/open-trading-api/885dd4e2f5c37e4f7e23dd63c15555a9967bc7bc/examples_llm/domestic_stock/ccnl_notice/chk_ccnl_notice.py`
- `https://raw.githubusercontent.com/koreainvestment/open-trading-api/885dd4e2f5c37e4f7e23dd63c15555a9967bc7bc/examples_llm/domestic_stock/ccnl_total/ccnl_total.py`

### 2-2. live observation — 공식 계약과 구분

2026-07-16 직접 관측 결과:

| endpoint | root | `/tryitout` | 분류 |
|---|---|---|---|
| prod `ops:21000` | HTTP 101 Switching Protocols | HTTP 101 Switching Protocols | live observation |
| VPS `vops:31000` | `ConnectionReset` | `ConnectionReset` | live observation |

이는 해당 시각·해당 네트워크 경로의 연결 관측일 뿐 KIS의 영구 계약, VPS 폐지, H0STCNI 메시지 전달 보장을 증명하지 않는다.

### 2-3. inference — 결정에 쓰되 공식 사실로 표현하지 않음

- prod handshake 성공과 VPS reset 관측, 그리고 공식 환경 결합 예제를 함께 보면 Phase 1에서 **prod 시장 데이터 세션을 먼저 복구**하는 것이 가장 작은 안전 변경이다.
- REAL credential로 발급한 approval을 VPS endpoint에 사용하는 현행 조합은 공식 예제가 보여주는 동일 `svr` 결합을 벗어난다. 따라서 rollback에서도 이 혼합을 복원하지 않는다.
- HTTP 101만으로 구독 성공·첫 tick·체결통보 수신은 입증되지 않는다. Phase 1 acceptance에는 SUBSCRIBE ACK와 first-data evidence가 별도로 필요하다.
- 2026-06-15 `tr_type="0"` 직후 OPSP8997 관측은 응답 `tr_id=null`이라 원인 채널을 특정하지 못했고, 이후 `"2"` 변경 뒤 실제 unsubscribe 재현도 없었다. 공식 5개 채널 샘플이 모두 해제 `"0"`을 명시하므로 과거 `"2"` 추론은 현행 계약으로 사용하지 않는다.

---

## 3. 결정

### 3-1. 시장 데이터 plane은 항상 REAL credential + prod `:21000`

시장 데이터 plane은 runtime mode와 무관하게 다음 tuple로 고정한다.

`(environment=prod, credential=REAL, approval=REAL/prod, endpoint=ops:21000, channels=market-data)`

보존 채널과 정책:

- 체결가 `H0UNCNT0` 단일 채널과 `NEW_MKOP_CLS_CODE` 세션 구분.
- 호가 `H0UNASP0`, 장운영/VI `H0UNMKO0`, 시간외 `H0STOUP0`의 현행 수요 조건.
- owner priority `HOLDING(0) > PENDING_BUY(5) > STRATEGY(10) > DETAIL(20)`.
- HOLDING/PENDING_BUY non-eviction.
- 공식 상한 40을 전부 쓰지 않고 체결통보 reserve를 고려한 **보수적 시장 데이터 39 slot**.
- watchlist는 WS 수요에 들어가지 않으며 자동 시세 polling도 부활시키지 않는다. 현행 watchlist 기능은 유지하되 시세 자동 수집 owner가 아니다.
- 모든 WS 채널의 wire 동작은 `tr_type="1"` 등록, `tr_type="0"` 해제로 고정한다. 과거 OPSP8997 incident는 공식값을 바꾸는 근거가 아니며 별도 payload/session 진단 대상으로 남긴다.

### 3-2. 실행 notice plane은 논리적으로 독립 판정

| runtime mode | 물리 세션 | 체결통보 | Phase 1 상태 |
|---|---|---|---|
| REAL | 기존 prod 시장 데이터 세션 공유 | `H0STCNI0`, `tr_key=KIS HTS ID` | 구독 ACK와 readiness 충족 시 `READY` |
| MOCK/PAPER | 기존 prod 시장 데이터 세션 유지 | **prod에서 `H0STCNI9` 전송 금지** | MOCK HTS ID/전용 VPS 세션이 없으면 `NOT_CONFIGURED`; REST 체결조회·reconcile/backfill만 가능한 경우 `REST_DEGRADED` |

Phase 1은 두 plane을 **논리적으로** 분리하지만 REAL에서는 검증된 prod 물리 세션을 공유한다. MOCK 전용 VPS 물리 세션은 만들지 않는다.

### 3-3. credential owner ID와 KIS HTS ID를 분리

- **credential-owner ID**: `user_kis_credential`에서 appkey/appsecret row를 선택하는 magicJar 내부 식별자.
- **KIS HTS ID**: KIS가 체결통보 subscribe `tr_key`로 요구하는 `my_htsid` 개념.

두 값은 우연히 같을 수 있으나 같은 개념으로 취급하거나 한 설정값의 fallback으로 서로 대체하지 않는다. WS approval과 today-candle seed는 configured owner exact-match만 허용하고 다른 REAL row로 fallback하지 않는다. Phase 1 구현은 이름·검증·masking·status를 분리한다. DB schema 통일이나 multi-tenant migration은 범위 밖이다.

과거 cross-account fallback으로 발급돼 TTL이 남아 있을 수 있는 approval cache는 신뢰하지 않는다. Phase 1은 approval cache와 lock을 `v2` namespace로 전환해 구버전 값을 즉시 격리하고, cache miss 뒤 configured owner exact-match 검증을 다시 수행한다.

### 3-4. readiness와 자본 동작

- 실행 gate가 `READY`가 아니면 **신규 BUY만 차단**한다.
- SELL, cancel, broker reconciliation, REST execution backfill은 허용한다. 청산과 원장 회복을 readiness 결함 때문에 막지 않는다.
- REAL 주문은 기존 `MAGICJAR_MODE=REAL` AND `MAGICJAR_ALLOW_REAL=true` 이중 게이트를 그대로 요구하며, WS readiness는 그 위에 추가되는 BUY gate다. 기존 사용자 동의/리스크/kill-switch 게이트는 완화하지 않는다.
- 해당 plane의 실제 demand가 0이면 장애가 아니라 `IDLE/NOT_REQUIRED`다. 연결·구독을 억지로 만들거나 ERROR를 발생시키지 않는다.
- 상태는 market-data와 execution을 독립 필드로 공개한다. 시장 데이터 `READY`를 근거로 execution `READY`를 추론하지 않는다.
- execution ACK는 root header/body strict JSON 구조와 current generation을 원자적으로 검증한다. market subscription transition은 approval 지연 중 unsubscribe와 경쟁해 원격 유령 구독을 만들지 않도록 직렬화한다.
- realtime AES material snapshot과 tick/orderbook/execution dispatch는 connection generation 전진과 동일 monitor로 선형화한다. 최초 검사 뒤 reconnect가 시작된 old-generation frame은 새 generation에 side effect를 남기지 않고 drop한다.

### 3-5. rollback

rollback은 다음 안전 방향만 허용한다.

1. prod 시장 데이터(`REAL approval + ops:21000`)는 유지한다.
2. 실행 notice가 불확실하면 실행 gate를 `NOT_CONFIGURED` 또는 `REST_DEGRADED`로 내려 신규 BUY를 차단한다.
3. SELL/cancel/reconcile/backfill은 유지한다.
4. **REAL/prod approval + VPS endpoint 혼합을 복원하지 않는다.**
5. MOCK 전용 VPS 세션을 rollback 명목으로 즉석 도입하지 않는다.

---

## 4. 명시적 비결정 / Phase 1 범위 밖

- MOCK 전용 VPS `:31000` 두 번째 물리 세션 및 supervisor.
- MOCK `H0STCNI9` end-to-end 수신 복구.
- DB/Flyway credential identity migration 또는 multi-tenant 식별자 통일.
- WS bridge 범용 다중 transport 프레임워크 재작성.
- 주문·리스크·전략·slot priority·watchlist 정책 변경.
- 새로운 polling loop, Kafka topic, REST endpoint, STOMP topic.

전용 MOCK VPS 세션은 KIS appkey/session 동시성, approval cache scope, reconnect replacement, 실제 first-notice evidence를 갖춘 별도 ADR과 최고 관리자 승인이 있어야 한다.

---

## 5. 대안과 기각 사유

| 대안 | 판단 | 사유 |
|---|---|---|
| mode에 따라 단일 세션을 prod/VPS로 계속 전환 | 기각 | 시장 데이터 REAL-only 정책 위반, prod approval+VPS 혼합 재발 |
| prod 세션에서 MOCK `H0STCNI9` 구독 유지 | 기각 | 공식 TR 환경 구분과 어긋나며 live delivery 증거 없음. silent success 위험 |
| Phase 1에서 prod+VPS 물리 세션 두 개 도입 | 보류 | session/appkey 동시성·approval scope 미확정, 범위와 장애면 확대 |
| MOCK 실행 readiness를 시장 데이터 연결 상태로 간주 | 기각 | 데이터 plane 정상으로 실행 notice 결함을 숨기는 위험 |
| readiness 미충족 시 모든 주문/정합 차단 | 기각 | 신규 BUY fail-closed는 필요하지만 SELL/cancel/reconcile/backfill까지 막으면 자본 보호가 역전 |

---

## 6. 운영 양식

### 6-1. SLO 영향

- 시장 데이터: prod 세션 고정으로 KIS WS 연결률 SLO `>99%` 회복 방향.
- 실행: 단일 `connected` 대신 mode별 notice readiness를 분리해 거짓 READY를 제거한다. MOCK Phase 1은 의도적 `NOT_CONFIGURED`/`REST_DEGRADED`가 정상 degraded 상태다.
- 신호→주문 SLO는 READY인 REAL 경로만 측정한다. gate 차단 BUY는 지연 성공으로 계산하지 않는다.

### 6-2. 관측 매트릭스

최소 상태/evidence:

- market transport: endpoint class(`PROD`), connection, subscribe ACK, first H0UNCNT0 tick, slot occupied/available.
- execution notice: mode, TR ID, HTS ID configured(masked), subscribe attempted/ACK, first notice 또는 REST backfill health, readiness reason.
- 금지 metric/log: secret, approval key, full HTS ID, appkey.
- alert: demand가 있는데 market-data 또는 REAL execution이 READY가 아니면 WARN/P1 정책에 연결. demand 0 `IDLE/NOT_REQUIRED`는 alert하지 않는다.

### 6-3. 검증·배포 gate

- docs Phase 0에서는 validators만 실행하며 runtime 호출·배포를 하지 않는다.
- Phase 1 구현은 장마감 후 배포, staging 30분 dwell, REAL 주문 이중 게이트 확인 후 canary한다.
- HTTP 101을 완료 evidence로 쓰지 않는다. ACK + first H0UNCNT0 tick + REAL H0STCNI0 subscribe 상태를 각각 확인한다.

---

## 7. 결과

Phase 1의 목표 topology는 **prod 시장 데이터 세션 1개를 항상 살리고, REAL 실행 notice만 그 세션에 공유하며, MOCK 실행 notice는 거짓 구독하지 않고 명시적으로 degraded 보고**하는 것이다. 데이터 plane을 먼저 복구하고 실행 plane은 신규 BUY에만 fail-closed를 적용한다. 기존 시장 채널·slot·owner·청산 안전 규칙은 모두 유지한다.
