# ADR 0135 — Trailing Stop 영속 (strategy.trailing_stop_pct)

- **상태**: Accepted (오케스트라 위임 결재 2026-06-26 — P0 영속 갭 해소)
- **날짜**: 2026-06-26
- **작성**: backend-core
- **관련**: ADR 0111(분할매수/분할익절 영속 — scale_in_config 매핑 패턴) · ADR 0114(진입 정책 영속 — entry_policy) · ADR 0128(scale-in 자본 재설계 — 매핑 누락 drift 교훈) · ADR 0129(autopilot 오버나잇 토글 — allow_overnight 양쪽 매핑 LOCK) · ADR 0130/0132(TradePlan 박제·라이브-백테스트 parity — TradePlanFactory) · `feedback_verification_strict`(단위테스트≠라이브)
- **롤백 앵커**: v3.14.x
- **범위 게이트**: `strategy` 테이블 단일 컬럼 add(nullable default null) · 평가·박제 로직 변경 0 · 영속 경로(엔티티 양쪽·DTO·서비스)만 add
- **자본 경로**: 중위험 (trailing = 보유 포지션 청산 레버. 단, 저장 항목 추가일 뿐 평가/주문 로직은 기존 그대로이며 nullable default null 로 기존 전략 거동 byte-동일)

---

## 1. 맥락 (왜 지금)

trailing stop(트레일링 스탑 — 진입 후 갱신되는 고점[high-watermark] 대비 일정 비율 하락 시 자동 청산하는 청산 레버)은 **평가·박제·도메인은 이미 완비**돼 있으나 **DB 영속만 빠져** 라이브 전략에 trailing 값을 저장할 수 없는 상태였다(항상 null).

실측 확인된 현황:

| 레이어 | 상태 | 위치 |
|--------|------|------|
| 도메인 | ✅ 존재 | `StrategyDefinition.trailingStop: BigDecimal? = null` |
| 박제 | ✅ 존재 | `TradePlanFactory.build` → `trailPct > 0` 시 `TradePlanTrailing(enabled=true, trailPct)` |
| 평가 | ✅ 존재 | `TradePlanEvaluator` — 고점 대비 trailPct 하락 시 TRAIL 청산(우선순위 보유) |
| **DB 컬럼** | ❌ 부재 | `strategy` 테이블에 trailing 컬럼 없음(take_profit_pct·fixed_stop_loss_pct 만 존재) |
| **엔티티 매핑** | ❌ 부재 | api `StrategyJpaEntity`·consumer `ConsumerStrategyJpaEntity` 양쪽 미매핑 |
| **DTO·서비스** | ❌ 부재 | `StrategiesController` create/update·`StrategyService` 미반영 |

trailing 은 1분봉 scalping 전략(oversold-crash-bounce)의 **유일한 break-even 레버**라 영속이 선결 과제다. 평가 파이프는 이미 trailing 을 소비할 준비가 됐으므로, 영속 경로만 채우면 즉시 작동한다.

## 2. 결정

`strategy.trailing_stop_pct NUMERIC(9,4) nullable` 컬럼을 신설하고, trailing 을 **take_profit_pct·fixed_stop_loss_pct 와 동급의 정식 영속 청산 항목**으로 청산 정책에 추가한다. 평가·박제 로직(`TradePlanEvaluator`·`TradePlanFactory`)은 **변경 0** — 이미 trailing 을 지원하므로 영속 경로(엔티티 양쪽·save·load·DTO·서비스)만 add-only 로 채운다.

### 2-1. 매핑 양쪽 의무 (drift 차단 LOCK)

ADR 0128(scale_in_config)·ADR 0129(allow_overnight) 교훈 — api 엔티티만 매핑하고 consumer 복제 엔티티 매핑을 누락하면 **신호평가(hot path)에서 trailing 이 null 로 덮여 박제 자체가 안 되는 자본 버그**가 발생한다(scale_in drift 와 동형 사고). 따라서:

- api `StrategyJpaEntity` + `StrategyEntityMapper.toDomain`(load) + `StrategyJpaAdapter.toEntity`(save) 양방향
- consumer `ConsumerStrategyJpaEntity` + `ConsumerStrategyLookupJpaAdapter.toDomainOrNull`(load)

네 지점 모두 `trailing_stop_pct ↔ StrategyDefinition.trailingStop` 을 매핑한다.

### 2-2. 정규화·검증

`StrategyService.normalizeTrailingStopPct` — null=비활성(현행), 0 이하는 400(takeProfitPct·stopLossPct 패턴 정합·도메인 init 500 회피). create·update 양쪽에서 적용.

## 3. zero-regression 보장

- 컬럼 nullable default null → 기존 전략 행은 모두 `trailing_stop_pct = NULL` → load 시 `trailingStop = null` → `TradePlanFactory` 의 `trailPct = definition.trailingStop?.takeIf { it > ZERO }` 가 null → `trailing` 미박제 → **기존 청산 거동 byte-동일**.
- `update` 가 `StrategyDefinition` 을 매번 새로 빌드하므로, trailing 미전달 시(null) 명시적으로 null 로 저장(다른 청산 필드와 동일 정책 — 의도된 거동).
- 평가·박제 코드 0 touch → TRAIL 청산 발화 규칙·우선순위 불변.

## 4. 마이그레이션·롤백

- **마이그레이션**: Flyway `V96__strategy_trailing_stop_pct.sql` — V번호 audit 결과 main 최신 = V95(`strategy_position_trade_plan`), 충돌 없이 다음 번호 V96. `ADD COLUMN IF NOT EXISTS` 멱등.
- **롤백**: 컬럼은 nullable·기존 코드가 참조 0이므로 데이터 손실 없이 잔류 가능. 코드 롤백 시 컬럼은 무시되며(읽지 않음) 거동 영향 0. DB 컬럼 제거가 필요하면 `ALTER TABLE strategy DROP COLUMN trailing_stop_pct`(앵커 v3.14.x).

## 5. 대안 기각

- **JSONB 통합(scale_in_config 류)**: trailing 은 단일 스칼라 청산 레버라 take_profit_pct·fixed_stop_loss_pct 와 정확히 동형. 별도 컬럼이 쿼리·인덱싱·가독성 우위. JSONB 는 over-engineering.
- **평가 로직에서 default trailing 부여**: 명시 영속 없이 코드 상수로 trailing 강제 시 사용자 의도 박탈·기존 전략 회귀. 기각.

## 6. 자원 audit (ADR 0102)

- **Flyway**: V96 단일 컬럼 add.
- **REST/Kafka/STOMP/ENV**: 0 신설(기존 `/api/strategies` create/update 재사용·DTO 필드 add-only).
- **재사용 LOCK**: StrategyDefinition.trailingStop · TradePlanFactory · TradePlanEvaluator · StrategyEntityMapper · StrategyJpaAdapter · ConsumerStrategyLookupJpaAdapter · StrategyService create/update.
- **KIS/AI/mock**: 0.
