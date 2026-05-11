# ADR 0019 — 캔들 저장 정책 (Watchlist 기반 + Yahoo 분봉 DB 영속)

- 상태: **Accepted** (Yahoo 의존 부분만 보류 — ADR 0057 정합. ADR 0062 합산 5종 + ADR 0063 NXT cover 재통합 — 2026-05-12 §2 매트릭스 갱신)
- 작성일: TBD
- 결정자: architect
- 관계: ADR 0057 (KIS OpenAPI 단일 데이터 출처) · **ADR 0062 (5m~1h 합산 가공 백엔드 precomputed — 2026-05-11 Accepted)** · **ADR 0063 (NXT/통합거래 매트릭스 cover — 2026-05-11 Accepted)** · ADR 0028(Watchlist Ingestion Worker) · ADR 0029(당일 진행 중 캔들 Redis 캐시) · ADR 0037(KIS Rate Limit)

> **2026-05-11 노트 (사용자 명시 정책 정정)**: §2 매트릭스의 Yahoo 의존 부분은 보류 상태 — 이번 사이클은 `1m·1d·1wk·1mo·1y`만 KIS-only로 운용(ADR 0057). 5m·15m·30m·90m·1h 인터벌은 **1m 합산 가공으로 처리 결정** (백엔드 또는 프론트 합산, 구현 사이클은 별도 backlog §31). 본 ADR 본문의 결정 골격(Watchlist 풀 기반·DB 영속·합성 폐기 원칙·LRU 폐기·CHECK constraint)은 **여전히 유효**하며, 5m~1h 합산 가공 트랙이 완료되면 §2 매트릭스에 재통합 예정. **폐기 아님 — 사용자 명시 "ADR 폐기는 하지말어 백로그로 남겨"**.
>
> **2026-05-12 노트 (v3.2.3 doc cut — ADR 0062·0063 Accepted 재통합)**: ADR 0062(백엔드 precomputed 합산 5종) + ADR 0063(NXT/UN 통합 08:00~20:00 cover) 사용자 Accepted (2026-05-11) — §2-5 "합성 폐기" 결정 부분 revoke 상태 (ADR 0062 §3 권장안 채택 시 CandleAggregator 부활). 본 ADR §2-1 매트릭스에 **NXT 시각 행 (08:00~20:00 KST) + 합산 5종 행 (5m·15m·30m·90m·1h, 백엔드 precomputed)** 추가, §2-2 UI 매트릭스에 **5종 활성화** 갱신. Yahoo 의존 인용은 그대로 보존 (rollback baseline). ADR 0057 supersede 헤더 표기는 ADR 0019 본문 보존 정합 (사용자 명시 "ADR 폐기 하지말어").

## 1. 컨텍스트

magicJar는 다음 4 종류의 캔들을 다룬다:

1. **틱**: KIS WS 실시간
2. **분봉 (1m·2m·5m·15m·30m·90m·1h)**: 사용자 차트·DSL·screener·signal·backtest의 base
3. **일봉 이상 (1d·1wk·1mo·1y)**: 룰 엔진·백테스트의 long-term base
4. **당일 진행 중 캔들**: 장중 OHLCV가 매 틱 변동 (분봉·일봉 모두)

Yahoo Finance interval별 한도가 명확 (ADR — `external-api-policy.md` §3):
- 1m → 7일 / 2m·5m·15m·30m·90m → 60일 / 1h → 730일 / 1d 이상 → 전체

KIS REST는 일봉/주봉/월봉/년봉 backfill 가능. 분봉은 당일분만 제공.

## 2. 결정

### 2-1. 데이터 영속 매트릭스

> **2026-05-12 갱신 (ADR 0062·0063 Accepted 재통합)**: 보류 Yahoo 행을 KIS-only로 갱신 + NXT 시각 행 + 합산 5종 행 추가. Yahoo 의존 row는 폐기 표기로 보존 (rollback baseline — ADR 0057 §4-2).

| Interval | Source | KIS endpoint / tr_id | Cache | DB 영속 | 시각 cover |
|---|---|---|---|---|---|
| **틱** | KIS WS `H0STCNT0` | — | Redis (휘발, retention 1h) | X | NXT 통합 08:00~20:00 KST (ADR 0063) |
| ~~1m / 2m / 5m / 15m / 30m / 90m / 1h~~ | ~~Yahoo (Watchlist 진입 시 max 한도 fetch)~~ | ~~—~~ | ~~—~~ | ~~DB 영속~~ | **폐기 (ADR 0057 — Yahoo 전면 폐기, 2026-05-11)** |
| **1m (1분봉, NXT cover 720 bar/일)** | KIS REST | `FHKST03010230` (30 거래일 페이지네이션, prod credential) | Redis (당일) | **DB 영속 (30 거래일, KOSPI=UN/KOSDAQ=J 분기 — ADR 0063·PR #304)** | **NXT 통합 08:00~20:00 KST** |
| **5m (5분봉, NXT cover 144 bar/일)** | **1m 합산 (백엔드 precomputed — CandleAggregator)** | — | Redis (당일 진행 중 boundary 마감 시) | **DB 영속 (30 거래일, 종목당 ~4.3K rows)** | **NXT 통합 08:00~19:55 KST (boundary 5분 단위)** |
| **15m (15분봉, NXT cover 48 bar/일)** | **1m 합산 (백엔드 precomputed)** | — | Redis (당일 진행 중 boundary 마감 시) | **DB 영속 (30 거래일, 종목당 ~1.4K rows)** | **NXT 통합 08:00~19:45 KST** |
| **30m (30분봉, NXT cover 24 bar/일)** | **1m 합산 (백엔드 precomputed)** | — | Redis (당일 진행 중 boundary 마감 시) | **DB 영속 (30 거래일, 종목당 ~720 rows)** | **NXT 통합 08:00~19:30 KST** |
| **90m (90분봉, NXT cover 8 bar/일)** | **1m 합산 (백엔드 precomputed)** | — | Redis (당일 진행 중 boundary 마감 시) | **DB 영속 (30 거래일, 종목당 ~240 rows)** | **NXT 통합 08:00·09:30·11:00·12:30·14:00·15:30·17:00·18:30 KST** |
| **1h (60분봉, NXT cover 12 bar/일)** | **1m 합산 (백엔드 precomputed)** | — | Redis (당일 진행 중 boundary 마감 시) | **DB 영속 (30 거래일, 종목당 ~360 rows)** | **NXT 통합 08:00~19:00 KST** |
| **당일 진행 중 분봉** | KIS REST·WS | `FHKST03010200` + WS tick | Redis & DB | DB INSERT (분 단위 closing 시) | NXT 통합 08:00~20:00 KST |
| **1d / 1wk / 1mo / 1y** | KIS REST | `FHKST03010100` (period='D/W/M/Y', KOSPI=UN/KOSDAQ=J 분기) | Redis cache (옵션) | **DB 영속** | 일봉 기준 (NXT 종가 통합) |
| **당일 진행 중 일봉** | KIS REST·WS tick | — | Redis only (휘발, 장 마감까지) | **20:00 KST 후 DB INSERT (`DailyCloseFinalizer`)** | NXT 마감 20:00 KST |

**합산 5종 행 (5m·15m·30m·90m·1h) 산출 근거**:
- 종목당 ~6.4K rows × 풀 30종목 ≈ **192K rows 합산 영속** (10년 운영 시)
- 1m 베이스 720 bar/일 × 30 거래일 × 30종목 ≈ 648K rows — 합산은 30% 추가 부담
- 합산 트리거: cold-start 1m 적재 직후 + 장중 boundary 마감 시 + 20:00 KST `flushAggregations()` (ADR 0029 갱신)

**NXT 시각 행 (08:00~20:00 KST) 산출 근거**:
- KRX 정규장 6.5시간 + NXT pre-hour 1시간 + NXT after-hour 4.5시간 = 12시간 cover
- `fid_cond_mrkt_div_code` 분기 — KOSPI=UN(NXT 통합), KOSDAQ=J(NXT 미상장 → UN 빈 응답 회피, PR #304)
- raw URL 인용: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py` (헌법 §3 KIS-first)

### 2-2. UI 노출 interval (ADR 0057 + 0062 정합)

> **2026-05-12 갱신 (ADR 0062 C 채택 — 5종 활성화)**: 합산 5종(5m·15m·30m·90m·1h) IntervalToggle 모두 활성화 (PR #302 frontend).

자유 입력 폐기. 버튼만:

```
분봉: [1m]                                                       ← KIS REST 30 거래일 + 당일 (NXT cover)
중간봉: [5m] [15m] [30m] [90m] [1h]                              ← **활성화 (1m 합산 백엔드 precomputed — ADR 0062)**
일봉: [1d] [1wk] [1mo] [1y]                                      ← KIS REST 전체 기간
```

각 interval source 매트릭스:
- **1m·1d·1wk·1mo·1y** = KIS REST 직접 제공 (합성 0)
- **5m·15m·30m·90m·1h** = **백엔드 precomputed 합산 (CandleAggregator, ADR 0062 §3 권장안)** — 모든 클라이언트(REST API·MCP·룰엔진·백테스트) 동일 source 참조

**폐기 표기 보존**: `2m` interval은 KIS 미제공 + 사용자 비전 정합 0 → IntervalToggle 미노출 (CHECK constraint에는 유지 — rollback baseline).

### 2-3. cold-start 흐름

> **2026-05-12 갱신 (ADR 0062·0063 Accepted)**: Yahoo backfill 흐름 폐기 + KIS REST cold-start 흐름 정합 (ADR 0028 §2-3 권위 인용). 1m 적재 직후 `AggregateBackfillRunner.backfill(code)` 호출 — 5m·15m·30m·90m·1h 일괄 합산 INSERT (PR #303 구현). KOSPI=UN/KOSDAQ=J 분기 (ADR 0063·PR #304). halt/liquidation 종목 skip + `KIS_COLD_START_SKIPPED` audit (PR #304 구현).

사용자가 Watchlist에 종목 등록 시 (ADR 0028 — `WatchlistChangedKafkaListener` + `ColdStartExecutor`):

```
0초:    api POST /api/watchlists  (body: {code})
        → DB watchlist INSERT
        → Kafka watchlist.changed.v1
1~12초: consumer WatchlistChangedKafkaListener
        ├─ instrument halt/liquidation 검사
        │   └─ true → KIS_COLD_START_SKIPPED audit + return (PR #304)
        ├─ KIS WS subscribe(code) (틱 + 당일 분봉, prod credential)
        ├─ market 분기 → fid_cond_mrkt_div_code 결정 (PR #304)
        │   ├─ KOSPI → "UN" (NXT cover 08:00~20:00 KST, ADR 0063)
        │   └─ KOSDAQ → "J" (NXT 미거래 → UN 빈 응답 회피)
        ├─ ColdStartExecutor.KIS_COLD_START_INTERVALS 5종 fetch:
        │   ├─ 1m  : FHKST03010230 — 30 거래일 페이지네이션 → DB INSERT
        │   ├─ 1d  : FHKST03010100 (period='D') — 200건 → DB INSERT
        │   ├─ 1wk : FHKST03010100 (period='W') — 200건 → DB INSERT
        │   ├─ 1mo : FHKST03010100 (period='M') — 120건 → DB INSERT
        │   └─ 1y  : FHKST03010100 (period='Y') — 10건 → DB INSERT
        └─ AggregateBackfillRunner.backfill(code) (PR #303, ADR 0062 D):
            ├─ 1m 전량 fetch (~10K~20K rows)
            ├─ CandleAggregator.aggregate(1m, target) × 5종 in-memory 합산
            ├─ 5m·15m·30m·90m·1h DB INSERT (ON CONFLICT DO NOTHING)
            └─ audit KIS_COLD_START_DONE + aggregateInserted 필드
12+초:  사용자 /symbol/{code} 진입 시 모든 interval 즉시 응답 (10종 — 1m·5m·15m·30m·90m·1h·1d·1wk·1mo·1y)
```

**raw 출처 (헌법 §3 KIS-first)**: `inquire_time_itemchartprice` (FHKST03010230, 1m) + `inquire_daily_itemchartprice` (FHKST03010100, 1d·1wk·1mo·1y). prod credential 단일 채널 (`AccountType.REAL`, 사용자 메모리 `feedback_kis_key_realonly_for_data.md`).

### 2-4. LRU 정책 폐기

Watchlist 풀이 자연 universe 한도 (ADR 0028 §2-2). 풀 size 30 초과 시 등록 시점 우선순위로 자동 unsub. **DB row LRU 폐기** — 풀 내 종목은 전 기간 보존.

### 2-5. 합성 폐기 → **부활 (ADR 0062 Accepted, 2026-05-11)**

> **2026-05-12 정정 (ADR 0062 §3 권장안 채택)**: 본 §2-5는 ADR 0062 Accepted 시점에 **revoke** 상태. 사용자 명시 결정으로 `CandleAggregator` 부활 — 백엔드 precomputed (위치 A-1) + source 옵션 2(과거 30 거래일) 채택. 5m·15m·30m·90m·1h은 1m → N분봉 합산으로 정합 (MCP·룰엔진·백테스트 동일 source). frontend `candleAggregator.ts`는 여전히 폐기 (백엔드가 단일 source).

- ~~이전 정책의 `CandleAggregator` (M1 → N분봉 백엔드 합성) 폐기~~ — **부활 (`domain/.../market/CandleAggregator.kt` 신규, PR #302 D 구현 완료)**
- `candleAggregator.ts` (frontend) — **폐기 유지** (백엔드 precomputed 단일 source)

### 2-6. CHECK constraint

`candle.interval`은 다음만 허용:
```sql
ALTER TABLE candle ADD CONSTRAINT candle_interval_chk
  CHECK (interval IN ('1m','2m','5m','15m','30m','90m','1h','1d','1wk','1mo','1y'));
```

## 3. 결과

- **DB 영속 단순** — 모든 interval이 DB의 단일 진실원
- **합성 코드 0** — 백엔드·프론트엔드 모두 합성 함수 부재
- **Watchlist 진입 시 1회 cold-start으로 모든 interval 채워짐** — 사용자 차트 즉시 표시
- **당일 분봉만 Redis & DB dual** (장중 변동 OHLCV) — 그 외는 DB only
- **Rate Limit 안전** (ADR 0037 정합) — Watchlist 풀이 자연 한도

## 4. 절충

- 분봉 영속 디스크 부담 — 풀 30종목 × 1m 7일 × 390 분봉/일 ≈ 82K rows (~12 MB) / 5m 60일 × 78 ≈ 17K rows / 1h 730일 × 6.5 ≈ 4.7K rows. 풀당 ≤ 100 MB
- 풀 100종목 확장 시 ≤ 1 GB — PostgreSQL 단일 인스턴스(ADR 0006) 한도 안
- 컨테이너 재기동 시 분봉·일봉 모두 DB 영속이라 stale 0. Redis는 당일 진행 중만 휘발

## 5. 참고

- **ADR 0057** — KIS OpenAPI 단일 데이터 출처 (본 ADR Yahoo 의존 폐기 baseline)
- **ADR 0062** — 5m~1h 합산 가공 (백엔드 precomputed 부활 + 옵션 2 과거 30 거래일 + 5종 활성화 — 2026-05-11 Accepted)
- **ADR 0063** — NXT/통합거래 매트릭스 cover (UN 통합 + 08:00~20:00 + threshold 700 — 2026-05-11 Accepted)
- ADR 0028 — Watchlist 기반 Ingestion Worker (본 정책의 trigger, cold-start 흐름 권위 인용)
- ADR 0029 — 당일 진행 중 캔들 Redis 캐시 (5m~1h boundary 키 매트릭스)
- ADR 0037 — KIS API Rate Limit 강제
- ADR 0018 — Corp Action raw + adjusted (수정 주가 정합)
- ADR 0048 — KIS WebSocket 41건 슬롯 + polling fallback (풀 size 자연 한도)
- `external-api-policy.md` §2-5 — KIS 단일 출처 + NXT 매트릭스 + raw URL 인용
- `docs/architecture/candle-data-policy-v2.md` — 운영 가이드 (NXT cover + 합산 5종 매트릭스)
- PR #303 — `AggregateBackfillRunner` + `ColdStartExecutor` 합산 확장 + `DailyCloseFinalizer` 20:00 KST
- PR #304 — KOSDAQ market_div J 분기 + halt/liquidation skip
