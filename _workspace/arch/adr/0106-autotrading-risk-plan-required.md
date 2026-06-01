# ADR 0106 — 자동매매 BUY 전 손절/익절 RiskPlan 필수화

- 상태: **Accepted (2026-06-01)**
- 작성자: backend-core
- 결정자: 사용자(최고 관리자)
- 관계:
  - ADR 0067 — Risk Gateway
  - ADR 0068 — Order Execution
  - policy: `docs/architecture/policies/autotrading-risk-plan.md`
  - visual: `_workspace/analysis/2026-06-01-autotrading-risk-plan.html`

## Context

자동매매는 잦은 매매보다 한 번의 진입이라도 손절가, 익절가, 허용 손실이 확정된 상태에서 들어가는 것이 핵심이다.
기존 구조는 전략 신호가 Risk Gateway를 통과하면 주문 의도로 넘어가며, 보유 수익률 익절(`takeProfitPct`)은 옵션성 exit policy로 존재했다.
하지만 손절가가 없는 BUY 주문은 실전 자동매매에서 자본 보호 기준을 잃게 만든다.

또한 2026-06-01 삼성전자 MOCK 운용 중 선행 SELL 주문 이후 SELL 신호가 반복되어 KIS에서 "잔고내역 없음" 거절이 반복됐다.
원인은 두 겹이다. 첫째, 내부 보유 수량만 보고 미체결 SELL 주문이 예약한 수량을 빼지 않았다. 둘째, 내부 position과 KIS 실제 잔고가 어긋난 경우 주문 요청 전 KIS 보유 수량을 확인하는 하드 게이트가 없었다.

## Decision

1. 자동매매 BUY는 RiskPlan이 없으면 fail-closed로 차단한다.
2. 1차 RiskPlan 필수 항목은 `fixed_stop_loss_pct`와 `take_profit_pct`다.
3. `take_profit_pct`는 `fixed_stop_loss_pct`보다 커야 한다.
4. 전략 ACTIVE 전환은 대상 종목, 인터벌, RiskPlan, 성공 백테스트가 모두 준비된 경우에만 허용한다.
5. consumer 실행 중에도 BUY 신호는 전략 RiskPlan preflight를 통과해야 주문 의도로 넘어간다.
6. SELL 주문 수량은 `보유 수량 - 미체결 SELL 주문 수량`으로 계산한다.
7. AUTO SELL은 주문 의도 발행 전 KIS 잔고를 조회해 주문 예정 수량 이하 보유 수량이면 `SELL_QUANTITY_UNAVAILABLE`로 차단한다.

## Consequences

- 기존 ACTIVE 전략 중 RiskPlan이 없는 전략은 BUY 신호가 발생해도 주문으로 이어지지 않는다.
- 내부 position에는 보유가 있어도 KIS 잔고에 해당 종목/수량이 없으면 SELL 주문 요청이 KIS로 나가지 않는다.
- 새 전략 저장 UX는 기본 손절 5%, 익절 10%를 제안한다.
- 차트 범위 손절, FVG/OB 무효화 손절, ATR 손절은 후속 확장으로 RiskPlan source를 넓힌다.
- KIS 국내주식 서버사이드 stop 지원 여부와 무관하게, 1차 구현은 내부 WS 감시 기반 보호 플랜의 전제 데이터를 저장한다.

## Rollback

- DB 컬럼은 add-only라 rollback 시 코드에서 필드 사용만 제거하면 기존 데이터와 충돌하지 않는다.
- RiskPlan preflight와 ACTIVE 전환 게이트를 이전 조건으로 되돌리면 기존 전략 운용 방식으로 복귀 가능하다.
