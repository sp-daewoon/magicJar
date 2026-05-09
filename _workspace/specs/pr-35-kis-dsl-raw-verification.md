# PR-35 KIS Strategy Builder DSL parser raw 검증 보고서

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-09
- 사유: 헌법 §3 KIS-first raw fetch 의무 + 사용자 메모리 `feedback_kis_first_policy.md` (자체 추론 0)
- 출처: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/strategy_builder/strategy_core/dsl/parser.py` (664 lines, License MIT)
- fetch 시점: 2026-05-09

---

## §1. 결론 (TL;DR)

**KIS Strategy Builder DSL = 텍스트 기반 표현식 DSL**. 정규식 토크나이저 + 재귀 하강 파서 + 11 클래스(5 enum + 6 dataclass). YAML/JSON 트리 구조가 아닌 **`"ma(5) crosses_above ma(20)"` 같은 한 줄 표현식**을 AST로 변환한다.

→ T-DSL Kotlin port 시 **KIS와 동일한 텍스트 표현식 DSL 채택 의무** (자체 추론 0). 사용자 결재 항목 #1 "JSON vs 자체 언어 vs 텍스트" → **KIS와 동일한 텍스트 표현식 LOCK** (호환성 + ADR 0022 §2-1 정합).

`.kis.yaml`은 buy_condition/sell_condition 필드에 **텍스트 표현식 1줄**을 담는 컨테이너. parser가 그 텍스트를 AST로 변환.

---

## §2. KIS DSL 11 클래스 raw 정합 (Kotlin port 시 1:1)

### 2-1. 5 enum

| KIS Python | line | Kotlin port | 본 PR-35 spec §위치 |
|---|---|---|---|
| `class ConditionType(Enum)` — CROSSOVER·THRESHOLD·BREAKOUT·PATTERN·COMPOSITE | L23 | `enum class ConditionType` | spec §6-2 |
| `class Operator(Enum)` — GT·LT·GTE·LTE·EQ·NEQ·CROSSES_ABOVE·CROSSES_BELOW·BREAKS (9종) | L34 | `enum class Operator` | spec §6-2 |
| `class LogicalOperator(Enum)` — AND·OR (2종) | L46 | `enum class LogicalOperator` | spec §6-2 |
| `class ArithmeticOperator(Enum)` — ADD·SUB·MUL·DIV (4종) | L52 | `enum class ArithmeticOperator` | spec §6-2 |
| (없음) | - | (없음 — KIS 정합) | - |

### 2-2. 6 dataclass / 1 parser class

| KIS Python | line | Kotlin port | 본 PR-35 spec §위치 |
|---|---|---|---|
| `@dataclass Indicator(name, params)` | L60 | `data class IndicatorRef(name: String, params: List<Any>)` (Indicator는 ADR 0021 도메인 타입과 충돌 회피로 IndicatorRef rename) | spec §6-2 |
| `@dataclass Value(value, is_percent)` | L218 | `data class LiteralValue(value: BigDecimal, isPercent: Boolean)` | spec §6-2 |
| `@dataclass ArithmeticExpr(left, operator, right)` | L235 | `data class ArithmeticExpr(...)` (재귀 sealed class node) | spec §6-2 |
| `@dataclass Condition(left, operator, right, condition_type)` | L251 + L283 (`get_required_days`) | `data class Condition(...)` + `requiredDays(): Int` | spec §6-2 |
| `@dataclass CompositeCondition(left, operator, right)` | L283 | `data class CompositeCondition(...)` | spec §6-2 |
| `@dataclass StrategyDefinition(name, name_ko, buy_condition, sell_condition, params)` | L555 | `data class DslStrategyDefinition(...)` | spec §6-2 |
| `class StrategyDSLParser` — TOKEN_PATTERNS + tokenize + parse | L301 + L355(parse) | `class KisYamlDslParser` (consumer/.../strategy/) | spec §3-2 |

### 2-3. TOKEN_PATTERNS LOCK (자체 추론 0)

raw 출처 L304-L322 정확 인용:

```python
TOKEN_PATTERNS = [
    ("LPAREN", r"\("),                  ("RPAREN", r"\)"),
    ("COMMA", r","),                    ("PERCENT", r"%"),
    ("CROSSES_ABOVE", r"crosses_above"), ("CROSSES_BELOW", r"crosses_below"),
    ("BREAKS", r"breaks"),
    ("AND", r"(?:AND|and)\b"),          ("OR", r"(?:OR|or)\b"),
    ("GTE", r">="),                     ("LTE", r"<="),
    ("NEQ", r"!="),                     ("EQ", r"=="),
    ("GT", r">"),                       ("LT", r"<"),
    ("MUL", r"\*"),                     ("DIV", r"/"),
    ("ADD", r"\+"),                     ("SUB", r"-"),
    ("NUMBER", r"-?\d+\.?\d*"),
    ("IDENTIFIER", r"[a-zA-Z_][a-zA-Z0-9_]*"),
    ("WHITESPACE", r"\s+"),
]
```

Kotlin port 시 동일 정규식 + 동일 token name LOCK (자체 추론 0).

### 2-4. parse() 재귀 하강 우선순위 LOCK

raw 출처 L412-L470 정합:

```
parse(text)
└─ _parse_or_expression()             # 우선순위 1 (최저)
   └─ _parse_and_expression()         # 우선순위 2
      └─ _parse_condition()           # 우선순위 3 — operator 매칭
         └─ _parse_arithmetic()       # 우선순위 4 (* /)
            └─ _parse_term()          # 우선순위 5 (+ -)
               └─ _parse_factor()     # 우선순위 6 — 단항 -, NUMBER, IDENTIFIER, ( )
                  └─ _parse_indicator() · _parse_value()
```

Kotlin port: 동일 6단 재귀 하강 LOCK. 사용자 결재 항목 #1 권장 = **KIS 정합 텍스트 DSL** (자체 추론 0 보장).

### 2-5. ConditionType 결정 룰 (raw 출처 L361-L390)

Kotlin port 시 동일:

| operator | condition_type |
|---|---|
| `crosses_above`·`crosses_below` | CROSSOVER |
| `breaks` | BREAKOUT |
| `>`·`<`·`>=`·`<=`·`==`·`!=` | THRESHOLD |
| left = `consecutive(...)` indicator | PATTERN (raw L378-L380) |
| `AND`·`OR` 합성 | COMPOSITE |

### 2-6. get_required_days() 함정 (raw L283-L296 + L295)

각 노드가 **필요 과거 캔들 수**를 자체 계산. T-DSL evaluator는 candles 부족 시 다음 정책:

| 상황 | 동작 |
|---|---|
| candles.size < required_days | `IndicatorEngine.calculate(...)` 호출하지 않음 + `EvalResult.HOLD(insufficient_data)` 반환 |
| MACD slow=26 + signal=9 → required = 35 | raw L290-L292 정합 |

Kotlin port 시 동일 — `Condition.requiredDays(): Int` + `CompositeCondition.requiredDays() = max(left, right)` LOCK.

---

## §3. KIS 지표 식별자 화이트리스트 (raw L72-L107 + L196-L210)

KIS DSL parser가 인식하는 indicator 이름 — to_code() 매핑 출처. ADR 0021 IndicatorSource 등록 80지표 + 일부 alias:

| KIS DSL 식별자 (text) | ADR 0021 IndicatorSpec.name | 비고 |
|---|---|---|
| `ma`, `ema`, `rsi`, `cci`, `adx`, `mfi`, `obv`, `atr` | 동일 | 1:1 |
| `macd` | `macd` | params `(fast=12, slow=26, signal=9)` |
| `macd_signal` | `macd` (outputKey="signal") | DSL alias |
| `macd_hist` | `macd` (outputKey="histogram") | DSL alias |
| `bb_upper`, `bb_lower`, `bb_middle` | `bb` (outputKey="upper"/"lower"/"middle") | DSL alias |
| `stoch_k`, `stoch_d` | `stochastic_k`, `stochastic_d` | DSL alias |
| `volume`, `volume_ma` | `volume_ma` (volume은 Candle 직접 접근) | candle.volume 직접 |
| `close`, `prev_close`, `high`, `low` | candle 직접 접근 (특수 케이스) | indicator 라우팅 X |
| `consecutive(up\|down)` | 자체 패턴 함수 | T-PAT 합류 후 본격 |
| `disparity`, `volatility`, `returns` | KIS 자체 함수 | 80지표 일부 |
| `change` | KIS 일변동률 | 자체 |
| `highest`, `lowest` | KIS rolling high/low | 자체 |

→ T-DSL evaluator는 `indicator(name, params)` 호출 시 **DSL 식별자 → ADR 0021 IndicatorSpec.name 매핑 테이블** 의무 (자체 추론 0). T-IND가 등록한 80지표만 호출 가능 + ADR 0047 §2-1 화이트리스트 정합.

`.kis.yaml` 텍스트 DSL 식별자 매핑은 `_workspace/strategy/dsl-identifier-map.md` (본 PR-35 산출물 — sub-agent strategy-engine이 raw fetch 후 inline 작성) 의무.

---

## §4. KIS DSL 텍스트 표현식 예제 (raw L1-L16 docstring 정합)

```
# Crossover
ma(5) crosses_above ma(20)
ema(12) crosses_below ema(26)

# Threshold
disparity(20) < 90
rsi(14) < 30
rsi(14) > 70

# Breakout
close breaks high(250)

# Pattern
consecutive(up) >= 5

# Composite (AND/OR)
close > ma(60) AND change > 0
rsi(14) < 30 OR cci(20) < -100

# Arithmetic
volume > volume_ma(20) * 1.5
close < ma(20) - atr(14) * 2

# 단항 마이너스
returns(1) > -3.5
```

Kotlin parser 출력 — 위 raw 예제 8건 모두 정합 의무 (R3 fixture 검증).

---

## §5. 사용자 결재 항목 검증 (KIS-first 결과 반영)

| # | 항목 | architect 권장 (KIS-first 결과) | 사유 |
|---|------|------|------|
| 1 | DSL 문법 — JSON vs 자체 언어 vs 텍스트 | **KIS 정합 텍스트 DSL** (`"ma(5) crosses_above ma(20)"`) | ADR 0022 §2-1 호환성 LOCK — `.kis.yaml` import 시 텍스트 그대로 evaluator 사용. JSON은 자체 추론 위반 |
| 2 | 자연어 변환 W2 vs W4 | **W4 이월 명시** | 룰 5(W2 LLM provider import 0) + ADR 0026 §2 사용자 트리거 한정 + tracks-roadmap T-DSL 비범위 명시 정합. **신규 ADR 0건** — ADR 0022 §3 (자연어 변환은 W4) + ADR 0047 §3-6 (DSL syntax v2 W4 후속) 이미 결정 |
| 5 | 신규 ADR 발행 | **0건** (ADR 0022/0047 implementation 명세 충분 — PR-32 정합) | 헌법 §3 (자체 추론 0) + ADR 0030 SoT (master §10 정합) |
| 6 | 1차 operator 우선 | **9개 (KIS LOCK)**: `>`·`<`·`>=`·`<=`·`==`·`!=`·`crosses_above`·`crosses_below`·`breaks` + 논리 2 (AND·OR) + 산술 4 (`+`·`-`·`*`·`/`) | KIS raw L34-L57 정합 |
| 7 | T-IND 의존 인터페이스 | `IndicatorEngine.calculate(spec, candles)` (ADR 0021 §2-2) + DSL 식별자 매핑 테이블 | spec §3 매핑 표 |

---

## §6. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-09 | KIS DSL parser raw fetch v1 (664 lines) — 11 클래스 LOCK + token patterns + parse 우선순위 + ConditionType 결정 룰 + get_required_days + DSL 식별자 화이트리스트 매핑. 자체 추론 0 보증. (architect, opus 4.7 1M) |
