# PR-11 T-IM1 설계 가이드 (Instrument Master: 종목 마스터·검색·자동완성)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-07
- 대상 PR 시리즈: PR-11a (market-data) · PR-11b (backend-core) — 병렬 가능 (§A 충돌 행렬 참조)
- 진입 prerequisite: PR-5/6/7/8/9/10 모두 MERGED ✅. main HEAD = PR-10 hotfix #204 머지 결과 (`a4333c9` 직후). Watchlist + Symbol Detail + 시세 인입(KIS WS·REST·Yahoo)·Redis cache·STOMP 모두 가동 중.
- **본 PR-11 = W1 잔여 트랙 진입** — 사용자 dogfooding 보류 중 (`project_track1_dogfooding_paused.md`). 종목 검색 자동완성으로 dogfooding 가시성 ↑ + PR-7 옵션 C raw code 함정 자연 해소 + T-IND·T-DSL 선행.
- 정합 SoT:
  - **ADR 0017** Instrument Master 1급 시민 (KOSPI·KOSDAQ·KONEX·ETF·ETN·우선주)
  - **ADR 0039** 종목 마스터 갱신 Cron (KIS 13시각 +5분)
  - **ADR 0037** KIS Rate Limit (cron 호출 시)
  - **ADR 0013** Market Session FSM (영업일 게이트)
  - **ADR 0050** CHANGELOG 슬롯 채번룰 (PR-11이 세 번째 적용 사이클 — PR-10 anchor 효과 입증 후 정합 적용)
  - **ADR 0036** Orchestrator Meta Rules (3단 hierarchy + task 파일 시스템)
  - `docs/architecture/master-architecture.md` v2 §A.1 (Instrument Master 컴포넌트)
  - `docs/architecture/tracks-roadmap.md` §2 T-IM1 + §3 outline
  - `docs/operations/kis-api-reference.md` (KIS endpoint 카탈로그)
  - `_workspace/specs/pr-7-w1-design.md` §7 (옵션 C raw code 저장 + instrument stub `market='UNKNOWN'`) — 본 PR-11에서 stub 보강 정합
  - `_workspace/specs/pr-10-s1-design.md` §7-2 (종목 메타 source 우선순위 — instrument 테이블 1순위) — 본 PR-11이 instrument 테이블 본격 채움으로 후속 정합
  - `_workspace/backlog.md` (미뤄둔 의제 11건 — 본 PR-11 비범위 제외)
- 작업 원칙 (개발 룰북 정합):
  - 룰 2 — 단일 책임 (PR-11a = market-data: KIS 종목 마스터 fetcher + Cron + consumer 영역 캐시 invalidate listener, PR-11b = backend-core: instrument 보강 + InstrumentController 검색 + Redis 검색 인덱스 + frontend WatchlistAddForm 자동완성). **다중 영역(api+frontend) 1 sub-agent 부담 — 룰 2 사유는 §A.4-1 dispatch 명시 권장**
  - 룰 3 — API 빌딩 한 스텝씩 순차. **본 PR-11 = R1+R2 동시** (T-IM1 본격 첫 진입). R3 일괄 테스트는 별도 사이클(W1 통합 테스트와 함께)
  - 룰 4 — 코드 먼저, 테스트 마지막 일괄 (§12 시나리오 골격 — sub-agent smoke 1~2건만 + 사용자 dogfooding 수동 검증)
  - 룰 5 — AI 호출 0 (W1 트랙 — LLM provider import 절대 금지)

---

## §1. T-IM1 한 줄 목표 + DoD

### 1-1. 목표

> **사용자가 Watchlist 등록 화면에서 종목명·종목코드·산업명·테마 일부만 입력해도 자동완성 dropdown이 떠서 정확한 종목을 선택할 수 있다. 등록된 종목은 SymbolDetailPage 헤더에 정확한 한글 종목명("삼성전자")·시장("KOSPI")·업종("전기전자")으로 표시된다. 종목 마스터는 KIS open-trading-api `genfile_*.py`(또는 동등 종목 마스터 endpoint)를 Kotlin port로 매일 13시각 +5분 cron sync(ADR 0039)하여 PostgreSQL `instrument` 테이블에 upsert + Redis 검색 인덱스 갱신 + Kafka `instrument.updated.v1` publish. 신규 상장은 INSERT, 상장폐지는 `delisted_at` UPDATE(DELETE 금지 — 생존자 편향 차단 ADR 0017 §2-2). KIS sync 실패 시 마지막 성공 데이터 유지(graceful) + WARN 로그. 정적 시드 fallback(KOSPI 200 + KOSDAQ 100)으로 KIS 미가용 환경(test profile·dev 첫 부팅) 동작 보장.**

### 1-2. DoD (Definition of Done) — PR-11 전체 머지 후 검증

| # | 검증 항목 | 명령·시나리오 | 기대 결과 |
|---|---|---|---|
| 1 | DB instrument 본격 채움 | `psql -c "SELECT COUNT(*) FROM instrument WHERE market != 'UNKNOWN'"` | KOSPI+KOSDAQ ≥ 2500 row |
| 2 | 종목 검색 REST | `curl 'http://localhost:8080/api/instruments/search?q=삼성전&limit=10'` | 매칭 종목 list (삼성전자·삼성전기·삼성SDI 등) |
| 3 | 종목 코드 prefix 검색 | `curl 'http://localhost:8080/api/instruments/search?q=0059&limit=10'` | 005930(삼성전자) 등 코드 prefix 매칭 |
| 4 | 영업일 cron sync | `MAGICJAR_INSTRUMENT_SYNC_AT_BOOT=true scripts/build-and-deploy.sh` 후 `docker logs magicjar-batch-app \| grep instrument-sync` | `[instrument-sync] cron=... fetched=... inserted=... updated=... delisted=... elapsed=...s` |
| 5 | 휴장일 cron skip | 토요일·일요일 진행 (또는 manual trigger via test) | "휴장일 skip" 로그 + DB 변경 0 |
| 6 | 상장폐지 보존 | sync 결과 row 부재 종목 → `delisted_at=today, active=false` UPDATE | 해당 row DELETE 0건 |
| 7 | 정적 시드 fallback | KIS endpoint 차단 환경 (`MAGICJAR_INSTRUMENT_KIS_DISABLED=true`) 부팅 | KOSPI 200 + KOSDAQ 100 시드 + 정상 검색 응답 |
| 8 | Redis 검색 인덱스 | `redis-cli KEYS "instrument:search:*"` | 한글 prefix sorted set + code prefix set |
| 9 | Kafka publish | `docker exec magicjar-kafka kafka-console-consumer ... --topic instrument.updated.v1` | sync 후 변경 종목 list payload |
| 10 | WatchlistAddForm 자동완성 | UI: `/watchlist` 페이지 → "삼성" 입력 | dropdown에 삼성전자·삼성전기·삼성SDI 표시 + 클릭 시 005930 채워짐 |
| 11 | SymbolDetailPage 종목명 표시 | UI: `/symbol/005930` | 헤더에 "삼성전자 · KOSPI · 전기전자" 정확 표시 (PR-10 graceful "code 그대로" 폐기) |
| 12 | PR-7 stub 자동 보강 | 사용자가 PR-7 옵션 C로 등록한 stub row(market='UNKNOWN') | 첫 sync 후 정확한 market·name·sector로 UPDATE |
| 13 | 잘못된 코드 등록 차단 | UI: WatchlistAddForm에서 자동완성 미사용으로 "999999" 입력 시도 | dropdown 비어있음 + 등록 버튼 disabled (또는 backend 400 응답) — 후속 정합 가능 |
| 14 | 검색 응답 latency | `curl -w '%{time_total}\n' 'http://localhost:8080/api/instruments/search?q=삼성'` | < 50ms (Redis 히트) |
| 15 | LLM provider import 0 | `grep -rE "spring-ai\|anthropic\|openai" {api,consumer,batch}/src/main` | 0건 (룰 5) |
| 16 | KIS-first 정책 검증 | KIS 종목 마스터 fetcher 코드 inline 주석에 KIS open-trading-api 출처 URL/path | 명시 의무 |

### 1-3. 비즈니스 가치

- **사용자 dogfooding 가시성 ↑** — "삼성전자" 한글 검색 → 자동완성 → 1 클릭 등록. PR-7 6자리 숫자 외우기 부담 해소
- **PR-7 옵션 C raw code 함정 자연 해소** — `market='UNKNOWN'` stub이 첫 sync 후 정확 데이터로 UPDATE. SymbolDetailPage에서 종목명 표시 정상화
- **T-IND·T-DSL·T-SC·T-BT 선행 의무** — 80지표·DSL screening·backtest universe는 instrument 테이블 본격 채움 필수
- **상장폐지 보존** — 생존자 편향 차단(ADR 0017 §2-2) → T-BT(백테스트) 정확성 1차 베이스라인
- **오프라인 동작 정합** — 정적 시드 fallback으로 KIS 미가용 환경(test·CI·dev 첫 부팅) 보장

---

## §2. ADR 0017·0039 준수 체크리스트

### 2-1. ADR 0017 (Instrument Master 1급 시민)

| ADR 0017 §위치 | 결정 사항 | PR-11 구현 위치 | 상태 |
|---|---|---|---|
| §2-1 도메인 모델 | `Instrument(code, isin, name, nameEn, market, type, sector, listedAt, delistedAt, active)` + Market 3종(KOSPI·KOSDAQ·KONEX) + Type 6종(STOCK·ETF·ETN·PREFERRED·WARRANT·REIT) | `domain/.../instrument/Instrument.kt` 기존 (PR-5a) — **그대로 사용** + 정적 시드 보강만 (alias 추가는 §3 결정) | ✅ 본 PR-11 그대로 사용 |
| §2-2 영속 — instrument 테이블 V1 baseline | `instrument` V1 + `instrument_alias` V1 (alias_source ∈ "KRX"·"NAVER"·"FNGUIDE") | V1에 `instrument_alias` 부재(검증 필요) — §3 LOCK에서 결정 | **§3에서 결정** |
| §2-2 상장폐지 보존 | DELETE 금지 + `delisted_at` UPDATE | sync logic LOCK — §5에서 명세 | **§5 핵심** |
| §2-3 Sync 메커니즘 | KIS REST 종목 마스터 endpoint(13시각) → upsert(Rate Limiter ADR 0037 통과) → Kafka `instrument.updated.v1` → Redis invalidate | `batch/.../scheduler/InstrumentMasterSyncScheduler.kt` + `consumer/.../infrastructure/kis/KisInstrumentMasterAdapter.kt` (ADR 0017 §2-3) — **PR-11a market-data 핵심** | **§5 LOCK** |
| §2-4 검색 인덱스 | Redis `instrument:search:{prefix}` (한글 prefix tree set+sorted set) + `instrument:list:{market}` TTL 1일 + `instrument:by-code:{code}` TTL 1일 | `api/.../service/InstrumentSearchService.kt` + Redis 키 매트릭스 §6 | **§6 LOCK** |
| §2-5 Profile 분기 | mock=정적 시드, dev=KIS sync + 시드 fallback, prd=KIS sync + KRX 보조 sync | 본 PR-11 1차: **dev=KIS sync + 정적 시드 fallback**. KRX 보조 sync는 비범위(post-MVP). mock profile 폐기(`feedback_no_mock_data.md` 정합 — 사용자 흐름 mock 0) | **§5 결정** |

### 2-2. ADR 0039 (종목 마스터 갱신 Cron)

| ADR 0039 §위치 | 결정 사항 | PR-11 구현 |
|---|---|---|
| §2-1 13 cron 시각 | KIS 갱신 +5분 — `06:05·07:00·07:40·08:00·08:50·09:51·11:00·17:15·17:35·18:00·18:15·18:35·19:00` KST | `batch/.../scheduler/InstrumentMasterSyncScheduler.kt` `@Scheduled(cron, zone="Asia/Seoul")` 13건 | **§5 핵심** |
| §2-2 영업일 게이트 | KRX 캘린더 정합(ADR 0013) — 휴장일 skip | `MarketSessionResolver.isBusinessDay(date)` 호출. 본 PR-11 1차는 PR-9 가동 중 `SimpleMarketSessionResolver`(시간 기반) — 본격 KRX 캘린더는 T-MS1 비범위 | **§5 LOCK + 비범위 명시** |
| §2-2 timezone | KST `zone = "Asia/Seoul"` 명시 의무 | `@Scheduled` 주석에 명시 | ✅ |
| §2-3 배치 진행 흐름 | fetch → diff(이전 vs 현재) → INSERT/UPDATE/DELISTED → Kafka publish → Redis invalidate → STOMP `/topic/instrument.updated` (옵션) | §5 흐름 명세. STOMP fanout은 본 PR-11 비범위(자동완성 사용자 path는 5분 cache 충분 — 후속 정합) | **§5 LOCK** |
| §2-4 실패 처리 | 5xx → exponential backoff 3회(1s/2s/4s) → 다음 cron 대기, EGW00201 → 1초 대기+1회 재시도, 부분 실패 → 성공 row만 처리 + WARN 로그 | KIS adapter 내부 + ADR 0037 RateLimiter | **§5 LOCK** |
| §2-4 컨테이너 재기동 멱등 | 마지막 sync 시각 Redis 영속 → 다음 cron에서 재개 | Redis `instrument:sync:lastAt` String TTL 1일 | **§6 매트릭스 LOCK** |
| §2-5 모니터링 로그 | `[instrument-sync] cron=... fetched=... inserted=... updated=... delisted=... elapsed=...s` | logger 의무 | ✅ |

### 2-3. ADR 0037 (KIS Rate Limit)

- 본 PR-11 cron이 KIS REST 호출 — RateLimiter 통과 의무. 13 cron이 분산이라 burst 부하 0 (ADR 0039 §3 정합)
- KisInstrumentMasterAdapter는 PR-9b 가동 중 `KisRateLimiter` bean 사용 의무

### 2-4. 룰 5 (AI 호출 0)

- `grep -rE "spring-ai|anthropic|openai" {api,consumer,batch}/src/main` → 0건
- frontend AI 라이브러리 import 0건

### 2-5. KIS-first 정책 (`feedback_kis_first_policy.md`)

- 본 PR-11a market-data가 사용할 KIS 종목 마스터 endpoint·tr_id·메시지 형식은 **KIS open-trading-api repo 출처 의무**:
  - 후보 1: KIS open-trading-api `genfile_*.py` (KOSPI/KOSDAQ 종목 마스터 zip 다운로드 + 파싱) — Kotlin port
  - 후보 2: KIS REST `inquire-search-info` 또는 동등 endpoint (TR_ID 미정 — sub-agent KIS-first MCP 검증 의무)
- sub-agent 자체 추론 0건. `mcp__kis-code-assistant__search_domestic_stock_api(query="종목 마스터")` 의무
- architect는 본 spec에서 endpoint 명시 안 함 (KIS-first 정책 — 추론 risk 차단). dispatch §3-2에서 KIS-first 명시 의무

---

## §3. DB 스키마 LOCK ★ (핵심 결정)

### 3-1. 현재 상태 (PR-9 V3 정합)

V1 baseline `instrument` 테이블:
```sql
CREATE TABLE instrument (
    code        VARCHAR(8)    NOT NULL PRIMARY KEY,
    isin        VARCHAR(12),
    name        VARCHAR(80)   NOT NULL,
    name_en     VARCHAR(120),
    market      VARCHAR(10)   NOT NULL,    -- V3에서 'UNKNOWN' 추가
    type        VARCHAR(20)   NOT NULL,    -- V3에서 'UNKNOWN' 추가
    sector      VARCHAR(40),
    listed_at   DATE,
    delisted_at DATE,
    active      BOOLEAN       NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_instrument_market_active ON instrument (market, active);
```

V1 검증: **`instrument_alias` 테이블 부재** (ADR 0017 §2-2에서는 alias_source "KRX"/"NAVER"/"FNGUIDE" 명시되어 있으나 V1 baseline에 미반영).

### 3-2. architect 결정 — **V4 신설 (instrument_alias + 검색 컬럼 + 인덱스)** ★

**채택**: **V4 마이그레이션 신설** (V3 위에 add-only ALTER + CREATE).

**변경 항목**:

| # | 변경 | 사유 |
|---|---|---|
| 1 | `ALTER TABLE instrument ADD COLUMN name_norm VARCHAR(80)` (검색 정규화 — 한글 분음 분리·공백·점 제거) | 한글 prefix 검색 정확도 ↑ (예: "엘지화학"·"LG화학" 매칭) |
| 2 | `ALTER TABLE instrument ADD COLUMN theme VARCHAR(200)` (KIS 테마 콤마 구분 — 옵션) | T-SC screener에서 테마 필터 사용 + 자동완성 부가 표시 |
| 3 | `CREATE INDEX idx_instrument_name_prefix ON instrument USING gin(name gin_trgm_ops)` (PostgreSQL pg_trgm) — fallback 검색용 | Redis 검색 miss 시 DB fallback (한글 부분 일치) |
| 4 | `CREATE INDEX idx_instrument_code_prefix ON instrument (code text_pattern_ops)` | 코드 prefix 검색 |
| 5 | `CREATE TABLE instrument_alias (code VARCHAR(8), alias VARCHAR(80), source VARCHAR(20), PRIMARY KEY(code, alias, source))` (ADR 0017 §2-2 정합) | KRX·NAVER·FNGUIDE 매핑 (1차는 source="KRX"만, NAVER·FNGUIDE는 post-MVP) |
| 6 | `CREATE INDEX idx_instrument_alias_alias ON instrument_alias (alias text_pattern_ops)` | alias prefix 검색 |
| 7 | `CREATE EXTENSION IF NOT EXISTS pg_trgm` (PostgreSQL 16 기본 포함) | gin trigram 인덱스 활성화 |

**스키마 LOCK 형식**:

```sql
-- V4__instrument_master_search.sql
-- ADR 0017 §2-2·§2-4 정합 — 검색 인덱스·alias 매핑·정규화 컬럼

CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE instrument ADD COLUMN IF NOT EXISTS name_norm VARCHAR(80);
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS theme     VARCHAR(200);

CREATE INDEX IF NOT EXISTS idx_instrument_name_trgm
    ON instrument USING gin (name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_instrument_code_prefix
    ON instrument (code text_pattern_ops);

CREATE TABLE IF NOT EXISTS instrument_alias (
    code   VARCHAR(8)  NOT NULL,
    alias  VARCHAR(80) NOT NULL,
    source VARCHAR(20) NOT NULL,
    CONSTRAINT pk_instrument_alias PRIMARY KEY (code, alias, source),
    CONSTRAINT fk_instrument_alias_code FOREIGN KEY (code)
        REFERENCES instrument (code) ON DELETE CASCADE,
    CONSTRAINT chk_instrument_alias_source CHECK (source IN ('KRX', 'NAVER', 'FNGUIDE'))
);
CREATE INDEX IF NOT EXISTS idx_instrument_alias_alias
    ON instrument_alias (alias text_pattern_ops);
```

**대안 검토**:
- (A) V1만 그대로 + alias 보류 → 검색 정확도 낮음 (한글 prefix 검색 patchy)
- (B) V4 신설(채택) → ADR 0017 §2-2 정합 완성 + 한글 검색 1급 지원
- (C) Elasticsearch·OpenSearch 도입 → 인프라 복잡도 폭증, MVP 비범위 (post-MVP 후보)

### 3-3. domain 모델 보강 (필요 시)

`domain/.../instrument/Instrument.kt` 기존 그대로 + 신규 add-only:

```kotlin
// 기존 Instrument 데이터 클래스에 옵션 필드 추가 권장
// (architect 권장: 본 PR-11에서는 추가하지 않고 후속 — 도메인 simplicity 우선)
//   val nameNorm: String? = null,
//   val theme: String? = null,
```

**architect 결정**: domain Instrument는 그대로 유지 (검색 정규화는 service layer 책임). theme은 후속 트랙(T-SC) 진입 시 도메인 보강. **본 PR-11은 DB 컬럼만 추가 + JPA 엔티티만 보강 (domain 변경 0)**.

신규 파일 (add-only):

```kotlin
// domain/.../instrument/InstrumentAlias.kt — add-only
data class InstrumentAlias(
    val code: String,
    val alias: String,
    val source: AliasSource,
)
enum class AliasSource { KRX, NAVER, FNGUIDE }
```

```kotlin
// domain/.../instrument/InstrumentSearchResult.kt — add-only
data class InstrumentSearchResult(
    val code: String,
    val name: String,
    val market: InstrumentMarket,
    val sector: String?,
    val score: Double, // 매칭 score (prefix 우선)
)
```

### 3-4. JPA 보강

`api/.../persistence/InstrumentJpaEntity.kt` — `name_norm`·`theme` 컬럼 추가:

```kotlin
// add-only — 기존 InstrumentJpaEntity에 컬럼 2개 추가
@Column(name = "name_norm", length = 80)
var nameNorm: String? = null,

@Column(name = "theme", length = 200)
var theme: String? = null,
```

`api/.../persistence/InstrumentAliasJpaEntity.kt` 신규 + Repository.

### 3-5. 마이그레이션 시점 정합

V4 적용 시점 = **PR-11 머지 시점 (api 모듈 부팅 시 Flyway 자동)**. 
- Flyway baseline `out_of_order=false` 가정 (현재 정책 그대로)
- 검증: `psql -c "\d instrument"` → name_norm·theme 컬럼 + `\d instrument_alias` → 3 컬럼 + 인덱스 4건

---

## §4. REST API 시그니처 LOCK ★ (자동완성 핵심)

### 4-1. endpoint 매트릭스

| # | Method | path | 용도 | 응답 source | 인증 |
|---|---|---|---|---|---|
| 1 | `GET` | `/api/instruments/search?q={쿼리}&limit={int<=20}&market={KOSPI\|KOSDAQ\|KONEX}` | 자동완성·검색 (한글 prefix·코드 prefix·alias 매칭) | Redis 1순위 + DB pg_trgm fallback | OnboardingGuardFilter (PR-6 정합) |
| 2 | `GET` | `/api/instruments/{code}` | 단일 종목 정보 (instrument 테이블 raw) | Redis `instrument:by-code:{code}` 1순위 + DB | OnboardingGuardFilter |
| 3 | `GET` | `/api/instruments?market={KOSPI\|KOSDAQ\|KONEX}&active=true&limit=...&offset=...` (옵션) | universe 페이지네이션 | DB only (Redis는 cardinality 큼) | OnboardingGuardFilter |
| 4 | `POST` | `/api/instruments/sync` (옵션 — admin trigger) | 수동 sync trigger (cron 외에 즉시 실행) | batch job 호출 | OnboardingGuardFilter + (post-MVP admin 권한) |

**1차 MVP 권장**: endpoint 1·2 의무, endpoint 3은 향후 universe browser UI 위한 보조(본 PR-11 1차 포함 OK), endpoint 4는 옵션(개발 편의).

### 4-2. endpoint 1: `GET /api/instruments/search` 응답 스키마

**요청 예시**: `GET /api/instruments/search?q=삼성전&limit=10`

**응답**:
```json
{
  "query": "삼성전",
  "results": [
    {
      "code": "005930",
      "name": "삼성전자",
      "market": "KOSPI",
      "sector": "전기전자",
      "score": 1.0
    },
    {
      "code": "009150",
      "name": "삼성전기",
      "market": "KOSPI",
      "sector": "전기전자",
      "score": 0.95
    },
    {
      "code": "006400",
      "name": "삼성SDI",
      "market": "KOSPI",
      "sector": "전기전자",
      "score": 0.7
    }
  ],
  "totalMatched": 3,
  "limit": 10
}
```

**파라미터**:
- `q`: 검색어 (한글·숫자·영문 모두 지원). **min 1자**, **max 30자**.
- `limit`: default 10, max 20.
- `market` (옵션): KOSPI·KOSDAQ·KONEX 필터. 미지정 시 전체.

**매칭 정책**:
1. **코드 정확 매칭** (`q == code`) — score 1.0 + 단일 결과 우선
2. **코드 prefix 매칭** (`code LIKE q || '%'`) — score 0.95~1.0 (남은 자릿수에 따라)
3. **종목명 prefix 매칭** (`name LIKE q || '%'`) — score 0.9~1.0
4. **종목명 부분 매칭** (`name LIKE '%' || q || '%'`) — score 0.5~0.9 (위치에 따라)
5. **alias 매칭** (`instrument_alias.alias LIKE q || '%'`) — score 0.7~0.95
6. **삭제·delisted 종목** — 결과에서 제외 (active=true만)

**매칭 흐름**:
```
1. Redis ZRANGEBYLEX instrument:search:prefix:{q[:2]} [q +    (한글 2자 prefix bucket — 한글은 2자가 효율 max)
2. 결과 codes → Redis pipeline GET instrument:by-code:{code} 각각
3. score 정렬 (DESC) + limit
4. Redis miss(또는 결과 0) → DB pg_trgm fallback
   SELECT code, name, market, sector,
          similarity(name, $q) AS score
   FROM instrument
   WHERE active = true
     AND (name ILIKE $q || '%' OR code LIKE $q || '%' OR EXISTS(SELECT 1 FROM instrument_alias WHERE instrument_alias.code = instrument.code AND alias ILIKE $q || '%'))
   ORDER BY score DESC, name ASC
   LIMIT $limit
```

**timeout·실패 처리**:
- Redis timeout 100ms → DB fallback
- DB timeout 1s → HTTP 503 + `{"error":"SEARCH_UNAVAILABLE"}`
- query 정규화 실패 (특수문자 등) → 400 + `{"error":"INVALID_QUERY"}`

### 4-3. endpoint 2: `GET /api/instruments/{code}`

**응답**:
```json
{
  "code": "005930",
  "isin": "KR7005930003",
  "name": "삼성전자",
  "nameEn": "Samsung Electronics Co., Ltd.",
  "market": "KOSPI",
  "type": "STOCK",
  "sector": "전기전자",
  "theme": "반도체,스마트폰,IT",
  "listedAt": "1975-06-11",
  "delistedAt": null,
  "active": true,
  "aliases": [
    {"alias": "삼성전자", "source": "KRX"}
  ]
}
```

**timeout·실패 처리**:
- 미존재 코드 → 404 + `{"error":"INSTRUMENT_NOT_FOUND"}`
- Redis miss → DB SELECT
- DB miss → 404

### 4-4. endpoint 3: `GET /api/instruments?market=KOSPI&active=true&limit=100&offset=0`

**응답**:
```json
{
  "market": "KOSPI",
  "active": true,
  "instruments": [...],
  "total": 950,
  "limit": 100,
  "offset": 0,
  "hasMore": true
}
```

### 4-5. endpoint 4 (옵션): `POST /api/instruments/sync`

**요청**: 빈 body
**응답**: `{"jobId": "instrument-sync-2026-05-07T11:00:00Z", "triggered": true}` 또는 409 (이미 실행 중)
**사유**: 개발·운영 편의 — cron 시각 외에도 즉시 sync 트리거 가능. 본 PR-11 1차 포함 권장 (admin 권한은 post-MVP).

### 4-6. 인증·권한

- 모든 endpoint OnboardingGuardFilter 통과 의무 (PR-6 정합)
- 1차 MVP single-tenant
- post-MVP — JWT/세션 기반 multi-tenant

---

## §5. Cron sync LOCK ★ (ADR 0039 본격 구현)

### 5-1. 위치 결정

| 후보 | 위치 | 결정 |
|---|---|---|
| (A) batch 모듈 `@Scheduled` | `batch/.../scheduler/InstrumentMasterSyncScheduler.kt` | **채택** — ADR 0039 §2-2 명시 정합 |
| (B) consumer 모듈 `@Scheduled` | consumer는 Kafka·KIS WS 책임 | reject — 책임 혼재 |
| (C) api 모듈 `@Scheduled` | api는 사용자 path 책임 | reject — 동일 사유 |

**채택**: batch 모듈. 신규 파일:
- `batch/.../scheduler/InstrumentMasterSyncScheduler.kt` — 13 cron `@Scheduled`
- `batch/.../job/instrument/InstrumentSyncJob.kt` — 1 sync 실행 logic
- `batch/.../job/instrument/InstrumentSyncResult.kt` — sync 결과 dto
- KIS adapter는 consumer 모듈에 (PR-9b 패턴 정합)

### 5-2. cron 시각 (ADR 0039 §2-1 정합 — 13 cron)

```kotlin
@Scheduled(cron = "0 5 6 * * MON-FRI",  zone = "Asia/Seoul") // 06:05
@Scheduled(cron = "0 0 7 * * MON-FRI",  zone = "Asia/Seoul") // 07:00
@Scheduled(cron = "0 40 7 * * MON-FRI", zone = "Asia/Seoul") // 07:40
@Scheduled(cron = "0 0 8 * * MON-FRI",  zone = "Asia/Seoul") // 08:00
@Scheduled(cron = "0 50 8 * * MON-FRI", zone = "Asia/Seoul") // 08:50
@Scheduled(cron = "0 51 9 * * MON-FRI", zone = "Asia/Seoul") // 09:51
@Scheduled(cron = "0 0 11 * * MON-FRI", zone = "Asia/Seoul") // 11:00
@Scheduled(cron = "0 15 17 * * MON-FRI", zone = "Asia/Seoul") // 17:15
@Scheduled(cron = "0 35 17 * * MON-FRI", zone = "Asia/Seoul") // 17:35
@Scheduled(cron = "0 0 18 * * MON-FRI",  zone = "Asia/Seoul") // 18:00
@Scheduled(cron = "0 15 18 * * MON-FRI", zone = "Asia/Seoul") // 18:15
@Scheduled(cron = "0 35 18 * * MON-FRI", zone = "Asia/Seoul") // 18:35
@Scheduled(cron = "0 0 19 * * MON-FRI",  zone = "Asia/Seoul") // 19:00
```

`MON-FRI`는 cron 자체 게이트 + 추가로 KRX 캘린더 게이트(휴일 skip):

```kotlin
fun runIfBusinessDay(cronTag: String) {
    val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
    if (!marketSessionResolver.isBusinessDay(today)) {
        log.info("[instrument-sync] cron={} skip — 휴장일 ({})", cronTag, today)
        return
    }
    instrumentSyncJob.run(cronTag)
}
```

**1차 한계**: PR-9 가동 중 `SimpleMarketSessionResolver`는 시간 기반 stub — 영업일 정밀도 낮음. **본 PR-11 1차는 그 stub 그대로 사용** + T-MS1 진입 시 KRX 캘린더 본격 동기화 (비범위 §9).

### 5-3. sync logic 흐름 (ADR 0039 §2-3 정합)

```
1. boot guard
   ├─ Redis instrument:sync:running == "true" 이면 abort + WARN 로그 (중복 실행 방지)
   └─ Redis SET instrument:sync:running true EX 600 (10분 lock)

2. KIS fetch
   ├─ KisInstrumentMasterAdapter.fetchAll(): List<KisInstrumentRaw>
   │  ├─ KIS open-trading-api 출처 endpoint 호출 (sub-agent KIS-first MCP 검증 의무)
   │  ├─ KisRateLimiter 통과 (ADR 0037)
   │  ├─ 5xx → exponential backoff 3회 (1s/2s/4s) → 그래도 실패 시 abort
   │  └─ EGW00201 → 1초 대기 + 1회 재시도
   └─ raw 파싱 → List<Instrument> (도메인 모델)

3. diff
   ├─ 기존 active=true 종목 List (DB SELECT)
   ├─ KIS 응답 set vs 기존 set
   ├─ NEW = KIS only → INSERT (active=true, listed_at=오늘 또는 KIS 응답)
   ├─ UPDATED = 양쪽 + 필드 변경 → UPDATE (name·sector·theme 등)
   └─ DELISTED = 기존 only → UPDATE delisted_at=오늘, active=false (DELETE 금지)

4. 영속
   ├─ instrument upsert (NEW + UPDATED)
   ├─ instrument_alias upsert (KRX source — 종목명 그대로 alias로 1건 default)
   └─ TX commit

5. Kafka publish
   ├─ instrument.updated.v1 — payload {changes: [{code, action: "INSERT"|"UPDATE"|"DELIST"}, ...]}
   └─ partition key = market (병렬 처리 가능)

6. Redis 갱신
   ├─ instrument:sync:lastAt SET = 현재 시각
   ├─ instrument:list:{market} DEL (다음 read 시 lazy 재구성)
   ├─ instrument:by-code:{code} DEL (변경된 코드만)
   └─ instrument:search:* — 신규·delisted만 영향 → architect 결정: 매 sync 후 전체 invalidate (간단 + Redis 재구성 빠름)

7. lock 해제
   └─ Redis DEL instrument:sync:running

8. 로그
   └─ [instrument-sync] cron=... fetched=... inserted=... updated=... delisted=... elapsed=...s
```

### 5-4. 정적 시드 fallback ★

KIS 미가용 환경(test profile·CI·dev 첫 부팅 + KIS 키 미발급) 동작 보장:

**위치**: `domain/.../instrument/InstrumentSeed.kt` (또는 batch resources `instrument-seed.json` 정적 파일)

**시드 범위**: KOSPI 200 + KOSDAQ 100 + 주요 ETF 20 (총 ~320 종목)

**시드 source**:
- KOSPI 200 — KRX 공식 KOSPI 200 구성 종목 (사용자 검증 — KRX 공시)
- KOSDAQ 100 — KRX KOSDAQ 100 구성 종목
- 주요 ETF — KODEX 200·KODEX 인버스·TIGER 차이나전기차·등 (사용자 친화적 20 ETF)

**시드 파일 형식** (architect 권장 — JSON):
```json
[
  {"code":"005930","name":"삼성전자","market":"KOSPI","type":"STOCK","sector":"전기전자","listedAt":"1975-06-11"},
  ...
]
```

**적용 시점**:
1. **부팅 시 `instrument` row count == 0**이면 자동 시드 INSERT (`@PostConstruct` ApplicationRunner)
2. **KIS sync 실패** 시 시드 그대로 유지 (마지막 성공 데이터 유지 — ADR 0039 §3 정합)
3. **dev profile 첫 부팅**: KIS 키 미발급 상태라도 시드만으로 자동완성 동작

**시드 발행 책임**: PR-11a market-data가 시드 JSON 파일 발행 + 시드 로더 작성. **architect 권장**: 시드는 `batch/src/main/resources/instrument-seed.json` 위치 (batch 모듈 책임 — sync 정합)

### 5-5. KIS endpoint LOCK (KIS-first 정책 — sub-agent 책임)

**architect는 본 spec에서 정확한 endpoint·tr_id 명시 안 함** (KIS-first 정책 — 추론 risk 차단).

dispatch §3-2 LOCK으로 PR-11a market-data sub-agent에 위임:

> KIS 종목 마스터 fetcher의 endpoint·메시지 형식·필드 인덱스는 **KIS open-trading-api repo 출처 의무**. 후보:
> - `mcp__kis-code-assistant__search_domestic_stock_api(query="종목 마스터 조회")` MCP 호출
> - KIS open-trading-api repo `examples_llm/domestic-stock/.../genfile_kospi.py·genfile_kosdaq.py` 검토
> - 자체 추론 0건. 코드 인라인 주석에 출처 URL/path 명시 의무

**역사적 정합**: KIS는 종목 마스터 zip 파일 다운로드 방식이 traditional. zip 파싱 fixed-width record format. python `genfile_*.py`가 reference.

### 5-6. 부팅 시 즉시 sync 옵션 (개발 편의)

`MAGICJAR_INSTRUMENT_SYNC_AT_BOOT=true` env var → ApplicationRunner가 부팅 직후 1회 sync (cron 다음 시각 대기 안 함).

dev profile에서 default true 권장 (사용자 dogfooding 즉시 자동완성 동작). prd profile은 default false (cron만).

### 5-7. 동시 실행 방지

Redis `instrument:sync:running` lock + `SET ... NX EX 600` 패턴. 부팅 즉시 sync + cron 동시 실행 방지.

---

## §6. 검색 인덱스 LOCK ★ (Redis)

### 6-1. Redis 키 매트릭스

| 키 | TTL | 작성자 | 읽기 | 형식 | 용도 |
|---|---|---|---|---|---|
| `instrument:by-code:{code}` | 1일 (ADR 0017 §2-4) | 부팅·sync 시 작성 | `/api/instruments/{code}` 단일 조회 | JSON | 단일 종목 메타 cache |
| `instrument:list:{market}` | 1일 | sync 시 lazy 재구성 | `/api/instruments?market=...` universe 조회 | JSON List<code> | universe 캐시 |
| `instrument:search:prefix:{prefix}` | 1일 | sync 시 lazy 재구성 | `/api/instruments/search?q=...` 자동완성 | sorted set (member=code, score=match score) | 한글·코드 prefix 검색 |
| `instrument:sync:running` | 600s (10분 lock) | sync 시작 시 SET NX | sync 시작 가드 | String "true" | 동시 실행 방지 |
| `instrument:sync:lastAt` | 1일 | sync 끝 SET | 부팅 시 stale 검증 + sync 진행 상태 표시 | ISO8601 String | 마지막 sync 시각 |

### 6-2. prefix bucket 정책 ★

**문제**: `instrument:search:prefix:{prefix}` 키 cardinality 폭증 risk (한글은 2,350자 base + 조합 폭증). 모든 prefix 키를 별도 만들면 Redis 메모리 폭증.

**해결**: **2자 prefix bucket**:
- 1글자만 — bucket 키 = 그 1글자 (예: "삼" → `instrument:search:prefix:삼`)
- 2글자 이상 — bucket 키 = 첫 2글자 (예: "삼성전" → `instrument:search:prefix:삼성`)
- bucket 안에서 application layer 추가 필터링 (`name LIKE q || '%'` in-memory)

**효과**: bucket 키 cardinality ≈ KOSPI+KOSDAQ ~3000 종목의 첫 2자 unique ~200~500 (한글 분포). 메모리 부담 적당.

### 6-3. 부팅 시 인덱스 재구성

```kotlin
@PostConstruct
fun rebuildIndexAtBoot() {
    if (redisTemplate.opsForValue().get("instrument:sync:lastAt") != null) {
        log.info("[instrument-index] 인덱스 부팅 시 재구성 skip — Redis 기존 데이터 사용")
        return
    }
    val instruments = instrumentJpaRepository.findAllByActiveTrue()
    rebuildSearchIndex(instruments)
    log.info("[instrument-index] 부팅 시 재구성 완료 — {} 건", instruments.size)
}
```

**race condition 회피**: Redis 부팅 직후 빈 상태 + `instrument:sync:lastAt` 부재 → 부팅 1회 재구성.

### 6-4. invalidate 정책

매 sync 후:
1. `instrument:list:*` DEL (모든 market)
2. `instrument:search:prefix:*` DEL (전체 invalidate — 다음 read 시 lazy 재구성)
3. 변경된 codes → `instrument:by-code:{code}` DEL

**lazy 재구성**: 다음 첫 검색 요청 시 application layer가 DB에서 fetch + Redis 다시 채움. 첫 요청 latency 약간 ↑ (~100ms) 후속은 Redis 히트.

**대안 검토**:
- (A) 매 sync 후 즉시 인덱스 재구성 — sync 시간 ↑ (300 종목 INSERT + Redis 키 ~3000건 SET 30s)
- (B) lazy 재구성 (채택) — sync 빠름 + 첫 검색 사용자가 latency 약간 ↑

### 6-5. 한글 정규화

검색 입력 정규화 (application layer):
- 공백·점 제거: "엘 지 화학" → "엘지화학"
- 영문 lowercase: "Samsung" → "samsung"
- 한글 분음: 1차 미적용 (post-MVP — 특히 typo 케이스 "ㅅㅏㅁㅅㅓㅇ" 같은)

architect 결정: 1차는 단순 (공백·점 제거 + lowercase). 분음·typo는 post-MVP.

---

## §7. frontend 자동완성 통합 LOCK ★

### 7-1. 컴포넌트 신규 — `InstrumentSearchInput`

위치: `frontend/src/components/InstrumentSearchInput.tsx`

API:
```typescript
interface InstrumentSearchInputProps {
  /** 선택된 종목코드 — controlled */
  value: string
  /** 종목 선택 callback */
  onSelect: (instrument: { code: string; name: string; market: string; sector?: string }) => void
  /** input 비활성화 (중복 등록 처리 등) */
  disabled?: boolean
  placeholder?: string
}

/** 한글·코드 입력 → debounce 200ms → /api/instruments/search → dropdown */
export function InstrumentSearchInput(props: InstrumentSearchInputProps): JSX.Element
```

### 7-2. 동작 흐름

1. 사용자 입력 (예: "삼성전")
2. debounce 200ms
3. `axios.get('/api/instruments/search?q=삼성전&limit=10')`
4. dropdown 표시:
   ```
   ┌─────────────────────────┐
   │ 005930 삼성전자 KOSPI    │
   │ 009150 삼성전기 KOSPI    │
   │ 006400 삼성SDI  KOSPI    │
   └─────────────────────────┘
   ```
5. 사용자 클릭 또는 Enter → `onSelect({code: '005930', name: '삼성전자', market: 'KOSPI', sector: '전기전자'})`
6. input 값은 종목명·코드 동시 표시 (예: "005930 삼성전자")

### 7-3. WatchlistAddForm 정정 (옵션 C → 자동완성 전환) ★

`frontend/src/pages/Watchlist/WatchlistAddForm.tsx` 정정:

**before** (PR-7 옵션 C):
```typescript
// 6자리 숫자 정규식 + 직접 입력
const TICKER_REGEX = /^\d{6}$/
<input ... maxLength={6} ... />
```

**after** (PR-11 자동완성 전환):
```typescript
const [selected, setSelected] = useState<{code, name, market} | null>(null)

<InstrumentSearchInput
  value={selected?.code ?? ''}
  onSelect={(inst) => setSelected(inst)}
  disabled={isPending}
  placeholder="종목명 또는 코드 검색 (예: 삼성전자, 005930)"
/>
<button disabled={isPending || !selected}
  onClick={() => addWatchlist({code: selected.code})}>
  {selected ? `${selected.code} ${selected.name} 등록` : '등록'}
</button>
```

**graceful fallback** (자동완성 실패 시):
- `/api/instruments/search` 5xx → input은 PR-7 옵션 C 동작 fallback (6자리 직접 입력 + clientside regex)
- WatchlistAddForm 내부 state — `searchAvailable: boolean`. axios fail 누적 3회 시 raw input 모드 전환

**스펙 LOCK**:
- 자동완성 미사용 시 등록 버튼 disabled (선택된 instrument 없으면 — 원칙적으로)
- raw input fallback 모드일 때만 6자리 숫자 그대로 등록 가능
- 등록 후 form 초기화 + selected null

### 7-4. SymbolDetailPage 헤더 정합

`frontend/src/pages/Symbol/SymbolHeader.tsx` (PR-10a 발행)에서 종목명·시장·업종 표시:

**현재 (PR-10 graceful)**:
- `inquire-price` 응답 + instrument stub `name=code` → "005930 · KOSPI · " (산업 부재)

**PR-11 정합 후**:
- backend SymbolMetaService가 instrument 테이블 우선 read (PR-10 spec §7-2 source 우선순위 1순위 정합)
- 응답: `{name: "삼성전자", market: "KOSPI", industry: "전기전자"}` 정확
- frontend는 변경 0 — backend 응답이 정확해지면 자동 정합

**PR-10b SymbolMetaService 정정 의무**: instrument 테이블 read 로직 추가. **architect 결정**: 본 PR-11에서 함께 정정 (backend-core 책임).

### 7-5. axios 클라이언트

`frontend/src/api/instrument.ts` 신규:
```typescript
export interface InstrumentSearchResultDto {
  code: string
  name: string
  market: 'KOSPI' | 'KOSDAQ' | 'KONEX'
  sector?: string
  score: number
}

export async function searchInstruments(q: string, limit = 10, market?: string): Promise<InstrumentSearchResultDto[]> {
  const params = new URLSearchParams({ q, limit: String(limit) })
  if (market) params.set('market', market)
  const res = await axios.get(`/api/instruments/search?${params}`)
  return res.data.results
}
```

`frontend/src/api/hooks/useInstrumentSearch.ts` 신규:
```typescript
import { useQuery } from '@tanstack/react-query'
import { searchInstruments } from '../instrument'

export function useInstrumentSearch(q: string, enabled = true) {
  return useQuery({
    queryKey: ['instrumentSearch', q],
    queryFn: () => searchInstruments(q, 10),
    enabled: enabled && q.length >= 1,
    staleTime: 60_000, // 1분 cache (검색 결과는 sync cron 시점에만 변경)
  })
}
```

---

## §8. 상장폐지 보존 LOCK ★ (생존자 편향 차단)

### 8-1. ADR 0017 §2-2 정합

DELETE 절대 금지. sync 시점:

```kotlin
val existingActiveCodes = instrumentJpaRepository.findByActiveTrue().map { it.code }.toSet()
val kisCodes = kisFetched.map { it.code }.toSet()

val delistedCodes = existingActiveCodes - kisCodes
delistedCodes.forEach { code ->
    instrumentJpaRepository.markDelisted(code, today) // delisted_at = today, active = false
}
```

### 8-2. 검색 결과 필터링

`/api/instruments/search` — `active = true` 종목만 반환 (delisted 제외)

### 8-3. 직접 조회 정상 (백테스트 정확성)

`/api/instruments/{code}` — delisted 종목도 그대로 반환 (백테스트는 과거 시점 universe에 포함시킬 의무)

### 8-4. T-BT(백테스트) 진입 시 universe 시점 정합

T-BT 트랙은 backtest 시점에 active 종목 universe 추출 시 `delisted_at` 기준 분기 의무 (post-MVP — 본 PR-11은 정합 베이스만):
```sql
SELECT * FROM instrument WHERE listed_at <= $backtestDate AND (delisted_at IS NULL OR delisted_at > $backtestDate);
```

본 PR-11 비범위 — `delisted_at` 컬럼만 정확 기록 의무.

---

## §9. 비범위 (post-MVP / 후속 트랙)

본 PR-11에 명시적으로 포함하지 않는 항목:

| 항목 | 다음 PR/트랙 | 사유 |
|---|---|---|
| 해외 종목 (NYSE·NASDAQ·HKSE) | post-MVP | 사용자 비전 = 한국 주식 자동매매 본체 |
| 암호화폐·선물·옵션 | post-MVP | KIS 한국주식 1차 |
| 뉴스·재무 통합 (`instrument` × DART × 네이버 뉴스) | post-MVP | 별도 데이터 파이프라인 트랙 |
| KRX 보조 sync (KIS + KRX 충돌 시 KRX 우선 ADR 0017 §2-5) | post-MVP | KIS 데이터 정확도 충분 가정. 충돌 발생 관찰 후 |
| KRX 영업일 캘린더 정밀 동기화 | T-MS1 | 본 PR-11 1차는 SimpleMarketSessionResolver 시간 기반 stub 그대로 |
| Elasticsearch 검색 인프라 | post-MVP | Redis sorted set + pg_trgm로 1차 충분 |
| 한글 분음·typo 검색 ("ㅅㅏㅁㅅㅓㅇ" → "삼성") | post-MVP | 1차는 prefix 매칭만 |
| `instrument:list:{market}` STOMP fanout | post-MVP | 사용자 자동완성 path는 5분 cache로 충분 |
| `name_norm` 컬럼 활용 (영문/한글 cross 검색) | post-MVP | 1차는 raw `name` ILIKE 매칭만 |
| 사용자 정의 universe (custom universe) | T-SC | screener에서 본격 |
| theme·sector 풀 매트릭스 (KIS 80+ 테마) | T-SC | screener 진입 시 |
| 외국인·기관 보유 비율 | post-MVP | 사용자 자율 트리거 |
| `instrument` row count 200K (해외+우선주 풀) | post-MVP | 1차는 KOSPI+KOSDAQ ~3000 |
| admin 권한 (sync trigger) | post-MVP | 본 PR-11 1차는 OnboardingGuardFilter만 |
| 실시간 instrument.updated.v1 STOMP fanout (사용자 페이지 즉시 갱신) | post-MVP | 1차는 `useQuery staleTime: 60_000`로 충분 |

---

## §10. 트레이드오프 표

| 결정 | 대안 | 채택 사유 |
|---|---|---|
| V4 신설 (alias·name_norm·theme·인덱스) | V1 그대로 + alias 보류 | ADR 0017 §2-2 alias 정합 + 한글 prefix 검색 정확도 ↑ |
| Redis sorted set + pg_trgm fallback | Elasticsearch | 인프라 추가 비용 vs 효과 — 1차 ~3000 종목은 단순 인덱스 충분. Elasticsearch는 post-MVP |
| 정적 시드 fallback (KOSPI 200 + KOSDAQ 100) | KIS 의존만 | 오프라인 동작 정합(ADR 0001 1순위) + dev 첫 부팅·CI 환경 동작 |
| batch 모듈 `@Scheduled` cron | api 또는 consumer | ADR 0039 §2-2 명시 — batch 책임 분리 |
| 13 cron 시각 (KIS 갱신 +5분) | 1 cron 일별만 | ADR 0039 §2-1 LOCK — KIS 갱신 13시각 정합 |
| 매 sync 후 search 인덱스 lazy 재구성 | 즉시 재구성 | sync 시간 짧음 + 첫 검색 사용자가 latency 약간 ↑ 감수 |
| frontend InstrumentSearchInput 컴포넌트 | WatchlistAddForm 안에 inline 구현 | 재사용 (Strategy Builder·Screener·SymbolDetail에서도 재사용 예정) |
| WatchlistAddForm 자동완성 전환 + raw input fallback | 자동완성 only | 자동완성 endpoint 5xx 시 사용자 등록 차단 회피 — graceful |
| SymbolMetaService instrument 테이블 우선 (PR-10b 정정 의무) | 별도 InstrumentService | 책임 일관성 — symbol 메타 source 라우팅 한 곳 |
| KIS endpoint 본 spec 미명시 | spec에 hardcode | KIS-first 정책 — sub-agent MCP 검증 의무 + 추론 risk 차단 |
| domain Instrument 변경 0 + JPA 컬럼만 추가 | domain 보강 | simplicity 우선 — name_norm·theme은 service layer 책임 |
| dispatch 모드 = 병렬 (frontend 영역도 backend-core가 함께) | 3 sub-agent (frontend 분리) | frontend 변경 범위 ≤ 3 파일 (WatchlistAddForm 정정 + InstrumentSearchInput 신규 + axios) — frontend sub-agent 1번 더 호출 비용 > 가치. backend-core 1 PR로 묶음 |

---

## §11. 발견된 함정 + 위험

### §11-1. KIS 종목 마스터 endpoint 추론 risk ★

KIS-first 정책 정합. sub-agent가 자체 추론 시:
- 잘못된 endpoint → 부팅 시 sync 실패 → instrument 테이블 빈 상태 + dogfooding 차단
- zip 파싱 fixed-width 인덱스 오류 → 종목명 깨짐 (예: "삼성전자" → "엑\xff성전자")

**처리**: dispatch §3-2에 KIS open-trading-api `genfile_*.py` 출처 의무 + `kis-code-assistant` MCP `search_domestic_stock_api(query="종목 마스터")` 호출 의무 명시. 코드 인라인 주석에 출처 URL/path 명시 의무.

### §11-2. PostgreSQL pg_trgm extension 부재 risk

**현재 상태**: `docker/docker-compose.infra.yml` 사용 중인 PostgreSQL 16 official image는 contrib 모듈 포함 (pg_trgm 자동 설치 가능).

**검증**: PR-11 진행 중 sub-agent가 V4 적용 시 `CREATE EXTENSION pg_trgm` 첫 실행. 실패 시 docker-compose volume 정합 (postgres user 권한 문제 가능성 — superuser 필요).

**처리**: dispatch §3-2-A에 pg_trgm 검증 의무 + Flyway V4에 `IF NOT EXISTS` 명시. 실패 시 fallback (ILIKE만 사용 — gin 인덱스 0 → 검색 latency ↑ 100ms 정도, 사용자 영향 ≤).

### §11-3. instrument_alias가 비어있을 때 검색 fallback

1차 sync는 alias_source='KRX'만 채움 (종목명 그대로 1건). NAVER·FNGUIDE는 비어있음. 사용자가 "엘지" 검색 시 "LG화학" 매칭 안 될 가능성.

**처리**: `name` 컬럼 ILIKE 매칭이 1차 매칭 — alias 부재여도 정확. 단 영문/한글 cross 검색은 후속 정합 (post-MVP).

### §11-4. PR-7 옵션 C stub UPDATE race ★

PR-7에서 사용자가 등록한 stub row(market='UNKNOWN', name=code) — 첫 sync에서 정확 데이터로 UPDATE.

**race**: sync 진행 중 사용자가 새 종목 등록 → stub INSERT → sync에서 KIS에 없으면 delisted 처리?

**처리**: sync logic이 `existingActiveCodes` 추출 시점이 sync 시작 시각 — 이후 INSERT된 stub은 제외. 다음 sync에서 자연 정합 (KIS에 있으면 UPDATE, 없으면 delisted UPDATE). race window 5~10분 OK.

### §11-5. ADR 0050 슬롯 채번룰 세 번째 적용 — anchor 효과 검증

PR-9·PR-10 적용 결과:
- PR-9 hunk 충돌 1건 발생
- PR-10 anchor 시도 → CHANGELOG conflict 0건 (입증)

본 PR-11 anchor 그대로 사용 (PR-10 패턴 정합). 추가 변경 0.

### §11-6. 자동완성 사용자 path debounce 200ms — 한글 IME composition 함정

한글 입력 시 IME composition (조합) 진행 중에 axios 호출 → 미완 글자 검색 → 빈 결과:
- 사용자: "ㅅ" 입력 (조합 진행 중) → debounce 200ms 후 axios 호출 → "ㅅ" 검색 → 결과 0
- 의도: "삼" 완성 후 검색

**처리**: `compositionstart`·`compositionend` 이벤트 핸들러로 IME composition 중에는 검색 차단:
```typescript
const [composing, setComposing] = useState(false)
<input
  onCompositionStart={() => setComposing(true)}
  onCompositionEnd={() => setComposing(false)}
  onChange={(e) => {
    if (!composing) setQuery(e.target.value)
  }}
/>
```

dispatch §C에 LOCK 명시.

### §11-7. universe 페이지네이션 endpoint(3) 분량 부담

`/api/instruments?market=KOSPI&active=true&limit=100&offset=0` — 3000 row 조회. limit=100 default 권장.

**처리**: spec §4-4 정합 + 1차 MVP 본 endpoint는 universe browser UI 위한 보조 (실제 frontend 사용 0건이라 1차 endpoint만 발행 + UI는 후속 트랙 — backlog 후보).

### §11-8. SymbolMetaService instrument 테이블 우선 read (PR-10 정정)

PR-10b가 SymbolMetaService를 발행 (KIS inquire-price 1순위). 본 PR-11에서 instrument 테이블 1순위로 정정 의무.

**처리**: `service/SymbolMetaService.kt` 정정:
```kotlin
fun getSymbol(code: String): SymbolDto {
    val instrument = instrumentJpaRepository.findById(code).orElse(null)
    val priceData = redisQuoteCache.get(code) ?: kisRestPriceAdapter.fetchPrice(code)
    val metrics = redisMetaCache.get(code) ?: kisRestPriceMetricsAdapter.fetchPriceMetrics(code)
    return SymbolDto(
        code = code,
        name = instrument?.name ?: code,        // PR-11 정정: instrument 1순위
        market = instrument?.market ?: "UNKNOWN",
        industry = instrument?.sector ?: metrics.industry,
        ...
    )
}
```

dispatch §3-2-G에 LOCK 명시.

### §11-9. 영업일 정밀도 부족 (SimpleMarketSessionResolver 시간 stub)

PR-9 가동 중 `SimpleMarketSessionResolver`는 시간 기반 (월~금 09:00~15:30 OPEN). 휴장일 (어린이날·부처님오신날) 정밀 분기 부재.

**처리**: 본 PR-11 1차는 그 stub 그대로 사용 + 휴장일 cron이 그냥 sync 시도 → KIS 응답 0건 → log WARN. 부정확한 sync는 다음 영업일 cron에서 자연 정합. T-MS1 진입 시 정밀 정합.

### §11-10. KIS 종목 마스터 응답 latency

KIS zip 다운로드 + 파싱 latency: 평균 5~15초 (3000 종목 × 100 byte ≈ 300KB zip + 압축 해제 + 파싱).

**처리**: `@Scheduled` 메서드는 async 권장 (TaskExecutor) — 동기 블로킹 시 다른 cron 영향 risk. 단 13 cron이 모두 다른 분이라 충돌 risk ≤ 0. 1차는 동기 OK.

### §11-11. Flyway V4 PostgreSQL pg_trgm extension 권한

`CREATE EXTENSION pg_trgm`는 superuser 권한 필요. dev docker compose는 `magicjar` user가 superuser?

**검증**: `docker exec magicjar-postgres psql -U magicjar -c "CREATE EXTENSION IF NOT EXISTS pg_trgm"` 검증. 실패 시 docker-compose `init.sql`에 사전 실행 추가 (sub-agent 책임).

**처리**: dispatch §3-2-A에 검증 의무 명시 + fallback (gin 인덱스 미생성 — pg_trgm CREATE 실패 시 ILIKE만 사용).

### §11-12. JPA 엔티티 secondary constructor protected — InstrumentJpaEntity 정합

PR-7 발행 InstrumentJpaEntity의 protected constructor는 market="UNKNOWN"·type="UNKNOWN" 기본값. 본 PR-11에서 컬럼 추가 시 protected constructor도 정합 의무.

**처리**: dispatch §3-2-G에 명시.

### §11-13. 사용자 dogfooding 보류 중 — 본 PR-11 머지 후 재개 트리거

사용자 메모리 `project_track1_dogfooding_paused.md` — dogfooding 보류 중. 본 PR-11 머지 = T-IM1 완료 = 자동완성 가시성 ↑ → dogfooding 재개 가능 트리거.

**처리**: backlog §6에 dogfooding 결과 흡수 항목 갱신 의무. backlog §7 W2 진입 가이드 정정 (W1 잔여 → W1 종료 트리거).

---

## §12. 테스트 시나리오 골격 (룰 4 — R3 일괄)

본 PR-11도 룰 4 정합 — sub-agent smoke 1~2건만 + 사용자 dogfooding 수동 검증.

### 12-1. backend smoke (PR-11a market-data 부담 — 1건)

1. `batch/src/test/kotlin/.../InstrumentSyncJobTest.kt` — KisInstrumentMasterAdapter mock + diff logic + delisted 처리 검증

### 12-2. backend smoke (PR-11b backend-core 부담 — 2건)

1. `api/src/test/kotlin/.../InstrumentSearchServiceTest.kt` — Redis mock + DB pg_trgm fallback 시나리오
2. `api/src/test/kotlin/.../InstrumentControllerTest.kt` — `GET /api/instruments/search?q=삼성` mock service 응답

### 12-3. frontend smoke (PR-11b 부담 — 1건 — 함께)

1. `frontend/src/components/__tests__/InstrumentSearchInput.test.tsx` — vitest mock useInstrumentSearch → dropdown 렌더링 + onSelect 호출 검증

### 12-4. 통합 시나리오 (사용자 dogfooding 수동 검증)

DoD 1~16 모두 — `scripts/build-and-deploy.sh` 후 사용자가 본인 KIS 키로 본인 watchlist 등록·자동완성 검증.

### 12-5. R3 일괄 테스트는 별도 묶음

W1 통합 테스트 사이클(별도 PR — `chore(test): W1 R3 일괄 테스트`)에서 본격. 본 PR-11은 smoke + dogfooding만.

---

## §13. 사용자 검증 안내 (PR-11 머지 후)

### 13-1. 진입 흐름

1. PR-11 spec PR + PR-11a + PR-11b 모두 머지 후 main 위
2. `cd /Users/ak-song/magic_ch1`
3. `MAGICJAR_INSTRUMENT_SYNC_AT_BOOT=true scripts/build-and-deploy.sh` (부팅 시 즉시 sync)
4. http://localhost:3000 진입
5. `/watchlist` 진입 → 자동완성 input에 "삼성" 입력 → dropdown 확인
6. 클릭 → 등록 → row 표시 확인
7. row 클릭 → SymbolDetailPage 헤더 "삼성전자 · KOSPI · 전기전자" 확인 (PR-10 graceful 폐기)
8. 잘못된 코드 직접 입력 ("999999") → dropdown 비어있음 검증

### 13-2. 사용자 검증 체크리스트

| # | 검증 항목 | 통과 기준 |
|---|---|---|
| 1 | 자동완성 dropdown 표시 | "삼성" 입력 → 200ms 후 dropdown (3+ 종목) |
| 2 | 한글 IME composition 처리 | "ㅅ" 조합 중에는 검색 안 함, "삼" 완성 후 검색 |
| 3 | 코드 prefix 검색 | "0059" 입력 → 005930·005935·... 표시 |
| 4 | KOSPI·KOSDAQ 양쪽 표시 | "삼성" → KOSPI 종목 + KOSDAQ 종목 모두 표시 (가능한 경우) |
| 5 | 클릭 → 등록 | 1 클릭 → row 추가 + 자동완성 input 초기화 |
| 6 | SymbolDetailPage 종목명 | row 클릭 → 정확한 한글 종목명 + 시장 + 업종 표시 |
| 7 | PR-7 stub UPDATE | PR-7로 등록한 기존 watchlist 종목도 정확한 이름으로 표시 |
| 8 | 자동완성 latency | 입력 → dropdown 표시 < 300ms (debounce 200ms + Redis ~50ms) |
| 9 | sync cron 실행 | `docker logs magicjar-batch-app \| grep instrument-sync` → 13 cron 실행 log |
| 10 | KIS 미가용 fallback | KIS 키 무효화 → 시드 200+100 종목으로 자동완성 동작 |
| 11 | LLM 호출 0 | 화면에 AI 코멘트 0건 |

### 13-3. 다음 사이클 진입 가이드 (사용자 결정 — backlog §7 정합)

본 PR-11 머지 + dogfooding 통과 후:
- **W1 잔여 트랙**: T-MS1·T-CA1 (선택)
- **W2 진입 후보**: T-IND·T-PAT (병렬 가능)
- **architect 권장**: backlog §7 갱신 후 사용자 결정

---

## §14. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | PR-11 T-IM1 설계 가이드 v1 발행 (architect, opus 4.7 1M). DB V4 신설 LOCK(instrument_alias·name_norm·theme·gin_trgm) + REST 4 endpoint LOCK(자동완성 1순위·단일 조회·universe·sync trigger) + Cron 13 LOCK(ADR 0039 정합 + KRX 영업일 게이트 stub) + Redis 검색 인덱스 LOCK(2자 prefix bucket + sorted set + 1일 TTL) + frontend InstrumentSearchInput 신규 + WatchlistAddForm 자동완성 전환(graceful raw input fallback) + 정적 시드 KOSPI 200·KOSDAQ 100 fallback + 상장폐지 보존 LOCK(DELETE 금지) + KIS-first 정책 강조(sub-agent endpoint·인덱스 자체 추론 0건). dispatch 모드 = 병렬 권장(market-data + backend-core). PR-10 anchor 효과 입증으로 ADR 0050 hunk 분리 anchor 정합 적용 + CHANGELOG 슬롯 3건. |
