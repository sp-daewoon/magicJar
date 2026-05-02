# strategy DSL — 정식 문법 (v1)

매매 조건을 사용자/AI가 작성·검증·실행할 수 있도록 한 작은 표현식 언어.
재귀 하강 파서로 구현되며 외부 DSL 라이브러리(ANTLR, Groovy 등)에 의존하지 않는다.

## EBNF

```
expr        = orExpr
orExpr      = andExpr ("OR" andExpr)*
andExpr     = notExpr ("AND" notExpr)*
notExpr     = "NOT" notExpr | comparison
comparison  = arith (compareOp arith)?
arith       = mulExpr (("+" | "-") mulExpr)*
mulExpr     = unary (("*" | "/") unary)*
unary       = "-" unary | primary
primary     = NUMBER
            | IDENT ("(" args? ")")?
            | "(" expr ")"
args        = arith ("," arith)*
compareOp   = "<" | "<=" | ">" | ">=" | "==" | "!="
```

- `AND`, `OR`, `NOT` 키워드는 대소문자 무시.
- 식별자(`IDENT`)는 대소문자 무시 후 대문자로 정규화.
- `NUMBER`는 정수/소수. (예: `30`, `1.5`, `0.001`)
- 비교 결과만 boolean. 중첩된 boolean은 괄호로 감싼다.

## 컬럼 참조 (인자 없는 식별자)

| 이름 | 의미 |
|------|------|
| `OPEN`, `HIGH`, `LOW`, `CLOSE` | 현재 봉 OHLC |
| `VOLUME` | 현재 봉 거래량 |

추가로 `extras`(전략 상태)에 주입한 키도 참조 가능.

## 지표 함수 (인자 있는 식별자)

| 이름 | 인자 (기본값) | 반환 | 카탈로그 |
|------|--------------|------|---------|
| `SMA(n)` | n | 단순 이동평균 종가 | indicator.Sma |
| `EMA(n)` | n | 지수 이동평균 종가 | indicator.Ema |
| `RSI(n=14)` | n | Wilder RSI | indicator.Rsi |
| `MACD(fast=12, slow=26, signal=9)` | fast, slow, signal | MACD line | indicator.Macd |
| `MACD_SIGNAL(12,26,9)` | 동일 | Signal line | indicator.Macd |
| `MACD_HIST(12,26,9)` | 동일 | Histogram | indicator.Macd |
| `BB_UPPER(n=20, k=2)` | n, k | 볼린저 상단 | indicator.BollingerBands |
| `BB_MIDDLE`, `BB_LOWER` | 동일 | 중앙/하단 | 동일 |
| `STOCH_K(n=14, d=3)` | n, d | 스토캐스틱 %K | indicator.Stochastic |
| `STOCH_D(n=14, d=3)` | n, d | 스토캐스틱 %D | indicator.Stochastic |
| `ATR(n=14)` | n | Wilder ATR | indicator.Atr |
| `ADX(n=14)` | n | Wilder ADX | indicator.Adx |
| `CCI(n=20)` | n | CCI | indicator.Cci |
| `DISPARITY(n=20)` | n | 이격도 | indicator.Disparity |
| `AVG_VOLUME(n=20)` | n | 평균 거래량 | indicator.VolumeOsc |
| `VOLUME_OSC(fast=12, slow=26)` | fast, slow | PVO | indicator.VolumeOsc |

지표 함수의 모든 인자는 정수 리터럴이어야 한다(현재 봉 시점 평가). 동적 인자 미지원(MVP).

## 평가 규칙

1. **lookahead 금지** — `currentIndex` 까지의 캔들만 참조.
2. **미정의 → false** — 지표 계산 불가능 구간(워밍업 미충족) 또는 0으로 나누기 발생 시 비교 결과는 false.
3. **결정론** — 동일 (식, 캔들, 인덱스) 입력은 항상 동일 결과.
4. **캐싱** — 동일 식 트리에서 같은 `함수(인자)` 호출은 1회만 계산.
5. **타입** — 모든 수치 BigDecimal. Double 사용 금지.

## 예시

```
# RSI 과매도 + 거래량 폭증
RSI(14) < 30 AND VOLUME > AVG_VOLUME(20) * 2

# 골든 크로스
SMA(5) > SMA(20)

# 볼린저 하단 터치 + 모멘텀 회복
CLOSE <= BB_LOWER(20, 2) AND MACD(12, 26, 9) > MACD_SIGNAL(12, 26, 9)

# 복합
NOT (RSI(14) > 70) AND CLOSE > SMA(60)
```

## 두 가지 평가 모드

- **`BarStrategyEvaluator`** — 캔들 시계열 + currentIndex 컨텍스트. 백테스트 엔진/실시간 신호용.
  - 지표 함수는 캔들 → 시리즈 계산 후 currentIndex 위치 값 사용.
- **`DslStrategyEvaluator`** (`core.port.StrategyEvaluator`) — flat values 맵.
  - 외부에서 미리 계산해둔 `{"RSI(14)": 28.5, "CLOSE": 70000}` 같은 맵을 직접 룩업.

## 향후 확장 (하위호환 유지)

- 시간 함수 `BAR_AGE`, `IS_FIRST_BAR_OF_MONTH` 등.
- 사용자 정의 변수 선언 `let x = SMA(20)` (DSL v2 후보).
- 문자열 비교 (`MARKET == "KOSPI"`) — 식별자 영역에서 분리 필요.

DSL 변경은 하위호환 우선. 비호환 변경 시 `dslVersion` 필드(StrategyRef)에 명시한다.
