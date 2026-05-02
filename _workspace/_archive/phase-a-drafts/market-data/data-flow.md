# market-data 데이터 흐름

## 시세 수집 → 저장 → 공급

```
strategy-engine
   │ (CandleRepository.find / BrokerGateway.fetchCandles)
   ▼
core.port              ←─────────── RealtimeQuoteEvent (ApplicationEvent)
   │                                 ▲
   ▼                                 │
CandleRepositoryAdapter         KisWsClient (H0STCNT0)
   │                                 ▲
   ▼                                 │ (재연결 백오프 1s/2s/5s)
PostgreSQL.candle         ←  KIS WebSocket
   ▲                                 ▲
   │                                 │
   │                            approval_key (수동 주입)
   │
   │ (저장 시점: scheduler 또는 명시 호출)
   │
KisQuoteApi (FHKST03010100)
   ▲
   │  Bucket4j(quote 15rps) + R4j(Retry/CB)
   │
   └─── KisRestClient ── KisTokenStore (oauth2/tokenP, 23h)
```

## 뉴스/공시 수집

```
NewsCollectorScheduler (5min)
   │
   ├──► NaverNewsRssClient.fetchAll() ── HTTP GET (UA 명시)
   │       │
   │       ▼
   │     NewsItem(source=NAVER_RSS)
   │
   └──► (TODO) DartCollectorScheduler → DartClient.listFilings()
           │
           ▼
         NewsItem(source=DART)

   ▼
NewsRepository.save(items)   ── core.port
   ▼
NewsRepositoryAdapter
   ▼
PostgreSQL.news_item (jsonb instruments)
```

## 주문 (참고용 — order-exec 모듈에서 호출)

```
order-exec.RealOrderSender
   │ (BrokerGateway.placeOrder)
   ▼
core.port.BrokerGateway
   │
   ▼
KisBrokerGateway → KisOrderApi
   │  Bucket4j(order 5rps) + R4j(Retry/CB/TimeLimiter)
   ▼
KIS REST /uapi/domestic-stock/v1/trading/order-cash (TTTC/VTTC)
   ▼
OrderResult (PENDING|REJECTED, rejectReason)
```

## 백테스트 모드 (`@Profile("backtest")`)

- KisBrokerGateway 비활성, `FakeBrokerGateway` 가 우선.
- 캔들/시세는 `classpath:kis-samples/*.json` 에서 로드.
- 주문은 즉시 FILLED, avgFillPrice = 입력 가격 또는 샘플 quote 가격.
- DB(`candle`/`instrument`/`news_item`) 사용은 동일.
