# ADR 0005: Indicator Source 추상화 (`IndicatorSource` plug-in)

- 상태: Accepted
- 작성일: 2026-05-03
- 결정자: architect (opus 4.7 1M) — 사용자 명시(2026-05-03)
- master §위치: `docs/architecture/master-architecture.md` v2 §B.5·§7-3
- 트랙: T5 (W2)
- 선행: ADR 0020 (KIS Strategy Builder 흡수), ADR 0001

---

## 1. 맥락

KIS Strategy Builder의 80지표(`indicators.py`)는 magicJar의 1차 흡수 대상이다. 단 사용자 명시(2026-05-03 결정 #3): "80 지표 일괄 + 지표 source 추상화 인터페이스 명세 의무. **KIS 외에서도 받을 수 있게**". 1차안의 "KIS만 흡수" 가정 폐기.

미래에 TA-Lib·자체 구현·KRX 공식 발행 지표 등이 같은 인터페이스로 plug-in 되어야 한다.

## 2. 결정

### 2-1. Port 정의

```kotlin
// domain/.../indicator/port/IndicatorSource.kt
interface IndicatorSource {
    /** source 식별자 — "kis-strategy-builder" · "ta-lib" · "native" · "krx" */
    val name: String

    /** 우선순위 — 같은 spec 지원 source 중 선택 기준 (낮을수록 우선) */
    val priority: Int

    /** 본 source가 spec(name·params)을 지원하는지 */
    fun supports(spec: IndicatorSpec): Boolean

    /** 계산 — 단일 source 계산. 실패 시 IndicatorException */
    fun calculate(spec: IndicatorSpec, candles: List<Candle>): IndicatorResult
}

data class IndicatorSpec(
    val name: String,           // "MA" · "EMA" · "RSI" · "MACD" · "BB" · ...
    val params: Map<String, Any>,
)

data class IndicatorResult(
    val spec: IndicatorSpec,
    val series: List<IndicatorPoint>,
    val source: String,         // 사용된 source 식별자
)

data class IndicatorPoint(
    val time: Instant,
    val values: Map<String, BigDecimal>,  // multi-output (MACD: line·signal·histogram)
)
```

### 2-2. Engine

```kotlin
// domain/.../indicator/IndicatorEngine.kt
class IndicatorEngine(
    private val sources: List<IndicatorSource>,
) {
    fun calculate(spec: IndicatorSpec, candles: List<Candle>): IndicatorResult {
        val source = sources
            .filter { it.supports(spec) }
            .minByOrNull { it.priority }
            ?: throw IllegalArgumentException("No source supports ${spec.name}")
        return source.calculate(spec, candles)
    }
}
```

### 2-3. 1차 구현 (T5 R1·R2)

| Source | priority | 범위 | 비고 |
|---|---|---|---|
| `KisStrategyBuilderSource` | 100 | 80지표 (KIS `indicators.py` port) | T5 R1 — 일괄 |
| `NativeIndicatorSource` | 50 | 핵심 5지표 (SMA·EMA·RSI·MACD·BB) — magicJar 자체 | T5 R2 — KIS와 cross-check |
| `TaLibSource` | 200 | (선택) JNI 또는 HTTP | post-W2 |
| `KrxSource` | 300 | (선택) KRX 공식 | post-MVP |

priority 낮을수록 우선 — `NativeIndicatorSource`가 활성화되면 KIS보다 우선 사용 (자체 구현이 신뢰).

### 2-4. 결과 컬럼 일관성

- 모든 source의 IndicatorResult는 동일 schema (name·params·values keys)
- KIS `MA` → values { "ma": 0.0 }
- KIS `MACD` → values { "macd": 0, "signal": 0, "histogram": 0 }
- 사용자 정의 source가 등록 시 schema validation 의무

### 2-5. 캐시

Redis `indicator:cache:{code}:{interval}:{name}:{paramsHash}`:
- 일봉: TTL 1일
- 분봉(1m·5m·15m·30m·60m): TTL 5분

cache key에 source 식별자 포함하지 않음 — 사용자 토글 시점에 invalidate.

### 2-6. 정확성 cross-check 모드 (옵션, default off)

`MAGICJAR_INDICATOR_CROSSCHECK=true` (dev 한정):
- 매 calculate 호출 시 모든 supports 가능한 source로 계산
- 결과 drift 0.001% 이상 시 ALERT 로그 + Kafka `indicator.drift.v1` (post-MVP topic)
- 운영 비용 큼 — default off, 정확성 검증 캠페인 시만 on.

## 3. 결과·영향

- **T5 (Indicators)** — 80지표 KIS port + Native 5지표 1차.
- **T6 (Patterns)** — 별도 ADR (0048 후보), source 추상화는 indicator 한정 (1차).
- **T7 (DSL)** — `IndicatorEngine`을 사용. DSL은 source 무관 — `MA(20)` 호출하면 priority 낮은 source 자동.
- **사용자 정의 지표** — `NativeIndicatorSource`에 추가 (post-MVP)

## 4. 검토 대안

- **(a) KIS만 흡수** (1차안) — 사용자 비전 위반.
- **(b) 자체 구현만** — KIS Strategy Builder의 80개 검증된 지표 가치 0.
- **(c) Source plug-in 인터페이스 (채택)** — 확장성 + 정확성 검증 + 사용자 비전 정합.

## 5. References

- master-architecture.md §B.5, §7-3
- ADR 0020 (KIS Strategy Builder 흡수)
- ADR 0001 (오프라인 1순위)
- KIS `strategy_builder/core/indicators.py` (80개 함수 명세)
- TA-Lib https://ta-lib.org/
