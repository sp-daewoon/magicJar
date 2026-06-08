# ADR 0118 — 전종목 일봉 universe(MARKET_DAILY) + 야간 멀티시세 snapshot + today 공식 보정

- 상태: **Proposed (2026-06-09, 사용자 결재 대기)** — "전종목 일봉 universe + 야간 갱신" 사용자 깊이 논의 결과를 설계로 확정. 신규 코드 최소·기존 자산 재사용 원칙. **미결 2건(이력 깊이·NXT 포함 범위)은 사용자 결재 필요.**
- 작성: architect
- 영역: 데이터(A) — candle ingestion / universe / 야간 batch. 자본 결정권 0.
- 관계:
  - **ADR 0057** (KIS OpenAPI 단일 데이터 출처) — 본 ADR이 신규 endpoint(`intstock-multprice`) 1건 add.
  - **ADR 0105** (캔들 Reconciliation + 장기 1m retention) — `ManagedCandleUniverseProvider`(~25 active) 위에 별도 `MARKET_DAILY` universe layer add. managed universe 흐름 변경 0.
  - **ADR 0086 / 0090** (CandleGapDetector 4축 교차검증) — gap 복구 로직 100% 재사용, universe만 확장.
  - **ADR 0116** (KIS Rate Limit Phase 2 — 3-tier 버킷) — 야간 snapshot은 BG 버킷(BACKFILL/INGESTION_LOW) 소비.
  - **ADR 0063 / 0091** (NXT 통합 cover + 종목 단위 `UN`→`J` resolver) — 야간 snapshot의 NXT 포함 여부 결정에 직결.
  - **ADR 0019** (candle UPSERT — UQ code+interval+ts) — today 보정은 기존 `upsertAll`(ON CONFLICT DO UPDATE) 재사용, 과거봉 immutable(DO NOTHING) 유지.
  - **ADR 0117** (단타 발굴 레이어) — 전종목 일봉 universe는 스크리너/발굴의 데이터 전제. 본 ADR이 그 토대 제공.
  - direction-guard: STRATEGY/DETAIL owner 분리 + watchlist 자동삽입 금지 정책 유지 (§7).

---

## 1. 배경 — 왜 지금 전종목 일봉이 필요한가

### 1-1. 기존 방향 (현재 구현)

magicJar의 캔들 데이터는 **active universe 중심**으로만 적재된다:

- `ManagedCandleUniverseProvider.resolve()` — holding / open-order / active-strategy / realtime-active / detail-recent / watchlist / strategy-candidate 7 source 합집합 (~25종목 규모).
- 이 universe만 cold-start backfill + 1m/1d gap 복구(`CandleGapDetectorAdapter` + `CandleBackfillWorker`) 대상.
- 장중 1d 갱신은 `LiveDailyCandleScheduler`가 **watchlist 한정** 5분 cron으로 처리.

**제약을 받아들인 이유**: KIS WS 동시구독 slot 한도(1+30+10 = 41 — ADR 0048) + KIS REST 10 TPS 한도. 실시간이 필요한 종목만 정밀 관리하는 것이 합리적이었다.

### 1-2. 문제 — 스크리너/단타 발굴은 전종목 일봉을 전제로 한다

ADR 0117(단타 발굴) + 향후 스크리너(T-SC)는 "오늘 거래대금 상위", "최근 N일 패턴", "체결강도 급등" 같은 **전종목 횡단 질의**를 요구한다. 그러나 현재 DB에는 active ~25종목 일봉만 존재하므로:

- 전종목 스크리닝 시 데이터가 없는 종목이 대부분 → 후보 산출 자체가 불가능하거나 watchlist에 편향.
- 매번 스크리닝 시점에 전종목 KIS REST 호출 = 3천 콜 burst = TPS 폭주 + 지연.

→ **전종목 일봉을 DB에 미리 적재해 두고, 야간에 그날 마감값으로 갱신**하는 별도 layer가 필요하다. 이것이 스크리너의 데이터 토대다.

### 1-3. 핵심 발견 — 멀티시세 묶음 호출이 비용을 100배 줄인다 (KIS-first 검증)

KIS `intstock-multprice` (TR `FHKST11300006`) 는 **한 콜에 최대 30종목** 시세를 묶어 반환한다. 장마감 후 호출 시 그날의 완전한 일봉 1개(OHLCV)를 종목당 1/30콜로 얻는다.

> **KIS-first raw 검증 (헌법 §3 — 2026-06-09 architect raw fetch)**
> - 출처: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/intstock_multprice/intstock_multprice.py` (+ `chk_*.py`)
> - URL path: `/uapi/domestic-stock/v1/quotations/intstock-multprice`
> - tr_id: `FHKST11300006`
> - 요청: `FID_COND_MRKT_DIV_CODE_N` + `FID_INPUT_ISCD_N` 쌍 (N = 1..30). **최대 30종목/콜.** `_1`은 필수, `_2`~`_30` 옵션.
> - 응답 output 필드 (verbatim): `inter2_oprc`(시가) · `inter2_hgpr`(고가) · `inter2_lwpr`(저가) · `inter2_prpr`(현재가/마감 시점 종가) · `acml_vol`(누적거래량) · `acml_tr_pbmn`(누적거래대금).

**비용 산정**: 약 3,000 종목(코스피+코스닥 보통주) ÷ 30 = **100콜**. ADR 0116 BG 버킷 3/s 기준 ~33초, jitter 포함 ~1~2분이면 전종목 그날 일봉 1개 확보.

→ 과거 backfill은 묶음 불가(`FHKST03010100`은 1종목 전용)이지만 **1회성**이고, 일상 운영은 **야간 100콜**로 끝난다는 것이 본 설계의 경제성 근거.

---

## 2. 결정

### 2-A. MARKET_DAILY universe — active universe와 분리된 전종목 일봉 적재 대상

`ManagedCandleUniverseProvider`(~25 active, WS 실시간 owner)와 **물리적으로 분리된** 별도 universe를 정의한다.

| 항목 | active managed universe (ADR 0105) | **MARKET_DAILY universe (본 ADR)** |
|------|-----------------------------------|-----------------------------------|
| 규모 | ~25종목 | ~3,000종목 (전 코스피·코스닥 보통주) |
| source | holding/order/strategy/watchlist/realtime 합집합 | **`instrument` 테이블** (active=true, 보통주, ETF·ETN·우선주·SPAC 정책 결정) |
| KIS WS 구독 | O (slot 41 한도 내) | **X (구독 0)** — 일봉 적재 전용 |
| 적재 interval | 1m + 1d + 합산 | **1d 단독** (분봉·합산 0) |
| 갱신 경로 | 실시간 tick + gap 복구 | **야간 멀티시세 snapshot 1회/일** |
| owner | DETAIL/STRATEGY/watchlist | system batch (사용자 표시 owner 아님) |

- **source = 기존 `instrument` 테이블 재사용** (신규 테이블 0). `instrument`는 이미 KIS 종목마스터 73필드 적재 완료(`InstrumentSyncJob` + `InstrumentMasterSyncScheduler` 13 cron). `active=true` + `securities_group`(보통주) 필터로 universe 산출.
  - **미결 ① — universe 포함 종목 범위**: ETF·ETN·우선주·SPAC·리츠를 포함할지. 스크리너가 보통주 단타를 주 대상으로 하면 `securities_group` 보통주("ST") 한정이 콜 수(≈100)를 줄인다. ETF 포함 시 ~3,500 → ~117콜. **사용자 결재 필요** (§8 참조).
- **direction-guard LOCK**: MARKET_DAILY는 WS 구독 universe가 아니며, **watchlist에 자동 삽입하지 않는다**. 전종목 일봉은 스크리너 후보 산출의 raw 데이터일 뿐이고, 사용자가 검증·선택해야 STRATEGY/DETAIL owner로 승격된다 (CLAUDE.md direction guard 정합).

### 2-B. 최초 과거 backfill — 1회성, per-symbol FHKST03010100

전종목 일봉을 처음 채울 때는 **과거 이력**이 필요하다. 멀티시세는 "그날 1개"만 주므로 과거는 종목별 기간 일봉(`FHKST03010100`, period='D')을 1회 호출한다(기존 `KisRestPort.fetchCandles` 1d 경로 100% 재사용).

- **미결 ② — 이력 깊이**: 스크리너 목적상 권고는 **1년(약 250 거래일)**.
  - 근거: (a) 단타·스윙 스크리너 지표(20/60/120일 이평, 52주 신고가, 거래대금 추세)는 1년이면 충분. (b) `FHKST03010100`은 1콜 최대 100건 반환 → 1년치는 종목당 ~3콜. 3,000종목 × 3콜 = **9,000콜**(1회성). (c) 2~3년으로 늘리면 종목당 5~8콜 = 15,000~24,000콜로 비례 증가하나, 스크리너 한계효용은 낮음.
  - storage: 3,000종목 × 250 거래일 × 1d row ≈ **75만 row** ≈ 수십 MB (candle 테이블 1d 행 기준). 1년 = 무시 가능 수준.
  - 소요: 9,000콜 ÷ BG 버킷 3/s ≈ 50분(순수) — 주말/야간 1회 분산 실행. throttle gate(`BackfillThrottleGate`) + circuit breaker 재사용으로 TPS 폭주 차단.
  - **권고 = 1년. 사용자 결재 필요** (2~3년 원하면 콜·storage 비례 증가만 감수).
- 실행 형태: 신규 batch job (`MarketDailyBackfillJob`, 가칭) — 1회성 트리거(admin endpoint 또는 ENV gate). 기존 `CandleBackfillWorker`의 sanity/retry/audit 패턴 재사용 검토(설계 단계 — 구현 영역은 backend-core/market-data).

### 2-C. 야간 23:00 멀티시세 snapshot 스케줄러 — 신규 통합 1건

batch 모듈에 신규 스케줄러 1개(가칭 `MarketDailySnapshotScheduler`)를 추가한다. 매 영업일 야간 1회:

1. `instrument` 테이블 → MARKET_DAILY universe 코드 list 산출.
2. 30종목씩 chunk → `intstock-multprice`(FHKST11300006) 호출 (신규 어댑터 1건 — KIS-first).
3. 응답 6필드 → 그날(KST today) `1d` Candle 1개 매핑 (open=`inter2_oprc`, high=`inter2_hgpr`, low=`inter2_lwpr`, close=`inter2_prpr`, volume=`acml_vol`; 거래대금 `acml_tr_pbmn`는 §2-D-보강 참조).
4. `candleRepository.upsertAll(...)` — today 1d 봉 **ON CONFLICT DO UPDATE**로 적재/교정 (§2-D).

- **스케줄 시각 = 23:00 KST 근거**:
  - NXT after-hour 거래 마감 = 20:00 KST (ADR 0063). DailyCloseFinalizer가 20:10 KST에 진행 중 1d 봉을 DB INSERT.
  - 23:00은 NXT 마감 후 3시간 여유 + KIS 야간 시스템 점검 시간대(보통 23:40~00:10) 이전. → 23:00 발사가 안전 마진 확보.
  - 영업일 게이트: `MarketIndexScheduler`·`InstrumentMasterSyncScheduler`와 동일하게 `BusinessDayCalendar.isBusinessDay()` + cron `zone=Asia/Seoul` MON-FRI 1차 필터 재사용.
- rate-limit: 100콜(보통주) ~ 117콜(ETF 포함)을 BG 버킷(3/s) + inter-call jitter로 분산 → ~1~2분. 사용자 path·자본 path 영향 0.

### 2-D. today 봉 공식 보정 (deferred 흡수) — 야간 snapshot이 WS 집계를 덮는다

**핵심 정합 결정**: 장중 WS tick 집계로 만든 today 1d 봉은 부정확분(누락 tick·집계 오차)을 가질 수 있다. 야간 snapshot은 KIS 공식 마감값이므로 **today 1d 봉을 `upsertAll`(ON CONFLICT DO UPDATE)로 덮어** 공식값으로 교정한다.

- **immutable 원칙 유지**: 과거 일봉(today 이전)은 변경하지 않는다.
  - snapshot은 **그날 1개**만 반환하므로 구조적으로 today만 건드린다.
  - 일반 KIS raw 적재 경로(`saveAll`, ON CONFLICT DO NOTHING)는 그대로 유지. snapshot만 명시적 `upsertAll` 경로 사용 (CandleRepository에 두 경로 이미 존재 — 신규 메서드 0).
- **candle-data-policy 우선순위 명문화** (§3 보강에서 문서 갱신): **"공식 일봉 마감값(야간 snapshot/FHKST03010100) > WS tick 집계"**. today 1d 봉의 권위는 마감 후 공식값이 최종이다. PR #833(구독 수립 시 공식 OHLC 시드)과 연결 — 장중은 공식 시드 + WS 갱신, 야간은 공식 마감값으로 최종 확정.
- **NXT 포함 여부 — 미결 ③ (사용자 NXT 중시)**:
  - `intstock-multprice` 응답 `inter2_*` 필드가 **정규장(KRX 09:00~15:30) 종가**인지 **NXT 통합(08:00~20:00) 종가**인지가 today close의 권위를 가른다.
  - candle-data-policy는 1d를 "NXT 종가 통합(20:00 KST 마감 기준)"으로 정의(ADR 0063). 따라서 본 snapshot이 NXT 통합값이어야 정책 정합.
  - `FID_COND_MRKT_DIV_CODE_N`을 `J`(정규장)로 주면 정규장 종가, `UN`/`NX`로 주면 NXT 통합 가능성 — 멀티시세 endpoint가 시장코드별로 어떤 값을 반환하는지는 **실측 검증 필요**(설계 단계에서 추론 금지).
  - **권고**: ADR 0091 종목 단위 resolver 재사용 — NXT 후보(`is_kospi200`/`is_kosdaq150`)는 NXT 통합 시장코드 우선, 그 외 `J`. 단 멀티시세가 NXT 통합 종가를 실제 반환하는지 **R2 구현 전 raw 응답 실측 의무**. **사용자 결재 필요** (NXT 통합 close를 today 권위로 삼을지 + 멀티시세가 그 값을 주는지 실측).
  - **보강**: 멀티시세가 NXT 통합값을 안 주는 것으로 실측되면, snapshot은 "정규장 마감값 교정"으로 한정하고 NXT after-hour 반영은 DailyCloseFinalizer(WS 집계) + 별도 검증 경로로 남긴다(폴백 설계 — §6 롤백).

### 2-E. gap 복구 3천 확장 — universe만 교체, 로직 100% 재사용

기동 시 + 정기 catch-up gap 복구를 MARKET_DAILY universe로 확장한다.

- `CandleGapDetectorAdapter`(4축 교차검증·휴장일 제외) + `CandleBackfillWorker`(range fetch `incremental` 1콜/종목·throttle·circuit breaker)는 **종목 코드 list만 다르게 주면** 그대로 동작. 신규 로직 0.
- 확장 경로: PC off → 다음 야간 snapshot 또는 catch-up 사이클에서 누락 영업일 자동 복구. range fetch(`incremental=true`)는 종목당 ~1콜로 며칠치 복구하므로 비용 낮음.
- **단, 3천 종목 × 1d gap detect를 매 기동마다 전수 스캔하면 비용 큼** → MARKET_DAILY gap 복구는 **야간 snapshot 직후 1d 한정**으로 배선하고, 1m gap 복구는 active universe(~25)에만 적용(현행 유지). 즉 gap 복구 universe 분리: 1m=active, 1d=MARKET_DAILY. (1d gap은 야간 snapshot이 대부분 자동 메움 → 별도 detect는 휴일 명일·PC 장기 off 보정용 catch-up.)

---

## 3. candle-data-policy 보강 (문서 정합)

`docs/architecture/candle-data-policy-v2.md` 갱신 항목:

1. **§3 매트릭스 1d 행 보강**: "야간 멀티시세 snapshot(FHKST11300006, 30종목/콜)으로 전종목 today 1d 교정 — ON CONFLICT DO UPDATE" 명시.
2. **§ 신설 — 1d today 봉 권위 우선순위**: `공식 일봉 마감값(야간 snapshot · FHKST03010100) > WS tick 집계`. today 이전 봉은 immutable.
3. **MARKET_DAILY universe vs active managed universe 분리표** 추가 (§2-A 표 인용).
4. NXT 포함 여부 결정(미결 ③) 확정 후 §3·§6 NXT cover 행 정합.

---

## 4. 단계 분할 (R1/R2/R3) — 한 PR = 한 영역

신규 코드는 **add-only**. 재사용 자산과 신설 자산을 정직하게 구분한다.

| 라운드 | 영역 | 내용 | 신규 vs 재사용 |
|--------|------|------|----------------|
| **R0 (본 PR)** | architect | 본 ADR + candle-data-policy 보강 + README 인덱스 + master §10 | 설계만 (코드 0) |
| **R1** | domain + market-data | `intstock-multprice` 멀티시세 어댑터(신규 1) + MARKET_DAILY universe provider(신규 1, `instrument` 조회) + KIS-first raw 응답 실측(NXT 미결 ③ 검증) | 신규 어댑터 1 + provider 1. KisRestPort 시그니처는 가능하면 재사용 |
| **R2** | batch | `MarketDailySnapshotScheduler`(신규 1, 23:00 cron) + `MarketDailyBackfillJob`(1회성, 신규 1) + `upsertAll` today 보정 배선 | 스케줄러 패턴(`MarketIndexScheduler`) 재사용. UPSERT 경로(`upsertAll`) 재사용 |
| **R3** | consumer + 일괄 테스트 | gap 복구 universe 확장 배선(1d=MARKET_DAILY catch-up) + 데이터 흐름 시나리오 테스트 일괄 | `CandleGapDetectorAdapter`·`CandleBackfillWorker` 100% 재사용. universe 주입만 변경 |

**신규 코드 총량(설계 추정)**: 신규 클래스 ~4개(멀티시세 어댑터·MARKET_DAILY provider·snapshot scheduler·backfill job) + Flyway 0(테이블 재사용) + Kafka/STOMP/REST 0. 나머지(gap detect·backfill·upsert·rate-limit·instrument 마스터·영업일 캘린더)는 전부 재사용. **개발 룰 3(라운드 분할)·4(테스트 마지막 일괄)·5(LLM 0) 정합.**

---

## 5. rate-limit · storage 영향

- **야간 snapshot**: 100~117콜/일, BG 버킷(3/s) + jitter → ~1~2분. 자본·사용자 path 영향 0.
- **최초 backfill burst**: 9,000콜(1년 기준, 1회성) — 주말/야간 BG 버킷 분산 + throttle gate + circuit breaker. ~50분(순수)~수시간(분산).
- **storage**: 1년 1d = ~75만 row(~수십 MB). 분봉 0 적재이므로 1m retention(ADR 0105, 종목당 ~9M row) 대비 무시 가능. MARKET_DAILY는 **1d 단독**이 storage 경제성의 핵심.
- 버킷 priority: 야간 snapshot·backfill 모두 **BACKFILL**(또는 INGESTION_LOW). USER_PATH·INGESTION 절대 침범 0 (ADR 0116 역방향 fallback — BG는 상위 버킷 fallback 불가).

---

## 6. direction-guard 섹션 — 변경 영향·롤백

```
기존 방향: 캔들 적재는 active managed universe(~25) 중심. 전종목 일봉 부재 → 스크리너 데이터 토대 없음.
변경 의도: instrument 테이블 기반 MARKET_DAILY universe(전종목·1d 단독)를 별도 layer로 추가.
          야간 멀티시세 100콜로 그날 일봉 적재 + today WS 집계를 공식값으로 교정.
방향성 영향: 부분 변경(add-only layer). active universe·WS 구독·자본 흐름·API 계약 변경 0.
확인할 리스크:
  - 데이터: today 1d 봉을 야간 snapshot이 덮음(upsertAll) — 과거봉 immutable 유지. WS 집계 < 공식값 권위 명문화.
  - API: KIS endpoint 1건 신규(intstock-multprice). 호출량 영향 §5 산정 완료(BG 버킷 여유).
  - UX: 전종목 일봉은 watchlist 자동삽입 금지 — 사용자 화면 노출은 스크리너 후보로만. 빈 데이터는 "수집 대기" 표시(mock 금지).
  - 운영: 신규 23:00 cron 1개 + 1회성 backfill job 1개. 영업일 게이트 재사용.
  - 검증: 멀티시세 NXT 통합값 반환 여부 R2 실측 의무(추론 금지).
```

**롤백 경로**:
- snapshot ENV toggle off → 야간 갱신 중단, 기존 active universe 흐름 무영향.
- today 보정(upsertAll)이 잘못된 값을 덮을 위험 → snapshot 적재 전 sanity check(OHLC 정합·가격 range — `CandleBackfillWorker.sanityCheck` 패턴 재사용) 필수. 실패 시 today 봉 유지(덮어쓰기 skip) + audit.
- NXT 미결 실측 결과 불리 시 → snapshot을 "정규장 마감 교정"으로 한정(§2-D 보강), NXT after-hour는 DailyCloseFinalizer 경로 유지.

---

## 7. STRATEGY/DETAIL owner 분리 정합 (direction-guard LOCK)

- MARKET_DAILY universe는 **KIS WS 구독 universe가 아니다.** 전종목 일봉 적재 ≠ 실시간 구독.
- 스크리너 후보(MARKET_DAILY 일봉 기반 산출)는 **STRATEGY owner** TTL/우선순위로만 실시간 수요를 만든다 (자동 watchlist 삽입 금지 — ADR 0105 + CLAUDE.md).
- 종목 상세 진입은 `DETAIL` owner — MARKET_DAILY universe와 무관하게 KIS WS 현재가·호가·진행 캔들 요청 (현행 유지).

---

## 8. 미결 사항 — 사용자 결재 필요

| # | 결정 항목 | architect 권고 | 트레이드오프 |
|---|----------|---------------|-------------|
| ① | MARKET_DAILY universe 포함 범위 | **보통주(securities_group ST) 한정 ~3,000종목** | ETF·ETN 포함 시 ~3,500 → 117콜(미미). 우선주·SPAC·리츠는 스크리너 가치 낮아 제외 권고 |
| ② | 최초 backfill 이력 깊이 | **1년(~250 거래일)** | 1년=9,000콜(1회성)·~75만 row. 2~3년 시 콜·storage 비례 증가, 스크리너 한계효용 낮음 |
| ③ | today close NXT 포함 여부 | **NXT 통합 종가를 권위로(ADR 0063 정합)** — 단 멀티시세가 NXT 통합값 반환하는지 R2 실측 의무 | 멀티시세가 정규장 종가만 주면 NXT after-hour는 DailyCloseFinalizer 경로로 폴백(§2-D 보강) |

> 미결 3건은 임의 확정하지 않는다. 사용자 결재 후 R1 진입.
