# market-data 시스템 점검 + hotfix 의제 도출 보고서

**작성**: market-data (`bot+market-data@magicjar.local`)
**발행일**: 2026-05-19 KST
**Task**: T-20260519-1648
**근거 권위**: 운영 헌법 §3 KIS-first raw fetch · ADR 0037 KIS Rate Limit · ADR 0038 KIS OAuth 분산락 · ADR 0042 잔고조회 · ADR 0048 KIS WS · ADR 0063 NXT · ADR 0075 T-NEWS · ADR 0076 NXT/시간외 · ADR 0081 T-ACCT · ADR 0084 P0-1·P0-3 · ADR 0086 §11 ASC 정규화

---

## 0. Executive Summary

### 시스템 헬스 매트릭스

| 카테고리 | 등급 | 핵심 evidence |
|---|---|---|
| KIS REST 어댑터 (auth · candle · price · balance · order · hashkey · reconcile) | OK (8건 raw 인용 정합 + ASC 정규화 LOCK 1건) | 본문 §1 |
| KIS WebSocket (체결가·NXT·시간외·체결통보·호가·재연결·heartbeat·OPSP8996) | OK (9건 raw 인용 정합 + onReconnect backfill hook) | 본문 §2 |
| 뉴스 (Naver) + 공시 (DART) path | OK (Naver Rate Limit 분당 17건 Redis ZSET + DART 1분 폴링 + java.net.http SSL fix) | 본문 §3 |
| Rate Limit 가드 (ADR 0037) | **DEGRADED** — Bucket4j stub 상태, sleep(50ms) 마진만 적용 | 본문 §4 + hotfix P1-1 |
| 한국석유 5개월 갭 — root cause cross-ref | **CLOSED** — ASC 정규화 LOCK 적용됨 (chart-curator PR #537) but api 모듈 별도 path 잔존 | 본문 §5 + hotfix P0-1 |
| API 모듈 별도 캔들 path (CandleRefreshService) | **DRIFT** — parseKisDailyOutput2 ASC 정규화 누락 | 본문 §6 + hotfix P0-1 |
| spec 표기 drift (ADR 0037 "10건" vs 코드 주석 "20 req/s") | **DRIFT** | 본문 §7 + hotfix P2-1 |

### hotfix 의제 (4건)

| 등급 | 의제 | 영역 | 사이클 |
|---|---|---|---|
| **P0-1** | `CandleRefreshService.parseKisDailyOutput2` ASC 정규화 추가 | api 모듈 | 즉시 |
| **P1-1** | ADR 0037 Bucket4j stub → 실 구현 (KisRateLimiterPort) | domain + 3모듈 어댑터 | architect 결정 후 |
| **P1-2** | EGW00201 (TPS 초과) 명시 처리 path 부재 — backoff + 재시도 매트릭스 추가 | consumer · api · batch | architect 결정 후 |
| **P2-1** | spec 표기 drift — ADR 0037 "초당 10건" vs 코드 주석 "20 req/s" 통일 | docs + KDoc replace_all | 즉시 |

---

## 1. KIS REST 어댑터 매트릭스

### 1-1. 모듈별 어댑터 (raw 인용 정합)

| 어댑터 | 모듈 | tr_id | 책임 | raw 인용 | 등급 |
|---|---|---|---|---|---|
| `KisAuthAdapter` | consumer | n/a (OAuth `/oauth2/tokenP`) | access_token 발급 + Redis cache 23h + in-process mutex + 분산락 | ADR 0038 §2-2 정합 | OK |
| `KisApprovalAdapter` | consumer | n/a (OAuth `/oauth2/Approval`) | WS approval_key 발급 + cache 23h + 분산락 | open-trading-api kis_auth.py auth_ws() 인용 | OK |
| `KisHashKeyAdapter` | consumer | n/a (`/uapi/hashkey`) | 주문 hashkey 발급 (ADR 0084 P0-1) | examples_user/kis_auth.py L? verbatim LOCK | OK |
| `KisRestCandleAdapter` | consumer | FHKST03010100 + FHKST03010230 | 일봉/주봉/월봉/연봉 + 1m 분봉 — **ASC 정규화 LOCK** | ADR 0086 §11 매트릭스 + parseKisDailyResponse line 833-836 sortedBy 적용 | OK |
| `KisRestPriceAdapter` | consumer | FHKST01010100 | 현재가 단건 + PriceMetrics (PER/PBR/EPS) | kis-api-reference.md §3 #1 정합 | OK |
| `KisBalanceAdapter` | consumer | TTTC8434R / VTTC8434R | 잔고조회 (Bootstrap 영속용 output1 only) | ADR 0042 §2-2 정합 | OK |
| `ApiKisBalanceQueryAdapter` | api | TTTC8434R / VTTC8434R | 사용자 노출 잔고 (output1 + output2 흡수, Redis cache) | ADR 0081 §2-2 + examples_llm/inquire_balance.py raw 인용 | OK |
| `KisReconcileBalanceAdapter` | consumer | TTTC8434R | reconcile 페이징 holdings 비교 (T-RECON) | T-RECON spec 정합 | OK |
| `KisOrderHttpAdapter` | consumer | TTTC0012U·TTTC0011U·TTTC0013U + VTTC variant | 주문 발행 + 정정·취소 | examples_llm/order_cash·order_rvsecncl 매트릭스 정합 | OK |
| `KisOrderInquireCcldHttpAdapter` | consumer | TTTC8001R | 정정·취소가능 주문 조회 (ADR 0084 P0-3 backfill용) | KIS 공식 raw 인용 | OK |
| `KisOrderInquireDailyCcldHttpAdapter` | consumer | TTTC8036R | 일별 체결조회 (ADR 0084 P0-3 backfill용) | KIS 공식 raw 인용 | OK |
| `KisChkHolidayAdapter` | batch | CTCA0903R | 휴장 캘린더 동기화 | KIS 공식 raw 인용 | OK |
| `KisKsdInfoAdapter` | batch | HHKDB669107·669108·669109·669110·669111·669112 | 6 ksdinfo 코퍼레이트 액션 endpoint | KIS 공식 raw 인용 | OK |
| `KisInstrumentMasterAdapter` | batch | n/a (KSD master CSV) | 종목 마스터 부트스트랩 | open-trading-api 정합 | OK |
| `KisAuthValidator` (api) | api | n/a (`/oauth2/tokenP`) | UI ⚙️ 키 등록 시 단발 검증 | ADR 0046 정합 | OK |

**총 15개 KIS REST adapter** — KIS-first raw 인용 매트릭스 100% (15/15). 운영 헌법 §3 정합.

### 1-2. ASC 정규화 매트릭스 (chart-curator ADR 0086 §11 정합)

| parse 함수 | 모듈 | tr_id | timeframe | sort 패턴 | ASC normalize | 등급 |
|---|---|---|---|---|---|---|
| `KisRestCandleAdapter.parseKisDailyResponse` | consumer | FHKST03010100 | 일·주·월·연봉 | KIS DESC | `.sortedBy { it.ts.epochSeconds }` line 835 LOCK | OK |
| `KisRestCandleAdapter.parseKis1mResponse` | consumer | FHKST03010230 | 1m | DESC 가능 | line 751 LOCK | OK |
| **`CandleRefreshService.parseKisDailyOutput2`** | **api** | FHKST03010100 | 일·주·월·연봉 | KIS DESC | **❌ 누락** (line 408-429) | **P0-1 hotfix** |

api 모듈 path는 ON CONFLICT DO NOTHING upsert(insertIgnore)이라 row 자체 적재는 정상이지만, downstream에서 이 list를 monotonic 가정으로 처리한다면 즉시 결함 발생. 또한 chart-curator skill §5 source-of-truth 위반 — adapter layer가 raw 정규화 단일 책임 강제인데 api path는 우회.

### 1-3. KIS HTTP 500 retry 정책

- exponential backoff 3회 (500ms → 1000ms → 2000ms) — KisRestCandleAdapter line 90, 580
- 503/502 등 기타 HTTP 오류는 즉시 실패 (정합)
- **EGW00201 (TPS 초과) 명시 처리 path 부재** — backlog §62 LastCandleAtMonitor에서만 언급. P1-2 hotfix.

---

## 2. KIS WebSocket 매트릭스

### 2-1. 구독 매트릭스 (ADR 0048 §2-1 + 0063 NXT + 0076 시간외)

| tr_id | 책임 | 시간대 | slot 예산 | raw 인용 |
|---|---|---|---|---|
| H0STCNT0 | 정규장 체결가 | 09:00~15:30 KST | 최대 20 | open-trading-api/examples_user/domestic_stock_functions_ws.py |
| H0NXCNT0 | NXT 대체거래소 체결가 | 08:00~20:00 KST | 최대 20 | open-trading-api/examples_user/domestic_stock/domestic_stock_functions_ws.py |
| H0STOUP0 | 시간외 단일가 (KRX) | 16:00~18:00 KST | 최대 20 | open-trading-api/examples_llm/.../overtime_ccnl_krx.py |
| H0STASP0 | 호가 | 24/7 | 0 (LRU OFF, ADR 0076 정합) | open-trading-api 정합 |
| H0STCNI0 / H0STCNI9 | 체결통보 (REAL/MOCK) | 24/7 | 1 reserve | open-trading-api 정합 |

**41 slot 예산** = H0STCNT0(20) + H0NXCNT0(20) + H0STCNI0(1) — KIS WS 동시 구독 41건 한도 정합 (정규장 시간 LOCK).

### 2-2. 재연결·복원성 가드 (ADR 0048 + 0076 + 0084)

| 가드 | 매커니즘 | 등급 |
|---|---|---|
| 지수 백오프 재연결 | 0s → 2s → 5s → 10s → 30s (5단) | OK |
| heartbeat watchdog | 정규장 30s / 정규장 외 60s (ADR 0076 D-1) | OK |
| OPSP8996 self-heal | 1회 retry(1s) 후 fail | OK (KisWebSocketBridgeOpsp8996FixTest 검증) |
| unsubscribe ACK latch | 2s timeout (ADR 0076 E-1) | OK |
| onReconnect backfill hook | KisOrderInquireCcldHttpAdapter 위임 (ADR 0084 P0-3) | OK |
| polling fallback | 30s timeout 시 backend-core WatchlistPollingWorker 위임 | OK |
| Sinks bounded buffer | 1024 (BACKPRESSURE_BUFFER_SIZE) — overflow silent drop | OK (backlog §29 #4 정합) |
| graceful shutdown | @PreDestroy ≤ 5s (ADR 0045) | OK |
| WS race 가드 | reconnecting AtomicBoolean — doFinally 완료 전 중복 dispose 차단 | OK (ADR 0084) |

### 2-3. WS 메시지 파싱

- `KisWsMessageParser` (체결가·NXT·시간외 공통 byte offset)
- `KisWsOrderbookParser` (호가 별도)
- KIS raw byte offset 매트릭스 기반 (헌법 §3 정합)

---

## 3. 뉴스·공시 path 매트릭스

### 3-1. DART (전자공시)

| 컴포넌트 | 위치 | 책임 | 등급 |
|---|---|---|---|
| `DartDisclosurePollingScheduler` | api/scheduler | 1분 cron (`0 * * * * *` Asia/Seoul) — list.json 폴링 + DB 적재 + Kafka publish | OK (ADR 0075 §1-3 정합) |
| `DartDisclosureAdapter` (batch) | batch/infrastructure/dart | corp_action 동기화 — list.json 호출 (T-CA1 PR-29b) | OK (raw 인용 정합) |
| `DartDisclosureAdapter` (api) | api/infrastructure/dart | **placeholder object 만 존재** — 본문 7줄 | OK (의도된 noop) |
| `DartCorpCodeRepository` (api·batch 양쪽) | persistence | stock_code ↔ corp_code 매핑 (in-memory) | OK (모듈별 사본 — ADR 0051 패턴 정합) |
| `DisclosureKafkaProducer` | api | Redis SETNX 24h dedup | OK |

**raw 출처 LOCK** (헌법 §3 정합):
- API 가이드: `https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001`
- endpoint: `https://opendart.fss.or.kr/api/list.json`
- 인증: query parameter `crtfc_key=<40자리>` (header 아님 — oversight LOCK)
- 한도: 20,000건/일 hard limit

**SSL fix**: hotfix/dart-ssl-handshake — Reactor Netty SslProvider.JDK 우회 + java.net.http.HttpClient (JDK 21 TLS 직접) 전환.

**graceful degrade**: `OPENDART_API_KEY` 미설정 시 fetch 0 + log INFO (사용자 흐름 침범 없음).

### 3-2. Naver Open API search/news.json (ADR 0075 Phase 2)

| 컴포넌트 | 책임 | 등급 |
|---|---|---|
| `NaverNewsAdapter` | search/news.json 호출 + items[] → NewsArticle 영속 + `<b>` strip + HTML entity decode + SHA-256 url_hash | OK |
| `NaverNewsRateLimiter` | Redis ZSET 슬라이딩 윈도 분당 17건 (25K/일 ÷ 1440) | OK (ADR 0075 §4-2 정합) |
| `WatchlistNewsScheduler` | 1분 fixedRate cron — Watchlist 종목 순회 + acquireSlot 통과 후 fetch | OK |
| `NewsKafkaProducer` | Kafka publish (24h dedup) | OK |

**raw 출처**: `https://developers.naver.com/docs/serviceapi/search/news/news.md` (헌법 §3 정합 — verbatim 인용 LOCK)

**한도**: 25,000건/일 hard limit. 권한 부재 시 403 응답 처리 (`네이버 개발자 센터 > 내 애플리케이션 > API 설정 > '검색' 체크 의무` log).

**graceful degrade**: API 키 미등록 시 fetch 0 + log INFO.

### 3-3. RSS 한경·구글 (backlog §53 잔류)

- ADR 0075 Phase 4 비범위 — Naver 한도 보완용 잔류 의제.
- 본 점검 시점에는 미구현 (정합).

---

## 4. Rate Limit 가드 매트릭스 (★ 핵심 issue)

### 4-1. 현행 상태 — ADR 0037 Bucket4j stub

ADR 0037 §1 LOCK:
- **REST 호출 초당 10건** (계정·앱키당) — 위반 시 `EGW00201` 또는 HTTP 429
- 토큰 발급 1분당 1회 (별도, ADR 0038에서 in-process mutex + 분산락 LOCK)
- WebSocket 동시 구독 41건

ADR 0037 §2-1 정책: **Bucket4j 또는 Resilience4j RateLimiter** + `domain/.../market/ratelimit/KisRateLimiterPort` 단일 진입점.

**현행 코드 상태 (audit grep matrix)**:

| 위치 | 정합 |
|---|---|
| `domain/.../market/ratelimit/KisRateLimiterPort.kt` | **❌ 존재하지 않음** |
| Bucket4j dependency `build.gradle.kts` | **❌ 없음** |
| KIS adapter `acquire()` 통과 의무 | **❌ 없음** — 모든 adapter가 직접 `webClient.get()/post()` |
| 분당 호출 빈도 가드 | sleep(50ms) interval 마진만 (20 req/s 가정 — KDoc 표기) |
| `KisKsdInfoAdapter` KDoc | "ADR 0037 Rate Limiter 통과 의무 (Bucket4j — **현재 stub, W3 본격 적용**)" — line 66 |
| EGW00201 명시 처리 | `KisReconcileBalanceAdapter` KDoc line 46만 언급 (`本 baseline은 호출 caller KisRateLimiterPort 추후 합류`) |
| HTTP 429 명시 처리 | 0건 |
| Circuit Breaker 5분 차단 (ADR 0037 §2-2) | 0건 |
| ArchUnit 빌드 게이트 (ADR 0037 §2-4) | 0건 |

### 4-2. 운영상 risk 매트릭스

| 시나리오 | TPS | KIS 한도 침범 risk |
|---|---|---|
| Watchlist 16종목 × 1m polling fixedRate 60s | ~0.27 req/s | 낮음 |
| LiveDailyCandleScheduler 5분 cron × 16종목 | ~0.05 req/s | 낮음 |
| **사용자 트리거 캔들 갱신** (16종목 × 5 timeframe 일괄) | 80건/burst | **위험** (sleep 50ms 마진 = 20 req/s 한도 가정인데 실제 한도 10 req/s) |
| **batch backfill 폭주** (예: 30 gap × 100종목) | 3000건 burst | **위험** |
| **DART 1분 폴링 × WS 재연결 burst × backfill 동시** | 다발 | **고위험** (multi-JVM share 미합산) |

### 4-3. P1-1 hotfix 의제

**제목**: ADR 0037 Bucket4j 실 구현 + KisRateLimiterPort 합류

**범위**:
1. `domain/.../market/ratelimit/KisRateLimiterPort` 인터페이스 발행 (acquire(priority), tryAcquire())
2. Bucket4j-Redis 또는 Resilience4j RateLimiter 분산 모드 채택 (architect 결정 의제)
3. 3 모듈(api·batch·consumer) 어댑터 합류 — 모든 KIS REST 호출 직전 `acquire()` 통과
4. EGW00201/HTTP 429 응답 시 1초 backoff + 1회 재시도 + Slack alert (ADR 0037 §2-2 정합)
5. Circuit Breaker — 연속 5분 50% 이상 EGW00201 시 5분 차단 + backfill 큐 pause
6. ArchUnit 빌드 게이트 — KIS 도메인 어댑터 외 `webClient.get()/post()` 직접 호출 reject

**선행 의제**: ADR 0037 §1 표기 "초당 10건" — 코드 주석 다수 "20 req/s" drift 통일 (P2-1).

**fitness**: 자본 path 보호 — REAL 진입 직전 차단 risk 0 (계정·앱키 정지 사고 방지).

---

## 5. 한국석유 5개월 갭 — root cause cross-ref

### 5-1. chart-curator 보고서 인용 (2026-05-19-kor-petroleum-investigation-report.md §2-4)

> `KisRestCandleAdapter.parseKisDailyResponse()` (line 737-786)가 KIS `inquire-daily-itemchartprice` 응답 `output2[]`를 받은 순서 **그대로** Candle list로 변환. KIS는 최신→과거 **DESC 순서**로 응답.
>
> `CandleBackfillWorker.sanityCheck()` 햇징 룰 §1 monotonic 검증이 **ASC를 강제** → idx 1~99 전부 reject → **insertedCount=0** → 5개월 갭 누적.

### 5-2. market-data 영역 cross-verify (2026-05-19 현재)

| 위치 | 상태 | 검증 |
|---|---|---|
| `KisRestCandleAdapter.parseKisDailyResponse` line 833-837 | **fix 적용됨** — `.sortedBy { it.ts.epochSeconds }` LOCK | OK (chart-curator PR #537 머지) |
| `KisRestCandleAdapter.parseKis1mResponse` line 751 | **fix 적용됨** | OK |
| `KisRestCandleAdapterAscNormalizeTest` | 검증 테스트 존재 | OK |
| **`CandleRefreshService.parseKisDailyOutput2` line 408-429 (api 모듈)** | **❌ ASC 정규화 누락** | **P0-1 hotfix** |

### 5-3. 추가 root cause — 한국석유 일봉 row 자체는 정상

chart-curator 보고서 §2-1 evidence:
- 일봉 100건 모두 KIS 출처 + 단조 ASC 정렬 + OHLC 정합성 양호 + monotonic 위반 0건
- `backfill_batch_id` 전부 NULL — Phase 2 backfill worker가 한국석유 일봉에 단 한 row도 적재하지 못함

→ 본 fix가 적용된 시점(2026-05-19 현재) 이후 backfill 워커가 다음 사이클에서 정상적으로 ASC 정렬된 KIS 응답을 받아 27 영업일(2025-12-31 ~ 2026-05-18) 갭을 채울 수 있어야 함.

**관측 의제** (사용자 dogfooding 사이클 권장):
- 일봉 backfill 워커 다음 trigger 후 `audit_trail` 에 `CANDLE_BACKFILL_SUCCESS` action 발행 확인
- 한국석유(004090) 일봉 row count: 100 → 100+갭개수(95) 증가 검증
- `latest_ts` 2025-12-30 → 2026-05-19 갱신 검증

### 5-4. 화면에 "2026-05-19" 표시 — 가설 A cross-ref

chart-curator 보고서 §2-2 가설 A: **frontend가 다른 interval 데이터를 일봉 차트에 mix** (1h boundary 14:00 candle이 일봉 series에 update).

→ market-data 영역 비범위 (frontend STOMP forwarder 책임). chart-curator 영역 후속 사이클.

**market-data 협업 의제**: STOMP `latestBoundaryCandle` topic을 발행할 때 interval 정보를 payload header에 명시 박아두는 의제 검토 (frontend가 interval 가드 적용하기 쉬워짐). 후속 사이클 검토.

---

## 6. API 모듈 별도 캔들 path (CandleRefreshService) DRIFT

### 6-1. 발견 — api 모듈에 독립 KIS 호출 path 잔존

`api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/CandleRefreshService.kt` (line 154-260)는 consumer `KisRestCandleAdapter`를 호출하지 않고 자체적으로 KIS API 호출:
- `webClient.get()` 직접 호출 (line 184-208)
- `parseKisDailyOutput2` 자체 파서 (line 408-429) — **ASC 정규화 누락**

### 6-2. 책임 분리 의제

이 path는 **사용자 트리거 캔들 갱신**(LiveDailyCandleScheduler 5분 cron 외에 사용자 명시 refresh API endpoint)에 사용. 책임 분리 매트릭스:

| 영역 | 경로 | adapter | ASC 정규화 |
|---|---|---|---|
| 사용자 트리거 (api 모듈) | `CandleRefreshService.refresh()` | api 자체 호출 | **❌ 누락** |
| backfill 워커 (consumer 모듈) | `CandleBackfillWorker` → `KisRestPort` → `KisRestCandleAdapter` | consumer | OK |
| 라이브 5분 cron (api 모듈) | `LiveDailyCandleScheduler` → `CandleRefreshService` | api 자체 호출 | **❌ 누락** |

### 6-3. P0-1 hotfix — 2 옵션 매트릭스

#### Option A — 최소 변경 (sortedBy 추가)

```kotlin
// CandleRefreshService.parseKisDailyOutput2 line 426 직전
return result.sortedBy { it.epochSec }  // KIS DESC → ASC 정규화 (ADR 0086 §11 정합)
```

**장점**: 최소 변경 + 본 사이클 빠른 fix
**단점**: api 모듈이 KIS 호출 path 자체 보유 — 책임 분리 모호 잔존

#### Option B — api 모듈 path 제거 + consumer KisRestPort 위임

`CandleRefreshService.refresh()`을 consumer `KisRestCandleAdapter.fetchCandles()` 위임으로 변경. api → consumer cross-module 호출 — Spring bean DI 가능.

**장점**: 단일 진입점 + ASC 정규화 LOCK
**단점**: 모듈 경계 cross 발생 — ADR 0004 모듈 경계 정합 의제 + LiveDailyCandleScheduler 종속도 변경

→ **권장**: Option A 즉시 + 후속 cycle에서 Option B 검토 (T-CC Phase 3 합류 의제).

---

## 7. spec 표기 drift — ADR 0037 vs 코드 주석

### 7-1. 발견

ADR 0037 §1 LOCK: **"REST 호출 초당 10건"**

코드 주석 다수 (15+ 위치): **"20 req/s"**
- `KisRestCandleAdapter` KDoc line 41·88·155
- `consumer/.../LastCandleAtMonitor.kt` line 40·227
- `api/.../CandleRefreshService.kt` line 42·129·132·264·298
- etc.

### 7-2. root cause 추정

PR-9a (KisRestCandleAdapter 첫 발행) 시점에 author가 KIS 공식 문서에서 "초당 20건" 또는 "tps 20"으로 잘못 읽고 KDoc에 박은 것으로 추정. 이후 모든 adapter들이 copy-paste로 확산. ADR 0037은 별도로 작성되며 정확한 "초당 10건"으로 LOCK.

### 7-3. P2-1 hotfix

**범위**: docs + KDoc replace_all "20 req/s" → "10 req/s" (ADR 0037 §1 LOCK 정합)

**위험**: 현재 sleep(50ms) 마진은 20 req/s 가정 → 실제 한도 10 req/s 대비 0.5배 마진만 존재. 사용자 트리거 burst 또는 batch backfill 폭주 시 침범 risk. P1-1 Bucket4j 본격 합류 의제 +까지 sleep(100ms)로 변경 의제도 같이 검토.

---

## 8. 점검에서 발견된 잠재 risk 매트릭스 (informational)

### 8-1. backlog §62 — onReconnect WS race throttle 부재

`KisExecutionBackfillService` KDoc line 60-62:
> WS 폭주(헬스체크 미스 → 무한 재연결) 시 KIS rate limit hit 가능 → backlog §62에서 throttle (예: 60s 내 1회 cap) 검토

현재 onReconnect hook이 발사할 때마다 backfill KIS REST 호출 trigger. 만일 WS heartbeat watchdog이 빈번하게 트리거 (예: 30s 마다 disconnect)되면 backfill 호출 폭주 risk.

**의제**: 60s 내 1회 cap throttle 합류 — 후속 사이클 검토.

### 8-2. consumer 모듈 별도 KisInstrumentRaw (batch와 동일 이름 사본)

`consumer/.../infrastructure/kis/KisInstrumentRaw.kt`와 `batch/.../infrastructure/kis/KisInstrumentRaw.kt` 양쪽 존재 — ADR 0051 패턴 (모듈별 사본) 정합. content drift 검증 필요한지 향후 audit.

### 8-3. UN/J 분기 안전 기본값 검토

`KisRestCandleAdapter.resolveMrktDivCode` (line 121-128):
- KOSPI → "UN" (NXT cover)
- KOSDAQ → "J" (NXT 미거래 → UN 빈 응답 방지 — PR #301 evidence)
- UNKNOWN/기타 → "J" (안전 기본값)

새 종목이 등록되었을 때 `instrumentRepository`에 market 정보 없으면 UNKNOWN → "J" fallback. KOSPI 종목이 NXT 미cover 되는 일시 risk. instrument 동기화 cycle이 일봉 동기화보다 늦으면 발생. 의제: instrument 동기화 우선 순위 검토 — 후속 사이클.

---

## 9. 결론 + 후속 사이클 매트릭스

### 9-1. 즉시 hotfix (P0)

| ID | 의제 | 영역 | 사이클 분류 |
|---|---|---|---|
| **P0-1** | `CandleRefreshService.parseKisDailyOutput2` ASC 정규화 추가 (Option A) | api 모듈 | hotfix · 단일 파일 · 단일 라인 추가 |

### 9-2. architect 결정 의제 (P1)

| ID | 의제 | 결정 의제 |
|---|---|---|
| **P1-1** | ADR 0037 Bucket4j 실 구현 + KisRateLimiterPort 합류 | (a) Bucket4j-Redis 분산 모드 vs Resilience4j RateLimiter (b) share 분할 vs single distributed bucket (c) Circuit Breaker spec |
| **P1-2** | EGW00201 / HTTP 429 명시 backoff + 재시도 매트릭스 | P1-1 합류 의제 동시 결정 |

### 9-3. docs 정합 (P2)

| ID | 의제 | 영역 |
|---|---|---|
| **P2-1** | spec 표기 drift "20 req/s" → "10 req/s" replace_all + sleep 마진 100ms 의제 | docs + KDoc + sleep interval |

### 9-4. 후속 cycle informational

- backlog §62 onReconnect throttle 60s 1회 cap (P1-1 합류 의제 후 검토)
- consumer/batch KisInstrumentRaw drift audit
- instrument 동기화 우선순위 (UN/J 안전 기본값 KOSPI cover 누락 risk)
- CandleRefreshService → KisRestPort 위임 (Option B — T-CC Phase 3 합류 의제)
- STOMP latestBoundaryCandle interval header 명시 (frontend·chart-curator 협업 의제)

---

## 10. 영역 외 침범 0 확인

- frontend·LWC dispose race → chart-curator 영역
- consumer aggregation·backfill 워커 자체 → backend-core/chart-curator 영역
- ADR 0037 결정 — architect 영역
- 본 보고서는 market-data 영역 (KIS REST/WS adapter · 뉴스 · 공시 · rate limit) audit 결과만 발행

---

**ENDS**

작성: market-data sub-agent
발행: 2026-05-19 KST
