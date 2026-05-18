# 주문 path 안정성 종합 spec — 외부 코드 리뷰 13 코멘트 흡수 + P0·P1·P2 트랙

> **발행일**: 2026-05-18 (KST)
> **발행 actor**: architect (수석 설계자)
> **트리거**: 사용자(최고 관리자) 외부 코드 리뷰 2건 수신 → 13 코멘트(중복 제거 11 의제) → 현 시스템 검증 → P0·P1·P2 단계적 트랙 발행 명시
> **상태**: Proposed (사용자 결재 후 sub-agent 디스패치)
> **ADR 동반**: 0084 "주문 path 안정성 종합 fitness gap fix (P0·P1·P2 트랙)" (Proposed)
> **rollback**: 본 spec PR `git revert` 단일 — 문서 only, 운영 코드 0 touch

---

## 0. Executive Summary

| 카테고리 | 건수 | 의제 # |
|---|---|---|
| ✅ 충족 | 2 | #1 (idempotency UNIQUE) · #5 (부분체결 SUM 가드) |
| 🟡 부분 충족 | 5 | #2 (outbox — Slack only) · #3 (5xx 1회 retry — reconcile 0) · #6 (취소만, 정정 0) · #8 (STOMP 즉시 publish) · #10 (FSM 1차 stub) |
| ❌ 미충족 | 4 | #4 (WS race·backfill) · #7 (DLQ) · #9 (MDC) · #11 (hashkey) |

**P0 (즉시 — 자본 잠재 risk)**: #4 · #3 · #11 · #7
**P1 (단기 — 정합성·관측성)**: #2 · #8 · #9
**P2 (중기 — UX·운영 편의)**: #6 · #10

**v3.x cut 시점 권장** (사용자 메모리 `feedback_release_policy_patch_first` 정합):
- P0 마감 → **v3.6.16** patch
- P1 마감 → **v3.6.17** patch
- P2 마감 → **v3.6.18** patch (또는 W4 진입 직전 묶음으로 v3.7.0 직전 마감)

---

## 1. 컨텍스트 — 외부 코드 리뷰 13 코멘트

### 1-1. 사용자 의제 (2026-05-18)

사용자가 외부 코드 리뷰 2건을 받음. 13 코멘트(중복 제거 11 의제). magicJar 현 시스템 grep·코드 정독 검증 결과:

| # | 의제 | 현재 상태 | gap evidence |
|---|---|---|---|
| 1 | 멱등성 (idempotency_key UNIQUE) | ✅ 충족 | `order_request.uq_order_idempotency` (Flyway V20) |
| 2 | DB↔Kafka Transactional Outbox | 🟡 부분 | `notification_outbox` (Slack only — ADR 0070 §2-3) · order publish outbox 통과 0건 |
| 3 | KIS REST 타임아웃·실패 재시도 | 🟡 부분 | `OrderRouterKafkaListener.submitWithRetry` 5xx 1회 retry (line 374~406) · PENDING_RECONCILE 상태 0건 + KIS 주문조회 reconciliation 0건 |
| 4 | WS 체결 race + 재연결 backfill | ❌ 미충족 | `grep "inquire-ccnl\|inquire_ccnl\|inquireCcnl" consumer/ api/ domain/` = 0건 · KisExecutionWsListener 재연결 시 backfill 0건 |
| 5 | 부분체결 누적 가드 (SUM≥qty → FILLED) | ✅ 충족 | `OrderExecutionRepository.sumExecutedQuantityByOrder` + PARTIALLY_FILLED→FILLED 가드 (KisExecutionWsListener §14-6 LOCK) |
| 6 | 취소/정정 흐름 | 🟡 부분 | `OrderCancelController` 합류 (취소만) · `KisOrderHttpAdapter.cancel` line 201 `RVSE_CNCL_DVSN_CD="02"` (취소 hard-code) · 정정 0건 |
| 7 | DLQ / Kafka 재시도 정책 | ❌ 미충족 | `grep "DefaultErrorHandler\|RetryableTopic\|DeadLetterPublishing" consumer/ api/` = 0건 · `OrderRouterKafkaListener` line 106 "// DLQ는 backlog" + line 267~268 명시 |
| 8 | STOMP 알림 영속 (재접속 push) | 🟡 부분 | NotificationOutbox 합류이나 STOMP 즉시 publish (NotificationDispatcher) — 재접속 unread fetch 0건 |
| 9 | 분산 추적 traceId/MDC | ❌ 미충족 | `grep "MDC\|traceId" consumer/src/main api/src/main` = 0건 — 사고 추적 cost ↑ |
| 10 | 시간 컨텍스트 (장 휴장·토큰 mutex) | 🟡 부분 | `MarketSessionFsm` 1차 stub (ADR 0013) · token mutex 0건 (KIS OAuth 토큰 동시발급 race risk) |
| 11 | KIS hashkey 헤더 | ❌ 미충족 | `grep "hashkey\|HashKey\|hash_key" consumer/ api/ domain/` = 0건 — KIS POST 주문 hashkey 의무 여부 raw 검증 필요 |

### 1-2. 종합

- ✅ 2건 충족 + 🟡 5건 부분 + ❌ 4건 미충족 = 운영 안정성 의제 큼
- T-O 트랙(ADR 0068) Phase 1·2·3 완료 + T-RECON+NOTIFY(ADR 0070) baseline 완료 시점이나, **WS race·재연결 backfill·DLQ·hashkey·MDC** 4건이 자본 잠재 risk 또는 운영 관측성 결함
- 사용자 결정: **P0·P1·P2 단계적 트랙 발행 + sub-agent dispatch**

### 1-3. fitness gap 검증 (사용자 메모리 `feedback_v3_skepticism_no_regression` lens)

**자동매매에 필요한가?**
- #4 WS race + backfill: ★★★ — 중복 주문 또는 체결 누락 risk → 자본 직접 영향
- #11 hashkey: ★★★ — KIS API 의무 요건 시 현재 주문 자체 reject 가능 (REAL 진입 즉시 차단)
- #3 PENDING_RECONCILE: ★★★ — 5xx 후 ACK 미수신 시 주문 상태 unknown → 운영 audit 깨짐
- #7 DLQ: ★★ — 무한 재시도 시 KIS rate limit 초과 + Kafka offset 정체
- #9 MDC: ★★ — 사고 발생 시 traceId 0건이면 추적 cost ↑ (운영 비용)
- #2 outbox: ★ — order publish 누락 시 STOMP 알림 누락 (사용자 UX 결함, 자본 영향 X)
- #6 정정: ★ — 사용자 UX 보강 (자본 영향 X)
- #10 FSM·token mutex: ★ — 휴장 시 cron 낭비 + token race (현재 KIS rate limit hit 가능)

**v1/v2 잔재 아닌가?**
- 11 의제 모두 v3 본체 path — v1/v2 잔재 0건. 전부 자동매매 본체 의제.

---

## 2. 의제별 명세 (11건)

각 의제 명세 구조:
- **현재 상태 진단**: code·DB·grep evidence 인용
- **gap 본문**: 외부 코드 리뷰 코멘트 흡수 + magicJar 정합 lens
- **fix 안**: 변경 파일·DB schema·도메인·DTO·KIS-first raw URL
- **영향 ADR**: 0068·0070·0077·0042 등 superset
- **sub-agent dispatch**: 영역·우선순위
- **rollback**

### §2-1. 의제 #4 — WS 체결 race + 재연결 backfill (P0)

**현재 상태 진단**:
- `KisExecutionWsListener.processMessage` (§14-6 LOCK) — H0STCNI0/H0STCNI9 수신 시 `findByKisOrderNo(ODER_NO)` 후 OrderExecution INSERT
- gap: REST `submit` ACK 응답보다 WS 체결 통보가 먼저 도달 시 → `OrderRequestRepository.findByKisOrderNo`가 null → 체결 row 영속 skip (현재 line 91~99 graceful skip)
- gap: KIS WS 재연결 직후 (예: OPSP8996 ERROR 후 재구독) 누락된 체결 통보 backfill 0건
- evidence: `grep "inquire-ccnl\|inquire_ccnl\|inquireCcnl"` = 0건

**gap 본문**:
- **race**: ACK 처리(`OrderRouterKafkaListener.updateKisAck`)가 WS 체결 통보(`KisExecutionWsListener.processMessage`)보다 늦으면 → 체결 row 영속 skip → `sumExecutedQuantityByOrder` 계산 누락 → FILLED 전환 안 됨 → 운영 audit 깨짐
- **backfill**: KIS WS 재연결 시점(ADR 0077 §3-2·§3-3 정합)에서 누락된 체결 통보 보강 0건 — 재구독 사이 체결 통보 손실 risk

**fix 안**:

1. **Upsert 패턴** (race fix):
   - `KisExecutionWsListener.processMessage` — `findByKisOrderNo`가 null이면 **pending_execution_buffer** 임시 보관(Redis) + 30s TTL
   - `OrderRouterKafkaListener.updateKisAck` — kisOrderNo set 직후 Redis pending 조회 → 있으면 즉시 OrderExecution INSERT 흡수
   - 또는 **OrderRequest pre-insert** (kisOrderNo NULL 허용 + 별도 인덱스) → ACK 시 update + WS는 항상 findByKisOrderNo OR findByOrderId(intent 추적)
2. **재연결 backfill** (KIS-first raw 인용):
   - KIS WS 재연결 직후 `KisWebSocketBridge.onReconnect` 콜백 → consumer 모듈에서 `inquire-ccnl` REST 호출 (당일 체결 내역 조회) → OrderExecution upsert (event_id UNIQUE로 dedup)
   - raw 출처: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_ccnl/inquire_ccnl.py`
   - tr_id: 일별 주문체결 조회 — `TTTC8001R` (실전) / `VTTC8001R` (모의)

**변경 영역**:
- 도메인: `OrderExecution` (eventId UUID 필드 추가 — dedup 키), `OrderExecutionRepository.upsertByEventId`
- DB: Flyway V35 — `order_execution.event_id UUID UNIQUE` 컬럼 추가 + idx
- consumer: `KisExecutionWsListener.processMessage` upsert 분기 + `KisOrderReconciliationBackfillService` 신규
- consumer: `KisWebSocketBridge.onReconnect` 콜백 hook
- consumer: `KisOrderInquireCcnlHttpAdapter` 신규 (KIS REST 일별 체결조회 어댑터)
- redis: `pending:execution:{kisOrderNo}` 30s TTL (선택)

**영향 ADR**: 0068 (T-O §14-6 LOCK 후속 보강) · 0077 (OPSP8996 재연결 ↔ backfill 결합) · 0070 (RECON 정합 — 별도 reconcile run으로 발사 가능)
**KIS-first raw 인용 의무**: inquire-ccnl raw URL (위 본문)
**sub-agent dispatch**: market-data (consumer/ws + consumer/order 영역, HIGH)
**rollback**: backfill 비활성 toggle `magicjar.order.ws-backfill.enabled=false` (default true) — 단일 commit revert로 rollback

---

### §2-2. 의제 #3 — KIS REST 타임아웃 → PENDING_RECONCILE + reconciliation worker (P0)

**현재 상태 진단**:
- `OrderRouterKafkaListener.submitWithRetry` (line 374~406) — 5xx 1회 retry (RETRY_BACKOFF_MS=500ms)
- `KisOrderHttpAdapter.submit` (line 167~178) — WebClientResponseException 5xx throws (caller retry 책임)
- gap: 5xx 1회 retry 후 동일 실패 → `OrderResult.Left(DenyReason.KisException("KIS_5XX:..."))` → REJECTED 처리 (line 400)
- **결함**: KIS 응답 timeout 또는 ConnectionReset인 경우 — KIS 측은 주문 접수했을 수 있으나 클라이언트는 ACK 미수신 → REJECTED 마킹 → 실제 KIS에 주문 잔존 시 **자본 audit 깨짐**

**gap 본문**:
- 외부 코드 리뷰: "타임아웃·실패 시 reconciliation 호출로 실제 KIS 상태 검증 필수"
- 현 path는 timeout · 5xx 재시도 후 실패 → REJECTED 즉시 결정 → KIS 실제 상태 검증 0
- evidence: `grep PENDING_RECONCILE` = 0건

**fix 안**:

1. **OrderStatus 신규 enum 값**: `PENDING_RECONCILE` (도메인 추가)
   - timeout 또는 5xx 재시도 후 실패 → REJECTED 대신 PENDING_RECONCILE 마킹
2. **KIS 주문조회 어댑터** (KIS-first raw 인용):
   - `KisOrderInquireDailyCcldHttpAdapter` 신규 — `/uapi/domestic-stock/v1/trading/inquire-daily-ccld`
   - raw 출처: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_ccld/inquire_daily_ccld.py`
   - tr_id: 실전 `TTTC8001R` / 모의 `VTTC8001R` (또는 `CTSC9215R`)
3. **OrderReconciliationWorker** 신규 (ADR 0070 §2-9 cron 매트릭스 superset):
   - cron `0 */1 * * * *` (1분 주기) — PENDING_RECONCILE 상태 order_request 조회
   - 각 row마다 inquire-daily-ccld 호출 → KIS 실제 상태 비교
   - 매칭: ACK 또는 FILLED 상태 발견 시 → 적절한 status로 transition + KIS_RECONCILE_RESOLVED audit
   - 비매칭(15분 timeout): REJECTED + KIS_RECONCILE_TIMEOUT audit + ADR 0044 P0 알림 발사

**변경 영역**:
- 도메인: `OrderStatus.PENDING_RECONCILE` enum 추가, `OrderAuditEventType.KIS_TIMEOUT·KIS_RECONCILE_RESOLVED·KIS_RECONCILE_TIMEOUT`
- DB: Flyway V36 — `order_request.status` enum check constraint 갱신 + `idx_order_request_pending_reconcile` partial index
- consumer: `KisOrderInquireDailyCcldHttpAdapter` 신규 + `OrderReconciliationWorker` 신규
- consumer: `OrderRouterKafkaListener.submitWithRetry` — timeout/5xx 재실패 시 PENDING_RECONCILE 분기

**영향 ADR**: 0068 (T-O §14-2 step 7·8 superset) · 0070 (RECON cron 매트릭스 §2-9 superset) · 0041 (broker reconciliation 패턴 정합)
**KIS-first raw 인용 의무**: inquire-daily-ccld raw URL
**sub-agent dispatch**: market-data + backend-core 협업 (consumer/order + Flyway V36 + domain OrderStatus, HIGH)
**rollback**: `magicjar.order.pending-reconcile.enabled=false` toggle (default true) — disabled 시 기존 REJECTED 즉시 결정 path 복귀

---

### §2-3. 의제 #11 — KIS hashkey 헤더 (P0)

**현재 상태 진단**:
- `KisOrderHttpAdapter.submit/cancel` — `appkey`·`appsecret`·`tr_id`·`authorization`·`custtype` 헤더 set (line 118~123, 248~253)
- **`hashkey` 헤더 부재** (`grep hashkey` = 0건)

**gap 본문**:
- KIS 공식 example_user에서 POST 주문 API 호출 시 hashkey 헤더 의무 여부 확인 필요
- KIS open-trading-api raw 분석: `getHashKey` 함수 — POST body를 KIS hashkey endpoint로 보내고 받은 hash를 헤더에 set
- **현재 매수/매도 주문이 KIS REJECT 받을 가능성** (REAL 진입 즉시 운영 차단 risk)

**fix 안**:

1. **KIS-first raw 검증 의무** (architect 본 spec 발행 단계에서 raw URL 명시 + sub-agent 구현 시 본문 확인):
   - raw URL: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/auth/auth.py` (getHashKey 함수)
   - 또는 `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/auth/auth.py`
   - 검증 1단계: KIS 매수/매도(TTTC0012U·TTTC0011U) POST 요청 시 hashkey 의무 여부 — KIS API 문서 본문 또는 examples 확인
2. **구현 (의무 시)**:
   - `KisHashKeyAdapter` 신규 — POST `/uapi/hashkey` endpoint 호출 → body JSON을 hash로 변환
   - `KisOrderHttpAdapter.submit/cancel` — body buildOrderCashBody 직후 hashkey 발급 → `hashkey` 헤더 추가
3. **구현 (불필요 시)**:
   - 본 의제 close + 향후 reference 보존을 위해 KDoc 본문에 "hashkey 미사용 사유" 명시 (raw URL 인용)

**변경 영역**:
- consumer: `KisHashKeyAdapter` 신규 (또는 기존 `KisAuthAdapter` 보강)
- consumer: `KisOrderHttpAdapter.submit/cancel` — hashkey 헤더 추가
- application.yml: `magicjar.kis.hashkey-path=/uapi/hashkey` (configurable)

**영향 ADR**: 0068 (T-O §14-5 KIS REST 어댑터 superset)
**KIS-first raw 인용 의무**: auth.py getHashKey + 매수/매도 raw (domestic_stock_functions.py L10097~L10213)에서 hashkey 사용 확인
**sub-agent dispatch**: market-data (consumer/order, HIGH — REAL 진입 즉시 차단 risk)
**rollback**: hashkey 헤더 add-only — disabled 시 기존 path 정합. 단일 commit revert로 rollback

---

### §2-4. 의제 #7 — DLQ / Kafka 재시도 정책 (P0)

**현재 상태 진단**:
- `OrderRouterKafkaListener.onOrderIntent` (line 96~268) — try-catch 최외곽 unexpected 예외 시 **ack 보류** (line 266~268 `자본 path이므로 unexpected 예외는 ack 보류 — Kafka가 재처리`)
- gap: 재처리 무한 loop 시 KIS rate limit (ADR 0037) 초과 + Kafka offset 정체 + 자본 path block
- evidence: `grep "DefaultErrorHandler\|RetryableTopic\|DeadLetterPublishing"` = 0건
- line 106 명시 `// DLQ는 backlog`, line 267 명시 `1회 retry 후 동일 실패 시 운영 알림(backlog DLQ) 필요`

**gap 본문**:
- 외부 코드 리뷰: "Spring Kafka DefaultErrorHandler + DeadLetterPublishingRecoverer로 N회 재시도 후 DLT 토픽으로 격리"
- 현 path는 무한 재시도 risk + 운영 알림 0

**fix 안**:

1. **DefaultErrorHandler + DLT 패턴**:
   - consumer Kafka config 신규 — `DefaultErrorHandler` (FixedBackOff 1s · 최대 3회) + `DeadLetterPublishingRecoverer`
   - DLT 토픽: `order.intent.v1.DLT` · `signal.emitted.v1.DLT` · `tick.v1.DLT` · `ws.execution.v1.DLT` (4건)
2. **DLT 모니터링 worker**:
   - `OrderDltMonitorListener` — `order.intent.v1.DLT` listen → audit + ADR 0044 P0 알림 즉시 발사 (자본 path이므로 P0)
3. **poison pill 분기 정합**:
   - 기존 line 106 `deserialize null → ack.acknowledge() // poison pill 회피` — 본 fix 후 DLT publish + audit + P0 알림으로 강화

**변경 영역**:
- consumer: `OrderConsumerErrorHandlerConfig` 신규 — DefaultErrorHandler bean
- consumer: `OrderDltMonitorListener` 신규
- api: Kafka topic 생성 — `order.intent.v1.DLT` (KafkaAdmin bean 또는 자동 create.topics.enable=true)
- application.yml: `spring.kafka.listener.default-error-handler.*` 설정

**영향 ADR**: 0068 (T-O §14-2 step 11·12 superset) · 0044 (notification P0 정합)
**KIS-first raw 인용 의무**: 없음 (Spring Kafka native 패턴)
**sub-agent dispatch**: backend-core (consumer + api Kafka config, HIGH)
**rollback**: DLT 토픽 ack 보류 path 복귀 (toggle `magicjar.kafka.dlt.enabled=false` default true)

---

### §2-5. 의제 #2 — order Kafka publish Transactional Outbox (P1)

**현재 상태 진단**:
- `NotificationOutboxJpaEntity` (Slack 한정) — outbox 패턴 baseline 합류 (ADR 0070)
- `OrderRequestedKafkaProducer`·`OrderRejectedKafkaProducer`·`OrderFilledKafkaProducer`·`OrderCancelledKafkaProducer` — 직접 Kafka publish (DB tx와 분리)
- gap: DB INSERT/UPDATE 성공 후 Kafka publish 직전 crash 시 → DB는 commit but Kafka는 미발행 → STOMP 알림 누락 + downstream listener 누락
- evidence: order outbox 통과 0건

**gap 본문**:
- 외부 코드 리뷰: "DB↔Kafka Transactional Outbox로 at-least-once 보장"
- 현 path는 DB commit + Kafka publish 사이 race risk

**fix 안**:

1. **OrderOutbox 패턴 확장** (NotificationOutbox 보강):
   - 신규 테이블 `order_event_outbox` — Flyway V37
     - `event_id UUID UNIQUE` (멱등 키)
     - `topic VARCHAR(64)` — order.requested.v1·order.rejected.v1·order.filled.v1·order.cancelled.v1
     - `partition_key VARCHAR(64)` (userId)
     - `payload JSONB`
     - `status VARCHAR(16)` — PENDING·PUBLISHED·FAILED
     - `created_at`·`published_at`·`retry_count`·`last_error`
   - 의제 #2 패턴 — `OrderEventOutboxRepository.save` + tx 내 commit → 별도 worker가 PENDING → Kafka publish → PUBLISHED
2. **OrderOutboxPublisherWorker** 신규 (poll 100ms cycle — 부담 시 1s 절충):
   - PENDING row 조회 → Kafka publish → status update
3. **Outbox 통합 — 4 publisher 모두 outbox 통과**:
   - `OrderRequestedKafkaProducer.publish` → `orderEventOutbox.save(REQUESTED_TOPIC, payload)` + tx commit
   - 직접 Kafka publish 0건 (자본 path 4 producer)

**변경 영역**:
- 도메인: `OrderEventOutbox` (도메인 추가 — pure Kotlin), `OrderEventOutboxRepository`
- DB: Flyway V37 — `order_event_outbox` 테이블
- api: `OrderEventOutboxJpaEntity`·`OrderEventOutboxRepositoryAdapter`·`OrderOutboxPublisherWorker`
- consumer: 4 producer 모두 outbox 통과로 전환

**영향 ADR**: 0068 (T-O §14-3 Kafka publish 정합) · 0070 (notification outbox 패턴 superset) · 0007 (Kafka backbone 정합)
**KIS-first raw 인용 의무**: 없음
**sub-agent dispatch**: backend-core (api/messaging + Flyway V37, MED)
**rollback**: 4 producer 직접 publish path 복귀 (toggle `magicjar.order.outbox.enabled=false` default true)

---

### §2-6. 의제 #8 — STOMP 알림 영속 + 재접속 unread push (P1)

**현재 상태 진단**:
- `NotificationDispatcher` (consumer) — STOMP 즉시 publish + NotificationOutbox row INSERT (Slack 영속)
- gap: STOMP 클라이언트(frontend)가 disconnect 상태일 때 publish된 메시지는 손실 — 재접속 시 backfill 0건
- evidence: NotificationOutbox는 SLACK 채널 한정 (NotificationOutboxJpaEntity line 21 `SLACK·(FUTURE: EMAIL·SMS·PUSH·DISCORD)`)

**gap 본문**:
- 외부 코드 리뷰: "STOMP 즉시 publish + at-least-once 보장 미흡 — 재접속 시 unread fetch 필요"
- 현 path는 frontend 재접속 시 마지막 N건 또는 unread 조회 0건

**fix 안**:

1. **NotificationOutbox 채널 확장** (STOMP_USER 채널 추가):
   - 신규 채널: `STOMP_USER` — userId별 STOMP queue
   - status: `PENDING·DELIVERED·READ` (READ는 사용자 명시 클릭)
2. **REST endpoint 신규**:
   - `GET /api/notifications/unread?userId={id}&limit=50` — frontend 재접속 시 호출
   - `POST /api/notifications/{id}/read` — 사용자 클릭 시 READ 전환
3. **frontend toast/모달**:
   - WebSocket onConnect → unread 조회 → 큐잉 모달 또는 toast 순차 표시

**변경 영역**:
- DB: Flyway V38 — `notification_outbox.channel` enum 'STOMP_USER' 추가 + `notification_outbox.user_id` 컬럼 + idx
- 도메인: `NotificationChannel.STOMP_USER` enum
- api: `NotificationOutboxQueryController` 신규 (REST)
- consumer: `NotificationDispatcher` — STOMP publish 직전 outbox INSERT
- frontend: WebSocket onConnect hook + unread fetch

**영향 ADR**: 0044 (notification baseline superset) · 0070 (outbox 패턴 superset)
**KIS-first raw 인용 의무**: 없음
**sub-agent dispatch**: backend-core + frontend (api/messaging + frontend WebSocket hook, MED)
**rollback**: STOMP_USER 채널 비활성 toggle (default true) — disabled 시 즉시 publish path 복귀

---

### §2-7. 의제 #9 — MDC traceId 분산 추적 (P1)

**현재 상태 진단**:
- `grep "MDC\|traceId" consumer/src/main api/src/main` = 0건
- gap: 사고 발생 시 STOMP→Kafka→consumer→KIS REST→KIS WS 흐름 전체 traceId 0 → 사고 추적 cost 매우 큼

**gap 본문**:
- 외부 코드 리뷰: "분산 추적 traceId/MDC + Kafka header propagation"
- 현 path는 log line별 userId·signalId 부분 보유이나 cross-service 단일 traceId 0

**fix 안**:

1. **TraceIdFilter** (api Servlet Filter):
   - HTTP request 진입 시 X-Trace-Id 헤더 추출 또는 UUID 생성 → MDC.put("traceId", id)
   - response 직전 MDC.clear()
2. **Kafka header propagation**:
   - producer — record header `X-Trace-Id` 추가
   - listener — record header 추출 → MDC.put
3. **KIS REST 호출 시 propagation**:
   - WebClient.filter — MDC traceId → `X-Trace-Id` header 추가 (KIS는 무시하나 magicJar 내부 audit는 유지)
4. **logback pattern 갱신**:
   - `logback-spring.xml` — `%X{traceId}` MDC 추출 포함

**변경 영역**:
- api: `TraceIdServletFilter` 신규
- consumer + api: Kafka header propagation (ProducerInterceptor + ConsumerRecord MDC populator)
- api·consumer: `logback-spring.xml` MDC pattern 추가

**영향 ADR**: 0008 (docker MSA 표준 superset — 운영 관측성)
**KIS-first raw 인용 의무**: 없음
**sub-agent dispatch**: backend-core (api·consumer Filter + Kafka header, MED)
**rollback**: TraceIdFilter beans removal — log pattern 그대로 두면 traceId 값 빈 문자열 (운영 영향 0)

---

### §2-8. 의제 #6 — 주문 정정 흐름 (P2)

**현재 상태 진단**:
- `KisOrderHttpAdapter.cancel` (line 201~291) — `RVSE_CNCL_DVSN_CD="02"` (취소만, hard-code line 238)
- `OrderCancelController` (api) — DELETE endpoint 합류
- gap: 정정(`RVSE_CNCL_DVSN_CD="01"`) 0건

**gap 본문**:
- KIS API 정정 흐름은 취소와 동일 endpoint(`/uapi/domestic-stock/v1/trading/order-rvsecncl`) + `RVSE_CNCL_DVSN_CD="01"` + 신규 ORD_QTY·ORD_UNPR 본문
- 사용자 UX 보강 — 현재는 취소 후 재주문 (UX 결함)

**fix 안**:

1. **KisOrderHttpAdapter.modify** 신규:
   - 기존 cancel 함수와 유사하나 `RVSE_CNCL_DVSN_CD="01"` + ORD_QTY·ORD_UNPR 신규값
2. **OrderModifyController** 신규:
   - PATCH `/api/orders/{orderId}` — body {quantity, limitPrice}
3. **OrderRouterKafkaListener 또는 신규 listener**:
   - `order.modify.intent.v1` 토픽 추가 또는 기존 listener 분기

**변경 영역**:
- 도메인: `OrderModifyIntent`·`OrderAuditEventType.KIS_MODIFY_ACK`
- consumer: `KisOrderHttpAdapter.modify` 추가
- api: `OrderModifyController` 신규
- DB: order_audit_event 추가 row만 (스키마 변경 0)

**영향 ADR**: 0068 (T-O §14-5 정정/취소 superset — Phase 1은 취소만 명시)
**KIS-first raw 인용 의무**: domestic_stock_functions.py L10771~L10884 (def order_rvsecncl)
**sub-agent dispatch**: backend-core (api/controller/order + consumer/order, LOW)
**rollback**: PATCH endpoint 비활성 또는 단일 commit revert

---

### §2-9. 의제 #10 — MarketSessionFsm Gate 통합 + KIS token mutex (P2)

**현재 상태 진단**:
- `MarketSessionFsm` (ADR 0013) — 1차 stub
- gap: 휴장 시 cron 무차별 발사 → KIS rate limit 무의미 소비
- gap: KIS OAuth 토큰 발급은 ADR 0038 Redis distributed lock 합류이나, 만료 직전 동시 갱신 race risk 미검증

**gap 본문**:
- 외부 코드 리뷰: "장 휴장 / 시간 컨텍스트 + 토큰 동시 갱신 race"
- MarketSessionFsm Gate 통합 시 cron skip + KIS REST 호출 차단 → rate limit 절약

**fix 안**:

1. **MarketSessionFsmGate**:
   - 신규 AOP 또는 @ConditionalOnExpression — 휴장 시 cron job 스킵
   - 적용 대상: reconcile cron · candle ingestion cron · ws subscribe cron 등
2. **KIS Token Mutex 재검증**:
   - `KisAuthAdapter.getAccessToken` 본문 정독 — Redis SETNX 동시 발급 차단 정합 검증
   - 만료 직전 동시 갱신 시 stampede 회피 (lock TTL > 토큰 refresh latency)

**변경 영역**:
- consumer: `MarketSessionFsmGate` AOP 또는 condition
- consumer/api: 적용 대상 cron annotation 추가
- `KisAuthAdapter` — redis lock 본문 audit (변경 없을 수도 있음)

**영향 ADR**: 0013 (MarketSessionFsm baseline) · 0038 (KIS OAuth Redis lock baseline)
**KIS-first raw 인용 의무**: 없음 (auth는 ADR 0038 기존 LOCK)
**sub-agent dispatch**: market-data + backend-core 협업 (consumer/ws + KIS REST, LOW)
**rollback**: Gate 비활성 toggle (default true) — disabled 시 cron 무차별 path 복귀

---

### §2-10. 의제 #5 — 부분체결 SUM 가드 (✅ 충족 — close)

**현재 상태 진단**:
- `OrderExecutionRepository.sumExecutedQuantityByOrder` 합류
- `KisExecutionWsListener.processMessage` (§14-6 LOCK) — sum < qty → PARTIALLY_FILLED · sum == qty → FILLED · sum > qty → EXECUTION_OVERFLOW skip
- 충족 — 본 의제는 fix 없음

**액션**: 본 spec 본문에 evidence만 인용 + backlog close (의제 trace 보존)

---

### §2-11. 의제 #1 — idempotency_key UNIQUE (✅ 충족 — close)

**현재 상태 진단**:
- Flyway V20 — `order_request.uq_order_idempotency UNIQUE (idempotency_key, user_id)` (또는 동일 의미 UNIQUE)
- `OrderIdempotencyGuard` (Redis SETNX 60s) — listener level 1차 가드
- 충족 — 본 의제는 fix 없음

**액션**: 본 spec 본문에 evidence만 인용 + backlog close (의제 trace 보존)

---

## 3. P0·P1·P2 단계적 트랙 + sub-agent dispatch plan

### 3-1. P0 (즉시 — 자본 잠재 risk · 4 의제) — v3.6.16 patch

| 순서 | 의제 | sub-agent | 영역 | 우선순위 | 추정 PR 수 |
|---|---|---|---|---|---|
| P0-1 | #11 hashkey | market-data | consumer/order/KisHashKeyAdapter + KisOrderHttpAdapter | HIGH (REAL 진입 즉시 차단 risk) | 1 |
| P0-2 | #3 PENDING_RECONCILE | market-data + backend-core | consumer/order + Flyway V36 + domain OrderStatus | HIGH | 2 (Flyway + 어댑터·worker) |
| P0-3 | #4 WS race + backfill | market-data | consumer/ws + consumer/order + Flyway V35 | HIGH | 2 (Flyway + WS race fix·backfill 어댑터) |
| P0-4 | #7 DLQ | backend-core | consumer Kafka config + DLT listener | HIGH | 1 |

**병렬 가능 매트릭스**:
- P0-1·P0-4 — 영역 충돌 0 (P0-1=KIS adapter / P0-4=Kafka config) → 병렬 OK
- P0-2·P0-3 — Flyway V35·V36·도메인 OrderStatus·OrderExecution 동시 변경 risk → **직렬 권장** (P0-2 → P0-3 순서, 또는 다른 sub-agent가 Flyway slot 합의 후 add-only)
- 사용자 메모리 `feedback_orchestrator_active_parallel` 정합 — orchestrator 영역 충돌 행렬 재검토 후 적극 병렬화 가능

### 3-2. P1 (단기 — 정합성·관측성 · 3 의제) — v3.6.17 patch

| 순서 | 의제 | sub-agent | 영역 | 우선순위 | 추정 PR 수 |
|---|---|---|---|---|---|
| P1-1 | #2 order outbox | backend-core | api/messaging + Flyway V37 + 4 producer 통합 | MED | 2 |
| P1-2 | #8 STOMP 영속 | backend-core + frontend | api/messaging + Flyway V38 + frontend WebSocket hook | MED | 2 (backend + frontend) |
| P1-3 | #9 MDC traceId | backend-core | api·consumer Filter + Kafka header + logback | MED | 1 |

**병렬 가능 매트릭스**:
- P1-1·P1-3 — 영역 충돌 0 (outbox는 Producer 단 / MDC는 Filter·logback) → 병렬 OK
- P1-2 — frontend 영역이 backend-core 어댑터 합류 후 진행 → **직렬** (P1-2 backend 합류 → frontend hook)

### 3-3. P2 (중기 — UX·운영 편의 · 2 의제) — v3.6.18 patch (또는 W4 진입 직전 묶음)

| 순서 | 의제 | sub-agent | 영역 | 우선순위 | 추정 PR 수 |
|---|---|---|---|---|---|
| P2-1 | #6 정정 | backend-core | api/controller/order + consumer/order | LOW | 1 |
| P2-2 | #10 MarketSession + token mutex | market-data + backend-core | consumer/ws + KIS REST | LOW | 1~2 |

**병렬 가능 매트릭스**:
- P2-1·P2-2 — 영역 충돌 0 → 병렬 OK

### 3-4. 종합 dispatch plan 매트릭스

| Phase | sub-agent | 점유 영역 | PR 수 추정 |
|---|---|---|---|
| P0 | market-data | consumer/order + consumer/ws + KisOrderHttpAdapter + hashkey 어댑터 + WS backfill 어댑터 + inquire-daily-ccld 어댑터 + inquire-ccnl 어댑터 | 4~5 |
| P0 | backend-core | consumer Kafka config + DLT listener + Flyway V35·V36 (market-data 협업) + domain OrderStatus 보강 | 1~2 |
| P1 | backend-core | api/messaging + Flyway V37·V38 + outbox worker + MDC Filter | 3~4 |
| P1 | frontend | WebSocket onConnect hook + unread fetch | 1 |
| P2 | backend-core | OrderModifyController + KisOrderHttpAdapter.modify + MarketSessionFsmGate | 1~2 |
| P2 | market-data | KisAuthAdapter token mutex 재검증 (변경 없을 수도 있음) | 0~1 |

**총 PR 수 추정**: 10~14건 (사이클 3건 — v3.6.16·v3.6.17·v3.6.18)

---

## 4. v3.x cut 시점 매트릭스

사용자 메모리 `feedback_release_policy_patch_first` 정합:
- v3.7.0 = W4 마감 (AI 트랙) — LOCK
- v3.8.0 = W5 마감 (LWC·LEARN) — LOCK
- 그 외 모든 변경 = patch

| 마감 시점 | 버전 | 트리거 의제 | 비고 |
|---|---|---|---|
| P0 완료 | v3.6.16 | #11·#3·#4·#7 마감 | 자본 잠재 risk 해소 — REAL 진입 가능 |
| P1 완료 | v3.6.17 | #2·#8·#9 마감 | 정합성·관측성 보강 |
| P2 완료 | v3.6.18 | #6·#10 마감 | UX·운영 편의 — (또는 W4 진입 직전 묶음으로 cut 생략 가능) |

**cut 직전 의무** (사용자 메모리 `feedback_docker_log_verify_before_cut` 정합):
- 각 사이클 tag cut 직전 `scripts/build-and-deploy.sh` 재배포
- 4 모듈(api·batch·consumer·frontend) docker logs 에러 검사 → 발견 시 hotfix → 0건 확인 후 cut

---

## 5. 영향 ADR 매트릭스 (superset 관계)

| ADR | 의제 | superset 관계 |
|---|---|---|
| 0068 (T-O) | #3·#4·#6·#7·#11 | §14-2·§14-5·§14-6 LOCK 후속 보강 — fitness gap fix |
| 0070 (T-RECON+NOTIFY) | #2·#3·#8 | outbox 패턴 + reconcile cron 매트릭스 + STOMP 채널 확장 |
| 0077 (OPSP8996) | #4 | WS 재연결 ↔ backfill 결합 |
| 0044 (notification) | #7·#8 | DLT P0 알림 + STOMP_USER 채널 |
| 0013 (MarketSessionFsm) | #10 | Gate 통합 |
| 0038 (KIS OAuth) | #10 | token mutex 재검증 |
| 0041 (broker reconciliation) | #3 | order reconciliation 패턴 정합 |
| 0007 (Kafka backbone) | #2·#7 | outbox + DLT |
| 0008 (docker MSA) | #9 | MDC 분산 추적 |

**신규 ADR**: 0084 "주문 path 안정성 종합 fitness gap fix (P0·P1·P2 트랙)" (Proposed)
- supersede 관계 없음 — 모두 superset (add-only)
- 본 spec과 1:1 매핑

---

## 6. KIS-first raw fetch 인벤토리 (헌법 §3 의무)

architect spec/plan 발행 시 KIS API 결정 의제는 자체 추론 0 + 공식 출처 raw URL 인용 의무 (헌법 §3 + 사용자 메모리 `feedback_kis_first_policy`).

| 의제 | raw URL | 검증 일자 (sub-agent 정독 의무) |
|---|---|---|
| #4 WS backfill | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_ccnl/inquire_ccnl.py` | sub-agent dispatch 시 본문 정독 의무 |
| #3 PENDING_RECONCILE | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_ccld/inquire_daily_ccld.py` | sub-agent dispatch 시 본문 정독 의무 |
| #11 hashkey | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/auth/auth.py` (getHashKey) + `examples_user/domestic_stock/domestic_stock_functions.py` L10097~L10213 (order_cash hashkey 사용 여부 확인) | sub-agent dispatch 시 본문 정독 의무 — hashkey 의무 여부 확정 |
| #6 정정 | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions.py` L10771~L10884 (order_rvsecncl, `RVSE_CNCL_DVSN_CD="01"`) | sub-agent dispatch 시 본문 정독 의무 |

**raw URL 보강 정책** (architect 단계 미확정 시):
- sub-agent 구현 시 raw URL 정독 + KDoc 본문에 raw URL inline 인용 의무
- raw 본문이 magicJar 구현과 다르면 — magicJar 본문 raw 인용으로 superset 갱신 (KIS-first 의무)

---

## 7. backlog 흐름

### 7-1. 신설 §

- **§78 — 외부 코드 리뷰 13 코멘트 흡수 종합 트랙 (본 spec 인용)** — 단일 종합 § + 11 sub-task

**§78 sub-task 매트릭스**:
- §78-1 ~ §78-11: 의제 #1 ~ #11 (각 의제별 상태 + sub-agent + 우선순위 표 row 1건)

또는 11건 분리 §:
- §79 ~ §89: 각 의제 1 §

본 spec 정책 — **§78 단일 종합** + 11 sub-task 행 매트릭스 (스크롤 줄임).

### 7-2. close 의제 (이미 충족)

- §78-1 (#1 idempotency) — ✅ 충족 — close, evidence 보존
- §78-5 (#5 SUM 가드) — ✅ 충족 — close, evidence 보존

---

## 8. CHANGELOG 슬롯 (헌법 §4)

본 spec PR diff에 sub-agent별 CHANGELOG 슬롯 row를 사전 발행한다. P0·P1·P2 sub-agent dispatch가 단일 spec PR이 아닌 **별도 후속 PR**로 진행되므로, 본 spec PR은 **architect 슬롯 1건 + 후속 sub-agent 슬롯 보강은 각 dispatch PR이 자체 add-only** 형태로 진행.

**현 PR 슬롯 row**:
```
| 2026-05-18 | **spec: PR-N order-path-stability-spec** — 외부 코드 리뷰 13 코멘트 흡수 + P0·P1·P2 분류 + ADR 0084 Proposed (작성: architect) | _workspace/specs/2026-05-18-order-path-stability-spec.md |
```

후속 sub-agent dispatch PR (P0-1 ~ P2-2)은 각 PR이 본인 row 1건 add-only.

---

## 9. 운영 영향 매트릭스

### 9-1. 컨테이너 영향

- consumer: KIS REST 추가 호출(inquire-daily-ccld·inquire-ccnl) + DLT listener 추가 → 약간의 메모리·CPU 증가 (LOW)
- api: TraceIdFilter 추가 + OrderEventOutbox worker 추가 → 1 thread + 100ms cycle 부담
- frontend: WebSocket onConnect unread fetch 추가 → 초기 진입 latency +50ms 미만 추정
- DB: Flyway V35·V36·V37·V38 4건 add-only — schema 변경 행렬

### 9-2. KIS Rate Limit 영향 (ADR 0037)

- inquire-daily-ccld (PENDING_RECONCILE worker, 1분 주기) — PENDING_RECONCILE 0건 시 호출 0 → MarketSessionFsmGate 합류 후 cron skip
- inquire-ccnl (WS 재연결 backfill) — 재연결 시점만 1회 호출
- hashkey (의무 시) — 주문당 1회 추가 호출 → 매수/매도 주문 발사 시점만 → rate limit 영향 LOW

### 9-3. 사용자 보고 UX

- STOMP 재접속 시 unread 모달 (P1-2) — 사용자 체감 알림 보강
- order 정정 (P2-1) — 취소 후 재주문 → PATCH 1회 (UX 보강)

---

## 10. 비범위 (post-MVP / backlog 후속 흡수)

- **분산 분산 추적 (Zipkin·Jaeger)**: 본 spec은 MDC traceId만 (현재 사이클 cost 최소화). 후속 운영 비용 검토 후 결정.
- **OrderEventOutbox transactional outbox 패턴 — Debezium CDC**: 본 spec은 polling 패턴 (poll 100ms 또는 1s). CDC는 Postgres logical replication 의존 — post-MVP 검토.
- **DLT replay UI**: DLT 토픽 row 수동 재처리 UI는 post-MVP.

---

## 11. Rollback 매트릭스

**본 spec PR**: 문서 only — `git revert` 단일 commit으로 rollback. 운영 코드 0 touch.

**P0·P1·P2 sub-agent dispatch PR별 rollback**:
- 각 PR 본문에 rollback 절차 명시 의무 (각 의제 §2-* 본문에 toggle 또는 revert 정합)
- 자본 path 변경 (P0)은 — toggle env 또는 feature flag 합류 권장

---

## 12. fitness 검증 (사용자 메모리 `feedback_v3_skepticism_no_regression` lens)

| 검증 항목 | 결과 |
|---|---|
| **자동매매에 필요한가?** | 11 의제 모두 자동매매 본체 path — P0 4건은 자본 잠재 risk, P1 3건은 정합성·관측성, P2 2건은 UX 보강 |
| **v1/v2 잔재 아닌가?** | 11 의제 모두 v3 본체 path — v1/v2 잔재 0건 |
| **MVP 범위 적합?** | P0·P1은 REAL 진입 필수 + 코드 리뷰 검증된 운영 표준 — MVP 범위 정합. P2는 cost 검토 후 W4 진입 직전 묶음 cut 가능 |

---

## 13. 사용자 결재 요청

architect 권장 결재 매트릭스:

1. **P0·P1·P2 분류 본문** — 의제 #6·#10이 P2 (LOW) 정합 vs P1 격상 의제? (architect 권장 = P2 유지)
2. **WS race fix 의제 #4** — pre-insert(kisOrderNo NULL 허용 + 별도 인덱스) vs Redis pending buffer — 어느 패턴 권장? (architect 권장 = pre-insert + DB UNIQUE — Redis 의존성 최소화)
3. **DLT P0 알림 의제 #7** — 자본 path이므로 P0 알림(즉시 Slack) 정합 vs P1 audit only? (architect 권장 = P0 — 자본 lock 의제이므로 즉시 알림)
4. **v3.6.18 cut 시점 의제** — P2 마감 후 cut vs W4 진입 직전 묶음 cut? (architect 권장 = W4 진입 직전 묶음 — 의제 #6·#10은 cost 낮으므로 patch 단독 cut 비효율)

**사용자 결재 후 orchestrator 영역 — P0 4 의제 sub-agent dispatch 발사**.

---

## 14. 변경 이력

| 날짜 | 변경 | actor |
|---|---|---|
| 2026-05-18 | 본 spec 발행 — 외부 코드 리뷰 13 코멘트 흡수 + P0·P1·P2 분류 + ADR 0084 Proposed | architect |
