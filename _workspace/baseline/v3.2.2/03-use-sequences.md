# v3.2.2 사용자 유즈 시퀀스 + KIS-first raw 출처

> **작성자**: market-data (bot+market-data@magicjar.local)
> **작성일**: 2026-05-11
> **기준 시점**: v3.2.2 — KIS-only 데이터 정책 + NXT/통합거래 cover + 1m 합산 가공 트랙 가동 (PR #294~#305)
> **KIS-first 의무**: 헌법 §3 정합 — 자체 추론 0, 모든 endpoint·tr_id·필드는 raw 출처 인용 의무
> **v3.2.0 대비 변경**: Yahoo Finance 전면 폐기(ADR 0057) · NXT 08:00~20:00 cover(ADR 0063) · 1m 합산 가공 A-1 백엔드 precomputed(ADR 0062) · PINGPONG JSON 분기 · KIS WS SSL JDK 핫픽스

---

## raw 출처 인용 인덱스

| # | 출처 | raw URL | 사용 시나리오 |
|---|---|---|---|
| R1 | KIS 종목마스터 Python 파서(KOSPI) | `https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/kis_kospi_code_mst.py` | 시나리오 2 — 73필드 |
| R2 | KIS 종목마스터 Python 파서(KOSDAQ) | `https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/kis_kosdaq_code_mst.py` | 시나리오 2 — 67필드 |
| R3 | KIS 종목마스터 헤더(KOSPI) | `https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/종목마스터정보(코스피).h` | 시나리오 2 — 73필드 바이트 레이아웃 |
| R4 | KIS WS auth + URL | `https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/kis_auth.py` | 시나리오 3 — WS URL · PINGPONG 처리 |
| R5 | KIS WS 체결가 파서 (ccnl_krx) | `https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py` | 시나리오 3 — H0STCNT0 44 cols |
| R6 | KIS REST 현재가 (FHKST01010100) | `https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_price/inquire_price.py` | 시나리오 4 |
| R7 | KIS REST 일봉 (FHKST03010100) | `https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py` | 시나리오 4·6 |
| R8 | KIS REST 1m 분봉 (FHKST03010230) | `https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py` | 시나리오 5·6 — UN 통합 + NXT cover |
| R9 | KIS Strategy Builder indicators.py | `https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/indicators.py` | 시나리오 7 — 80지표 |
| R10 | KIS Strategy Builder candlestick.py | `https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/candlestick.py` | 시나리오 7 — 57패턴 |
| R11 | KIS Strategy Builder DSL parser.py | `https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/strategy_core/dsl/parser.py` | 시나리오 7 — DSL TOKEN_PATTERNS |

**검증 보고서** (헌법 §3 §3-3 의무 산출물):
- PR-21 `_workspace/specs/pr-21-kis-header-raw-verification.md` — 종목마스터 73필드 KOSPI + 67필드 KOSDAQ
- PR-38 `_workspace/specs/pr-38-kis-pattern-raw-verification.md` — candlestick.py 755 lines + 57 매핑
- PR-35 `_workspace/specs/pr-35-kis-dsl-raw-verification.md` — parser.py 664 lines + TOKEN_PATTERNS LOCK

---

## 시나리오 1: 사용자 셋업 (Onboarding)

**목적**: 최초 진입 시 KIS 앱키·시크릿 등록 → onboarding guard 통과

**관련 PR/ADR**: PR-29 T-W1 R1 · ADR 0046 (KIS credential AES-GCM 암호화) · ADR 0057 (KIS-only — prod credential 단일 채널)

```mermaid
sequenceDiagram
    actor User as 사용자 (최고관리자)
    participant FE as Frontend (React 19)
    participant API as api 모듈 (Spring Boot)
    participant Cipher as AesGcmCipher
    participant DB as PostgreSQL (system_config)

    User->>FE: SettingsModal 열기 (앱키·시크릿 입력)
    FE->>API: POST /api/system-config/kis-credentials
    note over API: OnboardingGuard — system_config 테이블 빈 값 확인
    API->>Cipher: encrypt(appKey) + encrypt(appSecret)
    note over Cipher: AES-256-GCM · IV 12byte · tag 16byte (ADR 0046)
    Cipher-->>API: ciphertext (Base64)
    API->>DB: INSERT INTO system_config (key, value_enc, updated_at)
    DB-->>API: OK
    API-->>FE: 200 { onboarding: true }
    FE-->>User: "설정 완료" 토스트 → 홈 화면 진입 허용
```

**주요 제약**:
- prod(실전) credential 단일 채널 — 정보 수집 API는 모두 `AccountType.REAL` (ADR 0057 + 사용자 메모리 `feedback_kis_key_realonly_for_data.md`)
- 실계좌 이중 게이트: `MAGICJAR_MODE=REAL` + `MAGICJAR_ALLOW_REAL=true` 둘 다여야 실주문 (CLAUDE.md §4)
- 앱키는 화면에서 마스킹 표시, 응답에 평문 재출력 금지

---

## 시나리오 2: 종목 검색 + Watchlist 등록

**목적**: 사용자가 종목 검색 후 Watchlist 추가 → KIS WS 구독 cold-start 시작

**관련 PR/ADR**: PR-29 T-W1 · ADR 0028 (Watchlist Ingestion Worker) · ADR 0048 (WS 41 slot) · ADR 0057 (KIS-only cold-start)

**KIS-first raw 출처**:
- 종목마스터: `kis_kospi_code_mst.py` (R1) — Part1 3필드 + Part2 70필드 = 73필드 (검증: PR-21)
- 종목마스터: `kis_kosdaq_code_mst.py` (R2) — Part1 3필드 + Part2 64필드 = 67필드

```mermaid
sequenceDiagram
    actor User as 사용자
    participant FE as Frontend
    participant API as api 모듈
    participant InstrDB as InstrumentJpaRepo (PostgreSQL)
    participant Kafka as Kafka
    participant Consumer as consumer 모듈
    participant KIS_WS as KIS WebSocket
    participant ColdStart as ColdStartExecutor

    User->>FE: 검색창에 "삼성전자" 입력
    FE->>API: GET /api/instruments/search?q=삼성전자
    API->>InstrDB: SELECT * FROM instrument WHERE name LIKE ?
    alt DB에 종목 존재
        InstrDB-->>API: InstrumentDto (code=005930, name=삼성전자)
    else DB 미존재 → KIS 종목마스터
        note over API: 73필드 KOSPI / 67필드 KOSDAQ<br/>raw출처: kis_kospi_code_mst.py (R1·R3)
        API->>InstrDB: INSERT INTO instrument (code, name, market, ...)
    end
    API-->>FE: [{ code, name, market }]

    User->>FE: "삼성전자" → [+ 관심종목] 클릭
    FE->>API: POST /api/watchlists { code: "005930" }
    API->>InstrDB: INSERT INTO watchlist (instrument_code, created_at)
    API->>Kafka: watchlist.changed.v1 { event: ADDED, code: "005930" }

    Kafka->>Consumer: consume watchlist.changed.v1
    Consumer->>KIS_WS: connect() — SSL JDK provider (PR #279 hotfix)
    note over KIS_WS: wss://ops.koreainvestment.com:21000 (prod)<br/>raw: kis_auth.py my_url_ws (R4)
    Consumer->>KIS_WS: subscribe H0STCNT0 { tr_key: "005930" }
    note over KIS_WS: ADR 0048 — 최대 30 slot<br/>raw: domestic_stock_functions_ws.py (R5)
    KIS_WS-->>Consumer: { tr_id: "H0STCNT0", rt_cd: "0" } 구독 성공

    Consumer->>ColdStart: submit(code="005930") [비동기]
    note over ColdStart: cold-start 5 interval:<br/>1m(FHKST03010230) + 1d·1wk·1mo·1y(FHKST03010100)<br/>5m·15m·30m·90m·1h → 1m 합산 트리거

    API-->>FE: 200 { watchlist: [...] }
    FE-->>User: Watchlist에 "삼성전자" 추가됨
```

**WS 슬롯 정책 (ADR 0048)**:
- H0STCNI0 체결통보: 1 slot (의무 reserve)
- H0STCNT0 체결가: 최대 30 slot
- H0STASP0 호가: 최대 10 slot (LRU)
- 총합: 1 + 30 + 10 = 41 (KIS 한도 정합)

**KIS WS URL (raw출처: kis_auth.py R4)**:
- 실전: `wss://ops.koreainvestment.com:21000`
- 모의(VTS): `wss://ops.koreainvestment.com:31000`

**v3.2.2 변경점**: `KisWebSocketBridge` 생성자 주입 전환 + `WebClientConfig.kisWebSocketClient()` 빈 신설 (SSL JDK provider + TrustManagerFactory — PR #279 hotfix). `WatchlistIngestionWorker.onStartup()`에 `connect()` 선행 호출 의무 추가 (PR #282).

---

## 시나리오 3: 실시간 시세 구독

**목적**: Frontend STOMP 연결 → KIS WS H0STCNT0 체결가 수신 → Kafka → STOMP fanout

**관련 PR/ADR**: PR-29 T-W2 · ADR 0028 · ADR 0037 (Rate Limit) · ADR 0048 (WS 41 slot) · ADR 0063 (NXT 08:00~20:00)

**KIS-first raw 출처 — WS tr_id LOCK**:

| tr_id | 용도 | raw 출처 | slot |
|---|---|---|---|
| `H0STCNT0` | 실시간 체결가 (틱) 44 cols | `domestic_stock_functions_ws.py` ccnl_krx() (R5) | 최대 30 |
| `H0STASP0` | 실시간 호가 10단계 54 cols | `domestic_stock_functions_ws.py` asking_price_krx() (R5) | 최대 10 (LRU) |
| `H0STCNI0` | 체결통보 (사용자 본인 주문) | `kis_auth.py` (R4) | 1 (reserve) |

**H0STCNT0 필드 인덱스 (raw출처: domestic_stock_functions_ws.py ccnl_krx() R5 — 44 cols)**:

| 인덱스 | 필드명 | 용도 |
|---|---|---|
| `[0]` | MKSC_SHRN_ISCD | 종목코드 |
| `[1]` | STCK_CNTG_HOUR | 체결시각 (HHMMSS) |
| `[2]` | STCK_PRPR | 현재가(체결가) |
| `[3]` | PRDY_VRSS_SIGN | 전일 대비 부호 ("1"상한·"2"상승·"3"보합·"4"하한·"5"하락) |
| `[4]` | PRDY_VRSS | 전일 대비 등락가 |
| `[5]` | PRDY_CTRT | 전일 대비 등락률 |
| `[12]` | CNTG_VOL | 체결거래량 |
| `[13]` | ACML_VOL | 누적거래량 |
| `[33]` | BSOP_DATE | 영업일자 (YYYYMMDD) |

```mermaid
sequenceDiagram
    actor User as 사용자
    participant FE as Frontend (STOMP client)
    participant API as api 모듈 (STOMP broker)
    participant Consumer as consumer 모듈
    participant KIS_WS as KIS WebSocket<br/>(H0STCNT0 체결가)
    participant Redis as Redis<br/>(quote:last:{code})
    participant Kafka as Kafka<br/>(market.tick.v1)

    User->>FE: Watchlist 화면 열기
    FE->>API: STOMP CONNECT (WebSocket)
    FE->>API: STOMP SUBSCRIBE /topic/market.tick.{code}

    Note over Consumer,KIS_WS: H0STCNT0 이미 구독 중 (WS connect 완료)
    KIS_WS->>Consumer: "0|H0STCNT0|001|{44 cols 캐럿 구분}"
    note over Consumer: raw: domestic_stock_functions_ws.py ccnl_krx() (R5)<br/>NXT 08:00~20:00 cover (ADR 0063 채택)

    Consumer->>Consumer: KisWsMessageParser.parseH0Stcnt0()<br/>IDX_PRICE=[2], IDX_PRDY_VRSS_SIGN=[3],<br/>IDX_PRDY_VRSS=[4], IDX_PRDY_CTRT=[5],<br/>IDX_CNTG_VOL=[12], IDX_BSOP_DATE=[33]
    note over Consumer: PINGPONG JSON 분기 추가 (PR #287):<br/>header.tr_id=="PINGPONG" → pong(raw) echo<br/>raw: kis_auth.py rdic["header"]["tr_id"] (R4)

    Consumer->>Redis: SET quote:last:{code} { price, change, changeRate, changeSign, volume, time }
    Consumer->>Kafka: produce market.tick.v1 { TickEvent }
    note over TickEvent: priceChange·changeRate·changeSign 등락 필드 포함<br/>(PR #289 — PRDY_VRSS·PRDY_CTRT·PRDY_VRSS_SIGN forward)

    Kafka->>Consumer: consume market.tick.v1 (MarketTickStompForwarder)
    Consumer->>API: /topic/market.tick.{code} STOMP publish
    API->>FE: STOMP MESSAGE { price, priceChange, changeRate, changeSign, volume, time }
    FE-->>User: Watchlist 가격·등락 실시간 동기 업데이트

    Note over KIS_WS,Consumer: PINGPONG heartbeat (kis_auth.py R4)<br/>30초 주기 — trace 레벨 로깅 (운영 노이즈 회피)
```

**KIS WS subscribe 메시지 형식 (raw출처: kis_auth.py data_fetch() R4)**:
```json
{
  "header": {
    "approval_key": "<from /oauth2/Approval>",
    "tr_type": "1",
    "custtype": "P"
  },
  "body": {
    "input": {
      "tr_id": "H0STCNT0",
      "tr_key": "005930"
    }
  }
}
```

**WS 끊김 처리 (ADR 0048 §2-4)**:
- SSL JDK provider 강제 (PR #279 — TLSv1.2/1.3, TrustManagerFactory, `@Qualifier("kisWebSocketClient")`)
- 재연결 지수 백오프: 0s·2s·5s·10s·30s
- 3회 실패 시: WatchlistPollingWorker REST fallback (5초 주기, ADR 0014)
- 재연결 성공 시: 전체 구독 목록 재subscribe

---

## 시나리오 4: 종목 상세 진입

**목적**: SymbolDetail 진입 → 현재가 + 차트(1d·1wk·1mo·1y) + H0STASP0 호가 구독

**관련 PR/ADR**: PR-29 T-S1 R2 · ADR 0019 (캔들 저장) · ADR 0029 (당일 Redis 캐시) · ADR 0057 (KIS-only)

**KIS-first raw 출처 — REST endpoint LOCK**:

| endpoint | tr_id | 용도 | raw 출처 |
|---|---|---|---|
| `/uapi/domestic-stock/v1/quotations/inquire-price` | `FHKST01010100` | 현재가·PER·PBR·EPS·BPS·시총·업종 | R6 |
| `/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice` | `FHKST03010100` | 일봉/주봉/월봉/년봉 (fid_cond_mrkt_div_code="UN") | R7 |

```mermaid
sequenceDiagram
    actor User as 사용자
    participant FE as Frontend
    participant API as api 모듈
    participant Redis as Redis Cache
    participant KIS_REST as KIS REST API
    participant DB as PostgreSQL (candle)
    participant Consumer as consumer 모듈
    participant KIS_WS as KIS WebSocket<br/>(H0STASP0 호가)

    User->>FE: 종목 "삼성전자(005930)" 클릭
    FE->>API: GET /api/symbols/005930

    API->>Redis: GET quote:last:005930 (실시간 체결가 캐시)
    alt Redis hit
        Redis-->>API: { price, priceChange, changeRate, changeSign }
    else Redis miss → KIS REST fallback
        API->>KIS_REST: GET /uapi/domestic-stock/v1/quotations/inquire-price<br/>TR_ID: FHKST01010100 · prod credential (ADR 0057)<br/>raw: inquire_price.py (R6)
        KIS_REST-->>API: { stck_prpr, per, pbr, eps, bps, hts_avls, ... }
    end
    API-->>FE: SymbolMetaDto { code, name, price, per, pbr, ... }

    FE->>API: GET /api/symbols/005930/candles?interval=1d&limit=200
    API->>DB: SELECT * FROM candle WHERE code=? AND interval='1d' ORDER BY ts DESC LIMIT 200
    alt DB에 캔들 존재 (cold-start 적재 완료)
        DB-->>API: CandleEntity[]
    else DB 미존재 → KIS REST backfill
        API->>KIS_REST: GET /uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice<br/>TR_ID: FHKST03010100 · fid_cond_mrkt_div_code="UN" (ADR 0063)<br/>fid_period_div_code="D" · prod credential (ADR 0057)<br/>raw: inquire_daily_itemchartprice.py (R7)
        KIS_REST-->>API: { output2[]: [{ stck_bsop_date, stck_oprc, stck_hgpr, stck_lwpr, stck_clpr, acml_vol }] }
        API->>DB: INSERT INTO candle (code, interval='1d', ts, open, high, low, close, volume, source='KIS')
    end
    API-->>FE: CandleDto[]

    FE->>API: POST /api/symbols/005930/orderbook/subscribe
    API->>Consumer: 호가 구독 요청 (OrderbookSubscriptionRouter)
    Consumer->>KIS_WS: subscribe H0STASP0 { tr_key: "005930" }
    note over KIS_WS: raw: domestic_stock_functions_ws.py asking_price_krx() (R5)<br/>54 cols, ADR 0048 — 최대 10 slot LRU
    KIS_WS-->>Consumer: 호가 구독 성공
    API-->>FE: 200 OK (호가 구독 완료)
    FE-->>User: 차트 + 호가창 렌더링
```

**Symbol 이탈 시**: `POST /api/symbols/{code}/orderbook/unsubscribe` → H0STASP0 slot 회수

---

## 시나리오 5: 1m 분봉 적재 (KIS-first FHKST03010230)

**목적**: CandleBootstrapRunner 부팅 감지 → ColdStartExecutor → KisRestCandleAdapter 1m 페이지네이션 backfill

**관련 PR/ADR**: ADR 0057 (KIS-only) · ADR 0063 (NXT UN 통합 + 08:00~20:00) · ADR 0037 (Rate Limit)

**KIS-first raw 출처 — FHKST03010230**:
- raw URL: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py`
- endpoint: `/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice`
- TR_ID: `FHKST03010230`
- fid_cond_mrkt_div_code: `"UN"` (KRX+NXT 통합 — ADR 0063 채택 · raw: `J:KRX, NX:NXT, UN:통합`)
- fid_input_hour_1: 시작 시각 `"200000"` (NXT after-hour 마감 20:00 KST — ADR 0063)
- 응답 최대 120건/call → 페이지네이션 백워크 (응답 최솟값 ts −1분 → 재호출)
- 일자당 maxPagesPerDay=8 (12h×60m=720건 / 120건 = 6pages + 마진 2 = 8)

**1m 응답 필드 (raw 출처 R8 정합)**:
- `stck_bsop_date` — 영업일자 (YYYYMMDD)
- `stck_cntg_hour` — 체결시각 (HHMMSS)
- `stck_oprc` — 시가
- `stck_hgpr` — 고가
- `stck_lwpr` — 저가
- `stck_prpr` — 현재가(종가)
- `cntg_vol` — 체결거래량

```mermaid
sequenceDiagram
    participant Bootstrap as CandleBootstrapRunner<br/>(@Order(10))
    participant ColdExec as ColdStartExecutor
    participant Adapter as KisRestCandleAdapter
    participant KIS as KIS REST<br/>(FHKST03010230)
    participant DB as PostgreSQL (candle)
    participant Agg as AggregateBackfillRunner

    Bootstrap->>Bootstrap: run() — watchlist 종목 전체 순회
    note over Bootstrap: 1d count==0 OR 1m count < 700(ADR 0063 threshold) → cold-start<br/>1m 충분 + 5m==0 → aggregateBackfillRunner.backfill() 직접 호출

    Bootstrap->>ColdExec: submit("005930") [비동기]
    ColdExec->>Adapter: fetchCandles(code="005930", interval=ONE_MIN, count=1)
    note over Adapter: TR_ID: FHKST03010230<br/>fid_cond_mrkt_div_code="UN" (ADR 0063)<br/>fid_input_hour_1="200000" → 백워크 시작<br/>raw: R8

    loop 30 거래일 × 일자당 최대 8 pages
        Adapter->>KIS: GET /inquire-time-dailychartprice<br/>fid_input_date_1={date} fid_input_hour_1={currentHour}
        KIS-->>Adapter: output2[]: 최대 120건 (stck_bsop_date, stck_cntg_hour, stck_prpr, ...)
        Adapter->>Adapter: 응답 최솟값 ts: 다음 페이지 시각 = minTs − 1분
        alt 응답 0건 OR 가장 이른 ts < 08:00 KST (ADR 0063 NXT 시작)
            Adapter->>Adapter: 해당 일자 종료
        else 응답 있고 일자 미완료
            note over Adapter: ADR 0037 rate limit — 50ms sleep (초당 20 req 마진)
        end
    end

    Adapter-->>ColdExec: List<Candle> (30 거래일 × ~720건/일 = ~21,600건)
    ColdExec->>DB: BATCH INSERT INTO candle (code, interval='1m', ts, ohlcv, source='KIS')<br/>ON CONFLICT DO NOTHING (멱등)
    ColdExec->>Agg: aggregateBackfillRunner.backfill("005930") [즉시 트리거]
```

**코드 경로**:
- `KisRestCandleAdapter.kt:57` — `TR_ID_1MIN = "FHKST03010230"` (inline 주석 raw URL)
- `KisRestCandleAdapter.kt:114` — `fetchOneMinCandles` — 페이지네이션 백워크 알고리즘
- `CandleBootstrapRunner.kt:57` — `MIN_1M_CANDLE_THRESHOLD = 700L` (ADR 0063 threshold 정합)

---

## 시나리오 6: 5m·15m·30m·90m·1h 합산 가공 (ADR 0062 백엔드 precomputed)

**목적**: 1m 적재 완료 직후 CandleAggregator → 5종 분봉 DB INSERT (ON CONFLICT 멱등)

**관련 PR/ADR**: ADR 0062 (A-1 백엔드 precomputed + 30 거래일 cover) · ADR 0063 (NXT boundary) · ADR 0019 (캔들 저장 정책 §2-5 revoke)

**합산 로직 (domain/CandleAggregator.kt — Pure Kotlin, Spring 의존 0)**:
- OHLCV 공식: `(first.open, max(highs), min(lows), last.close, sum(volumes))`
- boundary 정렬: KST 08:00 시작 (ADR 0063 NXT cover) — raw 출처 R8 정합

**NXT cover boundary 매트릭스 (ADR 0062 §9 정합)**:

| Interval | 기준 시각 (NXT 08:00~20:00) | bar/일 |
|---|---|---|
| 5m | 08:00·08:05·…·19:55 | 144 |
| 15m | 08:00·08:15·…·19:45 | 48 |
| 30m | 08:00·08:30·…·19:30 | 24 |
| 90m | 08:00·09:30·11:00·12:30·14:00·15:30·17:00·18:30 | 8 |
| 1h | 08:00·09:00·…·19:00 | 12 |

```mermaid
sequenceDiagram
    participant ColdExec as ColdStartExecutor
    participant Agg as AggregateBackfillRunner
    participant Domain as CandleAggregator<br/>(domain — Spring 의존 0)
    participant DB as PostgreSQL (candle)

    ColdExec->>Agg: backfill("005930")
    Agg->>DB: SELECT * FROM candle WHERE code='005930' AND interval='1m' ORDER BY ts ASC
    DB-->>Agg: List<Candle> (~21,600건 — NXT 30 거래일)

    loop 5m·15m·30m·90m·1h 5 interval
        Agg->>Domain: CandleAggregator.aggregate(oneMinCandles, targetInterval)
        note over Domain: boundary 정렬: KST 08:00 시작 (ADR 0063)<br/>OHLCV = (first.open, max.high, min.low, last.close, sum.vol)
        Domain-->>Agg: List<Candle> (aggregated)
        Agg->>DB: BATCH INSERT INTO candle (code, interval='{target}', ts, ohlcv)<br/>ON CONFLICT DO NOTHING
        DB-->>Agg: insertedCount
    end

    Agg-->>ColdExec: Map<interval, count> { "5m"->2160, "15m"->720, "30m"->360, "90m"->120, "1h"->180, ... }
    note over ColdExec: audit trail detailJson에 aggregateInserted 필드 기록
```

**장 마감 트리거 (DailyCloseFinalizer)**:
- `@Scheduled(cron = "0 0 20 * * *", zone = "Asia/Seoul")` — 20:00 KST (ADR 0063 NXT 마감 정합)
- watchlist 전 종목에 대해 `aggregateBackfillRunner.backfill(code)` 호출 → 당일 1m 추가분 합산 갱신

**DB ROW 예측 (10종목 × 30 거래일, NXT cover)**:

| Interval | rows |
|---|---|
| 5m | ~43,200 |
| 15m | ~14,400 |
| 30m | ~7,200 |
| 90m | ~2,400 |
| 1h | ~3,600 |
| **합** | **~70,800** |

---

## 시나리오 7: 장중 실시간 합산 경계 갱신

**목적**: KIS WS tick → 1m closing → boundary 검증 → Redis 5m~1h 당일 캔들 갱신

**관련 PR/ADR**: ADR 0029 (당일 진행 중 캔들 Redis 캐시) · ADR 0062 (boundary 마감 Redis 갱신) · ADR 0063 (NXT 08:00~20:00)

```mermaid
sequenceDiagram
    participant KIS_WS as KIS WebSocket<br/>(H0STCNT0)
    participant WsBridge as KisWebSocketBridge
    participant Redis as Redis
    participant MinAgg as TickToMinuteAggregator
    participant BoundAgg as CandleAggregator<br/>(boundary 검증)
    participant DB as PostgreSQL (candle)

    KIS_WS->>WsBridge: H0STCNT0 tick (NXT 08:00~20:00 KST 범위)
    WsBridge->>Redis: HSET quote:last:{code} { price, change, changeRate, ... }
    WsBridge->>Redis: 당일 1m 진행 중 OHLCV 갱신 candle:today:{code}:1m

    note over MinAgg: 1분 경계 감지 (분 단위 cron 또는 마지막 tick 확인)
    MinAgg->>Redis: candle:today:{code}:1m Hash 읽기
    MinAgg->>DB: INSERT INTO candle (code, interval='1m', ts, ohlcv) ON CONFLICT DO NOTHING

    MinAgg->>BoundAgg: boundary 검증(현재 분)
    note over BoundAgg: 5m/15m/30m/90m/1h boundary 시각인지 확인<br/>NXT 08:00~20:00 기준 (ADR 0062 §9 정합)
    alt 5m boundary (08:05, 08:10, ...)
        BoundAgg->>Redis: candle:current:{code}:5m 갱신 (직전 5건 1m 합산)
    end
    alt 1h boundary (09:00, 10:00, ...)
        BoundAgg->>Redis: candle:current:{code}:1h 갱신 (직전 60건 1m 합산)
    end
    note over DB: 장 마감 20:00 KST → DailyCloseFinalizer<br/>Redis 5m~1h 당일 행 → DB INSERT + Redis evict
```

**Redis 키 매트릭스 (ADR 0029 §2-1 + ADR 0062 §4-4 확장)**:

| 키 | 데이터 | 갱신 주기 |
|---|---|---|
| `quote:last:{code}` | 실시간 틱 (price·change·changeRate·changeSign·volume) | H0STCNT0 수신 시 |
| `candle:today:{code}:1d` | 당일 진행 중 일봉 Hash (open·high·low·close·volume) | H0STCNT0 수신 시 |
| `candle:current:{code}:5m` | 당일 진행 중 5분봉 | 5m boundary 마감 시 |
| `candle:current:{code}:15m` | 당일 진행 중 15분봉 | 15m boundary 마감 시 |
| `candle:current:{code}:30m` | 당일 진행 중 30분봉 | 30m boundary 마감 시 |
| `candle:current:{code}:90m` | 당일 진행 중 90분봉 | 90m boundary 마감 시 |
| `candle:current:{code}:1h` | 당일 진행 중 60분봉 | 1h boundary 마감 시 |

---

## 시나리오 8: 장 마감 일봉 파이널라이저

**목적**: 20:00 KST 스케줄 → Redis candle:today:{code}:1d → DB INSERT + 합산 갱신

**관련 PR/ADR**: ADR 0013 §3-6 · ADR 0063 (NXT 마감 20:00 KST) · ADR 0062 §9 (합산 flush)

```mermaid
sequenceDiagram
    participant Scheduler as @Scheduled(cron 20:00 KST)
    participant Finalizer as DailyCloseFinalizer
    participant MarketSession as MarketSessionResolver
    participant Redis as Redis
    participant DB as PostgreSQL (candle)
    participant Agg as AggregateBackfillRunner

    Scheduler->>Finalizer: finalize() trigger (20:00 KST — ADR 0063 NXT 마감 정합)
    Finalizer->>MarketSession: isBusinessDay(today)
    alt 비영업일
        MarketSession-->>Finalizer: false → skip
    else 영업일
        MarketSession-->>Finalizer: true

        Finalizer->>Redis: SCAN "candle:today:*:1d" 패턴
        Redis-->>Finalizer: [{ code: "005930", ... }, { code: "000660", ... }]

        loop 각 종목
            Finalizer->>Redis: HGETALL candle:today:{code}:1d
            note over Finalizer: Hash 타입 읽기 (opsForHash — PR #283 hotfix)<br/>KisWebSocketBridge.updateCandleToday() Hash 저장과 타입 정합
            Finalizer->>DB: INSERT INTO candle (code, interval='1d', ts, ohlcv, source='KIS_DAILY_CLOSE')<br/>ON CONFLICT DO NOTHING
        end

        Note over Finalizer: ADR 0062 §9 NXT cover — 당일 1m 추가분 합산 갱신
        loop watchlist 전 종목
            Finalizer->>Agg: backfill(code) [당일 합산 갱신]
            Agg->>DB: SELECT 1m WHERE ts >= today 08:00 KST
            Agg->>DB: UPSERT 5m·15m·30m·90m·1h 당일분
        end
    end
```

---

## v3.2.2 KIS-first 의무 준수 체크리스트

| 항목 | 상태 | 코드 경로 + raw 출처 |
|---|---|---|
| H0STCNT0 tr_id (44 cols) | LOCK | `KisWsMessageParser.kt:42` — `domestic_stock_functions_ws.py` ccnl_krx() (R5) |
| H0STCNT0 PRDY_VRSS_SIGN[3]·PRDY_VRSS[4]·PRDY_CTRT[5] | LOCK | `KisWsMessageParser.kt:149~156` — R5 인덱스 정합 (PR #289) |
| H0STASP0 tr_id (54 cols) | LOCK | `KisWsMessageParser.kt` — `domestic_stock_functions_ws.py` asking_price_krx() (R5) |
| FHKST03010230 TR_ID + endpoint | LOCK | `KisRestCandleAdapter.kt:57~58` — raw URL R8 inline 주석 의무 |
| FHKST03010230 fid_cond_mrkt_div_code="UN" | LOCK | `KisRestCandleAdapter.kt:80~83` — ADR 0063 채택 (J→UN) raw: R8 |
| FHKST03010230 fid_input_hour_1="200000" | LOCK | `KisRestCandleAdapter.kt:152` — ADR 0063 채택 (153000→200000) |
| FHKST03010230 종료조건 08:00 KST | LOCK | `KisRestCandleAdapter.kt:225` — ADR 0063 채택 (09:00→08:00) |
| FHKST03010100 TR_ID + endpoint | LOCK | `KisRestCandleAdapter.kt:53~54` — raw URL R7 inline 주석 의무 |
| FHKST03010100 fid_cond_mrkt_div_code="UN" | LOCK | `KisRestCandleAdapter.kt:82` — ADR 0063 채택 (J→UN) raw: R7 |
| FHKST03010100 FID_PERIOD_DIV_CODE 옵션(D/W/M/Y) | LOCK | `KisRestCandleAdapter.kt:65` — raw R7 fid_period_div_code D·W·M·Y |
| PINGPONG JSON 분기 | LOCK | `KisWsMessageParser.kt:90~94` — `kis_auth.py` rdic["header"]["tr_id"] (R4) |
| WS URL 실전 | LOCK | `KisWebSocketBridge.kt` — `kis_auth.py` my_url_ws (R4) |
| WS approval_key endpoint | LOCK | `/oauth2/Approval` — `kis_auth.py` auth_ws() (R4) |
| 종목마스터 73필드 raw 검증 | LOCK | PR-21 검증보고서 + `kis_kospi_code_mst.py` (R1·R3) |
| 80지표 indicators.py 1507 lines | LOCK | PR-33 구현 + KDoc inline raw URL 명시 (R9) |
| 57패턴 candlestick.py 755 lines | LOCK | PR-38 검증보고서 + PR-39 구현 (R10) |
| DSL parser.py 664 lines | LOCK | PR-35 검증보고서 + KisYamlDslParser.kt (R11) |
| 자체 추론 | **0건** | 모든 endpoint·tr_id·필드·byte offset은 raw 출처 인용 의무 (헌법 §3) |

---

## 외부 API 통합 정책 요약 (v3.2.2)

### KIS OpenAPI (유일한 데이터 출처 — ADR 0057)

| 항목 | 값 | raw 출처 |
|---|---|---|
| 기본 URL (실전) | `https://openapi.koreainvestment.com:9443` | ADR 0003 |
| OAuth 토큰 | `/oauth2/tokenP` — 24h 유효 (ADR 0038) | `kis_auth.py` (R4) |
| WS approval_key | `/oauth2/Approval` | `kis_auth.py` (R4) |
| WS URL (실전) | `wss://ops.koreainvestment.com:21000` | `kis_auth.py` my_url_ws (R4) |
| WS 한도 | 41 stream / account (ADR 0048) | ADR 0048 §1 |
| Rate Limit | 초당 20건 (50ms sleep 마진, ADR 0037) | `docs/architecture/external-api-policy.md` |
| credential | prod(실전) 단일 채널 — 정보 수집 전 API | ADR 0057 + 사용자 메모리 |
| NXT cover | `fid_cond_mrkt_div_code="UN"` + 08:00~20:00 KST | ADR 0063 채택 |

### Yahoo Finance

**v3.2.2에서 전면 폐기 (ADR 0057 — 2026-05-11 사용자 결정 "yahoo꺼 쓰지 말라니깐")**
- `YahooCandleFetcherAdapter.kt` — `@Deprecated` 마킹 (실제 삭제는 v3.3.0)
- DB Yahoo source row 전량 삭제 + KIS 재backfill 완료 (PR #295)

### DART Open API (공시)

| 항목 | 값 |
|---|---|
| 목적 | 정기보고·주요사항보고·공시 목록 수집 |
| Kafka 토픽 | `dart.disclosure.v1` (ADR 0007) |
| W3 트랙 예정 | T-SIG·T-RISK 사이클 |

### Naver 금융 뉴스

| 항목 | 값 |
|---|---|
| 방식 | RSS 기반 (robots.txt 준수) |
| User-Agent | `magicjar-news-crawler/1.0` |
| W3 트랙 예정 | T-AI 사이클 |

---

## v3.2.2 변경 이력 (v3.2.0 대비)

| 시나리오 | v3.2.0 | v3.2.2 |
|---|---|---|
| cold-start | Yahoo 7 interval + KIS fallback | KIS-only 5 interval (ADR 0057) |
| 1m 분봉 시장 코드 | `"J"` KRX 단독 | `"UN"` KRX+NXT 통합 (ADR 0063) |
| 1m 수집 시간 범위 | 09:00~15:30 (~390건/일) | 08:00~20:00 (~720건/일, ADR 0063) |
| 5m·15m·30m·90m·1h | 데이터 없음 (미구현) | 1m 합산 A-1 백엔드 precomputed (ADR 0062) |
| 장 마감 트리거 | 16:00 KST | 20:00 KST (ADR 0063 NXT 마감) |
| PINGPONG | 순수 텍스트만 | JSON `{"header":{"tr_id":"PINGPONG",...}}` 분기 추가 |
| 틱 등락 필드 | STOMP에 미포함 | PRDY_VRSS·PRDY_CTRT·PRDY_VRSS_SIGN forward |
| WS SSL | 기본 provider | JDK provider + TrustManagerFactory (PR #279) |
| threshold | 350L | 700L (ADR 0063 NXT 기준) |

---

## 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-11 | v3.2.2 baseline 03-use-sequences.md 초안 발행. 8 시나리오 + KIS-first raw 출처 11건 LOCK. Mermaid sequence diagram 8건. ADR 0057·0062·0063 신규 반영. Yahoo 폐기·NXT UN 통합·1m 합산 A-1·PINGPONG JSON·틱 등락 forward·WS SSL 핫픽스 반영. 자체 추론 0건 검증. (market-data, sonnet 4.6) |
