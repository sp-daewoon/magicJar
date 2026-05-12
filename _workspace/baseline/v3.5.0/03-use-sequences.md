# v3.5.0 사용자 유즈 시퀀스 + KIS-first raw 출처

> **작성자**: market-data (bot+market-data@magicjar.local)
> **작성일**: 2026-05-12
> **기준 시점**: v3.5.0 — T-RISK 리스크 관리 게이트 미러스톤 (ADR 0067 채택 A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1) + v3.4.0 T-SIG·T-BT 누적
> **KIS-first 의무**: 헌법 §3 정합 — 자체 추론 0, 모든 endpoint·tr_id·필드는 raw 출처 인용 의무
> **v3.4.0 대비 변경**: T-RISK Signal→Risk Gateway→Order 의도 흐름 신규 (ADR 0067 E-1·F-1) — 시나리오 13·14 신규 추가. 시나리오 1~12는 v3.4.0 baseline 03 내용 완전 유지.

---

## raw 출처 인용 인덱스

| # | 출처 | raw URL | 사용 시나리오 |
|---|---|---|---|
| R1 | KIS 종목마스터 Python 파서(KOSPI) | `https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/kis_kospi_code_mst.py` | 시나리오 2 — 73필드 |
| R2 | KIS 종목마스터 Python 파서(KOSDAQ) | `https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/kis_kosdaq_code_mst.py` | 시나리오 2 — 67필드 |
| R3 | KIS 종목마스터 헤더(KOSPI) | `https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/종목마스터정보(코스피).h` | 시나리오 2 — 73필드 바이트 레이아웃 |
| R4 | KIS WS auth + URL | `https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/kis_auth.py` | 시나리오 3·9·10 — WS URL · PINGPONG 처리 |
| R5 | KIS WS 체결가 파서 (ccnl_krx) | `https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py` | 시나리오 3·9·10·11·13 — H0STCNT0 44 cols · STCK_CNTG_HOUR[1] boundary 검사 |
| R6 | KIS REST 현재가 (FHKST01010100) | `https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_price/inquire_price.py` | 시나리오 4 |
| R7 | KIS REST 일봉 (FHKST03010100) | `https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py` | 시나리오 4·6 |
| R8 | KIS REST 1m 분봉 (FHKST03010230) | `https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py` | 시나리오 5·6 — UN 통합 + NXT cover |
| R9 | KIS Strategy Builder indicators.py | `https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/indicators.py` | 시나리오 7·11·13 — 80지표 |
| R10 | KIS Strategy Builder candlestick.py | `https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/core/candlestick.py` | 시나리오 7·11·13 — 57패턴 |
| R11 | KIS Strategy Builder DSL parser.py | `https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/strategy_core/dsl/parser.py` | 시나리오 7·11·12·13 — DSL TOKEN_PATTERNS |

**검증 보고서** (헌법 §3 §3-3 의무 산출물):
- PR-21 `_workspace/specs/pr-21-kis-header-raw-verification.md` — 종목마스터 73필드 KOSPI + 67필드 KOSDAQ
- PR-38 `_workspace/specs/pr-38-kis-pattern-raw-verification.md` — candlestick.py 755 lines + 57 매핑
- PR-35 `_workspace/specs/pr-35-kis-dsl-raw-verification.md` — parser.py 664 lines + TOKEN_PATTERNS LOCK

**v3.5.0 KIS-first 의무 (ADR 0067 정합)**:
- 시나리오 13 — T-RISK Signal→Risk Gateway 흐름: KIS API 직접 호출 0건. signal pipeline 베이스라인 (v3.4.0 시나리오 11 인용). `signal.generated.v1` Kafka listen만.
- 시나리오 14 — AutoPause 연속 BLOCKED: KIS API 직접 호출 0건. Redis counter + strategy DB 갱신 내부 흐름만.

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
| `[1]` | STCK_CNTG_HOUR | 체결시각 (HHMMSS) — **v3.3.0 ADR 0064 boundary 검사 진입 필드** |
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

## 시나리오 7: 전략 평가 (DSL·지표·패턴)

**목적**: 사용자 DSL 전략 → KIS-first 80지표·57패턴 → 시그널 평가

**관련 PR/ADR**: ADR 0022 (Strategy DSL) · ADR 0023 (Signal Engine) · ADR 0047 (Strategy eval sandbox)

**KIS-first raw 출처**:
- 80지표: `indicators.py` 1507 lines (R9) — PR-33 구현 + KDoc inline raw URL 의무
- 57패턴: `candlestick.py` 755 lines (R10) — PR-38·PR-39 구현
- DSL TOKEN_PATTERNS: `parser.py` 664 lines (R11) — PR-35 검증보고서 + KisYamlDslParser.kt LOCK

```mermaid
sequenceDiagram
    actor User as 사용자
    participant FE as Frontend (StrategyBuilderPage)
    participant API as api 모듈
    participant DSL as KisYamlDslParser
    participant Eval as StrategyEvaluatorPort
    participant Domain as KisIndicatorEngine<br/>+ KisPatternEngine

    User->>FE: DSL 조건식 편집 + 실행 버튼
    FE->>API: POST /api/strategies/evaluate { dsl: "...", code: "005930", interval: "5m" }
    API->>DSL: parse(dsl) — TOKEN_PATTERNS 664 lines (R11)
    DSL-->>API: StrategyRule (parsed)
    API->>Eval: evaluate(rule, candles) — ADR 0047 sandbox
    Eval->>Domain: computeIndicators(candles) (R9 80지표)<br/>+ detectPatterns(candles) (R10 57패턴)
    Domain-->>Eval: IndicatorResult + PatternResult
    Eval-->>API: SignalEvent { BUY/SELL/HOLD, confidence, reason }
    API-->>FE: SignalDto
    FE-->>User: 시그널 결과 표시
```

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

        Note over Finalizer,Redis: ADR 0064 §4-3 정합 — boundary 마감으로 이미 INSERT된 row는<br/>ON CONFLICT DO NOTHING (멱등 safety net 역할)
    end
```

---

## 시나리오 9: 5m boundary 마감 → 실시간 차트 갱신 (v3.3.0 신규 — ADR 0064)

**목적**: KIS WS H0STCNT0 체결 tick → 1m closing 마감 → boundary 검사 → 5m~1h Redis 갱신 + Kafka + STOMP fanout → LWC 실시간 차트 갱신

**관련 PR/ADR**: ADR 0064 (boundary fanout A-1·B-1·C-1 채택) · ADR 0062 §4-3 (boundary 마감 흐름 인용) · ADR 0063 (NXT 08:00~20:00 boundary 시각) · ADR 0029 §2-1 (Redis 5종 키 LOCK) · ADR 0029 §2-5 (STOMP 5종 토픽 LOCK)

**KIS-first raw 출처 — boundary 검사 trigger 진입점 LOCK**:
- `[1]` STCK_CNTG_HOUR — 체결시각(HHMMSS) — `domestic_stock_functions_ws.py` ccnl_krx() (R5)
  - raw URL: `https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py`
  - boundary 검사 알고리즘 진입: `KisWsMessageParser.parseH0Stcnt0()` cols[1] → `BoundaryAggregator.onTickClosed(code, tickTs)` 전달
  - v3.2.2 baseline `03-use-sequences.md §시나리오 3 R5` 인용 정합

**boundary 시각 매트릭스 (ADR 0064 §4-2 + ADR 0063 NXT cover 정합)**:

| Interval | boundary 시각 (KST) | bar/일 |
|---|---|---|
| 5m | 08:05·08:10·08:15·…·19:55·20:00 | 144 |
| 15m | 08:15·08:30·08:45·…·19:45·20:00 | 48 |
| 30m | 08:30·09:00·09:30·…·19:30·20:00 | 24 |
| 90m | 09:30·11:00·12:30·14:00·15:30·17:00·18:30·20:00 | 8 |
| 1h | 09:00·10:00·11:00·…·19:00·20:00 | 12 |

**Redis 키 매트릭스 (ADR 0029 §2-1 LOCK + ADR 0064 A-1 채택)**:

| 캐시 키 | 데이터 | 갱신 주기 | TTL/evict |
|---|---|---|---|
| `candle:current:{code}:5m` | 당일 진행 중 5m bar | 5m boundary 마감 시 1m 5건 합산 | 5m closing evict + 20:00 flush |
| `candle:current:{code}:15m` | 당일 진행 중 15m bar | 15m boundary 마감 시 1m 15건 합산 | 15m closing evict + 20:00 flush |
| `candle:current:{code}:30m` | 당일 진행 중 30m bar | 30m boundary 마감 시 1m 30건 합산 | 30m closing evict + 20:00 flush |
| `candle:current:{code}:90m` | 당일 진행 중 90m bar | 90m boundary 마감 시 1m 90건 합산 | 90m closing evict + 20:00 flush |
| `candle:current:{code}:1h` | 당일 진행 중 1h bar | 60m boundary 마감 시 1m 60건 합산 | 1h closing evict + 20:00 flush |

**STOMP 토픽 매트릭스 (ADR 0029 §2-5 LOCK + ADR 0064 C-1 채택)**:

```
/topic/market.candle.5m.{code}     — 5m boundary closing (NXT 144건/일)
/topic/market.candle.15m.{code}    — 15m boundary closing (NXT 48건/일)
/topic/market.candle.30m.{code}    — 30m boundary closing (NXT 24건/일)
/topic/market.candle.90m.{code}    — 90m boundary closing (NXT 8건/일)
/topic/market.candle.1h.{code}     — 1h boundary closing (NXT 12건/일)
```

```mermaid
sequenceDiagram
    participant KIS_WS as KIS WebSocket<br/>(H0STCNT0)
    participant WsBridge as KisWebSocketBridge
    participant MinAgg as TickToMinuteAggregator
    participant BoundDet as BoundaryDetector<br/>(domain — Pure Kotlin)
    participant BoundAgg as BoundaryAggregator<br/>(consumer)
    participant Redis as Redis<br/>(candle:current:{code}:{interval})
    participant DB as PostgreSQL (candle)
    participant Kafka as Kafka<br/>(market.candle.5m.closed.v1)
    participant StompL as CandleClosedStompListener<br/>(api)
    participant FE as Frontend (LWC v4)

    KIS_WS->>WsBridge: "0|H0STCNT0|001|{44 cols}" (NXT 08:00~20:00 KST)
    note over WsBridge: raw: domestic_stock_functions_ws.py ccnl_krx() (R5)<br/>cols[1]=STCK_CNTG_HOUR — boundary 검사 진입 필드
    WsBridge->>Redis: HSET quote:last:{code} (기존)
    WsBridge->>MinAgg: onTick(code, tick) — 1m OHLCV 누적

    note over MinAgg: 1m boundary 도달 감지 (분 단위 전환)
    MinAgg->>DB: INSERT candle interval='1m' ON CONFLICT DO NOTHING
    MinAgg->>BoundAgg: onTickClosed(code, closingTs) [ADR 0064 B-1 trigger]

    BoundAgg->>BoundDet: detectBoundary(hhmmss) → List<CandleInterval>
    note over BoundDet: Pure Kotlin object — Spring 의존 0<br/>ADR 0063 NXT cover: minuteOfDay in [480,1200]<br/>5m: (min-480)%5==0 · 15m: %15==0 · 30m: %30==0<br/>90m: hardcoded set · 1h: %60==0

    alt boundary 마감 (예: 09:05 → [5m])
        loop detected interval (순차 — race 회피)
            BoundAgg->>DB: SELECT candle WHERE interval='1m' AND ts >= boundaryStart(5m)<br/>→ 직전 5건 1m fetch
            BoundAgg->>BoundAgg: CandleAggregator.aggregate(1m rows, 5m)<br/>OHLCV = (first.open, max.high, min.low, last.close, sum.vol)
            BoundAgg->>Redis: putBoundaryCandle(code, "5m", candle)<br/>HSET candle:current:{code}:5m TTL 25h
            BoundAgg->>DB: INSERT candle interval='5m' ON CONFLICT DO NOTHING
            note over BoundAgg: 멱등 가드: in-process lastProcessedBoundaryMin Map<br/>(code,interval) → 같은 boundary 분 1회만 처리
            BoundAgg->>Kafka: publish market.candle.5m.closed.v1<br/>{ code, interval="5m", ts, open, high, low, close, volume, source="boundary" }
        end
    else boundary 아님
        BoundAgg-->>MinAgg: skip (해당 분 tick은 1m 누적만)
    end

    Kafka->>StompL: @KafkaListener market.candle.5m.closed.v1
    StompL->>FE: STOMP /topic/market.candle.5m.{code}<br/>{ CandleClosedMessage }

    FE->>FE: useSymbolCandles STOMP handler<br/>→ LWC series.update({ time, open, high, low, close, value })
    note over FE: IntervalToggle "5m" 선택 중일 때만 subscribe 중<br/>→ 불필요한 interval 메시지 수신 0 (ADR 0064 C-1 효과)
```

**멱등성·race condition 가드 (ADR 0064 §4-6 정합)**:

| 시나리오 | 가드 |
|---|---|
| 같은 boundary 분 내 multiple tick (09:05:00·09:05:01·09:05:23) | BoundaryAggregator in-process `lastProcessedBoundaryMin: ConcurrentHashMap<Pair<String, CandleInterval>, LocalDate>` — 동일 (code, interval, date) 1회만 처리 |
| DailyCloseFinalizer 20:00 flush와 동시 trigger | DB ON CONFLICT DO NOTHING (code+interval+ts UNIQUE) — race 안전. 20:00 flush는 safety net |
| 컨테이너 재기동 시 in-process map 손실 | KIS WS 재연결 후 첫 tick에서 boundary 재진입 — DB 멱등 |

**latency 예측 (ADR 0064 §5-3 정합)**:

| 단계 | 예상 latency |
|---|---|
| KIS WS tick → BoundaryDetector | <5ms |
| 1m 직전 N건 fetch (Redis/DB) | <5ms (60건 최대) |
| CandleAggregator.aggregate | <2ms (Pure Kotlin in-memory) |
| Redis put + DB INSERT | <10ms |
| Kafka publish → STOMP fanout | <20ms |
| **총 end-to-end** | **<50ms** |

---

## 시나리오 10: KIS WS H0STCNT0 in-process boundary 검사 — DailyCloseFinalizer 20:00 KST NXT 마감 정합

**목적**: BoundaryAggregator boundary 마감 실시간 갱신 vs DailyCloseFinalizer 20:00 KST 멱등 safety net 정합 검증 — ON CONFLICT DO NOTHING 이중 보장 흐름

**관련 PR/ADR**: ADR 0064 §4-3·§4-6 (멱등 보장) · ADR 0062 §9 (DailyCloseFinalizer flush) · ADR 0063 (NXT 마감 20:00 KST)

**KIS-first raw 출처 (헌법 §3 의무 — v3.2.2 baseline 인용 정합)**:
- H0STCNT0 체결 `[1]` STCK_CNTG_HOUR — boundary 검사 진입 필드 (R5 — ccnl_krx() `domestic_stock_functions_ws.py`)
- PINGPONG JSON: `kis_auth.py rdic["header"]["tr_id"]` (R4) — 30초 주기, boundary 검사 대상 제외

```mermaid
sequenceDiagram
    participant KIS_WS as KIS WebSocket<br/>(H0STCNT0)
    participant BoundAgg as BoundaryAggregator<br/>(consumer — in-process B-1)
    participant DB as PostgreSQL (candle)
    participant Redis as Redis
    participant Kafka as Kafka
    participant Scheduler as @Scheduled(cron 20:00 KST)
    participant Finalizer as DailyCloseFinalizer
    participant Agg as AggregateBackfillRunner

    Note over KIS_WS,BoundAgg: 장중 실시간 boundary 마감 흐름 (ADR 0064 B-1)

    KIS_WS->>BoundAgg: H0STCNT0 tick (STCK_CNTG_HOUR=090500)<br/>raw: ccnl_krx() cols[1] (R5 — domestic_stock_functions_ws.py)
    BoundAgg->>BoundAgg: BoundaryDetector.detectBoundary("090500")<br/>→ [5m] (09:05 = 08:00 기준 65분 → 5의 배수 = 5m boundary)
    BoundAgg->>DB: INSERT candle interval='5m' ts=09:05:00<br/>ON CONFLICT DO NOTHING
    BoundAgg->>Redis: HSET candle:current:{code}:5m (ADR 0029 §2-1 A-1)
    BoundAgg->>Kafka: market.candle.5m.closed.v1

    note over BoundAgg: PINGPONG JSON 메시지는 boundary 검사 건너뜀<br/>raw: kis_auth.py rdic["header"]["tr_id"]=="PINGPONG" (R4)

    Note over Scheduler,Agg: 20:00 KST NXT 마감 — DailyCloseFinalizer (ADR 0062 §9 + ADR 0063)

    Scheduler->>Finalizer: finalize() trigger (20:00 KST)
    Finalizer->>Agg: backfill(code) [당일 합산 갱신]
    Agg->>DB: SELECT 1m WHERE ts >= today 08:00 KST
    loop 5m·15m·30m·90m·1h
        Agg->>DB: INSERT candle interval='{target}' ON CONFLICT DO NOTHING
        note over Agg: 장중 boundary 마감으로 이미 INSERT된 row:<br/>ON CONFLICT DO NOTHING → skip (멱등 정합)<br/>ADR 0064 §4-2 + §4-6 safety net 역할 확인
    end
    Finalizer->>Redis: DEL candle:current:{code}:5m~1h (evict)

    Note over BoundAgg,Finalizer: 정합 결론:<br/>1. boundary 마감(실시간) → DB row append (source="boundary")<br/>2. 20:00 flush → 동일 ts row → ON CONFLICT DO NOTHING<br/>3. 둘 다 멱등 — 중복 INSERT 0건 보장<br/>4. boundary 누락 종목(tick 0건) → 20:00 flush가 fallback cover
```

**in-process boundary 검사 가드 상세 (ADR 0064 §4-2 + §4-6 정합)**:

| 케이스 | 처리 |
|---|---|
| 같은 boundary 분 H0STCNT0 multiple tick | `lastProcessedBoundaryMin` in-process map — (code, interval, localDate) key 1회만 처리 |
| 거래량 ZERO 종목 (tick 수신 없음) | 20:00 DailyCloseFinalizer `backfill()` fallback cover |
| KIS WS PINGPONG JSON (30초 주기, R4) | `KisWsMessageParser` → `Pingpong` 타입 분류 → boundary 검사 skip (PR #287 정합) |
| 컨테이너 재기동 (in-process map 초기화) | WS 재연결 후 첫 tick boundary 재진입 → DB ON CONFLICT DO NOTHING 멱등 |
| WS + polling worker 동시 trigger (post-MVP 41+종목) | Kafka partition by code → 단일 consumer thread → race 0 (ADR 0064 §4-6) |

---

## 시나리오 11: T-SIG 실시간 시그널 발행 (v3.4.0 신규 — ADR 0066)

**목적**: boundary 마감 Kafka 이벤트 → 활성 strategy 조회 → DSL 평가 → 시그널 INSERT + Kafka publish → STOMP 토스트

**관련 PR/ADR**: ADR 0066 (A-1·B-1·C-1·D-1·E-1·F-1 채택) · ADR 0064 (boundary fanout — 트리거 진입점) · ADR 0047 (JvmStrategyEvaluator sandbox timeout 100ms) · ADR 0023 (Signal Engine 룰 매칭 only, AI 호출 0) · ADR 0029 §2-5 (STOMP 토픽 LOCK)

**ADR 0066 채택 의사결정 요약**:

| 의제 | 채택 | 설명 |
|---|---|---|
| A. 트리거 | A-1 Kafka boundary listen 5 listener | ADR 0064 `market.candle.{interval}.closed.v1` 5 토픽 listen |
| B. 룰 source | B-1 strategy 영속 DB | Redis cache(TTL 5분) 1차 + miss 시 DB SELECT |
| C. 시그널 영속 | C-1 신규 signal 테이블 (Flyway V15) | UQ(strategy_id, code, interval, ts) 멱등 E-1 |
| D. 알림 | D-1 STOMP fanout `/topic/signal.user.{userId}` | MVP 단순 · ADR 0064 STOMP 인프라 재활용 |
| E. 멱등 | E-1 DB UQ + Redis SETNX 30s window | deterministic boundary ts · race 0 |
| F. 룰 상태 | F-1 status enum 4단 (ACTIVE·PAUSED·DISABLED·DELETED) | ADR 0047 V26 baseline + transition 매트릭스 |

**KIS-first raw 출처 (헌법 §3 — v3.3.0 시나리오 9·10 인용 정합)**:
- 시나리오 11은 KIS REST/WS 직접 호출 0건 (ADR 0066 §3-4 명시)
- 트리거 진입점: `market.candle.{interval}.closed.v1` (ADR 0064 §4-5 LOCK — 시나리오 9 boundary fanout 결과)
- DSL 평가 엔진: `KisYamlDslParser` (R11 — parser.py TOKEN_PATTERNS LOCK) + `JvmStrategyEvaluator` sandbox (ADR 0047)
- 지표·패턴 계산: `KisIndicatorEngine` (R9 — indicators.py 80지표) + `KisPatternEngine` (R10 — candlestick.py 57패턴)

**signal 도메인 모델 (ADR 0066 §5-2 + ADR 0023 §2-1 baseline 확장)**:
```kotlin
// domain/.../signal/Signal.kt
data class Signal(
    val id: Long? = null,
    val strategyId: Long,
    val userId: String,
    val code: String,
    val interval: String,
    val ts: Instant,          // boundary closing ts (E-1 UQ key)
    val side: SignalSide,     // BUY · SELL (HOLD skip — 미저장)
    val strength: Double,
    val reason: String,
    val priceAtSignal: BigDecimal,
    val contextJson: String?,
    val createdAt: Instant = Instant.now(),
    val riskStatus: String = "PENDING"  // v3.5.0 T-RISK 추가 — ADR 0067 G-1 (Flyway V19)
)
enum class SignalSide { BUY, SELL, HOLD }
```

**strategy 상태 전이 매트릭스 (ADR 0066 F-1 + ADR 0067 F-1 — v3.5.0 확장)**:

| 현재 상태 | 가능한 전이 | 트리거 |
|---|---|---|
| ACTIVE | → PAUSED | 사용자 토글 OR AutoPauseGuard 연속 BLOCKED 10회 (ADR 0067) |
| ACTIVE | → DISABLED | sandbox autoDisable (timeout 초과 3회 연속) |
| ACTIVE | → DELETED | 사용자 소프트 삭제 |
| PAUSED | → ACTIVE | 사용자 토글 |
| PAUSED | → DELETED | 사용자 소프트 삭제 |
| DISABLED | → ACTIVE | 사용자 재활성화 |
| DELETED | (불변) | — |

```mermaid
sequenceDiagram
    participant BoundAgg as BoundaryAggregator<br/>(consumer — 시나리오 9 결과)
    participant Kafka as Kafka<br/>(market.candle.5m.closed.v1)
    participant Listener as BoundaryClosingKafkaListener<br/>(consumer — groupId: magicjar-consumer-signal-5m)
    participant SigGen as RealtimeSignalGenerator<br/>(consumer)
    participant StratLookup as StrategyLookupPort
    participant Redis as Redis<br/>(strategy:active:{code}:{interval})
    participant DB as PostgreSQL<br/>(strategy + signal)
    participant Dedup as SignalDedupeGuard<br/>(Redis SETNX 30s)
    participant SigRepo as SignalRepository
    participant SigKafka as SignalKafkaProducer<br/>(signal.generated.v1)
    participant Dispatcher as StompSignalDispatcher<br/>(api — groupId: magicjar-api-stomp-signal)
    participant FE as Frontend (useStompSignal)

    Note over BoundAgg,Kafka: 시나리오 9 boundary 마감 결과 (ADR 0064 §4-5)
    BoundAgg->>Kafka: market.candle.5m.closed.v1<br/>{ code="005930", interval="5m", ts, open, high, low, close, volume }

    Kafka->>Listener: @KafkaListener("market.candle.5m.closed.v1")<br/>groupId: magicjar-consumer-signal-5m
    Listener->>SigGen: onBoundaryClosed(code="005930", interval="5m", candle)

    SigGen->>StratLookup: findActiveByCodeAndInterval(code="005930", interval="5m")
    StratLookup->>Redis: GET strategy:active:by_code:005930:5m (TTL 5분 캐시 — B-1)
    alt Redis cache hit
        Redis-->>StratLookup: List<Strategy> (ACTIVE 상태 전략 목록)
    else cache miss
        StratLookup->>DB: SELECT strategy WHERE codes @> '["005930"]'<br/>AND intervals @> '["5m"]' AND status='ACTIVE'
        note over DB: Flyway V14 — codes·intervals JSONB 컬럼<br/>ADR 0066 F-1 status enum 4단
        DB-->>StratLookup: List<Strategy>
        StratLookup->>Redis: SET strategy:active:by_code:005930:5m TTL 5분
    end

    loop 각 활성 strategy
        SigGen->>SigGen: JvmStrategyEvaluator.evaluate(strategy, candles)<br/>sandbox timeout 100ms (ADR 0047 §2-5 정합)<br/>DSL AST → buyCondition 평가 (R11 parser.py TOKEN_PATTERNS)<br/>지표 계산 R9 indicators.py · 패턴 감지 R10 candlestick.py

        alt SignalSide == HOLD
            SigGen->>SigGen: skip (HOLD 미저장 — ADR 0066 §5-2)
        else SignalSide == BUY or SELL
            SigGen->>Dedup: tryAcquire(strategyId, code, interval, ts)<br/>Redis SETNX "dedup:signal:{strategyId}:{code}:{interval}:{ts}" TTL 30s
            alt SETNX 성공 (멱등 통과)
                Dedup-->>SigGen: true
                SigGen->>SigRepo: save(Signal(side=BUY, strength, reason, priceAtSignal, riskStatus="PENDING"))
                note over SigRepo: DB INSERT — UQ(strategy_id, code, interval, ts) 강제<br/>DUPLICATE_KEY → log.debug + skip (E-1 safety net)<br/>riskStatus="PENDING" 초기값 (Flyway V19 — ADR 0067 G-1)
                SigRepo-->>SigGen: Signal { id=42, riskStatus="PENDING", ... }
                SigGen->>SigKafka: publish signal.generated.v1<br/>{ signalId=42, strategyId, userId, code, side="BUY", ts, price }<br/>partition key=userId (ADR 0007 — 사용자별 순서 보장)
            else SETNX 실패 (중복 — 30s window 내 재처리)
                Dedup-->>SigGen: false → skip (log.debug "dedup guard blocked")
            end
        end
    end

    Kafka->>Dispatcher: @KafkaListener("signal.generated.v1")<br/>groupId: magicjar-api-stomp-signal (api 모듈)
    Dispatcher->>FE: STOMP /topic/signal.user.{userId}<br/>{ signalId, code, side="BUY", strategy="삼성전자 골든크로스", ts, price }

    FE->>FE: useStompSignal() handler<br/>→ 토스트 알림: "BUY 시그널 — 삼성전자 (005930)<br/>전략: 삼성전자 골든크로스 | 09:05"
    FE->>FE: SignalListPanel.prepend(signal)<br/>→ 시그널 목록 상단 추가
```

**시그널 발행 멱등성 매트릭스 (ADR 0066 E-1 정합)**:

| 케이스 | 1차 가드 | 2차 가드 |
|---|---|---|
| 같은 boundary 재처리 (Kafka rebalance) | Redis SETNX 30s window (fast-path skip) | DB UQ(strategy_id, code, interval, ts) DUPLICATE_KEY |
| 컨테이너 재기동 후 Redis 소실 | Redis SETNX 실패 없음 → DB INSERT 시도 | DB UQ DUPLICATE_KEY → skip |
| Redis 결함 (fail-open 정책) | SETNX 예외 → true 반환 (저장 시도) | DB UQ DUPLICATE_KEY 최종 보장 |
| HOLD 시그널 발생 | SigGen skip (미저장) | — |

**latency 예측 (ADR 0066 §5-3 + ADR 0064 §5-3 정합)**:

| 단계 | 예상 latency |
|---|---|
| boundary 마감 → Kafka (시나리오 9) | <20ms |
| Kafka → BoundaryClosingKafkaListener | <5ms |
| Redis strategy cache hit | <2ms |
| JvmStrategyEvaluator sandbox (10종목 × 5 전략) | <100ms (timeout 100ms ADR 0047) |
| Signal INSERT + Kafka publish | <10ms |
| STOMP fanout → frontend 토스트 | <20ms |
| **총 end-to-end (boundary→토스트)** | **<200ms** |

**AI 호출 0 검증 (ADR 0023 §2-4 + 개발 룰 5)**:
- `RealtimeSignalGenerator` — LLM provider import 0건
- `JvmStrategyEvaluator` — DSL AST 평가만, LLM 호출 0건
- `BoundaryClosingKafkaListener` — Kafka listen만
- grep 검증: `grep -r "openai\|anthropic\|ChatClient\|AiClient" consumer/src/main/kotlin/` → 0건 (T-SIG 트랙 정책 LOCK)

---

## 시나리오 12: T-BT 백테스트 실행 + 영속 (v3.4.0 신규 — ADR 0061)

**목적**: 사용자 BacktestPage DSL 입력 → POST /api/backtests → 캔들 순회 DSL 평가 + 시뮬레이션 → KPI 계산 → 3 테이블 영속 → frontend 폴링 → 결과 표시

**관련 PR/ADR**: ADR 0061 (A-1·B-1·C-1·D-1·E-1·F-1 채택) · ADR 0056 §2-7 (KPI 계산식 LOCK — ADR 0061 §2-5 계승) · ADR 0047 (DSL sandbox timeout 1000ms)

**KIS-first raw 출처 (헌법 §3 — 시나리오 12)**:
- 시나리오 12는 KIS REST/WS 직접 호출 0건 (candle 테이블 SELECT 기반)
- DSL 평가: `KisYamlDslParser` (R11 — parser.py TOKEN_PATTERNS LOCK) + `JvmBacktestEngine` (ADR 0047 sandbox timeout 1000ms)
- 지표·패턴: `KisIndicatorEngine` (R9) + `KisPatternEngine` (R10)

**KPI 계산식 (ADR 0056 §2-7 + ADR 0061 §2-5 계승)**:

| 지표 | 계산식 |
|---|---|
| 총수익률 | `(finalEquity - initialCapital) / initialCapital` |
| 승률 | `winTrades / totalTrades` |
| MDD | `max((peak_at_t - equity[t]) / peak_at_t for all t)` |
| Sharpe | `mean(returns) / std(returns) * sqrt(252)` |
| Sortino | post-MVP §6-2 |

```mermaid
sequenceDiagram
    actor User as 사용자
    participant FE as Frontend (BacktestPage)
    participant API as api 모듈 (BacktestsController)
    participant BtService as BacktestService
    participant Engine as JvmBacktestEngine<br/>(domain — ADR 0056 §2-3)
    participant DSL as KisYamlDslParser<br/>(R11 — parser.py TOKEN_PATTERNS)
    participant Indicator as KisIndicatorEngine<br/>(R9 — indicators.py 80지표)
    participant Pattern as KisPatternEngine<br/>(R10 — candlestick.py 57패턴)
    participant Persister as BacktestPersister<br/>(@Transactional)
    participant DB as PostgreSQL<br/>(backtest_run · backtest_trade · backtest_position)

    User->>FE: BacktestPage — 종목·인터벌·기간·DSL 조건식·초기자본 입력
    FE->>API: POST /api/backtests<br/>{ code, interval, rangFrom, rangeTo, dsl, initialCapital }

    API->>BtService: runBacktest(request)
    BtService->>DB: SELECT candle WHERE code=? AND interval=? AND ts BETWEEN rangFrom AND rangeTo ORDER BY ts ASC
    DB-->>BtService: List<Candle> (기간 내 캔들 데이터 — cold-start 적재 선행 전제)

    alt 캔들 0건
        BtService-->>API: BacktestResult(status=FAILED, reason="NO_CANDLE_DATA")
        note over API: feedback_no_mock_data.md 정합 — 0건 명시 안내
    else 캔들 존재
        BtService->>Engine: run(candles, dsl, initialCapital)
        note over Engine: sandbox timeout 1000ms (ADR 0047 정합)<br/>candle 순회: 각 캔들에서 DSL AST 평가

        loop 각 캔들 (시간순 순회)
            Engine->>DSL: parse(dsl) — TOKEN_PATTERNS (R11)
            Engine->>Indicator: computeIndicators(window) (R9 — 80지표 rolling)
            Engine->>Pattern: detectPatterns(window) (R10 — 57패턴)
            Engine->>Engine: evaluateBuyCondition() → BUY/HOLD<br/>evaluateSellCondition() → SELL/HOLD
            alt BUY 시그널 + 포지션 없음
                Engine->>Engine: openPosition(price, quantity)<br/>Trade(side=BUY, ts, price, quantity, fee=0)
            else SELL 시그널 + 포지션 있음
                Engine->>Engine: closePosition(price)<br/>Trade(side=SELL, ts, price, quantity, fee=0, pnl)<br/>Position(entryTs, exitTs, pnl)
            end
            Engine->>Engine: equity[t] = cash + holdingValue
        end

        Engine-->>BtService: BacktestResult<br/>{ trades[], positions[], equityCurve[], KPI }
        BtService->>BtService: KPI 계산 (ADR 0056 §2-7 + ADR 0061 §2-5)<br/>totalReturn · winRate · MDD · Sharpe
    end

    BtService->>Persister: persist(userId, result)
    note over Persister: @Transactional — SUCCEEDED 또는 FAILED 모두 저장<br/>ADR 0061 §2-4 정합
    Persister->>DB: INSERT backtest_run (status, KPI, detail_json)
    Persister->>DB: saveAll backtest_trade (trades[])
    Persister->>DB: saveAll backtest_position (positions[])
    DB-->>Persister: runId=77

    API-->>FE: 200 BacktestRunPersistResponse<br/>{ runId=77, status="SUCCEEDED", performance{ totalReturn, winRate, mdd, sharpe }, ... }

    note over FE: RUNNING 상태 시 3초 폴링 (BacktestDetail)<br/>GET /api/backtests/77 → status 확인
    FE->>FE: BacktestDetail 렌더링<br/>BacktestKpiPanel (5 KPI 카드)<br/>EquityCurveChart (LWC v4 line series)<br/>TradeTable (거래 페이징 테이블)
    FE-->>User: 백테스트 결과 표시<br/>(총수익률·승률·MDD·Sharpe + equity curve)
```

---

## 시나리오 13: T-RISK 시그널 → Risk Gateway → 주문 의도 (v3.5.0 신규 — ADR 0067)

**목적**: `signal.generated.v1` Kafka 수신 → RiskGatewayEvaluator 6 룰 평가 → ALLOWED 시 `order.intent.v1` publish / BLOCKED 시 `risk_status='BLOCKED'` + risk_event INSERT + STOMP 알림

**관련 PR/ADR**: ADR 0067 (A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1 채택) · ADR 0066 (signal.generated.v1 진입점 — 시나리오 11 인용) · ADR 0044 (Notification 진입 — risk.violation.v1) · ADR 0024 (Risk Guard 6단 골격) · ADR 0029 §2-5 (STOMP 토픽 인프라)

**ADR 0067 채택 의사결정 요약**:

| 의제 | 채택 | 설명 |
|---|---|---|
| A. 포지션 한도 | A-1 절대+% min() | 자본 변동 대응 + 신규 사용자 safe default |
| B. 일일 손실 한도 | B-1 전역+택1+자정 reset | 자본 일일 보호 본질 · UX 단순 |
| C. 동시 종목 한도 | C-1 사용자 설정 default 5 | 자본 분산 · UI 슬라이더 · safe default |
| D. 거래 빈도 | D-1 strategy당 분당+일별 dual Redis sliding | strategy 격리 + burst·누적 cover |
| E. 게이트 위치 | E-1 Kafka consumer (signal.generated.v1) | strategy 패턴 일관 + audit trail + T-O decoupled |
| F. 위반 처리 | F-1 4단 처리 (risk_status + risk_event + STOMP + auto-PAUSE) | audit + 사용자 인지 + 폭주 격리 |
| G. 영속 | G-1 4 도메인 (risk_config + risk_event + signal.risk_status + position) | 도메인 분리 + ADR 0061 baseline 일관 |
| H. 설정 source | H-1 사용자별 risk_config + 전역 default seed | multi-tenant + safe default |

**KIS-first raw 출처 (헌법 §3 — 시나리오 13 선언)**:
- 시나리오 13은 KIS REST/WS 직접 호출 0건 (ADR 0067 §8 명시)
- 트리거 진입점: `signal.generated.v1` (ADR 0066 §4-5 LOCK — 시나리오 11 결과)
- 6 룰 평가: RiskGatewayEvaluator 순수 Kotlin 도메인 로직 (KIS API 호출 0)
- raw 출처 인용: 시나리오 9 R5 (H0STCNT0 체결 pipeline baseline) + 시나리오 11 signal pipeline baseline — 두 선행 시나리오가 signal.generated.v1 origin 권위

**RiskGatewayEvaluator 6 룰 short-circuit 매트릭스 (ADR 0067 §5·ADR 0024 §2-1 정합)**:

| 순서 | 룰 | enum | 검사 내용 | data source |
|---|---|---|---|---|
| 1 | 포지션 한도 | `POSITION_LIMIT` | 단일 종목 보유금액 ≤ min(절대한도, 자본×%) | PositionRepository + CapitalSnapshotProvider |
| 2 | 일일 손실 한도 | `DAILY_LOSS_LIMIT` | 오늘 실현 손실 ≤ 한도 (KST 자정 reset) | DailyPnlCalculator (Redis cache + position history) |
| 3 | 동시 종목 한도 | `MAX_CONCURRENT_POSITIONS` | 동시 보유 종목 수 ≤ 사용자 설정 (default 5) | PositionRepository.countActive() |
| 4 | 분당 거래 빈도 | `TRADE_FREQUENCY_MINUTE` | strategy당 1분 내 거래 ≤ 한도 | TradeFrequencyRedisGuard ZSET sliding window |
| 5 | 일별 거래 빈도 | `TRADE_FREQUENCY_DAY` | strategy당 일별 거래 ≤ 한도 | TradeFrequencyRedisGuard ZSET sliding window |
| 6 | KillSwitch | `KILL_SWITCH_ON` | riskConfig.killSwitchEnabled == true | RiskConfigRepository |

```mermaid
sequenceDiagram
    participant SigKafka as Kafka<br/>(signal.generated.v1)
    participant Listener as SignalGeneratedKafkaListener<br/>(consumer — groupId: magicjar-consumer-risk)
    participant Evaluator as RiskGatewayEvaluator<br/>(6 룰 short-circuit)
    participant RiskConfig as RiskConfigRepository<br/>(H-1 사용자별)
    participant Position as PositionRepository
    participant Capital as CapitalSnapshotProvider
    participant DailyPnl as DailyPnlCalculator<br/>(Redis + position history)
    participant FreqGuard as TradeFrequencyRedisGuard<br/>(ZSET sliding window)
    participant StatusUpdater as SignalRiskStatusUpdater<br/>(signal.risk_status UPDATE)
    participant EventRepo as RiskEventRepository<br/>(BLOCKED만 INSERT)
    participant StompRisk as StompRiskNotifier<br/>(api — /topic/risk.user.{userId})
    participant ViolKafka as RiskViolationKafkaProducer<br/>(risk.violation.v1)
    participant IntentKafka as OrderIntentKafkaProducer<br/>(order.intent.v1)
    participant AutoPause as AutoPauseGuard
    participant FE as Frontend (useStompRisk)

    SigKafka->>Listener: signal.generated.v1<br/>{ signalId=42, strategyId, userId, code, side="BUY", ts, price }<br/>partition key=userId (ADR 0007 — 사용자별 순서 보장)
    note over Listener: groupId: magicjar-consumer-risk<br/>ADR 0067 E-1 — Kafka consumer 게이트 위치

    Listener->>Evaluator: evaluate(signalPayload)
    Evaluator->>RiskConfig: getOrDefault(userId)<br/>H-1 — 사용자별 risk_config (Flyway V16)

    Note over Evaluator: 6 룰 순차 short-circuit 평가 (ADR 0024 §2-1 + ADR 0067 §5)

    Evaluator->>Position: findByUserIdAndCode(userId, code)
    note over Evaluator: 룰 1 POSITION_LIMIT<br/>positionValue ≤ min(absLimit, capital × pctLimit)
    Evaluator->>Capital: getCapitalSnapshot(userId)

    Evaluator->>DailyPnl: getDailyPnl(userId)
    note over Evaluator: 룰 2 DAILY_LOSS_LIMIT<br/>realizedLoss ≤ dailyLossLimit (KST 자정 reset)

    Evaluator->>Position: countActiveByUserId(userId)
    note over Evaluator: 룰 3 MAX_CONCURRENT_POSITIONS<br/>activeCount ≤ maxConcurrent (default 5, C-1)

    Evaluator->>FreqGuard: checkMinuteFrequency(strategyId, userId)
    note over Evaluator: 룰 4 TRADE_FREQUENCY_MINUTE<br/>ZSET sliding window 1분 내 count ≤ limit (D-1)

    Evaluator->>FreqGuard: checkDayFrequency(strategyId, userId)
    note over Evaluator: 룰 5 TRADE_FREQUENCY_DAY<br/>ZSET sliding window 일별 count ≤ limit (D-1)

    note over Evaluator: 룰 6 KILL_SWITCH_ON<br/>riskConfig.killSwitchEnabled == false → pass

    alt ALLOWED (6 룰 모두 통과)
        Evaluator->>StatusUpdater: markAllowed(signalId)<br/>UPDATE signal SET risk_status='ALLOWED' WHERE id=42
        note over StatusUpdater: Flyway V19 — signal.risk_status 컬럼 (ADR 0067 G-1)
        Evaluator->>IntentKafka: publish order.intent.v1<br/>{ signalId=42, userId, code, side="BUY", price, ts }<br/>partition key=userId (T-O 후행 consumer 진입점)
        note over IntentKafka: ADR 0067 §5 ALLOWED 흐름 — risk_event INSERT 0 (ALLOWED 영속 불필요)
    else BLOCKED (첫 번째 위반 룰에서 short-circuit)
        Evaluator->>StatusUpdater: markBlocked(signalId, rule, reason)<br/>UPDATE signal SET risk_status='BLOCKED' WHERE id=42
        Evaluator->>EventRepo: INSERT risk_event<br/>{ userId, strategyId, signalId, rule, verdict="BLOCKED", reason, createdAt }<br/>Flyway V17 — BLOCKED만 영속 (ADR 0067 §5-3)
        Evaluator->>ViolKafka: publish risk.violation.v1<br/>{ userId, strategyId, rule, reason }<br/>partition key=userId (ADR 0044 Notification 진입점)
        Evaluator->>StompRisk: notifyViolation(userId, riskEvent)<br/>STOMP /topic/risk.user.{userId}<br/>{ type="BLOCKED", rule, reason, strategyId, code }
        StompRisk->>FE: STOMP MESSAGE (빨강 토스트)
        FE->>FE: useStompRisk() handler<br/>→ 토스트: "리스크 차단 — [POSITION_LIMIT]<br/>삼성전자 포지션 한도 초과"

        Evaluator->>AutoPause: checkAndPauseIfNeeded(strategyId, userId)
        note over AutoPause: 시나리오 14 — 연속 BLOCKED 10회 시 strategy PAUSED 전환
    end
```

**STOMP 토픽 (ADR 0067 F-1 + ADR 0029 §2-5 인프라 재활용)**:

```
/topic/risk.user.{userId}   — BLOCKED 빨강 토스트 · AUTO_PAUSED 주황 토스트
```

**risk_event 도메인 모델 (ADR 0067 G-1 — Flyway V17)**:
```kotlin
// domain/.../risk/RiskEvent.kt
data class RiskEvent(
    val id: Long? = null,
    val userId: String,
    val strategyId: Long,
    val signalId: Long,
    val rule: RiskRule,        // POSITION_LIMIT · DAILY_LOSS_LIMIT · MAX_CONCURRENT_POSITIONS
                               // TRADE_FREQUENCY_MINUTE · TRADE_FREQUENCY_DAY · KILL_SWITCH_ON
    val verdict: RiskVerdict,  // BLOCKED (ALLOWED는 영속 0 — ADR 0067 §5-3)
    val reason: String,
    val createdAt: Instant = Instant.now()
)
enum class RiskRule { POSITION_LIMIT, DAILY_LOSS_LIMIT, MAX_CONCURRENT_POSITIONS,
                      TRADE_FREQUENCY_MINUTE, TRADE_FREQUENCY_DAY, KILL_SWITCH_ON }
enum class RiskVerdict { ALLOWED, BLOCKED }
```

**REST endpoint 매트릭스 (ADR 0067 §5-4)**:

| 메서드 | 경로 | 기능 |
|---|---|---|
| `GET` | `/api/risk-config` | 현재 리스크 설정 조회 |
| `PUT` | `/api/risk-config` | 리스크 설정 수정 |
| `POST` | `/api/risk-config/reset` | 전역 default로 초기화 |
| `GET` | `/api/risk-events` | 위반 이력 목록 (페이징) |
| `GET` | `/api/risk-events/{id}` | 위반 이력 단건 |
| `GET` | `/api/positions` | 현재 포지션 목록 |
| `GET` | `/api/positions/{id}` | 포지션 단건 |

**latency 예측 (ADR 0067 §5-6 정합)**:

| 단계 | 예상 latency |
|---|---|
| signal.generated.v1 → SignalGeneratedKafkaListener | <5ms |
| RiskConfig 조회 (H-1 DB) | <5ms |
| 6 룰 순차 평가 (Redis+DB) | <20ms |
| SignalRiskStatusUpdater UPDATE | <5ms |
| BLOCKED: risk_event INSERT + STOMP fanout | <15ms |
| ALLOWED: order.intent.v1 publish | <5ms |
| **총 end-to-end (signal→intent/STOMP)** | **<75ms** |

**AI 호출 0 검증 (ADR 0023 §2-4 + ADR 0067 §8 + 개발 룰 5)**:
- `RiskGatewayEvaluator` — 6 룰 deterministic 평가, LLM provider import 0건
- `SignalGeneratedKafkaListener` — Kafka listen만
- `AutoPauseGuard` — Redis counter + strategy status update, LLM 호출 0건
- inline 주석 의무: `// AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)`

---

## 시나리오 14: AutoPause — 연속 BLOCKED 10회 → strategy PAUSED (v3.5.0 신규 — ADR 0067 F-1)

**목적**: 동일 strategy 연속 BLOCKED 10회 감지 → strategy.status=PAUSED + StrategyDisabledReason.RISK_VIOLATION + RedisStrategyCache invalidate + STOMP AUTO_PAUSED 알림

**관련 PR/ADR**: ADR 0067 (F-1 4단 위반 처리 채택) · ADR 0066 (F-1 strategy status enum — ACTIVE→PAUSED 전이) · ADR 0029 §2-5 (STOMP 인프라)

**KIS-first raw 출처 (헌법 §3 — 시나리오 14)**:
- 시나리오 14는 KIS API 직접 호출 0건
- strategy.status=PAUSED 전이: ADR 0066 F-1 status 전이 매트릭스 계승 (시나리오 11)
- Redis sliding window: TradeFrequencyRedisGuard와 동일 ZSET 패턴 재활용

**AutoPause 임계값 매트릭스 (ADR 0067 F-1)**:

| 항목 | 값 | 근거 |
|---|---|---|
| 연속 BLOCKED 임계 | 10회 | over-reaction 회피 (1회 즉시 DISABLED 제외) + 폭주 strategy 자동 격리 |
| 검사 window | 최근 10분 내 risk_event | `RiskEventRepository.countRecentBlockedByStrategy(strategyId, windowMinutes=10)` |
| 전이 | ACTIVE → PAUSED | `StrategyDisabledReason.RISK_VIOLATION` (ADR 0067 §5 backend-core 산출물) |

```mermaid
sequenceDiagram
    participant Evaluator as RiskGatewayEvaluator<br/>(시나리오 13 BLOCKED 분기 연속)
    participant AutoPause as AutoPauseGuard<br/>(consumer)
    participant EventRepo as RiskEventRepository
    participant StratRepo as StrategyRepository<br/>(api — ObjectProvider 패턴)
    participant Redis as Redis<br/>(strategy:active:by_code:{code}:{interval})
    participant StompRisk as StompRiskNotifier<br/>(api)
    participant FE as Frontend (useStompRisk)

    Note over Evaluator: 시나리오 13 BLOCKED 분기 완료 — AutoPauseGuard 위임
    Evaluator->>AutoPause: checkAndPauseIfNeeded(strategyId, userId)

    AutoPause->>EventRepo: countRecentBlockedByStrategy(strategyId, windowMinutes=10)
    note over EventRepo: SELECT COUNT(*) FROM risk_event<br/>WHERE strategy_id=? AND verdict='BLOCKED'<br/>AND created_at >= NOW() - INTERVAL '10 minutes'
    EventRepo-->>AutoPause: recentBlockedCount (예: 10)

    alt recentBlockedCount >= autoPauseThreshold (10)
        AutoPause->>StratRepo: updateStatus(strategyId, status=PAUSED,<br/>disabledReason=RISK_VIOLATION)
        note over StratRepo: ObjectProvider 패턴 — api 모듈 JPA 어댑터 (Phase 2 graceful fallback)<br/>StrategyDisabledReason.RISK_VIOLATION 신규 enum 값 (ADR 0067 §5 backend-core)
        StratRepo-->>AutoPause: Strategy { id, status=PAUSED, disabledReason=RISK_VIOLATION }

        AutoPause->>Redis: DEL strategy:active:by_code:{code}:{interval}<br/>(RedisStrategyCache invalidate — ADR 0066 B-1 cache evict)
        note over Redis: TTL 5분 캐시 강제 evict<br/>다음 signal 발생 시 DB에서 PAUSED 상태 재조회 → strategy 목록 제외

        AutoPause->>StompRisk: notifyAutoPaused(userId, strategyId, recentBlockedCount)
        StompRisk->>FE: STOMP /topic/risk.user.{userId}<br/>{ type="AUTO_PAUSED", strategyId, strategyName,<br/>blockedCount=10, windowMinutes=10 }
        FE->>FE: useStompRisk() handler<br/>→ 주황 토스트: "전략 자동 일시정지 —<br/>삼성전자 골든크로스 (10회 연속 리스크 차단)"
        FE->>FE: StrategyCard status 갱신 (PAUSED 뱃지)
    else recentBlockedCount < threshold
        AutoPause-->>Evaluator: skip (임계 미달 — 현재 연속 N회: {count})
    end
```

**AutoPause 정합 검증 매트릭스 (ADR 0067 F-1)**:

| 케이스 | 처리 |
|---|---|
| 단발성 BLOCKED (1~9회) | AutoPauseGuard skip — strategy ACTIVE 유지, 사용자 BLOCKED 토스트만 수신 |
| 연속 BLOCKED 10회 도달 | strategy.status=PAUSED + Redis evict + STOMP AUTO_PAUSED 주황 토스트 |
| PAUSED 후 사용자 재활성화 | strategy.status=ACTIVE 전이 (사용자 토글 — ADR 0066 F-1 PAUSED→ACTIVE 허용) |
| 재활성화 후 재차 연속 BLOCKED | AutoPauseGuard 재발동 가능 (window=10분 내 새 BLOCKED 누적 시) |
| Redis 소실 (재기동) | 다음 signal 발생 시 DB에서 PAUSED 상태 확인 → signal pipeline 제외 (ADR 0066 B-1 fallback DB) |
| DISABLED 상태 strategy | signal pipeline 진입 전 이미 제외 (ACTIVE 조건 SELECT — 시나리오 11 StrategyLookupPort) |

**strategy.status=PAUSED → signal pipeline 배제 흐름**:
```
시나리오 14 AutoPause → strategy.status=PAUSED + Redis evict
    ↓
다음 boundary 마감 (시나리오 11)
    ↓
StrategyLookupPort.findActiveByCodeAndInterval()
    → Redis miss (evict로 인해) → DB SELECT WHERE status='ACTIVE'
    → PAUSED strategy 조회 결과 제외
    ↓
signal 발생 0건 (해당 strategy는 pipeline 배제)
```

**STOMP 이벤트 타입 매트릭스 (ADR 0067 F-1)**:

| 이벤트 타입 | 트리거 | 색상 | 메시지 |
|---|---|---|---|
| `BLOCKED` | 단발 룰 위반 | 빨강 | "리스크 차단 — [{rule}] {reason}" |
| `AUTO_PAUSED` | 연속 BLOCKED 10회 | 주황 | "전략 자동 일시정지 — {strategyName} ({count}회 연속 리스크 차단)" |

---

## v3.5.0 KIS-first 의무 준수 체크리스트

| 항목 | 상태 | 코드 경로 + raw 출처 |
|---|---|---|
| H0STCNT0 tr_id (44 cols) | LOCK | `KisWsMessageParser.kt:42` — `domestic_stock_functions_ws.py` ccnl_krx() (R5) |
| H0STCNT0 STCK_CNTG_HOUR[1] boundary 검사 진입 | LOCK | `BoundaryAggregator.kt` — R5 cols[1] inline 주석 raw URL 의무 (ADR 0064 §1-4) |
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
| BoundaryDetector boundary 시각 매트릭스 | LOCK | `domain/.../candle/BoundaryDetector.kt` — ADR 0064 §4-2 + ADR 0063 NXT 08:00~20:00 정합 |
| RedisCandleCache 5종 키 | LOCK | `consumer/.../cache/RedisCandleCache.kt` — ADR 0029 §2-1 A-1 정합 |
| CandleClosedStompListener 5종 토픽 | LOCK | `api/.../messaging/CandleClosedStompListener.kt` — ADR 0029 §2-5 C-1 정합 |
| T-SIG KIS API 호출 | **0건** | ADR 0066 §3-4 — Kafka listen만, 시나리오 11 자체 추론 0 |
| T-BT KIS API 호출 | **0건** | ADR 0061 §2-4 — candle DB SELECT, 시나리오 12 자체 추론 0 |
| T-RISK KIS API 호출 | **0건** | ADR 0067 §8 — 내부 pipeline 평가만, 시나리오 13·14 자체 추론 0 |
| 자체 추론 | **0건** | 모든 endpoint·tr_id·필드·byte offset은 raw 출처 인용 의무 (헌법 §3) |

---

## 외부 API 통합 정책 요약 (v3.5.0)

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
| KIS 500 retry | 3회 exponential backoff (500ms→1s→2s) | PR #315 §33 market-data hotfix |

### Yahoo Finance

**v3.2.2에서 전면 폐기 (ADR 0057 — 2026-05-11 사용자 결정 "yahoo꺼 쓰지 말라니깐")**
- `YahooCandleFetcherAdapter.kt` — `@Deprecated` 마킹 (v3.2.2 이후 실 코드 호출 0)

### DART Open API (공시)

| 항목 | 값 |
|---|---|
| 목적 | 정기보고·주요사항보고·공시 목록 수집 |
| Kafka 토픽 | `dart.disclosure.v1` (ADR 0007) |
| W3 트랙 예정 | T-SIG·T-RISK 사이클 이후 |

### Naver 금융 뉴스

| 항목 | 값 |
|---|---|
| 방식 | RSS 기반 (robots.txt 준수) |
| User-Agent | `magicjar-news-crawler/1.0` |
| W3 트랙 예정 | T-AI 사이클 |

---

## v3.5.0 변경 이력 (v3.4.0 대비)

| 항목 | v3.4.0 | v3.5.0 |
|---|---|---|
| Signal → Risk 게이트 | 없음 | SignalGeneratedKafkaListener → RiskGatewayEvaluator 6 룰 short-circuit (ADR 0067 E-1) |
| signal.risk_status | 없음 | PENDING·ALLOWED·BLOCKED 컬럼 (Flyway V19 — ADR 0067 G-1) |
| risk_config | 없음 | 사용자별 1 row (Flyway V16, H-1 — ADR 0067) |
| risk_event | 없음 | BLOCKED만 영속 시계열 (Flyway V17 — ADR 0067 G-1) |
| position | 없음 | 현재 보유 포지션 (Flyway V18 — ADR 0067 G-1) |
| AutoPause | 없음 | 연속 BLOCKED 10회 → strategy.status=PAUSED + STOMP AUTO_PAUSED (ADR 0067 F-1) |
| strategy.status PAUSED 전이 트리거 | 사용자 토글만 | 사용자 토글 + AutoPauseGuard (RISK_VIOLATION — ADR 0067 F-1 확장) |
| STOMP 토픽 | `/topic/signal.user.{userId}` (v3.4.0) | + `/topic/risk.user.{userId}` BLOCKED·AUTO_PAUSED 신규 (ADR 0067 F-1) |
| order.intent.v1 | 없음 | ALLOWED 시 publish → T-O 후행 진입점 (ADR 0067 §5) |
| risk.violation.v1 | 없음 | BLOCKED 시 publish → ADR 0044 Notification 진입점 (ADR 0067 §5) |
| REST endpoint | 15건 (v3.4.0) | +7건 (risk-config 3 + risk-events 2 + positions 2 — ADR 0067 §5-4) |
| 시나리오 수 | 12건 | 14건 (시나리오 13·14 신규 추가) |
