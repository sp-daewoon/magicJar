# ADR 0122 — 발굴(Discovery) → MOCK 페이퍼 트레이딩 dispatch 경로

- **상태**: Accepted (2026-06-10 최고 관리자 결재 — spec §8 4건 권장값 일괄 승인 + R1 착수. 발행: orchestrator dispatch — "발굴 후보를 MOCK 페이퍼로 굴려보는 경로")
- **작성**: architect
- **트랙**: 단타 발굴 후속(ADR 0117 §1-4 B층) + 모의 단계(백테스트→모의→실전)
- **연관 ADR**: 0117(발굴 레이어 — 후보 출발점·결정권 0) · 0121(백테스트 사이징 정합 — 페이퍼 RiskConfig 프로필 권위) · 0067(RiskConfig·RiskGateway 6룰) · 0068(TradingModeGate MOCK/REAL 3중 게이트) · 0110(전략별 포지션 귀속) · 0115 P2-⑥(0-sentinel 폐지 + OrderTrigger 키 사슬 — **구현 선행 의존**)
- **결재**: 결재 완료 (2026-06-10 — §8 4건 전부 architect 권장값 채택: 다중선택 일괄 승격 · 가상 자본 1천만원 · RiskConfig 옵션 A `<userId>:paper` · DLL 3%/일 50회 1차)
- **구현**: 본 ADR은 **문서·계약 only**. 구현은 strategy-engine·backend-core·frontend·qa (spec §4 라운드).
- **spec/plan**: `_workspace/specs/2026-06-10-daytrade-mock-paper-dispatch-spec.md` (계약 LOCK 권위)

---

## §1. 컨텍스트 (왜 이 결정이 필요한가)

magicJar의 비전은 **백테스트 → 모의 → 실전** 3단 확장(project_overview)이다. 현재:

- **백테스트(과거)**: ADR 0056·0121 — 단타 6프리셋 OOS 측정 완료.
- **실전(REAL)**: ADR 0068 TradingModeGate 3중 게이트로 가드.
- **모의(페이퍼·실시간)**: **빠진 중간 고리**.

발굴(ADR 0117)은 "전략 없이도 오늘 단타 후보 TOP N"을 제시하지만, 사용자가 그 후보를 **실거래 전에 실시간으로 검증**할 경로가 없다. 백테스트는 과거 재현이고 실전은 자본 위험이다. 그 사이를 **페이퍼 트레이딩**(가상 자본·실 시세·MOCK 주문)으로 채운다. ADR 0117 §1-4가 이미 "B층(체결강도 incremental) = MOCK 페이퍼 A/B"로 예정한 후속이다.

## §2. 결정 (Decision)

### 2-A. 발굴 LOCK 불변 + 페이퍼 = 사용자 opt-in 검증 경로 (확장, 전환 아님)

발굴 §1-2 LOCK(발굴 DTO 주문 트리거 0·매매 결정권 0·watchlist write 0)은 **깨지 않는다**. 페이퍼 승격은 발굴 DTO가 아니라 **사용자 명시 opt-in 액션**(별 진입점)이 트리거한다. 발굴은 여전히 후보 출발점이고, 페이퍼는 사용자가 그 후보를 검증하는 실행 경로(백테스트·스크리닝과 같은 축). **자동 진입(opt-in 없는 자동 승격) 절대 금지** — 발굴 결과 표시·스코어카드 cron이 페이퍼 전략을 생성하면 안 된다(코드 리뷰 reject 게이트).

### 2-B. 기존 자본 경로 100% 재사용 — 신규 엔진·모듈 0

페이퍼 setup → `Strategy`(origin=PAPER_DISCOVERY) 인스턴스 1개 생성 → 기존 `RealtimeSignalGenerator` → `signal.generated.v1` → `SignalGeneratedKafkaListener`(RiskGateway 6룰) → `order.intent.v1` → `OrderRouterKafkaListener` → `TradingModeGate`(**MOCK 강제**) → `KisOrderHttpAdapter`(MOCK 분기). 신규 주문 엔진·신규 Kafka 토픽·신규 모듈 0. add-only: `Strategy.origin` enum + `PaperCapitalSnapshotProvider` 포트.

### 2-C. 가상 자본 격리 + ADR 0121 사이징 정합

- 페이퍼 RiskGateway 평가는 `PaperCapitalSnapshotProvider`(가상 자본)를 쓴다 — 실 `CapitalSnapshotProvider`(KIS 잔고) 미호출. 페이퍼 동작이 실 잔고 기반 리스크 판정을 오염시키지 않는다.
- 페이퍼 RiskConfig 프로필 = ADR 0121 정합: `maxPositionPercent 10%`·`maxConcurrentPositions 5`·`maxPositionAmount`. 즉 백테스트·페이퍼·실전이 같은 사이징 모델로 일관 측정.
- 페이퍼 포지션은 기존 `strategy_position`(전략별 lot ledger, ADR 0110)에 자연 귀속 — 별 테이블 발명 0.

### 2-D. 페이퍼 ≠ mock 합성 (데이터 정책 경계)

CLAUDE.md "mock 데이터 금지"(`feedback_no_mock_data`)는 합성 데이터로 화면을 채우는 것 금지다. 페이퍼 주문/체결/P&L은 **실 시세(KIS WS tick·실 candle) 기반 실제 시뮬 기록**이다 — 합성 아님. "가상 자본"은 금액 규모일 뿐 데이터 출처는 실측. 따라서 mock 금지 위반 아님. 단 UX에서 "모의 — 실거래 아님" 명시 배지 의무(spec §6).

### 2-E. 구현 선행 의존 — ADR 0115 P2-⑥

페이퍼 주문도 `order_request`에 발사 출처 키를 보존해야 한다. P2-⑥(`signal_id`·`strategy_id` 0-sentinel 폐지 + `OrderTrigger` enum)가 키 스키마를 정비 중 → 본 ADR 구현 R1 착수는 **P2-⑥ 완료 후**(OrderTrigger enum이 main에 존재). 페이퍼는 1차 LOCK으로 `OrderTrigger.STRATEGY_SIGNAL` 재사용 + mode=MOCK + origin=PAPER_DISCOVERY 조합으로 식별(enum add 최소화). spec 문서 자체는 코드 0 touch라 충돌 0.

## §3. 대안 (Alternatives)

| 대안 | 내용 | 기각 사유 |
|---|---|---|
| A (채택) | 발굴→opt-in 승격→Strategy(MOCK) 인스턴스→기존 signal→risk→order 재사용 | — |
| B | 페이퍼 전용 신규 주문 시뮬 엔진 + 페이퍼 ledger 테이블 신설 | 과개발 — 기존 경로(order_request mode·strategy_position)로 충분. skepticism lens 위반 |
| C | 발굴 후보 자동 페이퍼 승격(opt-in 없이) | 발굴 §1-2 LOCK 훼손 — "후보 출발점" 원칙 위반. 사용자 의도 없는 자본 시뮬 |
| D | 페이퍼를 백테스트 확장(과거 재현)으로 대체 | 페이퍼의 본질(실시간 실 시세 모의) 미충족. 백테스트는 이미 ADR 0056·0121 |

## §4. 자원 audit (ADR 0102)

- **Flyway**: 최신 V79. 다음 가용 V80(타 세션 P2-⑥이 V80 점유 가능 → R2 진입 시 동적 부여). 페이퍼 RiskConfig 프로필 + 페이퍼 식별 컬럼.
- **REST**: `/api/daytrade/paper/*`(promote·중단·positions·strategies) 신규 namespace — 충돌 0.
- **Strategy.origin**: enum add-only(USER_DSL default·PAPER_DISCOVERY) — 충돌 0.
- **Kafka/STOMP**: 신규 0(기존 signal.generated.v1·order.intent.v1 재사용. STOMP `/topic/daytrade/paper/{userId}` 선택).
- **ENV**: `MAGICJAR_DAYTRADE_PAPER_ENABLED`·`_DEFAULT_CAPITAL_KRW`·`_MAX_INSTANCES` add-only.

상세는 spec §2.

## §5. 영향 (Consequences)

- **긍정**: 백테스트↔실전 사이 모의 고리 완성. 발굴 후보를 실 시세로 검증 후 실전 승격 판단. 사이징 모델 3단 일관(ADR 0121).
- **부정/트레이드오프**: 페이퍼 전략도 시그널 평가·WS 수요 슬롯을 소비(STRATEGY owner priority 40 — 보유 구독 미경합). 페이퍼 인스턴스 상한(`_MAX_INSTANCES` default 10)으로 제한.
- **SLO**: 직접 수치 0. 페이퍼는 MOCK 경로라 KIS 실주문 0.
- **rollback**: `MAGICJAR_DAYTRADE_PAPER_ENABLED=false`로 승격 API 비활성. `Strategy.origin`은 nullable add라 무시 시 현행 회귀.

## §6. 사용자 결정 필요 (spec §8)

1. 페이퍼 승격 opt-in UI 위치 (권장: 후보 다중선택 일괄 승격)
2. 가상 자본 default 금액 (제안: 1천만원)
3. RiskConfig 페이퍼 프로필 저장 방식 (권장: 파생 userId 키 옵션 A)
4. 페이퍼 리스크 임계 DLL·MDD·거래빈도 (제안: defaultFor 정합 1차)

## §7. 변경 이력

| 날짜 | 변경 | 작성 |
|---|---|---|
| 2026-06-10 | ADR 0122 신설 — 발굴→MOCK 페이퍼 dispatch 경로 (Proposed). 발굴 LOCK 불변 + opt-in 승격 + 기존 자본 경로 재사용 + 가상 자본 격리 + ADR 0121 사이징 정합 + P2-⑥ 선행 의존. 코드 0 touch. | architect |
