---
name: magicjar-backtest-engine
description: magicJar의 기술적 지표 라이브러리, 차트 조건식 DSL, 백테스트 엔진, 주문 실행 로직을 설계·구현·수정·보완·재실행한다. 매매 전략을 코드로 표현하거나, 과거 데이터로 검증하거나, 조건식을 추가/변경할 때 반드시 사용. "지표 추가해줘", "전략 만들어줘", "백테스트 돌려줘", "조건식 DSL 확장"도 포함.
---

# magicjar-backtest-engine — 전략·백테스트 엔진

## 3대 구성요소

1. **지표(Indicators)** — 순수 함수. 입력은 `List<Candle>`, 출력은 `List<Double>` 또는 `Indicator<T>`.
2. **조건식(DSL)** — `IndicatorExpression` AST. 사용자/AI가 만든 조건을 파싱·평가.
3. **백테스트 엔진** — 캔들 스트림을 시간순으로 주입하면서 조건식을 평가, 가상의 주문을 생성·체결, 성과를 집계.

## 지표 카탈로그 (MVP)

| 카테고리 | 지표 | 파라미터 |
|---------|------|---------|
| 추세 | SMA, EMA | 기간 n |
| 모멘텀 | RSI, MACD | RSI n=14, MACD(12,26,9) |
| 변동성 | Bollinger Bands, ATR | Bollinger(20, 2σ), ATR(14) |
| 거래량 | OBV, VWAP, VolumeMA | - |
| 가격 | ROC, Stochastic | - |

우선 TA4J 채택 검토, 라이선스(MIT)·Java 21 호환 확인 후 ADR에 기록.

## 조건식 DSL 명세

**문법:**
```
expr      = term { ("AND"|"OR") term }
term      = atom | "(" expr ")" | "NOT" atom
atom      = func "(" args ")" (op value)?
func      = "RSI" | "EMA" | "SMA" | "MACD" | ...
args      = number {, number}
op        = "<" | "<=" | ">" | ">=" | "=="
value     = number | func "(" args ")"
```

**예시:** `RSI(14) < 30 AND VOLUME > SMA(VOLUME, 20) * 2`

**평가 규칙:**
- 현재 캔들 기준, 과거 데이터만 참조 (lookahead 금지)
- 지표 계산 불가능 구간(기간 부족)은 조건 `false`로 처리하고 `skipped_periods` 카운트

## 백테스트 실행 루프

```
for each candle in history:
  evaluate(entry_condition) -> 진입 신호
  evaluate(exit_condition)  -> 청산 신호
  if 신호 AND 포지션 허용:
    simulate_order(market or limit, slippage_bp, fee_bp, tax_bp)
    update portfolio
record metrics
```

## 비용 모델

- **수수료:** 0.015%(온라인 평균), ADR로 확정
- **거래세:** 매도 시 0.2%(코스피) / 0.23%(코스닥)
- **슬리피지:** 기본 5bp, 시가주문은 10bp
- **배당·유상증자:** MVP에선 미반영(ADR에 명시)

## 성과 지표

- 총 수익률, CAGR, 연변동성, 샤프, 소르티노, MDD, 승률, 평균 손익비, 거래 수

## 주문 실행기 (3-mode)

```
interface OrderSender {
  OrderResult send(Order order);
}
- BacktestOrderSender : 가상 체결, 시뮬레이션
- MockOrderSender     : KIS 모의계좌 API 호출
- RealOrderSender     : KIS 실계좌 API 호출 (기본 비활성)
```

**안전 게이트** (모든 Mode에 적용)
- 일일 최대 손실(예: -2%) 도달 시 신규 주문 차단
- 단일 종목 최대 비중 제한 (예: 20%)
- 동일 종목 연속 매수 횟수 제한 (예: 3회)

## 출력 포맷 (백테스트 리포트 JSON)

스키마 요약:
```json
{
  "strategyId": "rsi_oversold_v1",
  "period": {"from": "2024-01-01", "to": "2024-12-31"},
  "metrics": {"totalReturn": 0.18, "mdd": -0.12, "sharpe": 1.3, "winRate": 0.55, "trades": 42},
  "trades": [{"code": "005930", "side": "BUY", "qty": 10, "price": 72000, "at": "...", "reason": "RSI<30"}],
  "explanation": "ai-engineer가 자연어로 채움 (선택)"
}
```

## 재실행/업데이트 지침

- 기존 전략 ID는 유지, 변경 시 `_v2` 접미어로 새 버전 생성 후 결과 비교.
- DSL 문법 변경은 하위호환 우선, 불가능 시 버전 필드 명시(`dsl_version: 2`).
- 백테스트 수치가 크게 바뀌면 원인 분석(`_workspace/strategy/backtest-diff.md`) 먼저.
