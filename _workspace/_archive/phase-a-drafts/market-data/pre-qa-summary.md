# market-data 모듈 — Phase B-2 pre-QA 작업 요약

**시점:** 2026-04-25 17:20 KST · **상태:** Phase C(QA) 검수 직전
**브랜치:** `agent/market-data/kis-dart-news-adapters` · **PR:** #6 (base=qa)
**에이전트:** market-data · **스킬:** magicjar-kis-openapi

## 무엇을 작업했나

| 영역 | 산출물 |
|------|-------|
| KIS REST | `KisProperties` · `KisTokenStore`(23h TTL) · `KisRestClient` · `KisQuoteApi`(현재가/일봉/분봉) · `KisOrderApi`(매수/매도, mock/실전 trId 분기) |
| 안정성 | `KisRateLimiter`(Bucket4j 초당 20 call) + Resilience4j `@CircuitBreaker @Retry @TimeLimiter` |
| KIS WebSocket | `KisWsClient`(재연결 백오프 1→2→5s) · `KisWsEvents`(체결/호가 → `RealtimeQuoteEvent` 발행) |
| DART | `DartProperties` · `DartClient` (키 없으면 emptyList 폴백) |
| 뉴스 | `NaverNewsRssClient`(RSS 수집) · `NewsCollectorScheduler`(`@Scheduled fixedDelay=5min`) |
| 영속 | 3 JPA(`CandleJpa`, `InstrumentJpa`, `NewsJpa`) + 3 Spring Data + 3 Adapter(`Candle/Instrument/News RepositoryAdapter`) |
| BrokerGateway | `KisBrokerGateway`(REST/WS 래핑) + `FakeBrokerGateway`(`@Profile("backtest")`, 샘플 JSON 응답) |
| Flyway | `V1__market_data.sql` (instrument, candle, news_item + 인덱스) |
| 보안 | appkey/appsecret 로그 마스킹 (Logback converter 활용) |
| 테스트 | `KisTokenStoreTest` · `NaverNewsRssClientTest` · `CandleRepositoryAdapterIT` (Testcontainers Postgres) |

## 빌드 상태
- `./gradlew :market-data:build` → **BUILD SUCCESSFUL**
- `./gradlew ktlintCheck` → **BUILD SUCCESSFUL**

## 의존 / 경계
- core 포트 구현: `CandleRepository`, `InstrumentCatalog`, `NewsRepository`, `BrokerGateway`
- 외부 어댑터 모듈 직접 import 없음

## QA 체크 포인트
- API 키 없는 환경에서 `:market-data:test` 통과 (FakeBrokerGateway, RSS 폴백)
- KIS trId 매핑(매수 `TTTC0802U`/`VTTC0802U`, 매도 `TTTC0801U`/`VTTC0801U`) — 실계좌/모의 분기 정확성
- Resilience4j CircuitBreaker가 주문 경로에 적용됐는지 어노테이션 확인
- JPA 엔티티에 `kotlin-jpa` 플러그인 적용 확인 (no-arg)
- 로그에 appkey/appsecret 평문 노출 없음

## 알려진 TODO (후속)
- KIS hashkey/WS approval_key 자동 발급 (현재 임시)
- H0STASP0 호가 메시지 파서 정밀화
- Redis Stream 캐시 (현재 ConcurrentHashMap)
- 캔들 테이블 선언적 파티셔닝(현재 DDL 주석만)
