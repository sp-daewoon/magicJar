# ADR 0100: T-MARKET 트랙 — 지수·섹터 추세 분석 모듈 (market-trend-engine 신설 + KIS endpoint 5 LOCK)

- 상태: **Accepted (2026-05-24 — architect R0 spec 발행, 사용자 brainstorming 결재 완료 cec6a4d6)**
- 작성일: 2026-05-24
- 결정자: architect (W4 통합 사이클 R0 — orchestrator dispatch 정합)
- 영역: 시장 데이터·외부 API (§5) + 모듈 경계 (§2) + 운영 단계 SLO 영향 (§9-1)
- 권위 spec: `_workspace/specs/2026-05-24-w4-t-market-t-ai-t-mcp-design.md` (brainstorming 통합본 §0~§10)
- 관련 ADR: 0001(비전) · 0003(KIS OpenAPI) · 0004(모듈 경계 — 본 ADR이 amend 트리거) · 0013(MarketSession FSM — cron 발사 게이트) · 0017(Instrument Master — 시드 path 1:1) · 0037(KIS Rate Limit — `INGESTION_LOW` 신규 추가) · 0048(WS 41 slot — 미사용 LOCK) · 0053(Living Policy) · 0057(KIS-only 데이터 정책 — 본 ADR 정합) · 0063(NXT 분기) · 0079(master html) · 0083(indicator-impl 모듈 패턴 1:1) · 0088(persistence-shared 패턴 1:1) · 0094(운영 단계 — 본 ADR이 첫 신규 트랙 도입) · 0095(SLO/SLA — 본 ADR §6 SLO 영향) · 0096(DR/backup — 본 ADR 영속 표 cover) · 0101(TrendPreference 짝 ADR)

---

## 1. 맥락 (사용자 brainstorming 결재)

### 1-1. 사용자 결정 4건 (2026-05-24 brainstorming — cec6a4d6 main 머지)

`_workspace/specs/2026-05-24-w4-t-market-t-ai-t-mcp-design.md` §0-1 정합:

| # | 의제 | 결정 |
|---|---|---|
| 1 | 지수·섹터 추세 모듈 구조 | **신규 T-MARKET 트랙 + T-AI 합류** (data layer · AI layer 분리, AI 끊겨도 본체 0 영향) |
| 2 | /goal 한번 묶음 범위 | **T-MARKET + T-AI + T-MCP** (W4 완전 닫기 · v3.9.0 cut) |
| 3 | 지수·섹터 데이터 범위 | **국내 KOSPI/KOSDAQ + 해외 NASDAQ/S&P 4 지수 + KRX 21 업종** (KIS Overseas API 점직 진출) |
| 4 | 잔재 묶음 | **잔재 0 가정** (옛 backlog § 는 Codex 외부 처리 가정 + 새 사이클 dispatch 0) |

### 1-2. fitness gap (현재 baseline) — 지수·섹터 추세 lens 0건

- 현행 `frontend/src/pages/DashboardPage.tsx` — 사용자 watchlist + signal 카드만 표시. **시장 전체 추세 view 0**
- 사용자 대시보드 진입 시 KOSPI·KOSDAQ 등락 즉시 인지 path 0 (브라우저 다른 탭으로 이동 의무)
- 섹터 ranking (오늘 강세 sector · 장기 강세 sector) view 0 → "어느 섹터에 집중할까" 의사결정 input 부재
- 자동매매 본체(시그널 → 주문)에 시장 전체 추세 input 0 — 향후 risk gate 합류 가능성

→ **T-MARKET 트랙이 본 fitness gap의 단일 처리 권위**. 자본 path 0 (read-only) + AI 옵션 path 0 (별 트랙 T-AI)

### 1-3. 운영 단계 lens 정합 (ADR 0094)

- mindset: 본 트랙은 **사용자 의사결정 보조 + read-only** → 자본 path 영향 0 → 운영 단계 SLO 5건 직접 위반 risk 0
- 영향 가능 SLO: S-4 (KIS rate limit) — cron 발사 26 req/min 추가 → 본 ADR §6에서 분석 (안전 4.3% 점유)
- 변경 관리 의무 (헌법 §9-2): rollback plan + 관측 매트릭스 본 ADR §6에 명시
- qa Visual 격상 의무 (헌법 §9-2): frontend 핵심 화면(DashboardPage + 신규 `/market/trend` page) 영향 → qa Visual 자동 dispatch 의무 (R3 단계)

---

## 2. 결정

### 2-1. 트랙 ID 신설 — **T-MARKET**

`docs/architecture/tracks-roadmap.md` §1 갱신 의무 — W4에 T-MARKET 추가 (T-AI · T-MCP 옆). 본 ADR 결재로 트랙 ID 영속.

### 2-2. 모듈 신설 — `market-trend-engine` (ADR 0004 6 → 7 amend 동반)

ADR 0083 indicator-impl · ADR 0088 persistence-shared · ADR 0092 strategy-engine 패턴 1:1 reuse:

```
market-trend-engine/         # Kotlin + Spring Context (Boot main 0) — pure math + service
├─ build.gradle.kts          # implementation(project(":domain"))
├─ src/main/kotlin/kr/spdaewoon/magicjar/market/
│  ├─ calculator/
│  │  ├─ TrendCalculator.kt          # interface — pure math (Spring 의존 0)
│  │  ├─ SmaTrendCalculator.kt       # SMA 1차 default 구현
│  │  ├─ CrossSignalCalculator.kt    # GOLDEN·DEAD·NEUTRAL 표준 정의
│  │  └─ RsRatingCalculator.kt       # Mansfield RS — sector vs KOSPI
│  ├─ service/
│  │  ├─ TrendPreferenceService.kt   # user pref 조회·검증·적용
│  │  ├─ IndexTrendComposer.kt       # IndexTrend DTO 조립 (candles + periods → result)
│  │  └─ SectorRankingComposer.kt    # SectorRanking 조립 (todayTop + longTop)
│  └─ port/
│     └─ TrendPreferenceRepository.kt  # domain port (impl은 api/persistence-shared)
└─ src/test/kotlin/.../
   ├─ SmaTrendCalculatorTest.kt       # fixture 단위
   ├─ CrossSignalCalculatorTest.kt    # GOLDEN/DEAD/NEUTRAL edge case
   ├─ RsRatingCalculatorTest.kt       # Mansfield RS 계산 정확성
   └─ ...
```

#### 모듈 경계 (ADR 0004 정합)

- `market-trend-engine` → `domain`만 의존. `api`·`batch`·`consumer` 의존 0.
- `api` · `consumer` → `market-trend-engine` 의존 (R2·R1 부분).
- `batch` → `market-trend-engine` 의존 (cron Job에서 TrendCalculator reuse).
- `frontend` → JVM 그래프 밖. HTTP/STOMP only.

#### settings.gradle.kts 갱신 (R1 backend-core 영역)

```kotlin
include("market-trend-engine")
```

### 2-3. KIS endpoint 5 LOCK (KIS-first raw fetch 의무 정합 — 헌법 §3)

`docs/architecture/policies/kis-first-raw-fetch.md` 5단계 절차 정합. raw fetch evidence는 본 ADR §3 인용. 자체 추론 0.

| 출처 | endpoint | TR_ID | 용도 | 호출 빈도 | 모듈 |
|---|---|---|---|---|---|
| KIS 국내 업종 시세 | `/uapi/domestic-stock/v1/quotations/inquire-index-price` | `FHPUP02100000` | KOSPI(0001) · KOSDAQ(1001) · KOSPI200(2001) + KRX 21 업종 현재가 | 1분 cron (장중 한정) | batch |
| KIS 국내 업종 일봉 | `/uapi/domestic-stock/v1/quotations/inquire-index-daily-price` | `FHPUP02120000` | 추세 계산 input (최대 252일 = 1 거래년) | 일 1회 16:30 KST + 사용자 변경 시 lazy | batch |
| KIS 국내 업종 등락 | `/uapi/domestic-stock/v1/quotations/inquire-index-category-price` | `FHPUP02140000` | 섹터 ranking input (`FID_COND_SCR_DIV_CODE=20214`·`FID_MRKT_CLS_CODE=K`·`FID_BLNG_CLS_CODE=0`) | 1분 cron (장중 한정) | batch |
| KIS 해외 지수 현재가 | `/uapi/overseas-price/v1/quotations/price` | `HHDFS00000300` | NASDAQ(EXCD=NAS·SYMB=.IXIC) · S&P500(EXCD=NAS·SYMB=.SPX) — exchange code 매트릭스 §3-4 | 1분 cron (해외 장중 한정) | batch |
| KIS 해외 지수 일봉 | `/uapi/overseas-price/v1/quotations/dailyprice` | `HHDFS76240000` | 해외 추세 계산 input. `GUBN=0`(daily)·`BYMD`(YYYYMMDD)·`MODP=1`(수정주가) | 일 1회 한국 시간 06:00 KST | batch |

### 2-4. cron Job 2건 (batch 모듈)

#### `MarketIndexSyncJob` — 1분 cron (장중 한정)

- 발사 조건: `MarketSession FSM` (ADR 0013) 조회 → OPEN·OVERNIGHT 면 발사, CLOSED·HOLIDAY 면 skip
- 호출 매트릭스 (per minute):
  - 국내: `inquire-index-price` × 24 (3 지수 + 21 업종) = 24 req
  - 해외: `price` × 2 (.IXIC + .SPX) = 2 req
  - 총 = **26 req/min** = 0.43 req/s
- KisCallPriority: `INGESTION_LOW` (신규 — ADR 0037 amend)
- 결과: Redis `market:index:{code}` TTL 30s + Kafka `market.index.updated.v1` publish + 영속 `market_index_snapshot` (V40 1행 / minute / code)

#### `MarketIndexDailyCloseJob` — 16:30 KST + 06:00 KST 2회

- 16:30 KST: 국내 21 업종 + 3 지수 일봉 backfill (KIS 정규장 마감 + 30분 buffer)
- 06:00 KST: 해외 2 지수 일봉 backfill (미국 정규장 마감 + 30분 buffer)
- 영속: 신규 행 또는 ON CONFLICT UPDATE
- 결과: 추세 retroactive recompute + Kafka `market.sector.updated.v1` publish

### 2-5. REST 표면 (preference 2 + trend 4 = 6 endpoint — TrendPreference는 ADR 0101 정합)

| # | Method | endpoint | 용도 | controller 위치 |
|---|---|---|---|---|
| 1 | GET | `/api/market/index/snapshot` | 4 지수 카드 (KOSPI·KOSDAQ·NASDAQ·S&P) + user pref 적용 trend 라벨 | api `MarketIndexController` |
| 2 | GET | `/api/market/index/{code}/trend?periods=5,20,60` | 지수 추세 상세. periods 생략 시 user pref. echo periods + isUserCustom | api `MarketIndexController` |
| 3 | GET | `/api/market/sectors/snapshot` | KRX 21 섹터 snapshot (이름 + 등락률 + 거래대금) | api `MarketSectorController` |
| 4 | GET | `/api/market/sectors/ranking?periods=5,20,60&rsPeriod=60` | 섹터 ranking (todayTop 5 + longTop 5) | api `MarketSectorController` |

(5·6번은 ADR 0101 TrendPreference REST endpoint)

**Swagger 4단 의무** (사용자 메모리 `feedback_swagger_pattern.md` 정합):
- 컨트롤러: `@Tag(name = "시장 추세 API", description = "지수·섹터 추세 분석 (사용자 MA periods 기본 적용)")`
- 메서드: `@Operation(summary = "...", description = "...")`
- 파라미터: `@Parameter(description = "MA 기간 list (1~5개·각 1~252일·sorted ASC)", example = "5,20,60")`
- DTO: `@Schema(description = "...", example = "...")`
- 모든 description은 **한국어 의무**

### 2-6. STOMP fanout (api 모듈)

| topic | publisher | payload | TTL |
|---|---|---|---|
| `/topic/market.index.{code}` | api consumer of `market.index.updated.v1` | IndexTrend DTO | 30s polling fallback |
| `/topic/market.sector.rank` | api consumer of `market.sector.updated.v1` | SectorRanking DTO | 5분 |
| `/topic/market.user.{userId}.pref` | api after PUT `/preferences/trend` | TrendPreference DTO | 즉시 (다중 탭 sync) |

### 2-7. Flyway 마이그레이션 2건 (V40·V41 LOCK — R1 market-data 영역)

#### V40 — `market_index_snapshot`

```sql
CREATE TABLE market_index_snapshot (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(16) NOT NULL,           -- KOSPI=0001 · KOSDAQ=1001 · KOSPI200=2001 · 업종=업종코드 · 해외=.IXIC/.SPX
    market VARCHAR(16) NOT NULL,         -- DOMESTIC_INDEX · DOMESTIC_SECTOR · OVERSEAS_INDEX
    ts TIMESTAMPTZ NOT NULL,             -- 1분 단위 snapshot 시각
    price NUMERIC(15,4) NOT NULL,        -- BSTP_NMIX_PRPR · 해외=last
    open NUMERIC(15,4) NULL,             -- 일봉 source만 채움
    high NUMERIC(15,4) NULL,
    low NUMERIC(15,4) NULL,
    close NUMERIC(15,4) NULL,
    change_rate NUMERIC(8,4) NULL,       -- BSTP_NMIX_PRDY_CTRT · 전일 대비 %
    volume BIGINT NULL,                  -- ACML_VOL (옵션, 일부 지수만 채움)
    raw_payload JSONB NULL,              -- KIS-first 정합 — raw response 보존
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_market_index_snapshot_code_ts UNIQUE (code, ts)
);
CREATE INDEX idx_market_index_snapshot_code_ts ON market_index_snapshot(code, ts DESC);
CREATE INDEX idx_market_index_snapshot_market_ts ON market_index_snapshot(market, ts DESC);
COMMENT ON COLUMN market_index_snapshot.code IS 'KIS index code (예: 0001=KOSPI · .IXIC=NASDAQ)';
COMMENT ON COLUMN market_index_snapshot.raw_payload IS 'KIS raw response (KIS-first 정합 — 필드 변경 시 add-only 흡수 가능)';
```

#### V41 — `sector_index_snapshot`

```sql
CREATE TABLE sector_index_snapshot (
    id BIGSERIAL PRIMARY KEY,
    sector_code VARCHAR(16) NOT NULL,    -- KIS 업종 코드 (예: 0024=금융업)
    sector_name VARCHAR(64) NOT NULL,    -- 사용자 노출 한국어명
    ts TIMESTAMPTZ NOT NULL,
    price NUMERIC(15,4) NOT NULL,
    change_rate NUMERIC(8,4) NOT NULL,   -- 전일 대비 %
    trade_value NUMERIC(20,2) NULL,      -- 거래대금 (원)
    raw_payload JSONB NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_sector_index_snapshot_code_ts UNIQUE (sector_code, ts)
);
CREATE INDEX idx_sector_index_snapshot_code_ts ON sector_index_snapshot(sector_code, ts DESC);
CREATE INDEX idx_sector_index_snapshot_ts ON sector_index_snapshot(ts DESC);
```

### 2-8. ADR 0037 amend (KisCallPriority enum 신규 추가 — `INGESTION_LOW`)

ADR 0037 §2-1 KisCallPriority enum 매트릭스 4단계 amend:

```
USER_PATH > INGESTION > BACKFILL > INGESTION_LOW (신규)
```

- `INGESTION_LOW` 사유: 본 T-MARKET cron 26 req/min은 사용자 path 보다 느린 user-facing latency 허용 + 정기 시장 추세 갱신 본질. BACKFILL(과거 backfill) 보다 더 낮은 우선순위 — burst 시 BACKFILL 0건 drop 우선.
- Bucket4j-Redis 단일 bucket capacity 10 token/s 그대로. priority queue (Phase 2 합류 ADR 0037 §5) 시점에서 INGESTION_LOW가 가장 먼저 drop 대상.
- ADR 0037 amend 본문은 ADR 0037 §X amend (R0 architect 동반 PR).

---

## 3. KIS-first raw fetch evidence (헌법 §3 5단계 절차 정합)

### 3-1. 발동 조건 정합

새 KIS endpoint 5건 도입 + 새 KIS 데이터 규격 결정 (지수·섹터) → **자체 추론 0 정책 발동**.

### 3-2. raw 출처 인용 5건 (공식 SDK 레포 + 공식 doc portal)

| # | endpoint | raw 출처 (구체) | 인용 사실 |
|---|---|---|---|
| 1 | `inquire_index_price` (FHPUP02100000) | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_index_price/inquire_index_price.py | path=`/uapi/domestic-stock/v1/quotations/inquire-index-price` · request: `FID_COND_MRKT_DIV_CODE='U'` + `FID_INPUT_ISCD` (0001=KOSPI·1001=KOSDAQ·2001=KOSPI200·업종 코드) · response: `BSTP_NMIX_PRPR`(현재가) · `BSTP_NMIX_PRDY_VRSS`(전일대비) · `BSTP_NMIX_PRDY_CTRT`(전일대비율) · `ACML_VOL`(누적거래량) |
| 2 | `inquire_index_daily_price` (FHPUP02120000) | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_index_daily_price/inquire_index_daily_price.py | path=`/uapi/domestic-stock/v1/quotations/inquire-index-daily-price` · request: `FID_PERIOD_DIV_CODE` (D/W/M) + `FID_COND_MRKT_DIV_CODE='U'` + `FID_INPUT_ISCD` + `FID_INPUT_DATE_1` (YYYYMMDD) · response: output1=metadata + output2=daily candles array |
| 3 | `inquire_index_category_price` (FHPUP02140000) | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_index_category_price/inquire_index_category_price.py | path=`/uapi/domestic-stock/v1/quotations/inquire-index-category-price` · request: `FID_COND_MRKT_DIV_CODE='U'` + `FID_INPUT_ISCD` (0001 등) + `FID_COND_SCR_DIV_CODE='20214'` + `FID_MRKT_CLS_CODE` (K=거래소·Q=KOSDAQ·K2=KOSPI200) + `FID_BLNG_CLS_CODE='0'` · response: output1·output2 페어 (sector 목록 + 등락률 + 거래대금) · pagination tr_cont=M/F 재귀 |
| 4 | `price` (HHDFS00000300) | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/overseas_stock/price/price.py | path=`/uapi/overseas-price/v1/quotations/price` · request: `AUTH`(인증) + `EXCD`(NAS=NASDAQ·NYS=NYSE) + `SYMB` (`.IXIC`·`.SPX` for index — 점 prefix가 index 표시) · response: DataFrame (last/change/change_rate 필드는 KIS 공식 spec 참조 의무) |
| 5 | `dailyprice` (HHDFS76240000) | https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/overseas_stock/dailyprice/dailyprice.py | path=`/uapi/overseas-price/v1/quotations/dailyprice` · request: `AUTH` + `EXCD` + `SYMB` + `GUBN`(0=daily) + `BYMD`(YYYYMMDD) + `MODP`(0/1 수정주가) · response: output1=metadata + output2=daily candles (`xymd` date · `open` · `high` · `low` · `clos` close · `tvol` volume · `t_xprc` adjusted close) · tr_cont=M/F 재귀 (default depth 10) |

### 3-3. 인용 위치 (sub-agent 코드 inline KDoc 의무 — R1 market-data 영역)

각 KIS adapter Kotlin 파일 상단 KDoc 블록에 raw URL + tr_id 명시 의무. 예시:

```kotlin
/**
 * KIS 국내 업종 현재 시세 어댑터.
 *
 * raw 출처: https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_index_price/inquire_index_price.py
 * tr_id: FHPUP02100000
 * endpoint: /uapi/domestic-stock/v1/quotations/inquire-index-price
 *
 * @see ADR 0100 §2-3 · §3-2 (KIS-first raw fetch 정합)
 */
class KisDomesticIndexPriceAdapter(...) { ... }
```

### 3-4. exchange code 매트릭스 (해외 endpoint 정합)

`overseas_stock_functions.py` raw fetch evidence:

| EXCD | Exchange | 비고 |
|------|----------|------|
| `NAS` | NASDAQ | `.IXIC` (composite) 호출 가능 |
| `NYS` | NYSE | 향후 다우 `.DJI` 확장 가능 |
| `AMS` | AMEX | post-MVP |
| `HKS` | Hong Kong | post-MVP |
| `SHS` / `SZS` | Shanghai / Shenzhen | post-MVP |
| `TSE` | Tokyo | post-MVP |

본 ADR 1차는 `NAS` 단일 (NASDAQ composite `.IXIC` + S&P 500 `.SPX` — S&P 500도 NASDAQ EXCD로 query 가능 확인 의무 — R1 market-data 영역 raw response evidence 동반 보고).

### 3-5. 자체 추론 0 LOCK 검증

본 ADR §2-3 endpoint 매트릭스 5건 + §2-7 DDL 컬럼 매트릭스 모두 §3-2 raw 출처 인용 정합. 자체 추론 0 — sub-agent R1 dispatch 시 본 §3 raw URL 인용 의무 전파.

---

## 4. 영향도 매트릭스

### 4-1. 기존 ADR · 컴포넌트 영향 (LOW · MEDIUM · HIGH)

| 기존 ADR/컴포넌트 | 영향 | 대응 |
|---|---|---|
| ADR 0004 (모듈 경계 6→7) | MEDIUM | 본 ADR 결재 후 ADR 0004 §2 amend (R0 동반 PR) — market-trend-engine 추가 |
| ADR 0037 (KIS Rate Limit) | LOW (+26 req/min = 0.43 req/s) | KisCallPriority `INGESTION_LOW` 신규 enum + ADR 0037 §X amend. 한도 10 req/s 대비 4.3% 점유 안전 |
| ADR 0048 (WS 41 slot) | 0 | 본 트랙은 REST 1분 cron only · WS 미사용 LOCK |
| ADR 0013 (MarketSession FSM) | 0 (활용만) | cron 발사 게이트로 reuse — OPEN·OVERNIGHT 시 발사 |
| ADR 0017 (Instrument Master) | 0 | 시드 path 별개 — 본 ADR은 지수·섹터 단독 |
| ADR 0094 (운영 단계) | MEDIUM | 본 ADR이 운영 단계 진입 후 첫 신규 트랙 — 운영 ADR 양식 3 슬롯 의무 (§6) |
| ADR 0095 (SLO/SLA) | LOW (S-4 직접 영향) | §6 SLO 영향 분석 |
| ADR 0084 (OrderRouter) | 0 | 본 ADR read-only · 자본 path 영향 0 grep 검증 의무 |
| 기존 DashboardPage | LOW (add-only) | MarketIndexStrip 상단 add-only · 기존 위젯 0 touch |
| 기존 SettingsModal | LOW (add-only) | "추세 설정" section add-only · 기존 section 0 touch |

### 4-2. 영역 침범 매트릭스 (헌법 §1-A · §2 정합 — 충돌 회피 LOCK)

| 영역 | 침범 | 회피 |
|---|---|---|
| api 모듈 controller | 신규 2개 add-only (MarketIndexController · MarketSectorController) | 기존 controller 0 touch |
| batch 모듈 cron Job | 신규 2개 add-only (MarketIndexSyncJob · MarketIndexDailyCloseJob) | 기존 Job 0 touch |
| Flyway version | V40·V41 LOCK (market-data 영역) | V42·V43 (ai-engineer) · V44 (backend-core-trend) · V45 (backend-core-mcp) 분리 LOCK |
| domain port | 신규 1개 add-only (TrendPreferenceRepository — ADR 0101 정합) | 기존 port 0 touch |
| frontend 화면 | DashboardPage MarketIndexStrip add-only + `/market/trend` 신규 page | 기존 화면 0 touch |
| 자본 path | 0 (read-only 완전 LOCK) | grep 검증 의무 |

### 4-3. 연결 매트릭스 (신규 → 기존 reuse)

| 신규 컴포넌트 | 활용 기존 |
|---|---|
| `MarketIndexFetcher` (R1 market-data) | `KisRateLimiterPort` (ADR 0037 INGESTION_LOW) · `KisRestClient` 패턴 1:1 |
| `MarketIndexSyncJob` (R1 batch) | `MarketSession FSM` (ADR 0013 OPEN/OVERNIGHT 게이트) |
| `TrendCalculator` (R1 market-trend-engine) | `Candle` 도메인 (W1) · indicator-impl 패턴 1:1 (ADR 0083) |
| `TrendPreferenceRepository` (port) | `user_account` (W1 V8) FK 정합 — ADR 0101 |
| `MarketIndexController` (api) | `OnboardingGuardFilter` reuse (인증 path 정합) |
| Kafka 신규 토픽 2건 | `KafkaTopicConfig` 패턴 1:1 — `market.index.updated.v1` · `market.sector.updated.v1` |
| STOMP fanout | 기존 `WebSocketConfig` reuse |
| frontend `useMarketIndex` hook | TanStack Query polling 30s + STOMP subscribe (race-safe merge 패턴 1:1) |

---

## 5. 결과 (포지티브 · 네거티브)

### 5-1. 포지티브

- 사용자 dashboard 진입 시 시장 전체 추세 (4 지수 + 21 섹터) 즉시 인지 → 의사결정 input 강화
- 모듈 신설 (market-trend-engine)로 추세 계산 로직 단일 owner — 향후 RSI·MACD 추세 라벨 등 add-only 확장 path
- KIS Overseas API 첫 진출 → NASDAQ·S&P 500 데이터 path 확보 (향후 US 종목 트랙 prereq)
- `user_preference` 표 신설 (ADR 0101) — MA periods 외 모든 사용자 설정 단일 owner 진입점 (forward-compat 확장 슬롯)

### 5-2. 네거티브 (mitigation 포함)

- KIS rate limit 26 req/min 추가 부담 — **mitigation**: ADR 0037 INGESTION_LOW 우선순위 신규 + 4.3% 점유 안전
- 신규 모듈 1개 (market-trend-engine) → 빌드 시간 미미 증가 (~3s) — **mitigation**: indicator-impl 패턴 1:1 reuse · Gradle parallel build 활용
- 신규 표 2건 (market_index_snapshot · sector_index_snapshot) → DB 부담 — **mitigation**: 1분 cron 26 row/min × 60min × 6h(장중) = ~9360 row/day. 90일 누적 ~840K row · `id BIGSERIAL` + UQ index 충분
- 1차 단일 trend type (SMA) — **mitigation**: ADR 0101 `extra JSONB` 확장 슬롯 → post-MVP EMA·WMA·DEMA add-only

---

## 6. 운영 ADR 양식 3 슬롯 (헌법 §9-5 정합 — ADR 0094·0095·0096에 이은 본 ADR 첫 적용 사례 v3.9.0 직전)

### 6-1. SLO 영향 (ADR 0095 §9-1)

| SLO | 영향 | 분석 |
|---|---|---|
| S-1 (시그널→주문 P95<5s) | 0 (자본 path 미경유) | 본 ADR read-only — 시그널 path 영향 0 |
| S-2 (KIS WS 연결률>99%) | 0 (WS 미사용) | REST cron only |
| S-3 (account API p95<500ms) | 0 (account path 별개) | 신규 endpoint는 `/api/market/*` |
| S-4 (KIS rate limit<1건/5분) | **+26 req/min = +0.43 req/s** (저영향) | 한도 10 req/s 대비 4.3% 점유. 사용자 burst(80건) + INGESTION_LOW 우선순위 분리로 안전 |
| S-5 (KillSwitch 자동 ON<1건/일) | 0 | 자본 path 미경유 |

신규 SLO 후보 (post-MVP backlog § 등재):
- `market_index_snapshot` insertion 성공률 > 99%/min — **S-MARKET-1** (P1 backlog)
- KIS overseas API 호출 성공률 > 99% (장 closed 시 자동 skip 정합) — **S-MARKET-2** (P1 backlog)

### 6-2. rollback plan

본 ADR 운영 부적합 발견 시 즉시 되돌리는 절차:

| 단계 | 절차 | 예상 소요 |
|---|---|---|
| 1 | `application.yml` `magicjar.market.index.sync.enabled=false` env toggle | < 1분 |
| 2 | `MarketIndexSyncJob` `@ConditionalOnProperty` 자동 비활성 | 즉시 (jvm restart 0건) |
| 3 | frontend `MarketIndexStrip` feature flag `VITE_FEATURE_MARKET_TREND=false` env toggle + rebuild | < 5분 |
| 4 | DB 영속 보존 (rollback이 데이터 손실 없음 — read-only 본질) | 0 |
| 5 | PR revert (단일 PR revert + Flyway V40·V41 down migration 불요 — table drop은 별 사이클 의제) | < 30분 |

전체 rollback 6분 (RTO 30분 LOCK 안에 충분 · ADR 0096 §4 정합).

### 6-3. 관측 매트릭스

| 측정 | source | 1차 권한 | alert 임계 |
|---|---|---|---|
| `MarketIndexSyncJob` 발사 빈도 | Micrometer counter `magicjar.batch.market.index.sync.invocations` | Prometheus | < 50/h (장중) warning · 0/h critical |
| 지수 fetch 실패 | SystemEvent `MARKET_INDEX_FETCH_FAIL` (P2 ERROR severity) | Prometheus query `sum(increase(magicjar_system_events_total{event_type="MARKET_INDEX_FETCH_FAIL"}[5m]))` | ≥ 5건 연속 (5m window) → **Slack ERROR** (ADR 0095 amend 동반) |
| 섹터 ranking 계산 latency | Micrometer timer `magicjar.market.sector.ranking.latency` | Prometheus | p95 > 500ms warning |
| Redis `market:index:*` hit ratio | Redis INFO | Grafana panel | < 80% warning |
| Visual 회귀 시나리오 (헌법 §9-2 frontend 핵심 화면 영향) | qa Playwright | R3 자동 dispatch | DashboardPage MarketIndexStrip 4 카드 노출 · `/market/trend` 페이지 진입 · SettingsModal 추세 설정 변경 → 차트 즉시 재계산 (3 케이스 LOCK) |

---

## 7. 트레이드오프

| 의제 | 채택 | 거부 |
|---|---|---|
| 모듈 위치 | **market-trend-engine 신설** (ADR 0083·0088·0092 패턴 1:1) | api 단독 (모듈 폭증 회피 의제) — domain 순수성 위반 risk + indicator-impl 패턴 위반 |
| KIS endpoint 5 선택 | **본 ADR §2-3 5건 LOCK** | KIS Overseas API 미사용 + 해외 Yahoo fallback (KIS-only 정책 ADR 0057 위반 risk) |
| cron 빈도 | **1분 cron** | 5분 cron (사용자 dashboard 자연스러움 감소) · 10초 cron (rate limit 부담 ↑) |
| Flyway version | **V40·V41 LOCK** (market-data 영역) | architect 결정 deferred + sub-agent 자율 — 충돌 risk |
| AI 합류 | **별 트랙 T-AI** (data layer · AI layer 분리) | T-MARKET 내부 AI prompt — AI 끊김 시 본체 영향 risk |
| frontend 1차 화면 | **DashboardPage MarketIndexStrip + `/market/trend` 페이지 양쪽** | DashboardPage strip만 — 섹터 ranking view 진입점 부재 |
| MA periods 1차 default | **[5, 20, 60]** | [10, 30, 90] (중기 안 — 사용자 1차 mental model 표준 SMA 5/20/60 정합) |
| `INGESTION_LOW` enum | **신규 신설** (ADR 0037 amend) | 기존 BACKFILL reuse — 우선순위 충돌 risk (BACKFILL은 corp action·reconcile 사용자 path 영향 큼) |

---

## 8. 후속 사이클 (post-W4)

| 의제 | 위치 | 트리거 |
|---|---|---|
| MA type 선택 (SMA → EMA·WMA·DEMA) | TrendPreference `extra.maType` (ADR 0101 확장 슬롯) | 사용자 명시 |
| RS period user-settable UI | TrendPreference UI 노출 | post-MVP |
| 섹터 추세 RSI·MACD 라벨 | TrendCalculator add-only | 사용자 명시 |
| sentiment fusion (T-NEWS news.collected.v1 → trend lens) | 별 트랙 spec | T-NEWS 합류 시 |
| 테마/테마주 그룹 (증권사마다 다른 분류 — 권위 부재) | backlog | KRX 공식 분류 권위 확보 시 |
| KIS WS 지수 실시간 push | KIS WS 41 slot 매트릭스 amend | KIS WS 지수 토픽 지원 확인 후 |
| 알림 임계 (cross 발생 시 Slack/STOMP push) | TrendPreference `extra.alertOnCross` (ADR 0101) | 사용자 명시 |

---

## 9. References

- `_workspace/specs/2026-05-24-w4-t-market-t-ai-t-mcp-design.md` (brainstorming 통합본 — 본 ADR 권위 input)
- `_workspace/specs/2026-05-24-w4-r0-design.md` (architect R0 dispatch 정합)
- ADR 0001 (시스템 비전) · ADR 0003 (KIS OpenAPI) · ADR 0004 (모듈 경계 amend) · ADR 0013 (MarketSession FSM) · ADR 0037 (KIS Rate Limit amend) · ADR 0057 (KIS-only) · ADR 0083 (indicator-impl 모듈 패턴) · ADR 0088 (persistence-shared 패턴) · ADR 0094 (운영 단계) · ADR 0095 (SLO/SLA) · ADR 0096 (DR/backup) · ADR 0101 (TrendPreference 짝)
- `docs/architecture/policies/kis-first-raw-fetch.md` (KIS-first 5단계 절차)
- `docs/architecture/master-architecture.md` §5 (모듈 매트릭스) · §10 (ADR 인덱스)
- KIS 공식 SDK: https://github.com/koreainvestment/open-trading-api
- KIS 공식 doc portal: https://apiportal.koreainvestment.com/

---

## 10. 변경 이력

| 날짜 | 변경 | 결정자 |
|------|------|--------|
| 2026-05-24 | **ADR 0100 신설 Accepted — T-MARKET 트랙 도입 + market-trend-engine 모듈 신설 + KIS endpoint 5 LOCK + cron 매트릭스 + Flyway V40·V41 LOCK + REST 6 endpoint + STOMP 3 topic + 운영 ADR 양식 3 슬롯 첫 적용 사례 (v3.9.0 후보) + KIS-first raw fetch 5 evidence 인용** — 사용자 brainstorming 결재 cec6a4d6 정합. orchestrator → architect R0 dispatch. 운영 코드 0 touch (Kotlin/TS/SQL 0 변경 — ADR + spec + ADR README + master + CHANGELOG only). 자본 영향 0 + KIS endpoint 호출 0 (R1 market-data dispatch에서 실제 호출) + AI 호출 0 + mock 합성 0. ADR 0004 6→7 amend + ADR 0037 INGESTION_LOW amend + ADR 0026·0027·0094·0095 amend 동반. ADR 0101 (TrendPreference) 짝 ADR 동시 발행. (architect, opus 4.7 1M) |
