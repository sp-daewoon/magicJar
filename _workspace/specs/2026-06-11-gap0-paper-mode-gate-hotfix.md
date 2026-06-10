# GAP-0 hotfix 미니 spec — 주문 게이트 paper origin 인지 (ADR 0122 §6 명세↔구현 정합)

> **작성**: architect · 2026-06-11 · 입력: `_workspace/reports/2026-06-10-strategy-mock-paper-flow.html` GAP-0 실측 + `2026-06-10-strategy-funnel-verification-and-next-steps.html` W0-1 + 사용자 결재 (2026-06-10 — "지금 진행")
> **성격**: hotfix (architect 미개입 사이클 변형 — 미니 spec 동반). 신규 ADR 발행 0 — ADR 0122 §6 LOCK의 구현 정합이므로 ADR 0122 구현 노트 amend만 동반.

## 1. 문제 (GAP-0)

`OrderRouterKafkaListener` Step 3 → `TradingModeGateImpl.resolveMode(userId)`는 env 3중 게이트(`MAGICJAR_MODE` + `MAGICJAR_ALLOW_REAL` + consent)만 보고 **전략 origin을 모른다**. 현재는 env=MOCK이라 페이퍼 주문도 자연히 모의 계좌로 가지만, REAL canary 진입 후에는 `origin=PAPER_DISCOVERY` 전략의 주문이 **실 주문으로 발사될 수 있다**. ADR 0122 §6 "페이퍼는 시스템 모드 무관 MOCK + 가상 자본 격리" LOCK과 구현이 불일치.

## 2. 설계 결정

### 2-1. 판정 위치 = 라우터 단일 관문 (payload 필드 추가 기각)

`order.intent.v1` 발행처는 4곳 — T-RISK(`SignalGeneratedKafkaListener`)·자동청산(`AutoExitOrderCoordinator`)·손절 watcher(`PositionStopLossTickWatcher`)·수동(`ManualOrderIntentKafkaProducer`). 페이퍼 포지션의 **청산·손절 주문도 동일 토픽**으로 나가므로, 발행처마다 paper 플래그를 심는 payload 방식은 누락 위험이 있다. 모든 intent가 통과하는 `OrderRouterKafkaListener`에서 `intent.strategyId` → strategy.origin **DB 단건 조회**로 판정한다 (일 50회 주문 cap — 비용 무시 가능). origin의 단일 진실원은 strategy 테이블(V81)이며 payload 복제본을 만들지 않는다 (키 사슬 정합 — ADR 0115 P2-⑥ "enum/필드 발명 0").

### 2-2. 강제 위치 = 게이트 (port 시그니처 확장)

```kotlin
interface TradingModeGate {
    fun resolveMode(userId: String, paperStrategy: Boolean = false): OrderResult<DenyReason.RealGateDenied, TradingMode>
}
```

`paperStrategy=true`면 **env 3중 게이트 평가 전에 즉시 `TradingMode.MOCK` 반환** (INFO 로그). 모드 결정 권위를 게이트 한 곳에 유지 (ADR 0068 §14-3 정합 — default 파라미터로 기존 계약 zero-regression).

### 2-3. fail-closed (헌법 §11-2 정합)

`resolvePaperStrategy(intent)` 판정 매트릭스:

| 입력 | 판정 |
|---|---|
| `strategyId <= 0` (MANUAL sentinel) | `false` — 수동 주문은 env 모드 따름 |
| origin == `PAPER_DISCOVERY` | `true` → MOCK 강제 |
| origin null/기타 | `false` — V81 legacy=USER_DSL (zero-regression) |
| repo 미합류·조회 예외·전략 미존재 + **env=REAL** | **deny** `REAL_GATE_DENIED:PAPER_ORIGIN_UNRESOLVED:*` (fail-closed — 잘못된 모드로 실주문 발사보다 거부가 안전) |
| 〃 + env=MOCK | ERROR 로그 + `false` 진행 (명시 degraded — env=MOCK이 어차피 MOCK 강제, silent 0) |

audit eventType은 기존 `REAL_GATE_DENIED` 재사용 (enum 발명 0).

## 3. 변경 파일

| 파일 | 변경 |
|---|---|
| `domain/.../port/TradingModeGate.kt` | `paperStrategy: Boolean = false` 파라미터 + KDoc (ADR 0122 §6) |
| `consumer/.../TradingModeGateImpl.kt` | paper 분기 — 3중 게이트 앞 MOCK 즉시 반환 |
| `consumer/.../OrderRouterKafkaListener.kt` | `resolvePaperStrategy` helper + Step 3 배선 + `ConsumerStrategyJpaRepository` ObjectProvider 주입 |
| `consumer/.../TradingModeGateImplTest.kt` | (e) paper+REAL 3조건 충족 → MOCK · (f) paper+MOCK → MOCK · (g) paper 시 consent 미호출 |
| `CHANGELOG.md` | row 1건 (작성: backend-core) |
| `_workspace/arch/adr/0122-*.md` | §6 구현 노트 amend 1건 |

## 4. 운영 영향 매트릭스 (헌법 §9-2)

- **SLO 영향**: S-1(시그널→주문 지연)에 strategy findById 1회 추가 — PK 단건 조회로 ms 단위, P95 5초 예산 대비 무시 가능. 개선/저하 없음.
- **rollback plan**: 코드 revert 단독으로 완결 (DB 마이그레이션 0·설정 0·이벤트 스키마 0). revert 시 현행(GAP-0 잔존) 복귀 — env=MOCK 유지 중에는 운영 동작 차이 0.
- **관측**: `TradingModeGate PAPER MOCK 강제` INFO 로그 + `REAL_GATE_DENIED:PAPER_ORIGIN_UNRESOLVED` audit row. REAL canary 전 qa 회귀 시나리오(W1-1)에서 "env=REAL이어도 페이퍼는 MOCK" E2E LOCK 예정.

## 5. 검증

- 단위: `TradingModeGateImplTest` 3 케이스 추가 (기존 4 케이스 LOCK 유지).
- 통합: pre-push `./gradlew check` (본 작업 환경은 JDK 21 부재로 미실행 — push 시점 hook이 게이트).
- 후속(W1-1): qa 페이퍼 격리 회귀 시나리오 + canary checklist 등재.
