# ADR 0075 — T-NEWS 뉴스·공시 실시간 인입 트랙 진입 spec/plan (사용자 결정 8 의제 채택)

- 상태: **Accepted**
- 작성일: 2026-05-15
- 결정자: architect (사용자 자율 결정 인용 — 2026-05-15 "T-NEWS 트랙 진입 ADR 0073 → 진행해")
- 관계: **ADR 0073 supersede** (analysis only → 결정 채택 → 본 ADR 정밀 spec/plan) · ADR 0010 §5(`news`·`news_sentiment` 멀티테넌트 공유 테이블 baseline) · ADR 0018(Corp Action raw — DART 폴링 baseline + DartDisclosureAdapter 운영 중) · ADR 0026 §38(T-AI prompt 컨텍스트 뉴스 명시 — 본 트랙 출력 reuse) · ADR 0044(T-NOTIFY — Slack webhook 본 트랙 KillSwitch 알림 4-2 정합) · ADR 0046·0069(SettingsModal NaverApiSection §F 잔류 — Phase 2 진입 시 .env 단독, UI 0건 1차 LOCK) · **ADR 0057(KIS-only — 시세 단일 출처 LOCK · 뉴스·공시는 외부 보조 OK 정합)** · ADR 0070(T-NOTIFY baseline — Slack P0 dispatcher reuse) · ADR 0072(T-J · T-PERF 마감 — W3 종착 후 W3.5 본 트랙 진입) · 헌법 §3(KIS-first raw fetch 의무 — DART list.json + Naver search/news.json 풀 raw 확보) · 메모리 `feedback_kis_first_policy.md`(외부 뉴스 보조 OK) · `feedback_release_policy_patch_first.md`(v3.7.0=W4 마감 LOCK → 본 트랙 v3.6.7 patch 진입)

---

## 1. 컨텍스트 (왜 본 ADR이 지금 필요한가)

### 1-1. ADR 0073 채택 결정 (사용자 자율 위임 — 2026-05-15)

ADR 0073(Proposed analysis only)에서 architect 권장 8 의제 안 — **사용자 모두 채택**:

> 2026-05-14: "뉴스 스크래핑, 공시 정보 실시간 속보 등 주요 뉴스는 시작해?"
> 2026-05-15: "ADR 0073 T-NEWS 트랙 진입" + "T-NEWS 트랙 진입 진행해"

본 ADR 0075은 ADR 0073 §6 사용자 결정 의제 8건 + §3 옵션 A 채택을 정밀 명세화한 **트랙 진입 spec/plan**. ADR 0073 status: Proposed → Superseded (analysis only · 본 ADR로 위임).

### 1-2. v3.6.7 patch 진입 결정 (사용자 메모리 `feedback_release_policy_patch_first.md` 정합)

릴리즈 정책 LOCK 매트릭스:

| 버전 | 마감 의제 | 잠금 상태 |
|---|---|---|
| **v3.7.0** | **W4 마감 (T-AI + T-MCP 묶음)** | **LOCK — 미러스톤 점유** |
| **v3.8.0** | **W5 마감 (T-LWC + T-LEARN)** | **LOCK — 미러스톤 점유** |
| v3.6.7 patch | **T-NEWS 트랙 진입** | **본 ADR 적용 — minor 인플레이션 회피** |

T-NEWS는 W3.5(W3 마감 v3.6.4 후속 + W4 prereq) 트랙으로, v3.7.0 minor 점유를 회피하고 **v3.6.7 patch 사이클**로 진입.

### 1-3. ADR 0073 사용자 결정 8 의제 — 채택 매트릭스 (LOCK)

ADR 0073 §6 8 의제 + architect 권장 → 사용자 모두 채택:

| # | 의제 | architect 권장 | 사용자 결정 | 본 ADR 적용 § |
|---|---|---|---|---|
| 1 | 트랙 신설 옵션 (A·B·C·D) | A — T-NEWS 별도 트랙 신설 | **A 채택** | §2 트랙 진입 + §8 Phase 분리 |
| 2 | DART 실시간 폴링 빈도 | 1분 | **1분 채택** (현 5분 → 1분 강화) | §2 Flyway·§3 Kafka·§8 Phase 1 |
| 3 | Naver Open API 키 등록 시점 | Phase 2 진입 시 .env | **Phase 2 진입 시 .env 채택** (SettingsModal UI 0건 1차) | §11 한계 + ADR 0069 §F 잔류 |
| 4 | KillSwitch auto-ON 트리거 | Slack 알림만 (자동 토글 0) | **Slack 알림만 채택** (4-2) | §11 비범위 + ADR 0044 Slack reuse |
| 5 | 뉴스 감성 분석 분리 vs T-AI 흡수 | T-AI 흡수 | **T-AI 흡수 채택** (5-1) | §11 비범위 + backlog §62 |
| 6 | 뉴스 panel UI 위치 | 종목 상세 sidebar | **sidebar 채택** (6-1) | §8 Phase 3 frontend + §10 검증 |
| 7 | Kafka 신규 토픽 매트릭스 | 2 토픽 분리 (`disclosure.new.v1` + `news.collected.v1`) | **2 토픽 분리 채택** (7-1) | §3 Kafka 매트릭스 |
| 8 | 진입 시점 | W3 마감 직후 v3.7.0 | **v3.6.7 patch 채택** (사용자 메모리 정합 갱신) | §1-2 정합 + §13 변경 이력 |

**LOCK 의제** = sub-agent 디스패치 시 변경 0 의무. 변경 사유 발생 시 신규 ADR 발행.

### 1-4. fitness gap 검증 (사용자 메모리 `feedback_v3_skepticism_no_regression.md`)

| fitness 축 | 본 ADR 결정 |
|---|---|
| **자본 보호** | DART 1분 폴링 강화 — 정정공시·악재 즉시 인지 (사고 인지 ~1분, 현 5분 → 1분 80% 단축). Slack P0 알림(ADR 0044 reuse) |
| **사용자 비전 정합 (룰 본체 + AI 보조)** | 뉴스·공시 = 정보 수집 보조 (룰 매매 트리거 0 LOCK) + T-AI prompt input prereq (output reuse) |
| **KIS-first 정합 (ADR 0057)** | 시세·캔들·호가·체결 = KIS 단일 LOCK · 뉴스·공시 = 외부 보조 OK (master §B-3 정합) |
| **mock 합성 0** | Naver/DART 호출 실패 시 frontend "데이터 없음" + "API 키 미등록" 한국어 (메모리 `feedback_no_mock_data.md` 정합) |
| **v1/v2 회귀 의심** | 잔재 0 — ADR 0073 baseline 발견 6건 검증 완료 |

---

## 2. 결정 — DB Flyway V32 매트릭스 (의제 7 — 토픽 2 분리 + 멱등성 UQ)

### 2-1. Flyway V32 — `news_article` + `disclosure` 2 테이블 신규

ADR 0010 §5 baseline 정합 (멀티테넌트 공유 — `news`·`news_sentiment` 명시) — 본 ADR에서 **테이블명 정밀화 + Phase 1·2 분리 정합**:

| 테이블 | Phase | 책임 | 행 수 추정 (일별) |
|---|---|---|---|
| **`disclosure`** | Phase 1 (DART) | DART 공시 정밀 row — 정정공시 분류 우선 | 영업일 ~1500건 (전체 시장) |
| **`news_article`** | Phase 2 (Naver) | Naver Open API 뉴스 row — 종목 매핑 + 제목/요약 only | Watchlist 종목당 ~5건/일 = ~150건/일 (30 종목 가정) |

**Flyway V32 본문 LOCK**:

```sql
-- V32__t_news_disclosure_and_news_article.sql

-- 2-1-1. DART 공시 row (Phase 1)
CREATE TABLE IF NOT EXISTS disclosure (
    id            BIGSERIAL PRIMARY KEY,
    corp_code     VARCHAR(8)   NOT NULL,           -- DART corp_code 8자리 (KRX 종목코드 != DART corp_code)
    stock_code    VARCHAR(20),                     -- KRX 종목코드 6자리 (NULL 허용 — 비상장 공시 대응)
    rcept_no      VARCHAR(20)  NOT NULL,           -- DART 접수번호 — 멱등성 KEY
    rcept_dt      VARCHAR(8)   NOT NULL,           -- 접수일자 YYYYMMDD (DART raw 매핑)
    corp_name     VARCHAR(200) NOT NULL,
    report_nm     VARCHAR(500) NOT NULL,           -- 보고서명 (정정공시 "[기재정정]" prefix 분류 의무)
    flr_nm        VARCHAR(100),                    -- 공시제출인명
    rm            VARCHAR(50),                     -- 비고 (정정·연결·첨부 등)
    url           VARCHAR(500) NOT NULL,           -- DART 공시 본문 URL (rcept_no 기반 합성)
    is_correction BOOLEAN      NOT NULL DEFAULT FALSE,  -- report_nm "[기재정정]" or rm contains "정"
    ingested_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_disclosure_rcept_no ON disclosure(rcept_no);
CREATE INDEX IF NOT EXISTS ix_disclosure_stock_code_ingested_at ON disclosure(stock_code, ingested_at DESC);
CREATE INDEX IF NOT EXISTS ix_disclosure_rcept_dt ON disclosure(rcept_dt DESC);

-- 2-1-2. Naver 뉴스 row (Phase 2)
CREATE TABLE IF NOT EXISTS news_article (
    id            BIGSERIAL PRIMARY KEY,
    source        VARCHAR(50)  NOT NULL,           -- "NAVER" | "RSS_HANKYUNG" | "RSS_GOOGLE" (Phase 4 잔류)
    stock_code    VARCHAR(20)  NOT NULL,           -- KRX 종목코드 — 종목 매핑 의무
    title         TEXT         NOT NULL,           -- 뉴스 제목 (Naver <b> 태그 제거 후 영속)
    body_summary  TEXT,                            -- description 패시지 요약 (Naver <b> 태그 제거)
    url           VARCHAR(500) NOT NULL,           -- originallink — 멱등성 hash 기반
    url_hash      VARCHAR(64)  NOT NULL,           -- SHA-256(url) — 멱등성 UQ KEY
    published_at  TIMESTAMPTZ  NOT NULL,           -- pubDate RFC 822 파싱
    ingested_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sentiment     VARCHAR(20)                      -- "POSITIVE" | "NEGATIVE" | "NEUTRAL" | NULL (T-AI 흡수 backlog §62)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_news_article_source_stock_url_hash
    ON news_article(source, stock_code, url_hash);
CREATE INDEX IF NOT EXISTS ix_news_article_stock_code_published_at
    ON news_article(stock_code, published_at DESC);
CREATE INDEX IF NOT EXISTS ix_news_article_published_at ON news_article(published_at DESC);
```

**테이블 명명 정정**: ADR 0010 §5 baseline은 `news`로 명시 — 본 ADR에서 `news_article` 로 정밀화 사유:
- `news` = 도메인 단어 + 다의어 (예약어 risk + 검색 비효율)
- `news_article` = "뉴스 기사" 1:1 매핑 + 추후 `news_sentiment`(ADR 0010 §5 잔류, T-AI 사이클 진입 시 발행) 1:N 외래키 명확

**`disclosure`** 명명은 ADR 0018 baseline (`corp_action_disclosure` 운영 중) 와 별개 — `corp_action_disclosure`는 액면분할·증자·합병 등 corp action 보조이고, 본 `disclosure`는 **모든 DART 공시 row** (정정공시 포함 ~1500건/일). 양립 가능.

### 2-2. 멱등성 UQ — at-least-once 재처리 안전

| 테이블 | UQ KEY | 사유 |
|---|---|---|
| `disclosure` | `ux_disclosure_rcept_no (rcept_no)` | DART API의 rcept_no는 unique 보장(공식 spec) — Kafka at-least-once 재시도 시 PK 충돌 → `ON CONFLICT DO NOTHING` |
| `news_article` | `ux_news_article_source_stock_url_hash (source, stock_code, url_hash)` | 동일 URL이 여러 종목에 매핑될 수 있음(소형주 + 대형주 본문 겹침) — source × stock × URL 3-tuple 멱등성 |

**`url_hash` 계산**: `SHA-256(originallink)` — VARCHAR(64) 매핑. backend-core Phase 2 시 `MessageDigest.getInstance("SHA-256")` 표준 적용.

### 2-3. 한계 — IF NOT EXISTS LOCK + V32 명명

Flyway는 baseline `V1__init.sql` ~ `V31__...` (T-J·T-PERF) 순차. 본 ADR V32 명명 LOCK — 충돌 시 sub-agent 디스패치 단계에서 신규 번호 발행 (V33+).

**rollback 가능성**: `DROP TABLE IF EXISTS disclosure` + `DROP TABLE IF EXISTS news_article` — V32 단독 revert 시 DDL 회복 부담 ↓ (`UNIQUE INDEX`도 자동 drop).

---

## 3. 결정 — Kafka 토픽 매트릭스 (의제 7 — 2 토픽 분리 LOCK)

### 3-1. 신규 2 토픽 LOCK

| 토픽 | publish | listen | 보관 | retention 사유 |
|---|---|---|---|---|
| **`disclosure.new.v1`** (신규) | market-data (DartDisclosureAdapter cron 1분) | (Phase 4 잔류 — T-NOTIFY Slack dispatcher + 사용자 트리거 시) | **7d** | 운영 추적 + 정정공시 P0 알림 (ADR 0044 reuse) |
| **`news.collected.v1`** (신규) | backend-core (NaverNewsAdapter cron) | (Phase 5 잔류 — T-AI prompt input reuse · 본 트랙 1차는 publish only) | **7d** | T-AI prompt input 재처리 + 사용자 검색 추적 |

### 3-2. 사용자 결정 의제 7-1 채택 사유 (2 토픽 분리 vs 단일)

| 분리 | 단일 (`news.event.v1` discriminator) | 채택 |
|---|---|---|
| **T-AI listener 분기 깔끔** (뉴스 prompt vs 공시 prompt 다른 prompt template) | listener 분기 비용 (discriminator switch 의무) | **2 분리 채택** |
| **retention 분리 가능** (정정공시 P0는 30d 후속 확장 가능 · 뉴스는 7d 한계) | 단일 retention 부담 | **2 분리 채택** |
| **publish 모듈 분리** (market-data vs backend-core) | 동일 토픽 두 모듈 publish risk (idempotency 키 분리 의무) | **2 분리 채택** |
| 토픽 개수 +2 | 토픽 +1 | 우선순위 낮음 (확장성 우선) |

### 3-3. 토픽 schema LOCK

**`disclosure.new.v1`** payload:

```json
{
  "eventId": "<uuid>",
  "rceptNo": "20260515000123",
  "corpCode": "00126380",
  "stockCode": "005930",
  "rceptDt": "20260515",
  "corpName": "삼성전자",
  "reportNm": "[기재정정]사업보고서",
  "isCorrection": true,
  "url": "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260515000123",
  "occurredAt": "2026-05-15T09:31:21+09:00",
  "schemaVersion": 1
}
```

**`news.collected.v1`** payload:

```json
{
  "eventId": "<uuid>",
  "source": "NAVER",
  "stockCode": "005930",
  "title": "삼성전자, 메모리 반도체 가격 인상 검토",
  "bodySummary": "삼성전자가 메모리 반도체 가격 인상을 검토 중인 것으로...",
  "url": "https://n.news.naver.com/article/001/0012345678",
  "urlHash": "a3f5b1...64자",
  "publishedAt": "2026-05-15T08:50:00+09:00",
  "occurredAt": "2026-05-15T09:31:21+09:00",
  "schemaVersion": 1
}
```

**`schemaVersion` 의무** — 향후 schema 확장 시(예: `sentiment` 필드 추가, T-AI 사이클) backward-compat 유지.

### 3-3-A. Kafka publish 멱등성

- `disclosure.new.v1` — producer 측에서 `rcept_no` 기반 producer-side dedup (Redis `news:dedup:rcept:{rcept_no}` SET TTL 24h, NX 조건만 publish — §4 정합)
- `news.collected.v1` — `url_hash` 기반 동일 패턴 (`news:dedup:{url_hash}` SET TTL 24h, NX)

DB UQ 와 별개로 Kafka publish 단계에서도 dedup — 이중 보호.

---

## 4. 결정 — Redis 키 매트릭스

### 4-1. dedup + rate limit 2 카테고리

| Redis 키 | TTL | 책임 | 책임 모듈 |
|---|---|---|---|
| `news:dedup:rcept:{rcept_no}` | 24h | DART rcept_no 중복 publish 방지 (Kafka producer SETNX) | market-data Phase 1 |
| `news:dedup:url:{url_hash}` | 24h | Naver originallink 중복 publish 방지 (Kafka producer SETNX) | backend-core Phase 2 |
| `news:ratelimit:naver` | sliding window 60s | Naver Open API 분당 호출 제한 (분당 17건 한계 · 25K/일 ÷ 1440) | backend-core Phase 2 |
| `news:ratelimit:dart` | sliding window 60s | DART 분당 호출 제한 (1분 cron 1회 + 정정공시 polling 백오프 시) | market-data Phase 1 |

### 4-2. rate limit 알고리즘 — Redis ZSET 슬라이딩 윈도

Resilience4j RateLimiter 폐기 (다중 컨테이너 + Naver/DART 한도 글로벌 한계) → Redis 분산 sliding window:

```kotlin
// pseudo (Phase 1·2 sub-agent 구현 시)
fun acquireSlot(key: String, limitPerMinute: Int): Boolean {
    val now = Instant.now().toEpochMilli()
    val windowStart = now - 60_000
    redis.zremrangeByScore(key, "-inf", windowStart.toString())
    val count = redis.zcard(key)
    if (count >= limitPerMinute) return false
    redis.zadd(key, now.toDouble(), "$now:${UUID.randomUUID()}")
    redis.expire(key, 120)
    return true
}
```

**limit 매트릭스**:
- DART: 분당 60건 (20K/일 ÷ 1440 ≈ 14건 — 안전 마진 4배 + 정정공시 spike 흡수)
- Naver: 분당 17건 (25K/일 ÷ 1440 = 17.3건 hard limit) — 0건 마진. Watchlist 종목 우선 polling

---

## 5. 결정 — REST endpoint 매트릭스 (의제 6 — sidebar polling)

### 5-1. 신규 2 endpoint LOCK

| 메서드 | 경로 | 책임 | 모듈 | Phase |
|---|---|---|---|---|
| `GET` | `/api/symbols/{code}/news` | 종목별 뉴스 리스트 — 페이징 + 기간 필터 | api (backend-core Phase 2) | Phase 2 |
| `GET` | `/api/symbols/{code}/disclosures` | 종목별 공시 리스트 — 페이징 + 기간 필터 + 정정공시 강조 | api (market-data Phase 1) | Phase 1 |

### 5-2. 요청 query parameter LOCK

```
GET /api/symbols/{code}/news?from=2026-05-01T00:00:00%2B09:00&to=2026-05-15T23:59:59%2B09:00&page=0&size=20

GET /api/symbols/{code}/disclosures?from=2026-05-01&to=2026-05-15&page=0&size=20&onlyCorrection=false
```

| 파라미터 | 타입 | 기본값 | 검증 |
|---|---|---|---|
| `code` | path · String (종목코드 6자리) | — | `@Pattern(regexp = "[A-Z0-9]{6}")` |
| `from` | query · OffsetDateTime / LocalDate | T-7일 | not null |
| `to` | query · OffsetDateTime / LocalDate | now | from ≤ to + `to - from ≤ 90d` |
| `page` | query · Integer | 0 | `@Min(0)` |
| `size` | query · Integer | 20 | `@Min(1) @Max(100)` |
| `onlyCorrection` | query · Boolean (disclosure 전용) | false | — |

### 5-3. 응답 DTO LOCK

**`SymbolNewsListResponse`** (`/news`):

```kotlin
data class SymbolNewsListResponse(
    val stockCode: String,
    val items: List<NewsItem>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

data class NewsItem(
    val id: Long,
    val source: String,                 // "NAVER" | "RSS_HANKYUNG" (Phase 4) | "RSS_GOOGLE" (Phase 4)
    val title: String,                  // <b> 태그 제거 후 영속됨
    val bodySummary: String?,
    val url: String,
    val publishedAt: OffsetDateTime,    // KST
    val sentiment: String?,             // T-AI 흡수 backlog §62 (NULL 1차)
)
```

**`SymbolDisclosureListResponse`** (`/disclosures`):

```kotlin
data class SymbolDisclosureListResponse(
    val stockCode: String,
    val items: List<DisclosureItem>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

data class DisclosureItem(
    val id: Long,
    val rceptNo: String,
    val corpCode: String,
    val corpName: String,
    val reportNm: String,
    val flrNm: String?,
    val rm: String?,
    val isCorrection: Boolean,           // UI 강조 (배지 표시)
    val url: String,                     // DART 본문 link
    val rceptDt: String,                 // YYYYMMDD raw
    val ingestedAt: OffsetDateTime,
)
```

### 5-4. Swagger 4단 LOCK (메모리 `feedback_swagger_pattern.md` 정합)

@Tag(컨트롤러) + @Operation(메서드) + @Parameter(파라미터) + @Schema(DTO) 4단 + 한국어 description + example 값 + 200/400/404/500 ApiResponse 매트릭스.

### 5-5. 인증 정책

OnboardingGuardFilter 통과 (사용자 1인 baseline · 헌법 §1). 본 endpoint는 read-only — multi-user 진입 시 `@PreAuthorize` 추후 적용.

---

## 6. 결정 — STOMP topic 0 (의제 6 — sidebar polling 패턴)

### 6-1. STOMP 신규 0 LOCK

사용자 결정 의제 6 = sidebar 채택 → polling 패턴 LOCK. STOMP fanout 신규 발행 0.

**이유**:
- 뉴스 빈도 ~5건/일/종목 = 분당 0.003건 — STOMP 실시간 push 가치 ↓ (polling 충분)
- DART 공시 빈도 ~1500건/일 = 분당 1건 (시장 전체 — Watchlist 30 종목 매핑 후 분당 0.02건) — polling 충분
- frontend sidebar polling interval 60s 채택 (의제 6-1 정합)

### 6-2. polling interval 매트릭스

| 영역 | interval | 사유 |
|---|---|---|
| `NewsPanel` (sidebar) | 60s | Naver API 한도 보호 + 분당 0.003건 보충 |
| `DisclosurePanel` (sidebar) | 60s | 정정공시 P0는 Slack 알림 우선 (ADR 0044 reuse), UI는 60s 충분 |

frontend `@tanstack/react-query` `refetchInterval: 60_000`. 종목 상세 페이지 unmount 시 polling 자동 stop (queryClient cleanup).

### 6-3. 확장 가능성 (post-MVP)

정정공시 사용자 시급 인지 의제가 backlog로 등장 시 STOMP `/topic/disclosure/{stockCode}` 신규 발행 후속 (backlog §53 신규).

---

## 7. KIS-first 정합 검증 (헌법 §3 + 사용자 메모리 `feedback_kis_first_policy.md`)

### 7-1. ADR 0057 본문 정합

ADR 0057 §1 — **시세 데이터** 단일 출처 LOCK. 시세·캔들·호가·체결 = KIS LOCK. 뉴스·공시·재무 = 외부 보조 OK 명시 (master §B-3 정합).

본 ADR T-NEWS 트랙 = **뉴스·공시 영역 한정** = ADR 0057 §1 범위 외 = 위반 0.

### 7-2. 외부 데이터 정책

| 영역 | 출처 | KIS-first 정합 |
|---|---|---|
| 시세 (가격·캔들·tick) | KIS REST + KIS WS 단일 | ✅ LOCK |
| 잔고·체결 (position·execution) | KIS REST + KIS WS 단일 | ✅ LOCK |
| 공시 (corp action + 정정·악재) | DART API (운영 중) | ✅ 보조 OK (master §B-3) |
| 뉴스 | Naver Open API + RSS (Phase 4 옵션) | ✅ 보조 OK (master §B-3) |
| 재무 (PBR·PER·ROE 등) | post-MVP — 별도 의제 | (본 ADR 범위 외) |

### 7-3. raw fetch 4건 (헌법 §3 5단계 절차)

본 ADR 발행 단계에서 풀 raw fetch 완료 (사용자 메모리 정합 — 자체 추론 lens 회피):

| 출처 | URL | fetch 결과 |
|---|---|---|
| DART list.json | `https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001` | ✅ raw 확보 (§7-4 인용) |
| DART corpCode.xml | `https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019018` | (운영 중 — DartCorpCodeRepository 코드 인용으로 갈음) |
| Naver search/news.json | `https://developers.naver.com/docs/serviceapi/search/news/news.md` | ✅ raw 확보 (§7-5 인용) |
| RSS 한경·구글 | (Phase 4 잔류 — 본 ADR 범위 외) | Phase 4 진입 시 raw fetch 의무 LOCK |

### 7-4. DART list.json raw 인용 (공식 가이드 2026-05-15 fetch)

**endpoint**: `https://opendart.fss.or.kr/api/list.json`

| Parameter | Type | Required | Description |
|---|---|---|---|
| `crtfc_key` | STRING(40) | **Y** | 발급받은 인증키 (40자리) — **query param** (header 아님) |
| `corp_code` | STRING(8) | N | 공시대상회사의 고유번호 (8자리) |
| `bgn_de` | STRING(8) | N | 시작일 (YYYYMMDD); default: end_de; corp_code 없으면 3개월 제한 |
| `end_de` | STRING(8) | N | 종료일 (YYYYMMDD); default: 당일 |
| `last_reprt_at` | STRING(1) | N | Y or N; default N |
| `pblntf_ty` | STRING(1) | N | A-J 공시유형 |
| `pblntf_detail_ty` | STRING(4) | N | 상세 유형 코드 |
| `corp_cls` | STRING(1) | N | Y(유가)·K(코스닥)·N(코넥스)·E(기타) |
| `sort` | STRING(4) | N | date·crp·rpt; default: date |
| `sort_mth` | STRING(4) | N | asc·desc; default: desc |
| `page_no` | STRING(5) | N | default: 1 |
| `page_count` | STRING(3) | N | 1-100; default: 10, max: **100** |

**Response schema**:

result object:
- `status` (error/info code) · `message` · `page_no` · `page_count` · `total_count` · `total_page`

`list[]` items (모두 STRING):
- `corp_cls`·`corp_name`·`corp_code`·`stock_code`·`report_nm`·`rcept_no`·`flr_nm`·`rcept_dt`·`rm`

**Rate limit**: "일반적으로는 20,000건 이상의 요청에 대하여 이 에러 메시지가 발생" (error 020) → **20K건/일 hard limit**.

**Error codes**: 000 (정상), 010-014, 020-021, 100-101, 800, 900-901.

→ market-data Phase 1 DartDisclosureAdapter 구현 시 본 §7-4 raw 인용 KDoc 의무 LOCK.

### 7-5. Naver search/news.json raw 인용 (공식 가이드 2026-05-15 fetch)

**endpoint**: `https://openapi.naver.com/v1/search/news.json` (JSON) — XML 버전(`/news.xml`)은 본 ADR 범위 외 LOCK.

**HTTP**: HTTPS · GET method only.

**Request headers** (필수):
- `X-Naver-Client-Id: {애플리케이션 등록 시 발급받은 클라이언트 아이디 값}`
- `X-Naver-Client-Secret: {애플리케이션 등록 시 발급받은 클라이언트 시크릿 값}`

**Query parameters**:

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `query` | String | **Y** | 검색어 (UTF-8 encoded) |
| `display` | Integer | N | 1 ~ 100 (default 10) |
| `start` | Integer | N | 1 ~ 1000 (default 1) |
| `sort` | String | N | `sim` 정확도순 (default) · `date` 날짜순 |

**Response JSON** (top-level):
- `lastBuildDate` (dateTime · RFC 822)
- `total` (Integer · 전체 검색 결과 개수)
- `start` (Integer)
- `display` (Integer)
- `items` (Array)

**`items[]` 필드**:
- `title` (String · `<b>` 태그 포함 — 검색어 강조) — backend-core 영속 전 strip 의무
- `originallink` (String · 뉴스 원문 URL)
- `link` (String · 네이버 뉴스 URL — 제공되지 않은 기사면 originallink와 동일)
- `description` (String · 패시지 요약 · `<b>` 태그 포함) — strip 의무
- `pubDate` (dateTime · RFC 822 — `EEE, dd MMM yyyy HH:mm:ss Z` 파싱 의무)

**한도**: "검색 API의 하루 호출 한도는 **25,000회**입니다" → **25K건/일 hard limit**.

**인증 권한**: 403 = 검색 API 권한 없음 → 네이버 개발자 센터 Application > API 설정 탭 "검색" 체크 의무.

**Error codes**:

| 코드 | HTTP | 메시지 |
|---|---|---|
| `SE01` | 400 | Incorrect query request (잘못된 쿼리요청입니다.) |
| `SE02` | 400 | Invalid display value (부적절한 display 값입니다.) |
| `SE03` | 400 | Invalid start value (부적절한 start 값입니다.) |
| `SE04` | 400 | Invalid sort value (부적절한 sort 값입니다.) |
| `SE06` | 400 | Malformed encoding (잘못된 형식의 인코딩입니다.) |
| `SE05` | 404 | Invalid search api (존재하지 않는 검색 api 입니다.) |
| `SE99` | 500 | System Error (시스템 에러) |

→ backend-core Phase 2 NaverNewsAdapter 구현 시 본 §7-5 raw 인용 KDoc 의무 LOCK.

### 7-6. .env 신규 항목 (의제 3 — Phase 2 진입 시 .env LOCK)

`.env.example` 갱신 의무 (Phase 2 sub-agent — backend-core):

```bash
# Naver Open API (T-NEWS Phase 2 — 본 ADR 0075)
NAVER_OPEN_API_CLIENT_ID=          # 네이버 개발자 센터 발급 (https://developers.naver.com)
NAVER_OPEN_API_CLIENT_SECRET=      # 동상

# DART API key (T-NEWS Phase 1 — 운영 중, .env 기존 OPENDART_API_KEY 유지)
# OPENDART_API_KEY=                # 변경 0 (DartDisclosureAdapter 운영 중)
```

**SettingsModal NaverApiSection 1차 UI 0건 LOCK** — ADR 0069 §F 잔류 (T-AI 사이클 진입 시 5 step 표준 절차로 일괄 발행 — ADR 0074 §4 패턴 정합).

---

## 8. Phase 분리 LOCK + sub-agent 디스패치 매트릭스 (의제 1 — 옵션 A 채택)

### 8-1. 4 Phase 직렬 LOCK (병렬 가능 분석 + 의존성)

ADR 0070·0072 Phase 분리 패턴 정합 (사용자 메모리 `feedback_orchestrator_active_parallel.md` add-only 충돌 행렬 검토 결과 — 본 ADR도 **직렬 LOCK** 필수):

| Phase | sub-agent | 영역 | 산출 | 의존 |
|---|---|---|---|---|
| **Phase 1** | **market-data** | batch + api | DartDisclosureAdapter cron 1분 강화 + DisclosureRepository 신규 + Flyway V32 disclosure 테이블 + DisclosureProducer Kafka publish + REST `/api/symbols/{code}/disclosures` Swagger 4단 + 정정공시 분류 (`is_correction`) | — (운영 중 DartDisclosureAdapter baseline + Flyway V31 baseline) |
| **Phase 2** | **backend-core** | api | NaverNewsAdapter 신규(infrastructure) + NewsArticleRepository 신규 + Flyway V32 news_article 테이블 (Phase 1과 동일 V32 — 단일 migration 파일 의무 LOCK) + NewsProducer Kafka publish + REST `/api/symbols/{code}/news` Swagger 4단 + Watchlist 종목 우선 polling cron + .env.example NAVER_OPEN_API_* 추가 | Phase 1 머지 후 Flyway V32 추가 컬럼만 — **단일 V32 파일 분리 의제 §8-3** |
| **Phase 3** | **frontend** | frontend SPA | NewsPanel.tsx + DisclosurePanel.tsx 종목 상세 sidebar 신설 + `useSymbolNews` `useSymbolDisclosures` hook 2건 + polling refetchInterval 60s + 정정공시 배지 강조 + 빈 상태 "데이터 없음" 한국어 | Phase 1·2 endpoint 머지 후만 진행 (의존성) |
| **Phase 4 (옵션 — backlog §53 잔류)** | (옵션 backend-core 후속) | api | RSS 한경·구글 어댑터 신규 — Naver 한도 보완 시 | 별도 backlog (본 ADR 1차 PR scope 외) |

### 8-2. 병렬 가능성 검토 (`feedback_orchestrator_active_parallel.md` add-only 충돌 행렬)

| Phase 페어 | 충돌 분석 | 병렬 가능? |
|---|---|---|
| Phase 1 + Phase 2 | Flyway V32 단일 파일 분리 risk (§8-3) + Kafka 토픽 신규 2 — add-only · domain DTO 분리 가능 | **직렬 권장** (V32 충돌 회피) |
| Phase 1 + Phase 3 | frontend는 disclosure endpoint 머지 후만 가능 | 직렬 의무 |
| Phase 2 + Phase 3 | frontend는 news endpoint 머지 후만 가능 | 직렬 의무 |

**Decision (orchestrator 위임)**: Phase 1 → Phase 2 → Phase 3 **직렬 디스패치 LOCK**.

### 8-3. Flyway V32 단일 파일 분리 의제

Phase 1 (market-data disclosure)과 Phase 2 (backend-core news_article)가 **동일 V32 파일에 두 CREATE TABLE 포함** 시:
- Phase 1 머지 시 news_article CREATE 포함 → backend-core 미구현 영역 DDL 선반영 risk
- Phase 1·2 직렬 분리: V32 = disclosure만 → V33 = news_article만

**채택**: **V32 disclosure + V33 news_article 분리** (단일 Phase 1 PR scope 정합 + revert 단위 분리 가능).

위 §2-1 본문 V32 단일 묶음은 ADR 본문 문서 LOCK — 실제 sub-agent 디스패치 시 **V32 disclosure (Phase 1) + V33 news_article (Phase 2)** 분리 적용. spec markdown에 LOCK 반영.

### 8-4. sub-agent 디스패치 매트릭스

| sub-agent | task slug | branch | 작업 영역 | 산출 파일 추정 |
|---|---|---|---|---|
| **market-data** | `t-news-phase1-dart` | `agent/market-data/t-news-phase1-dart` | Phase 1 DART | ~7 (V32 + Adapter + Repository + Producer + Controller + Service + Mapper) |
| **backend-core** | `t-news-phase2-naver` | `agent/backend-core/t-news-phase2-naver` | Phase 2 Naver | ~10 (V33 + Adapter + Repository + Producer + Controller + Service + Mapper + RateLimiter + .env.example + KafkaTopicConfig) |
| **frontend** | `t-news-phase3-ui` | `agent/frontend/t-news-phase3-ui` | Phase 3 UI | ~8 (NewsPanel + DisclosurePanel + 2 hook + DTO + SymbolDetailPage 정합 + i18n + spec test) |

**총 산출 추정**: ~25 파일.

### 8-5. 산출물 파일 매트릭스 LOCK

**Phase 1 (market-data) — ~7 파일**:

```
batch/src/main/resources/db/migration/V32__t_news_disclosure_table.sql            (신규)
batch/src/main/kotlin/.../infrastructure/dart/DartDisclosureAdapter.kt            (기존 — cron 1분 강화 + is_correction 분류)
api/src/main/kotlin/.../persistence/DisclosureJpaEntity.kt                        (신규)
api/src/main/kotlin/.../persistence/DisclosureRepository.kt                       (신규 — JpaRepository)
api/src/main/kotlin/.../infrastructure/dart/DisclosureKafkaProducer.kt            (신규 — disclosure.new.v1)
api/src/main/kotlin/.../controller/SymbolDisclosureController.kt                  (신규)
api/src/main/kotlin/.../service/SymbolDisclosureService.kt                        (신규)
```

**Phase 2 (backend-core) — ~10 파일**:

```
batch/src/main/resources/db/migration/V33__t_news_article_table.sql               (신규)
api/src/main/kotlin/.../infrastructure/naver/NaverNewsAdapter.kt                  (신규 — HttpClient + 인증 헤더)
api/src/main/kotlin/.../infrastructure/naver/NaverNewsRateLimiter.kt              (신규 — Redis ZSET 슬라이딩)
api/src/main/kotlin/.../persistence/NewsArticleJpaEntity.kt                       (신규)
api/src/main/kotlin/.../persistence/NewsArticleRepository.kt                      (신규)
api/src/main/kotlin/.../infrastructure/naver/NewsKafkaProducer.kt                 (신규 — news.collected.v1)
api/src/main/kotlin/.../controller/SymbolNewsController.kt                        (신규)
api/src/main/kotlin/.../service/SymbolNewsService.kt                              (신규)
api/src/main/kotlin/.../config/KafkaTopicConfig.kt                                (기존 — 2 토픽 신규 추가)
.env.example                                                                       (기존 — NAVER_OPEN_API_* 추가)
```

**Phase 3 (frontend) — ~8 파일**:

```
frontend/src/components/symbol/sidebar/NewsPanel.tsx                              (신규)
frontend/src/components/symbol/sidebar/DisclosurePanel.tsx                        (신규)
frontend/src/api/queries/useSymbolNews.ts                                         (신규 — React Query)
frontend/src/api/queries/useSymbolDisclosures.ts                                  (신규)
frontend/src/api/dto/SymbolNewsDto.ts                                             (신규)
frontend/src/api/dto/SymbolDisclosureDto.ts                                       (신규)
frontend/src/pages/SymbolDetailPage.tsx                                           (기존 — sidebar mount)
frontend/src/i18n/ko.ts                                                            (기존 — 신규 문구 추가)
```

---

## 9. 검증 시나리오 (의제 2·6 정합)

### 9-1. DART 실시간 1분 시나리오 (의제 2-1 채택 검증)

```
[가정] 사용자 Watchlist에 005930 삼성전자 등록 + DART 정정공시 발행 직후
[1] DartDisclosureAdapter cron 1분 실행 → list.json 호출 (corp_code=00126380, bgn_de=오늘)
[2] response.list[] 매트릭스 — 신규 rcept_no=20260515000123 detect (DB 미존재)
[3] 정정공시 분류 — report_nm.startsWith("[기재정정]") = true → is_correction=true
[4] disclosure UPSERT (ON CONFLICT rcept_no DO NOTHING) → 신규 row INSERT
[5] DisclosureKafkaProducer publish disclosure.new.v1 (eventId UUID + isCorrection=true)
[6] T-NOTIFY NotificationDispatcher listen disclosure.new.v1 + isCorrection=true filter → Slack P0 webhook
   (※ ADR 0044 §2-2 baseline + ADR 0073 사용자 결정 4-2 정합 — KillSwitch auto-ON 미수행, Slack 알림만)
[7] frontend SymbolDetailPage 60s polling → /api/symbols/005930/disclosures → DisclosurePanel 정정공시 빨간 배지 표시
[검증] 사고 인지 시간 = cron 1분 + frontend polling 60s = 최대 2분 (현 5분 → 1분 80% 단축)
```

### 9-2. Naver 뉴스 graceful 시나리오 (의제 3 채택 검증)

```
[가정] 사용자 .env에 NAVER_OPEN_API_CLIENT_ID 미등록 + Watchlist 005930 등록
[1] NaverNewsAdapter cron 실행 → 환경변수 미설정 detect → fetch skip + log INFO "Naver API 키 미등록 — 폴링 skip"
[2] news_article 테이블 row INSERT 0
[3] frontend SymbolDetailPage 60s polling → /api/symbols/005930/news → 빈 array
[4] NewsPanel — "데이터 없음" 한국어 + ".env에 NAVER_OPEN_API_CLIENT_ID 등록 시 자동 시작" 안내 표시
[검증] mock 합성 0 정합 (메모리 `feedback_no_mock_data.md`)
[검증] 본체 흐름 영향 0 (master §B-3 "끊겨도 본체 영향 0" 정합)

[가정 2] 사용자 .env에 NAVER 키 등록 후 재부팅
[1] NaverNewsAdapter cron 실행 → fetch 호출 (query="삼성전자", display=20, sort=date)
[2] response.items[] 5건 detect → SHA-256(originallink) 계산 → url_hash 매트릭스
[3] Redis SETNX news:dedup:url:{hash} TTL 24h → 미존재 4건만 INSERT
[4] <b> 태그 strip(title + description) → news_article UPSERT (ON CONFLICT source+stock_code+url_hash DO NOTHING)
[5] NewsKafkaProducer publish news.collected.v1 (4건)
[6] frontend polling → NewsPanel 4건 표시 + 발행시간 정렬
```

### 9-3. sidebar 로드 + 페이징 시나리오 (의제 6-1 채택 검증)

```
[1] dashboard → Watchlist row click → SymbolDetailPage open (code=005930)
[2] sidebar mount → useSymbolNews + useSymbolDisclosures 동시 호출
   GET /api/symbols/005930/news?from=2026-05-08T00:00:00%2B09:00&to=2026-05-15T23:59:59%2B09:00&page=0&size=20
   GET /api/symbols/005930/disclosures?from=2026-05-08&to=2026-05-15&page=0&size=20
[3] backend — 페이징 쿼리 (LIMIT 20 OFFSET 0) + total count 계산
[4] frontend — NewsPanel + DisclosurePanel 별도 카드 표시
[5] 60s 후 refetchInterval auto-trigger → 신규 row 자동 갱신
[6] SymbolDetailPage unmount → queryClient cleanup → polling stop
[검증] STOMP 0 LOCK 정합 (의제 6-1)
[검증] 정정공시 빨간 배지 강조 (isCorrection=true UI 매핑)
```

---

## 10. 결과·영향

### 10-1. 트랙 영향

- **T-NEWS 트랙 신설** (ADR 0073 옵션 A 채택)
- ADR 0073 supersede (analysis only → 결정 채택 + 정밀 spec 본 ADR로 위임)
- W3.5 (W3 마감 v3.6.4 후속 + W4 T-AI prereq) 합류
- v3.6.7 patch 사이클 진입 (v3.7.0 W4 마감 LOCK 정합)

### 10-2. 컨테이너·모듈 영향

| 컨테이너 | 영향 |
|---|---|
| `api` | NaverNewsAdapter + SymbolNewsController + SymbolDisclosureController + NewsKafkaProducer + DisclosureKafkaProducer + KafkaTopicConfig 2 토픽 add + JpaEntity 2 신규 + Repository 2 신규 + Service 2 신규 |
| `batch` | DartDisclosureAdapter cron 5분 → 1분 강화 + Flyway V32·V33 신규 + is_correction 분류 logic |
| `consumer` | 영향 0 (Phase 1·2 publish-only · listen은 Phase 4 잔류) |
| `domain` | 변경 0 (도메인 port 신규 0 — JpaEntity 단순 영속만) |
| `frontend` | NewsPanel + DisclosurePanel + 2 hook + 2 DTO + SymbolDetailPage sidebar mount + i18n |

### 10-3. 운영 영향

| 모니터링 | 임계 |
|---|---|
| DART 1분 cron 실행 빈도 | log INFO + metrics Counter (분당 1건 + 정정공시 spike) |
| DART API rate limit 도달 | log WARN + 분당 60건 hard limit (현재 운영 중 5분 cron 대비 5배 increase + 마진 4배) |
| Naver API 키 미등록 | log INFO (graceful skip · alarm 0) |
| Naver API 호출 빈도 | log INFO + metrics Counter (분당 17건 hard limit) |
| 정정공시 detect (`is_correction=true`) | log WARN + Slack P0 (ADR 0044 reuse) |
| sidebar polling 60s | log DEBUG only |

### 10-4. 트레이드오프

| 검토안 | 채택 | 사유 |
|---|---|---|
| (a) DART 폴링 1분 (사용자 채택) | ✅ | 사고 인지 80% 단축 + DART 20K/일 hard limit 안전 마진 4배 |
| (b) DART 폴링 30초 | 폐기 | rate limit 한계 근접 + 효익 limited (사용자 결정 의제 2-3) |
| (c) Kafka 2 토픽 분리 (사용자 채택) | ✅ | T-AI listener 분기 깔끔 + retention 분리 가능 (의제 7-1) |
| (d) Kafka 단일 토픽 + discriminator | 폐기 | listener 분기 비용 + retention 단일 부담 |
| (e) STOMP 5 topic 신규 (sidebar 실시간 push) | 폐기 | 뉴스 빈도 ↓ + polling 60s 충분 + 의제 6-1 sidebar 정합 |
| (f) NewsPanel `/news` 단독 페이지 | 폐기 | 의제 6-1 sidebar 채택 (사용자 트리거 흐름 자연) |
| (g) Naver SettingsModal Section 5 1차 합류 | 폐기 | T-AI 미진입 + ADR 0074 §4 패턴 정합 → T-AI 사이클로 분리 |
| (h) KillSwitch auto-ON 정정공시 자동 트리거 | 폐기 | 의제 4-2 채택 (false positive risk + 사용자 결정권 보존) |
| (i) Naver 본문 풀 텍스트 영속 | 폐기 | 저작권 risk + DB 부하 — title + bodySummary만 (originallink로 본문 link) |
| (j) `news` 테이블 명명 (ADR 0010 §5 baseline) | 폐기 | `news_article` 1:1 매핑 명확 + 예약어 risk 회피 |
| (k) Flyway V32 단일 묶음 (Phase 1·2 동시) | 폐기 | V32 disclosure (Phase 1) + V33 news_article (Phase 2) 분리 — revert 단위 분리 |

---

## 11. 비범위 + 별도 backlog 흡수

### 11-1. 본 ADR 1차 PR scope 외 (별도 backlog 잔류)

| 의제 | 상태 | backlog 위치 |
|---|---|---|
| **RSS 한경·구글 어댑터 (Phase 4)** | 옵션 잔류 | backlog §53 신규 (T-NEWS Phase 4 — Naver 한도 보완) |
| **뉴스 감성 분석 (sentiment)** | T-AI 흡수 (의제 5-1) | backlog §62 (T-AI 사이클 진입 시) |
| **뉴스 본문 풀 텍스트 영속** | 비범위 LOCK | 저작권 risk — 영구 비범위 |
| **영어 뉴스 (Bloomberg·Reuters)** | post-MVP | 한국 주식 본체 비전 — 영구 비범위 후보 |
| **`/news` 단독 페이지** | 비범위 (의제 6-1 sidebar 채택) | backlog §53 신규 (모바일 진입 시 재논의) |
| **STOMP /topic/news/symbol/{code}** | 비범위 (의제 6-1 polling 채택) | backlog §53 신규 (정정공시 시급 인지 의제 등장 시) |
| **SettingsModal NaverApiSection** | T-AI 사이클 흡수 (ADR 0069 §F 잔류) | ADR 0094+ (가칭) T-AI 사이클 시 |
| **KillSwitch auto-ON 정정공시 트리거** | 비범위 (의제 4-2 Slack 알림만) | backlog §53 신규 (사용자 false positive 학습 후 의제) |
| **외국인·기관 수급 정밀** | post-MVP | master §11 정합 |

### 11-2. AI 호출 0건 grep 검증 의무 (ADR 0070·0071·0072 정합)

본 ADR 영역(T-NEWS 1차 PR — DART + Naver + frontend)에서:

```bash
grep -rn "anthropic\|openai\|claude\|gemini\|sentiment" \
  api/src/main/kotlin/kr/spdaewoon/magicjar/api/infrastructure/dart/ \
  api/src/main/kotlin/kr/spdaewoon/magicjar/api/infrastructure/naver/ \
  api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/SymbolNewsController.kt \
  api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/SymbolDisclosureController.kt \
  batch/src/main/kotlin/kr/spdaewoon/magicjar/batch/infrastructure/dart/ \
  frontend/src/components/symbol/sidebar/
# 기대: 0건 (AI provider import 0 + sentiment 컬럼 NULL only 1차)
```

본 ADR 1차 PR sub-agent (market-data + backend-core + frontend) 모두 LLM provider import 금지. sentiment 컬럼은 schema 정의만이고 INSERT 시 NULL 고정 (T-AI 사이클로 분리).

### 11-3. mock 합성 0건 (메모리 `feedback_no_mock_data.md` 정합)

- backend 0건 또는 API 키 미등록 시 frontend "데이터 없음" 한국어 명시 표시
- NaverNewsAdapter — NAVER_OPEN_API_CLIENT_ID 미설정 시 graceful skip + log INFO (alarm 0)
- DartDisclosureAdapter — OPENDART_API_KEY 미설정 시 graceful skip (운영 중 baseline 정합)
- NewsPanel — 빈 array 시 "최근 7일간 뉴스가 없습니다" + (Naver 키 미등록 시) "환경변수 NAVER_OPEN_API_CLIENT_ID 설정 후 자동 시작" 안내
- DisclosurePanel — 빈 array 시 "최근 7일간 공시가 없습니다"

---

## 12. Rollback 시나리오

### 12-1. Phase 1 머지 후 결함 발견 시

**시나리오**: DART 1분 cron 강화 후 DartDisclosureAdapter rate limit 도달 (20K/일 hard limit 임계 근접)

**Rollback path**:
1. cron 빈도 5분으로 즉시 복귀 (`@Scheduled cron` 단일 라인 revert)
2. Flyway V32 disclosure 테이블 잔존 OK — 미사용 row stop, 운영 영향 0
3. Kafka `disclosure.new.v1` topic publish 0건 — listener 없으므로 retention 7d 자연 만료
4. dogfooding 검증 사이클 재진입 — rate limit 모니터링 강화 후 신규 PR

**Risk 자기 점검**:
- Flyway V32 단독 revert 시 `DROP TABLE disclosure` — 운영 데이터 손실 (1주일 누적)
- ✅ 권장: cron 빈도만 revert + 테이블 보존

### 12-2. Phase 2 머지 후 결함 발견 시

**시나리오**: NaverNewsAdapter rate limit 미정합 (Resilience4j 단일 컨테이너 가정 → 분당 17건 초과 + 403 burst)

**Rollback path**:
1. NaverNewsAdapter cron 비활성 (`@ConditionalOnProperty` toggle false)
2. .env에서 NAVER_OPEN_API_CLIENT_ID 제거 → graceful skip 자동 진입
3. Flyway V33 news_article 테이블 잔존 OK
4. NaverNewsRateLimiter Redis ZSET 분산 sliding window 재설계 후 신규 PR

### 12-3. Phase 3 머지 후 결함 발견 시

**시나리오**: NewsPanel polling 60s 부담 (Watchlist 30 종목 동시 open 시 4 endpoint × 30 = 120 req/min)

**Rollback path**:
1. NewsPanel + DisclosurePanel mount 조건 추가 — "현재 보고 있는 종목만" (SymbolDetailPage active code 단일)
2. polling interval 60s → 5분 (300s) backoff
3. SymbolDetailPage 컴포넌트 단독 revert 가능 (sidebar mount 0)

### 12-4. 부분 rollback 매트릭스

| 산출물 | 단독 revert 가능? |
|---|---|
| DartDisclosureAdapter cron 빈도 강화 | ✅ (1라인 revert) |
| Flyway V32 disclosure | ⚠️ (운영 데이터 손실 risk — cron만 revert 권장) |
| NaverNewsAdapter | ✅ (toggle 0) |
| Flyway V33 news_article | ⚠️ (동상) |
| Kafka `disclosure.new.v1` `news.collected.v1` publish | ✅ (listener 0 — 자연 만료) |
| NewsPanel + DisclosurePanel UI | ✅ (sidebar mount 0) |

---

## 13. 후속 ADR 후보

- **ADR 0094+ (가칭)** — T-AI sentiment 분석 카테고리 (T-AI 사이클 진입 시 — backlog §62 흡수 + ADR 0069 §F SettingsModal NaverApiSection 합류 + news_article.sentiment 컬럼 UPDATE)
- **ADR 0095+ (가칭)** — T-NEWS Phase 4 RSS 어댑터 (한경·구글 RSS) — Naver 한도 보완 의제 등장 시
- **ADR 0096+ (가칭)** — STOMP /topic/news/symbol/{code} fanout (정정공시 사용자 시급 인지 의제 등장 시)
- **ADR 0097+ (가칭)** — `/news` 단독 페이지 (모바일 진입 시 재논의)

---

## 14. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-15 | **ADR 0075 신규 발행** — T-NEWS 트랙 진입 spec/plan (사용자 결정 8 의제 채택 + v3.6.7 patch). ADR 0073 supersede (analysis only → 결정 채택 + 정밀 spec 본 ADR로 위임). 14 § LOCK — §1 컨텍스트 (사용자 결정 8 의제 채택 매트릭스 + v3.6.7 patch 정합) · §2 Flyway V32 disclosure + V33 news_article (멱등성 UQ ux_disclosure_rcept_no + ux_news_article_source_stock_url_hash) · §3 Kafka 2 토픽 disclosure.new.v1 + news.collected.v1 retention 7d + schema 명세 · §4 Redis dedup + rate limit (DART 60건/분 + Naver 17건/분 sliding window ZSET) · §5 REST 2 endpoint Swagger 4단 (/api/symbols/{code}/news + /disclosures, 페이징 + 기간 필터 + onlyCorrection) · §6 STOMP 0 LOCK (polling 60s 채택) · §7 KIS-first 정합 검증 (ADR 0057 시세 단일 + 뉴스 보조 OK) + DART list.json raw 인용 + Naver search/news.json raw 인용 (헌법 §3 5단계 절차 정합) + .env NAVER_OPEN_API_CLIENT_ID·SECRET 추가 · §8 Phase 분리 LOCK 3 PR 직렬 (market-data Phase 1 → backend-core Phase 2 → frontend Phase 3) + Flyway V32·V33 분리 LOCK + sub-agent 디스패치 매트릭스 + ~25 산출 파일 매트릭스 · §9 검증 시나리오 3건 (DART 1분 + Naver graceful + sidebar 로드) · §10 결과·영향 (트랙·컨테이너·운영·트레이드오프) · §11 비범위 + AI 호출 0 grep 검증 의무 + mock 합성 0 · §12 Rollback 시나리오 + 부분 revert 매트릭스 · §13 후속 ADR 후보 · §14 변경 이력. KIS endpoint 호출 0 LOCK (시세 단일 KIS 정합) + AI 호출 0 grep 검증 의무 6 디렉토리 + mock 합성 0 ("데이터 없음" 한국어). ADR 0073 8 의제 결정 인용 (1=A 옵션·2=1분·3=Phase 2 .env·4=Slack 알림만·5=T-AI 흡수·6=sidebar·7=2 토픽·8=v3.6.7 patch). 사용자 메모리 정합 6건(feedback_v3_skepticism_no_regression + feedback_release_policy_patch_first + feedback_3tier_hierarchy + feedback_kis_first_policy + feedback_no_mock_data + feedback_secret_handling). spec 본문: `_workspace/specs/pr-N-t-news-spec.md` (PR 번호 본 사이클 PR 생성 후 확정). CHANGELOG slot 4 anchor (architect + market-data Phase 1 + backend-core Phase 2 + frontend Phase 3 — Phase별 sub-agent 합류 시 in-place edit). (architect, opus 4.7 1M) |
