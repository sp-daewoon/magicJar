# spec — 발굴(Discovery) → MOCK 페이퍼 트레이딩 dispatch 경로

- 작성: 2026-06-10, architect (orchestrator dispatch — "발굴 후보를 MOCK 페이퍼로 굴려보는 경로")
- 상태: **Proposed (사용자 결재 대기 — §8 결정 필요 4건 미결)**
- 출발점: `_workspace/specs/daytrade-discovery-spec.md` §1-4 ("B층 = 체결강도 incremental 검증 MOCK 페이퍼 A/B"로 이미 예정된 후속) · ADR 0117(발굴 레이어) · ADR 0121(백테스트 사이징 정합)
- 동반 ADR: **ADR 0122** (발굴→페이퍼 승격 경로 — 신규, §7 판단)
- 데이터 계약 LOCK: 본 §3·§4 계약은 R1~R3 sub-agent가 그대로 따른다. 임의 변경 금지.
- **구현 라운드 선행 조건 (LOCK)**: 본 spec의 R1~R3 구현은 **타 세션 ADR 0115 P2-⑥(`order_request` signal_id·strategy_id 0-sentinel 폐지 + `OrderTrigger` enum) 완료를 선행**으로 한다(§4-0). spec 문서 자체는 코드 0 touch이므로 충돌 0.

---

## §1. 방향성 가드 분석 (`.claude/skills/magicjar-direction-guard`)

```text
기존 방향:
  (A) 발굴 = "전략 없이도 오늘 단타 후보 TOP N 수치 제시". 발굴 DTO는 주문 트리거 필드 0,
      매매 결정권 0, watchlist write 0 (daytrade-discovery-spec §1-2·§3-R2 LOCK — 절대 불변).
  (B) 자동매매 자본 경로 = 사용자 DSL Strategy(ACTIVE) → RealtimeSignalGenerator 평가 → signal.generated.v1
      → SignalGeneratedKafkaListener(RiskGateway 6룰) → order.intent.v1 → OrderRouterKafkaListener
      → TradingModeGate(MOCK/REAL 3중 게이트) → KisOrderHttpAdapter(MOCK이면 모의 KIS).
  (C) MOCK/REAL 이중 게이트 (CLAUDE.md 운영함정 §4): MAGICJAR_MODE=REAL + MAGICJAR_ALLOW_REAL=true +
      user 동의 3중 충족 시에만 실주문. dev/staging은 항상 MOCK (TradingModeGateImpl.kt:48-78).
  (D) RiskConfig = userId PK 단일 행 (risk_config 테이블). defaultFor = maxPositionPercent 10%·
      maxConcurrentPositions 5·dailyLossLimitPercent 3% (RiskConfig.kt:71-89).
  (E) capital = CapitalSnapshotProvider.getCapital(userId) — KIS 잔고 기반 실자본
      (SignalGeneratedKafkaListener.kt:799).

변경 의도:
  발굴 후보를 사용자가 "이 setup으로 모의(페이퍼) 돌려보기" opt-in 하면, 그 setup을
  MOCK 모드 전략 인스턴스로 만들어 기존 (B) 경로에 태운다. 주문은 항상 MOCK,
  포지션은 페이퍼 포지션(가상 자본 기준). 실거래 결정권은 여전히 0.

방향성 영향: 유지 (보수적 확장 — 전환 아님). 근거 §1-1.
  → 발굴 LOCK(A)은 깨지 않는다. 페이퍼 승격은 발굴 DTO가 아니라 "사용자 opt-in 액션"이
    별도 진입점에서 트리거한다. 발굴 결과는 여전히 출발점일 뿐.
  → 신규 자본 경로·신규 주문 엔진을 발명하지 않는다. 기존 (B) 100% 재사용 + MOCK 강제(C).
확인할 리스크: 자동 진입 금지(§1-2) · 가상 자본 격리(§1-3) · 페이퍼≠실거래 UX 오인(§6) ·
  키 사슬(P2-⑥ 선행 §4-0) · 자원 충돌(§2).
```

### 1-1. 핵심 질문 — 확장인가 전환인가? → **확장 (전환 아님)**

발굴 LOCK의 본질은 **"발굴은 후보 출발점이지 매매 결정이 아니다"**(daytrade-discovery-spec §1-2). 본 spec은 이 LOCK을 **깨지 않는다**:

| 단계 | 누가 결정 | 발굴 LOCK 정합 |
|---|---|---|
| 발굴 후보 제시 | 시스템 빌트인 룰 (read-only) | 발굴 DTO 주문 트리거 0 — 불변 |
| **페이퍼 승격** | **사용자 명시 opt-in 액션** (자동 진입 금지 §1-2) | 발굴 결과를 사용자가 "검증 실행"하는 출발점 — 1:1 정합 |
| 페이퍼 주문 | 기존 signal→risk→order 경로 + MOCK 강제 | 실거래 결정권 0 (TradingModeGate MOCK) |

→ 발굴은 **여전히 후보 출발점**이고, 페이퍼는 사용자가 그 후보를 **검증하는 실행 경로**다. CLAUDE.md FVG 가드 "전략 후보 프리셋은 자동매매 결정을 대신하지 않는다. 사용자가 검증·저장·스크리닝을 실행하는 후보 출발점" 1:1 정합. 페이퍼 트레이딩 = "검증" 행위의 한 종류(백테스트·스크리닝과 같은 축). **전환 아님.**

### 1-2. 자동 진입 절대 금지 (LOCK)

- **발굴 → 페이퍼 자동 승격 금지**. 발굴 결과가 화면에 뜨거나 스코어카드가 가동되는 것만으로 페이퍼 전략 인스턴스가 생성되어선 **안 된다**. 반드시 **사용자 명시 액션**(화면 버튼 또는 API 호출)이 트리거한다.
- 근거: orchestrator 가드 블록 "전략 후보를 watchlist에 자동 삽입하지 않는다" + 발굴 §1-2 매매 결정권 0. 페이퍼라도 "자동으로 굴러가기 시작"하면 사용자 의도 없는 자본 시뮬이 발생 → 발굴 출발점 원칙 훼손.
- R2/R3 코드 리뷰 게이트: 발굴 응답 핸들러·스코어카드 cron이 페이퍼 전략을 INSERT하는 경로가 있으면 **reject**.

### 1-3. 가상 자본 격리 — 페이퍼는 실자본·실 KIS 잔고를 건드리지 않는다

- 페이퍼 전략의 RiskGateway 평가는 **가상 자본 snapshot**을 사용한다(§3-R2 `PaperCapitalSnapshotProvider`). 실 `CapitalSnapshotProvider.getCapital(userId)`(KIS 잔고)을 호출하지 않는다 → 페이퍼 동작이 실 잔고 기반 리스크 판정을 오염시키지 않는다.
- 페이퍼 포지션은 **별도 페이퍼 ledger**에 기록(§3-R2). 실 `strategy_position`·KIS 잔고와 섞이지 않는다 — 자본 흐름 격리 LOCK.
- 페이퍼 주문은 TradingModeGate가 MOCK으로 강제(§1-1 (C)) → KIS 실주문 0. dev/staging 환경은 이미 항상 MOCK이므로 추가 위험 0.

### 1-4. 데이터 정합 — 페이퍼는 mock 합성이 아니다 (중요 구분)

- CLAUDE.md "mock 데이터 사용 금지"(`feedback_no_mock_data`)는 **합성 데이터로 화면을 채우는 것** 금지다.
- 페이퍼 주문/체결은 **실 시세(KIS WS tick·실 candle close) 기반 실제 시뮬 기록**이다 — 합성 아님. 시그널은 실 시세로 발생하고, 체결가는 실 시세 기준이며, 결과 P&L은 그 실 시세로 계산된 실제 수치다. "가상 자본"은 금액 규모일 뿐 데이터 출처는 실측.
- 따라서 페이퍼는 mock 금지 정책 **위반이 아니다**. 단 UX에서 "실거래 아님(모의)"임을 명확히 구분 표시(§6).

### 1-5. 기존 인프라 재사용 검증 (신규 모듈·엔진 발명 0)

| 필요 기능 | 재사용 대상 (기존) | 신규 발명 |
|---|---|---|
| 전략 인스턴스 | `Strategy`(userId·definition.dsl·codes·intervals·status) — strategy 테이블 | 0 (origin 메타만 add) |
| 시그널 생성 | `RealtimeSignalGenerator` → signal.generated.v1 | 0 |
| 리스크 게이트 | `SignalGeneratedKafkaListener`(RiskGateway 6룰) | 0 |
| 주문 라우팅 | `OrderRouterKafkaListener` → `TradingModeGate`(MOCK 강제) | 0 |
| MOCK 주문 | `KisOrderHttpAdapter`(MOCK 분기 — dev 기본) | 0 |
| 리스크 설정 | `RiskConfig`(userId PK) | 페이퍼 프로필 1 행 add (§3-R2) |
| 가상 자본 | `CapitalSnapshotProvider` 포트 | 페이퍼 구현체 1 add (§3-R1) |

→ **신규 모듈 0, 신규 주문 엔진 0**. 발굴 setup → Strategy 인스턴스 1개 생성 + 가상 자본 주입 + 기존 경로 태우기. 보수적 보강 LOCK.

---

## §2. 자원 audit (main 점유 검증 — ADR 0102 정합)

검증 시점 main = `15878530`. **Flyway 재audit (2026-06-10 실 디렉토리 ls)**: `api/src/main/resources/db/migration/` 최신 = **V79** (`V79__system_config_slack_webhook_order.sql`). V75~V78이 단타 프리셋(시드·검증·스코어카드·V78 사이징 재검증), V79가 slack webhook. → **다음 가용 = V80**.

> ⚠ **타 세션 V80 예약 가능성**: ADR 0115 P2 R2(체결-원장-리스크 체인) 진행 세션이 P2-⑥(OrderTrigger enum 컬럼 add) Flyway를 발행하면 V80을 점유할 수 있다. 본 spec 구현 R2 진입 시점에 `ls db/migration/` 재확인 후 **다음 가용 번호 동적 부여** 의무(번호 고정 금지). 본 문서는 `V8x__daytrade_paper_*`로 placeholder 표기.

| 자원 | 신규 점유 | main 충돌 검증 | 결론 |
|---|---|---|---|
| **Flyway** | `V8x__daytrade_paper_profile.sql` (R2 — risk_config 페이퍼 프로필 + 페이퍼 ledger 스키마) | 최신 V79. P2-⑥이 V80 점유 가능 → R2 진입 시 재확인·동적 부여 | 번호 동적 LOCK |
| **REST path** | `POST /api/daytrade/paper/promote` (발굴 setup → 페이퍼 전략 생성) · `DELETE /api/daytrade/paper/{strategyId}` (중단) · `GET /api/daytrade/paper/positions` (페이퍼 포지션 조회) (R2) | `/api/screener/*`·`/api/strategy-research/*`·`/api/orders/*` 점유 (ScreenerController·StrategyResearchController·OrderController). `/api/daytrade/paper/*` 신규 namespace | 충돌 0 (namespace 분리) |
| **Strategy origin 메타** | `strategy.origin` enum add-only (`USER_DSL`·`PAPER_DISCOVERY`) — 발굴 출처 식별 (R1) | `Strategy.kt` — origin 필드 미존재 | add-only 충돌 0 |
| **RiskConfig 페이퍼 프로필** | `risk_config`에 페이퍼 전용 행 (userId 파생 키 — §3-R2 §8-3 결정 필요) | risk_config userId PK 단일 행 (V?? 스키마) | §8-3 결정 후 확정 |
| **OrderTrigger** | `PAPER`(또는 기존 `STRATEGY_SIGNAL` 재사용 + mode=MOCK 구분) (R1) | **P2-⑥ 산출 enum 의존** (§4-0). main 미머지 | P2-⑥ 선행 LOCK |
| **Kafka topic** | 0 (기존 signal.generated.v1·order.intent.v1 재사용. mode=MOCK 페이로드 마커로 구분) | — | 신규 0 |
| **STOMP destination** | (선택) `/topic/daytrade/paper/{userId}` (페이퍼 체결·포지션 푸시) (R2) | `/topic/*` 점유. `daytrade/paper` prefix 분리 | 충돌 0 |
| **Redis key** | (선택) `daytrade:paper:capital:<userId>` (가상 자본 잔액 캐시) | `screener:*`·기존 prefix | `daytrade:paper:` 분리 충돌 0 |
| **ENV** | `MAGICJAR_DAYTRADE_PAPER_ENABLED`(default true) · `MAGICJAR_DAYTRADE_PAPER_DEFAULT_CAPITAL_KRW`(§8-2 결정 필요) · `MAGICJAR_DAYTRADE_PAPER_MAX_INSTANCES`(default 10) | 기존 MAGICJAR_* 점유 (grep — 위 3종 미존재) | add-only 충돌 0 |

---

## §3. 데이터 계약 LOCK (R1~R3 그대로 따름)

### R1 — 도메인 + 시그니처 (domain + strategy-engine)

**계약 1 — `Strategy.origin` add (`domain/.../strategy/Strategy.kt`, add-only)**:

발굴 페이퍼 전략을 사용자 DSL 전략과 데이터 차원에서 구분한다 (OrderSource AUTO/MANUAL 구분 선례 정합).

```kotlin
/** 전략 출처 — 사용자 작성 DSL vs 발굴 페이퍼 승격 (add-only, default USER_DSL = 하위호환) */
val origin: StrategyOrigin = StrategyOrigin.USER_DSL,
```
```kotlin
enum class StrategyOrigin {
    USER_DSL,          // 사용자가 StrategyBuilder로 작성 (기존 전부 — default)
    PAPER_DISCOVERY,   // 발굴 setup을 사용자 opt-in으로 페이퍼 승격한 인스턴스
}
```
- default `USER_DSL` → 기존 모든 전략 무변경(zero-regression). `strategy` 테이블에 `origin` 컬럼 add-only nullable, 기존 row는 `USER_DSL` backfill.

**계약 2 — `PaperCapitalSnapshotProvider` 포트 (가상 자본 주입)**:

기존 `CapitalSnapshotProvider`(KIS 잔고)와 별도. 페이퍼 전략(`origin=PAPER_DISCOVERY`) 시그널 평가 시 RiskContext.currentCapital을 가상 자본으로 채운다.

```kotlin
// domain/.../risk/port/CapitalSnapshotProvider 와 동일 시그니처, 별 빈
interface PaperCapitalSnapshotProvider {
    /** 페이퍼 가상 자본 잔액 (초기 default - 페이퍼 실현손익 누적). 음수 방지 max(0). */
    fun getCapital(userId: String): BigDecimal
}
```
- **선택 분기 LOCK**: `SignalGeneratedKafkaListener`의 capital 산출(SignalGeneratedKafkaListener.kt:799)이, 평가 대상 전략이 `PAPER_DISCOVERY`면 `PaperCapitalSnapshotProvider`, 아니면 기존 `CapitalSnapshotProvider`를 쓴다. **R1은 포트 + 분기 시그니처만**(실 배선은 R2).
- 가상 자본 default 금액은 §8-2 사용자 결정.

**계약 3 — `OrderTrigger` 정합 (P2-⑥ 의존 §4-0)**:
- 페이퍼 주문은 P2-⑥ 산출 `OrderTrigger` enum에서 출처를 보존한다. 옵션:
  - (a) 기존 `STRATEGY_SIGNAL`(전략 시그널)로 두고 mode=MOCK + origin=PAPER_DISCOVERY 조합으로 페이퍼 식별 — enum add 0.
  - (b) `OrderTrigger.PAPER` 신규 값 add.
- **architect 1차 LOCK = (a)** — 페이퍼 주문도 "전략 시그널이 발사한 주문"이라는 본질은 동일하고, 페이퍼 여부는 mode=MOCK + strategy.origin으로 이미 식별 가능. enum 최소화. (b)는 P2-⑥ 세션과 협의 필요 시에만. → 본 spec은 (a) 전제로 진행. P2-⑥ enum이 확정되면 R1에서 재확인.

### R2 — 어댑터 + 영속 (api + consumer + persistence-shared)

**REST endpoint 시그니처 (신규 namespace `/api/daytrade/paper`)**:

```
POST /api/daytrade/paper/promote
  (발굴 setup → MOCK 페이퍼 전략 인스턴스 생성 — 사용자 명시 opt-in. 자동 진입 0 §1-2)
```
- request `PaperPromoteRequest`:
  - `userId: Long`
  - `presetKey: String` (발굴 빌트인 setup key — `strategy_research_candidate.candidate_key`. 이 setup의 `entry_dsl`·exit 파라미터를 Strategy.definition으로 복제)
  - `codes: List<String>` (페이퍼로 감시할 종목 — 발굴 결과 TOP N 중 사용자 선택. 빈 리스트 reject)
  - `interval: String = "1d"` (장전 setup 기준. 장중 분봉은 데이터 best-effort)
  - `virtualCapitalKrw: BigDecimal?` (null이면 ENV default §8-2. 사용자 override 허용)
- response `PaperStrategyDto`:
  - `strategyId: Long` (생성된 페이퍼 전략 id)
  - `origin: "PAPER_DISCOVERY"` · `presetKey` · `presetTitle` · `codes` · `virtualCapital` · `status`
- 동작:
  1. 발굴 setup(`strategy_research_candidate` row)을 read → `entry_dsl`을 `StrategyDefinition`으로 변환.
  2. `Strategy(userId, definition, origin=PAPER_DISCOVERY, codes, intervals=[interval], status=ACTIVE)` INSERT.
  3. 페이퍼 RiskConfig 프로필 보장 (§아래 — userId 파생 or 동일 행 + 가상 자본 분리).
  4. 가상 자본 잔액 초기화 (`daytrade:paper:capital:<userId>` Redis 또는 페이퍼 ledger seed).
  5. **watchlist write 0** (발굴 §1-2 LOCK 정합). 종목 감시는 STRATEGY owner WS 수요로만(발굴 spec §3-R2 경로 재사용).

```
DELETE /api/daytrade/paper/{strategyId}   — 페이퍼 전략 중단 (status=DELETED soft delete)
GET    /api/daytrade/paper/positions      — 페이퍼 포지션·실현/평가손익 조회 (userId)
GET    /api/daytrade/paper/strategies     — 활성 페이퍼 전략 목록
```

**RiskConfig 페이퍼 프로필 (ADR 0121 사이징 정합 LOCK)**:
- 페이퍼 RiskConfig 값 (ADR 0121 §2-A FixedFractional 정합):
  - `maxPositionPercent = 10.00` (1포지션 = 가상 자본 10% — ADR 0121 정합)
  - `maxConcurrentPositions = 5` (동시 5종 — ADR 0121 정합)
  - `dailyLossLimitPercent` / MDD 임계 = §8-4 결정 필요 (default 3% 정합 제안)
  - `maxTradesPerMinute`·`maxTradesPerDay` = defaultFor 동일 (단타라 maxTradesPerDay 상향 검토 — §8-4)
- **저장 위치 (§8-3 결정 필요)**: 두 옵션 —
  - 옵션 A: `risk_config` 행을 페이퍼 전용 파생 userId 키(`<userId>:paper`)로 별 행 INSERT. RiskGateway가 페이퍼 전략 평가 시 이 키로 config 조회. (격리 명확·기존 스키마 재사용)
  - 옵션 B: `risk_config`에 `profile` 컬럼 add (`REAL`·`PAPER`) + (userId, profile) 복합 키. (스키마 변경·명시적)
  - **architect 1차 권장 = 옵션 A** (스키마 add 최소·기존 userId PK 재사용. 단 userId가 String이라 파생키 충돌 없음 확인됨). §8-3 사용자 확정.

**페이퍼 ledger·체결 영속**:
- 페이퍼 주문은 기존 `order_request`에 `mode=MOCK` + `source=AUTO` + origin 추적으로 기록(이미 mode 컬럼 존재 — 별 테이블 발명 0). 페이퍼 식별 = 발사 전략의 `origin=PAPER_DISCOVERY`.
- 페이퍼 포지션: 기존 `strategy_position`(전략별 lot ledger)을 그대로 사용 — 페이퍼 전략도 strategyId가 있으므로 자연스럽게 분리 귀속됨(ADR 0110 전략별 포지션 귀속 정합). 실 KIS 잔고 하드 게이트는 MOCK 모드라 우회.
- **실 KIS 잔고 게이트 우회 검증 LOCK**: RiskGateway의 "KIS 잔고 하드 게이트"(RiskContext §6-2)는 페이퍼에서 가상 자본 기준으로 평가해야 한다. R2는 `PAPER_DISCOVERY` 전략 평가 시 KIS 잔고 조회를 가상 자본으로 치환하는 분기를 명시(strategyPosition 귀속은 페이퍼 전략 strategyId로 자연 분리).

**발굴 → 페이퍼 감시 종목 WS 연결**:
- 페이퍼 전략 codes를 `RealtimeDemandOwner.STRATEGY` 수요로 등록 (발굴 spec §3-R2 경로 1:1 재사용, ownerId=`daytrade-paper:<strategyId>`). watchlist write 0 LOCK.

### R3 — UI + 테스트 일괄 (frontend + qa + strategy-engine)

**frontend (발굴 화면 확장)**:
- 발굴 결과 TOP N 화면에 **"모의로 굴려보기"(페이퍼 승격)** 사용자 액션 버튼 추가 (§8-1 위치 결정).
- 페이퍼 전략 대시보드: 활성 페이퍼 전략 목록 + 페이퍼 포지션 + 가상 자본 잔액 + 실현/평가손익 + 중단 버튼.
- **UX 가드 (§6 LOCK)**: "모의(페이퍼) — 실거래 아님" 배지·색상 구분 의무. 실 계좌 포지션과 시각적으로 명확히 분리.

**qa Visual (헌법 §9-2)**:
- 페이퍼 승격 → 페이퍼 대시보드 표시 → 페이퍼 포지션 갱신 E2E Playwright + 스크린샷 시각 회귀.
- "모의 배지" 시각 회귀 (실거래 화면과 오인 0 검증).

**strategy-engine (테스트 일괄)**:
- 페이퍼 경로 시나리오 테스트: 발굴 setup → promote → 가상 자본 RiskGateway 평가 → MOCK 주문 → 페이퍼 포지션 → 실현손익. KIS 실주문 0 검증. 실 잔고 미오염 검증.

---

## §4. 라운드 계획 + 영역 매핑 (CHANGELOG 슬롯 ADR 0050)

### 4-0. 구현 라운드 선행 조건 (LOCK — 타 세션 의존)

> **타 세션 ADR 0115 P2 R2 진행 중** (P2-②④⑤⑥ — consumer·persistence·domain 키 사슬). 본 spec의 **구현 R1 착수는 P2-⑥(0-sentinel 폐지 + OrderTrigger enum) 완료 후**로 한다. 사유: 페이퍼 주문도 `order_request`에 출처 키를 보존해야 하는데, P2-⑥이 키 스키마(`signal_id`·`strategy_id` 0-sentinel 폐지·OrderTrigger)를 정비 중 → 그 전에 페이퍼 주문 키를 설계하면 P2-⑥ 산출과 충돌·재작업. **P2-⑥ 산출 인터페이스(OrderTrigger enum·실키 보존 규약)를 전제로만** R1 키 설계. spec 문서(본 문서)는 코드 0 touch라 지금 발행해도 충돌 0.
> P2-⑥ 완료 신호 = ADR 0115 P2-⑥ 머지 + `OrderTrigger` enum이 main에 존재. orchestrator가 선행 확인 후 본 spec R1 dispatch.

| 순번 | 라운드 | 담당 | 범위 | 의존 | 병렬 |
|---|---|---|---|---|---|
| **0 (선행)** | P2-⑥ (타 세션) | backend-core | `OrderTrigger` enum + 0-sentinel 폐지 | — | 본 spec R1의 **전제** |
| **1** | **R1** | strategy-engine(domain·엔진) ∥ backend-core(포트) | `Strategy.origin` add + `StrategyOrigin` enum + `PaperCapitalSnapshotProvider` 포트 + capital 분기 시그니처 + OrderTrigger 정합 확인(§3-R1 (a)) | **R0(P2-⑥) 완료** | R1 내부 add-only 병렬 가능 |
| **2** | **R2** | backend-core(consumer·api·persistence) ∥ frontend(화면 골격) | `/api/daytrade/paper/*` + 페이퍼 RiskConfig 프로필 + 가상 자본 배선 + 페이퍼 ledger(기존 재사용) + STRATEGY owner WS 연결 + Flyway V8x | **R1 머지** | R2 내부: api/consumer(backend-core) ∥ 화면 mock 골격(frontend) 병렬 |
| **3** | **R3** | frontend(승급 UI·페이퍼 대시보드) ∥ qa(Visual·E2E) ∥ strategy-engine(시나리오 테스트) | 승격 버튼 + 페이퍼 대시보드 + 모의 배지 UX + Playwright 시각 회귀 + 페이퍼 경로 e2e 테스트 | **R2 머지** | R3 내부 병렬 |

**의존 그래프**: `R0(P2-⑥, 타 세션)` → `R1` → `R2` → `R3`.

- **R2가 발굴 R2 의존?**: 발굴(daytrade-discovery-spec) R2(V75 시드·`/api/screener/daytrade-discover`)는 **이미 V75~V78로 라이브 적용 완료**(배경). 따라서 본 spec R2는 발굴 setup row를 read만 — 발굴 R2는 선완료 전제로 성립.

### 4-1. 단일 책임 정합 (development-rules §2·§3)
- R1=도메인+시그니처, R2=어댑터+영속, R3=UI+테스트 일괄 — API 빌딩 한 스텝씩(development-rules §3) 1:1. 4 layer 동시 변경 금지 LOCK.
- AI 호출 0 — 페이퍼는 룰 기반 시그널 경로 100%. LLM import 발견 시 reject (development-rules §5).

---

## §5. ADR 필요 판단 → **신규 ADR 0122 발행 (0117 amend 아님)**

판단 근거:
- ADR 0117은 "발굴 레이어 = 후보 제시(매매 결정권 0)"가 책임. 본 spec은 그 후보를 **자본 시뮬 경로(페이퍼 트레이딩)에 태우는 별도 자본 경로 모델** — 발굴의 "결정권 0" 책임과 다른 축(검증 실행 경로).
- 새 결정 누적 가치: (1) 발굴→페이퍼 승격은 **사용자 opt-in 게이트**(자동 진입 금지 정책) (2) 가상 자본 격리 모델 (3) 페이퍼 RiskConfig 프로필이 ADR 0121 사이징과 정합 (4) 페이퍼≠실거래 UX 구분 + mock 금지 정책과의 경계. → ADR 0117 amend로 묻기엔 결정 밀도가 충분.
- → **ADR 0122 신설** (본 spec 1:1). 관계: ADR 0117(발굴 — 출발점) · ADR 0121(사이징 정합 — 페이퍼 프로필 권위) · ADR 0067(RiskConfig·RiskGateway) · ADR 0068(TradingModeGate MOCK/REAL) · ADR 0110(전략별 포지션 귀속) · ADR 0115 P2-⑥(OrderTrigger 키 사슬 — 선행 의존).

---

## §6. UX 가드 — 페이퍼≠실거래 오인 방지 (LOCK)

- **명시 배지 의무**: 페이퍼 전략·포지션·체결·손익 표시 전부에 "모의(페이퍼) · 실거래 아님" 배지 + 구분 색상. 사용자가 실 계좌 화면과 1초도 혼동하지 않아야 한다.
- **격리 화면**: 페이퍼 대시보드는 실 포지션/계좌 화면과 **별도 진입점**. 합산 표시 금지(실 자산 + 가상 자본 합계 표시 금지).
- **mock 금지 정책과의 경계 명시 (§1-4)**: 페이퍼 데이터는 합성이 아니라 실 시세 기반 실제 시뮬 기록이다. "데이터 없음" 표시는 실 시세 결손 시에만 — 페이퍼 자체를 "mock"으로 표시하지 않는다("모의 매매"는 OK, "가짜 데이터" 표현 금지).
- **가상 자본 명시**: 페이퍼 잔액·손익은 항상 "가상 ₩" 단위 표기.

---

## §7. CHANGELOG 슬롯 (ADR 0050 정합)

라운드별 슬롯 row + anchor를 CHANGELOG.md 상단에 사전 prepend. 실제 구현 PR이 anchor를 채운다 (R1·R2·R3 + spec 슬롯).

---

## §8. 사용자 결정 필요 (임의 진행 금지 — 분리)

### 8-1. (중) 페이퍼 승격 opt-in UI 위치
발굴 결과 TOP N 화면의 후보 행(row)별 "모의로 굴려보기" 버튼 vs 후보 다중선택 후 일괄 승격 vs 발굴 setup 카드 상세 내. **architect 권장 = 후보 다중선택 + 일괄 승격**(발굴은 setup 단위라 종목 묶음 승격이 자연). 사용자 확정 필요.

### 8-2. (중) 가상 자본 default 금액
`MAGICJAR_DAYTRADE_PAPER_DEFAULT_CAPITAL_KRW` default. ADR 0121 사이징(positionPercent 10%·max 5종)을 의미있게 시뮬하려면 단타 1종목 진입 금액 × 5 이상이어야 함. **architect 제안 = 10,000,000원(1천만)** (defaultFor maxPositionAmount 1M × 10종 여유). 사용자 확정.

### 8-3. (중) RiskConfig 페이퍼 프로필 저장 방식
옵션 A(파생 userId 키 `<userId>:paper` 별 행) vs 옵션 B(`profile` 컬럼 add 복합키). **architect 권장 = 옵션 A**(스키마 add 최소). 사용자 확정.

### 8-4. (낮) 페이퍼 리스크 임계 — DLL·MDD·거래빈도
페이퍼 `dailyLossLimitPercent`·MDD 중단 임계·`maxTradesPerDay`(단타라 상향?). **architect 제안 = defaultFor 정합(DLL 3%·maxTradesPerDay 50)으로 1차, 단타 거래빈도 부족 시 R2에서 상향**. 사용자 의견 시 반영.

> 그 외 결정(경로 재사용·MOCK 강제·가상 자본 격리·UX 배지·R0 P2-⑥ 선행·ADR 0122 신설)은 기존 코드·ADR·발굴 LOCK 인용 기반 architect 자율 LOCK — 사용자 결정 불요.

---

## §9. fitness gap 점검 (자동매매 적합성 의심 lens — feedback_v3_skepticism)

- **자동매매에 필요한가?** ✅ 발굴 후보를 실거래 전에 페이퍼로 검증하는 것은 "백테스트 → 모의 → 실전" 단계적 확장(project_overview)의 **모의 단계** 그 자체. 백테스트(과거)·페이퍼(실시간 모의)·실전 3단 중 빠진 중간 고리를 채운다.
- **v1/v2 잔재 아닌가?** ✅ 아님. 신규 모듈 발명 0, 기존 signal→risk→order 경로 재사용. PoC 잔재 import 0.
- **과개발 risk?** ⚠ 가상 자본 ledger를 별 테이블로 만들면 과개발. → 기존 `order_request`(mode 컬럼)·`strategy_position`(전략별 귀속) 재사용으로 회피(§3-R2). 신규 테이블 0 LOCK.
- **발굴 LOCK 훼손?** ✅ 없음. 페이퍼 승격은 발굴 DTO가 아니라 사용자 opt-in 별 액션이 트리거(§1-1·§1-2). 발굴은 여전히 후보 출발점.
