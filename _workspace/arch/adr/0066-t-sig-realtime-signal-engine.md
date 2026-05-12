# ADR 0066 — T-SIG 실시간 시그널 엔진 (룰 매칭 + Kafka boundary listen + 영속 + STOMP fanout)

- 상태: **Proposed (2026-05-12 — 결정 의제 6건 사용자 검토 대기)**
- 작성일: 2026-05-12
- 작성자: architect
- 결정자: 사용자(최고 관리자) — 결정 의제 6건 검토 위임
- 진입 트랙: T-SIG (W3 두 번째 트랙, v3.4.0 미러스톤 후보)
- 관계:
  - **ADR 0023 (Signal Engine = 룰 매칭 only, AI 호출 0)** — 본 ADR이 그 결정의 W3 구현 권위. §2-1 도메인 모델 + §2-2 평가 흐름 + §2-3 두 모드(Realtime·Batch) + §2-4 AI 차단 검증 + §2-5 자동 주문 토글 baseline LOCK
  - **ADR 0047 (DSL Evaluation Sandbox)** — §2-2 Realtime timeout 100ms · §2-3 strategy-eval-pool · §2-6 strategy + strategy_eval_metric 테이블 + §3-1 T-SIG 합류 매트릭스 LOCK. 본 ADR이 그 sandbox를 실행 path에 정합 진입
  - **ADR 0064 (Redis boundary fanout + STOMP)** — §4-5 Kafka 토픽 `market.candle.{interval}.closed.v1` 5종이 본 ADR 시그널 평가 트리거 진입점. 본 ADR consumer는 그 토픽 listener
  - **ADR 0022 (Strategy DSL `.kis.yaml` 호환 superset)** — DSL 평가 로직 권위. 본 ADR 평가기 호출 path 정합
  - **ADR 0021 (Indicator Source 추상화)** — 룰 평가 시 indicator 호출 경유
  - **ADR 0044 (Notification System)** — `risk.violation.v1` payload type 확장(`STRATEGY_TIMEOUT`·`SIGNAL_GENERATION_FAILED`) Slack 통지 정합
  - **ADR 0045 (Graceful Shutdown)** — consumer Kafka listener container + strategy-eval-pool SmartLifecycle 정합
  - **ADR 0061 (Backtest 결과 영속)** — 3 테이블 분리 + REST 5 endpoint 패턴 baseline 적용
  - **ADR 0056 (Backtest Engine Architecture)** — 백테스트 통과 strategy 활성화 게이트 정합 (§2-3 후속)
  - **ADR 0030 (master-architecture SoT)** — §10 ADR 0066 row 추가 동반
  - **ADR 0007 (Kafka 이벤트 백본)** — `signal.generated.v1` 신규 토픽 정합
  - **ADR 0042 (Position Bootstrap)** — 시그널 → 주문 발행 시점 잔고 정합 (T-O 트랙 합류)
  - **ADR 0024 (Risk Guard chain 6단)** — 본 ADR 후행 (signal → order.intent → risk guard)

> **AI 호출 0 검증 의무 (ADR 0023 §2-4)**: 본 ADR 범위 내 모든 컴포넌트 코드에 LLM provider import(Anthropic SDK · Spring AI ChatClient · Gemini SDK · OpenAI SDK) 0건. sub-agent 디스패치 시 architect 1회 grep 검증 의무 + 코드 inline 주석 정책 명시.

> **KIS 키 정책 (ADR 0057 정합)**: 본 ADR은 KIS REST/WS 직접 호출 0건 — Kafka `market.candle.{interval}.closed.v1` listen만. 시장 데이터 source는 ADR 0064 boundary fanout (prod credential 단일 채널).

---

## 1. 컨텍스트

### 1-1. T-SIG 트랙 위치 (tracks-roadmap §1·§2)

W3 자동 매매 본체 트랙 (룰만, AI 호출 0):
- T-BT (Backtest Engine) — **v3.3.1 마감 (영속 + UI 완성)**
- **T-SIG (Signal Engine)** — 본 ADR 진입 (W3 두 번째 트랙)
- T-SC (Screener) — 후행
- T-RISK (Risk Guard chain) — 후행
- T-O (Order Dispatch) — 후행
- T-RECON·T-NOTIFY·T-PERF — 후행

T-SIG는 **자동매매 본체의 핵심 노드**:
1. T-DSL·T-IND·T-PAT가 정의한 룰을 실시간 캔들에 평가
2. boundary closing 시점에 BUY/SELL/HOLD 시그널 발행
3. 사용자 알림 + (T-O에서) 주문 intent 발행 trigger
4. AI 호출 0 (ADR 0023 §2-4 정합)

### 1-2. 현재 main 상태 (사전 자산 — v3.3.1 cut 완료)

자산 확보 완료:
- **ADR 0023** — Signal Engine 룰 매칭 only 권위 (도메인 모델 + 평가 흐름 + AI 차단 + 자동 주문 토글 baseline)
- **ADR 0047** — DSL Sandbox (Realtime timeout 100ms · strategy-eval-pool 4 fixed · strategy + strategy_eval_metric 테이블 V26·V27 baseline) — **본 ADR 합류 매트릭스 LOCK**
- **ADR 0064** — boundary fanout (Kafka `market.candle.{interval}.closed.v1` 5종 + STOMP `/topic/market.candle.{interval}.{code}`) — **본 ADR 시그널 트리거 진입점**
- **T-DSL** (W2 PR #255 머지) — DSL 파서·평가기 본체
- **T-IND** (W2 PR #253 머지) — 80 지표 라이브러리
- **T-PAT** (W2 PR #258 머지) — 57 패턴 인식
- **T-BT** (v3.3.1 마감) — 백테스트 영속 5 endpoint + frontend `/strategy/backtest`
- **StrategyEvalMetric 영속** (Flyway V11 baseline) — sandbox metric 영속 가능

본 ADR이 **위 자산을 실시간 path에 통합**.

### 1-3. fitness gap 분석 (사용자 메모리 `feedback_v3_skepticism_no_regression.md` lens)

#### 1-3-1. 자동매매에 필요한가?
**필수** — magicJar 비전 정의 (`project_system_vision.md`):
> "룰 기반 quant trading 본체 + AI 옵션 보조. 매매=룰/시그널"

T-SIG가 그 룰/시그널 본체. 부재 시 magicJar는 백테스트 도구로만 동작 — 실시간 매매 결정 0.

#### 1-3-2. v1/v2 잔재 아닌가?
**잔재 아님** — v3.x baseline 신규. ADR 0023(2026-05-03 Accepted)·ADR 0047(2026-05-07 Accepted)·ADR 0064(2026-05-12 Accepted)가 모두 v3.x 발행. 본 ADR이 그 자산의 W3 구현.

#### 1-3-3. 사용자 비전 정합
- magicJar = 룰 기반 본체 → 본 ADR 매매 결정 권위
- AI는 옵션 보조 → 본 ADR LLM 호출 0건 검증 의무 (§2-4)
- MCP 서버 노출(ADR 0027) → 향후 외부 client 시그널 query API 정합

---

## 2. 결정 의제 (사용자 결정 대기)

architect 권장안 6건 + 각 의제 대안 매트릭스. 본 ADR `Accepted` 갱신 후 sub-agent 디스패치 진입.

### 2-1. 의제 A — 시그널 평가 트리거 시점

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **A-1 ADR 0064 Kafka `market.candle.{interval}.closed.v1` listen** ⭐ | consumer 5 listener (5m·15m·30m·90m·1h) — boundary closing payload 수신 시 해당 종목 활성 strategy 평가 | latency 최소(<50ms boundary→평가→fanout) · ADR 0064 baseline 100% 활용 · 종목별 partition by code 단일 thread 보장 · 멱등 단순(boundary ts 단위) | Kafka listener 5건 추가 (코드 행수 ~50 LOC × 5) |
| A-2 별도 스케줄러 (Spring @Scheduled 5초) | 5초 주기 watchlist × 활성 strategy 일괄 평가 | 코드 단순(listener 1건) | latency 최대 5초 · boundary 정렬 어긋남 risk · 매 5초마다 활성 strategy 전체 평가 → CPU 부담 |
| A-3 Redis pub-sub 직접 (Kafka 우회) | Redis `candle:current:*` write 시 pub-sub → consumer subscribe → 평가 | latency <10ms · Kafka latency 우회 | Kafka audit trail 부재 · ADR 0007 백본 정책 위반 · 영속 보장 0 · 재처리 불가 |

**architect 권장 = A-1 Kafka boundary listen (5 listener)**

사유:
1. **ADR 0064 100% 활용** — 본 ADR 시점에 이미 boundary fanout 가동(채택 후 PR 머지). `market.candle.{interval}.closed.v1` 5종 토픽이 시그널 평가의 가장 빠른 source. 추가 컴포넌트 0
2. **latency <50ms** — boundary 마감(09:05:00) → Kafka publish(20ms) → consumer listener → 평가(<100ms) → Kafka `signal.generated.v1` → STOMP fanout(20ms). 총 end-to-end <200ms 예상
3. **partition by code 단일 thread** — Kafka partition key를 종목코드로 설정 시 같은 종목의 5m·15m boundary가 동일 partition → 순차 처리 → race 0
4. **멱등성 보장** — boundary closing ts는 deterministic(5m=08:05·08:10·…). (strategyId, code, ts) UQ로 중복 시그널 차단(§2-6 정합)
5. **audit trail** — Kafka topic retention 7d로 시그널 평가 history 보존(ADR 0007 정합)

대안 A-2는 latency 트레이드오프 + CPU 낭비, A-3은 ADR 0007 백본 정책 위반으로 폐기.

### 2-2. 의제 B — 룰 source (사용자 strategy 영속)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **B-1 사용자 strategy 영속 DB (strategy 테이블)** ⭐ | `strategy` 테이블(ADR 0047 §2-6 V26 baseline) + 사용자가 REST `POST /api/strategies` 등록 | UI 등록 가능 · 활성/비활성 토글 · 사용자별 격리 · 영속 = 재기동 후 자동 평가 재진입 | DB I/O 부담(평가마다 활성 strategy SELECT) — Redis cache(§2-5)로 완화 |
| B-2 inline 룰 (백테스트와 동일, 매 요청마다 ruleDsl POST) | T-BT 패턴 — 사용자가 매번 ruleDsl 전송 | strategy 테이블 불필요 | 실시간 시그널 본질 위반(매 boundary마다 사용자 입력 0건) · 등록·관리 UI 부재 |
| B-3 하이브리드 (영속 + inline 옵션) | strategy 등록 + 별도 inline endpoint 옵션 보존 | 유연 | 코드 복잡도 ↑ · MVP 가치 미달 |

**architect 권장 = B-1 strategy 영속 DB**

사유:
1. **ADR 0047 §2-6 baseline 완전 정합** — `strategy` 테이블 V26 + `strategy_eval_metric` V27 이미 baseline. 본 ADR이 그 테이블의 W3 실제 사용 권위
2. **사용자 UX 필수** — magicJar 비전 "사용자=최고 관리자/설계자" — 사용자가 룰 등록·관리·토글 의무. UI 부재로는 실시간 시그널 무의미
3. **활성/비활성 토글** — 사용자 dogfooding 시점에 룰 비활성 → 즉시 시그널 0건 보장
4. **재기동 자동 재진입** — 컨테이너 재기동 후 활성 strategy SELECT만으로 다시 평가 — graceful shutdown(ADR 0045) 정합
5. **DB I/O 완화** — Redis `strategy:active:by_code:{code}` cache(§2-5-2)로 평가 path I/O 0

### 2-3. 의제 C — 시그널 영속 방식

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **C-1 신규 `signal` 테이블 (1차 + 후속 partition)** ⭐ | `signal` 테이블 — id·strategyId·userId·code·interval·ts·side·strength·reason·priceAtSignal·contextJson·createdAt | T-J(Trading Journal) baseline · 시계열 시각화 가능 · 후속 페이지네이션 정합 · ADR 0061 T-BT 패턴 정합 | row 누적 부담 — 사용자 1명 × strategy 10 × 종목 10 × 5 interval × 30 day 가정 = 15K row/day → 1년 5.5M row (partition 권장) |
| C-2 `strategy_eval_metric` 확장 (ADR 0047 V27) | 기존 metric 테이블에 side·strength·reason 컬럼 add | 신규 테이블 0 | metric(평가 결과)과 signal(매매 결정) 도메인 혼선 · ADR 0047 §2-6 V27 정의(eval_time_ms·cpu_time_ms·outcome) 본질과 어긋남 |
| C-3 Redis stream 휘발 + 알림만 (영속 0) | Redis stream `signal:stream:{userId}` TTL 7d + STOMP fanout | DB 부담 0 · latency 최소 | T-J 회귀 · "지난 시그널 비교" 불가 · MCP 외부 client 조회 불가 · 백테스트 결과와 actual 시그널 비교 불가 |

**architect 권장 = C-1 신규 `signal` 테이블 (1차 단일 + 1년 후 partition 검토)**

사유:
1. **T-J·MCP·dogfooding 모두 필수** — 시그널 history는 자동매매 본체 audit · MCP 외부 query · 사용자 학습(`T-LEARN`) 모두 필요
2. **ADR 0061 T-BT 패턴 정합** — 3 테이블 분리 + REST 5 endpoint baseline. 본 ADR `signal` 테이블 + 5 endpoint(POST·GET list·GET detail·DELETE·toggle)
3. **partition 후속** — 1차는 단일 테이블 + index(userId, ts DESC). row ≥ 5M 도달 시 monthly partition(`signal_yyyymm`) 후속 ADR (가칭 0085+). MVP에서 premature optimization 회피
4. **C-2 폐기** — `strategy_eval_metric`은 ADR 0047 §2-6 sandbox metric 본질(eval_time_ms·cpu_time_ms·outcome). signal과 도메인 분리 의무
5. **C-3 폐기** — Redis stream만으로는 dogfooding 검증 불가 + MCP 외부 client query 부재 + 백테스트와 actual 정합 검증 부재

### 2-4. 의제 D — 사용자 알림 채널

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **D-1 STOMP fanout `/topic/signal.user.{userId}`** ⭐ | 1차 — STOMP 단일 채널. 사용자 frontend 접속 시 실시간 토스트 | 이미 STOMP 인프라 가동(ADR 0064) · latency 최소 · 코드 단순 | 사용자 frontend 미접속 시 알림 손실 → 영속(§2-3 C-1)로 보완 |
| D-2 STOMP + Slack webhook (ADR 0044 정합) | D-1 + `notification.signal_generated.v1` Kafka publish → NotificationDispatcher → Slack | offline 시에도 푸시 도달 | T-NOTIFY 합류 의무 + Slack noise 부담(시그널 ~50/일) · ADR 0044 P0/P1/P2 priority 매트릭스 재정의 필요 |
| D-3 D-1 + 이메일 + 푸시 (Web Push API) | full 멀티채널 | 사용자 도달 보장 100% | MVP 과다 · 인프라 의존 ↑ · 인증 복잡도 ↑ |

**architect 권장 = D-1 STOMP fanout 1차 (D-2 옵션 후속)**

사유:
1. **MVP 단순화** — 1차는 STOMP 단일. frontend 접속 시 즉시 토스트 + signal 테이블에서 history 조회 fallback
2. **이미 STOMP 가동** — ADR 0064에서 boundary fanout STOMP 인프라 LOCK. 신규 topic 1건(`/topic/signal.user.{userId}`) 추가만
3. **userId 단위 격리** — multi-tenant(ADR 0010) 정합. 사용자 A의 시그널이 사용자 B에게 누설 0
4. **D-2 옵션 후속 PR** — T-NOTIFY 트랙(ADR 0044 합류) 진입 시 Slack/이메일/Web Push 다채널 확장 검토. 현 PR scope 외
5. **D-3 폐기** — MVP 가치 대비 인프라 부담 과다(Web Push 인증서·이메일 SMTP·VAPID 키 관리)

### 2-5. 의제 E — 중복 시그널 처리 (멱등성)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **E-1 DB UQ (strategyId, code, interval, ts) + Redis SETNX 30s window** ⭐ | DB UNIQUE constraint으로 강제 차단 + Redis dedupe(보조) | DB level 강제 · race 0 · ADR 0064 boundary ts deterministic 정합 | row insert 시 DUPLICATE_KEY 예외 handling 의무 → log warn + skip |
| E-2 Application level dedupe map | in-process `Map<Triple<strategyId, code, interval>, Instant>` | DB 영향 0 | 컨테이너 재기동 시 map 손실 · 분산 환경(향후) 시 partition별 격리 의무 |
| E-3 Kafka deduplication header | `signal.generated.v1` event에 dedup key + Kafka idempotent producer | Kafka level 보장 | Kafka idempotent producer 설정 의무 + retention 7d window only |

**architect 권장 = E-1 DB UQ + Redis SETNX 30s window 보조**

사유:
1. **deterministic boundary ts** — ADR 0064 boundary 시각은 deterministic(09:05:00·09:10:00·…). 동일 boundary 평가 시 동일 ts → DB UQ 강제 차단
2. **DB level 보장** — application bug · Kafka rebalance · race 모두 cover (consumer rebalance 시 동일 boundary 재처리 가능)
3. **Redis SETNX 30s window 보조** — DUPLICATE_KEY 예외 발생 전 fast-path skip (DB 부담 완화). 30s window는 boundary closing(5m=300s, 15m=900s) 대비 충분히 짧음
4. **DUPLICATE_KEY handling 정책**: `log.debug("dup signal skipped: ...")` + skip + metric 발행(가능)
5. **E-2 폐기** — 분산 환경 회귀, **E-3 폐기** — Kafka idempotent producer 추가 복잡도

### 2-6. 의제 F — 비활성 룰 처리 (사용자 토글 + auto-disable)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **F-1 strategy.status enum (ACTIVE·PAUSED·DISABLED·DELETED) + disabled_reason** ⭐ | ADR 0047 §2-6 V26 컬럼 추가 정합. 사용자 토글 PAUSED · sandbox auto DISABLED · soft DELETED | 4가지 상태 명확 · auto-disable 사유 분리 · UI 직관 · ADR 0047 V26 baseline 정합 | 상태 변경 transition 매트릭스 검증 의무 |
| F-2 status 단순(ACTIVE·INACTIVE) + 별도 컬럼 | status boolean + disabled_reason VARCHAR | 단순 | 사용자 PAUSED vs auto DISABLED vs hard DELETED 구분 0 → UX 모호 |
| F-3 soft delete only (deletedAt timestamp) | 별도 status 없이 deletedAt 0/null 분기 | 컬럼 1건 | PAUSED 상태 표현 불가 — 사용자가 임시 끄려 해도 DELETE 수밖에 없음 → 재활성화 복잡 |

**architect 권장 = F-1 status enum 4단**

사유:
1. **ADR 0047 §2-6 V26 baseline 정합** — `disabled_reason VARCHAR(32)` (TIMEOUT·CPU_BUDGET_EXCEEDED·MANUAL·NULL) 이미 설계. 본 ADR이 `status enum`도 추가
2. **4가지 의도 명확**:
   - **ACTIVE** — 평가 path 진입 (default)
   - **PAUSED** — 사용자 토글, 평가 skip, 즉시 재활성화 가능
   - **DISABLED** — sandbox auto-disable (ADR 0047 §2-2-D TIMEOUT·CPU_BUDGET_EXCEEDED), 사용자 명시 [다시 활성화] 의무
   - **DELETED** — soft delete, list 조회 제외 (audit 보존)
3. **transition 매트릭스**:
   - 사용자 액션: ACTIVE ↔ PAUSED (토글), ANY → DELETED (삭제), DISABLED → ACTIVE (사용자 [다시 활성화])
   - 시스템 액션: ACTIVE → DISABLED (sandbox)
4. **REST 정합** — `PUT /api/strategies/{id}/toggle?status=PAUSED` + `DELETE /api/strategies/{id}` 분리

---

## 3. 권장 결정 종합

| 의제 | 권장안 | 핵심 사유 |
|---|---|---|
| A. 트리거 시점 | **A-1** Kafka boundary listen 5 listener | ADR 0064 baseline · latency <50ms · partition by code race 0 |
| B. 룰 source | **B-1** strategy 영속 DB | ADR 0047 §2-6 baseline · 사용자 UX 필수 · Redis cache 완화 |
| C. 시그널 영속 | **C-1** 신규 signal 테이블 | T-J·MCP·dogfooding 필수 · ADR 0061 패턴 정합 · partition 후속 |
| D. 사용자 알림 | **D-1** STOMP fanout 1차 | MVP 단순 · ADR 0064 STOMP 인프라 100% 활용 · D-2 옵션 후속 |
| E. 중복 시그널 | **E-1** DB UQ + Redis SETNX 보조 | deterministic boundary ts · race 0 · DB level 강제 |
| F. 비활성 룰 | **F-1** status enum 4단 | ADR 0047 V26 baseline · transition 명확 |

---

## 4. 채택 시 REST API 설계 (B-1 + C-1 + F-1 정합)

### 4-1. Strategy CRUD (사용자 룰 관리)

| 메서드 | 경로 | 기능 | request | response |
|---|---|---|---|---|
| `POST` | `/api/strategies` | 룰 등록 + ADR 0047 §2-1 static 검증 | `{name, ruleDsl, codes[], intervals[]}` | `{id, complexity{astDepth, nodeCount, estimatedEvalTimeMs, complexityClass}}` |
| `GET` | `/api/strategies` | 사용자 룰 목록 | (auth) | `[{id, name, status, codes, intervals, createdAt, lastSignalAt}]` |
| `GET` | `/api/strategies/{id}` | 단건 + metric summary | (auth) | `{id, ..., evalMetric{avgEvalMs, timeoutCount, lastDisabledAt}}` |
| `PUT` | `/api/strategies/{id}/toggle` | status 토글 (ACTIVE↔PAUSED) | `{status: "PAUSED"\|"ACTIVE"}` | `{id, status, updatedAt}` |
| `PUT` | `/api/strategies/{id}/reactivate` | DISABLED → ACTIVE (사용자 명시 [다시 활성화]) | (auth) | `{id, status: "ACTIVE", updatedAt}` |
| `DELETE` | `/api/strategies/{id}` | soft delete (status=DELETED) | (auth) | `204 No Content` |

### 4-2. Signal 조회 (시계열 + 단건)

| 메서드 | 경로 | 기능 | request | response |
|---|---|---|---|---|
| `GET` | `/api/signals` | 시계열 (페이징) | `?userId&strategyId&code&from&to&page&size` | `{content: [...], totalElements, totalPages}` |
| `GET` | `/api/signals/{id}` | 단건 (룰·종목·ts·side·price·reason·context) | (auth) | `{id, strategyId, code, interval, ts, side, strength, reason, priceAtSignal, contextJson}` |

5 + 2 = 총 7 endpoint (ADR 0061 T-BT 5 endpoint 패턴 + 토글·재활성화·signal 시계열·signal 단건 추가).

### 4-3. 인증·인가
- userId는 JWT 또는 session 기반 (T-ONBOARD ADR 0046 정합)
- multi-tenant(ADR 0010) — strategy.userId · signal.userId 격리, 다른 사용자 strategy 조회 차단(403)

---

## 5. 채택 시 도메인 모델 (Pure Kotlin — domain 모듈)

### 5-1. Strategy 본체

```kotlin
// domain/.../strategy/Strategy.kt (확장 — ADR 0022 + ADR 0047 V26 baseline)
data class Strategy(
    val id: Long,
    val userId: String,
    val name: String,
    val ruleDsl: String,            // .kis.yaml superset (ADR 0022)
    val codes: List<String>,        // 적용 종목 코드 (복수)
    val intervals: List<CandleInterval>, // 평가 인터벌 (5m·15m·30m·90m·1h)
    val status: StrategyStatus,
    val disabledReason: StrategyDisabledReason?, // ADR 0047 §2-6 정합
    val complexity: StrategyComplexity, // ADR 0047 §2-1-B
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastDisabledAt: Instant?,
    val evalMetricSummary: EvalMetricSummary?, // 최근 N=20회 metric
)

enum class StrategyStatus { ACTIVE, PAUSED, DISABLED, DELETED }
enum class StrategyDisabledReason { TIMEOUT, CPU_BUDGET_EXCEEDED, MANUAL }
data class EvalMetricSummary(val avgEvalMs: Double, val timeoutCount: Int, val cpuBudgetExceededCount: Int)
```

### 5-2. Signal 본체

```kotlin
// domain/.../signal/Signal.kt (ADR 0023 §2-1 baseline 확장)
data class Signal(
    val id: Long,
    val strategyId: Long,
    val userId: String,             // 정합 cache (signal_strategy_user UQ)
    val code: String,
    val interval: CandleInterval,
    val ts: Instant,                // boundary closing ts (deterministic)
    val side: SignalSide,           // BUY · SELL · HOLD
    val strength: Int,              // 0~100 (ADR 0023 §2-1)
    val reason: String,             // 룰 매칭 사유 ("MA(5) > MA(20) at 09:05")
    val priceAtSignal: BigDecimal,  // 평가 시점 close
    val contextJson: String,        // 평가 context (indicator values · pattern matched · ...)
    val createdAt: Instant,
)

enum class SignalSide { BUY, SELL, HOLD }
```

### 5-3. Port 인터페이스 (헥사고날)

```kotlin
// domain/.../strategy/StrategyRepository.kt
interface StrategyRepository {
    fun findById(id: Long): Strategy?
    fun findByUserId(userId: String): List<Strategy>
    fun findActiveByCode(code: String, interval: CandleInterval): List<Strategy>
    fun save(strategy: Strategy): Strategy
    fun updateStatus(id: Long, status: StrategyStatus, reason: StrategyDisabledReason?, disabledAt: Instant?)
}

// domain/.../signal/SignalRepository.kt
interface SignalRepository {
    fun save(signal: Signal): Signal      // DB UQ (strategyId, code, interval, ts) → DUPLICATE_KEY 시 skip
    fun findByUser(userId: String, from: Instant, to: Instant, pageable: Pageable): Page<Signal>
    fun findById(id: Long): Signal?
}

// domain/.../signal/SignalDispatcher.kt — STOMP fanout port
interface SignalDispatcher {
    fun dispatch(signal: Signal)
}

// domain/.../signal/SignalGenerator.kt (ADR 0023 §2-1 baseline)
interface SignalGenerator {
    fun generate(strategy: Strategy, candles: List<Candle>, context: SignalContext): Signal?
}
```

### 5-4. SignalContext

```kotlin
// domain/.../signal/SignalContext.kt
data class SignalContext(
    val code: String,
    val interval: CandleInterval,
    val boundaryTs: Instant,
    val nowKst: Instant,
    val indicatorCache: Map<String, BigDecimal>, // ADR 0021 IndicatorSource 결과
)
```

---

## 6. 채택 시 영속 매트릭스 (Flyway V14·V15)

### 6-1. `strategy` 테이블 (ADR 0047 §2-6 V26 + 본 ADR 정합 V14로 통합)

> **Flyway 번호 정합**: ADR 0047 §2-6 V26·V27은 T-DSL 진입 시 발행 예약. T-DSL 머지 후 본 ADR이 V14·V15로 재할당 (ADR 0047 baseline 매트릭스 갱신 의무 — §11 영향 ADR 매트릭스 참조).

```sql
-- Flyway V14
CREATE TABLE strategy (
    id              BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(40) NOT NULL,
    name            VARCHAR(120) NOT NULL,
    rule_dsl        TEXT NOT NULL,
    codes           VARCHAR(500) NOT NULL,        -- 쉼표 구분 ("005930,000660")
    intervals       VARCHAR(80) NOT NULL,         -- 쉼표 구분 ("5m,15m,30m")
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    disabled_reason VARCHAR(32),
    complexity_json JSONB,                        -- ADR 0047 §2-1-B
    eval_metric_summary JSONB,                    -- 최근 N=20회 metric
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_disabled_at TIMESTAMPTZ
);

CREATE INDEX idx_strategy_user_status ON strategy (user_id, status);
CREATE INDEX idx_strategy_active_lookup ON strategy (status) WHERE status = 'ACTIVE';
```

### 6-2. `signal` 테이블 (신규 V15)

```sql
-- Flyway V15
CREATE TABLE signal (
    id              BIGSERIAL PRIMARY KEY,
    strategy_id     BIGINT NOT NULL REFERENCES strategy(id) ON DELETE CASCADE,
    user_id         VARCHAR(40) NOT NULL,         -- denormalized for index efficiency
    code            VARCHAR(8) NOT NULL,
    interval        VARCHAR(8) NOT NULL,
    ts              TIMESTAMPTZ NOT NULL,         -- boundary closing ts
    side            VARCHAR(8) NOT NULL,          -- BUY · SELL · HOLD
    strength        INT NOT NULL,                 -- 0~100
    reason          TEXT NOT NULL,
    price_at_signal NUMERIC(15,4) NOT NULL,
    context_json    JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (strategy_id, code, interval, ts)      -- 의제 E-1 멱등 보장
);

CREATE INDEX idx_signal_user_ts ON signal (user_id, ts DESC);
CREATE INDEX idx_signal_strategy_ts ON signal (strategy_id, ts DESC);
CREATE INDEX idx_signal_code_ts ON signal (code, ts DESC);
```

### 6-3. `strategy_eval_metric` 테이블 (ADR 0047 §2-6 V27 → V16로 재할당)

ADR 0047 §2-6 정의 그대로. eval_time_ms·cpu_time_ms·outcome — 마지막 N=100건 ring buffer.

```sql
-- Flyway V16
CREATE TABLE strategy_eval_metric (
    id              BIGSERIAL PRIMARY KEY,
    strategy_id     BIGINT NOT NULL REFERENCES strategy(id) ON DELETE CASCADE,
    evaluated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    eval_time_ms    INT NOT NULL,
    cpu_time_ms     INT,
    outcome         VARCHAR(20) NOT NULL,         -- OK · TIMEOUT · CPU_EXCEEDED · ERROR
    error_msg       TEXT
);

CREATE INDEX idx_eval_metric_strategy_evaluated ON strategy_eval_metric (strategy_id, evaluated_at DESC);
```

---

## 7. 채택 시 실행 엔진 매트릭스 (SignalEngine consumer)

### 7-1. 컴포넌트 매트릭스

| 컴포넌트 | 위치 | 책임 |
|---|---|---|
| **BoundaryClosingKafkaListener** (5건) | `consumer/.../signal/kafka/BoundaryClosingKafkaListener.kt` | `market.candle.{interval}.closed.v1` 5종 listen (5m·15m·30m·90m·1h). payload 수신 → SignalEvaluator 위임. partition key = code |
| **RealtimeSignalGenerator** (ADR 0023 §2-3) | `consumer/.../signal/RealtimeSignalGenerator.kt` | boundary payload 수신 → `StrategyRepository.findActiveByCode(code, interval)` → 활성 strategy별 평가 → Signal 발행 |
| **JvmStrategyEvaluator** (T-DSL 자산) | `consumer/.../strategy/JvmStrategyEvaluator.kt` | DSL 평가 본체. ADR 0047 sandbox 적용(timeout 100ms · CPU budget · strategy-eval-pool) |
| **SignalPersister** | `api/.../persistence/signal/SignalJpaAdapter.kt` | Signal entity 영속. DUPLICATE_KEY 시 log.debug + skip |
| **SignalDispatcher** (STOMP fanout) | `api/.../stomp/StompSignalDispatcher.kt` | Signal 영속 성공 시 `/topic/signal.user.{userId}` STOMP fanout |
| **SignalKafkaProducer** | `consumer/.../signal/kafka/SignalKafkaProducer.kt` | `signal.generated.v1` Kafka publish (audit · 후행 T-O 트리거) |
| **StrategyController** | `api/.../controller/StrategyController.kt` | REST 6 endpoint (CRUD + toggle + reactivate) |
| **SignalController** | `api/.../controller/SignalController.kt` | REST 2 endpoint (시계열 + 단건) |
| **StrategyCache** (Redis) | `api/.../infrastructure/cache/RedisStrategyCache.kt` | `strategy:active:by_code:{code}:{interval}` cache (B-1 DB I/O 완화) — strategy 등록·토글·삭제 시 evict |
| **SignalDedupeGuard** (Redis SETNX) | `consumer/.../signal/SignalDedupeGuard.kt` | E-1 보조 — `signal:dedupe:{strategyId}:{code}:{interval}:{ts}` 30s TTL SETNX |

### 7-2. 평가 흐름 (ADR 0023 §2-2 baseline 확장)

```
Kafka market.candle.5m.closed.v1 (partition by code)
   ↓
BoundaryClosingKafkaListener.onMessage(payload)
   ├─ code · interval · ts · close · ohlcv 추출
   └─ RealtimeSignalGenerator.evaluate(code, interval, boundaryTs)
        ↓
StrategyRepository.findActiveByCode(code, interval) (Redis cache 우선)
   → List<Strategy> (status=ACTIVE)
   ↓
FOR strategy IN active:
    1. SignalDedupeGuard.tryAcquire(strategyId, code, interval, ts) (30s SETNX)
       └─ 이미 처리됨 → skip (log.debug)
    2. candles fetch — recent N개 (interval 정합) DB SELECT or Redis hot path
    3. JvmStrategyEvaluator.evaluate(strategy, candles, context)
       └─ ADR 0047 sandbox — strategy-eval-pool + timeout 100ms + CPU budget 50ms
       └─ TIMEOUT 시 strategy.status=DISABLED + risk.violation.v1 publish (ADR 0044 정합)
    4. Signal? 발행 (BUY·SELL·HOLD)
       └─ null이면 skip
    5. SignalPersister.save(signal)
       └─ DB UQ → DUPLICATE_KEY 시 skip (E-1 보호망)
    6. SignalKafkaProducer.publish(signal.generated.v1, key=userId)
    7. SignalDispatcher.dispatch(signal) — STOMP `/topic/signal.user.{userId}`
    8. StrategyEvalMetric INSERT (eval_time_ms · cpu_time_ms · outcome)
```

### 7-3. latency 예측

| 단계 | 예상 latency |
|---|---|
| boundary publish (ADR 0064) → Kafka deliver | <20ms |
| BoundaryClosingKafkaListener → RealtimeSignalGenerator | <5ms |
| StrategyRepository.findActiveByCode (Redis cache hit) | <2ms |
| Candle fetch (recent N개, Redis hot path) | <5ms |
| JvmStrategyEvaluator (sandbox 100ms timeout, 일반 식 <30ms) | <30ms |
| SignalPersister + Kafka publish + STOMP fanout | <20ms |
| **end-to-end (boundary closing → 사용자 토스트)** | **<100ms** |

### 7-4. 비동기 · concurrency

- Kafka listener container thread (consumer default pool) — 5 listener 각 1 thread (5 listener × topic당 1 partition 권장 → 단일 thread per interval)
- 활성 strategy 평가 — strategy-eval-pool 4 thread fixed (ADR 0047 §2-3-A) — 동시 평가 4건
- 같은 종목 5m·15m·30m boundary가 동시 도달 시 (예: 09:30) — 3 listener 각각 다른 thread → strategy-eval-pool queue → 순차 처리

---

## 8. AI 호출 0 검증 (ADR 0023 §2-4 의무)

본 ADR scope 내 코드 의무:
- T-SIG 영역 코드 어디에도 LLM provider import 0건
- sub-agent 디스패치 시 architect 1회 grep 검증 의무
- 검증 대상 import 패턴:
  - `import org.springframework.ai.*`
  - `import com.anthropic.*`
  - `import com.openai.*`
  - `import com.google.cloud.aiplatform.*`
- 코드 inline 주석 정책: `// AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + 본 ADR §8)`

W4 트랙(T-AI) 진입 전까지 동일 정책 유지.

---

## 9. 운영 영향

### 9-1. DB ROW 부담 (signal 테이블 — 의제 C-1)

| 가정 | 값 |
|---|---|
| 사용자 1명 | 1 |
| 활성 strategy / user | 10 |
| 종목 / strategy | 10 |
| interval | 5 (5m·15m·30m·90m·1h) |
| 평균 시그널 / strategy·종목·interval·day | ~2 (HOLD 제외 — BUY/SELL만 영속 가정) |
| **시그널 / day** | 10 × 10 × 5 × 2 = **1,000/day** |
| **시그널 / month** | ~30,000 |
| **시그널 / year** | ~365,000 |

1년 단일 테이블 row ~365K, 5MB 추정. partition 불필요. 5명 사용자 + 5년 도달 시(~9M row) monthly partition 후속 검토.

### 9-2. Kafka 부하

| topic | msg/day | 비고 |
|---|---|---|
| `signal.generated.v1` | ~1,000 | C-1 영속 = BUY/SELL/HOLD 모두 영속 시 ~2,600 (HOLD 포함) — architect 권장 BUY/SELL만 publish |
| `market.candle.{interval}.closed.v1` (listener) | 본 ADR scope X (ADR 0064 publish) | 본 ADR은 listen만 |

**부하 미미** — 1,000 msg/day = 0.012 msg/s.

### 9-3. STOMP 부하

| topic | msg/day |
|---|---|
| `/topic/signal.user.{userId}` | ~1,000 (사용자 단위) |

0.012 msg/s — 매우 가벼움.

### 9-4. Redis 부담

| 키 | 용도 | 메모리 |
|---|---|---|
| `strategy:active:by_code:{code}:{interval}` | 활성 strategy cache (의제 B-1) | 종목 ~30 × interval 5 × strategy ~10 × ~500 bytes = ~750 KB |
| `signal:dedupe:{strategyId}:{code}:{interval}:{ts}` | 30s TTL dedupe (의제 E-1) | ~1KB × peak 1,000 = 1 MB (30s window) |

**합** ≈ 2 MB. ADR 0048 41 한도와 무관(별도 namespace).

### 9-5. KIS API 영향

본 ADR은 **KIS REST/WS 직접 호출 0건** — Kafka boundary listen만. Rate Limit(ADR 0037) 영향 0.

### 9-6. strategy-eval-pool 부하 (ADR 0047 §2-3 정합)

- 활성 strategy 10건 × 종목 10 × interval 5 = 500 평가 path. boundary closing 시점 burst.
- 5m boundary (144/day) 마감 시 동시 평가 burst — 활성 strategy 10건 × 종목 10 = 100 평가 task. queue 100 cover (CallerRunsPolicy fallback).
- 평균 평가 30ms × 100 = 3,000ms (4 thread 분산 → ~750ms 실 wall-clock).
- 사용자 dogfooding 누적 시 ADR 0047 §2-3-C 30분 평균 80% 모니터링 — `EVAL_POOL_OVERLOAD` risk.violation.v1 발동 시 사용자 통지.

---

## 10. Rollback 시나리오

### 10-1. 본 ADR Supersede 또는 Revoke case

1. **5m boundary 평가 latency 초과** — 평균 wall-clock > 1초 → boundary 다음 closing 전 미완료. → 비활성 strategy 정리 또는 strategy-eval-pool 크기 확장(ADR 0047 §2-3-A 4 → 8) 후속
2. **사용자 dogfooding 결함** — 시그널 false positive 누적 → strategy DSL 튜닝 의무 (본 ADR scope 외 — T-DSL 합류)
3. **신호 노이즈 과다** — 1,000/day가 사용자 noise → 사용자 설정 토글 (시그널 threshold strength ≥ N 만 알림) 후속 ADR

### 10-2. Rollback 절차

1. 본 ADR 상태 `Superseded by ADR XXXX, YYYY-MM-DD` 헤더 갱신
2. `BoundaryClosingKafkaListener` 5건 `@KafkaListener` 비활성 (또는 `@ConditionalOnProperty` 게이트)
3. `signal` 테이블 row 보존 (audit) + 신규 INSERT 0 — strategy.status 모두 PAUSED
4. STOMP `/topic/signal.user.{userId}` fanout 자연 휘발
5. ADR 0023·0047·0064 baseline 정합 본문 LOCK 표기 (변경 0건)
6. CHANGELOG row 추가 + master §10 ADR 인덱스 갱신

---

## 11. 채택 시 후속 작업 (별도 PR — 본 ADR `Accepted` 갱신 후 sub-agent 디스패치)

본 plan PR은 **architect 단독 — ADR + spec/plan 문서만**. 실무 코드 변경은 별도 sub-agent PR 매트릭스.

### 11-1. sub-agent 디스패치 매트릭스

| Phase | sub-agent | 영역 | 산출물 | 의존성 |
|---|---|---|---|---|
| **Phase 1** | **strategy-engine** | (1) `domain/.../signal/Signal.kt` · `SignalSide` · `SignalContext` · `SignalGenerator` port (Pure Kotlin). (2) `domain/.../strategy/Strategy.kt` 확장 · `StrategyStatus` · `StrategyDisabledReason` · `StrategyComplexity` · `EvalMetricSummary` · `StrategyRepository` port. (3) `consumer/.../signal/RealtimeSignalGenerator.kt` — boundary 평가 흐름 본체(§7-2 1:1 매핑). (4) `consumer/.../signal/SignalDedupeGuard.kt` — Redis SETNX 30s. (5) `consumer/.../signal/kafka/BoundaryClosingKafkaListener.kt` 5건 — Kafka listen → SignalEvaluator 위임. (6) `consumer/.../signal/kafka/SignalKafkaProducer.kt` — `signal.generated.v1` publish. (7) JvmStrategyEvaluator → strategy-eval-pool sandbox 통합 (ADR 0047 §2-2·§2-3 정합). (8) 단위 테스트 — RealtimeSignalGenerator 핵심 path (DUPLICATE_KEY skip · TIMEOUT auto-disable · HOLD null return) | 본 ADR §5·§7 1:1 매핑 + AI 호출 0 grep 검증 inline 주석 의무 |
| **Phase 2** | **backend-core** | (1) `api/.../persistence/strategy/StrategyJpaEntity.kt`·`StrategyJpaRepository.kt`·`StrategyJpaAdapter.kt` (ADR 0051 default fun 함정 회피). (2) `api/.../persistence/signal/SignalJpaEntity.kt`·`SignalJpaRepository.kt`·`SignalJpaAdapter.kt` (DUPLICATE_KEY handling). (3) `api/.../controller/StrategyController.kt` 6 endpoint + `api/.../controller/SignalController.kt` 2 endpoint (Swagger 4단 어노테이션 + 한국어 설명). (4) `api/.../application/strategy/StrategyService.kt` — register · toggle · reactivate · softDelete + ADR 0047 §2-1 complexity 계산. (5) `api/.../infrastructure/cache/RedisStrategyCache.kt` — `strategy:active:by_code:{code}:{interval}` cache + evict 정책. (6) `api/.../stomp/StompSignalDispatcher.kt` — Kafka `signal.generated.v1` consumer → STOMP fanout. (7) Flyway V14·V15·V16 (strategy · signal · strategy_eval_metric). (8) 통합 테스트 — REST 8 endpoint + DSL 등록 검증 + signal 시계열 페이징 | 본 ADR §4·§5·§6·§7 1:1 매핑 + AI 호출 0 inline 주석 의무 |
| **Phase 3** | **frontend** | (1) `frontend/src/pages/strategy/StrategyListPage.tsx` — `/strategy/list` 페이지 (룰 목록 · 토글 · 삭제). (2) `frontend/src/components/strategy/StrategyCard.tsx` — 룰 카드 (status 배지 + complexity 표시 + lastSignalAt). (3) `frontend/src/components/signal/SignalListPanel.tsx` — 시그널 시계열 (페이징 · 종목 필터 · 인터벌 필터). (4) `frontend/src/hooks/useStompSignal.ts` — STOMP `/topic/signal.user.{userId}` subscribe + 토스트. (5) `frontend/src/hooks/useStrategies.ts` · `useSignals.ts` — REST hook (TanStack Query). (6) routing 확장 (`/strategy/list` · `/strategy/signals`). (7) frontend test 5건 (Strategy CRUD · 토글 · STOMP 수신 · 토스트 표시 · 페이징) | 본 ADR §4 REST 8 endpoint + D-1 STOMP 1:1 매핑 |
| (선택) **qa** | sample DSL 룰 5건 dogfooding 검증 — 5m·15m·30m boundary closing 시 시그널 발행 evidence(DB SELECT + STOMP log inspect) + DUPLICATE_KEY skip 검증 + auto-disable 검증 | spec test 또는 dogfooding evidence 보고 |

### 11-2. 디스패치 모드

**직렬 권장 (Phase 1 → 2 → 3)**:
- Phase 1 머지 후 Phase 2 진입 — domain port + RealtimeSignalGenerator 본체 의존
- Phase 2 머지 후 Phase 3 진입 — REST API + STOMP fanout 의존

병렬 가능 case: Phase 1·Phase 2가 add-only 충돌 0 명확 시 (domain 신규 파일 + api 신규 파일 + Flyway 신규 파일). architect 충돌 행렬 점검 후 결정.

### 11-3. CHANGELOG 슬롯 사전 발행 (헌법 §4)

본 plan PR diff에 sub-agent별 CHANGELOG 슬롯 row + anchor 사전 prepend 의무:
- `<!-- pr-N-slot-strategy-engine -->` row
- `<!-- pr-N-slot-backend-core -->` row
- `<!-- pr-N-slot-frontend -->` row
- `<!-- pr-N-slot-end -->`

면제 조건 점검 (사용자 메모리 `feedback_spec_plan_scope.md`):
- hotfix? **아님** (T-SIG W3 신규 트랙)
- 결함 정정? **아님** (신규 기능)
- 작은 묶음? **아님** (신규 트랙 + 다중 ADR 합류)

→ **슬롯 사전 발행 의무 (헌법 §4 정합)**.

### 11-4. 영향 ADR/문서 매트릭스 (정합 갱신 지시)

| 문서 | 갱신 내용 | 갱신 시점 |
|---|---|---|
| **ADR 0023 (Signal Engine 룰 매칭 only)** | §2-1 도메인 모델 본문 갱신 — `Signal` 데이터 클래스 확장(userId·priceAtSignal·contextJson 추가) · §2-2 평가 흐름 본문에 boundary listener 단계 추가 · §2-3 두 모드 매트릭스에 RealtimeSignalGenerator 본 ADR 권위 참조 | 본 ADR `Accepted` 갱신 시 동시 (architect 단독) |
| **ADR 0047 (DSL Sandbox)** | §2-6 V26·V27 → V14·V15·V16 재할당 · §3-1 T-SIG 합류 매트릭스 본 ADR 권위 인용 | 본 ADR `Accepted` 갱신 시 동시 |
| **ADR 0064 (Redis boundary fanout)** | §7-1 sub-agent 매트릭스 — boundary listener consumer는 본 ADR 권위 명시 | 본 ADR `Accepted` 갱신 시 동시 |
| **ADR 0030 (master-architecture SoT) + master §10** | ADR 0066 인덱스 row 추가 + §C.11 Signal Engine 본문 갱신(boundary listener 흐름) | 본 plan PR 동시 |
| `_workspace/arch/adr/README.md` | ADR 0066 index row 추가 (§6 전략·신호·리스크 6→7건) + §12 후속 예정 0067~0080 시프트 | 본 plan PR 동시 |
| `_workspace/backlog.md` | §40 신규 등록(T-SIG R1·R2·R3 트랙) | 본 plan PR 동시 |
| `docs/architecture/tracks-roadmap.md` | T-SIG row 본 ADR 권위 인용 + 진입 표기 | 본 plan PR 동시 |
| `docs/architecture/development-rules.md` | 룰 5 영역 본 ADR scope 1 PR 한계 검증 (Phase 1·2·3 분리 권장) | 본 plan PR 동시 |
| `CHANGELOG.md` | spec/plan PR row 추가 + sub-agent 슬롯 3건 + anchor | 본 plan PR 동시 |

### 11-5. external-api-policy 영향

본 ADR 채택 시 외부 API 정책 영향 0 — KIS API 추가 호출 0건 + Kafka 내부 토픽만 활용.

---

## 12. 결과 (채택 시)

- **자동매매 본체 매매 결정 노드 가동** — 룰 기반 BUY/SELL/HOLD 시그널 실시간 발행 (boundary 마감 시점 <100ms latency)
- **ADR 0023 권위 W3 구현** — 룰 매칭 only · AI 호출 0 검증 의무
- **ADR 0047 sandbox 실행 path 통합** — strategy-eval-pool · timeout 100ms · auto-disable 정합
- **ADR 0064 boundary fanout 활용** — Kafka 5종 listen으로 latency 최소
- **사용자 UX** — `/strategy/list` 페이지 + 실시간 토스트 + 시그널 history 시계열
- **T-O·T-RISK 후행 의존 자산 LOCK** — `signal.generated.v1` Kafka topic이 후행 트랙 진입점
- **MCP 외부 client 정합 후속** — signal 시계열 endpoint를 후행 ADR 0027(MCP)에서 노출 가능

---

## 13. 참고

- ADR 0001 — 시스템 비전 (룰 본체 + AI 옵션)
- ADR 0007 — Kafka 이벤트 백본 (`signal.generated.v1` 신규)
- ADR 0010 — Multi-tenant (userId 격리)
- ADR 0021 — Indicator Source 추상화 (룰 평가 indicator() 경유)
- ADR 0022 — Strategy DSL `.kis.yaml` 호환 superset
- ADR 0023 — Signal Engine = 룰 매칭 only (본 ADR W3 구현 권위)
- ADR 0024 — Risk Guard chain 6단 (본 ADR 후행)
- ADR 0027 — MCP 서버 미래 노출 (후속 signal query API)
- ADR 0030 — master-architecture SoT
- ADR 0037 — KIS Rate Limit (영향 0)
- ADR 0042 — Position Bootstrap (T-O 합류)
- ADR 0044 — Notification System (`risk.violation.v1` payload 확장)
- ADR 0045 — Graceful Shutdown (Kafka listener container · strategy-eval-pool)
- ADR 0047 — DSL Evaluation Sandbox (sandbox 통합 권위)
- ADR 0051 — Spring Data Repository default fun 함정 (회피 패턴)
- ADR 0056 — Backtest Engine Architecture (활성화 게이트 정합 후속)
- ADR 0061 — Backtest 결과 영속 (3 테이블 + 5 endpoint 패턴 baseline)
- ADR 0064 — Redis boundary fanout + STOMP (시그널 평가 트리거 진입점)
- `docs/architecture/master-architecture.md` §C.11 (Signal Engine)
- `docs/architecture/tracks-roadmap.md` §2 T-SIG row
- `_workspace/backlog.md` §40 (본 ADR plan 발행 신규 등록)
- 사용자 메모리 `feedback_v3_skepticism_no_regression.md` (fitness gap lens) · `feedback_kis_first_policy.md` (KIS-only) · `feedback_3tier_hierarchy.md` (architect plan → sub-agent 병렬) · `feedback_spec_plan_scope.md` (작은 묶음 제외 정합) · `project_system_vision.md` (룰 기반 quant trading + MCP 비전)
