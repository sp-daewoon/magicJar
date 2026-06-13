# ADR 0118 — 전종목 일봉 universe(MARKET_DAILY) + 야간 멀티시세 snapshot + today 공식 보정

- 상태: **Accepted (2026-06-09 — R1·R2 구현 완료, 2026-06-13 cron amend)** — "전종목 일봉 universe + 야간 갱신" 사용자 깊이 논의 결과를 설계로 확정 후 R1(멀티시세 어댑터·universe provider)·R-BF(10년 backfill 캠페인·J 채널 amend)·**R2(저녁 20:05 today snapshot + 장전 08:05 직전 영업일 재확정 스케줄러 — 본 §10)** 구현 완료로 Accepted 전환. 신규 코드 최소·기존 자산 재사용 원칙. **3개 미결 사용자(최고 관리자) 결재 완료: ① universe=보통주 ~3,000(ETF·ETN 제외, 실측 ~2,517) ② 이력 깊이=10년(architect 권고 1년에서 상향 — 사용자 직접 결정) ③ today 종가=~~NXT 통합 권위~~ → amend §9-B 로 J 정규장 권위(UN/NX stale 동결 실측).** R3(gap catch-up)·R-BF full run 은 운영 단계 후속.
- 작성: architect
- 영역: 데이터(A) — candle ingestion / universe / 야간 batch. 자본 결정권 0.
- 관계:
  - **ADR 0057** (KIS OpenAPI 단일 데이터 출처) — 본 ADR이 신규 endpoint(`intstock-multprice`) 1건 add.
  - **ADR 0105** (캔들 Reconciliation + 장기 1m retention) — `ManagedCandleUniverseProvider`(~25 active) 위에 별도 `MARKET_DAILY` universe layer add. managed universe 흐름 변경 0.
  - **ADR 0086 / 0090** (CandleGapDetector 4축 교차검증) — gap 복구 로직 100% 재사용, universe만 확장.
  - **ADR 0116** (KIS Rate Limit Phase 2 — 3-tier 버킷) — 저녁/장전 snapshot은 BG 버킷(BACKFILL/INGESTION_LOW) 소비.
  - **ADR 0063 / 0091** (NXT 통합 cover + 종목 단위 `UN`→`J` resolver) — snapshot의 NXT 포함 여부 결정에 직결. **2026-06-09 amend(§9)로 deprecated** — UN/NX 채널 KIS 측 stale 동결 실측 → J 단일 채널로 전환.
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
| source | holding/order/strategy/watchlist/realtime 합집합 | **`instrument` 테이블** (active=true, 보통주 한정 — ETF·ETN 제외, 결재 ① LOCK) |
| KIS WS 구독 | O (slot 41 한도 내) | **X (구독 0)** — 일봉 적재 전용 |
| 적재 interval | 1m + 1d + 합산 | **1d 단독** (분봉·합산 0) |
| 갱신 경로 | 실시간 tick + gap 복구 | **야간 멀티시세 snapshot 1회/일** |
| owner | DETAIL/STRATEGY/watchlist | system batch (사용자 표시 owner 아님) |

- **source = 기존 `instrument` 테이블 재사용** (신규 테이블 0). `instrument`는 이미 KIS 종목마스터 73필드 적재 완료(`InstrumentSyncJob` + `InstrumentMasterSyncScheduler` 13 cron). `active=true` + `securities_group`(보통주) 필터로 universe 산출.
  - **결재 ① 완료(2026-06-09) — universe = 보통주 ~3,000 한정, ETF·ETN 제외.** `securities_group` 보통주("ST") 필터로 산출. ETF·ETN은 단타 스크리너 가치가 낮고 콜 수만 늘리므로(~3,500 → ~117콜/run) 제외 LOCK. 우선주·SPAC·리츠도 동일 사유 제외. 멀티시세 snapshot 기준 콜 수 = ceil(3,000/30) ≈ **100콜/run** 확정.
- **direction-guard LOCK**: MARKET_DAILY는 WS 구독 universe가 아니며, **watchlist에 자동 삽입하지 않는다**. 전종목 일봉은 스크리너 후보 산출의 raw 데이터일 뿐이고, 사용자가 검증·선택해야 STRATEGY/DETAIL owner로 승격된다 (CLAUDE.md direction guard 정합).

#### 2-A-1. universe 범위 명확화 — "일봉 미수집 N종목"은 커버리지 버그가 아님 (2026-06-10 추가)

> **다른 세션/감사가 `instrument` 전체(~3,600) 또는 발굴 universe(~3,338) 대비 일봉 보유(~2,517)의 차이를 "미수집 커버리지 gap"으로 오인하는 것을 방지하기 위한 명시.**

`instrument` 테이블은 **모든 타입**(보통주·ETF·우선주·리츠·DR·SPAC)을 담지만, **MARKET_DAILY R-BF/R2 대상은 보통주(STOCK)뿐**이다(결재 ① LOCK). 따라서 비-보통주는 **설계상 일봉이 없는 게 정상**이다. 2026-06-10 실측 분해 (instrument 3,600 기준):

| type | 일봉 없음 | 판정 |
|------|----------|------|
| ETF (KOSPI) | 874 | **의도적 제외** (보통주 아님 — 콜 절감, 스크리너 가치↓) |
| PREFERRED 우선주 | 90 | 의도적 제외 |
| REIT 리츠 | 24 | 의도적 제외 |
| DR | 9 | 의도적 제외 |
| **STOCK** (스팩·인프라펀드 등) | ~86 | STOCK 분류지만 SPAC/특수목적 — universe 필터 제외/KIS 데이터 희박. 신규상장(2026) 0건 |

→ **"진짜 일반 보통주"의 일봉 누락은 사실상 0.** 비-보통주·SPAC graceful skip은 발굴·매매에 영향 0(데이터 없음 명시 path). **"미수집 N종목" 알람·로그는 universe 범위(보통주) 정의의 정상 결과로 해석할 것.**

- **deferred(향후 결정)**: ETF(874)는 실거래 활발 — ETF 스크리닝/매매를 도입하면 universe 필터에 ETF 추가 + R-BF 재실행으로 별도 커버 가능(universe 확장 결정 사항). 우선주·리츠·DR·SPAC은 제외 유지. **현행(2026-06-10)은 보통주만 유지 결재.**

### 2-B. 최초 과거 backfill — 1회성 캠페인, per-symbol FHKST03010100, **10년 스케일 분산 배치**

전종목 일봉을 처음 채울 때는 **과거 이력**이 필요하다. 멀티시세는 "그날 1개"만 주므로 과거는 종목별 기간 일봉(`FHKST03010100`, period='D')을 1회 호출한다(기존 `KisRestPort.fetchCandles` 1d 경로 100% 재사용).

- **결재 ② 완료(2026-06-09) — 이력 깊이 = 10년.** architect 초안 권고는 1년(~250 거래일)이었으나 **사용자(최고 관리자) 직접 결정으로 10년 상향**. 장기 패턴·다년 추세·과거 변동성 학습 토대를 위한 결정. 10년 상향은 단순히 값만 바꾸는 게 아니라 backfill 콜 예산·storage·소요 시간을 재산정해야 한다(이하 본 절 전체 재설계).

  #### 10년 콜 예산 재산정
  - 거래일: 10년 × ~245 거래일/년 = **~2,450 거래일** (휴장 제외 보수 추정).
  - `FHKST03010100`은 1콜 최대 ~100건(페이지) 반환 → 종목당 ceil(2,450/100) = **~25콜**.
  - 전종목: 3,000종목 × ~25콜 = **~75,000콜 (1회성)**. 1년 가정(~9,000콜) 대비 **~8배**.

  #### 소요 시간 재산정 → 단일 밤 초과 → 여러 밤/주말 분산 배치 필수
  - BG 버킷 안전 throttle 3/s 기준: 75,000 ÷ 3 = **25,000초 ≈ 6.9시간 (순수 호출만)**. inter-call jitter·재시도·circuit breaker open 구간 포함 시 **실효 8~12시간** 추정.
  - 야간 단일 운영 윈도(예: 20:05 snapshot 이후 ~익일 08:00 약 12시간) 안에서도 KIS 야간 점검(23:40~00:10)·일반 야간 운영 안정성을 고려하면 **단일 밤 1회 완주는 비현실적**.
  - **결론 = backfill을 snapshot과 분리된 독립 "campaign" 형태로 여러 밤/주말에 분산**한다. 체크포인트 기반 재개(이하)로 매 야간 일정 콜 예산(예: 1~2시간/밤)만 소비하고, 며칠~한 주에 걸쳐 전종목 10년 적재를 완주한다.

  #### 캠페인 idempotency·재개·circuit breaker (10년 스케일 필수 요건)
  - **체크포인트 진행 상태**: 종목별 backfill 완료 여부(예: `market_daily_backfill_progress` 진행 테이블 또는 candle row 존재 기반 산출)를 두어, 캠페인 중단(PC off·점검·circuit open) 후 재시작 시 **이미 적재된 종목·구간은 skip**(idempotent). UPSERT 경로가 ON CONFLICT(과거봉 DO NOTHING)이므로 중복 호출되어도 데이터 손상 0이지만, 콜 낭비 방지를 위해 progress skip을 1차로 둔다.
  - **circuit breaker·재시도**: 기존 `CandleBackfillWorker`의 circuit breaker(연속 실패 시 open → backoff) + 지수 backoff 재시도 100% 재사용. KIS 429/5xx burst 시 자동 차단 후 다음 밤 재개.
  - **밤당 콜 예산 cap**: 캠페인에 야간 콜 예산 상한(예: 저녁 snapshot 약 100콜 소비 후 잔여 budget으로 backfill ~수천 콜/밤)을 ENV로 두고, 초과분은 다음 밤으로 이연. 저녁 snapshot이 항상 우선(snapshot 약 100콜 선소비 → 잔여로 backfill).
  - 실행 형태: 신규 batch job (`MarketDailyBackfillJob`, 가칭) — 1회성/멱등 트리거(admin endpoint 또는 ENV gate). 매 야간 재호출 시 progress 기반으로 미완료 종목만 이어서 처리. sanity/retry/audit 패턴은 `CandleBackfillWorker` 재사용(구현 영역은 backend-core/market-data).

  #### storage 재산정 (§5 상세) — 7.3M row, 파티셔닝 불필요 판단
  - 3,000종목 × ~2,450 거래일 ≈ **~7.3M 1d row** + 인덱스. 1년 가정(~75만 row) 대비 ~10배지만 **여전히 단일 candle 테이블이 무리 없이 수용하는 규모**. 상세·파티셔닝 판단은 §5 참조.

- **저녁/장전 snapshot과의 분리 명확화**: 10년 상향은 **최초 backfill 캠페인에만** 영향을 준다. 20:05 멀티시세 snapshot(§2-C)은 "그날 1봉", 08:05 snapshot은 "직전 영업일 1봉"만 적재하므로 이력 깊이와 무관하게 **항상 약 100콜/run**이다. 둘은 별개 경로다.

### 2-C. 저녁 20:05 + 장전 08:05 멀티시세 snapshot 스케줄러 — 신규 통합 1건

consumer 모듈에 신규 스케줄러 1개(`MarketDailyNightlySnapshotScheduler`)를 추가한다. 매 영업일 저녁/장전:

1. `instrument` 테이블 → MARKET_DAILY universe 코드 list 산출.
2. 30종목씩 chunk → `intstock-multprice`(FHKST11300006) 호출 (신규 어댑터 1건 — KIS-first).
3. 응답 6필드 → 그날(KST today) `1d` Candle 1개 매핑 (open=`inter2_oprc`, high=`inter2_hgpr`, low=`inter2_lwpr`, close=`inter2_prpr`, volume=`acml_vol`; 거래대금 `acml_tr_pbmn`는 §2-D-보강 참조).
4. `candleRepository.upsertAll(...)` — today 1d 봉 **ON CONFLICT DO UPDATE**로 적재/교정 (§2-D).

- **스케줄 시각 = 20:05 KST + 08:05 KST 근거**:
  - NXT after-hour 거래 마감 = 20:00 KST (ADR 0063). DailyCloseFinalizer가 20:10 KST에 진행 중 1d 봉을 DB INSERT.
  - 20:05는 NXT 마감 직후 전종목 today 1d를 빠르게 공식값으로 교정한다. DailyCloseFinalizer(20:10)는 `INSERT DO NOTHING`이므로 20:05 R2 성공값을 덮지 않고, R2 실패 시 managed 종목 fallback 역할만 한다.
  - 08:05는 다음 영업일 장전 직전 직전 영업일 1d를 다시 확인한다. 20:05 직후 KIS 반영 지연·야간 점검·PC down 공백이 사용자 상세 진입 가격에 남지 않게 하는 보정 path다.
  - 영업일 게이트: `MarketIndexScheduler`·`InstrumentMasterSyncScheduler`와 동일하게 `BusinessDayCalendar.isBusinessDay()` + cron `zone=Asia/Seoul` MON-FRI 1차 필터 재사용.
- rate-limit: run당 100콜(보통주) ~ 117콜(ETF 포함)을 BG 버킷(3/s) + inter-call jitter로 분산 → ~1~2분. 저녁+장전 2회여도 USER/INGESTION path 침범 0.

### 2-D. today 봉 공식 보정 (deferred 흡수) — snapshot이 WS 집계를 보정한다

**핵심 정합 결정**: 장중 WS tick 집계로 만든 today 1d 봉은 부정확분(누락 tick·집계 오차)을 가질 수 있다. snapshot은 KIS 공식 마감값이므로 **today 1d 봉을 `upsertAll`(ON CONFLICT DO UPDATE)로 덮어** 공식값으로 교정한다.

- **immutable 원칙 유지**: 과거 일봉(today 이전)은 변경하지 않는다.
  - snapshot은 **그날 1개**만 반환하므로 구조적으로 today만 건드린다.
  - 일반 KIS raw 적재 경로(`saveAll`, ON CONFLICT DO NOTHING)는 그대로 유지. snapshot만 명시적 `upsertAll` 경로 사용 (CandleRepository에 두 경로 이미 존재 — 신규 메서드 0).
- **candle-data-policy 우선순위 명문화** (§3 보강에서 문서 갱신): **"공식 일봉 마감값(snapshot/FHKST03010100) > WS tick 집계"**. today 1d 봉의 권위는 마감 후 공식값이 최종이다. PR #833(구독 수립 시 공식 OHLC 시드)과 연결 — 장중은 공식 시드 + WS 갱신, 저녁/장전은 공식 마감값으로 최종 확정.
- **결재 ③ 완료(2026-06-09) — today 종가 권위 = NXT 통합 (ADR 0063 정합).**
  - 사용자 결정: today 1d 봉의 권위 종가는 **NXT 통합(08:00~20:00 KST 마감 기준)**으로 한다. candle-data-policy의 1d 정의("NXT 종가 통합, 20:00 KST 마감")와 정합한다.
  - `intstock-multprice` 응답 `inter2_*` 필드가 **정규장(KRX 09:00~15:30) 종가**인지 **NXT 통합(08:00~20:00) 종가**인지는 today close 권위를 가르며, `FID_COND_MRKT_DIV_CODE_N`을 `J`(정규장)로 주면 정규장, `UN`/`NX`로 주면 NXT 통합 가능성 — **멀티시세 endpoint가 시장코드별로 실제 어떤 값을 반환하는지는 R2 구현 전 raw 응답 실측 의무(추론 금지·헌법 §3).**
  - 적용: ~~ADR 0091 종목 단위 resolver 재사용 — NXT 후보(`is_kospi200`/`is_kosdaq150`)는 NXT 통합 시장코드 우선, 그 외 `J`.~~ **2026-06-09 amend(§9)로 변경 — UN/NX 채널 stale 동결 실측 → J 단일 채널 LOCK. today 종가 권위도 J 정규장으로 전환(NXT 통합 권위 deprecated).**
  - **폴백 설계 LOCK (불변)**: 멀티시세가 NXT 통합값을 **안 주고 정규장 종가만 반환**하는 것으로 R2 실측되면, snapshot은 "정규장 마감값 교정"으로 한정하고 NXT after-hour 반영은 **DailyCloseFinalizer(WS 집계 20:00 마감 기준) + 별도 검증 경로**로 남긴다(§6 롤백). 즉 결재는 "NXT 통합 권위 LOCK"이되, 멀티시세가 그 값을 주지 못하면 권위 source만 DailyCloseFinalizer로 폴백(권위 정의 자체는 불변).

### 2-E. gap 복구 3천 확장 — universe만 교체, 로직 100% 재사용

기동 시 + 정기 catch-up gap 복구를 MARKET_DAILY universe로 확장한다.

- `CandleGapDetectorAdapter`(4축 교차검증·휴장일 제외) + `CandleBackfillWorker`(range fetch `incremental` 1콜/종목·throttle·circuit breaker)는 **종목 코드 list만 다르게 주면** 그대로 동작. 신규 로직 0.
- 확장 경로: PC off → 다음 저녁/장전 snapshot 또는 catch-up 사이클에서 누락 영업일 자동 복구. range fetch(`incremental=true`)는 종목당 ~1콜로 며칠치 복구하므로 비용 낮음.
- **신규/단기 상장 종목 false gap 방지 (10년 backfill 필수 요건)**: 10년 backfill을 전종목에 일괄 적용하면 상장 10년 미만 종목(IPO·재상장 등)은 상장 이전 거래일이 "구멍"으로 보여 false gap·무의미 재시도를 유발한다. 이를 막기 위해 `CandleGapDetectorAdapter` **4축 교차검증 중 `listed_at` axis를 재사용**한다 — backfill 시작 경계를 `max(today - 10년, instrument.listed_at)`으로 clamp하여 **상장 이전 구간을 gap 후보에서 원천 제외**(false gap 0). `instrument` 테이블에 상장일이 이미 존재하므로 신규 필드 0. gap detector가 listed_at 이전을 미존재로 처리하는 기존 로직을 그대로 사용한다.
- **단, 3천 종목 × 1d gap detect를 매 기동마다 전수 스캔하면 비용 큼** → MARKET_DAILY gap 복구는 **snapshot 직후 1d 한정**으로 배선하고, 1m gap 복구는 active universe(~25)에만 적용(현행 유지). 즉 gap 복구 universe 분리: 1m=active, 1d=MARKET_DAILY. (1d gap은 저녁/장전 snapshot이 대부분 자동 메움 → 별도 detect는 휴일 명일·PC 장기 off 보정용 catch-up.)

---

## 3. candle-data-policy 보강 (문서 정합)

`docs/architecture/candle-data-policy-v2.md` 갱신 항목:

1. **§3 매트릭스 1d 행 보강**: "야간 멀티시세 snapshot(FHKST11300006, 30종목/콜)으로 전종목 today 1d 교정 — ON CONFLICT DO UPDATE" 명시.
2. **§ 신설 — 1d today 봉 권위 우선순위**: `공식 일봉 마감값(snapshot · FHKST03010100) > WS tick 집계`. today 이전 봉은 immutable.
3. **MARKET_DAILY universe vs active managed universe 분리표** 추가 (§2-A 표 인용).
4. **NXT 종가 통합 권위 확정 정합(결재 ③)**: 1d today 종가 = NXT 통합(20:00 마감) 권위. 멀티시세 NXT 반환 여부 R2 실측·정규장만 시 DailyCloseFinalizer 폴백 명시.
5. **MARKET_DAILY 이력 깊이 = 10년 명시(결재 ②)**: 1d 행에 "MARKET_DAILY 최초 backfill 이력 깊이 = 10년(~2,450 거래일·~7.3M row·파티셔닝 불필요)" 정합. **active managed universe 1m/1d retention(6개월~1년, ADR 0105)과는 별개 축**임을 명확히(혼동 방지).

---

## 4. 단계 분할 (R1/R2/R3) — 한 PR = 한 영역

신규 코드는 **add-only**. 재사용 자산과 신설 자산을 정직하게 구분한다.

| 라운드 | 영역 | 내용 | 신규 vs 재사용 |
|--------|------|------|----------------|
| **R0 (본 PR)** | architect | 본 ADR + candle-data-policy 보강 + README 인덱스 + master §10 + 3개 결재 확정 + 10년 재산정 | 설계만 (코드 0) |
| **R1** | domain + market-data | `intstock-multprice` 멀티시세 어댑터(신규 1) + MARKET_DAILY universe provider(신규 1, `instrument` 보통주 조회) + **KIS-first raw 응답 실측(결재 ③ NXT 통합 반환 여부 검증 — R2 배선 전 의무)** | 신규 어댑터 1 + provider 1. KisRestPort 시그니처는 가능하면 재사용 |
| **R2 ✅ 구현 완료(2026-06-09, 2026-06-13 cron amend)** | consumer(batch 대신 — 실제 부품 위치) | `MarketDailyNightlySnapshotScheduler`(신규 1, cron ENV 외부화 default 20:05 + pre-open 08:05) + `MarketDailyNightlySnapshotService`(신규 1) + `MarketDailySnapshotProperties`(신규 1) + `upsertAll` today 보정 배선(J 채널 — amend §9-B) | 스케줄러 패턴(`MarketIndexScheduler` 영업일 가드) 재사용. UPSERT 경로(`upsertAll`)·universe provider(R1)·멀티시세 어댑터(R1) 재사용. 상세 §10 |
| **R-BF (별도 캠페인 라운드 — 10년 backfill)** | batch + market-data | `MarketDailyBackfillJob`(신규 1, 멱등 캠페인) + 종목별 progress 체크포인트 + 밤당 콜 예산 cap + listed_at clamp + circuit breaker/재시도 재사용. **여러 밤/주말 분산 적재(~75,000콜·8~12h 실효)** | 신규 job 1 + progress 추적. `CandleBackfillWorker` sanity/retry/circuit 재사용. snapshot(R2)과 별도 트리거·운영 |
| **R3** | consumer + 일괄 테스트 | gap 복구 universe 확장 배선(1d=MARKET_DAILY catch-up, listed_at axis) + 데이터 흐름 시나리오 테스트 일괄 | `CandleGapDetectorAdapter`·`CandleBackfillWorker` 100% 재사용. universe 주입만 변경 |

- **10년 backfill을 별도 R-BF 캠페인 라운드로 분리한 이유**: 10년 ≈ ~75,000콜·실효 8~12시간으로 단일 야간 완주가 비현실적(§2-B). R2 snapshot(약 100콜/run)과 운영 risk·실행 주기·실패 재개 요건이 다르므로, backfill을 R2에 섞으면 한 라운드가 두 운영 성격을 혼재하게 된다. R-BF를 **멱등·체크포인트·콜예산 cap 기반 독립 캠페인**으로 떼어내 여러 밤/주말 분산 적재한다. R-BF는 R1(어댑터·universe)·R2(upsert 경로) 완료 후 가동 가능하며, R3(gap catch-up)와 무관하게 병행/선후행 모두 가능.

**신규 코드 총량(설계 추정)**: 신규 클래스 ~4개(멀티시세 어댑터·MARKET_DAILY provider·snapshot scheduler·backfill job) + backfill progress 추적 1(테이블 또는 candle row 산출 — 구현 단계 결정) + Flyway 0~1(progress 테이블 도입 시에만) + Kafka/STOMP/REST 0. 나머지(gap detect·backfill·upsert·rate-limit·instrument 마스터·영업일 캘린더)는 전부 재사용. **개발 룰 3(라운드 분할)·4(테스트 마지막 일괄)·5(LLM 0) 정합.**

---

## 5. rate-limit · storage 영향 (10년 재산정)

### 5-1. rate-limit

- **R2 snapshot (불변)**: ~100콜/run(보통주 ~3,000 ÷ 30), 저녁 20:05 + 장전 08:05 = 최대 ~200콜/영업일. BG 버킷(3/s) + jitter → run당 ~1~2분. **이력 깊이 10년과 무관하게 run당 호출 수 고정**. 자본·사용자 path 영향 0.
- **최초 backfill 캠페인 (10년 재산정)**: ~**75,000콜 (1회성)** = 3,000종목 × ~25콜(2,450 거래일 ÷ 100/페이지). 1년 가정(~9,000콜) 대비 ~8배.
  - 순수 호출 시간: 75,000 ÷ 3/s = 25,000초 ≈ **6.9시간**. jitter·재시도·circuit open 포함 **실효 8~12시간**.
  - **단일 밤 완주 비현실 → 여러 밤/주말 분산 배치**(§2-B R-BF 캠페인). 밤당 콜 예산 cap(저녁 snapshot 약 100콜 선소비 후 잔여 budget)으로 며칠~한 주 분산.
  - throttle gate(`BackfillThrottleGate`) + circuit breaker + 멱등 progress skip 재사용으로 TPS 폭주·중복 호출 차단.
- 버킷 priority: snapshot·backfill 모두 **BACKFILL**(또는 INGESTION_LOW). USER_PATH·INGESTION 절대 침범 0 (ADR 0116 역방향 fallback — BG는 상위 버킷 fallback 불가). backfill 캠페인이 8~12시간 점유해도 BG 버킷 한정이므로 사용자·자본 path 영향 0.

### 5-2. storage (10년 재산정 — 파티셔닝 불필요 판단)

- **row 추정**: 3,000종목 × ~2,450 거래일 ≈ **~7.3M 1d row** (+ 단기 상장분 listed_at clamp로 실제는 다소 작음). 1년 가정(~75만 row) 대비 ~10배.
- **물리 크기 추정**: 1d candle row(code·interval·ts·OHLCV·거래대금 등 ~수십 byte/row + 인덱스) 기준 ~7.3M row ≈ **수백 MB~~1GB대 초반**(인덱스 포함). 
- **파티셔닝 필요 여부 — 불필요 판단**:
  - 근거 (a): magicJar candle 테이블은 이미 1m 장기 retention(ADR 0105, post-MVP 500종목 시 ~90M row 가정)을 단일 테이블로 수용하도록 설계됐다. 1d **7.3M row는 그 1/10 수준**으로, 인덱스 트리(`UQ code+interval+ts`) B-tree 깊이·UPSERT 성능에 무리 없음.
  - 근거 (b): MARKET_DAILY 질의 패턴은 "code별 최근 N일" 또는 "특정일 전종목 횡단"으로, 기존 `(code, interval, ts)` 복합 인덱스가 둘 다 커버. 파티셔닝(예: ts 월별 range)은 cross-partition 전종목 횡단 질의를 오히려 복잡하게 만든다.
  - 근거 (c): Postgres 16에서 단일 테이블 ~10M row·~1GB는 표준 운영 범위. VACUUM·인덱스 유지보수 부담 미미.
  - **결론**: 10년 7.3M row는 **파티셔닝 없이 단일 candle 테이블로 수용**. 향후 universe를 ETF·전종목으로 확장하거나 1d 외 interval을 전종목 적재로 늘릴 때 재평가(post-MVP 트리거). Flyway 신규 0(테이블 재사용).
- 분봉 0 적재이므로 1m retention(종목당 ~9M row) 대비 여전히 무시 가능. MARKET_DAILY는 **1d 단독**이 storage 경제성의 핵심.

---

## 6. direction-guard 섹션 — 변경 영향·롤백

```
기존 방향: 캔들 적재는 active managed universe(~25) 중심. 전종목 일봉 부재 → 스크리너 데이터 토대 없음.
변경 의도: instrument 테이블 기반 MARKET_DAILY universe(전종목·1d 단독)를 별도 layer로 추가.
         저녁/장전 멀티시세 run당 약 100콜로 대상 영업일 일봉 적재 + today WS 집계를 공식값으로 교정.
방향성 영향: 부분 변경(add-only layer). active universe·WS 구독·자본 흐름·API 계약 변경 0.
확인할 리스크:
  - 데이터: today 1d 봉을 snapshot이 보정(upsertAll) — 과거봉 immutable 유지. WS 집계 < 공식값 권위 명문화.
  - API: KIS endpoint 1건 신규(intstock-multprice). 호출량 영향 §5 산정 완료(BG 버킷 여유). **10년 backfill ~75,000콜은 R-BF 멱등 캠페인으로 여러 밤/주말 분산 — 단일 밤 폭주 0, BG 버킷 한정으로 사용자/자본 path 침범 0.**
  - UX: 전종목 일봉은 watchlist 자동삽입 금지 — 사용자 화면 노출은 스크리너 후보로만. 빈 데이터는 "수집 대기" 표시(mock 금지).
  - 운영: 신규 20:05 cron 1개 + 08:05 재확정 cron 1개 + 1회성 backfill job 1개. 영업일 게이트 재사용.
  - 검증: 멀티시세 NXT 통합값 반환 여부 R2 실측 의무(추론 금지).
```

**롤백 경로**:
- snapshot ENV toggle off → 야간 갱신 중단, 기존 active universe 흐름 무영향.
- backfill 캠페인 ENV toggle off / 중단 → 멱등 progress 기반이므로 적재분 손상 0, 미완료분은 재가동 시 이어서 처리(이미 적재 종목 skip).
- today 보정(upsertAll)이 잘못된 값을 덮을 위험 → snapshot 적재 전 sanity check(OHLC 정합·가격 range — `CandleBackfillWorker.sanityCheck` 패턴 재사용) 필수. 실패 시 today 봉 유지(덮어쓰기 skip) + audit.
- NXT R2 실측 결과 불리 시(멀티시세가 정규장 종가만 반환) → snapshot을 "정규장 마감 교정"으로 한정(§2-D 폴백), NXT after-hour는 DailyCloseFinalizer 경로 유지. (결재 ③ NXT 통합 권위 정의 자체는 불변 — source만 폴백.)

---

## 7. STRATEGY/DETAIL owner 분리 정합 (direction-guard LOCK)

- MARKET_DAILY universe는 **KIS WS 구독 universe가 아니다.** 전종목 일봉 적재 ≠ 실시간 구독.
- 스크리너 후보(MARKET_DAILY 일봉 기반 산출)는 **STRATEGY owner** TTL/우선순위로만 실시간 수요를 만든다 (자동 watchlist 삽입 금지 — ADR 0105 + CLAUDE.md).
- 종목 상세 진입은 `DETAIL` owner — MARKET_DAILY universe와 무관하게 KIS WS 현재가·호가·진행 캔들 요청 (현행 유지).

---

## 8. 결재 완료 (2026-06-09 — 사용자/최고 관리자)

3건 미결은 사용자(최고 관리자) 결재로 **확정 LOCK**되었다. 임의 변경 금지.

| # | 결정 항목 | architect 초안 권고 | **사용자 확정 결재** | 재산정 영향 |
|---|----------|--------------------|---------------------|-------------|
| ① | MARKET_DAILY universe 포함 범위 | 보통주 한정 ~3,000 | **보통주 ~3,000 (ETF·ETN 제외) — 권고 채택** | snapshot ~100콜/run 확정 |
| ② | 최초 backfill 이력 깊이 | 1년(~250 거래일) | **10년 (~2,450 거래일) — 사용자 직접 상향** | backfill ~75,000콜(~8배)·~7.3M row(~10배)·실효 8~12h → **R-BF 분산 캠페인 신설**(§2-B·§5) |
| ③ | today close NXT 포함 여부 | NXT 통합 권위 + 멀티시세 실측 | **NXT 통합 종가 권위 LOCK (ADR 0063 정합) — 권고 채택. 멀티시세 NXT 반환 여부 R2 raw 실측 의무. 정규장만 주면 DailyCloseFinalizer 폴백 유지** | §2-D NXT/정규장 분기 + 폴백 설계 LOCK |

**status 처리**: 결정은 LOCK이나 구현(R1~) 전이므로 ADR status는 **Proposed 유지**. R1(어댑터·universe·NXT 실측) 구현 완료 시 **Accepted** 전환 예정.

**후속**: 결재 확정으로 R1 디스패치 가능. NXT 실측(③)은 R1에서 raw fetch로 선행 검증 후 R2 배선에 반영.

---

## 9. amend (2026-06-09) — 채널 J-단일 우선 + wipe 마이그레이션 모드 + 동시성 보강

> 라이브 dogfooding 에서 일부 종목 일봉이 2026-02-11 에 동결된 결함의 **근본 원인을 REAL 키 raw 실측으로 확정**하고,
> 사용자(최고 관리자)가 **J 단일 채널 · 일봉 패밀리만 wipe→재적재 · 장중 즉시 실행**을 결재 완료했다. 본 §가 그 결정을
> ADR 본문에 LOCK 한다. 관계 ADR 0063/0091(NXT 통합·종목 단위 `UN`→`J` resolver)은 본 amend 로 **deprecated**.

### 9-A. 근본 원인 — UN/NX 채널 stale 동결 (raw 실측 evidence)

036930·047040·098460 일봉이 2026-02-11 에 동결(주봉도 2/02 동결)되었으나, **같은 종목 1분봉은 오늘
(2026-06-09 13:40) 실시간 체결 정상 유입** → 거래정지/데이터부재 아님. REAL 키로 `inquire-daily-itemchartprice`
(FHKST03010100)를 **채널별 raw 호출**한 실측:

| code | J(KRX) | NX(NXT) | UN(통합) |
|------|--------|---------|----------|
| 036930 | **20260609 정상** | 20260211 stale | 20260211 stale |
| 047040 | **20260609 정상** | - | 20260211 stale |
| 098460 | **20260609 정상** | - | 20260211 stale |
| 138040 | 20260609 | - | 20260609 |
| 455180 | 20260609 | - | 0 rows |

`KisRestCandleAdapter.resolveMrktDivCodeCandidates(code)`가 KOSPI200/KOSDAQ150 멤버에 `["UN","J"]`(UN 우선)을
썼다. KIS 측 결함으로 UN(통합) 일봉이 일부 종목에서 2/11 에 동결돼 있는데, fallback 은 "UN 0건"일 때만 J 로
가므로 UN 이 stale 8행을 주면 그대로 채택하고 J(항상 최신)를 영영 안 본다 → **일부 종목 일봉 영구 동결**.
J(KRX 본시장)는 전 종목 항상 최신·정상.

### 9-B. 결정 — J 단일 primary (UN/NX deprecated)

- `resolveMrktDivCodeCandidates()`는 **항상 `listOf("J")`** 반환. UN/NX 분기·fallback 전면 제거.
- `KisMultiPriceAdapter`(R2 today 종가·FHKST11300006)의 `fetchMultiPrice(mrktDivCode = "J")` default 도 J 정합
  (§2-D 의 "NXT 통합 종가 권위"는 본 amend 로 **J 정규장 권위**로 변경 — UN/NX stale 으로 신뢰 불가).
- **trade-off 수용**: 일봉 거래량에서 NXT 분 제외(본시장 거래량만). 정합성·신뢰성 우선. 향후 KIS 측 UN/NX
  stale 해소 시 `resolveMrktDivCodeCandidates()` 단일 지점 교체 + ADR 재amend 로 복원 가능.

### 9-C. wipe→재적재 마이그레이션 모드

UN/NX 로 적재된 stale 일봉을 J 기준으로 일소하기 위해, `MarketDailyBackfillCampaign` 에 명시적 게이트
`BackfillMode.MIGRATE` 를 add (default `NORMAL` 은 절대 wipe 하지 않음·기존 멱등 재개 그대로):

- **wipe 범위 LOCK**: `interval IN ('1d','1wk','1mo','1y')` 4종만(REST 재생성 가능). `MarketDailyWipeService`
  화이트리스트 상수(`MIGRATE_WIPE_INTERVALS`)가 컴파일-타임 LOCK + init 단언으로 **`1m`·`5m` 절대 보존**.
  사유: 실시간 누적 1m·5m 는 KIS REST 로 복구 불가(영구 손실) — 가장 중요한 안전 제약.
- 대량 삭제는 `ctid` LIMIT 배치(독립 트랜잭션 REQUIRES_NEW)로 long-transaction 회피·멱등 재개.
- wipe 후 전체 MARKET_DAILY universe 를 J 채널로 재적재(기존 R-BF 멱등 캠페인 재사용). 응답에 `wipedRows`·`mode` 노출.
- 트리거: `POST /admin/market-daily-backfill/run?mode=MIGRATE` (default·파라미터 없음=NORMAL). 실 wipe 실행은
  머지·재배포 후 오케스트라가 운영 단계에서 수행.

### 9-D. 동시성 보강

기존 종목별 동기 순차 루프(1.5~2h/run, 단일호출 지연이 주범)를 **coroutine Semaphore(permits=N) + 종목 launch당
지연** 비동기로 전환. 책임 분리 LOCK:

- coroutine Semaphore = **throughput shaping**(동시 종목 수 제한). ENV `..._CONCURRENCY`(default 10) · `..._LAUNCH_DELAY_MS`(default 100).
- KIS **hard ceiling 은 `ConsumerRedisKisRateLimiterAdapter`(Bucket4j Redis bucket)가 그대로 유효** — 세마포어가
  rate limiter 를 우회·중복하지 않음. blocking WebClient `.block()` 은 `Dispatchers.IO.limitedParallelism` 격리.
- `BackfillThrottleGate` 의 serial semaphore(1)·minInterval(1.5s)는 동시성을 무력화하므로 MARKET_DAILY path 는
  `runWithCircuitGuard`(circuit breaker만 보존)로 분리. 1m managed-universe path 의 `runThrottled` 는 무변경.
- call-cap·progress·no-advance·audit 회계는 `AtomicInteger`/`AtomicLong`/`AtomicBoolean` 으로 원자성 보호.

### 9-E. ENV 신규 (`.env.example` 동기)

```
MAGICJAR_MARKET_DAILY_BACKFILL_CONCURRENCY=10       # 동시 처리 종목 수(coroutine Semaphore permits)
MAGICJAR_MARKET_DAILY_BACKFILL_LAUNCH_DELAY_MS=100  # 종목 launch 간 지연(ms) — burst 분산
```
(직전 머지된 `..._ENABLED`·`..._HISTORY_YEARS`·`..._NIGHTLY_CALL_CAP` example 누락분도 본 사이클에 동기.)

---

## 10. R2 구현 완료 (2026-06-09 — market-data, 2026-06-13 cron amend) — 저녁/장전 전종목 일봉 snapshot 스케줄러

> R1(멀티시세 어댑터·MARKET_DAILY universe provider)·R-BF(10년 backfill 캠페인)는 머지됐으나
> **R2 스케줄러+caller+today upsert 가 미배선**이라(주석/ADR엔 있으나 `@Scheduled` 미구현),
> 비-managed ~2,480 종목 today 일봉이 매일 자동 전진하지 못하는 공백이 있었다. 본 §가 그 공백을 메운다.
> 오케스트라 실측: managed 잡(DailyCloseFinalizer 20:10·LastCandleAtMonitor 18:00)은 watchlist/보유/전략/상세
> 수십개만 커버 → 전종목 today 갱신 권위 부재.

### 10-A. 구현 부품 (신규 3 · consumer 모듈)

배선 위치는 ADR §4 표가 "batch"라 적었으나 **R1·R-BF 부품이 모두 consumer 모듈에 있고**(`KisMultiPriceAdapter`·
`MarketDailyUniverseProvider`·`MarketDailyBackfillCampaign`), consumer 가 이미 `@EnableScheduling` +
DailyCloseFinalizer 등 다수 `@Scheduled` 워커를 보유하므로 **consumer 에 배선**(영역 침범 0·부품 인접).

| 신규 클래스 | 책임 |
|------------|------|
| `MarketDailyNightlySnapshotScheduler` | `@Scheduled(cron=ENV, zone="Asia/Seoul")` 트리거. 영업일 가드(`BusinessDayCalendar`) + best-effort(예외 graceful) |
| `MarketDailyNightlySnapshotService` | universe → 30 chunk → `KisMultiPricePort.fetchMultiPrice("J")` → today 1d Candle 매핑 → `upsertAll`(DO UPDATE) + audit |
| `MarketDailySnapshotProperties` | `enabled`(kill-switch)·`cron`(ENV 외부화)·`chunkSpacingMs`. `ChartCandleConfiguration` 에 등록 |

### 10-B. 동작 매트릭스

- **저녁 cron**: `@Scheduled(cron = "${magicjar.market-daily-snapshot.cron:0 5 20 * * *}", zone = "Asia/Seoul")` —
  ENV `MAGICJAR_MARKET_DAILY_SNAPSHOT_CRON` 외부화(default 20:05). `@Scheduled` 가 SpEL placeholder 만 받는
  제약 때문에 cron 은 property placeholder 로 주입(메서드 호출 불가).
- **장전 cron**: `@Scheduled(cron = "${magicjar.market-daily-snapshot.pre-open-cron:0 5 8 * * *}", zone = "Asia/Seoul")` —
  오늘이 영업일이면 직전 영업일 1d 를 재확정한다.
- **거래일 가드**: `BusinessDayCalendar.isBusinessDay(today)` — market_calendar DB 기반(공휴일·임시휴장 skip),
  `MarketIndexScheduler`·`InstrumentMasterSyncScheduler` 와 동일 KRX 영업일 판정 재사용.
- **universe**: `MarketDailyUniverseProvider.resolveCodes()`(보통주 ~2,517) → **30종목 chunk**(~84콜).
- **멀티시세**: `KisMultiPricePort.fetchMultiPrice(chunk, "J")` chunk 단위 호출 + chunk 간 `chunkSpacingMs`(120ms
  default) spacing(burst 분산 — 2026-06-09 wipe→재적재 교훈). rate-limiter(`BACKFILL` BG 버킷)는 어댑터 내부 유효.
- **today upsert**: 응답 OHLC+volume → `ts=today 00:00 KST`(DailyCloseFinalizer·`stck_bsop_date` 관행 정합)
  1d Candle → `candleRepository.upsertAll`(ON CONFLICT DO UPDATE). source=`KIS-MKT-DAILY-SNAP`(18자 ≤ varchar(20)).
  OHLC 4필드 중 하나라도 결손이면 skip(부분 봉 적재 금지·mock 0). 멱등 — 같은 (code,1d,today) 키 중복 호출 시 행 안 늘고 값만 갱신.
- **kill-switch**: `MAGICJAR_MARKET_DAILY_SNAPSHOT_ENABLED`(default true) — off 시 서비스 DISABLED 즉시 종료(콜 0).
- **관측**: 처리 종목수·chunk·quotes·candlesMapped·upsertedRows·skipped·durationMs 로그 + audit(START·DONE·EMPTY).

### 10-C. R-BF / R2 / managed 3자 분업 (LOCK)

| 잡 | 시각 | universe | interval | 경로 / 충돌 처리 | 권위 |
|----|------|----------|----------|------------------|------|
| **R2 저녁 snapshot** | **20:05** | **전종목 ~2,517** | **today 1d** | 멀티시세 → `upsertAll`(ON CONFLICT **DO UPDATE**) | **KIS 공식 종가(권위·후행 교정)** |
| R2 장전 재확정 | 08:05 | 전종목 ~2,517 | 직전 영업일 1d | 멀티시세 → `upsertAll`(ON CONFLICT **DO UPDATE**) | 전일 공식값 재확정 |
| DailyCloseFinalizer | 20:10 | managed(watchlist) | today 1d | Redis WS 집계 → `insertIgnore`(ON CONFLICT DO NOTHING) | R2 실패 시 managed fallback |
| R-BF backfill | admin /run·여러 밤 | 전종목 | 과거 1d(10년) | range fetch → `saveAll`(ON CONFLICT DO NOTHING) | 과거봉 immutable |

- **R2 는 today 1봉만 전진**(과거 gap 채우기 아님 — R-BF 소관). 시스템 며칠 down 시 누락 과거분은
  `POST /admin/market-daily-backfill/run`(R-BF)으로 메운다.
- **같은 (code,1d,today) 키 충돌 0**: R2 가 20:05에 멀티시세 공식 종가로 `upsertAll`(DO UPDATE) 하면,
  DailyCloseFinalizer 의 20:10 `insertIgnore` 는 같은 row 를 덮지 않는다. R2 실패 시에만 managed 종목의 WS 집계가 보조로 들어간다. R-BF 는 today 미적재(어제까지)라 R2 와 시간 축 분리.

### 10-D. SLO 영향 · rollback · 관측 (운영 단계 ADR 양식 §9-5)

- **SLO 영향**: 사용자·자본 path 영향 0(BG 버킷 한정, ~84콜 × 2회/영업일). 자동매매 시그널·주문 path 미접촉.
- **rollback**: `MAGICJAR_MARKET_DAILY_SNAPSHOT_ENABLED=false` → 야간 갱신 즉시 중단(기존 active universe·DailyCloseFinalizer
  흐름 무영향). today 봉 잘못 덮을 위험 → OHLC 결손 quote skip + upsert 예외 graceful(다음 chunk 진행).
- **관측 매트릭스**: 로그 `MarketDailyNightlySnapshot 종료: ... upserted={} durationMs={}` + audit_trail
  `entity_type=market_daily_snapshot`(action START/DONE/EMPTY). frontend 영향 0(Visual 회귀 N/A).

### 10-E. status 전환

R1·R2 구현 완료로 본 ADR status **Proposed → Accepted** 전환 (R3 gap catch-up·R-BF full run 은 운영 단계 후속).
