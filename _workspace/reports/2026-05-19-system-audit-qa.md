# magicJar 시스템 점검 보고서 — qa 경계면 audit + hotfix 의제

**작성**: qa (`bot+qa@magicjar.local`)
**발행일**: 2026-05-19 KST
**Task**: T-20260519-1649-system-audit-2026-05-19
**근거 권위**: `feedback_qa_on_demand` (사용자 메모리) · ADR 0004 (모듈 경계) · ADR 0036 §1 (task cycle) · ADR 0084 (P1-1·P1-2 outbox)
**dispatch 유형**: orchestrator 명시 시스템 점검 사이클 — on-demand 7 트리거 중 "사용자 명시 점검" 정합
**main HEAD**: `2a2225d` (v3.6.16 마감 후 devlog 발행 시점)

---

## 0. 요약 (TL;DR)

| 축 | 결과 | 등급 |
|---|---|---|
| 테스트 커버리지 | 145 JVM XML / 861 testcases + frontend 34 file / 210 case = 1,071 케이스 | GREEN |
| 모듈 경계 (ADR 0004) | api↔consumer↔domain↔batch 4축 침범 0건 / domain self-only / indicator-impl·pattern-engine 위배 0건 | GREEN |
| 통합 시나리오 (smoke.sh) | 3 시나리오 (backtest·tick→stomp·news→ai) 정합 — 9 컨테이너 healthy | GREEN |
| dogfooding 흔적 (docker logs ERROR 1h) | api 0 / batch 0 / consumer 0 / frontend 0 | GREEN |
| dogfooding 흔적 (WARN 1h) | consumer **OrderOutboxPublisherWorker 720건** / api NaverNewsRateLimiter 31건 / consumer BoundaryAggregator 13건 | **RED (P0 1건 + P2 2건)** |
| hotfix 의제 도출 | **P0 1건** (outbox repository wiring 결함) · **P1 1건** (BoundaryAggregator stale 데이터) · **P2 2건** (Naver rate limit · BoundaryAggregator 정상화) | — |

**핵심 발견**: ADR 0084 P1-1·P1-2 (outbox 영속화 트랙)이 PR 머지 완료 상태이지만, **consumer 컨테이너 실런타임에서 repository wiring이 아예 적용되지 않음** — 12회/분 × 60분 = 720건/h WARN. 어댑터가 api 모듈에만 위치하여 consumer classpath에서 빈 발견이 원천적으로 불가. **이중 게이트 검증(PR 머지·gradle GREEN) 우회 사례** — `feedback_verification_strict` (할루시네이션 금지) 메모리 정합 검증 필요.

---

## 1. 테스트 커버리지 매트릭스

### 1-1. JVM 모듈별 (./gradlew test — BUILD SUCCESSFUL, FROM-CACHE)

| 모듈 | 테스트 클래스 (XML) | testcases 추정 |
|---|---:|---:|
| domain | 3 | (소형) |
| api | 66 | (대형) |
| batch | 5 | (소형) |
| consumer | 62 | (대형) |
| indicator-impl | 5 | (소형) |
| pattern-engine | 4 | (소형) |
| **합계** | **145** | **861** |

### 1-2. Frontend (pnpm test 미실행, 정적 grep)

| 카테고리 | 파일 수 | `it()` / `test()` 호출 |
|---|---:|---:|
| `__tests__/` 일반 | 22 | — |
| `dsl/` | 3 | — |
| `strategy/` | 3 | — |
| `journal/` | 3 | — |
| 기타 | 3 | — |
| **합계** | **34** | **210** |

사용자 dispatch 명시 "209 카운트"는 +1 차이로 거의 일치. 정합 OK.

### 1-3. 총합

- JVM 861 + frontend 210 = **1,071 testcases**
- 사이클 BUILD SUCCESSFUL · 모듈 6/6 PASS · 실패 testcase 0건

---

## 2. 모듈 경계 (ADR 0004) 정합 grep

### 2-1. 침범 매트릭스

| from → to | 위반 카운트 | 판정 |
|---|---:|---|
| `domain → api` | 0 | OK |
| `domain → batch` | 0 | OK |
| `domain → consumer` | 0 | OK |
| `api → consumer` | 0 | OK |
| `consumer → api` | 0 | OK |
| `consumer → batch` | 0 | OK |
| `indicator-impl → api·batch·consumer` | 0 | OK (domain·self만 의존) |
| `pattern-engine → api·batch·consumer` | 0 | OK (domain·self만 의존) |

### 2-2. domain 모듈 자체 의존 (정합 reference)

`grep -rn "import kr.spdaewoon.magicjar" domain/src/main/` 결과:
- domain.account / audit / backtest / common / indicator / indicator.port / instrument / instrument.corpaction / journal / market / market.candle / market.session / market.tick / notification / order / order.event / order.execution / pattern / performance / position
- **모두 domain.\* self-import** — Spring·JPA·외부 모듈 0 의존 LOCK 정합

### 2-3. consumer 모듈 의존 LOCK (settings.gradle.kts 정합)

```kotlin
implementation(project(":domain"))
implementation(project(":pattern-engine"))   // JvmStrategyEvaluator
implementation(project(":indicator-impl"))   // IndicatorEngineConfig
```

`:api` 의존 0건 — ADR 0004 §4-2 (api↔consumer는 Kafka topic만 매개) 정합.

**결론**: ADR 0004 정합성 **perfect (위반 0건)**.

---

## 3. 통합 시나리오 매트릭스

### 3-1. smoke.sh 3 시나리오 정합

| ID | 시나리오 | 검증 경로 | scripts/smoke.sh 정합 |
|---|---|---|---|
| 1 | 백테스트 트리거 → 매매일지 | `POST /api/backtest` → `GET /api/journal` | line 83-103 OK |
| 2 | 시세 → 신호 → STOMP fanout | Kafka publish + api `/actuator/health` UP | line 104-127 OK |
| 3 | 뉴스 크롤 → AI 분석 | `POST /api/news/scrape` → `news.analyzed.v1` published | line 129-145 OK |

stale jar 가드 (line 21-49): 코드 mtime > jar mtime 시 abort — 운영 함정 §1 정합.

### 3-2. 컨테이너 상태 (docker ps)

| 컨테이너 | 상태 | 비고 |
|---|---|---|
| magicjar-api | Up 3h healthy | image `magicjar/api:p4` |
| magicjar-batch | Up 4h healthy | image `magicjar/batch:p4` |
| magicjar-consumer | Up 1h healthy | image `magicjar/consumer:p4` (재기동 흔적) |
| magicjar-frontend | Up 1h healthy | image `magicjar/frontend:p4` |
| magicjar-kafka | Up 40h healthy | apache/kafka:3.7.0 |
| magicjar-postgres | Up 40h healthy | postgres:16-alpine |
| magicjar-redis | Up 40h healthy | redis:7-alpine |
| magicjar-kafka-ui | Up 40h healthy | provectuslabs/kafka-ui |
| magicjar-dozzle | Up 40h | amir20/dozzle |

9 컨테이너 모두 healthy / 다운 0건.

### 3-3. dogfooding case 누적 매트릭스 (최근 24h git log 기반)

| Task ID | Phase | 결과 |
|---|---|---|
| T-20260518-2207 P0-1 (KIS hashkey header) | hotfix done | MERGED |
| T-20260518-2207 indicator-impl 모듈 분리 | implementation done | MERGED |
| T-20260518-2208 T-ACCT Phase 3 capital snapshot | done | MERGED |
| T-20260518-2241 P0-2 pending reconcile | done | MERGED |
| T-20260518-2241 P0-4 kafka DLQ | done | MERGED |
| T-20260518-2333 P0-4 ConsumerFactory hotfix | done | MERGED |
| T-20260519-0010 T-ACCT Phase 4 dashboard | done | MERGED |
| T-20260519-0010 ADR 0083·0084 Accepted | done | MERGED |
| T-20260519-1034 ADR 0084 P1-1 order-event-outbox | done | MERGED (P0 결함 1 후술) |
| T-20260519-1034 ADR 0084 P1-2 stomp-event-persistence | done | MERGED (P0 결함 1 후술) |
| T-20260519-1318 T-CC Phase 2A audit JSON + killswitch | hotfix done | MERGED |
| T-20260519-1339 STOMP event payload jsonb cast | hotfix done | MERGED (v3.6.18 cut 차단 해소) |
| T-20260519-1413 한국석유 investigation report | report done | MERGED (PR #535) |
| T-20260519-1457 P0 KIS ASC + LWC dispose | hotfix done | MERGED |
| T-20260519-1457 hedging rules §11·§12·§13 amend | done | MERGED |
| T-20260519-1521 T-CC Phase 2B §11 KIS ASC residual | done | MERGED (PR #540) |
| T-20260519-1523 T-CC Phase 2B §12·§13 | done | MERGED (PR #541) |
| T-20260519-1601 chart-curator 페르소나 재정의 | done | MERGED (PR #544) |
| T-20260519-1622 backend-core 페르소나 부여 | done | MERGED (PR #546) |

총 19 task / 사용자 dispatch 명시 매트릭스 정합. 사이클 흡수 안정.

---

## 4. dogfooding 흔적 audit (docker logs 1h)

### 4-1. ERROR 빈도 (4 모듈)

| 모듈 | ERROR 수 | 상태 |
|---|---:|---|
| magicjar-api | 0 | GREEN |
| magicjar-batch | 0 | GREEN |
| magicjar-consumer | 0 | GREEN |
| magicjar-frontend | 0 | GREEN |

**Exception/stacktrace 검색 → 2h 기간 0건 발견** — `feedback_verification_strict` (할루시네이션 금지) 의무 evidence 정합.

### 4-2. WARN 빈도 (4 모듈, 1h)

#### api (75건)

| Logger | 카운트 | 패턴 |
|---|---:|---|
| `WatchlistNewsScheduler` | 31 | rate limit 초과로 종목 skip |
| `NaverNewsRateLimiter` | 31 | 분당 17회 한도 도달 |
| `DisclosureKafkaProducer` | 10 | DART disclosure publish 관련 |
| `CandleRefreshService` | 3 | 캔들 새로고침 lazy fallback |

→ Naver/CandleRefresh 31+3 = **P2 의제** (운영 정책 조정 — `external-api-policy.md` rate 조정).

#### batch (0건) — GREEN

#### consumer (754건)

| Logger | 카운트 | 패턴 |
|---|---:|---|
| `OrderOutboxPublisherWorker` | **720** | "OrderEventOutboxRepository 미등록 — drain worker skip" — **5초마다 12회/분 발사** |
| `BoundaryAggregator` | 13 | "1m data 없음" — 장 마감 후 (KST 16:00+) stale |
| `NotificationOutboxRetryWorker` | 12 | (별도 worker) |
| `KisWebSocketBridge` | 4 | (재구독 등 정상 신호 추정) |
| `BalanceReconcileService` | 3 | (정합 점검 흔적) |
| `NotificationDispatcher` | 2 | "NotificationOutboxRepository 미등록 — outbox INSERT skip" — reconcile.run.v1 topic |

→ **OrderOutbox 720 + NotificationOutbox 2 = P0 의제** (후술 §5).

#### frontend (0건 ERROR/WARN) — GREEN

### 4-3. 결함 시그널 evidence 발췌

```
15:51:12.732 [-] [scheduling-1] WARN  k.s.m.c.o.OrderOutboxPublisherWorker
  - OrderEventOutboxRepository 미등록 — drain worker skip
15:51:17.736 [-] [scheduling-1] WARN  k.s.m.c.o.OrderOutboxPublisherWorker
  - OrderEventOutboxRepository 미등록 — drain worker skip
... (5초 주기, 720건/h)

15:55:00.739 [...] [...] WARN  k.s.m.c.n.NotificationDispatcher
  - NotificationOutboxRepository 미등록 — outbox INSERT skip. topic=reconcile.run.v1 userId=7468****
```

### 4-4. 한국석유 dogfooding cycle 후속 검증

- T-20260519-1413 보고서 (`_workspace/reports/2026-05-19-kor-petroleum-investigation-report.md`) 발행 완료
- ADR 0086 §11·§12·§13 hedging rules amend → T-CC Phase 2B §11·§12·§13 코드 fix 완료 (PR #540·#541 머지)
- BoundaryAggregator에서 010280·195500·004090 등 종목 "1m data 없음" 13건 — **장 마감 후 정상 패턴 + Phase 2B fix 흡수 후 잔여** → 잔여 후속 검증 P2 의제

---

## 5. hotfix 의제 매트릭스

### P0-A. **Outbox Repository wiring 결함 (consumer 컨테이너 빈 미등록)**

#### 결함 요약

- 사실 #1: `OrderEventOutboxRepository` port 위치 → `domain/src/main/kotlin/.../domain/order/event/port/OrderEventOutboxRepository.kt`
- 사실 #2: `OrderEventOutboxRepositoryAdapter` 어댑터 위치 → **`api/src/main/kotlin/.../api/persistence/order/OrderEventOutboxRepositoryAdapter.kt` (api 모듈)**
- 사실 #3: consumer는 `build.gradle.kts` 상 `:api` 의존 0 — ADR 0004 정합
- 사실 #4: `OrderOutboxPublisherWorker.kt:45` 에서 `ObjectProvider<OrderEventOutboxRepository>` 로 optional 주입
- 사실 #5: consumer 컨테이너 classpath에 api 어댑터 jar 없음 → ObjectProvider always empty → drain worker skip 720회/h

같은 패턴이 `NotificationOutboxRepository` (api/persistence/notification/NotificationOutboxRepositoryAdapter.kt)에도 발생.

#### Root Cause

ADR 0084 P1-1·P1-2 트랙 도입 시 **어댑터 모듈 배치 결정 누락**:
- port (domain) + JPA 어댑터 (api) + 사용처 (consumer) 3축 분리
- consumer는 api 의존이 금지되므로 어댑터를 발견할 수 없음
- 결과: PR 머지 완료·gradle GREEN·테스트 PASS 이지만 **운영 흐름에서는 작동 0%**

#### Evidence

- consumer/order/OrderOutboxPublisherWorker.kt L45·L64
- api/persistence/order/OrderEventOutboxRepositoryAdapter.kt L21
- consumer/build.gradle.kts dependencies block (api 의존 없음)
- docker logs magicjar-consumer 720건 WARN (위 §4-3)

#### 수정안 매트릭스

| 옵션 | 내용 | trade-off |
|---|---|---|
| **A. 어댑터 consumer 모듈로 이동** | `api/persistence/order/OrderEventOutboxRepositoryAdapter.kt` → consumer로 이동. JPA dependency 추가 | consumer에 JPA 도입 — 단순하지만 추가 결합 |
| **B. 공유 persistence 모듈 신설** | 신규 `persistence-shared` 모듈에 어댑터 배치 → api·consumer 양쪽 의존 | 깔끔하지만 ADR 신설 필요 |
| **C. consumer가 api jar 의존** | consumer build에 `implementation(project(":api"))` 추가 | ADR 0004 위반 — **금지** |
| **D. Kafka topic 게이트 변경** | consumer가 직접 DB 안 쓰고 outbox topic publish만, api가 consume + DB write | 가장 큰 변경 (ADR 0084 자체 재검토) |

→ 권장: **B (persistence-shared 모듈)** 가 ADR 0004 정합 + 미래 batch도 재사용 가능. spec/plan 발행 후 backend-core 또는 architect 디스패치 추천.

#### 우선순위 사유

`feedback_verification_strict` 메모리: "PR 머지·gradle GREEN만으로 완료 종결 금지. 실증 evidence 의무"의 정확한 위반 사례. **할루시네이션 PR 의심 케이스** — backlog §우선 발사.

---

### P1-A. **BoundaryAggregator 1m candle stale 잔여 13건**

#### 증상

장 마감 후(KST 16:00+) `BoundaryAggregator 1m data 없음: code=XXXXXX interval=5m/15m/30m` WARN 13회/h. 종목: 010280, 195500, 001470, 027740, 004090 등.

#### 추정

- T-CC Phase 2B §12 active gap detection fix 흡수 후 일부 종목 잔여
- 또는 장 마감 후 1m candle 미발생이 자연 — WARN 등급 자체가 과도

#### 권장

- 옵션 1: 장 종료 시간 (15:30 KST 또는 16:00) 이후에는 1m boundary aggregator schedule skip
- 옵션 2: WARN → DEBUG 로깅 등급 조정 (정상 흐름에 가깝다면)

market-data 또는 chart-curator 디스패치.

---

### P2-A. **NaverNewsRateLimiter 분당 17회 한도 도달 31건/h**

#### 증상

`[NaverRateLimit] 분당 한도 초과 — count=17 limit=17 key=news:ratelimit:naver`
WatchlistNewsScheduler 종목 skip 잔여 15·14·13 종목 패턴.

#### 추정

`docs/architecture/external-api-policy.md` 정책 vs 실제 watchlist 종목 수 mismatch.

#### 권장

- 옵션 1: watchlist 종목당 호출 빈도 조정 (현행 1분 1회 → 2분 1회 등)
- 옵션 2: Naver rate limit 상향 (가능 시)
- 옵션 3: 종목 분산 (한 번에 10종목 vs 5종목)

market-data 디스패치.

---

### P2-B. **NotificationOutboxRetryWorker WARN 12건/h**

P0-A 후속 결함과 같은 원인일 가능성 → P0-A fix 후 자동 흡수 검증.

---

## 6. 영역 외 침범 0건 검증

- 본 audit 작업은 read-only (grep · find · docker logs · 보고서 작성)
- 운영 코드 0 touch
- 본 worktree의 gradle wrapper jar 1건 복원 (`gradle/wrapper/gradle-wrapper.jar`) — staged 안 함 (커밋 대상 외)
- ADR 0036 §1 task cycle 정합 — start → check → done 4단계 진입

---

## 7. 권장 후속 디스패치

| 순서 | 의제 | 추천 에이전트 | 예상 사이클 |
|---:|---|---|---|
| 1 | **P0-A outbox repository wiring fix** — 옵션 B (persistence-shared 모듈) spec 발행 | architect → backend-core | spec 0.5d + impl 1d |
| 2 | P1-A BoundaryAggregator stale 잔여 검증 | chart-curator 또는 market-data | 0.5d |
| 3 | P2-A Naver rate limit 조정 | market-data | 0.3d |
| 4 | P2-B NotificationOutboxRetryWorker (P0-A 흡수 후 재검증) | qa | 0.1d (검증만) |

---

## 8. 작업 종료 evidence

- task 파일: `_workspace/tasks/T-20260519-1649-system-audit-2026-05-19.md` (start RUNNING)
- 본 보고서: `_workspace/reports/2026-05-19-system-audit-qa.md`
- 영역: qa 경계면 audit 단독 (운영 코드 0 touch)
- author: `bot+qa@magicjar.local` / `magicjar-qa`
- 모듈 경계 위반: 0건
- 테스트 카운트: JVM 861 + frontend 210 = 1,071 케이스
- ERROR 1h: 4 모듈 모두 0건
- **P0 1건 (outbox wiring) — 사용자 결재 필요**

---

**작성**: qa
**근거 메모리**: `feedback_qa_on_demand` · `feedback_verification_strict` · `feedback_release_policy_patch_first`
