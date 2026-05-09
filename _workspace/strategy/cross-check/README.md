# KIS Indicator Cross-Check Fixture

> **목적**: strategy-engine NativeIndicatorSource(SMA·EMA·RSI·MACD·BB) 5종 Kotlin 구현 정확성 검증용 기준값
> **raw 출처**: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/strategy_builder/core/indicators.py` (1507 lines)
> **생성**: market-data agent, 2026-05-09 (PR-33 T-IND KIS-first 정합)

---

## 사용법

PR-33b backend-core의 `NativeXxxTest.kt` 5종이 본 fixture JSON을 읽어 검증:

```kotlin
// 예시: NativeMaTest.kt
val fixture = ObjectMapper().readValue(
    File("_workspace/strategy/cross-check/kis-indicator-fixture.json"),
    IndicatorFixture::class.java
)
val expected = fixture.expectedOutputs["ma"]!!.values["29"]!!  // 74675.000000
val actual = NativeMa.calculate(spec, candles).series.last().values["ma"]!!
val drift = (actual - expected).abs() / expected.abs() * BigDecimal("100")
assert(drift <= BigDecimal("0.001")) // 0.001% 미만
```

---

## Fixture 구성

| 파일 | 내용 |
|------|------|
| `kis-indicator-fixture.json` | 5종 KIS Python 실행 결과 (30캔들, candle index 0-29) |

### 입력 캔들 (30개)

현실적 OHLCV — 삼성전자 스타일 일봉 (실제 ticker 아님). close 범위 71800~75600원.

### 출력 fixture

| 지표 | params | 첫 유효 index | 검증 포인트 수 |
|------|--------|--------------|--------------|
| SMA(20) | period=20 | 19 | 11개 (index 19~29) |
| EMA(20) | period=20 | 19 | 11개 (index 19~29) |
| RSI(14) | period=14 | 14 | 16개 (index 14~29) |
| MACD(12,26,9) | fast=12, slow=26, signal=9 | 25 | 5개 (index 25~29) × {macd,signal,histogram} 3 key |
| BB(20,2.0) | period=20, std_dev=2.0 | 19 | 11개 (index 19~29) × {upper,middle,lower} 3 key |

---

## KIS 알고리즘 특이사항 (strategy-engine 주의)

### SMA
- `calc_ma` L15: `close.rolling(window=period).mean()` — 단순 rolling mean

### EMA
- `calc_ema` L264: `close.ewm(span=period, adjust=False).mean()` — Pandas EWM
- **adjust=False**: Wilder smoothing 방식. 초기값이 단순 SMA 기반 EWM과 다름

### RSI
- `calc_rsi` L282: gain/loss 모두 `ewm(span=period, adjust=False).mean()`
- Traditional Wilder RSI(alpha=1/period)와 동일. SMA-seed 방식 아님
- 최소 캔들: `period + 1` (diff() 때문에 1개 추가 필요)

### MACD (multi-output)
- KIS 3개 별도 함수: `calc_macd`(L312) / `calc_macd_signal`(L339) / `calc_macd_histogram`(L365)
- Kotlin `NativeMacd.calculate()` → IndicatorPoint.values `{macd, signal, histogram}` 합성
- 최소 캔들: slow_period (26) — histogram은 signal 이후이나 실질적으로 slow_period가 기준

### BB (multi-output)
- KIS 3개 별도 함수: `calc_bb_middle`(L392) / `calc_bb_upper`(L406) / `calc_bb_lower`(L427)
- `calc_bb_middle`은 `calc_ma` 위임
- std는 `calc_std` L33 (`rolling.std()` — pandas ddof=1 default)
- Kotlin `NativeBollingerBands.calculate()` → `{upper, middle, lower}` 합성

---

## drift 허용 기준

```
drift = abs(native - kis) / abs(kis) * 100

허용: drift <= 0.001%
기준: PR-34 R3 IndicatorAccuracyTest — 80지표 전수 검증 (ADR 0021 §4-4)
```

BigDecimal scale=8, RoundingMode=HALF_EVEN 적용 시 0.001% drift는 충분히 달성 가능.

---

## 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-05-09 | v1 — 5종 fixture 생성. KIS indicators.py Python 직접 실행 결과. market-data agent (PR-33 T-IND) |
