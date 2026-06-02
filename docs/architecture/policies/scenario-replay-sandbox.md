# Scenario Replay Sandbox — 현행 권위

> **권위**: 본 문서. 변경 시 직접 갱신.
> **결정 이력**: ADR 0107 (도입)
> **마지막 갱신**: 2026-06-03

## 현행 규약

Scenario Replay는 전략 QA 환경이다. 실제 투자와 같은 전략 DSL, candle, risk rule을 사용할 수 있지만 실제 주문 경로와 side effect는 사용할 수 없다.

### 절대 금지

| 영역 | 금지 대상 |
|---|---|
| Kafka | `order.intent.v1`, `order.requested.v1`, `order.filled.v1`, `order.rejected.v1`, `risk.violation.v1`, `signal.generated.v1` publish |
| 주문 코드 | `OrderRouterKafkaListener`, `KisOrderHttpAdapter`, `KisOrderPort`, `KisExecutionWsListener` 직접 호출/import |
| KIS 주문 | `TTTC0012U`, `TTTC0011U`, `VTTC0012U`, `VTTC0011U` 호출 또는 문자열 상수 보유 |
| live DB write | `order_request`, `order_execution`, `order_audit_event`, `risk_event`, `position`, `trading_journal`, `performance_summary` |
| 사용자 알림 | Slack TRADE/P0 주문 체결·거절 알림 publish |

### 허용

| 영역 | 허용 방식 |
|---|---|
| 전략 평가 | `strategy-engine` 순수 evaluator를 virtual candle slice로 호출 |
| 지표/패턴 | `indicator-impl`, `pattern-engine` 순수 계산 재사용 |
| 리스크 룰 | `RiskGatewayEvaluator` rule 공식을 사용하되 `ScenarioLedger` 기반 context만 주입 |
| candle | DB candle read-only 조회 |
| 비용 모델 | backtest tax/commission/slippage model 재사용 |
| 영속 | `scenario_*` 테이블만 |
| 이벤트 | `scenario_event` 또는 후속 `scenario.*` topic만 |

### 사용자 메시지

리플레이에서 발생한 주문/체결/거절은 항상 "가상" 또는 "리플레이"로 표시한다.

잔고 조회 실패, rate limit, data readiness 실패는 실제 매도 주문 실패가 아니다. 메시지는 다음처럼 원인을 분리한다.

| 원인 | 표시 원칙 |
|---|---|
| 잔고 조회 rate limit | "KIS 잔고 조회 rate limit으로 가상 주문 보류" |
| 잔고 조회 재시도 실패 | "잔고 조회 실패로 가상 주문 미발행" |
| 보유 수량 부족 | "리플레이 포지션 기준 매도 가능 수량 부족" |
| 장종료 | "리플레이 시장 세션 규칙상 가상 주문 거절" |

## 적용 위치 (코드·설정)

- `domain/.../scenario/` — 리플레이 도메인 모델과 pure engine
- `api/.../scenario/` — 리플레이 API, persistence, report
- `api/src/main/resources/db/migration/V63__scenario_replay.sql` 이후 — `scenario_*` 테이블
- `MAGICJAR_SCENARIO_REPLAY_ENABLED` — endpoint enable gate

## 변경 이력

- 2026-06-03: ADR 0107에 따라 Scenario Replay Sandbox 현행 권위 정책 도입.
