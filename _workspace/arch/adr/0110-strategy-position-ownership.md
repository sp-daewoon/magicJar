# ADR 0110 — 전략별 포지션 귀속 (`strategy_position` 1급 도메인)

- 상태: **Accepted (2026-06-05)**
- 작성자: architect
- 결정자: 사용자(최고 관리자) — 분할 매수·익절/손절 통합 요구 결재
- 영역: 도메인 + 영속 + Risk Gateway (자본 path)
- 관계:
  - spec: `_workspace/specs/2026-06-05-scale-in-exit-ownership-spec.md`
  - ADR 0106(RiskPlan 필수 — SELL 가용 수량 §6) · ADR 0067(Risk Gateway) · ADR 0042(PositionRepository) · ADR 0068(Order Execution)
  - ADR 0111(RiskPlan scale-in/exit — 본 ADR 위에 평단/exit 구축)
  - readiness: `docs/assessments/2026-06-05-autotrading-readiness.html` Phase C-2(+5 P1)

## 1. Context

readiness 재평가(2026-06-05)는 REAL 무인 운용의 잔여 P1 게이트 둘 중 하나로 "전략별 포지션 귀속"을 지목했다: *"동일 종목 다전략 시 '이 전략이 산 물량' 분리 추적 부재 — 익절/청산이 사용자+종목 포지션 기준."*

현행 `position` 테이블은 `UNIQUE(user_id, code)`(V1 baseline)다. 동일 종목을 2전략이 동시 운용하면 한 행에 합산되어 "어느 전략이 얼마에 샀나"를 알 수 없다. 그 결과:

- 익절/손절(ADR 0106)이 user+code 합산 평단 기준 → A전략이 산 물량을 B전략 평단으로 청산하는 오염.
- 분할 매수(ADR 0111)가 전략별 평단·진행도를 추적할 토대가 없음.
- RiskGateway `SELL_WITHOUT_POSITION`·`SELL_QUANTITY_UNAVAILABLE`이 합산 기준 → 다전략 교차 청산 차단 불가.

동시에 `position`은 KIS 잔고 부트스트랩(ADR 0042) + SELL 하드 게이트(ADR 0106 §7)의 권위라 깨면 안 된다. KIS는 전략을 모른다 — 실잔고는 종목 합산 단위다.

## 2. Decision

기존 `position`(user+code 합산, KIS 잔고 권위)은 **유지**하고, 그 아래에 전략별 lot ledger `strategy_position`을 1급 도메인으로 신설한다.

1. **`strategy_position` 테이블** — `UNIQUE(user_id, strategy_id, code)`. 컬럼: `quantity`·`avg_price`·`filled_lots`·`realized_pnl`·`opened_at`·`updated_at` + **`executed_exit_stages JSONB`**(발화된 PartialExit 단계 인덱스 — 부분 익절 중복 발화 방지·ADR 0111 §2-2 정합). FK(user_account·strategy·instrument). Flyway **V65**(spec §4 LOCK).
   - **부분 익절 1회 발화 불변식 (amend)**: 각 PartialExit 단계는 `executed_exit_stages`로 1회만 발화(가격이 trigger 위에 머물러도 중복 SELL 0). 체결 확정 시 단계 인덱스 마킹(발행 시점 아님 — 미체결 reject 재시도 보존). `filledLots`(진입 단조 증가)와 대칭. 전량 청산 시 `filled_lots=0`·`executed_exit_stages` reset.
   - **leg idempotency 권위 (amend)**: order_request의 자본 중복 보호 권위는 V38에서 완화된 `uq_order_signal_user`가 아니라 **`idempotency_key` UQ + Redis SETNX**(V20 §5·V38 §22 실측). scale-in/분할 익절 leg는 idempotencyKey에 회차/단계 suffix를 add해 중복 차단(spec §5-3).
2. **불변식**: `sum(strategy_position.quantity WHERE user+code) == position.quantity`. drift 시 **KIS 잔고(position)가 상위 권위** — `STRATEGY_POSITION_DRIFT` SystemEvent(WARN) 발사 + 보수적으로 position 우선.
3. **귀속 키**: `OrderIntent`·`order_execution`이 이미 보유한 `strategy_id`로 체결 listener가 `strategy_position` upsert(평단 가중평균 갱신 + `filled_lots` 증가 / SELL 시 `realized_pnl` 누적 + quantity 감소).
4. **RiskGateway SELL 계열 2룰만 전략별 분기**:
   - `SELL_WITHOUT_POSITION` → `strategy_position(strategyId).quantity` 기준(그 전략 물량 0이면 차단 — 다전략 교차 청산 방지).
   - `SELL_QUANTITY_UNAVAILABLE` → `strategy_position.quantity - reservedSell(strategyId)` 기준.
5. **합산 유지 룰**: KIS 잔고 하드 게이트(ADR 0106 §7), `POSITION_LIMIT`(종목 예산), `MAX_CONCURRENT_POSITIONS`(자본 노출)는 **user+code 합산 유지** — KIS·자본 노출은 전략 무관.
6. **도메인 port**: `StrategyPositionPort`(헌법 §6 abstract만) 신설 — `RiskPositionPort` 패턴 정합. live·리플레이·백테스트가 동일 도메인 모델 공유.

## 3. Consequences

- 동일 종목 2전략 동시 운용 시 익절/청산이 전략별 물량만 처리(readiness C-2 해소).
- 기존 단일전략 운용은 `strategy_position` 1행 = `position` 1행 정합(하위호환 — 동작 변화 0).
- 체결 listener가 position 갱신 시 strategy_position 동반 upsert — 체결 경로 1곳 추가 책임.
- drift는 fail-safe(KIS 권위) — 내부 ledger 버그가 실잔고 초과 SELL로 번지지 않음.

## 4. 운영 ADR 양식 3 슬롯 (헌법 §9-5)

### 4-1. SLO 영향

직접 SLO 수치 영향 0. SELL 정확도 향상(다전략 교차 청산 차단)으로 S-1(시그널→주문) 오발주 reject 감소. 체결 listener에 strategy_position upsert 1건 추가 — Outbox drain lag(P95<30초)에 무시 가능 수준(단건 upsert).

### 4-2. rollback plan

- 본 ADR은 정책 — 본 PR(ADR + spec + README + CHANGELOG)은 운영 코드 0 touch.
- 후속 구현: V65는 add-only 테이블 — rollback 시 코드의 strategy_position 사용만 제거하면 기존 position 기반 동작으로 복귀(자본 보호 공백 0 — position·KIS 하드 게이트 그대로). RiskGateway SELL 분기는 feature 분기라 합산 fallback으로 즉시 되돌림.

### 4-3. 관측 매트릭스

| 측정 항목 | 목표 | 측정 방법 |
|----------|------|----------|
| strategy_position 합 = position drift | 0건(WARN 시 조사) | `STRATEGY_POSITION_DRIFT` SystemEvent count |
| 다전략 교차 청산 reject | 정상 차단 | `risk_event` reason `SELL_WITHOUT_POSITION` (strategy 기준) |
| 전략별 실현손익 추적 | 100% | `strategy_position.realized_pnl` vs trading_journal |

## 5. 트레이드오프

| 결정 | 채택 사유 | 거부 대안 |
|------|----------|----------|
| 신규 테이블 (position 유지) | KIS 잔고 권위 보존 + lot 분리 + 정합 검증 | position에 strategy_id → UNIQUE 깨짐 + KIS 매핑 모호 + 마이그레이션 |
| SELL 룰만 전략별 분기 | 전략 청산 정합 + KIS 실잔고 권위 + 자본 노출 합산 정합 | 모든 룰 전략별 → KIS 매핑 깨짐 + budget 중복 |
| drift 시 KIS 권위 | fail-safe — 내부 버그가 실잔고 초과 SELL로 안 번짐 | 내부 ledger 권위 → KIS 불일치 시 거절 폭주 risk |

## 6. 변경 이력

| 날짜 | 변경 | 결정자 |
|------|------|--------|
| 2026-06-05 | **ADR 0110 Accepted** — `strategy_position` 1급 도메인 신설(UNIQUE user+strategy+code, V65). position(KIS 잔고 권위) 유지 + 전략별 lot ledger. 불변식(KIS 권위 우선) + RiskGateway SELL 2룰 전략별 분기(SELL_WITHOUT_POSITION·SELL_QUANTITY_UNAVAILABLE) + 합산 룰 유지(KIS게이트·POSITION_LIMIT·동시보유). StrategyPositionPort. 운영 코드 0 touch. | architect (readiness C-2) |
| 2026-06-05 | **amend (PR #741 검수 4건)** — V65에 `executed_exit_stages JSONB` 추가(부분 익절 1회 발화 불변식·체결 시 마킹). leg idempotency 권위 명문화(V38 실측: `uq_order_signal_user` DROP → `idempotency_key` UQ + Redis SETNX가 단일 권위). spec §3-1·§5-3 정합. | architect (orchestrator 검수) |
