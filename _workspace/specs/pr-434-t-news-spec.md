# PR #434 — T-NEWS 트랙 진입 spec/plan (ADR 0075)

> **상태**: spec/plan only — 본 PR은 ADR 0075 + spec markdown 발행 단독. sub-agent 디스패치 (market-data Phase 1 + backend-core Phase 2 + frontend Phase 3)는 본 PR 머지 후 별도 사이클.
> **사이클**: v3.6.7 patch
> **사용자 자율 결정**: 2026-05-15 "T-NEWS 트랙 진입 ADR 0073 → 진행해" + 8 의제 architect 권장 안 채택
> **연관**: ADR 0075 §1~§14 본문 정합

---

## 1. 변경 개요

### 1-1. 본 PR scope

| 영역 | 산출 | 상세 |
|---|---|---|
| ADR | `_workspace/arch/adr/0075-t-news-track-spec.md` (신규) | T-NEWS 트랙 진입 정밀 spec/plan |
| ADR 인덱스 | `_workspace/arch/adr/README.md` | count 70 → 71 (0075 row 추가) · §5 시장 데이터 카테고리 13건 → 14건 · §12 후속 예정 0073 supersede 표기 |
| master 정합 | `docs/architecture/master-architecture.md` | §10 ADR 인덱스 0075 row + 헤더 카운트 갱신 + §A.4 News & Disclosure 본문 보강 (ADR 0075 정합) + §13 변경 이력 row |
| tracks-roadmap 정합 | `docs/architecture/tracks-roadmap.md` | §2 T-NEWS row Accepted 격상 (W3.5 → v3.6.7 patch) + §3 outline 신규 + §8 변경 이력 |
| backlog 정합 | `_workspace/backlog.md` | §52 ADR 0075 흡수 마킹 + RSS Phase 4 잔류 표시 (backlog §53 신규 분리 후보) |
| CHANGELOG slot | `CHANGELOG.md` | 4 anchor — architect (본 PR 머지 row) + market-data Phase 1 + backend-core Phase 2 + frontend Phase 3 (slot 사전 prepend, Phase별 sub-agent 합류 시 in-place edit) |
| spec 본문 | `_workspace/specs/pr-434-t-news-spec.md` (본 파일) | sub-agent 디스패치 정밀 명세 |

**본 PR 코드 변경 0 LOCK** — ADR + markdown 정합만. sub-agent 코드 디스패치는 별도 사이클.

### 1-2. 비범위 (본 PR scope 외 — sub-agent 사이클 분리)

- Phase 1 market-data 코드 (DartDisclosureAdapter cron 강화 + Flyway V32 + DisclosureKafkaProducer + SymbolDisclosureController)
- Phase 2 backend-core 코드 (NaverNewsAdapter + Flyway V33 + NewsKafkaProducer + SymbolNewsController + Watchlist 종목 우선 polling cron + .env.example 갱신)
- Phase 3 frontend 코드 (NewsPanel + DisclosurePanel + 2 hook + SymbolDetailPage sidebar mount)
- Phase 4 옵션 RSS 어댑터 (backlog §53 신규 잔류)

---

## 2. sub-agent 디스패치 정밀 명세

### 2-1. Phase 1 — market-data (DART 1분 강화 + disclosure 영속 + Kafka publish + REST `/disclosures`)

**branch**: `agent/market-data/t-news-phase1-dart`
**author**: `bot+market-data@magicjar.local`
**task slug**: `t-news-phase1-dart`

**산출 파일 매트릭스 (~7)**:

| 파일 | 책임 | 본 PR 머지 후 sub-agent 작업 |
|---|---|---|
| `batch/src/main/resources/db/migration/V32__t_news_disclosure_table.sql` | DDL — `disclosure` 테이블 + 멱등성 UQ `ux_disclosure_rcept_no` + 인덱스 2건 | 신규 — ADR 0075 §2-1 본문 V32 schema 그대로 적용 |
| `batch/src/main/kotlin/.../infrastructure/dart/DartDisclosureAdapter.kt` | DART list.json 폴링 — cron 5분 → **1분** 강화 + `is_correction` 분류(report_nm `[기재정정]` prefix or rm contains `정`) | 기존 reuse + 1분 cron 갱신 + `is_correction` field 추가 + KDoc raw 인용 의무 (ADR 0075 §7-4) |
| `api/src/main/kotlin/.../persistence/DisclosureJpaEntity.kt` | JPA Entity 매핑 `disclosure` 테이블 | 신규 |
| `api/src/main/kotlin/.../persistence/DisclosureRepository.kt` | Spring Data Repository — `findByStockCodeAndIngestedAtBetween` + `findByStockCodeAndIsCorrectionTrueAndIngestedAtBetween` + native UPSERT (ON CONFLICT) | 신규 — ADR 0051 default fun 함정 회피 LOCK |
| `api/src/main/kotlin/.../infrastructure/dart/DisclosureKafkaProducer.kt` | Kafka `disclosure.new.v1` publish + Redis `news:dedup:rcept:{rcept_no}` SETNX 24h | 신규 |
| `api/src/main/kotlin/.../controller/SymbolDisclosureController.kt` | `GET /api/symbols/{code}/disclosures` Swagger 4단 + 페이징 + 기간 필터 + `onlyCorrection` query | 신규 |
| `api/src/main/kotlin/.../service/SymbolDisclosureService.kt` | use case — stockCode 검증 + Page<DisclosureItem> 조회 + DTO 매핑 | 신규 |

**기존 파일 갱신**:

- `api/src/main/kotlin/.../config/KafkaTopicConfig.kt` — `disclosure.new.v1` 토픽 신규 add (`@Bean` NewTopic, retention 7d, partition 3)

**핵심 결정 LOCK**:

1. **cron 빈도 1분** (ADR 0075 §1-3 의제 2-1 정합) — `@Scheduled(cron = "0 */1 * * * *", zone = "Asia/Seoul")`
2. **정정공시 분류** — `is_correction = report_nm.startsWith("[기재정정]") || rm?.contains("정") == true`
3. **멱등성 UPSERT** — `ON CONFLICT (rcept_no) DO NOTHING` (DB UQ + Redis SETNX 2중)
4. **DART raw 인용 의무** — KDoc 상단 `https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001` URL + ADR 0075 §7-4 parameter 표 인용
5. **Kafka publish schema** — ADR 0075 §3-3 schema LOCK (eventId UUID + rceptNo + stockCode + corpCode + isCorrection + url + schemaVersion=1)
6. **rate limit** — Redis ZSET `news:ratelimit:dart` 분당 60건 (20K/일 hard limit 안전 마진 4배)

**단위 테스트 매트릭스 ~10건**:

- DartDisclosureAdapter cron 1분 trigger 검증
- 정정공시 분류 — `[기재정정]사업보고서` → `is_correction=true`
- 정정공시 분류 — `rm="정"` 단독 → `is_correction=true`
- 정상공시 — `report_nm="사업보고서"` + `rm=null` → `is_correction=false`
- 멱등성 — 동일 rcept_no 2회 → UNIQUE INDEX 충돌 → 1건만 INSERT
- DisclosureKafkaProducer publish — schema field 검증 (eventId 형식 + occurredAt KST)
- Redis dedup — 동일 rcept_no 24h 내 재호출 → SETNX 0 → publish skip
- rate limit — 분당 61건 호출 시 61번째 skip + log WARN
- SymbolDisclosureService — 페이징 + 기간 필터 + onlyCorrection true 검증
- SymbolDisclosureController — 400 (잘못된 date range), 404 (미존재 종목), 200 (정상)

---

### 2-2. Phase 2 — backend-core (Naver 뉴스 어댑터 + news_article 영속 + Kafka publish + REST `/news`)

**branch**: `agent/backend-core/t-news-phase2-naver`
**author**: `bot+backend-core@magicjar.local`
**task slug**: `t-news-phase2-naver`
**의존**: Phase 1 머지 완료 후 진행 (V33 = V32 다음 번호 LOCK)

**산출 파일 매트릭스 (~10)**:

| 파일 | 책임 | 본 PR 머지 후 sub-agent 작업 |
|---|---|---|
| `batch/src/main/resources/db/migration/V33__t_news_article_table.sql` | DDL — `news_article` 테이블 + 멱등성 UQ `ux_news_article_source_stock_url_hash` + 인덱스 2건 | 신규 — ADR 0075 §2-1 본문 schema 그대로 적용 (V33 분리 §8-3 정합) |
| `api/src/main/kotlin/.../infrastructure/naver/NaverNewsAdapter.kt` | Naver Open API search/news.json 폴링 + `X-Naver-Client-Id` + `X-Naver-Client-Secret` header + UTF-8 query + `<b>` 태그 strip + pubDate RFC 822 파싱 | 신규 — ADR 0075 §7-5 raw 인용 KDoc 의무 |
| `api/src/main/kotlin/.../infrastructure/naver/NaverNewsRateLimiter.kt` | Redis ZSET 슬라이딩 윈도 — 분당 17건 hard limit (25K/일) | 신규 — Resilience4j RateLimiter 폐기 + Redis 분산 분리 |
| `api/src/main/kotlin/.../persistence/NewsArticleJpaEntity.kt` | JPA Entity 매핑 `news_article` | 신규 |
| `api/src/main/kotlin/.../persistence/NewsArticleRepository.kt` | Spring Data — `findByStockCodeAndPublishedAtBetween` + native UPSERT (ON CONFLICT source+stock_code+url_hash) | 신규 — ADR 0051 default fun 함정 회피 |
| `api/src/main/kotlin/.../infrastructure/naver/NewsKafkaProducer.kt` | Kafka `news.collected.v1` publish + Redis `news:dedup:url:{url_hash}` SETNX 24h | 신규 |
| `api/src/main/kotlin/.../controller/SymbolNewsController.kt` | `GET /api/symbols/{code}/news` Swagger 4단 + 페이징 + 기간 필터 | 신규 |
| `api/src/main/kotlin/.../service/SymbolNewsService.kt` | use case — stockCode 검증 + Page<NewsItem> 조회 + DTO 매핑 | 신규 |
| `api/src/main/kotlin/.../scheduler/WatchlistNewsScheduler.kt` | Watchlist 종목 우선 cron — 분당 1 종목 polling (분당 17건 hard limit + page 1 display 20 + 30 종목 = 30분 1회전) | 신규 — Watchlist Repository reuse |
| `.env.example` | `NAVER_OPEN_API_CLIENT_ID` + `NAVER_OPEN_API_CLIENT_SECRET` 신규 추가 + 주석 (Naver 개발자 센터 발급 URL + 25K/일 hard limit 안내) | 기존 갱신 |

**기존 파일 갱신**:

- `api/src/main/kotlin/.../config/KafkaTopicConfig.kt` — `news.collected.v1` 토픽 신규 add (`@Bean` NewTopic, retention 7d, partition 3)

**핵심 결정 LOCK**:

1. **Naver raw 인용 의무** — NaverNewsAdapter KDoc 상단 `https://developers.naver.com/docs/serviceapi/search/news/news.md` URL + ADR 0075 §7-5 parameter·response·error 표 inline 인용
2. **인증 헤더 정밀** — `X-Naver-Client-Id` + `X-Naver-Client-Secret` (대소문자 LOCK · 오타 0)
3. **`<b>` 태그 strip** — `title.replace(Regex("</?b>"), "")` (검색어 강조 태그 제거 후 영속)
4. **pubDate 파싱** — `DateTimeFormatter.RFC_1123_DATE_TIME` (RFC 822 호환) → `OffsetDateTime`
5. **`url_hash` 계산** — `MessageDigest.getInstance("SHA-256").digest(originallink.toByteArray()).joinToString("") { "%02x".format(it) }` → 64자 hex
6. **graceful skip** — `NAVER_OPEN_API_CLIENT_ID` 미설정 시 fetch 0 + log INFO "Naver API 키 미등록 — 폴링 skip"
7. **403 응답 처리** — 검색 API 권한 없음 → log ERROR + Slack P0 (ADR 0044 reuse) + 24h backoff (재시도 폭주 방지)
8. **Watchlist 우선** — 사용자 등록 종목만 polling (전체 universe ~3000 종목 X · API 한도 보호)
9. **rate limit** — Redis ZSET `news:ratelimit:naver` 분당 17건 hard limit (25K/일 ÷ 1440)
10. **종목 매핑** — Watchlist Repository → instrument.name → query 파라미터 (예: `query=삼성전자`)
11. **secretKey log 금지** — 메모리 `feedback_secret_handling.md` 정합 + ADR 0046 §2-5 MaskingPatternLayout reuse

**단위 테스트 매트릭스 ~12건**:

- NaverNewsAdapter — fetch 200 OK → items[] 매핑
- NaverNewsAdapter — graceful skip (API 키 미설정)
- NaverNewsAdapter — 403 응답 → Slack P0 + 24h backoff
- NaverNewsAdapter — `<b>` 태그 strip 검증 (`<b>삼성전자</b>` → `삼성전자`)
- NaverNewsAdapter — pubDate RFC 822 파싱 (`Mon, 26 Sep 2016 07:50:00 +0900` → OffsetDateTime)
- NaverNewsRateLimiter — 분당 17건 허용 + 18번째 false
- NaverNewsRateLimiter — 60s 후 slot 해제
- url_hash SHA-256 — 동일 URL 2회 동일 hash
- NewsArticleRepository UPSERT — 동일 (source, stock_code, url_hash) 2회 → 1건만
- NewsKafkaProducer — Redis SETNX 24h dedup → 2회 publish 시 1회만
- WatchlistNewsScheduler — Watchlist 30 종목 분당 1 종목 cron
- SymbolNewsService — 페이징 + 기간 필터 (3개월 limit) 검증

---

### 2-3. Phase 3 — frontend (NewsPanel + DisclosurePanel + sidebar 통합 + 60s polling)

**branch**: `agent/frontend/t-news-phase3-ui`
**author**: `bot+frontend@magicjar.local`
**task slug**: `t-news-phase3-ui`
**의존**: Phase 1·2 머지 완료 후 진행

**산출 파일 매트릭스 (~8)**:

| 파일 | 책임 | 본 PR 머지 후 sub-agent 작업 |
|---|---|---|
| `frontend/src/components/symbol/sidebar/NewsPanel.tsx` | 뉴스 카드 리스트 — `useSymbolNews` query + refetchInterval 60s + 빈 상태 "최근 7일간 뉴스가 없습니다" + Naver 키 미등록 안내 | 신규 |
| `frontend/src/components/symbol/sidebar/DisclosurePanel.tsx` | 공시 카드 리스트 — `useSymbolDisclosures` query + 정정공시 빨간 배지 강조 + URL 클릭 시 DART 본문 새 탭 | 신규 |
| `frontend/src/api/queries/useSymbolNews.ts` | `@tanstack/react-query` useQuery — endpoint `/api/symbols/{code}/news` + refetchInterval 60_000 + 페이지 0 default | 신규 |
| `frontend/src/api/queries/useSymbolDisclosures.ts` | 동상 — endpoint `/disclosures` + `onlyCorrection` query 옵션 | 신규 |
| `frontend/src/api/dto/SymbolNewsDto.ts` | TypeScript DTO — `NewsItem` + `SymbolNewsListResponse` (ADR 0075 §5-3 LOCK) | 신규 |
| `frontend/src/api/dto/SymbolDisclosureDto.ts` | 동상 — `DisclosureItem` + `SymbolDisclosureListResponse` | 신규 |
| `frontend/src/pages/SymbolDetailPage.tsx` | sidebar mount — `<NewsPanel code={code} />` + `<DisclosurePanel code={code} />` 추가 (현 sidebar 영역) | 기존 갱신 |
| `frontend/src/i18n/ko.ts` | 신규 문구 추가 — "최근 7일간 뉴스가 없습니다" · "최근 7일간 공시가 없습니다" · "Naver API 키 미등록" · "정정공시" 배지 라벨 | 기존 갱신 |

**핵심 결정 LOCK**:

1. **sidebar 위치** — SymbolDetailPage 우측 sidebar 영역 (Watchlist · 현재가 · 차트 ↘ News · Disclosure 순서)
2. **polling interval 60s** — `refetchInterval: 60_000` (의제 6-1 정합 + STOMP 0 LOCK)
3. **unmount cleanup** — `queryClient.removeQueries({ queryKey: ['symbolNews', code] })` SymbolDetailPage useEffect cleanup
4. **정정공시 배지 강조** — `<span className="bg-red-500 text-white px-1 rounded text-xs">정정공시</span>` (Tailwind reuse)
5. **빈 상태 mock 합성 0** — 메모리 `feedback_no_mock_data.md` 정합 + Naver 키 미등록 시 명시 안내
6. **STOMP 0 LOCK** — STOMP client import 0 (의제 6-1 정합 검증 의무 — grep)
7. **AI provider import 0 LOCK** — frontend도 sentiment field NULL only render (T-AI 사이클 분리)
8. **URL 새 탭 open** — `target="_blank" rel="noopener noreferrer"` (보안 정합)

**단위 테스트 매트릭스 ~6건** (vitest):

- NewsPanel — 빈 array → "최근 7일간 뉴스가 없습니다" render
- NewsPanel — items 5건 → 5개 카드 render + 발행시간 KST 표시
- DisclosurePanel — isCorrection=true item → 빨간 배지 render
- useSymbolNews — refetchInterval 60s 검증 (vitest fake timer)
- SymbolDetailPage — unmount 시 query cleanup
- i18n — 신규 문구 4건 ko.ts 등록 검증

---

## 3. KIS-first raw fetch 인용 (헌법 §3 5단계 절차)

본 spec markdown은 ADR 0075 §7-4 + §7-5 raw 인용을 1:1 reuse — sub-agent 코드 PR 발행 시 KDoc 의무 LOCK.

### 3-1. DART list.json (Phase 1 의무)

```
출처: https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001
endpoint: https://opendart.fss.or.kr/api/list.json
인증: query parameter `crtfc_key=<40자리>` (header 아님 — 오타 risk LOCK)
한도: 20,000건/일 hard limit (error 020)
응답 list[] STRING: corp_cls·corp_name·corp_code·stock_code·report_nm·rcept_no·flr_nm·rcept_dt·rm
페이지: page_count max=100
```

### 3-2. Naver search/news.json (Phase 2 의무)

```
출처: https://developers.naver.com/docs/serviceapi/search/news/news.md
endpoint: https://openapi.naver.com/v1/search/news.json (JSON · XML 폐기)
인증: HTTP header X-Naver-Client-Id + X-Naver-Client-Secret
한도: 25,000건/일 hard limit (검색 API 공통)
요청 param: query(필수 UTF-8) · display(1-100 default 10) · start(1-1000 default 1) · sort(sim/date)
응답 items[]: title (<b> strip 의무) · originallink · link · description (<b> strip) · pubDate (RFC 822)
권한 403: 네이버 개발자 센터 > 내 애플리케이션 > API 설정 > 검색 체크 의무
error: SE01·SE02·SE03·SE04·SE05·SE06·SE99
```

---

## 4. 검증 시나리오 (ADR 0075 §9 정합)

### 4-1. Phase 1 머지 후 dogfooding

- [ ] DART cron 1분 실행 빈도 확인 (log INFO + metrics Counter)
- [ ] 정정공시 detect → `disclosure.is_correction=true` row INSERT 확인
- [ ] Kafka `disclosure.new.v1` topic publish 확인 (kafka-console-consumer)
- [ ] `GET /api/symbols/005930/disclosures?from=...&onlyCorrection=true` → 정정공시만 응답
- [ ] Swagger UI `/swagger-ui.html` 4단 어노테이션 한국어 description + example 노출
- [ ] DART 20K/일 hard limit 안전 마진 4배 검증 (분당 ~14건 → 60건 limit 여유)

### 4-2. Phase 2 머지 후 dogfooding

- [ ] `.env` `NAVER_OPEN_API_CLIENT_ID` 미설정 → graceful skip + log INFO 확인
- [ ] `.env` 키 등록 후 재부팅 → Watchlist 종목별 polling 시작 (log INFO "Naver polling start: 005930")
- [ ] news_article row 5건 INSERT 확인 (멱등성 UQ ON CONFLICT 검증)
- [ ] `<b>` 태그 strip 확인 (`<b>삼성전자</b>` → `삼성전자` 영속)
- [ ] Kafka `news.collected.v1` topic publish 확인
- [ ] `GET /api/symbols/005930/news` 페이징 + 기간 필터 응답 확인
- [ ] 403 응답 시뮬 (의도적 잘못된 secret) → Slack P0 알림 + 24h backoff
- [ ] Naver 17건/분 hard limit 도달 시 18번째 skip + log WARN

### 4-3. Phase 3 머지 후 dogfooding

- [ ] SymbolDetailPage open → sidebar 우측 NewsPanel + DisclosurePanel mount
- [ ] 60s 후 refetch auto-trigger → 신규 row 자동 갱신
- [ ] 정정공시 빨간 배지 시각 확인
- [ ] URL click → DART/Naver 본문 새 탭 open
- [ ] SymbolDetailPage unmount → polling stop (network 탭 검증)
- [ ] Naver 키 미등록 + 빈 array → "Naver API 키 미등록" 한국어 안내 표시
- [ ] STOMP 0 LOCK 검증 — `grep "/topic/news" frontend/src` → 0건
- [ ] AI provider import 0 LOCK — `grep "anthropic\|openai\|sentiment" frontend/src/components/symbol/sidebar/` → 0건

---

## 5. CHANGELOG slot 4 anchor (사전 prepend LOCK)

ADR 0050 정합 — architect 사전 발행 + sub-agent in-place edit:

```markdown
## v3.6.7 — T-NEWS 트랙 진입 (사용자 결정 8 의제 채택 · v3.6.7 patch 사이클)

### architect <!-- anchor-t-news-arch -->
- (#434) ADR 0075 T-NEWS 트랙 진입 spec/plan 발행 (사용자 결정 8 의제 채택 + v3.6.7 patch 정합 + Phase 분리 LOCK 3 PR 직렬 + DART list.json + Naver search/news.json raw fetch 인용 + 산출 ~25 파일 매트릭스). ADR 0073 supersede (analysis only → 결정 채택 + 정밀 spec 위임).

### market-data Phase 1 <!-- anchor-t-news-md -->
- (#TBD) Phase 1 DART 실시간 폴링 강화 (cron 5분 → 1분 + 정정공시 분류 + Flyway V32 disclosure 테이블 멱등성 UQ ux_disclosure_rcept_no + DisclosureKafkaProducer disclosure.new.v1 publish + Redis dedup + REST GET /api/symbols/{code}/disclosures Swagger 4단 + 단위 테스트 ~10건).

### backend-core Phase 2 <!-- anchor-t-news-bc -->
- (#TBD) Phase 2 Naver Open API 어댑터 + news_article 영속 (Flyway V33 IF NOT EXISTS + 멱등성 UQ source+stock+url_hash + NaverNewsAdapter Redis ZSET 분당 17건 rate limit + <b> 태그 strip + pubDate RFC 822 파싱 + NewsKafkaProducer news.collected.v1 publish + Watchlist 종목 우선 cron + REST GET /api/symbols/{code}/news Swagger 4단 + .env.example NAVER_OPEN_API_* + 단위 테스트 ~12건).

### frontend Phase 3 <!-- anchor-t-news-fe -->
- (#TBD) Phase 3 종목 상세 sidebar NewsPanel + DisclosurePanel + useSymbolNews/useSymbolDisclosures hook + refetchInterval 60s polling + 정정공시 빨간 배지 강조 + 빈 상태 한국어 + Naver 키 미등록 안내 + STOMP 0 LOCK 검증 + AI provider import 0 LOCK + 단위 테스트 ~6건.
```

---

## 6. 의존성 + 직렬 LOCK

```
[architect] ADR 0075 + spec markdown (PR #434 본 PR)
    ↓ 머지 후
[market-data Phase 1] Flyway V32 + DartAdapter 1분 + DisclosureProducer + REST /disclosures
    ↓ 머지 후 (V33 sequential 의존)
[backend-core Phase 2] Flyway V33 + NaverAdapter + NewsProducer + REST /news + .env.example
    ↓ 머지 후 (endpoint 의존)
[frontend Phase 3] NewsPanel + DisclosurePanel + 2 hook + SymbolDetailPage mount
```

**병렬 가능성**: Phase 1·2 add-only 영역 분리 가능하나 **Flyway V32→V33 sequential 의무 + endpoint mock 0 정합 (메모리 `feedback_no_mock_data`) → 직렬 LOCK**.

---

## 7. 사용자 메모리 정합 매트릭스

| 메모리 | 정합 검증 |
|---|---|
| `feedback_v3_skepticism_no_regression` | ADR 0075 §1-4 fitness gap 검증 매트릭스 + v1/v2 잔재 0 |
| `feedback_release_policy_patch_first` | v3.7.0=W4 LOCK 정합 → 본 트랙 v3.6.7 patch 진입 |
| `feedback_3tier_hierarchy` | architect 단독 spec 발행 + sub-agent 디스패치 분리 |
| `feedback_kis_first_policy` | ADR 0057 §1 시세 단일 KIS LOCK + 뉴스·공시 보조 OK (master §B-3 정합) |
| `feedback_no_mock_data` | Naver 키 미등록 시 frontend 한국어 안내 + mock 합성 0 |
| `feedback_secret_handling` | Naver Client Secret log 금지 + .env 단독 저장 + UI 0건 1차 |
| `feedback_swagger_pattern` | REST 2 endpoint Swagger 4단 한국어 + example 의무 |
| `feedback_qa_on_demand` | 본 사이클 QA 게이트 X (MVP 단계) — 단위 테스트만 |
| `feedback_orchestrator_active_parallel` | Phase 1·2·3 add-only 분석 + 직렬 LOCK 사유 명시 (Flyway sequential + endpoint mock 0) |

---

## 8. Rollback 계획 (ADR 0075 §12 정합)

- **Phase 1 결함**: cron 빈도 5분 복귀 + Flyway V32 테이블 보존 (운영 데이터 손실 회피)
- **Phase 2 결함**: NaverNewsAdapter `@ConditionalOnProperty` toggle 0 + Flyway V33 보존
- **Phase 3 결함**: SymbolDetailPage sidebar mount 단독 revert

---

## 9. 참고 ADR + 문서

- **ADR 0075** (본 spec 1차 SoT)
- ADR 0073 supersede (analysis only → 본 spec)
- ADR 0010 §5 (멀티테넌트 news 테이블 baseline)
- ADR 0018 (DART 폴링 baseline + DartDisclosureAdapter 운영 중)
- ADR 0044 (T-NOTIFY Slack webhook reuse)
- ADR 0057 (KIS-only 시세 단일 + 뉴스 보조 OK)
- ADR 0069 §F (SettingsModal NaverApiSection 잔류 — T-AI 사이클 흡수)
- ADR 0072 (T-J·T-PERF 마감 — W3 종착 후 W3.5 본 트랙 진입)
- master `§A.4` News & Disclosure
- master `§B-3` NAVER 옵션 + 끊겨도 본체 영향 0
- master `§C.13` News 19 컴포넌트
- tracks-roadmap §2 T-NEWS row
- backlog §52 흡수 마킹 + §53 신규 (Phase 4 RSS 잔류)
- 헌법 §3 KIS-first raw fetch 의무 5단계 절차
