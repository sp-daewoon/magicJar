# 백테스트 샘플 결과 — 골든크로스 (SMA5 > SMA20)

## 입력

- 종목: `005930` (삼성전자, 가상 100봉 일봉 데이터; `strategy/src/test/resources/candles/005930-daily-sample.csv`)
- 기간: 2024-01-02 ~ 2024-05-31 (영업일 100봉, 가상)
- 조건식: `SMA(5) > SMA(20)`
- 초기 자본: ₩10,000,000
- 비용: 수수료 0.015%, 거래세 0.23% (KOSPI/KOSDAQ 동률 가정), 슬리피지 5bp

## 실행 경로

`BacktestEngine.run(BacktestRequest)`
- 종목별로 봉 단위 평가 → `SMA(5) > SMA(20)`이 true이면 다음 봉 시가에 BUY, false인데 보유 중이면 다음 봉 시가에 SELL.
- 종목별 일별 평가액(equity) 합산 → `MetricsCalculator`에 전달.

## 결과 (테스트 어서션 기반)

`strategy/src/test/.../backtest/BacktestEngineSmokeTest.kt::golden cross strategy runs end to end` 통과.

| 항목 | 값/제약 |
|------|---------|
| `Metrics.totalReturn` | non-null BigDecimal |
| `Metrics.cagr` | non-null BigDecimal |
| `Metrics.mdd` | ≤ 0 (음수, 0 이하 보장) |
| `Metrics.sharpe` | non-null BigDecimal (연 252일 환산) |
| `Metrics.winRate` | 0.0 ≤ x ≤ 1.0 |
| `Metrics.tradeCount` | ≥ 0 (라운드트립 수) |

## 비결정성 검증

동일 시드/입력으로 재실행 시 모든 메트릭은 비트 단위 동일(BigDecimal). 난수 미사용.

## 관찰 사항

- 가상 데이터가 우상향 후 조정 패턴이라 골든크로스는 1~2회 트리거.
- 100봉 짧은 기간이어서 `Metrics.cagr` 신뢰성은 낮음(전체 기간 0.4년 미만).
- 미청산 잔고는 마지막 봉 종가로 마크되며 라운드트립 손익에는 포함되지 않음(마지막 강제 청산 미구현 — TODO).

## 향후 개선

1. 진입/청산 조건식 분리(`BacktestRequest.entryExpr`, `exitExpr`).
2. 마지막 봉 강제 청산 옵션 (`forceFlattenAtEnd`).
3. 종목별 자금 배분 정책(`equal`, `weighted`).
4. 슬리피지 모델 다양화(VWAP, 거래량 기반).
5. 실 데이터 fixture 도입 시 `_workspace/strategy/sample-backtest/`에 결과 JSON 첨부.
