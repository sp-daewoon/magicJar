# KIS WebSocket Gate Recovery Phase 1 구현 spec

- 상태: **Accepted — 2026-07-16 최고 관리자 승인**
- 기준 ADR: [ADR 0142](../arch/adr/0142-kis-ws-gate-recovery.md)
- 구현 기준 SHA: `de24737e39c4838b918a87b699d8d76b173dc76f`
- KIS 공식 source pin: `koreainvestment/open-trading-api@885dd4e2f5c37e4f7e23dd63c15555a9967bc7bc`
- 범위: consumer + 문서. API/frontend/DB/Flyway/runtime/deploy는 제외.

## 1. 목표

현 단일 `KisWebSocketBridge`가 runtime mode에 따라 시장 데이터 socket까지 prod/VPS로 전환하는 결합을 해소한다.

1. 시장 데이터는 항상 REAL credential·REAL approval·공식 prod `ws://ops.koreainvestment.com:21000`에서 수집한다.
2. REAL execution notice는 같은 prod session에서 `H0STCNI0`을 사용한다.
3. MOCK/PAPER에서는 prod market session에 `H0STCNI9`을 보내지 않는다.
4. credential row owner와 KIS HTS ID를 별도 설정·상태로 관리한다.
5. 연결 여부가 아니라 matching subscribe ACK와 AES readiness로 REAL execution READY를 판정한다.
6. execution 미준비 시 신규 BUY만 막고 SELL·cancel·reconcile·backfill은 유지한다.
7. demand 0의 미연결은 장애가 아니라 `IDLE/NOT_REQUIRED`로 보고한다.

## 2. 비목표

- MOCK VPS `:31000` 전용 두 번째 물리 session
- MOCK `H0STCNI9` end-to-end 수신 복구
- DB credential identity migration
- API/frontend 상태 계약 변경
- owner priority, eviction, 39-slot 시장 예산 변경
- H0UNCNT0/H0UNASP0/H0UNMKO0/H0STOUP0 수요 조건 변경
- watchlist WS owner 복원 또는 신규 polling loop
- REAL 주문 활성화, 실제 주문 canary, 배포

## 3. 환경·identity 계약

```yaml
magicjar:
  kis:
    ws:
      url-prod: ${MAGICJAR_KIS_WS_URL_PROD:ws://ops.koreainvestment.com:21000}
      credential-owner-id: ${MAGICJAR_KIS_WS_CREDENTIAL_OWNER_ID:}
      hts-id: ${MAGICJAR_KIS_WS_HTS_ID:}
      user-id: ${MAGICJAR_KIS_WS_USER_ID:}
```

| 설정 | 의미 | fallback |
|---|---|---|
| `url-prod` | 시장 데이터 prod WS endpoint | 공식 `ws://ops.koreainvestment.com:21000` |
| `credential-owner-id` | `user_kis_credential` lookup owner | legacy `user-id` 허용 |
| `hts-id` | REAL `H0STCNI0` subscribe `tr_key` | **없음**. owner/user-id로 대체 금지 |
| `user-id` | 과거 credential owner 설정 | owner fallback only |

`url-prod`는 startup에서 scheme=`ws`, host=`ops.koreainvestment.com`, port=`21000`을 모두 검증한다. userInfo·비-root path·query·fragment는 approval key 외부 전송을 막기 위해 fail-closed로 거부한다.

Startup validator:

- scheme은 KIS 공식 `ws`만 허용한다.
- host는 `ops.koreainvestment.com`만 허용한다.
- port는 `21000`이어야 한다.
- userInfo·비-root path·query·fragment는 허용하지 않는다.
- 빈 credential owner 또는 HTS ID는 secret/config 오류를 출력하지 않고 상태를 `NOT_CONFIGURED`로 내린다.
- full owner ID, HTS ID, appkey, secret, approval key를 로그·snapshot에 출력하지 않는다.

## 4. Transport assignment matrix

| runtime mode | market approval/account | market endpoint | execution TR | Phase 1 action |
|---|---|---|---|---|
| REAL | REAL | prod `:21000` | `H0STCNI0` | explicit HTS ID가 있을 때 같은 session에 subscribe |
| MOCK | REAL | prod `:21000` | `H0STCNI9` | **발사 금지**, `NOT_CONFIGURED` |
| PAPER | REAL | prod `:21000` | `H0STCNI9` | **발사 금지**, `NOT_CONFIGURED` |

`getApprovalKey`는 market plane을 위해 항상 `AccountType.REAL`과 resolved credential owner exact-match를 사용한다. today-candle seed도 같은 owner만 사용하며 다른 REAL row로 fallback하지 않는다. VPS URL과 MOCK approval은 Phase 1 bridge에서 사용하지 않는다.

모든 채널의 subscribe/unsubscribe wire 계약은 KIS 공식 source pin 기준 `tr_type="1"` 등록, `tr_type="0"` 해제다. 과거 `"0"` 발사 직후 OPSP8997 관측은 `tr_id=null`이라 원인을 특정하지 못했고, `"2"` 변경 후 실제 해제 재현도 없으므로 현행 계약을 override하지 않는다.

## 5. Readiness state machine

### 5-1. Market plane

| required | connected | state | degradedReason |
|---|---:|---|---|
| false | any | `IDLE` | `NOT_REQUIRED` |
| true | true | `CONNECTED` | null |
| true | false | `DEGRADED` | `MARKET_SESSION_DISCONNECTED` |

`required`는 현재 체결가·호가·VI/시장상태·시간외 단일가 subscription demand 합집합으로 판정한다. `CONNECTED`는 물리 socket 연결만 뜻하며 remote subscribe ACK·first frame·parsed publish 성공을 뜻하지 않는다. Phase 1 consumer snapshot은 add-only이며 first-frame 상세는 후속 API/frontend phase에서 확장한다.

### 5-2. Execution plane

REAL `READY`는 모두 충족해야 한다.

1. prod market session connected
2. `H0STCNI0` subscribe emit 성공
3. ACK가 subscribe emit과 동일한 current connection generation에서 수신
4. ACK의 `(tr_id,tr_key)`가 `(H0STCNI0, configured hts-id)`와 일치
5. 성공 ACK의 AES IV/key가 UTF-8 16/32 byte이며 실제 decrypt map에 동일 값으로 저장

ACK는 한 번의 strict JSON parse에서 root `header.tr_id`, `header.tr_key`, `body.rt_cd`,
`body.output.iv/key`만 인정한다. 다른 위치의 동일 문자열·필드, malformed JSON은 readiness를 열지 않는다.
Execution subscribe attempt/current generation은 wire emit 전에 publish하고 emit 실패 시 동일 generation에서
rollback한다. 따라서 synchronous fast ACK도 유효하며, 실패한 emit은 거짓 attempted 상태를 남기지 않는다.

Disconnect, graceful reconnect, shutdown에서는 ACK/AES/attempt/generation state를 즉시 reset한다. generation 비교·terminal reset·disconnect·watchdog stop은 generation 증가와 같은 monitor에서 원자화한다. 미시도 응답, 이전 generation, 다른 종목·다른 HTS ID·다른 TR ACK는 readiness를 열지 않는다.

Market channel desired-state/slot add와 unsubscribe 제거, 최종 wire emit 및 reconnect replay snapshot→emit은 공통 transition lock으로 직렬화한다. approval 조회 중 demand가 제거되면 늦게 돌아온 subscribe는 wire emit하지 않으며, reconnect replay 도중 unsubscribe는 replay emit 뒤 해제되어 최종 remote/local 상태가 일치한다.

MOCK/PAPER Phase 1은 `NOT_CONFIGURED`, transport `NONE`, subscribed/ACK/AES false다. REST reconciliation 실제 health를 bridge가 증명하지 못하므로 credential owner 존재만으로 `REST_DEGRADED`를 선언하지 않는다.

## 6. 주문 admission

- 적용 대상: `OrderSide.BUY`, source `AUTO`와 `MANUAL` 모두.
- 허용: execution state가 정확히 `READY`.
- 거부: `NOT_CONFIGURED`, `DEGRADED`, bridge/status bean 부재, status 조회 예외.
- 거부 reason prefix: `EXECUTION_NOTICE_NOT_READY`.
- 거부는 기존 `handleRejected`·audit·Kafka reject·frequency compensation 경로를 재사용한다.
- denied BUY는 order_request REQUESTED 저장과 KIS submit을 수행하지 않는다.
- SELL은 execution notice readiness와 `ensureKisExecutionSync` 때문에 차단하지 않는다.
- cancel/reconcile/backfill 코드는 변경하지 않는다.
- KillSwitch, REAL mode gate, canary cap, 계좌 availability, risk gate는 완화하지 않는다.

## 7. Consumer snapshot add-only 계약

기존 필드를 삭제·rename하지 않고 아래 필드를 추가한다.

```text
market.required
market.state
market.degradedReason

executionNotice.state
executionNotice.transport
executionNotice.connected
executionNotice.ack
executionNotice.aes
executionNotice.degradedReason
```

기본값은 옛 payload 역직렬화와 rolling compatibility를 보장해야 한다. API/frontend는 Phase 1에서 이 필드를 아직 UI 권위로 사용하지 않는다.

## 8. 변경 경계

### market-data

- `KisWsTransportProperties` / configuration 등록
- `KisWebSocketBridge`
- `KisWsMessageParser` subscription channel 추출
- `RealtimeStatusSnapshotPublisher`
- `consumer/application.yml`
- focused unit tests

### backend-core

- consumer-local execution readiness guard
- `OrderRouterKafkaListener` BUY-only gate와 SELL sync bypass
- focused unit/integration tests

### 변경 금지

- domain strategy/risk 규칙
- API/frontend
- DB/Flyway
- Docker/runtime `.env`
- Kafka/STOMP topic
- WS slot ledger와 owner router

## 9. 테스트 매트릭스

### Transport/config

- REAL/MOCK/PAPER 모두 market URL은 prod `:21000`
- 체결가·호가·VI·시간외·체결통보 unsubscribe 모두 `tr_type="0"`
- `https`, `wss`, non-21000 URL startup reject
- explicit credential owner가 legacy owner보다 우선
- legacy owner는 credential lookup fallback only
- owner가 HTS ID로 fallback하지 않음
- repository 순서와 무관하게 WS approval·today-candle seed owner exact-match
- configured owner REAL row가 없거나 repository가 부재하면 today-candle seed REST 호출 0
- approval 지연 중 unsubscribe된 tick/호가/VI/시간외 demand의 late subscribe emit 0
- approval HTTP 실패 로그에 raw response body·credential·approval content 0
- H0UNCNT0·H0UNASP0·H0UNMKO0·H0STOUP0·H0STCNI0 outbound unsubscribe가 각각 `tr_type="0"`
- strict root `body.rt_cd="0"` + root `msg_cd=OPSP0002` 또는 `msg1=UNSUB*`인 ACK만 일반 subscribe success보다 먼저 graceful latch 해제
- market demand 0이고 현재-generation H0STCNI0만 남은 destroy도 execution unsubscribe 후 ACK 종료

### Execution subscription

- MOCK/PAPER H0STCNI9 emit 0
- REAL + HTS ID 없음 H0STCNI0 emit 0
- REAL + HTS ID 있음 H0STCNI0 emit 1
- wrong TR/wrong tr_key ACK readiness 변화 0
- root header/body 밖의 decoy 성공·TR/key/AES 필드는 readiness 변화 0
- emit과 동시에 도착한 matching fast ACK는 READY, emit 실패는 attempt rollback
- subscribe 미시도 또는 이전 connection generation ACK readiness 변화 0
- matching ACK without AES READY false
- matching ACK + 잘못된 AES byte length READY false
- matching ACK + AES READY true
- disconnect/reconnect/shutdown reset

### Status

- demand 0 → market IDLE/NOT_REQUIRED
- demand > 0 + connected → CONNECTED (streaming READY 주장 금지)
- demand > 0 + disconnected → DEGRADED
- MOCK execution → NOT_CONFIGURED/NONE/ACK false/AES false
- 기존 snapshot payload 역직렬화 호환
- 신규 `market`/`executionNotice`와 `slots.codesByTrId`가 없는 legacy JSON의 default 역직렬화

### Order

- BUY READY만 진행
- AUTO/MANUAL BUY NOT_READY 거부
- bridge/status 부재·예외 BUY 거부
- denied BUY KIS submit 0, audit/reject/ack 1
- SELL은 readiness와 sync 부재에도 기존 청산 경로 진행
- 다른 mode/kill-switch/canary/account gates 회귀 0

## 10. 검증 gate

PR 전 필수:

```bash
./gradlew :consumer:ktlintCheck
./gradlew :consumer:check
./gradlew check
npm run verify:docs
npm run verify:architecture
npm run test:architecture
```

변경 전 baseline과 변경 후 결과를 분리 기록한다. 테스트 삭제, skip, assertion/timeout 약화는 금지한다.

## 11. 배포·canary stop condition

본 PR은 배포하지 않는다. 후속 배포 승인이 있더라도 아래가 하나라도 없으면 중단한다.

- 승인 Git SHA = image revision = actuator build SHA
- clean provenance와 single consumer proof
- inflight/open position/pending BUY 확인
- `MAGICJAR_MODE=MOCK`, `MAGICJAR_ALLOW_REAL=false`
- 신규 주문 admission 닫힘
- rollback image와 REST reconciliation 증거

No-order market canary는 prod handshake, matching market subscribe ACK, first H0UNCNT0 frame, first parsed tick publish, demand 제거 후 slot 0을 순서대로 확인한다. HTTP 101이나 local slot count만으로 PASS를 선언하지 않는다.

## 12. Rollback

1. 신규 BUY admission을 닫는다.
2. execution state를 `NOT_CONFIGURED`로 유지한다.
3. prod market session은 유지한다.
4. SELL/cancel/reconcile/backfill을 유지한다.
5. `REAL approval + VPS endpoint` 혼합으로 되돌리지 않는다.
6. VPS dedicated session은 별도 ADR·credential·canary 승인 전 도입하지 않는다.
