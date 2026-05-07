# PR-29 W1 마감 설계 (T-MS1 + T-CA1 + T-S1 잔재 통합)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-08
- 사이클 목표: **W1 마감 + v3.1.0 릴리즈 + release/v3.1 베이스라인**
- 본 spec PR(=PR-29 본 PR) 자체는 **코드 변경 0** — 룰 2(단일 책임) 정합 (docs only)
- 정합 SoT:
  - master-architecture.md §A.2·§A.3·§3-2·§3-5
  - tracks-roadmap.md §2 T-MS1·T-CA1
  - ADR 0013 (Market Session FSM) · 0014 (Fallback 3-tier) · 0015 (REJECTED Audit) · 0016 (KIS HTTP 정밀화) · 0018 (Corp Action raw+adjusted) · 0017 (Instrument)
  - candle-data-policy-v2.md §6 polling fallback
  - external-api-policy.md §4 DART · §5 운영
  - PR-9b 임시 SimpleMarketSessionResolver(시간 기반 stub) — 본 사이클 본격 교체 대상
  - PR-29 사이클 컨텍스트 (devlog 2026-05-08 §4·§6)
- 사용자 메모리 정합:
  - `feedback_kis_first_policy.md` — KIS endpoint·메시지 형식은 open-trading-api raw fetch 의무
  - `feedback_v3_skepticism_no_regression.md` — fitness gap 의무
  - `feedback_dev_priority_qa_batch.md` — minor 정정에 매달리지 말 것. backlog 흡수 + 다음 트랙. QA는 누적 후 일괄
  - `feedback_orchestrator_active_parallel.md` — add-only 충돌은 진짜 병렬 가능
  - `feedback_3tier_hierarchy.md` — 오케스트라 → architect → 실무 sub-agents (병렬)
- 운영 헌법 정합: `_workspace/arch/agent-charter.md` §1·§3·§4·§5·§6

---

## §1. 사이클 한 줄

> **W1 잔재 3 트랙(T-MS1 M + T-CA1 M + T-S1 잔재 S)을 4 sub-agent PR(PR-29a/b/c/d)로 분배 머지하여 W1 마감하고 v3.1.0 태그 + release/v3.1 베이스라인 컷.** 신규 ADR 0건(0013·0014·0015·0016·0018 이미 발행 — 결정 사유 모두 정합). KIS-first raw fetch 7개 endpoint 출처 LOCK(휴장일 1건 + corp action 6건). DART OPENDART 공시검색 endpoint LOCK. **Flyway V6·V7·V8 add-only 신설 LOCK**. 영역 분배는 add-add 0% 사전 검증.

---

## §2. PR-29 사이클 트랙 매트릭스

| sub PR | 트랙 | sub-agent | 모듈 | 주요 산출물 |
|---|---|---|---|---|
| **PR-29a** | T-MS1 | backend-core | domain/api/batch | MarketSessionResolver 본격 교체 + KrxBusinessDayCalendar + Flyway V6 `market_calendar` + V7 `order_reject_audit` + DailyCloseFinalizer @Scheduled 16:00 KST + RejectReason 도메인 + KIS chk-holiday 어댑터 |
| **PR-29b** | T-CA1 | market-data | domain/batch/api | DART OPENDART 어댑터 + KIS ksdinfo 6 endpoint 어댑터 + CorpAction 도메인 + CorpActionSyncJob (cron 18:30) + CandleAdjustmentJob (retroactive 재계산) + Flyway V8 `corp_action` + V8b candle ALTER (close_raw + close_adjusted + adjustment_factor) |
| **PR-29c** | T-S1 잔재 | frontend | frontend | LWC 본격 차트 검증 (5/15/30/60/D/W/M interval 토글 + 무한 스크롤) + 호가창 STOMP 구독 동작 검증 + dogfooding §6.5 한글 검색 회귀 검증 + delisted 표시 검증 |
| **PR-29d** (R3 후반) | W1 R3 일괄 테스트 | backend-core | api/batch | SymbolControllerTest OnboardingGuardFilter mock 보강 + batch test @Disabled 2건 assertion 정정 (KisInstrumentMasterAdapterTest + InstrumentSyncJobTest) |

**룰 2 단일 책임 사유**: PR-29a·b·c·d 모두 **단일 트랙**. 다만 PR-29a는 T-MS1 + REJECTED audit(ADR 0015) 통합 — 이는 "장 세션·영업일 인지 → 영업일 외 주문 reject + audit"가 한 인과 사슬이라 단일 책임으로 간주(ADR 0013·0015 모두 본 트랙 LOCK).

---

## §3. T-MS1 — Market Session FSM + Data Fallback (PR-29a)

### §3-1. 핵심 결정 (ADR 0013·0014·0015·0016 정합 — 신규 0건)

| 영역 | 결정 | ADR |
|---|---|---|
| FSM 4 상태 | PreMarket / Open / PostMarket / Closed | 0013 §2 |
| 시간 전이 (영업일) | 08:00 PreMarket → 09:00 Open → 15:30 Closed → 15:40 PostMarket → 18:00 Closed | 0013 §2-1 |
| 비영업일 | 종일 Closed | 0013 §2-1 |
| 영업일 source | KRX 정적 캘린더 시드 (1차) + KIS chk-holiday 어댑터 (sync-at-boot + cron 03:00 daily) | 0013 §2-2 |
| Redis 캐시 | `session:cache:current` TTL 60초 | 0013 §2-2 |
| profile 분기 | mock=NoOp(Open) / dev=toggle / prd=strict | 0013 §2-3 |
| Fallback 3-tier | T1 live / T2 cache (10초 무신호) / T3 last-day-close (Closed 시) | 0014 §2 |
| stale 표시 | `stale: boolean` + `asOf` + `source` payload 추가 | 0014 §2 |
| REJECTED audit | orders INSERT + order_reject_audit 테이블 | 0015 §2-1·§2-3 |

### §3-2. 도메인 모델 LOCK

```kotlin
// domain/.../market/session/
sealed class MarketSession {
    object PreMarket : MarketSession()
    object Open : MarketSession()
    object PostMarket : MarketSession()
    object Closed : MarketSession()
}

interface MarketSessionResolver {
    fun current(at: Instant = Instant.now()): MarketSession
    fun businessDayOf(at: Instant): LocalDate?  // null = 비영업일
    fun isBusinessDay(date: LocalDate): Boolean
    fun nextBusinessDay(from: LocalDate): LocalDate
    fun previousBusinessDay(from: LocalDate): LocalDate
}

interface BusinessDayCalendar {
    fun isBusinessDay(date: LocalDate): Boolean
    fun seedRange(from: LocalDate, to: LocalDate): List<MarketCalendarEntry>
}

data class MarketCalendarEntry(
    val date: LocalDate,
    val isBusinessDay: Boolean,
    val isHoliday: Boolean,
    val holidayName: String?,
    val source: String,  // "STATIC_SEED" | "KIS_CHK_HOLIDAY" | "MANUAL"
)

// domain/.../market/order/
sealed class RejectReason(val code: String) {
    object KillSwitchActive : RejectReason("KILL_SWITCH")
    object MarketSessionClosed : RejectReason("SESSION_CLOSED")
    object MarketNotBusinessDay : RejectReason("NOT_BUSINESS_DAY")
    object DailyOrderLimitExceeded : RejectReason("DAILY_LIMIT")
    object PositionLimitExceeded : RejectReason("POSITION_LIMIT")
    object DrawdownStopTriggered : RejectReason("DRAWDOWN_STOP")
    object PriceSanityFailed : RejectReason("PRICE_SANITY")
    data class KisApiError(val rtCd: String, val msg: String) : RejectReason("KIS_API:${rtCd}")
    data class Internal(val message: String) : RejectReason("INTERNAL")
}
```

### §3-3. KIS chk-holiday endpoint LOCK (KIS-first raw fetch 의무)

**출처**: `https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/chk_holiday/chk_holiday.py` (2026-05-08 fetch)

| 항목 | 값 (raw 1:1) |
|---|---|
| **API_URL** | `/uapi/domestic-stock/v1/quotations/chk-holiday` |
| **tr_id** | `CTCA0903R` |
| **params** | `BASS_DT` (YYYYMMDD, 필수) · `CTX_AREA_FK` (연속조회 검색조건) · `CTX_AREA_NK` (연속조회 키) |
| **응답 output** | `bass_dt` (기준일자) · `wday_dvsn_cd` (요일구분코드) · `bzdy_yn` (영업일여부 Y/N) · `tr_day_yn` (거래일여부) · `opnd_yn` (개장일여부) · `sttl_day_yn` (결제일여부) |
| **호출 한도** | **(★중요) 1일 1회 호출 권장** — KIS 원장서비스 영향 |
| **연속조회** | `tr_cont = M/F` 시 다음 페이지 존재 |

**우리 정책**:
- sync-at-boot 1회 + cron 03:00 daily — 약 1년치 캘린더 (NK·FK 페이징) 한 번에 fetch
- 응답 → `market_calendar` 테이블 upsert (멱등)
- 실패 시 정적 시드(`market-calendar-seed.json` — 2026~2027 KRX 휴일 약 30건)로 fallback

### §3-4. Flyway V6 LOCK — `market_calendar`

```sql
-- api/src/main/resources/db/migration/V6__market_calendar_and_reject_audit.sql
-- 본 V6 1 파일에 §3-4 (market_calendar) + §3-5 (order_reject_audit) 모두 포함

CREATE TABLE market_calendar (
    date DATE PRIMARY KEY,
    is_business_day BOOLEAN NOT NULL,
    is_holiday BOOLEAN NOT NULL DEFAULT false,
    holiday_name VARCHAR(64),
    source VARCHAR(32) NOT NULL,  -- 'STATIC_SEED' | 'KIS_CHK_HOLIDAY' | 'MANUAL'
    synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_market_calendar_business ON market_calendar(date) WHERE is_business_day = true;
```

**LOCK 사유**: V5까지 사용 중 (V1 baseline·V2 onboarding·V3 watchlist·V4 instrument search·V5 instrument KIS-first 70+컬럼). V6은 첫 빈 번호. **본 사이클 PR-29a 단독 V6 LOCK**.

### §3-5. order_reject_audit (V6 동일 파일)

```sql
-- V6 동일 파일 §3-5

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS reject_reason VARCHAR(64),
    ADD COLUMN IF NOT EXISTS reject_detail TEXT,
    ADD COLUMN IF NOT EXISTS reject_source VARCHAR(32);

CREATE TABLE order_reject_audit (
    id BIGSERIAL PRIMARY KEY,
    intent_id UUID NOT NULL,
    user_id BIGINT NOT NULL,
    code VARCHAR(8) NOT NULL,
    side VARCHAR(8) NOT NULL,
    reject_source VARCHAR(32) NOT NULL,  -- 'GUARD' | 'KIS_API' | 'INTERNAL'
    reject_reason VARCHAR(64) NOT NULL,
    reject_detail TEXT,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reject_user_time ON order_reject_audit(user_id, occurred_at DESC);
CREATE UNIQUE INDEX uq_reject_intent ON order_reject_audit(intent_id);
```

**LOCK 사유**: ADR 0015 §2-1과 1:1. V11 표기는 ADR 본문이고, 실제 채번은 본 spec 시점 V6. **V6 한 파일에 market_calendar + order_reject_audit 묶음** (한 트랙 PR-29a 단독 책임).

### §3-6. 어댑터·서비스·스케줄러 LOCK

| 산출물 | 모듈 | 책임 |
|---|---|---|
| `KrxMarketSessionResolver` | api or consumer (PR-29a 위치 LOCK = `consumer/.../market/session/`) | MarketSessionResolver 구현 — Redis 캐시 + DB market_calendar 조회 |
| `MarketCalendarRepository` (port) | domain | seedRange / findByDate |
| `MarketCalendarJpaEntity` + Adapter | api | persistence |
| `KisChkHolidayAdapter` | batch | KIS chk-holiday endpoint 호출 + market_calendar upsert |
| `MarketCalendarSyncJob` | batch | sync-at-boot + cron 03:00 daily |
| `MarketCalendarSeedRunner` | batch | @PostConstruct 정적 시드 (응답 0건 시) |
| `DailyCloseFinalizer` | consumer | @Scheduled 16:00 KST — 1d candle 영속 (T3 fallback source) |
| `TickFallbackEmitter` | consumer | 10초 주기 — T1 신호 없거나 session=Closed 시 T2/T3 합성 publish |
| `RedisQuoteCacheAdapter` (이미 PR-9a 존재 — 본격 활용만) | consumer | T2 source `quote:last:*` |
| `KisInstrumentMasterAdapter` 정합 | batch (이미 PR-21a 존재) | 변경 0 |

### §3-7. 함정·트레이드오프

1. **chk-holiday 1일 1회 호출 권장** — KIS 명시 경고 ("당사 원장서비스와 연관"). 우리 정책: sync-at-boot 1회 + cron 03:00 1회 = 1일 2회 (재기동 시 +1). **운영 허용 범위 내** (24시간당 평균 2회). re-sync 비활성 시 정적 시드 + DB 캐시 단독 동작.
2. **PreMarket/PostMarket 진입 시점** — KIS 시간외 시간(PreMarket 08:00~08:40 단일가, PostMarket 16:00~18:00) 정확. 본 spec은 ADR 0013 §2-1 "08:00 PreMarket → 09:00 Open" 표기 그대로 LOCK (단순화). 실제 KIS 시간외 가격 흐름과 가벼운 차이는 후속 ADR 0048 후보로 흡수 가능 (현재 0건).
3. **Closed → PreMarket 전이** — 자정~08:00 사이 영업일 시작 직전 Closed. PreMarket 09:00 미만은 동일하지만, 자정 ~ 08:00 구간을 명시적으로 Closed로 둠 (FSM 4 상태 정합).
4. **session=Closed 시 KIS WS subscribe 동작** — KIS WS는 영업일 외 자동 끊김 + reconnect 무한반복 risk. KisWebSocketBridge(PR-9b)가 session=Closed 인지 시 reconnect 보류 + 정적 시드 cron으로 `session:cache:current` TTL 60초 만료 시 재평가. **본 사이클 추가 작업 없음** — 기존 ADR 0048 §3 (41건 한도 + reconnect) 정합으로 자연 처리.
5. **휴장일 정적 시드 권위** — `KrxBusinessDayCalendar` 정적 시드는 2026~2027 약 30건 KRX 공휴일 (설날·추석·어린이날·근로자의 날·임시공휴일). KIS chk-holiday sync 후 우선순위는 **KIS 응답 > 정적 시드** (KIS의 임시휴장 흡수).
6. **DailyCloseFinalizer 16:00 KST** — KRX 정규장 마감 15:30 + 마감 후 정리 30분 여유 → 16:00 시작. PostMarket 단일가(15:40~16:00)도 마감되므로 raw 종가 = 정규장 종가 + 시간외 단일가 weighted (KIS REST FHKST03010100 종가 응답 그대로 사용 — 자체 계산 0).
7. **REJECTED audit retention** — orders 영구 보존 + order_reject_audit 90일 (ADR 0015 §3). 본 V6 마이그레이션은 retention 자동 cleanup job 포함 안 함 — backlog 후속 처리 (post-W3).

---

## §4. T-CA1 — Corporate Action & Adjusted Price (PR-29b)

### §4-1. 핵심 결정 (ADR 0018 정합 — 신규 0건)

| 영역 | 결정 | ADR |
|---|---|---|
| 양 컬럼 | candle.open_raw + open_adjusted (4 OHLC × 2 = 8 컬럼) + adjustment_factor + volume_adjusted | 0018 §2-2 |
| 차트 default | adjusted | 0018 §2-4 |
| DSL 평가 | adjusted **의무** (룩어헤드 차단) | 0018 §2-4 |
| backtest | adjusted **의무** | 0018 §2-4 |
| Sync 주기 | cron 18:30 | 0018 §2-5 |
| Sync source | DART 공시 + KIS ksdinfo 6 endpoint **양쪽** | 0018 §2-5 |
| Retroactive 재계산 | `corp_action.applied.v1` Kafka 이벤트 → CandleAdjustmentJob | 0018 §2-3 |

### §4-2. 도메인 모델 LOCK

```kotlin
// domain/.../instrument/corp_action/
data class CorpAction(
    val id: Long,
    val instrumentCode: String,
    val type: CorpActionType,
    val exDate: LocalDate,
    val ratio: BigDecimal?,        // 액면분할: 50 (1:50), 무상증자: 0.1 (10%)
    val cashDividend: BigDecimal?, // 배당금 (원)
    val mergeRatio: BigDecimal?,
    val source: String,            // "KIS_KSDINFO" · "DART"
    val rawPayload: String?,       // JSON raw (감사용)
    val appliedAt: Instant?,
    val createdAt: Instant,
)

enum class CorpActionType {
    STOCK_SPLIT,         // 액면분할 (KIS rev-split + DART)
    REVERSE_SPLIT,       // 액면병합
    RIGHTS_OFFERING,     // 유상증자 (KIS paidin-capin)
    BONUS_ISSUE,         // 무상증자 (KIS bonus-issue)
    CASH_DIVIDEND,       // 현금배당 (KIS dividend)
    STOCK_DIVIDEND,      // 주식배당
    MERGER,              // 합병 (KIS merger-split)
    SPINOFF,             // 분할
    CAPITAL_DECREASE,    // 자본감소 (KIS cap-dcrs)
    DELISTING_NOTICE     // 상장폐지 공시
}

interface CorpActionRepository {
    fun upsert(action: CorpAction): CorpAction
    fun findByCodeAndExDate(code: String, exDate: LocalDate): List<CorpAction>
    fun findPendingApplied(): List<CorpAction>  // appliedAt = null
    fun markApplied(id: Long, at: Instant)
}

interface DartDisclosureFetcher {
    fun fetchByCode(code: String, from: LocalDate, to: LocalDate): List<DartDisclosure>
}

interface KisKsdInfoFetcher {
    fun fetchDividend(code: String?, from: LocalDate, to: LocalDate): List<CorpAction>
    fun fetchRevSplit(code: String?, from: LocalDate, to: LocalDate): List<CorpAction>
    fun fetchMergerSplit(code: String?, from: LocalDate, to: LocalDate): List<CorpAction>
    fun fetchBonusIssue(code: String?, from: LocalDate, to: LocalDate): List<CorpAction>
    fun fetchPaidinCapin(code: String?, from: LocalDate, to: LocalDate): List<CorpAction>
    fun fetchCapDcrs(code: String?, from: LocalDate, to: LocalDate): List<CorpAction>
}
```

### §4-3. KIS ksdinfo 6 endpoint LOCK (KIS-first raw fetch 의무)

**출처**: `https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/{ksdinfo_*}/{ksdinfo_*}.py` (2026-05-08 fetch)

| 함수 | API_URL | tr_id | 매핑 CorpActionType |
|---|---|---|---|
| 배당 (`ksdinfo_dividend`) | `/uapi/domestic-stock/v1/ksdinfo/dividend` | `HHKDB669102C0` | CASH_DIVIDEND · STOCK_DIVIDEND |
| 액면분할/병합 (`ksdinfo_rev_split`) | `/uapi/domestic-stock/v1/ksdinfo/rev-split` | `HHKDB669105C0` | STOCK_SPLIT · REVERSE_SPLIT |
| 합병/분할 (`ksdinfo_merger_split`) | `/uapi/domestic-stock/v1/ksdinfo/merger-split` | `HHKDB669104C0` | MERGER · SPINOFF |
| 무상증자 (`ksdinfo_bonus_issue`) | `/uapi/domestic-stock/v1/ksdinfo/bonus-issue` | `HHKDB669101C0` | BONUS_ISSUE |
| 유상증자 (`ksdinfo_paidin_capin`) | `/uapi/domestic-stock/v1/ksdinfo/paidin-capin` | `HHKDB669100C0` | RIGHTS_OFFERING |
| 자본감소 (`ksdinfo_cap_dcrs`) | `/uapi/domestic-stock/v1/ksdinfo/cap-dcrs` | `HHKDB669106C0` | CAPITAL_DECREASE |

**우리 정책** (raw 검증 의무):
- 본격 어댑터 작성 전 sub-agent(market-data)는 6 endpoint 각각 raw `params` + `response output` 필드 규격 PR 본문 인용 의무 (헌법 §3 KIS-first 강화). PR-21처럼 별도 raw 검증 보고서 1건 추가 권고.
- params 공통: `cts` (연속조회), `gb1` (시장구분 0=전체/1=KOSPI/2=KOSDAQ), `f_dt`/`t_dt` (조회기간 YYYYMMDD), `sht_cd` (종목코드 — 빈문자열 = 전체)
- 호출 한도: **개별 endpoint별 Rate Limiter 통과 의무** (ADR 0037)

### §4-4. DART OPENDART endpoint LOCK (DART 출처 raw fetch 의무)

**출처**: `https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001` (2026-05-08 fetch)

| 항목 | 값 (raw 1:1) |
|---|---|
| **endpoint (JSON)** | `https://opendart.fss.or.kr/api/list.json` |
| **endpoint (XML)** | `https://opendart.fss.or.kr/api/list.xml` |
| **encoding** | UTF-8 |
| **crtfc_key** (필수) | 40자 API key (`OPENDART_API_KEY` 환경변수 — `.env.example` line 71 이미 존재) |
| **corp_code** (선택) | 8자 고유 회사코드 (DART 별도 발급) |
| **bgn_de**·**end_de** (선택) | YYYYMMDD |
| **pblntf_ty** (선택) | A=정기보고서·B=주요사항보고서·C=발행공시·D=지분공시·E=기타공시·F=외부감사·G=펀드공시·H=자산유동화·I=거래소공시·J=공정위공시 |
| **pblntf_detail_ty** (선택) | 상세 코드 (예: I001=실시간공시·I004=지분공시) |
| **page_no/page_count** | default 1 / 10, 최대 100 |
| **응답 list 필드** | `corp_name`·`corp_code`·`stock_code`·`report_nm`·`rcept_no`·`rcept_dt`·`flr_nm`·`rm` |

**우리 정책**:
- corp action 관련 pblntf_ty = `B` (주요사항보고서) 우선 — 액면분할·합병·증자 결정 공시 본문 fetch
- corp action 관련 pblntf_ty = `D` (지분공시) — 배당 결정 공시
- corp_code → 종목코드(stock_code) 매핑 필요 — `CORPCODE.xml` (3.5MB ZIP) 1회 다운로드 + in-memory cache (external-api-policy.md §4-2 §6시간 캐시)
- 호출 한도: 1일 10,000건 (`external-api-policy.md` §4-1) → 종목당 6시간 캐시 + 배치 1일 1회 sync 충분
- 본문 상세는 `document.xml` endpoint 별도 호출 (rcept_no 기반) — 본 PR-29b 비범위 (post-MVP)

### §4-5. Flyway V7 + V8 LOCK

**전략**: Flyway 1 마이그레이션 = 1 트랜잭션 원칙 정합 — `corp_action` (V7) + `candle ALTER` (V8) 분리. 향후 V7 단독 rollback 가능 (corp_action 테이블만 drop).

```sql
-- batch/src/main/resources/db/migration/V7__corp_action.sql (PR-29b market-data 책임)
CREATE TABLE corp_action (
    id BIGSERIAL PRIMARY KEY,
    instrument_code VARCHAR(8) NOT NULL,
    type VARCHAR(32) NOT NULL,  -- CorpActionType enum 문자열
    ex_date DATE NOT NULL,
    ratio NUMERIC(15, 8),
    cash_dividend NUMERIC(15, 4),
    merge_ratio NUMERIC(15, 8),
    source VARCHAR(32) NOT NULL,  -- 'KIS_KSDINFO' | 'DART'
    raw_payload JSONB,
    applied_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_corp_action_event ON corp_action(instrument_code, type, ex_date, source);
CREATE INDEX idx_corp_action_pending ON corp_action(applied_at) WHERE applied_at IS NULL;
CREATE INDEX idx_corp_action_code_ex ON corp_action(instrument_code, ex_date DESC);

-- batch/src/main/resources/db/migration/V8__candle_adjusted.sql (PR-29b market-data 책임)
ALTER TABLE candle
    ADD COLUMN IF NOT EXISTS open_raw NUMERIC(15, 4),
    ADD COLUMN IF NOT EXISTS high_raw NUMERIC(15, 4),
    ADD COLUMN IF NOT EXISTS low_raw NUMERIC(15, 4),
    ADD COLUMN IF NOT EXISTS close_raw NUMERIC(15, 4),
    ADD COLUMN IF NOT EXISTS open_adjusted NUMERIC(15, 4),
    ADD COLUMN IF NOT EXISTS high_adjusted NUMERIC(15, 4),
    ADD COLUMN IF NOT EXISTS low_adjusted NUMERIC(15, 4),
    ADD COLUMN IF NOT EXISTS close_adjusted NUMERIC(15, 4),
    ADD COLUMN IF NOT EXISTS volume_adjusted BIGINT,
    ADD COLUMN IF NOT EXISTS adjustment_factor NUMERIC(15, 8);

-- 기존 close 컬럼 → close_raw 백필 (1회) + close_adjusted = close_raw (corp_action 미적용 default)
UPDATE candle SET
    open_raw = open_price,
    high_raw = high_price,
    low_raw = low_price,
    close_raw = close_price,
    open_adjusted = open_price,
    high_adjusted = high_price,
    low_adjusted = low_price,
    close_adjusted = close_price,
    volume_adjusted = volume,
    adjustment_factor = 1.0
WHERE close_raw IS NULL;

CREATE INDEX IF NOT EXISTS idx_candle_code_interval_time ON candle(code, interval, time DESC);
```

**LOCK 사유**:
- V6 = PR-29a (market_calendar + order_reject_audit 묶음)
- V7 = PR-29b corp_action (market-data 단독)
- V8 = PR-29b candle ALTER (market-data 단독)
- **PR-29a + PR-29b는 V 번호 정확 분배 LOCK** (충돌 행렬 §3 정합)

### §4-6. CorpActionSyncJob 흐름 LOCK

```
cron 18:30 (KRX 마감 + 정리 시간 후)
  ├─ KIS ksdinfo 6 endpoint 순차 호출 (Rate Limiter 통과)
  │   ├─ 응답 → CorpAction 도메인 매핑 (raw_payload 보존)
  │   └─ corp_action upsert (UNIQUE: code + type + ex_date + source)
  ├─ DART list.json 호출 (pblntf_ty=B+D, bgn_de=어제, end_de=오늘)
  │   ├─ 본문 상세 fetch는 비범위 — list만 corp_action 후보 INSERT
  │   └─ 매칭 (instrument_code via CORPCODE.xml) → 매칭 안 되는 건 skip + WARN 로그
  ├─ pending corp_action (applied_at = null) → Kafka publish `corp_action.applied.v1`
  └─ CandleAdjustmentJob 트리거 (Kafka listener 또는 직접 호출)
```

### §4-7. CandleAdjustmentJob 흐름 LOCK

```
input: corp_action 1건 (예: 005930 stock_split ratio=50 ex_date=2024-04-26)
  1. SELECT candle WHERE code=005930 AND time < 2024-04-26 ORDER BY time
  2. 보정 계수 = 1/50 (액면분할 50:1)
  3. UPDATE candle SET
       open_adjusted = open_raw * adjustment_factor * 1/50,
       high_adjusted = high_raw * adjustment_factor * 1/50,
       low_adjusted = low_raw * adjustment_factor * 1/50,
       close_adjusted = close_raw * adjustment_factor * 1/50,
       volume_adjusted = volume * 50,
       adjustment_factor = adjustment_factor * 1/50
     WHERE code=005930 AND time < 2024-04-26
  4. Redis indicator:cache:005930:* DEL (지표 재계산 트리거)
  5. corp_action.applied_at = now()
  6. Kafka publish corp_action.applied.v1 (consumer 후속 listener 처리)
```

**LOCK 사유**: 보정 계수 누적 (`adjustment_factor *= ...`) — 한 종목 다중 corp_action 발생 시 정합. ex_date 기준 backward 적용.

### §4-8. 함정·트레이드오프

1. **DART corp_code ↔ stock_code 매핑** — DART는 8자리 corp_code, KIS는 6자리 stock_code. CORPCODE.xml ZIP에 매핑 표 존재. 1회 다운로드 + Redis 24h 캐시 + JPA `instrument` 테이블에 `dart_corp_code` 컬럼 add-only 옵션 (instrument 70+ 컬럼은 PR-21 LOCK이라 본 사이클 add 안 함, in-memory map only).
2. **KIS ksdinfo + DART 중복 row** — 같은 corp action을 KIS·DART 양쪽이 발견 → UNIQUE INDEX `(code + type + ex_date + source)`로 source별 분리 보존. 적용 시 KIS 우선 + DART는 raw_payload 비교용 (감사).
3. **adjustment_factor 정확성** — 액면분할 50:1은 1/50 = 0.02. NUMERIC(15, 8) 정밀도 충분. 다단계 분할(예: 5:1 후 10:1) 누적 시 0.02 그대로 정합.
4. **거래량 보정** — 액면분할 시 거래량은 비율만큼 증가 (50:1 분할 → 1주가 50주 됨 → 거래량 ×50). volume_adjusted 컬럼 별도. 단, 합병·증자 시 거래량 보정 정책은 **post-MVP** (현재는 단순 비율 적용 + 합병·증자 retroactive 시 volume_adjusted = volume 그대로).
5. **DSL 평가 의무 adjusted** — T-DSL(W2)의 IndicatorEngine은 `close_adjusted` only 사용. close_raw는 세금·수익률 계산에만 노출. 본 PR-29b는 candle 컬럼 add만 — DSL 적용은 W2 사이클.
6. **retroactive 재계산 시간** — 1 종목 × 일봉 250일 × 1회 UPDATE = ms 단위. 3577 종목 풀 재계산도 분 단위 OK. 본 PR-29b는 1회 corp_action에 대한 1 종목 처리만 — 풀 backfill은 별도 트리거 (post-MVP).
7. **DART 본문 fetch 비범위** — list endpoint만 호출 + 사용 (rcept_no·report_nm으로 type 추정). 본문 `document.xml` 호출은 post-W3.
8. **OPENDART_API_KEY 미발급 시 graceful degrade** — DART 어댑터는 key 없으면 WARN 로그 + skip. KIS ksdinfo 단독 fallback. 사용자가 키 발급 후 SettingsModal 등록 (사용자 메모리 `project_kis_key_management.md` 정합).

---

## §5. T-S1 잔재 — SymbolDetail 차트·호가·dogfooding 검증 (PR-29c)

### §5-1. 작업 범위 (코드 신규 0 권장 — 동작 검증 + minor 정정만)

| 항목 | 작업 | 출처 |
|---|---|---|
| LWC v4 차트 interval 토글 (5/15/30/60/D/W/M) | PR-10a SymbolChart 동작 검증 + 끊김 시 정정 | PR-10a 산출물 (이미 존재) |
| 무한 스크롤 (before cursor) | useSymbolCandles InfiniteQuery 동작 검증 | PR-10a |
| 호가창 STOMP 구독 | mount POST subscribe + unmount sendBeacon (ADR 0048 §5) | PR-10a + PR-10b |
| dogfooding §6.5 한글 검색 회귀 | InstrumentSearchInput 자동완성 7b6b904 hotfix 후 검증 | PR-11b + 7b6b904 |
| delisted 라벨 검증 | WatchlistRow + SymbolDetailPage active=false 흐름 | 9dde856 |
| 일부 종목 노출 검증 (004090 한국석유 등) | scrt_grp_cls_code='ST' 필터 검증 | 7b6b904 |

### §5-2. 본 PR-29c 결함 정정 권장 범위

- **신규 컴포넌트 add 금지** — 룰 4 정합 (R3 일괄 테스트만, R1·R2는 backlog 흡수)
- 기존 컴포넌트 minor 정정만 (1줄~10줄 단위)
- 결함 발견 시 backlog §21 (W1 마감) sub-section에 흡수 + 본 PR에서 처리하지 않고 PR-30 사이클로 이연 가능 (사용자 dogfooding 결과 분류 의무 — backlog §6 정합)

### §5-3. dogfooding 1사이클 검증 시나리오 (릴리즈 게이트)

1. SetupWizard 셋업 완료 (PR-6)
2. Watchlist 5종목 등록 — 한글 검색·코드 검색 양쪽 동작
3. SymbolDetail 진입 (각 종목)
4. 차트 interval 토글 7건 모두 동작 + 무한 스크롤
5. 호가창 STOMP subscribe → unsubscribe 정상
6. delisted 종목(004530) 진입 시 안내 + polling/STOMP 차단 검증
7. session=Closed 시간대 진입 시 차트 stale 배지 + last-day-close T3 fallback (T-MS1 본격 동작 후)

---

## §6. PR-29d — W1 R3 일괄 테스트 (사이클 후반 backend-core 단독)

### §6-1. 작업 범위

| 항목 | 출처 backlog § |
|---|---|
| `SymbolControllerTest` OnboardingGuardFilter mock 보강 | §11 |
| `KisInstrumentMasterAdapterTest` @Disabled 해제 + assertion 정정 | §18 |
| `InstrumentSyncJobTest` @Disabled 해제 + assertion 정정 | §18 |

### §6-2. 룰 4 정합 (코드 먼저, 테스트 마지막 일괄)

- PR-29a·b·c는 R1·R2 (도메인+어댑터+UI) — 테스트 0건 OK
- **PR-29d**가 본 사이클의 R3 — 데이터 흐름 시나리오 일괄
- PR-29d는 PR-29a·b·c 모두 머지 후 진입 (직렬 의존)

---

## §7. CHANGELOG 슬롯 채번룰 (ADR 0050 + 헌법 §4)

### §7-1. anchor 4건 + row 5건 사전 발행

본 PR-29 (architect) 머지 시 CHANGELOG.md head에 다음 4 anchor + 5 row TBD 사전 prepend:

```
| 2026-05-08 | docs(specs): PR-29 W1 마감 spec 4건 + ... | docs/spec/backlog | (architect 본인 row — 본 PR-29에서 채움) |
<!-- pr-29-slot-architect -->
| 2026-05-?? | TBD — PR-29a backend-core: T-MS1 ... | api/batch/consumer/domain | (sub-agent in-place edit) |
<!-- pr-29-slot-backend-core -->
| 2026-05-?? | TBD — PR-29b market-data: T-CA1 ... | api/batch/domain | (sub-agent in-place edit) |
<!-- pr-29-slot-market-data -->
| 2026-05-?? | TBD — PR-29c frontend: T-S1 잔재 검증 ... | frontend | (sub-agent in-place edit) |
<!-- pr-29-slot-frontend -->
| 2026-05-?? | TBD — PR-29d backend-core (R3): SymbolControllerTest mock + batch test 정정 ... | api/batch | (sub-agent in-place edit) |
<!-- pr-29-slot-end -->
```

### §7-2. anchor hunk 분리 효과

PR-21·PR-11·PR-10에서 anchor 분리로 git hunk 충돌 0% 입증. 본 PR-29도 동일 패턴.

---

## §8. 릴리즈 게이트 v3.1.0

### §8-1. 머지 순서 LOCK

1. **PR-29 (architect)** — 본 spec PR (docs only) **선행 머지**
2. **PR-29a + PR-29b 병렬** — 충돌 행렬 §6 (영역 격리 LOCK + V 번호 분배 LOCK)
3. **PR-29c** — frontend 단독, PR-29a·b 머지와 병렬 가능 (frontend 영역 단독)
4. **PR-29d** — R3 일괄 테스트, PR-29a·b·c 머지 **후** 진입 (테스트가 R1·R2 산출물 검증)

### §8-2. 릴리즈 컷 절차

1. PR-29a~d 모두 머지 후 main pull
2. `./gradlew check` GREEN 확인 (4 모듈 + frontend pnpm test/build)
3. `scripts/smoke.sh` 3 시나리오 통과 (백테스트·시그널·AI 분석)
4. dogfooding 1사이클 통과 (§5-3 시나리오 7건)
5. CHANGELOG.md "## [3.1.0] - 2026-05-?? — W1 마감" 섹션 본 사이클 PR 묶음 요약 prepend
6. `git tag -a v3.1.0 -m "v3.1.0 — W1 마감 (T-MS1 + T-CA1 + T-S1 잔재)"`
7. `git push origin v3.1.0`
8. `release/v3.1` 베이스라인 push (ADR 0031 immutable — 기존 ref update 차단, 신규 생성만 허용)

### §8-3. 릴리즈 후 후속

- backlog §21 (W1 마감) ✅ 처리 완료 마킹
- backlog §6.5 dogfooding 5차 결함 ✅ 처리 완료 (delisted UX + scrt_grp_cls_code 필터로 대부분 해소)
- W2 진입 직전 architect 사이클 — T-IND·T-PAT·T-DSL·T-SB 4 트랙 spec 발행

---

## §9. ADR 신규 발행 검토

**결론: 신규 0건 권장**.

| 기존 ADR | 본 사이클 정합 |
|---|---|
| 0013 Market Session FSM | §3 결정 1:1 |
| 0014 Fallback 3-tier | §3-1 정합 |
| 0015 REJECTED Audit | §3-5 V6 1:1 |
| 0016 KIS HTTP 정밀화 | KisChkHolidayAdapter 적용 |
| 0017 Instrument | corp_action ↔ instrument FK 정합 |
| 0018 Corp Action raw+adjusted | §4 결정 1:1 |
| 0037 KIS Rate Limiter | KIS ksdinfo 6 endpoint 적용 |
| 0048 KIS WS 41건 한도 | session=Closed 시 reconnect 정합 |
| 0050 CHANGELOG 슬롯 | §7 적용 |
| 0051 Spring Data default fun 함정 | CorpActionRepository 정합 |

**예외 발생 조건**: PR-29a 또는 PR-29b 진행 중 함정·결정 사유 발견 시 backlog §21 흡수. 본격 ADR 등급(기존 ADR로 해석 불가) 발견 시 별도 hotfix PR로 ADR 0052·0053 후보 발행.

---

## §10. 운영 체크 (헌법 §1·§3·§4·§5)

- ✅ 본 PR `node scripts/task.mjs start --agent architect` 시작 (T-20260508-0241)
- ✅ branch `agent/architect/pr-29-w1-close-spec`
- ✅ author `bot+architect@magicjar.local`
- ✅ KIS-first raw fetch 7개 endpoint 출처 인용 (헌법 §3)
- ✅ DART OPENDART 출처 인용 (헌법 §3 외부 API 정합)
- ✅ CHANGELOG anchor 4건 사전 발행 (헌법 §4 + ADR 0050)
- ✅ rebase 머지 의무 (헌법 §5)
- ✅ 룰 2 단일 책임 — 본 PR docs only (코드 변경 0)

---

## §11. 변경 이력

- v1 (2026-05-08, architect) — 초안. T-MS1·T-CA1·T-S1 잔재 통합 design + Flyway V6·V7·V8 LOCK + KIS-first 7 endpoint raw + DART endpoint raw + 함정 8건.
