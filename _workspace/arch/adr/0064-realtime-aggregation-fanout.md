# ADR 0064 — Redis boundary fanout + STOMP 실시간 합산 갱신 (5m·15m·30m·90m·1h)

- 상태: **Accepted (2026-05-12 — 사용자 결정 A-1·B-1·C-1 채택)**
- 작성일: 2026-05-12
- 작성자: architect
- 결정자: 사용자(최고 관리자) — 결정 의제 3건 검토 위임
- 진입 트랙: v3.3.0 미러스톤 (backlog §34)
- 관계:
  - **ADR 0029 (당일 진행 중 캔들 Redis 캐시)** — §2-1 Redis 키 매트릭스 5m~1h 5건 신규 정의 baseline + §2-5 boundary fanout 토픽 가이드 인용. 본 ADR 채택 시 §2-1·§2-3·§2-5 매트릭스 본문 LOCK (현재 v3.3.0 §34 작업 가이드로 분리 표기 → 정합 갱신)
  - **ADR 0062 (5m·15m·30m·90m·1h 합산 가공 — Accepted)** — §4-3 트리거 흐름 본문에 boundary 마감 흐름 명시 + §9 NXT 결합 정합. 본 ADR이 그 boundary 마감 코드 트리거·STOMP fanout 구현 권위
  - **ADR 0048 (KIS WebSocket 41건 구독 한도)** — `H0STCNT0` 체결 stream tick 수신 핸들러가 본 ADR boundary 검사 trigger 진입점
  - **ADR 0063 (NXT/통합거래 매트릭스 cover — Accepted)** — boundary 시각 매트릭스 08:00~20:00 KST cover (5m: 144 bar/일 등) baseline
  - **ADR 0028 (Watchlist Ingestion Worker)** — 구독 대상 종목 풀 (KIS WS slot 30 + polling 종목 모두 cover)
  - **ADR 0019 (캔들 저장 정책)** — DB INSERT 시점 정합 (boundary closing 시 5m~1h interval row append)
  - **ADR 0030 (master-architecture SoT)** — §10 ADR 인덱스 0064 등록 동반
  - **ADR 0023 (Signal Engine = 룰 매칭 only)** — 5m·15m boundary 마감 실시간 시그널 evaluation trigger (T-SIG 본 ADR 채택 baseline)

> **KIS 키 정책 (ADR 0057 정합)**: 본 ADR 모든 KIS WS 체결 수신은 **prod credential 단일 채널** (`AccountType.REAL`). 모의/실전 분기는 주문 라우터(거래 액션)에만. 사용자 메모리 `feedback_kis_first_policy.md`·`feedback_kis_key_realonly_for_data.md` 정합.

---

## 1. 컨텍스트

### 1-1. 현재 main 상태 (사전 조건)

ADR 0062 채택(2026-05-11) + 후속 sub-agent 디스패치(PR #303·#304) 결과:

- **cold-start 합산** — `AggregateBackfillRunner.backfill(code)` 호출 시 1m 전량 → 5m·15m·30m·90m·1h DB INSERT (ON CONFLICT DO NOTHING 멱등)
- **장 마감 합산** — `DailyCloseFinalizer` 20:00 KST scheduled — `flushAggregations()` watchlist 전 종목 당일 누적분 합산 갱신
- **장중 boundary 마감 합산 — 미구현 (현재 결함)** — KIS WS H0STCNT0 체결 수신 핸들러(`KisWebSocketBridge`)가 1m closing은 처리하지만 5m·15m·30m·90m·1h boundary 도달 시점(09:05·09:10·09:15·09:30 등) 합산 갱신 0건

### 1-2. 결함 dogfooding 증거 (사용자 관찰 가능)

장중 사용자가 SymbolDetail 페이지에서 5m 차트 진입 시:

1. 09:00~09:04:59 — `candle:current:{code}:5m` 키 부재 → 5m 차트 마지막 bar는 어제 종가 마감 bar
2. 09:05 boundary 도달 — KIS WS H0STCNT0 체결 수신 + 1m boundary closing은 처리되나 5m 합산 갱신 0건 → 5m 차트 stale
3. 사용자가 새로고침해도 DB는 cold-start + 20:00 flush 시점만 진실 → **장중 5m bar 1개 missing**
4. 20:00 DailyCloseFinalizer 마감 후 첫 다음 영업일에야 5m bar 정합

자동매매 본체 정합 영향:
- **T-SIG (Signal Engine)** — 5m·15m 인터벌 룰 평가 시 stale data → 시그널 latency 5분~수 시간 (boundary 마감 ~ 20:00 flush)
- **T-DSL (Strategy DSL)** — 5m·30m 인터벌 조건식 평가 시 동일 결함
- **MCP 서버 노출 (ADR 0027)** — 외부 client가 장중 5m 데이터 요청 시 stale 응답

### 1-3. ADR 0029·0062 가이드 인용

ADR 0029 §2-5 (v3.2.3 doc cut 갱신):
> "**v3.3.0 §34 작업 가이드 — boundary fanout 토픽 5종 추가 (별도 사이클)**: `/topic/market.candle.5m.{code}` 등 5종 토픽 신규."

ADR 0062 §4-3 (Accepted 본문):
> "5m·15m·30m·90m·1h boundary closing → 직전 1m N건 fetch → CandleAggregator.aggregate(1m, target) → Redis candle:current:{code}:{target} 갱신 → candle table INSERT → Redis publish market.candle.{target}.closed.v1 → STOMP fanout."

본 ADR은 그 boundary 마감 코드 트리거 + STOMP fanout 구현을 LOCK한다.

### 1-4. KIS-first raw 출처 인용 (헌법 §3 의무)

**H0STCNT0 체결가 raw cols (R5 인용, v3.2.2 baseline 03-use-sequences.md §시나리오 3)**:
- URL: `https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py`
- 함수: `ccnl_krx()` — 44 cols, 캐럿(`^`) 구분
- 본 ADR 관련 필드:
  - `[0]` MKSC_SHRN_ISCD — 종목코드
  - `[1]` STCK_CNTG_HOUR — 체결시각 (HHMMSS)
  - `[2]` STCK_PRPR — 현재가(체결가)
  - `[12]` CNTG_VOL — 체결거래량

본 ADR boundary 검사 트리거는 `[1]` STCK_CNTG_HOUR 시각 필드 기반.

---

## 2. 결정 의제 (사용자 결정 대기)

### 2-1. 의제 A — Redis 키 매트릭스 (5종 별도 vs 통합 hash)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **A-1 5종 별도 Redis hash 키** ⭐ | `candle:current:{code}:5m` · `:15m` · `:30m` · `:90m` · `:1h` 각각 hash | 가독성 · 캐시 invalidation 단순(키별 evict) · ADR 0029 §2-1 매트릭스 정합 · DEBUG 시 `redis-cli HGETALL candle:current:005930:5m` 직관 | 키 종목 × 5 = 55개 (10종목 가정) — 미미 |
| A-2 통합 hash 단일 키 | `candle:current:{code}` hash 내부 fields `1m·5m·15m·30m·90m·1h` JSON | 키 개수 절약 · TTL 단일 관리 | invalidation 복잡(field별 HDEL) · field JSON 직렬화 비용 · ADR 0029 §2-1 매트릭스 본문 재정의 필요 |

**architect 권장 = A-1 5종 별도 Redis hash 키**

사유:
1. **ADR 0029 §2-1 매트릭스 정합** — 본 ADR이 채택될 매트릭스가 이미 5종 별도 키로 baseline. 통합 hash로 가면 매트릭스 본문 재발행 + 5건 row → 1건 row 압축으로 가독성 손실
2. **invalidation 단순** — boundary별 evict 시점 다름 (5m 5분 closing / 15m 15분 closing 등). 5종 별도 키는 키별 `DEL` 또는 TTL로 직관 처리
3. **DEBUG·운영 visibility** — 운영 중 사용자 dogfooding 결함 발생 시 `redis-cli HGETALL candle:current:{code}:5m` 한 줄로 5m 현재 상태 inspect 가능
4. **메모리 부담 미미** — 종목 11개(현 watchlist) × 5 interval × ~200 bytes ≈ 11 KB. Redis 한도 영향 0
5. **TTL 정합** — 본 ADR 각 키는 boundary closing 시점에 명시 `DEL` (TTL 자동 만료가 아닌 코드 trigger evict). 통합 hash로 가면 field별 timestamp 비교 로직 추가 → 코드 복잡도 증가

### 2-2. 의제 B — boundary 마감 트리거 (in-process vs 별도 스케줄러)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **B-1 KIS WS 체결 핸들러 in-process 검사** ⭐ | `KisWebSocketBridge` H0STCNT0 수신 시 STCK_CNTG_HOUR 기반 boundary 도달 검사 — 도달 시 즉시 합산 + Redis + STOMP | latency 최소(<50ms 예상) · in-process로 race condition 단순 · 추가 컴포넌트 0 · KIS WS tick = boundary 마감 신호의 가장 빠른 source | tick 0건 종목(거래량 ZERO) boundary 누락 risk — polling fallback 종목 영향 |
| B-2 별도 스케줄러 (Spring @Scheduled) | 5초 주기 scheduled — `BoundaryFinalizer.check()` watchlist 전 종목 boundary 도달 시 합산 | tick 0건 종목도 cover · WS·polling 종목 모두 동일 흐름 | latency 최대 5초 · scheduled 빈도와 boundary 정렬 어긋남 risk · 추가 컴포넌트 |
| B-3 하이브리드 | B-1 우선 + scheduled fallback (1분 주기, boundary 마감 후 30초 grace) | latency + 누락 모두 cover | 코드 복잡도 (이중 trigger 멱등 보장 의무) |

**architect 권장 = B-1 KIS WS 체결 핸들러 in-process 검사 (+ 멱등 보장)**

사유:
1. **latency 최소** — KIS WS H0STCNT0 tick은 매 체결마다 즉시 도달. STCK_CNTG_HOUR가 boundary 도달 시 in-process 검사 → 합산 → Redis → STOMP fanout 총 latency <50ms 예상. T-SIG 실시간 시그널 본질 충족
2. **tick 0건 종목 누락 대응** — `WatchlistPollingWorker` (ADR 0048 §2-4)가 5초 polling으로 `quote:last:{code}` write. polling worker에도 boundary 검사 trigger 추가하면 tick 0건 종목도 cover (B-3 하이브리드의 부분만 채택 — scheduled 추가 컴포넌트 회피)
3. **멱등성 — DB ON CONFLICT DO NOTHING (Flyway V11 정합)** — 같은 boundary가 WS + polling 양쪽에서 trigger 되어도 DB UNIQUE constraint (code, interval, ts) + ON CONFLICT DO NOTHING으로 race 안전
4. **Redis race**: `SETNX candle:current:{code}:{interval}:lock` 100ms TTL 또는 watchlist 종목별 단일 thread 보장 (Kafka partition by code) — 본 ADR §4-3 trigger 흐름에서 LOCK
5. **운영 부담 0** — 추가 컴포넌트(BoundaryFinalizer scheduled) 폐기 → 운영 자원·로그 채널·테스트 표면 최소

### 2-3. 의제 C — STOMP fanout 토픽 (5종 별도 vs 단일 + payload field)

| 옵션 | 설명 | Pro | Con |
|---|---|---|---|
| **C-1 5종 별도 토픽** ⭐ | `/topic/market.candle.5m.{code}` · `.15m.` · `.30m.` · `.90m.` · `.1h.` | frontend subscription 단순 — 사용자가 5m 차트 보면 5m 토픽만 subscribe (network 절약) · ADR 0029 §2-5 baseline 정합 · 토픽 명 inspect 시 의도 직관 | 토픽 명 5종 — frontend STOMP subscription 코드 5건 |
| C-2 단일 토픽 + payload interval field | `/topic/market.candle.aggregated.{code}` payload `{interval: "5m", candle: {...}}` | 토픽 명 1종 — backend publisher 코드 단순 | frontend가 모든 interval 메시지 수신 + payload 필터링 (불필요 메시지 ~80% — 5m 보고 있어도 15m·30m·90m·1h 메시지 수신) · ADR 0029 §2-5 baseline 재정의 필요 |

**architect 권장 = C-1 5종 별도 토픽**

사유:
1. **frontend network 효율** — 사용자가 SymbolDetail에서 5m 차트 보고 있을 때 15m·30m·90m·1h 메시지 수신은 낭비. boundary 도달 시각 다르므로(5m=12개/일 vs 1h=12개/일 vs 90m=8개/일) 단순 합산이 어려움 + frontend payload filter 코드 부담
2. **subscription 단순** — `useSymbolCandles.ts`가 IntervalToggle 선택값에 따라 동적으로 subscribe/unsubscribe — `/topic/market.candle.{interval}.{code}` 패턴화 가능
3. **ADR 0029 §2-5 baseline 정합** — 이미 v3.2.3 doc cut에서 5종 토픽 매트릭스 정의 baseline. C-2로 가면 ADR 0029 §2-5 본문 재발행 필요 (modify-shared)
4. **운영 visibility** — Kafka·STOMP 모니터링 시 토픽별 메시지 수·latency 측정 직관. 단일 토픽이면 interval 분포 별도 metric 추가 필요
5. **5m·15m·30m·90m·1h boundary 시각 매트릭스**(§4-2)에서 5m boundary는 144/일, 1h boundary는 12/일 등 부하 분산 명확
6. **메시지 부하 미미** — §5-2 운영 영향 매트릭스 — 5m boundary = 12개/일 × 11 종목 × 5 interval = ~660 msg/일 = 0.008 msg/s (가벼움)

---

## 3. fitness gap 분석 (사용자 메모리 `feedback_v3_skepticism_no_regression.md` lens)

### 3-1. 자동매매에 필요한가?

**필요** — 5m·15m 실시간 시그널 엔진(T-SIG, ADR 0023) 본질 입력. 현재 결함 상태에서는:
- T-SIG 5m·15m 룰 평가 = stale (장중 5m bar missing → 20:00 flush까지 시그널 latency 5분~수 시간)
- T-DSL (ADR 0022) 5m·30m 인터벌 조건식 = stale
- MCP 외부 client (ADR 0027) 장중 5m 요청 = stale 응답

본 ADR이 자동매매 본체 정합의 결정적 결함을 해결.

### 3-2. v1/v2 잔재 아닌가?

**잔재 아님** — ADR 0029 §2-5 (1m·daily만 정의)는 v3.x baseline. ADR 0062 §4-3 (boundary 흐름)는 2026-05-11 채택. 본 ADR이 그 신규 baseline의 boundary 마감 코드 트리거를 LOCK. v1/v2 시점 코드 변경 0.

### 3-3. ADR 0048 KIS WS 41 한도 + ADR 0028 polling 종목 정합

- KIS WS 30 slot 종목: H0STCNT0 체결 수신 → in-process boundary 검사 (B-1 의제 권장안)
- polling 종목 (post-MVP, 41+ 종목 풀): `WatchlistPollingWorker.fetchPrice()` 5초 주기 응답에 boundary 검사 trigger 추가
- 본 ADR 코드 trigger 진입점 2건 모두 cover

### 3-4. 사용자 비전 정합 (`project_system_vision.md` lens)

- magicJar = **룰 기반 quant trading 본체** — 5m·15m 실시간 시그널이 매매 결정 근거 — 본 ADR 필수
- AI는 옵션 보조 (ADR 0026) — 본 ADR LLM provider import 0건 (W1·W2·W3 영역, 룰 5 정합)
- magicJar = **MCP 서버 노출** — 외부 client 장중 5m 데이터 요청 정합

### 3-5. 회귀 risk 검증

- **regression source 1: 1m closing 흐름** — 본 ADR이 1m closing 코드를 변경하지 않음. 5m~1h boundary 검사만 추가 (orthogonal extension). risk 0
- **regression source 2: DailyCloseFinalizer 20:00 flush** — 본 ADR boundary 갱신이 in-process 동작 + DB ON CONFLICT DO NOTHING 멱등 → 20:00 flush와 충돌 0
- **regression source 3: cold-start AggregateBackfillRunner** — 본 ADR boundary 갱신 후 cold-start 재진입 시 동일 ts row → ON CONFLICT DO NOTHING. risk 0

---

## 4. 채택 시 구현 plan

### 4-1. 컴포넌트 매트릭스 (신규·확장)

| 컴포넌트 | 위치 | 책임 |
|---|---|---|
| **BoundaryDetector** (신규) | `domain/.../market/BoundaryDetector.kt` | Pure Kotlin — `detectBoundary(tickTimeHHmmss: String): List<CandleInterval>` 5m·15m·30m·90m·1h 중 도달한 interval list 반환 (단위 테스트 boundary 매트릭스 144·48·24·8·12 bar/일 cover) |
| **BoundaryAggregator** (확장 또는 신규) | `consumer/.../aggregation/BoundaryAggregator.kt` | KIS WS tick 수신 + BoundaryDetector → 도달 interval별 직전 N건 1m fetch → CandleAggregator.aggregate → Redis 갱신 + DB INSERT + STOMP fanout |
| **KisWebSocketBridge** (확장) | `consumer/.../ws/KisWebSocketBridge.kt` | H0STCNT0 tick 수신 핸들러 — STCK_CNTG_HOUR 추출 후 BoundaryAggregator.onTick(code, hhmmss) 호출 |
| **WatchlistPollingWorker** (확장, post-MVP) | `consumer/.../market/WatchlistPollingWorker.kt` | polling 종목(41+ 풀)에서도 5초 응답 시 BoundaryAggregator.onPoll(code, now()) 호출 |
| **StompCandleForwarder** (신규 또는 확장) | `api/.../stomp/StompCandleForwarder.kt` | Kafka `market.candle.{interval}.closed.v1` consumer → `/topic/market.candle.{interval}.{code}` STOMP fanout |
| **RedisCandleCache** (확장) | `api/.../infrastructure/cache/RedisCandleCache.kt` | `getCurrent(code, interval): Candle?` + `putCurrent(code, interval, candle)` + `evictCurrent(code, interval)` 5종 cover |

### 4-2. boundary 시각 매트릭스 (ADR 0062 §9 + ADR 0063 NXT cover 정합)

NXT 통합 거래시간 08:00~20:00 KST cover (ADR 0063):

| Interval | boundary 시각 (KST) | bar/일 |
|---|---|---|
| 5m | 08:05·08:10·08:15·…·19:55·20:00 | 144 |
| 15m | 08:15·08:30·08:45·…·19:45·20:00 | 48 |
| 30m | 08:30·09:00·09:30·…·19:30·20:00 | 24 |
| 90m | 09:30·11:00·12:30·14:00·15:30·17:00·18:30·20:00 | 8 |
| 1h | 09:00·10:00·11:00·…·19:00·20:00 | 12 |

**boundary 도달 검사 알고리즘 (Pure Kotlin, BoundaryDetector)**:
```kotlin
// 의사 코드 — 본 ADR plan 단계 (실제 구현은 sub-agent)
fun detectBoundary(hhmmss: String): List<CandleInterval> {
    val time = LocalTime.parse(hhmmss, KIS_TIME_FORMAT)  // "093015" → 09:30:15
    val minuteOfDay = time.hour * 60 + time.minute       // 09:30 → 570
    val result = mutableListOf<CandleInterval>()
    
    // NXT cover: 08:00 = 480분, 20:00 = 1200분
    if (minuteOfDay < 480 || minuteOfDay > 1200) return result
    
    // 5m boundary: 08:00 기준 5분 단위 일치 + 초가 0~59 cover
    if ((minuteOfDay - 480) % 5 == 0 && time.minute != lastCheckedMinute) result += FIVE_MIN
    // 15m boundary: 08:00 기준 15분 단위
    if ((minuteOfDay - 480) % 15 == 0) result += FIFTEEN_MIN
    // 30m boundary: 08:00 기준 30분 단위
    if ((minuteOfDay - 480) % 30 == 0) result += THIRTY_MIN
    // 90m boundary: ADR 0062 §9 8개/일 hardcoded set membership
    if (minuteOfDay in BOUNDARY_90M_SET) result += NINETY_MIN
    // 1h boundary: 08:00 기준 60분 단위
    if ((minuteOfDay - 480) % 60 == 0) result += ONE_HOUR
    
    return result
}
```

**경계 케이스**:
- KIS WS tick은 매 체결 시 도달 — 같은 boundary 분에 여러 tick (예: 09:05:00, 09:05:01, 09:05:23) → BoundaryDetector는 첫 검출만 처리, 이후 같은 분 내 tick은 skip (in-process 종목별 lastBoundaryProcessed map)
- tick 0건 종목 (거래량 ZERO): WS tick 수신 자체가 0 → polling worker가 5초 주기 trigger (B-1 권장안 + polling extension)
- DST·시각 보정: 한국 표준시 단일 — DST 미해당. NTP sync 정합 (ADR 0037 정합)

### 4-3. 트리거 흐름 (B-1 권장안 채택 시)

```
KIS WS H0STCNT0 tick 도달
   ├─ KisWebSocketBridge.onMessage(payload)
   ├─ TickEvent 파싱 (STCK_CNTG_HOUR + STCK_PRPR + CNTG_VOL + ...)
   ├─ Redis quote:last:{code} write (기존)
   ├─ Redis candle:current:{code}:1m 갱신 (기존 — ADR 0029)
   └─ BoundaryAggregator.onTick(code, hhmmss)
        ↓
BoundaryDetector.detectBoundary(hhmmss) → List<CandleInterval>
   ↓
boundary 도달 (예: 09:30:00 → [5m, 15m, 30m])
   ↓
interval별 처리 (순차 또는 병렬 — 본 ADR plan은 순차 권장 — race 회피):
   FOR target IN detected:
       1. 직전 1m N건 fetch (Redis candle:current:{code}:1m 또는 DB recent)
          - 5m → 직전 5건 1m
          - 15m → 직전 15건 1m
          - 30m → 직전 30건 1m
          - 90m → 직전 90건 1m
          - 1h → 직전 60건 1m
       2. CandleAggregator.aggregate(rows, target) → Candle (Pure Kotlin)
       3. Redis candle:current:{code}:{target} write
       4. DB candle table INSERT (interval=target) — ON CONFLICT DO NOTHING
       5. Kafka publish market.candle.{target}.closed.v1 { code, ts, ohlcv, source: "boundary" }
       6. (api side) StompCandleForwarder → /topic/market.candle.{target}.{code} fanout

장 마감 20:00 KST
   └─ DailyCloseFinalizer.flushAggregations() (기존 — ADR 0062 §9·PR #303)
        ├─ Redis candle:current:{code}:5m~1h 키 evict
        └─ 멱등 — 이미 INSERT된 row는 ON CONFLICT DO NOTHING
```

### 4-4. Redis 키 매트릭스 (ADR 0029 §2-1 LOCK)

본 ADR 채택 시 ADR 0029 §2-1 매트릭스 본문 LOCK (현재 "v3.3.0 §34 작업 가이드" 표기 → "Accepted, 본 ADR 0064 본문 권위"):

| 캐시 키 | 데이터 | 갱신 주기 | TTL/evict |
|---|---|---|---|
| `candle:current:{code}:5m` | 당일 진행 중 5m bar (NXT 144 bar/일 cover) | 5m boundary 마감 시 1m 5건 합산 | 5분 closing 시 evict + 20:00 flush |
| `candle:current:{code}:15m` | 당일 진행 중 15m bar (NXT 48 bar/일 cover) | 15m boundary 마감 시 1m 15건 합산 | 15분 closing 시 evict + 20:00 flush |
| `candle:current:{code}:30m` | 당일 진행 중 30m bar (NXT 24 bar/일 cover) | 30m boundary 마감 시 1m 30건 합산 | 30분 closing 시 evict + 20:00 flush |
| `candle:current:{code}:90m` | 당일 진행 중 90m bar (NXT 8 bar/일 cover) | 90m boundary 마감 시 1m 90건 합산 | 90분 closing 시 evict + 20:00 flush |
| `candle:current:{code}:1h` | 당일 진행 중 1h bar (NXT 12 bar/일 cover) | 60m boundary 마감 시 1m 60건 합산 | 1시간 closing 시 evict + 20:00 flush |

### 4-5. STOMP 토픽 매트릭스 (ADR 0029 §2-5 LOCK)

본 ADR 채택 시 ADR 0029 §2-5 매트릭스 본문 LOCK:

```
/topic/market.candle.5m.{code}     - 5m boundary closing 이벤트 (NXT 08:00~20:00 KST 144건/일)
/topic/market.candle.15m.{code}    - 15m boundary closing 이벤트 (NXT 48건/일)
/topic/market.candle.30m.{code}    - 30m boundary closing 이벤트 (NXT 24건/일)
/topic/market.candle.90m.{code}    - 90m boundary closing 이벤트 (NXT 8건/일)
/topic/market.candle.1h.{code}     - 1h boundary closing 이벤트 (NXT 12건/일)
```

**payload schema (Kafka `market.candle.{interval}.closed.v1`)**:
```json
{
  "code": "005930",
  "interval": "5m",
  "ts": 1747022100,
  "open": 78000.0,
  "high": 78500.0,
  "low": 77800.0,
  "close": 78200.0,
  "volume": 152300,
  "source": "boundary",
  "publishedAt": "2026-05-12T09:05:00.123+09:00"
}
```

### 4-6. 멱등성·race condition 가드

| 시나리오 | 가드 |
|---|---|
| 같은 boundary 분 내 multiple tick (예: 09:05:00·09:05:01·09:05:23) | BoundaryAggregator in-process `lastBoundaryProcessed: Map<Pair<String, CandleInterval>, LocalDate>` — 같은 (code, interval, date)는 1회만 처리 |
| WS + polling 동시 trigger (post-MVP) | DB ON CONFLICT DO NOTHING (Flyway V11 UNIQUE constraint code+interval+ts) + Redis SETNX 100ms lock 또는 Kafka partition by code (단일 thread 보장) |
| DailyCloseFinalizer 20:00 flush 시 boundary 채 도달 안 한 종목 | flushAggregations()이 5m~1h 합산을 backfill (cold-start 패턴) — 결손 종목 자동 cover |
| 컨테이너 재기동 시 in-process map 손실 | KIS WS 재연결 후 첫 tick에서 boundary 검사 재진입 — DB ON CONFLICT DO NOTHING으로 멱등 |
| 합산 결과 staleness (1m row가 후행 backfill로 변경) | 본 ADR scope 외 — 1m append-only 가정 (ADR 0062 §6-1 절충 정합) |

---

## 5. 운영 영향

### 5-1. Redis 메모리 부담

종목 11개 × 5 interval × ~200 bytes (hash field 7~8개 — open·high·low·close·volume·timestamp·sequence) ≈ **11 KB**. 미미. ADR 0029 §2-1 매트릭스에 이미 기 반영 (보조 키 추가만으로 메모리 영향 측정).

### 5-2. STOMP 메시지 부하

| Interval | bar/일 | 종목 | msg/일 |
|---|---|---|---|
| 5m | 144 | 11 | 1,584 |
| 15m | 48 | 11 | 528 |
| 30m | 24 | 11 | 264 |
| 90m | 8 | 11 | 88 |
| 1h | 12 | 11 | 132 |
| **합** | — | — | **2,596 msg/일** |

12시간 cover 기준 ≈ **0.06 msg/s** (매우 가벼움). 1m closing fanout (현재 11 × 720 = 7,920/일) 대비 33% 추가. STOMP broker(`SimpleBrokerMessageHandler`) 부담 영향 0.

### 5-3. latency 예측

- KIS WS H0STCNT0 tick → KisWebSocketBridge → BoundaryDetector: **<5ms**
- 직전 1m N건 fetch (Redis hot path): **<5ms** (60건 1h cover 최대)
- CandleAggregator.aggregate (Pure Kotlin in-memory): **<2ms**
- Redis put + DB INSERT (ON CONFLICT DO NOTHING): **<10ms**
- Kafka publish → STOMP fanout: **<20ms** (broker latency)

**총 end-to-end latency 예상: <50ms** (사용자 체감 1초 이내 정합).

### 5-4. KIS Rate Limit 영향 (ADR 0037)

본 ADR boundary 검사는 **KIS REST 추가 호출 0건** (KIS WS H0STCNT0 tick 기반 + 1m 직전 N건은 Redis/DB local fetch). Rate Limit 영향 0.

### 5-5. DB 부담

boundary INSERT 추가 = 종목 11개 × (144+48+24+8+12) bar/일 = 2,596 rows/일. 1m INSERT (11 × 720 = 7,920/일) 대비 33%. PostgreSQL 영향 미미.

### 5-6. KIS WS slot 영향 (ADR 0048)

본 ADR은 기존 H0STCNT0 30 slot stream을 그대로 활용. 추가 slot 0건. ADR 0048 41 한도 영향 0.

---

## 6. 절충 + Rollback 시나리오

### 6-1. 절충

- **장중 boundary 마감 시 in-process 검사 부담** — 매 tick마다 hhmmss 파싱 + boundary detector 호출. 1 tick = ~10μs 추정 (in-process arithmetic). 1m closing 기존 trigger와 동일 path → 부담 미미
- **5종 별도 STOMP 토픽의 frontend subscription 5건** — `useSymbolCandles.ts` IntervalToggle 변경 시 dynamic subscribe/unsubscribe. 코드 ~30 LOC 추정
- **DailyCloseFinalizer 20:00 flush redundancy** — 본 ADR boundary 갱신 후 20:00 flush가 동일 row 재INSERT 시도 → ON CONFLICT DO NOTHING. 부담 미미. **장점**: 20:00 flush는 safety net 역할 (boundary 갱신 누락 시 fallback)
- **WS·polling 이중 trigger (post-MVP)** — 같은 boundary가 양쪽에서 trigger되어도 멱등 보장(§4-6). 부담 0

### 6-2. Rollback 시나리오 (본 ADR을 supersede 또는 revoke 결정 case)

1. **boundary 검사 in-process 부담이 KIS WS 메시지 처리 latency 초과 (예: tick 1000건/s) — 측정 후 결정 (B-2 별도 스케줄러로 전환 검토)**
2. **boundary 5종 토픽 frontend 부담 과도** — C-2 단일 토픽 + payload field로 전환
3. **DailyCloseFinalizer 20:00 flush로 충분 (사용자 dogfooding 결함 재발 없음)** — 본 ADR 자체 revoke + boundary 갱신 코드 제거

### 6-3. Rollback 절차 (case 3 — 본 ADR 자체 revoke)

1. 본 ADR 상태 `Superseded by ADR XXXX, YYYY-MM-DD` 헤더 갱신
2. `BoundaryDetector.kt`·`BoundaryAggregator.kt` 제거 또는 `@Component @Conditional(disabled)` 비활성
3. `KisWebSocketBridge` boundary 검사 trigger line 제거
4. `StompCandleForwarder` 5종 토픽 fanout 제거
5. Redis `candle:current:{code}:5m~1h` 키는 휘발 → 자연 만료 (코드 자체 evict 없음)
6. ADR 0029 §2-1·§2-5 매트릭스 본문 "Proposed v3.3.0 §34 작업 가이드" 표기 복원
7. CHANGELOG row 추가 + master-architecture §10 ADR 인덱스 갱신

---

## 7. 채택 시 후속 작업 (별도 PR — 본 ADR `Accepted` 갱신 후 sub-agent 디스패치)

본 plan PR은 **architect 단독 — ADR/문서만**. 실무 코드 변경은 별도 sub-agent PR 매트릭스.

### 7-1. sub-agent 디스패치 매트릭스

| sub-agent | 영역 | 산출물 |
|---|---|---|
| **market-data** | (1) `domain/.../market/BoundaryDetector.kt` 도메인 본체 (Pure Kotlin, 단위 테스트 boundary 매트릭스 5m·15m·30m·90m·1h × NXT 08:00~20:00 cover). (2) `consumer/.../aggregation/BoundaryAggregator.kt` 신규 — KisWebSocketBridge 통합 + Redis 갱신 + DB INSERT + Kafka publish. (3) `KisWebSocketBridge` H0STCNT0 tick 핸들러 확장 — STCK_CNTG_HOUR boundary 검사 trigger 진입점. (4) `RedisCandleCache` 5종 키 확장. (5) `WatchlistPollingWorker` polling tick boundary 검사 (post-MVP). (6) Kafka 토픽 `market.candle.{interval}.closed.v1` 5종 신규 또는 단일 `market.candle.boundary.closed.v1` (의제 C 결정 정합) | 본 ADR §4 plan 1:1 매핑 + KIS-first raw 출처 inline 주석 의무 |
| **frontend** | (1) `frontend/src/hooks/useSymbolCandles.ts` STOMP 5종 토픽 dynamic subscribe/unsubscribe — IntervalToggle 선택값 정합. (2) `LightweightChart.tsx` boundary 마감 메시지 수신 시 마지막 bar update (LWC v4 `series.update()`). (3) `SymbolChart.tsx` interval 5종 boundary closing 차트 갱신 검증 dogfooding evidence | UI dogfooding evidence + frontend test 5건 (interval별 STOMP 수신 → 차트 update 검증) |
| (선택) **qa** | (1) boundary 정합 검증 — sample 영업일 5m·15m·30m·90m·1h boundary 도달 시각 KIS WS log inspect. (2) DailyCloseFinalizer 20:00 flush redundancy 멱등 검증 (sample SELECT count) | spec test 또는 dogfooding evidence 보고 |

### 7-2. CHANGELOG 슬롯 사전 발행 (헌법 §4)

본 plan PR diff에 sub-agent별 CHANGELOG 슬롯 row + anchor 사전 prepend는 **본 plan 단계에서는 0** — 사용자 결정 후 후속 PR에서 발행 (사용자 메모리 `feedback_spec_plan_scope.md` — 작은 묶음 제외 정합. 본 plan PR은 .md 신규 4 파일 add-only로 작은 묶음).

### 7-3. 영향 ADR/문서 매트릭스 (정합 갱신 지시, 별도 사이클)

| 문서 | 갱신 내용 | 갱신 시점 |
|---|---|---|
| ADR 0029 (당일 진행 중 캔들 Redis 캐시) | §2-1 매트릭스 본문 LOCK (현 "v3.3.0 §34 작업 가이드" 표기 → "Accepted, ADR 0064 본문 권위") + §2-3 boundary 흐름 본문 LOCK + §2-5 STOMP 5종 토픽 본문 LOCK | 본 ADR `Accepted` 갱신 시 동시 (또는 별도 사이클) |
| ADR 0048 (KIS WS 41 한도) | §3-2 흐름 본문 — KIS WS tick 수신 trigger boundary aggregator 호출 단계 추가 | 본 ADR `Accepted` 갱신 시 동시 (또는 별도 사이클) |
| ADR 0062 (5m·15m·30m·90m·1h 합산 가공) | §4-3 트리거 흐름 본문 — 본 ADR 0064 인용 + boundary 마감 흐름 LOCK | 본 ADR `Accepted` 갱신 시 동시 (또는 별도 사이클) |
| ADR 0019 (캔들 저장 정책) | §2-1 매트릭스 — 5m~1h INSERT 트리거에 "boundary closing (ADR 0064)" 추가 | 본 ADR `Accepted` 갱신 시 동시 (또는 별도 사이클) |
| ADR 0030 (master-architecture SoT) + master §10 | ADR 0064 인덱스 row 추가 | 본 plan PR 동시 |
| `_workspace/arch/adr/README.md` | ADR 0064 index row 추가 (§5 시장 데이터 11건) + §12 후속 예정 시프트 | 본 plan PR 동시 |
| `_workspace/backlog.md` §34 | "방향 확정 + 결정 대기" → "ADR 0064 plan 발행 + 사용자 결정 대기" 갱신 | 본 plan PR 동시 |
| `docs/architecture/candle-data-policy-v2.md` | §3·§6·§7 boundary 마감 trigger 단락 추가 | 본 ADR `Accepted` 갱신 시 별도 사이클 |

### 7-4. external-api-policy 영향

본 ADR 채택 시 외부 API 정책 영향 0 — KIS WS 기존 stream(H0STCNT0) 활용 + REST 추가 호출 0건.

---

## 8. 결과 (채택 시)

- **장중 5m·15m·30m·90m·1h boundary 마감 시 실시간 차트 갱신** — 사용자 dogfooding 결함 해소
- **T-SIG (Signal Engine) 실시간 시그널 본질 충족** — 5m·15m 룰 평가 latency <50ms
- **T-DSL 인터벌 조건식 정합** — 30m·90m DSL 평가 stale 0
- **MCP 외부 client 정합** — 장중 5m~1h interval 요청 fresh 응답
- **KIS REST 추가 호출 0건** — Rate Limit 영향 0
- **Redis 메모리 +11KB** — 미미
- **STOMP 메시지 부하 +33%** — 0.06 msg/s 추가, 매우 가벼움
- **DailyCloseFinalizer 20:00 flush는 safety net 역할 유지** — boundary 누락 시 fallback

---

## 9. 참고

- ADR 0019 — 캔들 저장 정책 (Watchlist 기반)
- ADR 0023 — Signal Engine = 룰 매칭 only (본 ADR 채택 baseline T-SIG)
- ADR 0027 — MCP 서버 미래 노출 (외부 client 5m~1h 정합)
- ADR 0028 — Watchlist Ingestion Worker (구독 풀)
- ADR 0029 — 당일 진행 중 캔들 Redis 캐시 (§2-1 매트릭스 baseline)
- ADR 0030 — master-architecture SoT
- ADR 0037 — KIS Rate Limit (본 ADR 영향 0)
- ADR 0048 — KIS WebSocket 41건 구독 한도 (H0STCNT0 stream baseline)
- ADR 0057 — KIS-only 데이터 정책 (credential 단일)
- ADR 0062 — 5m·15m·30m·90m·1h 합산 가공 (§4-3 boundary 흐름 본 ADR 권위)
- ADR 0063 — NXT/통합거래 매트릭스 cover (boundary 시각 08:00~20:00 cover baseline)
- `docs/architecture/candle-data-policy-v2.md` — 운영 가이드
- `_workspace/baseline/v3.2.2/03-use-sequences.md` §시나리오 3 R5 — H0STCNT0 44 cols raw 출처
- KIS open-trading-api repo `examples_user/domestic_stock/domestic_stock_functions_ws.py` `ccnl_krx()` — H0STCNT0 raw 권위
- 사용자 메모리 `feedback_v3_skepticism_no_regression.md` (fitness gap lens) · `feedback_kis_first_policy.md` (KIS-only) · `feedback_kis_key_realonly_for_data.md` (prod 단일 채널) · `feedback_no_mock_data.md` (mock 합성 금지) · `feedback_3tier_hierarchy.md` (architect plan → sub-agent 병렬) · `feedback_spec_plan_scope.md` (작은 묶음 spec 면제) · `project_system_vision.md` (룰 기반 quant trading + MCP 비전)
- `_workspace/backlog.md` §34 (방향 확정 baseline)
