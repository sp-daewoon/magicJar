# ADR 0018: Corp Action raw + adjusted (룩어헤드 차단의 전제)

- 상태: Accepted
- 작성일: 2026-05-03
- 결정자: architect (opus 4.7 1M)
- master §위치: `docs/architecture/master-architecture.md` v2 §A.3·§3-5
- 트랙: T4 (W1)
- 선행: ADR 0017

---

## 1. 맥락

액면분할·증자·배당락·합병으로 인한 가격 점프는 백테스트·screener·지표의 정합성을 깬다. 예: 2024-04-26 삼성전자 50:1 액면분할 → 가격 2,500,000 → 50,000으로 점프. 보정 안 하면 2024-01-01 backtest의 SMA-60·RSI 모두 왜곡 (룩어헤드 + 신호 거짓 발생).

**raw 가격 + adjusted 가격 양 컬럼 + retroactive 재계산 batch**가 v2 정합.

## 2. 결정

### 2-1. 도메인 모델

```kotlin
data class CorpAction(
    val id: Long,
    val instrumentCode: String,
    val type: CorpActionType,
    val exDate: LocalDate,        // ex-date (영업일 기준)
    val ratio: BigDecimal?,       // 액면분할: 50, 증자: 0.1 등
    val cashDividend: BigDecimal?,// 배당금
    val mergeRatio: BigDecimal?,  // 합병 비율
    val source: String,           // "KIS" · "DART"
    val appliedAt: Instant?,
)

enum class CorpActionType {
    STOCK_SPLIT, REVERSE_SPLIT,
    RIGHTS_OFFERING, BONUS_ISSUE,
    CASH_DIVIDEND, STOCK_DIVIDEND,
    MERGER, SPINOFF,
    DELISTING_NOTICE
}
```

### 2-2. candle 테이블 양 컬럼 (V14)

```sql
ALTER TABLE candle ADD COLUMN open_raw       NUMERIC(15,4);
ALTER TABLE candle ADD COLUMN high_raw       NUMERIC(15,4);
ALTER TABLE candle ADD COLUMN low_raw        NUMERIC(15,4);
ALTER TABLE candle ADD COLUMN close_raw      NUMERIC(15,4);
ALTER TABLE candle ADD COLUMN open_adjusted  NUMERIC(15,4);
ALTER TABLE candle ADD COLUMN high_adjusted  NUMERIC(15,4);
ALTER TABLE candle ADD COLUMN low_adjusted   NUMERIC(15,4);
ALTER TABLE candle ADD COLUMN close_adjusted NUMERIC(15,4);
ALTER TABLE candle ADD COLUMN volume         BIGINT NOT NULL DEFAULT 0;
ALTER TABLE candle ADD COLUMN volume_adjusted BIGINT;  -- ratio 보정 후 거래량
ALTER TABLE candle ADD COLUMN adjustment_factor NUMERIC(15,8);  -- ex-date 이후 누적 보정 계수

CREATE INDEX idx_candle_code_interval_time ON candle(code, interval, time DESC);
```

### 2-3. retroactive 재계산 흐름

`CandleAdjustmentJob` (corp_action 신규 발생 시 트리거):

1. `corp_action.applied.v1` Kafka 이벤트 수신
2. `instrumentCode`의 ex-date **이전** candle 모두 SELECT
3. corp_action 종류·비율로 보정 계수 계산
4. `*_adjusted` 컬럼 + `adjustment_factor` 누적 update
5. Redis `indicator:cache:{code}:*` invalidate (지표 재계산 트리거)
6. Kafka `corp_action.applied.v1` 재발행 (consumer 후속 처리)

### 2-4. 차트·DSL·backtest의 default

- 차트 표시: **adjusted** default (사용자 토글로 raw 가능)
- DSL 평가: **adjusted** 의무 (룩어헤드 차단)
- backtest: **adjusted** 의무
- `/api/instruments/{code}/candles?interval=DAY&adjusted=true` (default true)

raw가 필요한 케이스(수익률 계산·세금 계산)는 명시 토글.

### 2-5. Sync 흐름

`CorpActionSyncJob` (cron 18:30):

1. DART 공시 + KIS 종목 정보 변경 양쪽 fetch
2. corp_action upsert (멱등 — instrumentCode + type + exDate UNIQUE)
3. 신규 row 발생 시 `corp_action.applied.v1` publish

DART는 api 모듈 외부 어댑터에서 스크래핑.

## 3. 결과·영향

- backtest의 정확성 핵심 — KIS Strategy Builder examples와 동일 시드·기간으로 결과 비교 게이트 (T9 R3).
- candle storage size 약 2x (양 컬럼) — 1년 일봉 ~250 row × 3000종목 × 2 = 150만 row. 부담 0.
- 분봉도 양 컬럼 의무? — 분봉은 ex-date 당일에만 영향, raw + 1회 보정 OK. 단 일관성 위해 양 컬럼 유지 (1차).

## 4. 검토 대안

- **(a) raw만 저장 + 차트 시 보정** — 매번 corp_action JOIN, 성능↓.
- **(b) adjusted만 저장** — 세금·수익률 계산 시 raw 필요. 거부.
- **(c) 양 컬럼 + retroactive batch (채택)** — 정확성 + 성능 정합.

## 5. References

- master-architecture.md §A.3, §3-5
- ADR 0017 (instrument)
- ADR 0021 (indicator — adjusted 사용)
- KIS open-trading-api 종목 정보 변경 endpoint
- DART (전자공시) https://opendart.fss.or.kr/
