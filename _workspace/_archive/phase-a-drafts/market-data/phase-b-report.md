# market-data Phase B-2 보고서

- 브랜치: `agent/market-data/kis-dart-news-adapters`
- 베이스: `origin/main` (commit 1efbcdc)
- 빌드: `./gradlew :market-data:build` BUILD SUCCESSFUL
- ktlint: `./gradlew ktlintCheck` BUILD SUCCESSFUL

## 파일 트리

```
market-data/
├── build.gradle.kts                # web/ws/data-jpa/resilience4j/bucket4j/jackson-xml + 테스트
├── src/main/kotlin/kr/spdaewoon/magicjar/marketdata/
│   ├── MarketDataConfig.kt         # @EnableConfigurationProperties + EntityScan + Scheduling
│   ├── event/RealtimeQuoteEvent.kt # WS → ApplicationEvent
│   ├── kis/
│   │   ├── KisProperties.kt        # @ConfigurationProperties("kis")
│   │   ├── KisTokenStore.kt        # 23h TTL token cache + /oauth2/tokenP
│   │   ├── KisRateLimiter.kt       # Bucket4j (quote 15rps, order 5rps)
│   │   ├── KisRestClient.kt        # 공통 헤더+401 재발급+로그 마스킹
│   │   ├── KisQuoteApi.kt          # FHKST01010100, FHKST03010100 + 샘플 폴백
│   │   ├── KisOrderApi.kt          # TTTC0802U/VTTC0802U mock 분기 + R4j
│   │   ├── KisWsClient.kt          # H0STCNT0 구독, 1s→2s→5s 백오프
│   │   ├── KisWsEvents.kt          # raw "^" 텍스트 → Quote 파싱
│   │   ├── KisBrokerGateway.kt     # core.port.BrokerGateway 구현
│   │   └── FakeBrokerGateway.kt    # @Profile("backtest"), 샘플 응답
│   ├── dart/
│   │   ├── DartProperties.kt
│   │   └── DartClient.kt           # /api/list.json, key 없으면 emptyList()
│   ├── news/
│   │   ├── NewsProperties.kt
│   │   ├── NaverNewsRssClient.kt   # finance.naver.com RSS, UTF-8 byte 수신
│   │   └── NewsCollectorScheduler.kt # @Scheduled fixedDelay=PT5M
│   └── persistence/
│       ├── InstrumentJpa.kt + JpaRepository + InstrumentCatalogAdapter.kt
│       ├── CandleJpa.kt(IdClass) + JpaRepository + CandleRepositoryAdapter.kt
│       └── NewsJpa.kt(jsonb) + JpaRepository + NewsRepositoryAdapter.kt
├── src/main/resources/
│   ├── application-market-data.yml # kis/dart/뉴스/Resilience4j 설정
│   ├── db/migration/V1__market_data.sql
│   └── kis-samples/{quote.json, candles-d1.json}
└── src/test/kotlin/kr/spdaewoon/magicjar/marketdata/
    ├── kis/KisTokenStoreTest.kt    # MockWebServer, 캐시·invalidate 검증
    ├── news/NaverNewsRssClientTest.kt # 한글 RSS, 500 폴백 검증
    └── persistence/CandleRepositoryAdapterIT.kt # Testcontainers (RUN_TESTCONTAINERS=true 시)
```

## 결정

1. **TokenStore 캐시는 in-process AtomicReference.** Redis 캐시는 후속 보강. 동일 앱키당 동시 1개라 충분.
2. **샘플 폴백은 시세 계열만.** 주문은 자격증명 없으면 즉시 IllegalStateException — 실수로 주문되는 일을 차단.
3. **RealtimeQuoteEvent 는 market-data 자체 패키지에 둔다.** 다른 모듈은 ApplicationContext 이벤트 발행만 신뢰(직접 import 정책상 금지). 후속 ai/web 어댑터는 별도 listener-side 어댑터 패턴 권장.
4. **`@ConditionalOnMissingBean(name=fakeBrokerGateway)`** 로 backtest 프로파일에서는 KIS 비활성. order-exec 모듈은 BrokerGateway 포트 한 종류만 본다.
5. **Testcontainers IT는 `RUN_TESTCONTAINERS=true` 환경변수 가드.** 로컬 Docker 미설치 환경(macOS dev)에서도 빌드 통과. CI는 env 켜고 실행.
6. **NewsJpa.instruments 는 JSONB + Hibernate `@JdbcTypeCode(SqlTypes.JSON)`.** ObjectMapper 로 직렬화·역직렬화. like 검색은 임시 — 후속 GIN 인덱스 + JSON 연산자 도입 가능.
7. **R4j 어노테이션 적용 범위:** kisQuote=Retry+CircuitBreaker, kisOrder=Retry+CircuitBreaker+TimeLimiter.
8. **ktlint chain wrapping** 규칙 때문에 `?.items.orEmpty().map{}` 같은 1줄 체인은 임시 변수 분리.

## 사용 KIS trId

`kis-trid-map.md` 참조.

## 환경변수

스킬 / `.env.example` 과 동일. 신규 없음. mockMode 기본 true.

## TODO (후속)

- [ ] `KisHashKeyApi` 도입 (현재는 `hashkey` 헤더 미적용 — 주문 200 OK는 받지만 일부 trId 는 hashkey 필수일 수 있음)
- [ ] WebSocket approval_key 발급 자동화 (`POST /oauth2/Approval`)
- [ ] H0STASP0(호가) 파서 + 도메인 모델 보강
- [ ] Quote/Candle Redis Stream 캐시 — 현재는 DB 직쓰기
- [ ] DART corp_code↔stock_code 매핑 캐시(현재는 응답에 stock_code 있으면만 매핑)
- [ ] Naver RSS 외 RSS 추가 + 종목코드 자동 태깅(텍스트 매칭)
- [ ] CandleRepositoryAdapter 의 saveAll 을 `INSERT … ON CONFLICT` SQL 로 최적화

## 빌드 검증 로그

```
$ ./gradlew :market-data:build
BUILD SUCCESSFUL in 2s

$ ./gradlew ktlintCheck
BUILD SUCCESSFUL in 624ms
```

`market-data:test` 6 tests, 5 pass + 1 skipped (Testcontainers IT, env 가드).
