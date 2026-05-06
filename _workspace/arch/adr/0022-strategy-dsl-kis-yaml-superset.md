# ADR 0022: Strategy DSL = `.kis.yaml` 호환 superset

- 상태: Accepted
- 작성일: 2026-05-03
- 결정자: architect (opus 4.7 1M)
- master §위치: `docs/architecture/master-architecture.md` v2 §B.7
- 트랙: T-DSL (Strategy DSL, W2)
- 선행: ADR 0020, 0039

---

## 1. 맥락

KIS Strategy Builder는 `.kis.yaml` 포맷으로 전략을 표현한다. 80지표·11 파서 클래스(ConditionType·Operator·Indicator·Value·CompositeCondition 등)·10 프리셋이 검증된 자산이다. magicJar는 이 자산을 흡수하면서, 사용자가 magicJar에서 추가로 필요로 하는 기능(다중 timeframe·시간 필터·종목 그룹·패턴 조건)도 포함한다.

호환성 보존 + 확장 동시 — superset.

## 2. 결정

### 2-1. KIS 호환 핵심

```yaml
# .kis.yaml 호환 — 그대로 import 가능
name: golden_cross_5_20
universe: KOSPI200
entry:
  conditions:
    - lhs: { indicator: MA, params: { period: 5 } }
      op: ">"
      rhs: { indicator: MA, params: { period: 20 } }
exit:
  conditions:
    - lhs: { indicator: MA, params: { period: 5 } }
      op: "<"
      rhs: { indicator: MA, params: { period: 20 } }
```

1:1 KIS 파서 클래스 port:
- `ConditionType` (PRICE·INDICATOR·VOLUME·...)
- `Operator` (>·<·>=·<=·==·CROSS_UP·CROSS_DOWN)
- `LogicalOperator` (AND·OR·NOT)
- `ArithmeticOperator` (+·-·*·/)
- `Indicator`·`Value`·`ArithmeticExpr`·`Condition`·`CompositeCondition`
- `StrategyDSLParser`·`StrategyDefinition`

→ Kotlin sealed class (`domain/.../strategy/`).

### 2-2. magicJar 확장 키워드

```yaml
# magicJar 확장
name: my_strategy
universe:
  market: KOSPI
  filter:
    sector: ["반도체", "이차전지"]   # 종목 그룹
    minMarketCap: 1000000000000     # 1조원 이상
tf: D1                              # default timeframe
entry:
  conditions:
    - tf: D1
      pattern: "BULLISH_ENGULFING"  # 패턴 조건 (T-PAT)
    - tf: 60m                       # 다중 timeframe
      lhs: { indicator: RSI, params: { period: 14 } }
      op: "<"
      rhs: { value: 30 }
time:
  session: REGULAR                  # 시간 필터 — REGULAR · PRE_MARKET · POST_MARKET
  windowKst: ["09:30", "14:50"]     # 진입 시간대
exit:
  conditions: ...
  trailingStop: 0.05                # 5% trailing
  fixedStopLoss: 0.03               # 3% 고정 손절
sizing:
  type: FIXED_FRACTIONAL            # KELLY · FIXED_FRACTIONAL · FIXED_LOT
  fraction: 0.05                    # 자산의 5%
```

### 2-3. import/export 정책

- `POST /api/strategy/import?format=kis-yaml` — `.kis.yaml` 그대로 받아 magicJar 형식으로 등록 (확장 키워드 default값)
- `GET /api/strategy/{id}?format=kis-yaml` — magicJar 전략을 KIS 호환으로 export. 단 magicJar 확장 키워드(`tf:`·`time:`·`pattern:`·`universe.filter:`·`sizing:`)는 **YAML comment(#)로 처리** — KIS 파서가 무시하지만 사람은 볼 수 있음.
- GUI 블록 빌더(T-SB)에서 만든 전략은 `format=magicjar` (default) 또는 `format=kis-yaml` (comment fallback) 둘 다 export.

### 2-4. 평가 흐름

```kotlin
val parser = KisYamlDslParser()
val strategy: StrategyDefinition = parser.parse(yamlContent)
val evaluator = JvmStrategyEvaluator(indicatorEngine, patternRecognizer)
val result: StrategyEvalResult = evaluator.evaluate(strategy, candles, context)
// result.signal: Signal (BUY/SELL/HOLD) + strength + reason
```

### 2-5. 검증·테스트

- `POST /api/strategy/validate` — DSL syntax·indicator name·timeframe 정합성 검증, 200 OK + warnings
- 10개 KIS 프리셋 (strategy_NN_*.py) → Kotlin port → e2e backtest 결과를 KIS examples와 cross-check (T-BT R3 게이트)

## 3. 결과·영향

- T-DSL·T-SB·T-BT·T-SC·T-SIG 모두 본 ADR 직접 의존.
- 사용자가 KIS GUI에서 만든 .kis.yaml 그대로 magicJar에서 사용 가능 — 호환성 우선.
- magicJar 확장 키워드는 점진적 추가 (R3 라운드별).

## 4. 검토 대안

- **(a) magicJar 자체 DSL 신설** (KIS 비호환) — KIS Strategy Builder 자산 가치 0. 거부.
- **(b) `.kis.yaml` 한정** — 다중 TF·패턴 조건 부재. 거부.
- **(c) superset (채택)** — 호환 + 확장.

## 5. References

- master-architecture.md §B.7
- ADR 0020 (KIS 흡수)
- ADR 0021 (Indicator Source)
- ADR 0023 (Signal Engine — DSL 평가)
- KIS `strategy_core/dsl/parser.py`
- KIS 10 프리셋 (`strategy_NN_*.py`)
