# 백테스트 MDD mark-to-market fix — before/after 실측 (ADR 0056 §2-7 amend)

> V76 검증 #860에서 발견된 cash-only equity curve 결함 → MDD≈100% 아티팩트 fix.
> 실측: 005930 일봉, 2015-01-01 ~ 2025-12-31, initialCapital 10,000,000,
> taxModel=korean_default, commissionModel=kis_direct, slippageModel=fixed_0.05, ADJUSTED.
> before = 결함 jar(cash-only), after = MTM fix jar. 동일 컨테이너 재배포 전/후 동일 요청.

## 원인

`JvmBacktestEngine`이 equity curve를 **체결마다 `cash`만** append 했다. AllCapital 매수 시 cash≈0이 되어
포지션 보유 구간 내내 equity≈0 → drawdown ≈ (peak − 0)/peak ≈ 100%. MDD 게이트 G3(≤8%) 무력화.

## fix

매 캔들마다 mark-to-market equity 1점 기록:
`equity(t) = cash(t) + Σ(보유 포지션 수량 × 그 시점 종가)`. 미실현 손익 포함 → MDD·Sharpe 정상화.

## 실측 (MDD before ≈100% → after 정상)

| 전략 | 성격 | MDD before | MDD after | totalReturn (불변) | Sharpe before→after |
|---|---|---|---|---|---|
| #8 paper_learning (장기 보유, +334%) | 진입 후 장기 보유 | **0.9995** | **0.4516** | 3.3474 = 3.3474 | None → 0.5509 |
| #50 VWAP_1 (장기 보유, +338%) | 진입 후 장기 보유 | **0.9997** | **0.4516** | 3.383 = 3.383 | None → 0.5539 |
| #46 ma(5)x ma(20) (빈번 매매) | stop 5% / TP 10% | **0.5496** | **0.1468** | 0.1499 = 0.1499 | 3.5458 → -0.1748 |

- **before ≈100% 아티팩트**: #8·#50은 +334%/+338%로 크게 수익났음에도 MDD가 ≈100%로 표시 — cash-only 곡선이 보유 구간 내내 ≈0이었기 때문.
- **after 정상화**: 0.4516 = 2015~2025 005930 보유 중 실제 최대 미실현 낙폭(2018·2022 하락장 반영). #46은 0.5496→0.1468로 짧은 보유 구간 inflation까지 제거.
- **회귀 0**: totalReturn·CAGR·winRate·profitFactor·totalTrades 등 cash·trades 기반 지표는 before/after **동일**. MDD·Sharpe만 정정.
- **Sharpe**: before에는 cash-only 곡선이 매매 시점에만 점이 찍혀 데이터 포인트가 부족(#8·#50 None)했으나, MTM은 매 캔들 1점이라 일별 수익률 계열이 충분 → 정상 산출. #46은 spiky cash 곡선 기반 과대 Sharpe(3.5458)가 실제 MTM 기반(-0.1748)으로 정정.

before 원시 수치는 본 표(결함 jar 재현)로 보존. after 상세 JSON은 `mdd-mtm-fix-strategy{8,46}-005930-AFTER.json`.
기존 V76 샘플(`v76-h1-gap-and-go-overfit-IS-OOS.json`의 maxDrawdown 1.0000/0.9999)이 동일 아티팩트의 최초 발견 evidence.
