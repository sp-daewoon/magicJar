# spec — KIS Rate Limit 큐 기반 재설계 + 백필 인벤토리 전수조사 + 정리안

- 상태: **결재됨 (2026-06-12 최고 관리자 — "큐 재설계까지 다 진행해줘" 4의제 전체 승인 · R0 즉시 착수)** — 권고안 "대안 C + per-TR(A)"(§4-6) 채택 확정. R0 audit 산출물: `_workspace/specs/2026-06-12-kis-queue-r0-audit.md`. (직전: Proposed 2026-06-10)
  - **결재 evidence(오늘 incident 인용)**: 단일 전략 운영에서도 **원장 per-TR 한도(EGW00215 계열)가 BUY 평가 잔고조회를 직격**. 2026-06-12 REAL canary D2 — 시그널마다 KIS 원장 TR(체결동기화·잔고)을 공짜 룰(KillSwitch·동시보유·거래빈도)보다 **먼저** 호출 → 차단될 시그널도 원장 TR 소모하여 자가 압박(EGW00201/EGW00215 자가 기여). 14:12 무렵 **총자산 0원 fabricate incident** 발생 → backend-core hotfix **F10(`8644f10a` canary D2 배치 A #5 `preCheckFreeRules` — I/O 0 룰 선차단으로 원장 TR 호출 자체를 줄이는 순서 재배치)** 으로 **임시 수선**. F10은 "원장 TR을 덜 부른다"일 뿐 **per-TR 한도를 구조적으로 표현하지 못함** — 근본 해소는 본 spec §4-5 per-TR(원장) sub-limit 게이트. → **결재 핵심 동기: 다전략 확장이 아니라 단일 전략 운영에서도 이미 원장 per-TR가 자본 path를 직격하므로 R1 즉시 착수.**
- 작성: architect (opus 4.8 1M)
- 브랜치: `agent/architect/kis-ratelimit-queue-redesign-spec`
- 배경 권위: 최고 관리자 지시 (2026-06-10) — "버킷을 잘게 쪼개서 큐로 관리하는 큰 그림 + 내부 백필 정리"
- 정합 ADR: **0116**(3-tier 버킷 — 본 spec이 amend/연장 후보), 0095(SLO S-4 alert — 병렬 amend), 0037(rate limit 강제), 0100(T-MARKET cron), 0105(1m backfill TPS budget), 0118(전종목 일봉 universe), 0119(1m backfill 폐지·on-demand 전환), 0043(KillSwitch), 0070(reconcile cron)
- 참조 보고서: `_workspace/reports/2026-05-22-kis-tps-exceeded-root-cause.md` · `_workspace/reports/2026-06-04-api-tps-exceeded-investigation.md`
- 참조 정책: `docs/architecture/external-api-policy.md` §2 (KIS 한도 SoT)

> **본 spec은 설계 문서다.** 코드·ADR 본문화는 사용자 결재 후 별도 사이클(R1~). 본 문서는 (1) 백필/폴링 전수조사 인벤토리, (2) 통폐합·정리안, (3) 큐 기반 재설계 대안 비교 + 권고, (4) EGW00201 회복/실패 분리 시그널 설계 4부로 구성한다.

---

## 0. 요약 (사용자 결재용 1페이지)

### 0-1. 진단 (orchestrator 실측 evidence — 2026-06-10)

10 TPS를 지키는데도 EGW00201이 발생하는 **3대 root cause**:

1. **윈도 straddle** — `refillIntervally(N, 1s)`는 1초 **경계마다 일괄 충전**한다. KIS가 sliding/sub-second 윈도라면 0.999초에 N건 + 1.001초에 N건 = 사실상 ~2초 안 2N건이 **인접 100~200ms에 몰려** 발사될 수 있다 (코드 자인: `RedisKisRateLimiterAdapter.kt:183-188` 주석). 버킷만 잘게 나눠도 **각 버킷이 동일 경계에서 동시 충전**되면 straddle은 그대로다.
2. **원장(계좌) API per-TR 한도 미표현** — KIS는 잔고/체결조회 등 **원장 계열 TR에 글로벌 10 TPS와 별개의 더 엄격한 per-API 한도**를 둔 것으로 추정된다("원장에서 허용 가능한 초당 거래건수 초과" 메시지, `AccountQueryService.kt:73` 주석 — refresh 3연발 36/40/44s burst 직격 evidence). 현행 글로벌+priority 버킷 모델에는 **per-TR sub-limit를 표현할 자리가 없다**.
3. **백필 다발성 + 동시 발사** — consumer 일봉 backfill campaign(`concurrency=10` coroutine Semaphore)이 도는 동안 bg/shared에서 산발 EGW00201(12:15~12:18 count 7→11 실측). 1s backoff로 자동 회복되지만 **사용자 알람화**된다(→ ADR 0095 amend 병렬 처리 중).

### 0-2. 사용자 핵심 판단

> "버킷을 각각 나누는 것만으로는 해소가 안 될 것 같다. 버킷을 잘게 쪼개서 **큐로 관리**하는 큰 그림이 필요하다. 그리고 내부적으로 **백필 도는 게 너무 많아서 백필 정리까지** 고려해야 한다."

→ architect 동의. **버킷 분리(ADR 0116)는 priority 격리는 풀지만 straddle·per-TR·발사 타이밍 본질은 못 푼다.** 본질은 **"누가 언제 발사하는가"를 caller에게 맡기지 말고 단일 디스패처가 sub-second 균등 스케줄**하는 것이다.

### 0-3. 권고 결론 (§4-6 본문)

**대안 C (하이브리드 — 모듈 내 로컬 우선순위 큐 + Redis 버킷 유지) 를 권고.** 단, **per-TR(원장) sub-limit(대안 A의 핵심)을 C에 흡수**한다. 즉 **"C + A의 per-TR 한도"** 가 권고안이다.

- 대안 B(중앙 분산 큐)는 straddle을 가장 깨끗이 풀지만 Redis 분산 큐 + single dispatcher 3모듈 합의 = MVP 과대설계 + 주문 latency·SPOF risk → **REJECT (post-MVP)**.
- 대안 C는 각 모듈 어댑터 진입점에 **로컬 토큰 스케줄러(sub-second 균등 발사 pacing) + 우선순위 큐**를 두고, 분산 정합은 **현 Redis 버킷이 cross-instance ceiling**으로 계속 담당한다. straddle은 caller-side pacing(이미 H1 150ms·LiveDaily 200ms로 부분 적용 중)을 **버킷 진입 게이트로 일반화**해서 해소한다.
- per-TR 한도(A)는 원장 계열 TR(잔고·체결조회)에 **TR별 추가 토큰 게이트(예: 1 req / 1s per TR)** 를 add-only로 얹는다. 버킷 capacity와 직교(orthogonal).

### 0-4. 백필 정리 결론 (§3 본문)

전수조사 결과 **장중(09:00~15:30) KIS REST를 BG/SHARED로 소비하는 컴포넌트는 6개**. 이 중 **3개가 통폐합·이연 후보**:

- `LiveDailyCandleScheduler`(api, 5분) + `MarketDailyBackfillCampaign`(consumer, admin/야간) 의 **장중 일봉 갱신 역할 중복** — LiveDaily는 watchlist 한정, Campaign은 전종목 야간. 장중 갱신은 LiveDaily 단일로 충분, **장중 backfill campaign 실행 금지 가드** 명시.
- `MarketIndexScheduler`(batch, 장중 1분 cron, 26 req burst) — ADR 0116 §2-D inter-call jitter 미적용 상태면 **장중 BG 버킷 최대 압박원**. jitter 또는 **2분 cron 하향** 검토.
- `BalanceReconcileJob`(consumer, 장중 5분 cron, BACKFILL) — 원장 계열 per-TR 한도 직격원. per-TR 게이트 적용 1순위.

**ADR 0119(1m backfill 폐지) 정합 확인**: proactive 1분봉 backfill 잔존 0건 확인 — `KisRestCandleAdapter` 1min 경로는 모두 **on-demand(USER_PATH 763행·BACKFILL admin 959/1099행)** 로만 잔존, proactive scheduler 0. **위반 없음 (GREEN)**.

---

## 1. 인벤토리 전수조사 매트릭스 (최우선 — KIS REST 소비처 전수)

> 조사 방법: `grep KisCallPriority|kisRateLimiterPort.acquire|@Scheduled` 전수 + 각 컴포넌트 본문 read. KIS REST를 실제 소비하는(rate limiter `acquire` 호출 또는 KIS adapter 경유) 컴포넌트만 등재. Naver/DART(비-KIS)는 병기만.

### 1-A. 장중(09:00~15:30 KST) KIS REST 소비 — EGW00201 직결 (★ 정리 핵심)

| # | 컴포넌트 | 모듈 | 트리거·주기 | 1회 호출량(burst) | priority→버킷 | KIS TR | 존재 사유 | ADR 근거 | 정리 판정 |
|---|---------|------|------------|------------------|--------------|--------|----------|---------|----------|
| L1 | `LiveDailyCandleScheduler.tick` | api | `fixedRate 5분` (08:00~20:00) | watchlist N종목 × 1d incremental ~1페이지, 종목 간 **200ms sleep** | BACKFILL→BG | FHKST03010100 (일봉) | 장중 watchlist 일봉 stale 방지 (분봉은 WS) | 0118·0037 | **유지** (incremental fix로 burst 제거됨) |
| L2 | `MarketIndexScheduler.intradayTick` | batch | `cron 0 * 9-15 MON-FRI`(1분) | 지수·섹터 **26 req burst** | INGESTION_LOW→BG | FHPUP02100000 외 5종 | 장중 시장 추세 갱신 | 0100·0116 | **★ 정리**: jitter 미적용 시 BG 최대 압박. jitter 강제 or 2분 하향 |
| L3 | `BalanceReconcileJob.openMarketTick` | consumer | `cron 0 */5 9-15 MON-FRI`(5분) | 잔고조회 1~N (보유종목수) | BACKFILL→BG | inquire-balance(원장) | 포지션 정합 reconcile | 0070 | **★ 정리**: 원장 per-TR 한도 직격. per-TR 게이트 1순위 |
| L4 | `WatchlistPollingWorker.poll` | consumer | `fixedRate 5초` (장중) | pool 종목 × 1d 1캔들 fetch | (현 코드 — §1-E 확인 필요) | FHKST03010100 | watchlist 최신가 polling | — | **★ 확인**: WS tick 우선 정책(CLAUDE.md)과 중복 의심 — REST polling 잔존 사유 audit |
| L5 | `WatchlistIngestionWorker` | consumer | `cron 0 * * * *`(1분, session 변경 감지) | session 전환 시 approval 등 | INGESTION→SHARED | approval | WS 구독 수요 갱신 | — | **유지** (이벤트성) |
| L6 | account REST (`AccountQueryService`) | api | **frontend-driven** (30s auto + 사용자 trigger) | 잔고 1회 (single-flight + debounce) | USER_PATH→USER | inquire-balance(원장) | 자본 시각화 | 0081·0116 | **유지** (debounce·분산락 적용됨). 단 원장 per-TR 한도 직격원 |

### 1-B. 장중 외 / 야간·시동 KIS REST 소비 — EGW00201 간접

| # | 컴포넌트 | 모듈 | 트리거·주기 | 1회 호출량 | priority→버킷 | 존재 사유 | ADR | 판정 |
|---|---------|------|------------|-----------|--------------|----------|------|------|
| N1 | `MarketDailyBackfillCampaign` | consumer | admin `/run` + 야간 재개(멱등) | **전종목 ~2,517** × 1d, `concurrency=10` Semaphore, `nightlyCallCap=6000` | BACKFILL→BG | 전종목 10년 일봉 재구축 | 0118 | **★ 정리**: 장중 실행 금지 가드 명시 (L1과 역할 분리 LOCK) |
| N2 | `MarketDailyNightlySnapshotScheduler` | consumer | `cron 23:00`(영업일) | 전종목 멀티시세 today 1봉 | BACKFILL→BG | today 종가 권위 갱신 | 0118 | **유지** (야간 단발) |
| N3 | `MarketIndexScheduler.closeTick`+`prevDayTick` | batch | `cron 16:30`+`06:00` | 지수 마감·전일 | INGESTION_LOW→BG | 지수 마감 스냅 | 0100 | **유지** (장외) |
| N4 | `InstrumentMasterSyncScheduler` | batch | `cron 13개`(06:05~19:00 MON-FRI) | 종목마스터·KSD·holiday | BACKFILL→BG | 종목 universe·휴장일 sync | — | **유지** (장외 분산됨) |
| N5 | `DiscoveryScorecardScheduler` | api | `cron 08:30`+`23:30` | 발굴 후보 스코어 | (내부 DB 위주) | 단타 발굴 스냅 | discovery-spec | **유지** (장외) |
| N6 | `PositionBootstrapRunner` | consumer | 시동 1회 | 잔고 1회 | USER_PATH→USER | 포지션 부트스트랩 | — | **유지** (1회) |
| N7 | `CandleBackfillWorker` | consumer | on-demand(admin/gap fill) | 종목 × 페이지(150ms sleep) | BACKFILL→BG | gap 보강 | 0118·0105 | **유지** (on-demand) |

### 1-C. 비-KIS 폴링 (병기 — 본 spec 비범위, 버킷 무관)

| 컴포넌트 | 모듈 | 주기 | 외부 | rate limiter | 비고 |
|---------|------|------|------|-------------|------|
| `WatchlistNewsScheduler` | api | 1분 | **Naver** (17건/분 hard) | `NaverNewsRateLimiter` 별도 | KIS 버킷 무관 — 별도 슬롯 |
| `DartDisclosurePollingScheduler` | api | 1분 cron | **DART** | DART 자체 한도 | KIS 버킷 무관 |

### 1-D. ADR 0119(1m backfill 폐지) 정합 audit — 의심 lens 의무

ADR 0119는 1분봉 proactive backfill을 전면 폐지(on-demand 전환)했다. **잔존 위반 검사**:

- `KisRestCandleAdapter` 1min 경로 3곳: `:763`(USER_PATH — 사용자 SymbolDetail on-demand), `:959`/`:1099`(BACKFILL — admin recollect on-demand). **proactive scheduler에서 1min fetch 호출 0건 확인**.
- `TickToMinuteAggregator`(consumer)는 **WS tick → 1분봉 집계**로 KIS REST 미소비 (rate limiter acquire 0). ADR 0119 정합 GREEN.
- **결론**: 1m proactive backfill 잔존 0 — ADR 0119 위반 없음. **L4 `WatchlistPollingWorker`만 추가 audit 필요**(아래 §1-E).

### 1-E. WatchlistPollingWorker (L4) 방향성 가드 의심 — UNCERTAIN

CLAUDE.md 방향성 가드: *"종목 상세·watchlist는 KIS WS tick이 장중 실시간 권위. REST는 bootstrap/backfill/fallback."*

- L4는 `fixedRate 5초`로 pool 종목마다 `kisRestPort.fetchCandles(code, ONE_DAY, 1)` REST 호출 (WatchlistPollingWorker.kt:52). **장중 5초 REST polling이 WS tick 권위 정책과 중복**일 수 있다.
- **판정 UNCERTAIN** — market-data에게 "L4 REST polling이 WS tick 도입 후에도 필요한 fallback인가, 아니면 ADR 0119 정신(proactive 폐지)에 따라 제거 대상인가" 1회 확인 의무 (본 spec 결재 후 R0). 만약 제거 가능하면 장중 BG/USER 압박 1개 추가 제거.

---

## 2. 백필 통폐합·정리안

### 2-1. 중복/과잉 후보

| 후보 | 현 상태 | 정리안 | 효과 |
|------|--------|--------|------|
| **장중 일봉 갱신 이원화** (L1 LiveDaily watchlist 5분 + N1 Campaign 전종목) | L1·N1 역할 문서상 분리됐으나 **장중 Campaign 실행 가드 부재** | Campaign에 **장중(09:00~15:30) 실행 금지 hard 가드** 추가 (ENV `MAGICJAR_MARKET_DAILY_BACKFILL_INTRADAY_BLOCK=true` default). 장중 일봉 갱신은 L1 단일 권위 LOCK | 장중 전종목 backfill burst 원천 차단 |
| **L2 MarketIndex 장중 1분 26 req** | jitter 적용 여부 코드 audit 필요 (ADR 0116 §2-D R2 의제) | jitter(종목 간 50~100ms) 강제 OR **장중 2분 cron 하향**(13 req/2분). 시장 추세는 1분 지연 허용(INGESTION_LOW 본질) | 장중 BG 최대 압박 ~50% 완화 |
| **L3 reconcile 장중 5분 원장조회** | 원장 per-TR 한도 직격 | per-TR 게이트(§4-5) 적용 + 보유종목 0 시 skip 가드 | 원장 EGW00201 직격 차단 |
| **L4 watchlist REST polling 5초** | WS tick과 중복 의심(§1-E) | market-data 확인 후 제거 or fallback 한정(WS 단절 시만) | 장중 polling 1개 제거 가능 |

### 2-2. 통합 스케줄 윈도 (야간 일괄 원칙)

- **장중(09:00~15:30)**: 사용자·자본 path(USER_PATH·INGESTION)만 우선. BG는 **L1(일봉 incremental, watchlist 한정) + L2(지수, jitter/2분)** 로 최소화. N1 Campaign·N7 on-demand backfill은 **장중 진입 금지**.
- **장외·야간(15:30~09:00)**: N1~N5 전종목 backfill·snapshot·master sync 일괄. 이미 InstrumentMasterSync는 06:05~19:00 13 cron으로 분산됨(GREEN).
- **장중 BG budget 상한 제안**: 장중 BG 버킷(현 3/s)은 **L1+L2 합산 peak < 3/s** 가 되도록 caller pacing. 본 spec §4-6 권고안의 로컬 스케줄러가 이 상한을 caller-side로 강제.

### 2-3. 장중 budget 상한 (정량)

KIS 10/s 중 장중 배분 권고(현 ADR 0116 4/3/3 유지하되 caller pacing 추가):

| 윈도 | USER(주문·차트·잔고) | SHARED(approval·watchlist ingestion) | BG(일봉·지수·reconcile) |
|------|---------------------|--------------------------------------|------------------------|
| 장중 | 4/s (보존 — 절대 안 막힘) | 3/s | **3/s, 단 caller peak pacing < 3/s 유지** |
| 야간 | (주문 0 — 여유) | (낮음) | **최대 활용** (전종목 backfill — nightlyCallCap 내) |

---

## 3. 큐 기반 재설계 대안 비교

> 현행 baseline = ADR 0116 3-tier 버킷(USER 4 / SHARED 3 / BG 3, Redis 공유, `refillIntervally`). 아래는 그 위에서의 진화 대안.

### 3-1. 대안 매트릭스

| 평가 축 | A. 버킷 유지 + per-TR sub-limit | B. 중앙 우선순위 큐 (single dispatcher) | C. 하이브리드 (로컬 큐 + Redis 버킷) ★ |
|---------|--------------------------------|----------------------------------------|--------------------------------------|
| **EGW00201 윈도 straddle 해소** | △ 부분 (버킷 충전 경계 동일 → straddle 잔존, per-TR만 추가 보호) | ◎ 완전 (dispatcher가 sub-second 균등 발사 — 경계 straddle 0) | ○ 양호 (caller-side pacing 게이트로 sub-second 분산. 단 cross-instance는 Redis 버킷 ceiling에 의존) |
| **원장 per-TR 한도 표현** | ◎ (본 대안의 핵심 — TR별 게이트) | ○ (큐 dispatch 시 TR 한도 검사 가능) | ◎ (per-TR 게이트를 로컬 큐 진입에 add-only) |
| **주문 latency 영향** | ◎ 영향 0 (현 구조 유지) | △ 큐 head 삽입 보장하나 dispatcher 직렬화 = blocking 모델 전환 risk | ○ 로컬 큐 head 삽입 + USER_PATH 즉시 발사 path 보존 |
| **모듈 3개 분산 비용** | ◎ 0 (현 어댑터 catch만 확장) | ✗ Redis 분산 큐 필요 (api·batch·consumer 합의 + dispatcher leader election or per-module dispatcher) | ○ 모듈별 로컬 큐(분산 큐 회피) + Redis 버킷이 cross-instance 정합 |
| **SPOF/운영 복잡도** | ◎ 낮음 | ✗ dispatcher SPOF + 큐 backlog 관측 부담 | ○ 중간 (모듈별 로컬 — SPOF 0, 큐 관측만 추가) |
| **마이그레이션/롤백** | ◎ add-only (ENV toggle) | ✗ 큰 재작성 + 롤백 시 전 모듈 revert | ○ 어댑터 진입 게이트 add-only (ENV toggle로 bypass → 현 버킷 직접 acquire 회귀) |
| **Phase 3 Circuit Breaker 합류** | ○ onTpsExceeded → CB (현 주석 예고 지점) | ◎ dispatcher가 CB OPEN 시 큐 drain 중단 자연 표현 | ◎ 로컬 큐 dispatcher가 CB OPEN 시 발사 중단 (CB를 큐 dispatch loop에 합류) |
| **MVP 적합성** | ◎ (보수적 보강) | ✗ 과대설계 | ○ (현 구조 위 점진 보강) |

### 3-2. 각 대안 상세

**대안 A — 버킷 유지 + per-TR(원장) sub-limit (보수적 보강)**
- 현 ADR 0116 3-tier 버킷 그대로 + 원장 계열 TR(inquire-balance·inquire-ccld 등)에 **TR별 Redis 토큰 게이트** add-only. `acquire(priority)` 통과 후 원장 TR이면 `acquireTrSubLimit(trId, 1/s)` 추가 게이트.
- **한계**: straddle 본질(경계 일괄 충전)은 그대로. per-TR만 닫음. root cause 1(straddle)·3(백필 발사 타이밍)은 미해소.

**대안 B — 중앙 우선순위 큐 (single dispatcher)**
- 모든 KIS REST 호출을 `enqueue(priority, trId, call)` → 토큰 스케줄러가 **100ms당 1건(또는 sub-second 균등)** dispatch. 주문은 큐 head 삽입.
- Bucket4j `asScheduler().tryConsume(1, maxWait, scheduler)` 또는 자체 `ScheduledExecutorService` 균등 발사. 코드 주석(`RedisKisRateLimiterAdapter.kt:294` TODO)이 이미 asScheduler 예고.
- **한계**: 현 호출은 동기 blocking(`acquire()` 후 즉시 WebClient 호출). 큐 모델은 비동기 dispatch → caller가 CompletableFuture/콜백 대기 = **대규모 호출 모델 전환**. 3모듈 분산 시 Redis 분산 큐 + dispatcher 합의 필요. 주문 path가 dispatcher 직렬화에 묶이면 S-1(시그널→주문 P95<5s) risk. **MVP 과대설계 — REJECT.**

**대안 C — 하이브리드 (로컬 우선순위 큐 + Redis 버킷 유지) ★**
- 각 모듈 어댑터 공통 진입점에 **로컬 우선순위 큐 + 토큰 스케줄러(pacing)** 를 둔다. 큐 dispatcher가 **모듈 내 KIS 호출을 sub-second 균등 간격으로 release** → caller가 동시 burst를 던져도 dispatcher가 100~150ms gap으로 평탄화(현 H1 150ms·LiveDaily 200ms를 **버킷 진입 게이트로 일반화**).
- 분산 정합(cross-instance ceiling)은 **현 Redis 3-tier 버킷이 계속 담당** → 분산 큐 회피.
- per-TR(원장) sub-limit(A)을 **로컬 큐 진입 게이트에 add-only** 흡수.
- USER_PATH(주문·차트)는 **큐 head 삽입 + 즉시 발사 fast-path**(pacing 면제 옵션)로 latency 보존.
- **straddle 해소 원리**: 경계 일괄 충전 버킷이 0.999s/1.001s에 2N토큰을 내줘도, **로컬 dispatcher가 release를 100ms gap으로 평탄화**하므로 인접 발사 몰림이 사라진다. 버킷은 cross-instance ceiling, 로컬 dispatcher는 intra-instance pacing — 책임 분리.

### 3-3. Phase 3 Circuit Breaker 합류 지점

- 코드 예고: `RedisKisRateLimiterAdapter.kt:50·342` "Phase 3 Resilience4j Circuit Breaker `onError()` 위임 → 5분 윈도 50% 임계 OPEN".
- **대안 C 합류**: 로컬 큐 dispatcher loop에 CB 게이트 삽입 — CB OPEN 시 dispatcher가 BG/INGESTION release 중단(주문 USER_PATH는 half-open probe 허용). 큐 backlog는 timeout으로 drain. 대안 A는 CB를 acquire 진입에만 걸어 큐 평탄화 효과 없음.

---

## 4. 권고안 — "대안 C + per-TR(A)" (사용자 결재용)

### 4-1. 권고 구조

```
caller adapter (api/batch/consumer)
   │  enqueue(priority, trId, timeoutMillis)
   ▼
[모듈 로컬 우선순위 큐]  ← USER_PATH head 삽입 / BG tail
   │  dispatcher loop (pacing: gap ≥ paceMillis, USER_PATH fast-path 면제)
   ▼
[per-TR sub-limit 게이트]  ← 원장 계열 TR만 (inquire-balance 등 1/s)
   ▼
[Redis 3-tier 버킷 acquire]  ← cross-instance ceiling (현 ADR 0116 그대로)
   ▼
   WebClient → KIS
```

### 4-2. 핵심 설계 결정

1. **로컬 dispatcher pacing** — 모듈별 단일 `ScheduledExecutorService` 또는 가상스레드 dispatcher가 큐에서 release. `paceMillis`(default 100~120ms, ENV) gap 강제 → sub-second 균등. **straddle 해소 본질**.
2. **USER_PATH fast-path** — 주문·차트·잔고(사용자 trigger)는 pacing 면제 + 큐 head. S-1 latency 보존.
3. **per-TR 게이트** — 원장 TR 화이트리스트(`inquire-balance`, `inquire-ccld`, `inquire-daily-ccld`)에 Redis 토큰 1/s. capacity와 직교.
4. **Redis 버킷 보존** — ADR 0116 3-tier 그대로 cross-instance ceiling. 분산 큐 0.
5. **ENV bypass** — `MAGICJAR_KIS_LOCAL_QUEUE_ENABLED=false` → 큐 우회, 현 직접 `acquire()` 회귀 (rollback path).

### 4-3. ADR 0116과의 관계

- 본 권고는 ADR 0116 **supersede 아님 — amend/연장**. 버킷 모델은 보존하고 그 **앞단에 로컬 큐 + per-TR**를 add-only. 새 ADR(예: 0122 또는 0116 amend §2-F)로 본문화 후보.
- **결재 후 architect가 ADR 발행 + master §10 + external-api-policy §2 + README 인덱스 동시 갱신** (ADR 0030 SoT).

### 4-4. 라운드 분할(결재 후 — 참고)

- **R0**: market-data L4 watchlist polling audit(§1-E) + L2 jitter/2분 결정 + N1 장중 가드 + per-TR 화이트리스트 확정.
- **R1**: 로컬 큐 + dispatcher pacing (모듈 공통 — backend-core, 3 어댑터 진입 게이트).
- **R2**: per-TR sub-limit 게이트 + 백필 정리 가드(N1 장중 block, L2 분산) + Phase 3 CB 합류 sketch.
- **R3**: 부하 검증(장중 3 cycle EGW00201 0) + 테스트 일괄.

### 4-5. per-TR(원장) sub-limit 상세

- KIS 원장 계열 추정 한도: 글로벌 10/s와 별개로 **원장 TR당 더 엄격**(메시지 "원장에서 허용 가능한 초당 거래건수 초과"). **공식 수치 미명시 → 보수적 1/s per-TR로 시작**, R3 부하 evidence로 조정.
- 적용 TR: `inquire-balance`(잔고) · `inquire-ccld`(체결) · `inquire-daily-ccld`(일별체결) · `inquire-psbl-order`(주문가능). 화이트리스트는 R0에서 KIS-first raw fetch로 확정(헌법 §3).
- 구현: Redis 토큰 게이트 `kis-rate-limit:tr:{trId}` capacity 1 refillIntervally(1, 1s). 버킷 acquire **전** 통과 의무.

### 4-6. 권고 확정

> **대안 C(하이브리드 로컬 큐) + 대안 A의 per-TR 게이트** 를 권고한다. 대안 B(중앙 분산 큐)는 straddle을 가장 깨끗이 풀지만 **호출 모델 전면 비동기화 + Redis 분산 큐 + dispatcher SPOF + 주문 latency risk** 로 MVP 과대설계이므로 post-MVP backlog. C는 현 ADR 0116 버킷을 보존하면서 **straddle(로컬 pacing)·per-TR(게이트)·백필 발사 타이밍(큐 평탄화)** 3 root cause를 모두 add-only로 닫고 ENV toggle 롤백이 안전하다.

---

## 5. EGW00201 회복/실패 구분 시그널 설계

### 5-1. 현행 한계

- `onTpsExceeded()`(RedisKisRateLimiterAdapter.kt:344)는 **단일 시그널** — count++ + SystemEvent(5분 aggregator). **backoff 재시도로 회복된 건과 실패 확정 건을 구분 못 한다.**
- caller ~30곳(`grep onTpsExceeded|classifyKisError` 30 파일)이 `classifyKisError` → `RateLimit` 분기 시 일률 `onTpsExceeded()` 호출. 회복 여부 정보 손실.

### 5-2. 재설계 — 회복/실패 2-시그널

| 시그널 | 의미 | metric | alert |
|--------|------|--------|-------|
| `onTpsExceededRecovered(trId)` | EGW00201 발생했으나 backoff 재시도(1s)로 회복 | `kis_ratelimit_tps_exceeded_total{outcome="recovered", tr_id}` | **INFO** (알림센터만 — §6 ADR 0095 amend 정합) |
| `onTpsExceededFailed(trId)` | 재시도 소진 후 실패 확정(호출 포기) | `kis_ratelimit_tps_exceeded_total{outcome="failed", tr_id}` | **WARN** (현행 SystemEvent 경로) |

- caller 어댑터의 retry loop(현 1s backoff 1회)이 **재시도 성공 시 `recovered`, 최종 실패 시 `failed`** 호출. retry wrapper가 없는 어댑터는 R2에서 공통 retry helper로 일반화(헌법 §6 — Repository body fun 금지, caller 클래스 private helper).
- **caller ~30곳 영향 범위**: 시그널 메서드만 2개로 분기 → 어댑터별 catch 블록 1~2줄 add. 일괄 변경은 R2(per-TR 게이트와 동일 사이클 — 어댑터 touch 묶음).

### 5-3. ADR 0095 amend 정합 (병렬 처리 중)

- §6 ADR 0095 amend가 **windowCount 차등**(1~4=INFO, ≥5=WARN)을 도입한다. 본 §5-2의 `recovered`(INFO)/`failed`(WARN) 분리는 그 차등과 **상호 보완** — recovered는 자동 회복이므로 INFO, failed는 윈도 누적 ≥5 시 WARN escalate.

---

## 6. 자원 audit (헌법 §3 + spec 발행 의무 — 대안 B/C 점유 충돌 사전 점검)

| 자원 | 현 main 점유 | 권고안(C+A) 신설 | 대안 B(참고) | 정합 |
|------|-------------|-----------------|-------------|------|
| **Flyway** | V71 (ADR 0116 audit) | **0** (큐·게이트 모두 Redis/in-memory) | 0 | GREEN |
| **Kafka** | 기존 토픽 | **0** | (분산 큐를 Kafka로 구현 시 신규 토픽 — B의 비용) | GREEN (C는 0) |
| **Redis** | `kis-rate-limit:global`+`:user`+`:shared`+`:bg`, `account:balance:*` | **add-only**: `kis-rate-limit:tr:{trId}` (per-TR 게이트, TTL 자동) | (분산 큐 list/stream key 다수 — B의 비용) | GREEN (C add-only) |
| **STOMP** | 기존 | **0** | 0 | GREEN |
| **REST** | 기존 + `/actuator/prometheus` | **0** (큐 backlog metric은 기존 Micrometer reuse) | 0 | GREEN |
| **ENV** | `MAGICJAR_KIS_PRIORITY_BUCKETS_ENABLED`·`_BUCKET_USER/SHARED/BG` | **add-only**: `MAGICJAR_KIS_LOCAL_QUEUE_ENABLED`(default true)·`_PACE_MILLIS`(120)·`_TR_SUBLIMIT_ENABLED`(true)·`MAGICJAR_MARKET_DAILY_BACKFILL_INTRADAY_BLOCK`(true) | (dispatcher leader election ENV 등) | GREEN (C add-only) |

**충돌 결론**: 권고안 C+A는 **5 자원 모두 add-only 또는 0 신설** — main 점유 충돌 없음. 대안 B는 Kafka/Redis 분산 큐 신설 → main 자원 압박 + 신규 인프라 — REJECT 근거 보강.

---

## 7. SLO 영향 · rollback · 관측 (운영 ADR 양식 §9-5 — 결재 후 ADR에 본문화)

### 7-1. SLO 영향
- **S-4(KIS rate limit)** — EGW00201 발생률 현 산발(12:15~12:18 7→11) → **0 수렴 목표** (straddle 해소 + per-TR + 백필 정리).
- **S-1(시그널→주문 P95<5s)** — **유지/개선**: USER_PATH fast-path(pacing 면제 + 큐 head) → 주문 latency 보존. BG 평탄화로 주문 acquire 경합 감소.
- **S-3(account API p95<500ms)** — **유지**: account는 USER_PATH fast-path + single-flight/debounce(현행) 보존. per-TR 게이트는 1/s이나 single-flight로 호출 자체가 드묾.

### 7-2. rollback plan
1. `MAGICJAR_KIS_LOCAL_QUEUE_ENABLED=false` → 로컬 큐 우회, 현 직접 `acquire()` 회귀 (재배포 불요).
2. `MAGICJAR_KIS_TR_SUBLIMIT_ENABLED=false` → per-TR 게이트 우회.
3. `MAGICJAR_MARKET_DAILY_BACKFILL_INTRADAY_BLOCK=false` → 장중 가드 해제.
4. 코드 revert 시 ADR 0116 버킷 모델 그대로 유지(큐는 add-only layer) — 버킷 변경 0.

### 7-3. 관측 매트릭스
- Prometheus: `kis_ratelimit_queue_depth{module,priority}`(신규 큐 backlog) · `kis_ratelimit_tps_exceeded_total{outcome,tr_id}`(회복/실패 분리) · `kis_ratelimit_tr_sublimit_throttle_total{tr_id}`.
- Grafana: 장중 BG release rate(평탄화 확인) + per-TR throttle + EGW00201 outcome 스택.
- Visual 회귀: backend 인프라 — frontend 영향 0 (qa Visual 불요).

---

## 8. 미해결·확인 의제 (결재 시 함께)

1. **L4 WatchlistPollingWorker 존치 여부** (§1-E UNCERTAIN) — market-data 확인 1회. WS tick 권위 후 REST polling 5초가 fallback인가 잔재인가.
2. **L2 MarketIndexScheduler** — jitter 적용 코드 audit(ADR 0116 §2-D R2 완료 여부) + 2분 cron 하향 가부.
3. **per-TR 화이트리스트·수치** — KIS-first raw fetch로 원장 TR별 한도 확정(헌법 §3, R0).
4. **paceMillis 기본값** — 100/120/150ms 중 R3 부하 evidence로 LOCK.
5. **대안 B 재검토 시점** — 다중 인스턴스 수평 확장 또는 주문량 급증 시 post-MVP backlog로 승격 검토.

---

## 9. 변경 이력

| 일자 | 변경 | 작성 |
|------|------|------|
| 2026-06-12 | **결재됨 (최고 관리자 — 4의제 전체 승인, R0 즉시 착수)**. 결재 evidence: 2026-06-12 REAL canary D2 단일 전략 운영에서도 원장 per-TR(EGW00215 계열)가 BUY 평가 잔고조회 직격 → 14:12 총자산 0원 fabricate incident → backend-core F10(`8644f10a` canary D2 #5 preCheckFreeRules) 임시 수선(원장 TR 호출 순서 재배치, per-TR 한도 구조 표현 X — 근본은 본 spec §4-5). R0 audit 수행 → 산출물 `_workspace/specs/2026-06-12-kis-queue-r0-audit.md`(L4 polling 결론·L2 jitter 결론·per-TR 화이트리스트 확정·R1~R3 dispatch 매트릭스). 코드 0 touch. (market-data, opus 4.8) | market-data |
| 2026-06-10 | spec 신설 — KIS rate limit 큐 기반 재설계(대안 A/B/C 비교 + 권고 "C+per-TR") + 백필/폴링 전수조사 인벤토리 13 컴포넌트(장중 6 + 야간 7 + 비-KIS 2) + 백필 통폐합 정리안(장중 일봉 이원화·MarketIndex jitter·reconcile per-TR·watchlist polling audit) + ADR 0119(1m backfill 폐지) 정합 audit GREEN + EGW00201 회복/실패 2-시그널 설계 + 자원 audit 5종(C add-only GREEN) + 운영 ADR 3슬롯. Proposed — 사용자 결재 대기. 코드 0 touch. KIS 호출 0 · 자본 영향 0 · AI 호출 0 · mock 0. (architect, opus 4.8 1M) | architect |
