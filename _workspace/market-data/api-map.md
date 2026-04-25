# market-data API 맵

## 외부 API → 내부 도메인 매핑

| 외부 API | 인증 | 호출 빈도 제한 | 내부 도메인 | 어댑터 |
|----------|------|---------------|------------|--------|
| KIS REST `/oauth2/tokenP` | appKey/appSecret | 토큰당 24h 1회 | (내부 토큰 캐시) | `KisTokenStore` |
| KIS REST 시세 | Bearer + appkey/appsecret/tr_id | 15회/초(공식 20) | `Quote`, `Candle` | `KisQuoteApi` |
| KIS REST 주문 | Bearer + tr_id (mock 분기) | 5회/초 (별도 버킷) | `Order` → `OrderResult` | `KisOrderApi` |
| KIS WebSocket H0STCNT0 | approval_key | 동시 30종목(40 한도-버퍼) | `Quote` (RealtimeQuoteEvent) | `KisWsClient` |
| DART `/api/list.json` | crtfc_key | 1초당 5회 권장 | `NewsItem` (source=DART) | `DartClient` |
| 네이버 금융 RSS | (User-Agent 명시) | 5분 주기 | `NewsItem` (source=NAVER_RSS) | `NaverNewsRssClient` |

## 내부 포트 ↔ 어댑터

| core.port | market-data 어댑터 | 저장소 | 비고 |
|-----------|--------------------|--------|------|
| `CandleRepository` | `CandleRepositoryAdapter` | PostgreSQL `candle` | upsert 의미론(존재시 OHLCV·tradeCount 갱신) |
| `InstrumentCatalog` | `InstrumentCatalogAdapter` | PostgreSQL `instrument` | upsert 시 updated_at 갱신 |
| `NewsRepository` | `NewsRepositoryAdapter` | PostgreSQL `news_item` | jsonb instruments + LIKE 검색 |
| `BrokerGateway` | `KisBrokerGateway` (default) / `FakeBrokerGateway` (`@Profile("backtest")`) | KIS REST | 백테스트는 `kis-samples` 사용 |

## 레이트리밋

- quote 버킷: 15 token/sec (Bucket4j classic, intervally refill)
- order 버킷: 5 token/sec
- 토큰 부족 시 최대 2초 대기, 그 이상은 IllegalStateException + WARN 로그.

## Resilience4j

- `kisQuote`: CircuitBreaker(20 sliding/50% threshold/30s open) + Retry(3, 200ms exp×2)
- `kisOrder`: CircuitBreaker(10/40%/60s) + Retry(2) + TimeLimiter(5s)

## 폴백·실패 정책

- `KIS appKey/appSecret 비어있음` → 시세 호출은 `kis-samples/*` 폴백, 주문 호출은 즉시 IllegalStateException.
- `DART apiKey 비어있음` → `listFilings()` 빈 리스트.
- `RSS 네트워크 실패` → 빈 리스트 + WARN.
- `KIS 401` → 토큰 무효화 + 1회 재시도.
- `WS 끊김` → 1s → 2s → 5s … 백오프.
