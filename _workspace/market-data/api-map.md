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
