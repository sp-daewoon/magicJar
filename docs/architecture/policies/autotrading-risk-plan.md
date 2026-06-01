# 자동매매 RiskPlan — 현행 권위

> **권위**: 본 문서. 변경 시 직접 갱신.
> **결정 이력**: ADR-0106 (도입), ADR-0067 (Risk Gateway), ADR-0068 (Order Execution)
> **마지막 갱신**: 2026-06-01

## 현행 규약

자동매매 BUY 주문은 손절과 익절 계획이 모두 확정된 뒤에만 주문 경로로 진입한다.

| 항목 | 정책 |
|------|------|
| 손절 | 전략은 `fixed_stop_loss_pct`를 가져야 한다. 차트 범위 손절이 없는 1차 구현에서는 매수가 기준 고정 손절 %를 hard stop으로 사용한다. |
| 익절 | 전략은 `take_profit_pct`를 가져야 한다. 1차 구현에서는 보유 포지션 평가 수익률 기준 익절 %로 사용한다. |
| 최소 손익비 | `take_profit_pct > fixed_stop_loss_pct` 여야 한다. 같거나 작으면 저장/자동매매 진입을 차단한다. |
| 자동매매 시작 | 대상 종목, 인터벌, RiskPlan, 성공 백테스트가 모두 준비되어야 ACTIVE 전환 가능하다. |
| 실행 중 보호 | ACTIVE 전략이라도 BUY 시점에 RiskPlan이 없거나 비정상이면 Risk Gateway 이전 preflight에서 fail-closed로 차단한다. |
| 매도 수량 | SELL 주문 수량은 내부 보유 수량에서 미체결 매도 주문 수량을 뺀 가용 수량만 사용한다. |
| KIS 매도 잔고 | AUTO SELL은 주문 의도 발행 전 KIS 잔고에 해당 수량이 있는지 확인한다. KIS 잔고 조회 실패나 수량 부족은 주문 요청 대신 Risk 차단 알림으로 남긴다. |

## 적용 위치 (코드·설정)

- `strategy.fixed_stop_loss_pct` — 자동매매 기본 hard stop 손절 %
- `strategy.take_profit_pct` — 보유 포지션 평가 수익률 기준 익절 %
- `api/.../StrategyService.kt` — 전략 저장 검증 및 ACTIVE 전환 차단 사유
- `consumer/.../SignalGeneratedKafkaListener.kt` — BUY RiskPlan preflight fail-closed, AUTO SELL KIS 잔고 preflight
- `consumer/.../RiskGatewayEvaluator.kt` — SELL 가용 수량 산정
- `frontend/.../StrategySaveModal.tsx` — 전략 저장 시 관리자 RiskPlan 입력 UX
- `_workspace/analysis/2026-06-01-autotrading-risk-plan.html` — 시각 설계도

## 변경 이력

- 2026-06-01: 자동매매 BUY 전 손절/익절 RiskPlan 필수화, SELL 가용 수량 게이트, AUTO SELL KIS 잔고 preflight 도입 (ADR-0106).
