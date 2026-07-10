# market-data Data Flow

수집 → 가공 → 저장 → 공급 흐름(텍스트). 영역별 증분 추가.

---

## 2026-07-10 minute-pool — 분봉 수집 풀 (후보 풀 과거 1분봉 백필 + 장중 전방 수집 · consumer 모듈)

목적: 인트라데이 백테스트 가능 종목 확장(≈15 → 풀 30~50). KIS 분봉 API는 과거 소급 깊이가 얕아(≤1년)
전종목 소급이 불가하므로 **후보 풀만** 소급 백필 + 장중 전방 수집으로 최신분을 잇는다.

```
[후보 풀 산출]  MinutePoolProvider.resolvePool()   (KIS 콜 0 · DB·전략 조회만)
   union(순서 보존·dedupe·poolSize clamp):
     1. yml seed 종목 (대형 유동주 15 default · ops 지정)                    ← 항상 포함
     2. ACTIVE/autopilot 전략 명시 종목 (ActiveStrategyCodeProvider)          ← 인트라데이 최우선 수요
     3. 거래대금 상위 N (candle 최신 1d close_raw×volume DESC)                ← ConsumerMinutePoolCandidateJpaRepo
     4. 발굴 스코어카드 후보 (discovery_scorecard 최신 snapshot MIN(rank))    ← 신규 테이블 0(existing native @Query)

[① 과거 백필]  POST /admin/minute-pool-backfill/run  (+ magicjar.minute-pool-backfill.enabled=true)
        ▼ MinutePoolBackfillCampaign.runAsync()   (비동기 · runId 즉시 반환 → GET /status 관측)
        │  window = [최근 N영업일(default 30) 09:00 KST .. now]
        ▼ for code in pool(≤ maxCodesPerRun):
        ├─ [멱등 skip] countByCodeAndInterval(code,1m) ≥ expectedBars×coverageRatio → skip (KIS 콜 0)
        ├─ MinutePoolCollectionService.collect(code, from, to)
        │     ▼ KisMinuteBackfillRangePort.fetchOneMinuteCandlesForBackfill   (FHKST03010230 · BACKFILL(BG) 버킷)
        │         호출당 ≤120봉 · FID_INPUT_DATE_1+FID_INPUT_HOUR_1 역방향 페이지네이션 (어댑터 기존 배선 재사용)
        │         · rate-limit/credential 결함 → IllegalStateException (fail-closed, per-code 흡수)
        │     ▼ CandleRepository.saveAll(source="KIS-POOL-1M-BF")   ON CONFLICT DO NOTHING (멱등 — 재실행 중복 0)
        │     ▼ inserted>0 → AggregateBackfillRunner.backfill(code)  기존 롤업 재사용 → 5m·15m·30m·1h… upsert
        └─ per-code 실패는 failed++ 후 다음 종목 (전략 평가 경로 무영향)

[② 전방 수집]  MinutePoolForwardPoller  @Scheduled(fixedDelay 5분 default)   (원래 스코프 2순위)
        │  세션 게이트: 정규장 09:00~15:35 KST 평일만 (밖이면 콜 0)
        ▼ for code in pool:  from = maxTs(1m)+1분 ?: 09:00, to = now-1분
        │     from>to → skip · 아니면 collect(code, from, to)  (동일 BG 버킷)
        └─ ★ WS 슬롯 절대 미사용 (ADR 0124 — WS 40 slot 매매 owner 전용). REST 전용.

[저장소]  candle 테이블 (1m 원천 + 롤업 5m/15m/… — 기존 UQ code+interval+ts). 신규 테이블 0.
[공급]    strategy-engine 인트라데이 백테스트·전략 평가 · 발굴 스코어카드 forward-test 후보 데이터 토대.
[rate 예산]  백필: 30종×30일×~4page ≈ 3,600 KIS 콜 → BG 3/초 pacing ≈ 20분 (장 마감 후 실행 · 자본 path 침범 0).
             전방: cycle당 ~30콜 / 5분 ≈ 6콜/분 = BG(180/분)의 ~3%.
```

### rate-limit 격리 (ADR 0116 §2-B · ADR 0132 §D2-3 정합)
모든 minute-pool KIS 콜은 BACKFILL(BG) 버킷 고정 — USER_PATH(주문·현재가·차트)·INGESTION(WS approval·watchlist)
절대 침범 0. 역방향 fallback(BG는 상위 버킷 fallback 불가)으로 자본·사용자 path 영향 0.

---

## ADR 0118 R-BF — MARKET_DAILY 10년 일봉 backfill 캠페인 (consumer 모듈)

```
[트리거]
  POST /admin/market-daily-backfill/run   (또는 R2 야간 스케줄러 배선 — 본 PR 범위 외)
  + ENV magicjar.market-daily-backfill.enabled=true
        │  (enabled=false → DISABLED 즉시 종료, 가동 0·적재분 보존)
        ▼
[MarketDailyBackfillCampaign.runCampaign]  ── 멱등 1회 실행 ──────────────────────
        │
        ├─ MarketDailyUniverseProvider.resolveCodes()        (R1 재사용)
        │     instrument 보통주(securities_group='ST', 우선주·SPAC·ETF·ETN·REIT 제외) → ~2,517 코드 ASC
        │
        ▼  for code in codes:
        │
        ├─ [progress 멱등] isSymbolComplete(code)              (candle-row 기반 — 진행 테이블 0)
        │     countByCodeAndInterval(code,1d) ≥ completionMinRows  OR  최신봉 recencyGraceDays 이내
        │       → 완료 → skip (KIS 콜 0)
        │
        ├─ [콜 cap] callsConsumed ≥ nightlyCallCap → CAP_REACHED break (미완료분 다음 실행 이연)
        │
        ├─ [listed_at clamp] from = max(today-historyYears, instrument.listed_at)   (R1 findListedAtByCode)
        │     상장 이전 구간 원천 제외 (false fetch 0). from > to(어제) → 완료 간주(거래일 0)
        │
        ├─ [throttle] BackfillThrottleGate.runThrottled(code) { … }   (재사용)
        │     직렬화·run 간격·circuit breaker. null(permit timeout·circuit OPEN) → Throttled → run 이월
        │     │
        │     ▼ KisDailyCandleRangePort.fetchDailyCandlesInRange(code, from, to=어제)
        │         FHKST03010100 period=D · BACKFILL(BG) 버킷 · cursor 페이지네이션 (≤100/페이지)
        │         · rate-limit/credential 결함 → KisDailyRangeUnavailableException (fail-closed)
        │         · 정상 0건(상장 이전·거래 부재) → 빈 list (uncovered, 실패 아님)
        │     │
        │     ▼ CandleBackfillWorker.sanityCheck(fetched)        (햇징 룰 §1 재사용 — 가격 range·OHLC·monotonic)
        │     │
        │     ▼ CandleRepository.saveAll(sane, source="KIS-MARKET-DAILY-BACKFILL")
        │         ON CONFLICT DO NOTHING — 과거봉 immutable (재실행 중복 호출돼도 데이터 손상 0)
        │     │
        │     ▼ BackfillThrottleGate.recordRunOutcome(rateLimitPressure)  → circuit breaker 보고
        │
        ▼  루프 종료
        │
        ├─ remaining = 미완료 종목 수 산출
        ├─ status = remaining==0 ? COMPLETED : CAP_REACHED
        └─ audit MARKET_DAILY_BACKFILL_RUN_DONE (status·콜 소비·잔여·inserted 등)

[다음 실행]  매 run 호출이 progress 이어받아 미완료만 처리 → 며칠~한 주 분산 완주.

[저장소]   candle 테이블 (1d, 단일 테이블 — 파티셔닝 불필요 §5-2). 1d 단독 적재(분봉·합산 0).
[공급]     스크리너/단타 발굴(ADR 0117) 전종목 횡단 질의의 raw 데이터 토대.
           ★ direction-guard LOCK: MARKET_DAILY는 WS 구독 universe 아님. watchlist 자동삽입 0.
```

### R2 독립성 (구현 선행 검증 결과)

R-BF write 경로는 R2(야간 멀티시세 snapshot·`upsertAll`)에 **의존하지 않는다**(코드 검증 완료):
- 과거 일봉은 settled 공식값·immutable → `saveAll`(ON CONFLICT DO NOTHING) 사용(`upsertAll` 아님).
- 필요한 모든 의존(universe·listed_at·FHKST03010100 range·candle-row progress)이 main 에 존재(R1 + 기존 자산).
- main 에 `MarketDailySnapshotScheduler`/R2 신규 코드 부재 → R-BF는 R2 없이 독립 가동 가능.
- ADR §4 "R-BF는 R2 후" 표기는 보수적 sequencing이며 hard 데이터 의존 아님 → 선행 구현.

### rate-limit 격리 (§5-1 LOCK)

모든 R-BF KIS 콜은 BACKFILL(BG) 버킷 고정 — USER_PATH·INGESTION 절대 침범 0. ADR 0116 역방향 fallback(BG는 상위 버킷 fallback 불가)으로 8~12h 점유해도 자본·사용자 path 영향 0.
