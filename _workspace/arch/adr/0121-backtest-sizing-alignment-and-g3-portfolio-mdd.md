# ADR 0121 — 백테스트 사이징 정합(실전 RiskConfig) + G3 게이트 재정의(실전 사이징 기준 포트폴리오 MDD)

- **상태**: Proposed (2026-06-10)
- **작성**: architect
- **트랙**: T-BT(백테스트 사이징) + 단타 발굴 후속(ADR 0117) + 검증 게이트 정합
- **연관 ADR**: 0056(Backtest Engine + §2-7-A MDD MTM amend) · 0067(RiskConfig A-1/C-1) · 0111(scale-in/exit) · 0114(진입 정책) · 0117(단타 발굴 IS/OOS·민감도)
- **결재**: 최고 관리자 direction-guard 결재(2026-06-10, V77 재검증 #865 evidence) — 방향 LOCK. 본 ADR은 그 결정을 코드 계약으로 정밀화한다.
- **구현**: 본 ADR은 **문서·계약 only**. fractional sizing 구현은 strategy-engine(Wave 2), V78 재검증은 strategy-engine(Wave 3).

---

## §1. 문제 (Context — V77 #865가 실증한 측정-게이트 불일치)

`_workspace/strategy/2026-06-10-v77-preset-revalidation-report.md` §5-1·§6 실측:

1. **백테스트 사이징 = `SizingPolicy.AllCapital`(1포지션 = 보유 자본 100%)**. `JvmBacktestEngine.run`은 `request.universe`를 단일 cash 풀로 **순차 concat**(`for (code in universe) { for (t in candles) }`) 시뮬한다. 한 시점에 단 1종목만, 보유 현금 전액으로 진입한다(`executeBuy` L486 `cash.divide(executedPrice, FLOOR)`).
2. ADR 0056 §2-7-A amend(#862 MTM equity fix)로 MDD가 ≈100% 아티팩트에서 **클린 측정**으로 복원됨. 그 결과 6개 단타 프리셋 OOS 클린 MDD = **21.4%~41.7%**.
3. 게이트 G3는 `OOS maxDrawdown ≤ 8%`(`daytrade-preset-research.md` §게이트). 8%는 **분산 포트폴리오**(maxPositionPercent 10%·동시 5포지션) 자본보호 한도다.
4. **불일치**: 백테스트는 "몰빵(1포지션=100%) 누적 곡선" MDD를 만드는데, 게이트는 "분산 포트폴리오" MDD를 잰다. 몰빵 가정 MDD 35%를 분산 잣대 8%로 재면 **단타 프리셋 0 승격이 구조적·영구 강제**된다. 이건 프리셋 식의 문제가 아니라 **측정 모델 ↔ 게이트 기준의 정합 문제**다.

실전 자동매매(`RiskConfig.defaultFor`)는 분산이다 — `maxPositionPercent 10.00`·`maxPositionAmount 1,000,000`·`maxConcurrentPositions 5`. 백테스트가 이 분산을 반영하지 않으면 백테스트 MDD는 실전에서 실제로 겪을 낙폭을 과대 표현한다.

### 1-1. 정직성 — 현 엔진의 구조적 한계 (LOCK 인지)

엔진은 **단일 종목 단위 순차 시뮬**이지 진짜 멀티종목 동시 포트폴리오가 아니다. equity curve는 "80종목을 한 cash 풀로 차례로 돌린 누적 곡선"이다. 이 사실은 본 ADR이 손대지 **않는다**(과개발 경계). 본 ADR의 사이징 정합은 이 구조 위에서 **per-position 한도(positionPercent)를 진입 수량에 적용**하는 보수적 1차로 한정한다(§2-2).

---

## §2. 결정 (Decision)

### 2-A. 본선 — 백테스트 사이징을 실전 RiskConfig와 정합

`SizingPolicy`에 **`FixedFractional`을 1급 사이징 옵션으로 활성화**한다(기존 `sealed class SizingPolicy`에 이미 `FixedFractional(fraction)` 정의 존재 — post-MVP 표시였던 것을 실측 근거로 승격). `BacktestRequest`에 **add-only 필드**로 사이징 파라미터를 노출한다. **`AllCapital`은 default 유지** — 기존 모든 호출(smoke·기존 백테스트·UI)의 회귀 0 LOCK.

#### 계약 — `BacktestRequest` add-only (필드명·타입·default LOCK)

기존 `sizingPolicy: SizingPolicy = SizingPolicy.AllCapital` 필드를 **그대로 활용**한다. 추가 평면 필드는 **0개** — 사이징 파라미터는 `SizingPolicy.FixedFractional`의 생성자 인자로 전달한다(도메인 응집). 단, 멀티포지션 동시 보유 상한을 백테스트에 표현하기 위해 **`FixedFractional`을 확장**한다:

```kotlin
// domain/.../backtest/BacktestRequest.kt — SizingPolicy 확장 (add-only · AllCapital 무변경)
sealed class SizingPolicy {
    /** 전액 투입 — 1포지션 = 자본 100% (1차 default · 기존 호출 회귀 0) */
    object AllCapital : SizingPolicy()

    /**
     * 고정 비율 사이징 — 실전 RiskConfig 정합 (ADR 0121 §2-A).
     * @param positionPercent 1포지션당 가용 자본 비율 (%) — 실전 RiskConfig.maxPositionPercent 정합. 0.01~100.00.
     * @param maxConcurrent    동시 보유 가능 포지션 수 상한 — 실전 RiskConfig.maxConcurrentPositions 정합. 1~50.
     * @param maxPositionAmount 1포지션 절대 상한 (KRW) — 실전 RiskConfig.maxPositionAmount 정합. null=비활성. min(percent, amount) 적용(A-1 정합).
     */
    data class FixedFractional(
        val positionPercent: BigDecimal,
        val maxConcurrent: Int = 1,
        val maxPositionAmount: BigDecimal? = null,
    ) : SizingPolicy() {
        init {
            require(positionPercent in BigDecimal("0.01")..BigDecimal("100.00")) { "positionPercent 0.01~100.00" }
            require(maxConcurrent in 1..50) { "maxConcurrent 1~50" }
            maxPositionAmount?.let { require(it > BigDecimal.ZERO) { "maxPositionAmount must be positive" } }
        }
    }

    /** 켈리 — post-MVP 보류 유지 */
    data class Kelly(val winRate: BigDecimal, val payoff: BigDecimal) : SizingPolicy()
}
```

> **네이밍 결정**: 기존 `FixedFractional(fraction)`(0~1 비율)을 `positionPercent`(0~100 %) 시맨틱으로 통일 — RiskConfig.maxPositionPercent와 동일 단위. 기존 `FixedFractional(fraction)` 호출처는 **현재 0건**(post-MVP 미사용)이므로 시그니처 변경의 회귀 risk 0(strategy-engine grep 검증 의무).

#### 정합 매핑 (실전 ↔ 백테스트)

| 실전 `RiskConfig` | 백테스트 `FixedFractional` | 정합 근거 |
|---|---|---|
| `maxPositionPercent` (default 10.00) | `positionPercent` | A-1 — 자본 비율 한도 |
| `maxPositionAmount` (default 1,000,000) | `maxPositionAmount` | A-1 — min(percent, amount) |
| `maxConcurrentPositions` (default 5) | `maxConcurrent` | C-1 — 동시 보유 상한 |

#### V78 재검증 표준 사이징 프로파일 (LOCK)

V78은 `FixedFractional(positionPercent = 10.00, maxConcurrent = 5, maxPositionAmount = null)`로 고정한다. maxPositionAmount는 백테스트 초기자본 10,000,000 × 10% = 1,000,000이 default와 일치하므로 V78에서는 percent만으로 등가(amount=null로 단순화). 실전 정합 = "자본의 10%씩 최대 5종목 동시".

### 2-2. 멀티포지션 시뮬 범위 — 1차 보수적 LOCK (과개발 경계)

현 엔진은 **단일 종목 단위 순차 concat**이다. "진짜 멀티종목 동시 포트폴리오 시뮬"(전 universe를 시간축으로 merge해 동시에 5종목 보유)은 엔진 코어 재작성(시간축 머지·종목 간 cash 경합·동시 진입 우선순위)으로 ADR 0056 코어 LOCK을 깨고 risk가 크다. **1차는 도입하지 않는다.**

**1차 구현 범위 = "per-position 한도 사이징으로 MDD를 포트폴리오 환산"** (LOCK):

- 진입 수량 산식만 바꾼다: `AllCapital`은 `cash / price`(전액), `FixedFractional`은 **`min(initialCapital × positionPercent/100, maxPositionAmount ?? ∞) / price`**(고정 슬롯 크기). 즉 한 종목이 자본 전체를 먹지 않고 "1슬롯 = 자본의 10%"만 쓴다.
  - **이 슬롯 환산은 비-scale-in(`executeBuy`)과 scale-in(`evaluateScaleInExitSlice` 회차 budget) 두 경로 모두에 적용한다** (2026-06-10 hotfix amend — #871이 `executeBuy`만 환산하고 scale-in 회차 budget을 누락해 scale_in_config 전략에서 FixedFractional이 AllCapital과 비트 동일하게 무력화되던 V78 결함 정정). scale-in 회차 budget = `슬롯 base × weight%`(totalSlices=1·weight=100%이면 `executeBuy` 슬롯과 등가). 두 경로는 단일 `fractionalSlot` 함수를 공유한다.
- 이렇게 하면 단일 cash 풀 순차 곡선이라도 **1회 진입의 자본 노출이 1/10로 제한**되어, 보유 구간 미실현 낙폭이 자본 대비 1/10로 축소된 equity curve가 만들어진다. → **MDD가 분산 포트폴리오에 근사**한 값으로 측정된다.
- 슬롯 base는 **`initialCapital` 고정**(현재 cash 아님) — 복리 누적으로 슬롯이 부풀어 노출이 다시 100%로 수렴하는 것을 막는다(고정 슬롯 = 일정 비중 분산 근사). 잔여 cash 부족 시 가용 cash로 floor(진입 skip 아님).
- `maxConcurrent`는 1차에서 **MDD 환산용 메타데이터로만** 기록한다(현 엔진은 한 시점 1종목만 보유하므로 동시 5보유를 물리적으로 시뮬 못 함). 단, **결과 리포트에 `sizingPolicy` 전체를 echo**해 G3 판정 시 "10%/5슬롯 가정" 맥락을 명시한다. maxConcurrent의 실제 동시보유 시뮬은 **post-1차(별도 ADR)** 로 명시 격하.

> **정직 LOCK**: 1차 환산은 "1종목 10% 슬롯 순차"이지 "5종목 10% 동시"가 아니다. 후자는 종목 간 상관(동반 하락 시 합산 낙폭)을 반영하나 전자는 못 한다. 따라서 1차 G3 측정 MDD는 **실전 분산 MDD의 보수적(낮은) 근사**(상관=0 가정에 더 가까움)다. 이 한계를 §3 임계 설정과 V78 판정 규칙에 명시 반영한다.

### 2-3. 전파 — overfit / sensitivity 동일 옵션 자동 전파

`OverfitDetector.detect`는 `request.copy(from=…, to=…, outOfSampleSplit=null)`, `SensitivityAnalyzer.analyze`는 `request.base.copy(exitParamOverride=…, outOfSampleSplit=null)`로 IS/OOS·grid run을 만든다. **둘 다 `copy`를 쓰므로 `sizingPolicy` 필드는 자동 보존·전파**된다(시그니처 변경 0, 추가 작업 0). 검증 의무: strategy-engine은 `copy` 호출에서 `sizingPolicy`를 명시 reset하지 않음을 확인한다.

### 2-B. 보조 — G3 게이트 재정의 (실전 사이징 기준 포트폴리오 MDD)

**G3 신정의 (LOCK)**:

> **G3 (재정의)**: OOS `maxDrawdown ≤ 12%`, 단 **`SizingPolicy.FixedFractional(positionPercent=10, maxConcurrent=5)` 적용 백테스트의 MDD** 기준. `AllCapital`(몰빵) 곡선 MDD는 G3 판정에 **사용 금지**(이전 정의 무효).

#### 임계값 X = 12% — 근거

V77 실측: `AllCapital`(1포지션=100%) OOS 클린 MDD = 21.4%~41.7%(H1 35.07%). 사이징 환산 추정:

- **1차 보수 환산(상관=0 근사)**: 1슬롯 = 자본 10%로 진입하면 1회 보유의 자본 노출이 1/10. 단일 cash 풀 순차 곡선에서 누적 낙폭의 self-correlation(연속 손실 클러스터링·force-close)을 고려하면 MDD가 단순 1/10(3.5%)까지 내려가진 않고, 실측 35% × (대략 0.25~0.4 환산계수) ≈ **9%~14%** 범위로 추정한다(환산계수는 진입 빈도·연속 손실 클러스터·force-close 영향에 따라 변동 — V78 실측이 권위, 본 추정은 임계 설계용).
- **실전 분산 5종목 동시(상관 양수)**: 종목 동반 하락 시 합산 낙폭이 1슬롯보다 크므로 위 하한보다 높아질 수 있다 — 동시 5종목 × 10% = 자본 50% 노출 시 최악 시나리오는 단일 35%의 절반 수준. → **12% 안팎**이 실전 분산 MDD의 현실적 상한 근방.

**8% 유지 vs 12% 완화 판단**:
- 8% 유지: 사이징 정합 후에도 단타 프리셋이 통과 못 할 risk(1차 환산 하한이 9%이면 8%는 여전히 0 승격 강제). → 측정-게이트 불일치를 완전히 해소 못 함.
- 12% 채택(본 ADR): 실전 분산(10%/5슬롯) MDD의 현실적 범위 상단. **단타 = 변동성 수취 전략**이므로 분산해도 8% swing보다 큰 낙폭이 정상이며, per-trade 손절 1.2%가 별도 보호한다. 자본보호는 "1종목 몰빵 35%"가 아니라 "분산 12% 한도"로 집행하는 것이 실전 정합.
- 15~20% 완화는 거부 — 실전 RiskConfig 분산이 노출을 자본 50%로 제한하므로 그 이상의 낙폭은 전략 결함 신호로 본다(보수성 유지).

> **X=12%는 제안값**이다. V78 실측 사이징 환산 MDD가 임계 설계 추정(9~14%)을 크게 벗어나면(예: 환산 후에도 25%) 임계가 아니라 **전략·엔진 환산 로직을 의심**하고 본 ADR을 amend한다(임계를 사후에 맞추는 게이트 후퇴 금지 — `feedback_v3_skepticism`).

#### G3 외 게이트 영향

- G1·G2·G4·G6·G8: 사이징 무관(수익률 부호·거래수·PF·레짐 PF는 슬롯 크기에 비례 불변). **변경 0**.
- G5(plateau): TP/SL grid sweep은 `exitParamOverride`만 바꾸고 `sizingPolicy`는 고정 전파되므로 영향 0.
- G7(유동성·통과율): universe 필터 단계 — 사이징 무관. 변경 0.
- **하드 게이트 집합 무변경**: G1·G2·G3·G4·G5(G3만 측정 기준·임계 재정의).

---

## §3. V78 재검증 계획 + 판정 규칙 (LOCK)

### 3-1. 환경 (V77 정합 — 비교 가능성 보존)

V77 §1과 **동일** universe 80종목·IS 2021-01-01~2024-05-31·OOS 2024-06-01~2026-06-09·레짐 A/B 분할·비용 모델·초기자본 10,000,000. **유일 변경 = `sizingPolicy = FixedFractional(positionPercent=10.00, maxConcurrent=5, maxPositionAmount=null)`**. exit·진입 edge·DSL 전부 V77 그대로(사이징 격리 — 변화 원인이 사이징임을 명확히).

### 3-2. 대상

- **6종 재판정**: H1 GAP_AND_GO · H2 VOLUME_BREAKOUT · H6 RANGE_BREAKOUT · V75-1 · V75-2 · V75-3.
- 추가 가설은 본 wave에서 **신설 안 함**(사이징 정합 격리 — V77 6종 동일 비교가 핵심 evidence). 신규 가설은 별도 wave.

### 3-3. 판정 규칙 (LOCK)

각 프리셋에 **신 G3(FixedFractional 10%/5 기준 MDD ≤ 12%)** + 기존 하드 게이트(G1·G2·G4·G5) + 소프트(G6·G8) 적용:

1. **승격(promote / 유지)**: 하드 게이트 G1·G2·**G3(신)**·G4·G5 **전부 통과** → `is_active=TRUE`. H1이 통과하면 V77 보류했던 비활성을 **집행 안 함**(active 유지 확정). 신규 통과 프리셋은 INSERT 승격.
2. **비활성(deactivate)**: 신 G3 포함 하드 게이트 **하나라도 실패** → `is_active=FALSE` 집행. **H1이 신 G3(≤12%)에서도 미달하면 V77 보류 판정을 집행**(정직 LOCK — 사이징 정합 후에도 떨어지면 비활성). market_fit에 V78 수치·사유 명기.
3. **판정 보류(insufficient)**: G2 미달(거래<100) → "insufficient" 라벨, 변경 0(승격·비활성 둘 다 안 함).
4. **degradation 정의 불가**(IS totalReturn ≤ 0): G4 보류 처리(V77 §3 엔진 명시 정합) — 다른 하드 게이트로만 판정.

### 3-4. 산출물

- strategy-engine V78 리포트(`_workspace/strategy/2026-06-XX-v78-...md`): 6종 사이징 환산 MDD 실측 + 신 G3 판정표 + (필요 시) V77 마이그레이션. **마이그레이션은 승격/비활성 변경이 있을 때만** 발행(H1 active 유지 + 신규 0이면 마이그레이션 0).
- 임계 추정(§임계 9~14%) 검증: 실측 환산 MDD가 추정 범위면 X=12% 확정, 벗어나면 ADR 0121 amend 트리거.

---

## §4. 세션함수 DSL 확장 — 별도 spec 위임 (이번 wave 아님)

VWAP(당일 세션 누적)·ORB(시초 N분 range breakout)·time-of-day RVOL 3종은 `daytrade-preset-research.md` §2-3 "일봉 표현 불가" 목록이다. DSL 식별자·시그니처·평가 컨텍스트·데이터 요구·계산 위치 계약 초안은 **별도 spec** `_workspace/specs/session-function-dsl-spec.md`에 둔다(본 ADR §2 사이징과 독립 · R-later wave). 본 ADR은 그 존재만 명시하고 결정하지 않는다.

---

## §5. 대안 + 트레이드오프 (Why)

| 대안 | 채택? | 사유 |
|---|---|---|
| (A) 백테스트 사이징을 RiskConfig 정합 + G3 포트폴리오 MDD 재정의 | **채택(본선+보조)** | 측정 모델과 실전 자본 흐름을 일치 — 백테스트 MDD가 실전 낙폭을 표현. 근본 해소 |
| (B) G3를 per-trade 손실(stop 1.2%)/trade-level MDD로 재정의 | 거부 | per-trade 손절은 이미 진입 조건에 내장 — 게이트로 중복. 포트폴리오 누적 낙폭(연속 손실 클러스터)을 못 잡음. 자본보호 게이트 본질 상실 |
| (C) 단타 G3 한도를 15~20%로 완화(사이징 무변경) | 거부 | 측정 모델은 여전히 몰빵 100%. "몰빵 MDD를 느슨한 잣대로" — 실전 분산을 표현 못 함. 근본 미해소 + 임계만 후퇴(skepticism 위반) |
| (D) 진짜 멀티종목 동시 포트폴리오 시뮬(엔진 코어 재작성) | 거부(1차) | ADR 0056 코어 LOCK 위반 risk + 종목 간 상관·동시 진입 우선순위·cash 경합 복잡도. 과개발. post-1차 별도 ADR로 격하 |
| (E) maxConcurrent까지 실제 동시보유 시뮬 | 거부(1차) | 현 엔진 단일종목 순차 구조 — 동시보유는 (D)와 동급 재작성. 1차는 슬롯 크기 환산 + 메타 echo만 |

**채택 = A(사이징 정합) 본선 + B(G3 재정의) 보조**, 1차 구현은 보수적 슬롯 환산(§2-2). (D)(E)는 데이터·필요 입증 후 별도 ADR.

---

## §6. 자원 audit + 라운드 + CHANGELOG 슬롯

### 6-1. 자원 점유 audit (spec audit 의무 — ADR 0102)

| 자원 | 점유 | 비고 |
|---|---|---|
| **Flyway** | **신규 0건** | 사이징은 코드 계약(`SizingPolicy`/`BacktestRequest`). DB 스키마 무관. V78 마이그레이션은 승격/비활성 변경 시에만 strategy-engine이 발행(최신 = V76, V77 파일 부재 확인 — V77 미점유). 옆 세션 스코어카드 작업이 V77 점유 가능성 있으나 **사이징 쪽은 마이그레이션 불필요**이므로 충돌 0 |
| **REST** | **신규 0건** | `/api/backtest/run`·`/overfit`·`/sensitivity` 시그니처 무변경(`sizingPolicy`는 기존 `BacktestRequest` 필드 — 요청 DTO add-only일 뿐 엔드포인트 불변). UI는 default `AllCapital` 유지로 회귀 0 |
| **ENV** | **신규 0건** | 게이트 임계는 검증 리포트 상수 — ENV 게이트 불요 |
| **Kafka / STOMP** | **0건** | 백테스트는 동기 REST 경로 |
| **도메인** | `SizingPolicy.FixedFractional` 확장(add-only) + `BacktestRequest.sizingPolicy` 기존 필드 활용 | 헥사고날 — domain 모듈 내 변경, Spring/JPA import 0 |

### 6-2. 라운드 계획

- **Wave 2 (strategy-engine 구현)**: `SizingPolicy.FixedFractional` 확장 + `JvmBacktestEngine.executeBuy` 슬롯 크기 환산(initialCapital base 고정) + 결과 리포트 `sizingPolicy` echo. 테스트: AllCapital 회귀 0 + FixedFractional 슬롯 크기 단위 테스트.
- **Wave 3 (strategy-engine V78)**: §3 재검증 + 판정 집행 + (조건부)마이그레이션.

### 6-3. CHANGELOG 슬롯 사전 발행 (ADR 0050)

본 PR에서 CHANGELOG에 슬롯 row 사전 prepend(architect ADR + Wave 2/3 anchor). 구현 row는 strategy-engine이 구현 PR에서 채운다.

---

## §7. 영향 받는 SoT 갱신

- `_workspace/arch/adr/README.md` — 본 ADR 인덱스 추가(§ 백테스트/검증).
- `docs/architecture/master-architecture.md` §10 — 사이징 정합 + G3 재정의 참조(strategy-engine 구현 머지 시 동시 갱신 권장 — 본 ADR은 Proposed 단계라 참조만).
- `daytrade-preset-research.md` §게이트 G3 정의 — strategy-engine이 V78 리포트에서 신 G3로 갱신(본 ADR 권위 인용).
