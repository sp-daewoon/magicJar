# ADR 0084 — 주문 path 안정성 종합 fitness gap fix (P0·P1·P2 트랙)

> **Status**: Accepted (2026-05-19 격상 — P0 4건 마감 evidence · v3.6.16 cut). P1·P2는 후속 트랙 잔류.
> **Date**: 2026-05-18 (KST)
> **Accepted Date**: 2026-05-19 (KST)
> **Author**: architect (수석 설계자)
> **PR**: TBD (본 ADR + spec `2026-05-18-order-path-stability-spec.md` 동시 발행)
> **Supersedes**: 없음 (모두 superset add-only)
> **Supersedes by**: 없음

---

## 1. 컨텍스트

### 1-1. 외부 코드 리뷰 13 코멘트 (2026-05-18)

사용자(최고 관리자)가 외부 코드 리뷰 2건을 받음. 13 코멘트(중복 제거 11 의제) — magicJar 현 시스템 grep·코드 정독 검증 결과:

- ✅ 충족 2건: #1 (idempotency UNIQUE) · #5 (부분체결 SUM 가드)
- 🟡 부분 충족 5건: #2 (outbox — Slack only) · #3 (5xx 1회 retry — reconcile 0) · #6 (취소만, 정정 0) · #8 (STOMP 즉시 publish) · #10 (FSM 1차 stub)
- ❌ 미충족 4건: #4 (WS race·backfill) · #7 (DLQ) · #9 (MDC) · #11 (hashkey)

상세 매트릭스는 spec `_workspace/specs/2026-05-18-order-path-stability-spec.md` §1·§2 본문.

### 1-2. fitness gap 검증 (사용자 메모리 `feedback_v3_skepticism_no_regression` lens)

- **자동매매에 필요한가?**: 11 의제 모두 자동매매 본체 path. P0 4건은 자본 잠재 risk (#4 중복주문·체결누락, #3 ACK 미수신 후 audit 깨짐, #11 KIS REJECT, #7 무한 재시도).
- **v1/v2 잔재 아닌가?**: 11 의제 모두 v3 본체 path — v1/v2 잔재 0건.
- **MVP 범위 적합?**: P0·P1은 REAL 진입 필수. P2는 W4 진입 직전 묶음 cut 가능.

### 1-3. 사용자 결정 (2026-05-18)

사용자 명시: **P0·P1·P2 단계적 트랙 발행 + sub-agent dispatch**.

---

## 2. 결정

### 2-1. P0·P1·P2 분류 매트릭스

#### P0 (즉시 — 자본 잠재 risk)

| 의제 | sub-agent | 영역 | 우선순위 |
|---|---|---|---|
| #11 hashkey | market-data | consumer/order/KisHashKeyAdapter + KisOrderHttpAdapter | HIGH (REAL 진입 즉시 차단 risk) |
| #3 PENDING_RECONCILE | market-data + backend-core | consumer/order + Flyway V36 + domain OrderStatus | HIGH |
| #4 WS race + backfill | market-data | consumer/ws + consumer/order + Flyway V35 | HIGH |
| #7 DLQ | backend-core | consumer Kafka config + DLT listener | HIGH |

#### P1 (단기 — 정합성·관측성)

| 의제 | sub-agent | 영역 | 우선순위 |
|---|---|---|---|
| #2 order outbox | backend-core | api/messaging + Flyway V37 + 4 producer 통합 | MED |
| #8 STOMP 영속 | backend-core + frontend | api/messaging + Flyway V38 + frontend WebSocket hook | MED |
| #9 MDC traceId | backend-core | api·consumer Filter + Kafka header + logback | MED |

#### P2 (중기 — UX·운영 편의)

| 의제 | sub-agent | 영역 | 우선순위 |
|---|---|---|---|
| #6 정정 | backend-core | api/controller/order + consumer/order | LOW |
| #10 MarketSession + token mutex | market-data + backend-core | consumer/ws + KIS REST | LOW |

#### close (이미 충족 — backlog evidence 보존)

- #1 (idempotency UNIQUE) — Flyway V20 `uq_order_idempotency` + Redis SETNX 60s
- #5 (부분체결 SUM 가드) — `OrderExecutionRepository.sumExecutedQuantityByOrder` + §14-6 LOCK

### 2-2. v3.x cut 시점 매트릭스

사용자 메모리 `feedback_release_policy_patch_first` 정합 (v3.7·v3.8 외 patch only):

| 마감 시점 | 버전 | 트리거 의제 |
|---|---|---|
| P0 완료 | v3.6.16 | #11·#3·#4·#7 — 자본 잠재 risk 해소 |
| P1 완료 | v3.6.17 | #2·#8·#9 — 정합성·관측성 보강 |
| P2 완료 | v3.6.18 (또는 W4 진입 직전 묶음) | #6·#10 — UX·운영 편의 |

cut 직전 의무: `scripts/build-and-deploy.sh` 재배포 + 4 모듈 docker logs 에러 검사 → 0건 확인 후 cut (사용자 메모리 `feedback_docker_log_verify_before_cut`).

### 2-3. KIS-first raw fetch 인벤토리 (헌법 §3)

architect spec/plan 발행 시 KIS API 결정 의제는 자체 추론 0 + 공식 출처 raw URL 인용 의무.

| 의제 | raw URL |
|---|---|
| #4 WS backfill | https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_ccnl/inquire_ccnl.py |
| #3 PENDING_RECONCILE | https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_ccld/inquire_daily_ccld.py |
| #11 hashkey | https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/auth/auth.py (getHashKey) + examples_user/domestic_stock/domestic_stock_functions.py L10097~L10213 |
| #6 정정 | https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions.py L10771~L10884 (def order_rvsecncl, RVSE_CNCL_DVSN_CD="01") |

### 2-4. DB Flyway 매트릭스 (add-only)

| Flyway | 의제 | 변경 |
|---|---|---|
| V35 | #4 | `order_execution.event_id UUID UNIQUE` 컬럼 + idx |
| V36 | #3 | `order_request.status` enum check constraint 갱신 + `idx_order_request_pending_reconcile` partial index |
| V37 | #2 | `order_event_outbox` 신규 테이블 |
| V38 | #8 | `notification_outbox.channel` enum 'STOMP_USER' 추가 + `notification_outbox.user_id` 컬럼 + idx |

### 2-5. Kafka 토픽 매트릭스

신규 토픽 (의제 #7 DLT):
- `order.intent.v1.DLT`
- `signal.emitted.v1.DLT`
- `tick.v1.DLT`
- `ws.execution.v1.DLT`

신규 토픽 (의제 #6 정정 — 선택):
- `order.modify.intent.v1` (또는 기존 `order.intent.v1` 분기)

### 2-6. Redis 키 매트릭스

신규 (의제 #4 — 선택):
- `pending:execution:{kisOrderNo}` — 30s TTL (WS 체결이 ACK보다 먼저 도달 시 임시 보관)
- pre-insert 패턴 채택 시 본 키 불필요

기존 (의제 #10 재검증):
- `lock:kis:auth:{userId}:{accountType}` — KIS OAuth 토큰 발급 mutex (ADR 0038)

### 2-7. 영향 ADR 매트릭스 (superset 관계)

| ADR | 의제 | superset 관계 |
|---|---|---|
| 0068 (T-O) | #3·#4·#6·#7·#11 | §14-2·§14-5·§14-6 LOCK 후속 보강 |
| 0070 (T-RECON+NOTIFY) | #2·#3·#8 | outbox + reconcile + STOMP 채널 |
| 0077 (OPSP8996) | #4 | WS 재연결 ↔ backfill 결합 |
| 0044 (notification) | #7·#8 | DLT P0 알림 + STOMP_USER 채널 |
| 0013 (MarketSessionFsm) | #10 | Gate 통합 |
| 0038 (KIS OAuth) | #10 | token mutex 재검증 |
| 0041 (broker reconciliation) | #3 | order reconciliation 패턴 정합 |
| 0007 (Kafka backbone) | #2·#7 | outbox + DLT |
| 0008 (docker MSA) | #9 | MDC 분산 추적 |

본 ADR과 spec은 1:1 매핑. spec 본문 §2~§5가 본 ADR 본문의 실행 매트릭스.

### 2-8. AI 호출 0 검증

본 ADR이 영향을 미치는 11 의제는 모두 W1·W2·W3 트랙 구성요소(T-O 트랙 본체) — `feedback_v3_skepticism_no_regression` + 개발 룰 5건 §5(AI W4 한정) 정합. LLM provider import 0건 의무.

---

## 3. 결과·영향

### 3-1. 트랙 영향

- **T-O 트랙 (ADR 0068)**: §14-2·§14-5·§14-6 보강 — Phase 1·2·3 마감 후 운영 안정성 보강 사이클
- **T-RECON+NOTIFY 트랙 (ADR 0070)**: outbox 패턴 superset + reconcile cron 매트릭스 superset
- **신규 트랙 0건**: 본 ADR은 기존 트랙 superset만, 신규 트랙 생성 0

### 3-2. 컨테이너 영향

- consumer: KIS REST 추가 호출(inquire-daily-ccld·inquire-ccnl) + DLT listener — 메모리·CPU 영향 LOW
- api: TraceIdFilter + OrderEventOutbox worker — 1 thread + 100ms~1s cycle
- frontend: WebSocket onConnect unread fetch — 초기 진입 latency +50ms 이하 추정
- DB: 4 Flyway add-only — schema 영향 LOW

### 3-3. KIS Rate Limit (ADR 0037)

- inquire-daily-ccld (PENDING_RECONCILE worker, 1분 주기) — PENDING_RECONCILE 0건 시 호출 0
- inquire-ccnl (WS 재연결 backfill) — 재연결 시점만 1회
- hashkey (의무 시) — 주문당 1회 → 매수/매도 발사 시점만
- MarketSessionFsmGate (의제 #10) 합류 후 휴장 시 cron skip → rate limit 절약

### 3-4. fitness 검증 (사용자 메모리 `feedback_v3_skepticism_no_regression`)

- **자동매매 정합**: 11 의제 모두 본체 path — 자본 잠재 risk · 운영 관측성 · UX 보강
- **v1/v2 잔재**: 0건
- **MVP 범위**: P0·P1은 REAL 진입 필수, P2는 W4 직전 묶음 cut 가능

### 3-5. 트레이드오프

- **outbox poll 100ms vs 1s**: poll 100ms는 latency 작으나 DB 부담 ↑. 1s는 frontend STOMP 알림 latency +1s. 본 ADR 권장 = 1s 시작 + 부담 측정 후 100ms 조정.
- **pre-insert(kisOrderNo NULL) vs Redis pending buffer**: pre-insert는 DB UNIQUE + 의존성 최소, buffer는 race window 좁음. 본 ADR 권장 = **pre-insert** (DB single source of truth).
- **DLT P0 알림 즉시 vs P1 audit**: P0은 자본 path 즉시 알림으로 운영자 대응 빠름, P1 audit only는 알림 노이즈 낮으나 사고 발견 지연. 본 ADR 권장 = **P0 즉시 알림** (자본 lock 의제).
- **P2 단독 cut vs W4 직전 묶음**: 단독 cut은 사이클 명확, 묶음은 cost 절약. 본 ADR 권장 = **W4 직전 묶음** (의제 #6·#10 cost 낮음).

---

## 4. 후속 ADR/backlog 후보

- **DLT replay UI**: post-MVP (backlog 신설)
- **Zipkin·Jaeger 분산 추적**: post-MVP (현재 사이클은 MDC만)
- **Debezium CDC outbox**: post-MVP (현재 polling outbox 합류)
- **MarketSessionFsm Gate 전 cron 통합 검증**: §10 합류 후 다른 cron(candle ingestion 등) Gate 적용 검토 — 별도 spec 사이클

---

## 5. 변경 이력

| 날짜 | 변경 | actor |
|---|---|---|
| 2026-05-18 | 본 ADR 발행 (Proposed) — 외부 코드 리뷰 13 코멘트 흡수 + P0·P1·P2 분류 + spec 1:1 매핑 | architect |
| 2026-05-19 | Proposed → Accepted (P0 4건 baseline 마감) — P0-1 hashkey + P0-2 PENDING_RECONCILE + P0-3 WS race·backfill + P0-4 DLQ 4 PR 머지 완료 + v3.6.16 cut. P1(outbox·STOMP 영속·MDC) + P2(정정·MarketSession) 후속 잔류 (별도 사이클 dispatch 예정 — backlog §78 잔류). | architect |
