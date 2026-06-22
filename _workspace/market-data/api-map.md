# market-data API Map — T-IM1 트랙 (종목 마스터)

## KIS 종목 마스터 정적 파일 서버

| 항목 | 값 |
|------|-----|
| 방식 | HTTP 파일 다운로드 (REST API 아님, trId 없음) |
| Base URL | `https://new.real.download.dws.co.kr/common/master` |
| 인코딩 | CP949 (줄 읽기는 ISO_8859_1 + 필드별 CP949 디코딩) |
| 레이트리밋 | 없음 (정적 파일 서버) — 1일 1회 cron 실행 |
| 인증 | 없음 |

### 파일 목록

| 시장 | 파일명 | Part2 크기 |
|------|--------|-----------|
| KOSPI | `kospi_code.mst.zip` → `kospi_code.mst` | 228 bytes |
| KOSDAQ | `kosdaq_code.mst.zip` → `kosdaq_code.mst` | 222 bytes |
| KONEX | `konex_code.mst.zip` → `konex_code.mst` | 222 bytes |
| ETF | KOSPI/KOSDAQ 파일에 포함 (별도 파일 없음) | — |
| ETN | KOSPI/KOSDAQ 파일에 포함 (별도 파일 없음) | — |

### MST 파일 레이아웃

```
[Part1]
  Byte  0 -  8 : 단축코드 (9 bytes, CP949, 6자리 앞 패딩)
  Byte  9 - 20 : ISIN (12 bytes, CP949)
  Byte 21 - (N-Part2Size-1) : 한글 종목명 (CP949, variable)

[Part2] 공통 offset (KOSPI 228 / KOSDAQ·KONEX 222 bytes)
  offset  0 -  1 : 패딩 (2 bytes)
  offset  2 -  5 : 업종코드 (4 bytes)
  offset 44      : 거래정지 여부 ('Y'/'N', 1 byte)
  offset 45 - 49 : 거래 수량 단위 (5 bytes, right-padded)
  offset 47 - 58 : 액면가 (12 bytes, right-padded)  ← offset 47 기준
  offset 79 - 86 : 상장일 (8 bytes, yyyyMMdd)
```

## 저장 구조

### PostgreSQL (Flyway V12)

| 테이블 | 용도 |
|--------|------|
| `instrument` | 종목 마스터 (ALTER 추가: isin, name_en, type, sector, listed_at, delisted_at, par_value, trading_unit, active) |
| `instrument_alias` | 종목코드 별칭 (예: 005930 ↔ 삼성전자) |
| `instrument_sync_log` | 마켓별 동기화 이력 |

인덱스:
- `idx_instrument_market` — market 컬럼
- `idx_instrument_status` — active + delisted_at
- `idx_instrument_name` — name GIN trgm (향후 pg_trgm 활성화 시)

### Redis (캐시)

| 키 | 타입 | 용도 |
|----|------|------|
| `instrument:idx:code` | ZSET | 코드 prefix 검색 (ZRANGEBYLEX) — score=0, member=`{CODE}\|{code}` |
| `instrument:idx:name` | ZSET | 종목명 substring 검색 — score=0, member=`{name}\|{code}` |
| `instrument:data:{code}` | HASH | 종목 상세 캐시 (TTL 1일) |
| `instrument:list:{MARKET}` | ZSET | 시장별 universe — score=0, member=`{code}` (TTL 1일) |

## REST API (api 모듈)

| 메서드 | 경로 | 설명 | 파라미터 |
|--------|------|------|----------|
| GET | `/api/instruments/search` | 종목 검색 | `q` (query), `limit` (max 100, default 20) |
| GET | `/api/instruments/{code}` | 종목 상세 조회 | `code` (6자리) |
| GET | `/api/instruments/{code}/candles` | 캔들 조회 | `interval`, `from`, `to` |

## 스케줄러 (batch 모듈)

| Job | Cron | Profile | 설명 |
|-----|------|---------|------|
| `InstrumentMasterSyncJob` | `0 0 6 * * *` (KST 06:00) | dev, prd | KOSPI→KOSDAQ→KONEX 순차 upsert + 상장폐지 처리 |
| `CorpActionBackfillJob` | `0 30 6 * * *` (KST 06:30) | dev, prd | 전일 KSD 법인 이벤트 수집 + 조정가 역산 |

---

# Corp Action API Map — T4 트랙 (KSD 법인행위 + 조정가)

## KIS ksdinfo 엔드포인트

| 이벤트 | 경로 | TR-ID | 설명 |
|--------|------|-------|------|
| 액면분할 | `/uapi/domestic-stock/v1/ksdinfo/rev-split` | `HHKDB669105C0` | 주식분할·병합 이력 |
| 무상증자 | `/uapi/domestic-stock/v1/ksdinfo/bonus-issue` | `HHKDB669101C0` | 무상주 배정 이력 |
| 유상증자 | `/uapi/domestic-stock/v1/ksdinfo/paidin-capin` | `HHKDB669100C0` | 유상증자·청약 이력 |
| 배당 | `/uapi/domestic-stock/v1/ksdinfo/dividend` | `HHKDB669102C0` | 현금·주식 배당 이력 |
| 합병·분리 | `/uapi/domestic-stock/v1/ksdinfo/merger-split` | `HHKDB669104C0` | 합병·물적분할·인적분할 |

### 공통 요청 파라미터

| 파라미터 | 위치 | 타입 | 설명 |
|---------|------|------|------|
| `Authorization` | Header | String | `Bearer {token}` |
| `appkey` | Header | String | KIS 앱키 |
| `appsecret` | Header | String | KIS 앱시크릿 |
| `tr_id` | Header | String | TR ID (위 표 참조) |
| `SHT_CD` | Query | String | 종목 단축코드 (6자리) |
| `F_DT` | Query | String | 조회 시작일 yyyyMMdd |
| `T_DT` | Query | String | 조회 종료일 yyyyMMdd |

### 공통 응답 스키마

```json
{
  "rt_cd": "0",
  "msg_cd": "KIOK0000",
  "msg1": "정상처리 되었습니다.",
  "output1": [ ... ]
}
```

`rt_cd != "0"` 이면 `KisApiException(msgCd, msg1)` 발생, 재시도 금지.

### output1 필드 — 엔드포인트별

**rev-split (액면분할)**

| 필드 | 설명 |
|------|------|
| `sht_cd` | 종목코드 |
| `ex_dt` | 권리락일 yyyyMMdd |
| `bfac_nmvl` | 분할 비율 (문자열, e.g. "50" → 1주가 50주로) |
| `bfor_par_prc` | 분할 전 액면가 |
| `aftr_par_prc` | 분할 후 액면가 |

**bonus-issue (무상증자)**

| 필드 | 설명 |
|------|------|
| `sht_cd` | 종목코드 |
| `ex_dt` | 권리락일 yyyyMMdd |
| `bonus_rt` | 무상 비율 (e.g. "0.05" → 5% 무상 배정) |

**paidin-capin (유상증자)**

| 필드 | 설명 |
|------|------|
| `sht_cd` | 종목코드 |
| `ex_dt` | 권리락일 yyyyMMdd |
| `rights_rt` | 청약 비율 |
| `sbsc_prc` | 청약 가격 |

**dividend (배당)**

| 필드 | 설명 |
|------|------|
| `sht_cd` | 종목코드 |
| `ex_dt` | 배당락일 yyyyMMdd |
| `cash_div` | 주당 현금 배당액 (원, 문자열) |
| `stk_div_rt` | 주식 배당 비율 (소수, 문자열) |

**merger-split (합병·분리)**

| 필드 | 설명 |
|------|------|
| `sht_cd` | 종목코드 |
| `ex_dt` | 기준일 yyyyMMdd |
| `mrg_tp` | 구분: `M`=합병, `S`=인적분할, `P`=물적분할 |
| `ratio` | 합병·분할 비율 (문자열) |

### 레이트리밋

- 도메인 분리: 별도 `KisCorpActionTokenHolder` (OAuth 토큰 공유 없음)
- 토큰 유효시간: 86400초 (24h), 만료 전 자동 재발급
- 호출 간격: 50ms sleep (인스트루먼트 단위)
- 인증 실패(잘못된 앱키): 즉시 `KisApiException`, 재시도 금지

## 저장 구조

### PostgreSQL (Flyway V13)

| 테이블 | 용도 |
|--------|------|
| `corp_action` | KSD 법인 이벤트 원본 (UNIQUE: instrument_code + type + ex_date) |

```sql
corp_action (
  id               BIGSERIAL PRIMARY KEY,
  instrument_code  VARCHAR(20) NOT NULL REFERENCES instrument(code),
  type             VARCHAR(30) NOT NULL,   -- SPLIT / BONUS_ISSUE / RIGHTS_ISSUE / DIVIDEND / MERGER / SPINOFF
  ex_date          DATE NOT NULL,
  payload          JSONB NOT NULL,         -- 타입별 상세 데이터
  source           VARCHAR(20) DEFAULT 'KIS_KSD',
  applied_at       TIMESTAMPTZ,            -- NULL = 조정가 미반영
  created_at       TIMESTAMPTZ DEFAULT NOW(),
  UNIQUE(instrument_code, type, ex_date)
)
```

인덱스:
- `idx_corp_action_code_date` — `(instrument_code, ex_date DESC)`
- `idx_corp_action_unapplied` — `(applied_at) WHERE applied_at IS NULL`

### Candle 조정가 컬럼 (Flyway V14)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `open_adjusted` | NUMERIC(18,4) | 수정 시가 |
| `high_adjusted` | NUMERIC(18,4) | 수정 고가 |
| `low_adjusted` | NUMERIC(18,4) | 수정 저가 |
| `close_adjusted` | NUMERIC(18,4) | 수정 종가 |
| `volume_adjusted` | BIGINT | 수정 거래량 |
| `adjustment_factor` | NUMERIC(20,8) | 누적 조정 계수 |

## 도메인 포트 인터페이스

```kotlin
interface KisCorpActionPort {
    fun fetchSplits(instrumentCode: String, from: LocalDate, to: LocalDate): List<CorpAction.Split>
    fun fetchBonusIssues(instrumentCode: String, from: LocalDate, to: LocalDate): List<CorpAction.BonusIssue>
    fun fetchRightsIssues(instrumentCode: String, from: LocalDate, to: LocalDate): List<CorpAction.RightsIssue>
    fun fetchDividends(instrumentCode: String, from: LocalDate, to: LocalDate): List<CorpAction.Dividend>
    fun fetchMergersAndSpinoffs(instrumentCode: String, from: LocalDate, to: LocalDate): List<CorpAction>
    fun fetchAll(instrumentCode: String, from: LocalDate, to: LocalDate): List<CorpAction>
}
```

## 룩어헤드 바이어스 방지 규칙

- `AdjustedPriceCalculator.calculateCumulativeFactor(priceDate, corpActions)` — `exDate > priceDate` 인 이벤트만 계수에 반영
- `exDate <= priceDate` 이면 시장가격에 이미 반영된 것으로 간주(계수 = 1.0)
- 소급 재계산은 `CorpActionBackfillJob`에서만 수행, 백테스트 런타임에서는 호출 금지

---

## ADR 0118 R-BF — MARKET_DAILY 10년 전종목 일봉 backfill 캠페인

전종목(보통주 ~2,517) 10년 일봉을 멱등 캠페인으로 적재한다. 야간 멀티시세 snapshot(R2, 100콜/일)과 분리된 1회성 분산 backfill.

### KIS endpoint (재사용 — 신규 endpoint 0)

| 항목 | 값 |
|------|-----|
| endpoint | `/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice` |
| tr_id | `FHKST03010100` (period_div_code=`D` 일봉) |
| raw 출처 | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py` |
| 1콜 한도 | output2[] 최대 100건/페이지 (cursor=fid_input_date_2 역방향 페이지네이션) |
| mrkt_div | ADR 0091 resolver — NXT 후보 UN 우선 + J fallback |
| rate-limit 버킷 | **BACKFILL(BG)** 고정 (§5-1 LOCK) — USER_PATH·INGESTION 침범 0 |

### 도메인 포트 (신규 1)

```kotlin
interface KisDailyCandleRangePort {
    // BACKFILL 버킷, listed_at clamp 시작 경계 명시. rate-limit/credential 결함은
    // KisDailyRangeUnavailableException(fail-closed). 정상 0건은 빈 list(uncovered).
    fun fetchDailyCandlesInRange(code: String, from: LocalDate, to: LocalDate): List<Candle>
}
```

### 트리거 / ENV (consumer 모듈, port 8082)

| 트리거 | 설명 |
|--------|------|
| `POST /admin/market-daily-backfill/run` | 멱등 캠페인 1회 실행 — progress(candle row 기반) 이어받아 미완료만 콜 cap까지 처리 |
| `GET /admin/market-daily-backfill/progress` | universe 규모·ENV 관측(콜 0) |

| ENV | default | 의미 |
|-----|---------|------|
| `MAGICJAR_MARKET_DAILY_BACKFILL_ENABLED` | false | 시동/중단 게이트(off=가동 0·적재분 보존) |
| `MAGICJAR_MARKET_DAILY_BACKFILL_HISTORY_YEARS` | 10 | 이력 깊이(결재 ② LOCK) |
| `MAGICJAR_MARKET_DAILY_BACKFILL_NIGHTLY_CALL_CAP` | 6000 | 한 실행당 KIS 콜 상한(초과분 다음 실행 이연) |
| `MAGICJAR_MARKET_DAILY_BACKFILL_COMPLETION_MIN_ROWS` | 200 | 종목 완료 판정 최소 row(candle-row progress) |
| `MAGICJAR_MARKET_DAILY_BACKFILL_RECENCY_GRACE_DAYS` | 7 | 최신 봉이 이 일수 이내면 최근까지 적재됨 판정 |

### 콜 예산·소요 추정 (ADR 0118 §5-1)

- 전종목 10년 ≈ **~75,000콜(1회성)** = ~2,517종목 × ~25콜(2,450 거래일÷100/페이지).
- BG 버킷 3/s 기준 순수 ~6.9h, jitter·재시도 포함 실효 **8~12h** → 밤당 cap(default 6,000콜 ≈ ~33분 순수)으로 **며칠~한 주 분산 완주**.
- 야간 snapshot(R2)은 이력 깊이와 무관하게 항상 100콜/일 불변 — R-BF와 별개 경로.
- ⚠ **full 실행은 사용자 환경 + 명시 go 시점.** ENABLED off default.

---

## KIS 실현손익 크로스체크 — 권위 endpoint = TTTC8715R (2026-06-22 개선 A로 승격)

> "우리 성과 숫자가 진짜 KIS와 맞는가"를 KIS 권위로 증명. **개선 A**: 1차 권위를 TTTC8494R(체결기준잔고)
> → **TTTC8715R(기간별매매손익현황조회)** 로 정정. TTTC8494R 보유 행 누락이 drift +5,879 의 root 였음(실증).

### 권위 endpoint = TTTC8715R 기간별매매손익현황조회 (raw 출처 — 추측 0)

| 항목 | 값 | raw 출처 |
|------|-----|---------|
| path | `/uapi/domestic-stock/v1/trading/inquire-period-trade-profit` | open-trading-api `examples_llm/domestic_stock/inquire_period_trade_profit/inquire_period_trade_profit.py` (`API_URL`) |
| tr_id | `TTTC8715R` | 같은 파일 (`tr_id = "TTTC8715R"`) |
| 화면 | HTS [0856] **기간별 매매손익 → "종목별"** | 같은 파일 docstring (eFriend Plus) |
| SORT_DVSN | `02` (최근순) | 공식 예제 main() 호출값 |
| INQR_STRT_DT/END_DT | `yyyyMMdd` 조회 시작/종료 | 필수 파라미터 |
| CBLC_DVSN | `00` = **전체** | 필수 파라미터 (chk 예제 `cblc_dvsn="00"`) |
| PDNO | `""` = 전 종목 | 선택(빈 문자열=전체) |

### TTTC8715R 응답 필드 (chk_*.py COLUMN_MAPPING 인용 — 추측 0)

| 필드 | 한글 | 위치 | 의미 |
|------|------|------|------|
| `rlzt_pfls` | 실현손익 | output1(매매일자×종목 행) | 종목별 실현손익. net/gross 정의는 화면 명시 부족 → **우리 net·gross 둘 다 대조해 실측 확정** |
| `fee` | 수수료 | output1 | 종목별 수수료 — 우리 `order_execution.fee` 캡처 정확화 소스 |
| `tl_tax` | 제세금 | output1 | 종목별 제세금 — 우리 `order_execution.tax` 캡처 정확화 소스 |
| `sll_qty` | 매도수량 | output1 | |
| `tot_rlzt_pfls` | 총실현손익 | output2(계좌합계) | **"당일 총 실현손익" 1차 권위** (전량청산 포함) |
| `sll_fee_smtl` | 매도수수료합계 | output2 | KIS net 보정용 |
| `sll_tltx_smtl` | 매도제세금합계 | output2 | KIS net 보정용 |

### TTTC8494R 과의 차이 (왜 8715R 로 바꿨나)

| 축 | TTTC8494R (구·보조) | TTTC8715R (신·1차 권위) |
|----|--------------------|--------------------------|
| 기준 | **체결기준잔고(보유 행)** HTS[0800] | **매매 기준(매도 발생 종목 전부)** HTS[0856] 종목별 |
| 당일 전량청산 종목 | **누락**(hldg_qty=0 → output1 행 없음) | **포함**(매도가 일어났으면 행 존재) |
| "당일 총 실현손익" 적합성 | ✗ 부적합(청산분 빠짐) | ✓ 적합 |
| fee/tax | 미제공(COST_ICLD_YN=N) | **종목별 fee·tl_tax 제공** |
| drift root | 청산 종목 누락 → KIS<우리 (실증 -5,879) | 해소 기대 |

### tolerance & net/gross 실측 판정

- `TOLERANCE_WON=1` (원 단위 정확 일치 기대). `matched` = (periodDriftNet 또는 periodDriftGross 중 하나) 절대값 ≤ 1원.
- `matchedDefinition` ∈ {NET, GROSS, BOTH, null} — KIS `rlzt_pfls`/`tot_rlzt_pfls` 가 어느 정의였는지 **실측 결과** 기록.
- 우리 `order_execution.fee/tax=0`(미캡처) → 우리 net=gross. KIS 가 net 이면 `OUR_COST_ZERO` 원인 + KIS `sll_fee_smtl+sll_tltx_smtl` 보정액 안내.

### drift 원인 트리 (개선 A)

```
matched=false
├─ kisCallOk=false ......... KIS_CALL_FAILED (자격증명·토큰·HTTP)
├─ kisPeriodTotal=null ..... KIS_SUMMARY_ABSENT (해당 기간 매도 0 또는 output2 누락)
├─ ourCost=0 & KIS fee/tax>0 OUR_COST_ZERO (우리 fee/tax 미캡처 → net=gross. KIS 비용만큼 우리 net 보정 필요)
├─ DRIFT_BREAKDOWN ......... periodDriftNet vs periodDriftGross 중 가까운 정의 표기(KIS rlzt_pfls 정의 실측)
├─ KIS_ROW_ABSENT .......... 우리 매도 종목이 TTTC8715R output1 누락(조회기간·미체결 동기화 점검 — 8715R 에선 비정상)
└─ UNCLASSIFIED ............ 위 어디에도 안 맞음(수동 점검)
```

---

## KIS 실현손익 크로스체크 (TTTC8494R) — 2026-06-22 신설 (개선 A로 보조 강등)

> "우리 성과 숫자가 진짜 KIS와 맞는가"를 KIS 권위로 증명. dormant 였던 `KisRealizedProfitPort` wiring.
> **개선 A 이후 역할**: 1차 권위 자리는 TTTC8715R 에 양보, real_evaluation·체결기준잔고 참고용 병행(실패 무시).

### KIS endpoint (raw 출처 — 추측 0)

| 항목 | 값 | raw 출처 |
|------|-----|---------|
| path | `/uapi/domestic-stock/v1/trading/inquire-balance-rlz-pl` | open-trading-api `examples_llm/domestic_stock/inquire_balance_rlz_pl/inquire_balance_rlz_pl.py` L24 |
| tr_id | `TTTC8494R` | 같은 파일 L111 |
| 화면 | HTS [0800] **국내 체결기준잔고** | 같은 파일 L47 docstring |
| INQR_DVSN | `02` (공식 chk 예제 값 / 00=전체) | `chk_inquire_balance_rlz_pl.py` L106 |
| PRCS_DVSN | `01` = **전일매매미포함** (00=전일매매포함) | `inquire_balance_rlz_pl.py` L58 |
| COST_ICLD_YN | `N` = **비용 미포함(gross)** | `chk_*.py` L110 |
| UNPR_DVSN | `01` (기본값) | L55 |

### 응답 필드 (체결기준잔고 의미)

| 필드 | 한글 | 위치 | 의미 |
|------|------|------|------|
| `rlzt_pfls` | 실현손익 | output1(종목별)·output2(계좌합계) | **gross 실현손익** (COST_ICLD_YN=N → 수수료·세금 미포함) |
| `rlzt_erng_rt` | 실현수익율 | output1·output2 | |
| `real_evlu_pfls` | 실평가손익 | output2 | 참고용(보유분 평가손익) |

### 우리 계산과의 구조적 차이 (tolerance 분류 근거)

1. **비용(COST_SEMANTICS)**: KIS=gross(비용 미포함) vs 우리 PerformanceService=net(매도 수수료·세금 차감). → 우리도 **gross 병행 산출**해 KIS gross 와 직접 비교(`ourGrossRealizedPnl`).
2. **잔고행 기준(KIS_ROW_ABSENT)**: KIS `rlzt_pfls` 는 **체결기준잔고(보유 종목)** 기준 → 당일 **전량 청산해 hldg_qty=0** 된 종목은 output1 에서 누락 가능(0800 화면 잔고행 기준). 우리 DAY 계산은 당일 매도 왕복 전부. → 종목별 정밀 비교는 "KIS 잔고행 남은 종목" 한정 + 계좌합계 semantics 명시.
3. **당일/전일(PRCS_DVSN=01)**: 전일 체결 제외 → 우리 DAY(당일 한정)와 정합.

### tolerance 표준

- **정확 일치 기대(원 단위)** — `TOLERANCE_WON=1`. gross 계좌합계 drift 절대값 ≤ 1원이면 `matched=true`.
- drift > tolerance 시 원인 분류: `COST_SEMANTICS`·`KIS_ROW_ABSENT`·`KIS_SUMMARY_ABSENT`·`ACCOUNT_DRIFT_GROSS`(미체결 지연·당일/누계·페이지네이션)·`KIS_CALL_FAILED`·`UNCLASSIFIED`.

### REST 훅 (consumer 8082, REAL 전용)

| endpoint | 설명 |
|----------|------|
| `GET /admin/reconcile/realized-pnl?userId=&period=DAY` | **TTTC8715R(1차)** + TTTC8494R(보조) 호출 + 우리 FIFO 계산 + net/gross drift·matchedDefinition·원인 반환. rate limit BACKFILL priority 보호, 자본영향 0 read-only. period ∈ DAY/WEEK/MONTH/YEAR. 응답: `kisPeriodTotalRealizedPnl`·`kisPeriodSellFee`·`kisPeriodSellTax`·`periodDriftNet`·`periodDriftGross`·`matchedDefinition` + perCode(`kisFee`·`kisTax`·`driftNet`). |

- **에이전틱 검증 훅**: 모니터링/검증이 "애매하면 KIS와 대조" 표준으로 호출.
