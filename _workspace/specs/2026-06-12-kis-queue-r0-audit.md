# R0 audit — KIS Rate Limit 큐 재설계 R1 착수 입력 (결재 후속)

- 상태: **R0 완료 (2026-06-12 · market-data)** — R1 착수 가능. 코드 0 touch (읽기+문서만).
- 작성: market-data (opus 4.8)
- 상위 spec: `_workspace/specs/2026-06-10-kis-ratelimit-queue-redesign-spec.md` (2026-06-12 결재됨)
- 결재 권위: 최고 관리자 (2026-06-12) — "큐 재설계까지 다 진행해줘" 4의제 전체 승인
- 정합 ADR: 0116(3-tier 버킷), 0048(WS 슬롯·polling fallback), 0100(T-MARKET cron), 0070(reconcile cron), 0081(account), 0037(rate limit 강제)
- 실측 방법: worktree HEAD 코드 read (grep `@Scheduled|acquire|tr_id|inter-request-delay` 전수 + 본문 정독). KIS 호출 0 · 자본 영향 0.

> 본 문서는 상위 spec §4-4 라운드 분할이 정의한 **R0 4개 작업**(L4 polling audit / L2 jitter 결정 / N1 장중 가드 / per-TR 화이트리스트)을 코드 실측으로 확정하고, R1~R3 dispatch 매트릭스를 산출한다. 결재 권고안 = **"대안 C(로컬 큐 + Redis 버킷) + per-TR(원장) 게이트"**.

---

## 0. R0 결론 요약 (3건 + 부수 2건)

| # | audit 대상 | spec 가정 | **실측 결론** | R1 입력 |
|---|-----------|----------|--------------|---------|
| **a** | L4 `WatchlistPollingWorker` REST polling 5초 존치 | UNCERTAIN(§1-E) — WS tick 권위와 중복 의심 | **존치 (제거 대상 아님)** — eviction된 WS 슬롯 부족 종목 + WS 단절 fallback 전용. 항상 도는 polling 아님(pool 빌 때 즉시 skip) | per-TR/큐 게이트에 polling fetch 합류만, 제거 X |
| **b** | L2 `MarketIndexScheduler` jitter 미적용 → 강제 vs 2분 하향 | jitter 미적용 시 BG 최대 압박 의심(§1-A) | **jitter 이미 적용됨(GREEN)** — `inter-request-delay-ms:250` × 26 ≈ 6.5초 분산(ADR 0116 §2-D 정합). **강제도 2분 하향도 불요** | 현행 유지. R1 변경 0 |
| **c** | per-TR(원장) 화이트리스트 확정 | inquire-balance·ccld·daily-ccld·psbl-order 추정(§4-5) | **3 TR 확정**(balance·daily-ccld 2계열) + **psbl-order는 코드 미사용 → 화이트리스트 제외(미래 합류 슬롯만)** | 아래 §3 TR_ID 표 그대로 R1 게이트 키 |
| (부수) | N1 Campaign 장중 가드 | 장중 실행 금지 가드 부재 의심 | **가드 부재 확인** — `MARKET_DAILY_BACKFILL_INTRADAY_BLOCK` ENV 신설 필요(R2) | R2 백필 정리 항목 |
| (부수) | F10 임시 수선 ↔ per-TR 근본 | — | F10(`preCheckFreeRules`)은 원장 TR **호출 빈도**만 줄임. per-TR **한도 표현** 부재 — 본 spec §4-5가 근본 | R1 per-TR 게이트 최우선 |

---

## 1. R0-a — L4 `WatchlistPollingWorker` 존치 여부 (결론: **존치**)

### 1-1. 실측 (코드 evidence)

- 파일: `consumer/.../ingestion/WatchlistPollingWorker.kt`
- 스케줄: `@Scheduled(fixedRate = 5000)` `tick()` — **단, `if (!running || pool.isEmpty()) return`** (L73). 즉 **pool이 비면 KIS 호출 0**. 항상 도는 polling이 아니다.
- fetch: pool 종목마다 `kisRestPort.fetchCandles(code, ONE_DAY, 1)` + `Thread.sleep(100)` 마진 (L80·L110).
- 주석 자인(L16~27): *"eviction 후 WS 슬롯 부족 종목을 5초 주기 KIS REST polling으로 시세 수집 / KIS WS 끊김 시 REST fallback 경로도 candle 영속을 보장."*

### 1-2. pool 주입 경로 — 누가 add/start 하는가 (trace)

| 호출처 | 트리거 | 의미 |
|--------|--------|------|
| `SubscriptionRouter.add` L64 `pollingWorker.add(evictionCandidate)` | WS 슬롯 가득 + priority 비교 → **낮은 종목 eviction** | WS에서 밀려난 종목만 polling |
| `SubscriptionRouter.add` L73 `pollingWorker.add(code)` | WS 슬롯 가득 + 본 종목이 최저 priority | WS 못 들어간 종목만 polling |
| `RealtimeSubscriptionRouter` L270 `pollingWorker.add(code)` | DETAIL/STRATEGY 수요 슬롯 부족 fallback | 동일 — 슬롯 부족 fallback |
| `KisWsRejectFallbackWiring` / `KisWebSocketBridge` L366·399 | **WS 구독 거부·단절 시** polling 전환 | WS 장애 fallback |
| `WatchlistIngestionWorker` L103 `pollingWorker.start()` / L136 `stop()` | 장 세션 전환 | graceful start/stop |
| `SubscriptionRouter.remove/recompute` L98·L120·L208 `remove` | WS 슬롯 회수 시 promotion | polling → WS 승격(pool에서 제거) |

### 1-3. 방향성 가드 정합 판정

CLAUDE.md 가드: *"watchlist는 KIS WS tick이 장중 실시간 권위. REST는 bootstrap/backfill/fallback."*

- L4는 **WS tick의 대체가 아니라 WS 슬롯 부족·단절 시 fallback**이다(ADR 0048 §D §3-2). pool은 평소 비어 있고(전 종목이 WS 슬롯 안이면 pool=0 → tick skip), **WS가 수용 못 하는 종목만** 한시적으로 5초 polling.
- 따라서 **가드 위반 아님 = fallback 본질에 부합**. ADR 0119(1m proactive backfill 폐지) 정신과도 무관(L4는 1d 캔들 fallback이며 proactive 전종목 backfill이 아님).
- **결론: 제거 대상 아님(존치).** spec §1-E의 UNCERTAIN을 **존치(GREEN)** 로 확정한다. spec §2-1 "L4 제거 or fallback 한정" 중 **이미 fallback 한정 상태**이므로 추가 제거 불요.

### 1-4. R1 입력

- L4는 **삭제하지 않는다.** 단 큐/per-TR 게이트 일반화(R1) 시 L4의 `fetchCandles` 경로도 **BACKFILL→BG priority로 로컬 큐에 합류**(현재 priority 미표기 — §1-A 표 "(현 코드 확인 필요)"였던 칸을 **BACKFILL→BG로 확정**). polling은 이미 `Thread.sleep(100)` self-pacing이 있으므로 큐 dispatcher pacing과 **중복 sleep 제거**가 R1 정리 포인트(100ms sleep → dispatcher paceMillis로 일원화).

---

## 2. R0-b — L2 `MarketIndexScheduler` jitter (결론: **이미 적용 · 현행 유지 GREEN**)

### 2-1. 실측 (코드 evidence)

- 스케줄러: `MarketIndexScheduler.syncEveryMinute` — `@Scheduled(cron = "0 * 9-15 * * MON-FRI")` → `marketIndexSyncJob.run("minute")` (라우팅만, jitter 없음).
- **jitter는 Job 내부에 있음**: `MarketIndexSyncJob.kt`
  - `@Value("...inter-request-delay-ms:250")` `interRequestDelayMs` (L62-63) — **default 250ms**.
  - `for (code in IndexCode.ALL_FOR_MINUTE_SYNC) { ... finally { delayBetweenKisCalls() } }` (L103·L118) — **매 endpoint 호출 사이 250ms sleep**.
  - 섹터·해외 fallback 경로(L198)에도 동일 `delayBetweenKisCalls()` 적용.
  - 주석 자인(L58-60): *"26 req/min burst를 시간 분산하여 BG 버킷 순간 소진 + EGW00201 방지. 250ms × 26 ≈ 6.5초 분산 → 1분 윈도 안 균등 발사 ... 순간 burst의 시간 분산은 caller(본 Job) 책임 (ADR 0116 §2-D 원칙)."*

### 2-2. 판정

- **spec §1-A·§2-1의 "jitter 미적용 시" 전제는 실측 결과 거짓**. ADR 0116 §2-D inter-call jitter는 **이미 R2에서 적용 완료**된 상태다(250ms × 26 ≈ 6.5초 분산 = 1분 윈도의 11% 점유, BG 3/s 버킷이 6.5초간 흡수 가능).
- 따라서 **jitter 강제(이미 됨)도, 2분 cron 하향(불필요)도 둘 다 R1 작업에서 제외.** 26 req가 6.5초에 균등 분산되므로 BG 최대 압박원이 아니다.
- **유일한 R1 합류점**: 대안 C의 로컬 dispatcher가 모듈별 pacing을 일반화하면, L2의 `delayBetweenKisCalls()`(250ms self-pacing)도 **dispatcher paceMillis(120ms)로 위임 가능**(중복 제거). 단 batch 모듈 dispatcher 도입 시점에만 — **현행 250ms는 그대로 두어도 안전(GREEN)**.

### 2-3. R1 입력

- **L2 변경 0.** 현행 250ms 분산 유지. R1 batch 모듈 로컬 큐 도입 시 `inter-request-delay-ms`를 dispatcher pacing으로 이관할지는 **R1 선택(필수 아님)** — paceMillis(120ms) < 250ms이므로 dispatcher로 옮기면 오히려 분산이 촘촘해져 안전. 단 batch 모듈 우선순위는 api·consumer보다 후순위(§4 매트릭스 R1-c).

---

## 3. R0-c — per-TR(원장) 화이트리스트 확정 (KIS-first raw fetch 정합)

### 3-1. 실측 — 원장 계열 TR_ID 전수 (코드 inline 주석 raw 출처 정합)

| 원장 TR(논리) | REAL TR_ID | MOCK TR_ID | endpoint | 호출처(컴포넌트) | KIS raw 정합 |
|--------------|-----------|-----------|----------|-----------------|-------------|
| **잔고조회** (inquire-balance) | `TTTC8434R` | `VTTC8434R` | `/uapi/domestic-stock/v1/trading/inquire-balance` | api `ApiKisBalanceQueryAdapter`(L124) · consumer `KisBalanceAdapter`(L56) · consumer `KisReconcileBalanceAdapter`(L82-83) · `PositionBootstrapRunner` | adapter inline 주석에 raw URL·tr_id 명시 확인 |
| **일별체결 (3개월내)** (inquire-daily-ccld inner) | `TTTC0081R` | `VTTC0081R` | `/uapi/domestic-stock/v1/trading/inquire-daily-ccld` | consumer `KisOrderInquireDailyCcldHttpAdapter`(L97·100·134) · `OrderReconciliationWorker` · `KisExecutionBackfillService` | adapter L36~46 raw 본문 시그니처 인용 |
| **일별체결 (3개월이전)** (inquire-daily-ccld before) | `CTSC9215R` | `VTSC9215R` | `/uapi/domestic-stock/v1/trading/inquire-daily-ccld` | 위 adapter(L103·106) — backfill 전용 분기 | 동일 endpoint, pd_dv 분기 |

### 3-2. psbl-order(주문가능) — 화이트리스트 제외

- spec §4-5는 `inquire-psbl-order`(주문가능)를 후보에 넣었으나 **grep 결과 코드에 해당 TR 호출 0건** (현재 미사용). 잔고(`inquire-balance`)를 매수가능 산정에 재사용하는 구조.
- **결론: psbl-order는 화이트리스트에서 제외.** 미래 합류 시 `TTTC8908R`/`VTTC8908R`(KIS 공식 주문가능조회 TR — raw fetch로 R 합류 시 확정) 슬롯만 예약.

### 3-3. per-TR 게이트 적용 키 (R1 LOCK)

- 게이트 키는 **논리 TR 단위로 묶는다** (REAL/MOCK은 동일 원장 한도 공유 추정 — 동일 endpoint).
  - `kis-rate-limit:tr:inquire-balance` ← {TTTC8434R, VTTC8434R}
  - `kis-rate-limit:tr:inquire-daily-ccld` ← {TTTC0081R, VTTC0081R, CTSC9215R, VTSC9215R}
- capacity = **1 req / 1s per 논리 TR** (보수적 시작 — spec §4-5, R3 부하 evidence로 조정).
- **R1 KIS-first 의무(헌법 §3)**: 원장 per-TR **공식 수치**는 KIS 문서에 명시값 미확인 상태 → R1 착수 시 `mcp__kis-code-assistant__*` 또는 open-trading-api raw로 1회 재확인. 미확인이면 1/s 보수값 LOCK + 코드 inline에 "공식 미명시·보수값" 주석 의무.

### 3-4. F10 임시 수선 ↔ per-TR 근본 (결재 evidence 정합)

- 오늘 F10(`8644f10a` canary D2 #5 `RiskGatewayEvaluator.preCheckFreeRules`)은 **시그널 평가 시 공짜 룰(KillSwitch·동시보유·거래빈도)을 KIS 원장 TR 호출보다 먼저** 평가하여 **차단될 시그널이 원장 TR을 소모하지 않게** 했다. 이는 원장 TR **호출 횟수**를 줄이는 순서 재배치일 뿐, **원장 per-TR 한도 자체를 코드가 표현하지 못한다**.
- 따라서 BUY 시그널이 다발하거나 reconcile(L3)·account(L6)와 동시간대에 몰리면 **per-TR 한도 재돌파 가능** → 14:12 총자산 0원 fabricate incident 재현 risk. **근본 = 본 spec §4-5 per-TR sub-limit 게이트(R1).**
- **R1 정합 포인트**: per-TR 게이트는 F10과 **상호 보완**(F10=호출 빈도↓, 게이트=한도 표현). F10의 `preCheckFreeRules` 순서는 유지하고, KIS 호출이 실제 발생하는 잔고·체결 adapter 진입에 per-TR 게이트를 add-only.

---

## 4. R1~R3 dispatch 매트릭스 (라운드 분할 — 결재 후 착수)

> 상위 spec §4-4 라운드 + R0 실측 보정. 한 PR = 한 영역(개발룰 §2) + 한 layer(개발룰 §3) 준수. 병렬 충돌 행렬 명시.

### R1 — 로컬 큐 + dispatcher pacing + per-TR 게이트 (코어, 테스트 0 OK)

| ID | 담당 | 모듈/영역 | 산출 | 의존 | 병렬성 |
|----|------|----------|------|------|--------|
| **R1-a** | backend-core | (공용) domain/port + 로컬 우선순위 큐 + dispatcher(paceMillis 120ms·USER_PATH fast-path) 인터페이스 | `KisLocalQueueDispatcher` port + in-memory 큐 골격 | — | **선행(blocking)** — R1-b·c·d 진입점 |
| **R1-b** | market-data | api 모듈 | api KIS adapter 진입점에 큐 dispatcher 게이트 합류(`acquire` 앞단) + **per-TR 게이트**(inquire-balance·daily-ccld §3-3) | R1-a | R1-c·d와 add-only 병렬 가능 |
| **R1-c** | market-data | batch 모듈 | batch(MarketIndexSyncJob) 큐 합류 — **L2 250ms→dispatcher 위임 선택** | R1-a | 병렬 가능 (R1-b와 다른 모듈) |
| **R1-d** | market-data | consumer 모듈 | consumer KIS adapter(balance·reconcile·daily-ccld·**L4 polling**) 큐+per-TR 게이트 합류 | R1-a | **충돌 주의**: 옆 세션이 `OrderbookSubscriptionRouter`·`KisRestAskingPriceAdapter` 수정 중 — 본 R1-d는 balance/ccld/polling adapter만, 호가 adapter 제외 |
| **R1-e** | architect | ADR | ADR 0116 amend §2-F (또는 신규 0122) — 로컬 큐+per-TR 본문화 + master §10 + external-api-policy §2 동시 갱신 | R1-a~d 설계 확정 후 | 문서 — 코드와 병렬 |

**R1 ENV(add-only)**: `MAGICJAR_KIS_LOCAL_QUEUE_ENABLED`(true)·`_PACE_MILLIS`(120)·`MAGICJAR_KIS_TR_SUBLIMIT_ENABLED`(true). Flyway 0 · Kafka 0 · Redis add-only(`kis-rate-limit:tr:{logicalTr}`).

### R2 — 백필 정리 가드 + EGW00201 회복/실패 2-시그널 + Phase 3 CB sketch

| ID | 담당 | 모듈/영역 | 산출 | 의존 |
|----|------|----------|------|------|
| **R2-a** | market-data | consumer | N1 `MarketDailyBackfillCampaign` 장중(09:00~15:30) 실행 금지 hard 가드 + ENV `MAGICJAR_MARKET_DAILY_BACKFILL_INTRADAY_BLOCK`(true) | R1 |
| **R2-b** | market-data | api(공통 retry helper) | EGW00201 `onTpsExceededRecovered`/`onTpsExceededFailed` 2-시그널 분리(spec §5-2) + Micrometer `outcome` tag. caller ~30곳 catch 1~2줄 add | R1 · ADR 0095 amend 정합 |
| **R2-c** | market-data | (공통) | Phase 3 Resilience4j CB를 로컬 큐 dispatcher loop에 합류 sketch(OPEN 시 BG/INGESTION release 중단, USER_PATH half-open probe) | R1-a |
| **R2-d** | (검토) | consumer | L4 polling `Thread.sleep(100)` ↔ dispatcher paceMillis 중복 제거 + L3 reconcile 보유종목 0 skip 가드 | R1-d |

### R3 — 부하 검증 + 테스트 일괄

| ID | 담당 | 산출 | 게이트 |
|----|------|------|--------|
| **R3-a** | market-data + qa | 장중 3 cycle EGW00201/EGW00215 **0 수렴** 실측(Prometheus `kis_ratelimit_tps_exceeded_total{outcome,tr_id}` + `tr_sublimit_throttle_total`) | S-4 0 수렴 |
| **R3-b** | market-data | paceMillis 100/120/150 부하 evidence로 LOCK + per-TR 1/s 적정성 검증 | S-1 P95<5s 유지 |
| **R3-c** | qa | 큐·per-TR 게이트 테스트 일괄(개발룰 §4 — R1·R2 테스트 0 → R3 일괄) | :api:check·:consumer:check GREEN |

### 병렬 충돌 가드 (본 세션 옆 작업 정합)

- **현재 옆 market-data 세션이 `OrderbookSubscriptionRouter`·`KisRestAskingPriceAdapter` 병렬 수정 중.** R1-d(consumer)는 **balance·reconcile·daily-ccld·L4 polling adapter만** touch — **호가(asking-price)·orderbook router 제외**. R1 dispatch 시 파일 경계 LOCK으로 충돌 0 보장.
- R1-a(backend-core 코어)는 신규 파일(`KisLocalQueueDispatcher`)이라 add-only — 기존 파일 충돌 0.

---

## 5. 자원 audit 재확인 (R0 보정 — C+A add-only GREEN)

| 자원 | R0 실측 보정 | 결론 |
|------|-------------|------|
| Flyway | 큐·per-TR 모두 Redis/in-memory — V 신설 0 | GREEN |
| Redis | add-only `kis-rate-limit:tr:inquire-balance`·`:inquire-daily-ccld`(논리 TR 2키, TTL 자동) | GREEN |
| ENV | add-only 3건(§R1) + R2 1건(intraday block) | GREEN |
| Kafka·STOMP·REST | 0 신설 | GREEN |
| **L2 jitter** | **이미 적용 — 신규 작업 0** | GREEN(보정) |
| **L4 polling** | **존치 — 제거 작업 0**, 큐 합류만 | GREEN(보정) |

---

## 6. 변경 이력

| 일자 | 변경 | 작성 |
|------|------|------|
| 2026-06-12 | R0 audit 신설 — 결재 후속. L4 polling 존치(fallback 본질 확정, 제거 X) + L2 jitter 이미 적용(250ms×26 GREEN, 강제·하향 둘 다 불요) + per-TR 화이트리스트 3 논리 TR 확정(balance·daily-ccld inner·before / psbl-order 미사용 제외) + F10↔per-TR 근본 정합(F10=호출 빈도↓·per-TR=한도 표현, 상호 보완) + R1~R3 dispatch 매트릭스(R1 코어/per-TR · R2 백필 가드/2-시그널/CB · R3 부하·테스트) + 병렬 충돌 가드(호가 adapter 제외). 코드 0 touch · KIS 호출 0 · 자본 0 · AI 0 · mock 0. (market-data, opus 4.8) | market-data |
