# ADR 0098: KillSwitchQueryPort + Strategy 활성화 게이트 (백테스트 통과 의무)

- 상태: **Accepted (2026-05-23 — architect 결재, 코덱스 협업 P0 4건 일괄 fix 사이클 PR-2)**
- 작성일: 2026-05-23
- 결정자: architect (자동매매 본질 결함 매트릭스 §3 자본 path LOCK 책임)
- 영역: `domain/order/execution` (port) + `consumer/risk` (RiskContext caller) + `api/strategy` (StrategyService 활성화 게이트)
- Amends: ADR 0043 §2-11 (KillSwitch race-safe — 본 ADR이 query path port 권위 본문화 + 활성화 게이트는 별도 결정)
- 관련 ADR: 0043 (KillSwitch 행동 정의 — 본 ADR이 query port + Strategy 활성화 게이트 추가) · 0024 (Risk Guard chain 6단 — 본 ADR이 RiskContext.killSwitchOn 결정 path 본문화) · 0056 (Backtest Engine — 활성화 게이트의 "통과 백테스트" 정의 source) · 0066 (T-SIG 실시간 시그널 엔진 — SignalGeneratedKafkaListener caller) · 0067 (T-RISK 리스크 관리 — Risk Gateway consumer 정합) · 0070 (T-RECON/T-NOTIFY — auto-PAUSE/auto-ON 정합)

## 1. 배경

### 1-1. 결함 1 — RiskContext.killSwitchOn 결정 source 분산

ADR 0024 RiskGuard 6단의 첫 번째 guard가 `killSwitchOn` 검사. 현 코드에서 결정 source가 분산:

- `SignalGeneratedKafkaListener`가 RiskContext 빌드 시 `killSwitchOn = ???`
- 일부 caller는 Redis 직접 lookup, 일부는 JPA `KillSwitchJpaRepository.findByUserId()` 직접 호출
- ADR 0043 §2-3 (baseline 자동 OFF 0 LOCK) + §2-11 (race-safe A+B+C) 정책의 query 시점 결합이 분산되면 정책 LOCK 검증이 어려움

### 1-2. 결함 2 — query 실패 시 fail-open default

dogfooding evidence: KillSwitchJpaRepository 호출 실패 (DB connection pool exhaust 1회 + Redis timeout 2회) 시 `killSwitchOn = false` default → **fail-open**으로 자본 path 진입 허용.

**자동매매 자본 보호 본질 위배** — KillSwitch 정책 본질은 "확실히 OFF임을 증명 못하면 ON 가정". fail-closed default=true가 본질.

### 1-3. 결함 3 — Strategy 활성화 시 백테스트 통과 검증 부재

현 코드에서 `StrategyService`가 PAUSED → ACTIVE 전환 시 검증 없음. 사용자가 신규 전략 등록 후 즉시 ACTIVE 진입 가능 → **백테스트 통과 없이 자본 path 진입 risk**.

자동매매 자본 보호 첫 단계는 "백테스트 통과 의무". ADR 0056 (Backtest Engine)가 이미 룩어헤드 가드 + 세·수수료·슬리피지 모델 + sandbox 운영 중이지만 **활성화 게이트 결합 0**.

코덱스 협업 cycle에서 4번째 P0 결함으로 식별 — 사용자 dogfooding 시 신규 전략 즉시 활성화 후 첫 시그널 발사 1건 발생 (체결 직전 사용자 수동 KillSwitch ON으로 차단됨).

---

## 2. 결정

### 2-1. 결정 1 — `KillSwitchQueryPort` 신규 + 단일 진입점

위치: `domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/order/execution/port/KillSwitchQueryPort.kt`

```kotlin
package kr.spdaewoon.magicjar.domain.order.execution.port

/**
 * KillSwitch 상태 조회 단일 진입점 port.
 *
 * - SignalGeneratedKafkaListener의 RiskContext.killSwitchOn 결정에 결합
 * - ADR 0024 RiskGuard 6단의 첫 번째 guard 검사 source
 * - 예외 시 fail-closed default=true (안전 우선) — 결정 2 본문화
 * - 구현은 api 모듈 adapter (KillSwitchQueryAdapter) — JPA + Redis cache lens
 */
interface KillSwitchQueryPort {
    /**
     * 사용자별 KillSwitch ON 여부 반환.
     *
     * @return true=ON(주문 차단) / false=OFF(주문 허용)
     * @throws 결코 throws 하지 않음 — 모든 예외는 adapter 내부에서 catch + ERROR log + true 반환
     */
    fun isKillSwitchOn(userId: Long): Boolean
}
```

### 2-2. 결정 2 — fail-closed default=true (안전 우선)

| 시나리오 | 반환값 | 근거 |
|----------|--------|------|
| 정상 query + KillSwitch ON | `true` | 본질 정상 |
| 정상 query + KillSwitch OFF | `false` | 본질 정상 |
| DB connection 실패 | **`true`** | fail-closed — 자본 보호 |
| Redis timeout | **`true`** | fail-closed |
| 미등록 사용자 | **`true`** | fail-closed (등록 절차 미완 = 안전 가정) |
| 알 수 없는 예외 | **`true`** | fail-closed + ERROR log + Slack alert (ADR 0044 reuse) |

adapter 내부 흐름:

```kotlin
override fun isKillSwitchOn(userId: Long): Boolean {
    return try {
        killSwitchJpaRepository.findByUserId(userId)
            ?.let { it.engagedAt != null }
            ?: true   // 미등록 → fail-closed
    } catch (e: Exception) {
        log.error("KillSwitchQuery failed userId={} — fail-closed", userId, e)
        true   // 모든 예외 → fail-closed
    }
}
```

### 2-3. 결정 3 — Strategy 활성화 게이트 (PAUSED → ACTIVE 백테스트 통과 의무)

#### 2-3-1. 신규 전략 기본 status=PAUSED

DB schema 정합 — `strategy.status` default value `PAUSED`. 사용자가 명시적으로 활성화 호출해야만 ACTIVE 전환.

#### 2-3-2. 활성화 검증 — `BacktestRunJpaRepository.existsAutoActivationReadyRun(strategyId, userId)`

- 신규 query method (BacktestRunJpaRepository 신규 메서드) — backend-core PR-3 dispatch
- "auto-activation-ready" 정의: `BacktestRun.status = COMPLETED` AND `BacktestRun.userId = userId` AND `BacktestRun.strategyId = strategyId` AND `BacktestRun.completedAt > now() - 30일` AND `BacktestRun.totalReturnPct > -50% (kill-loss threshold)` AND `BacktestRun.maxDrawdownPct < 80%` (사용자 dogfooding hyperparameter — 후속 backlog amend 후보)
- 통과 ≥ 1건 시 PAUSED → ACTIVE 허용
- 0건 시 `StrategyActivationNotReadyException` throw → 사용자에게 "백테스트 통과 후 활성화" 메시지

#### 2-3-3. `StrategyService.validateActivationReadiness()` 신규 메서드

위치: `api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/strategy/StrategyService.kt`

```kotlin
fun activate(strategyId: Long, userId: Long): StrategyResult {
    val strategy = findOrThrow(strategyId, userId)
    if (strategy.status != StrategyStatus.PAUSED) {
        throw StrategyAlreadyActiveException(strategyId)
    }
    validateActivationReadiness(strategyId, userId)   // 본 ADR §2-3-2
    return strategyJpaRepository.save(strategy.copy(status = StrategyStatus.ACTIVE))
}

private fun validateActivationReadiness(strategyId: Long, userId: Long) {
    val hasReadyRun = backtestRunJpaRepository.existsAutoActivationReadyRun(strategyId, userId)
    if (!hasReadyRun) {
        throw StrategyActivationNotReadyException(strategyId,
            "백테스트 통과 후 활성화 가능합니다. (조건: 최근 30일 + total return > -50% + max DD < 80%)")
    }
}
```

#### 2-3-4. 우회 path 0 LOCK

- DB 직접 UPDATE도 차단 (DBA가 dev 환경에서 status='ACTIVE' UPDATE 시 별도 audit_trail log 의무 — 운영 헌법 §9-4 backup 정합)
- API 우회 0 — 모든 활성화 path는 `StrategyService.activate()` 단일 진입점
- W4 AI 옵션 분기도 본 게이트 통과 의무 (ADR 0026 §AI 매매 결정권 0 정합)

### 2-4. 결정 매트릭스 요약

| 결정 | 본문 | LOCK 강도 |
|------|------|----------|
| 1 | KillSwitchQueryPort 단일 진입점 | ★★★ (RiskGuard 6단 source) |
| 2 | fail-closed default=true | ★★★ (자본 보호 본질) |
| 3-1 | 신규 전략 status=PAUSED default | ★★★ (자본 path 진입 차단) |
| 3-2 | 백테스트 통과 의무 | ★★★ (자본 보호 첫 단계) |
| 3-3 | StrategyService 단일 진입점 | ★★★ (우회 path 0) |
| 3-4 | 30일 + return -50% + DD 80% hyperparameter | ★ (dogfooding 후 amend) |

---

## 3. 트레이드오프 및 대안

### 3-1. KillSwitchQueryPort fail-closed vs fail-open

| 옵션 | 본문 | 평가 |
|------|------|------|
| **A. fail-closed default=true (본 ADR)** | 모든 예외 시 ON 가정 | ★★★ 자본 보호 + 정책 본질 |
| B. fail-open default=false | 예외 시 OFF 가정 + 자본 path 진입 | 자본 보호 본질 위배 + dogfooding 결함 cover 못함 |
| C. 예외 시 caller throws | RuntimeException → consumer crash | 시그널 dispatch loop 자체 차단 + 운영 안정성 위배 |

옵션 A 채택. 단점: KillSwitch 미발동 상태에서 일시 DB/Redis 결함 시 정상 시그널도 차단됨 → **수용**. 자본 보호가 본질, 가용성은 부차.

### 3-2. Strategy 활성화 게이트 — 백테스트 hyperparameter

| 옵션 | 본문 | 평가 |
|------|------|------|
| **A. 본 ADR (30일/-50%/80%)** | dogfooding hyperparameter baseline | ★★★ MVP LOCK |
| B. 사용자 설정 노출 | 사용자가 threshold 설정 | 본질 회피 — 사용자가 게이트 우회 risk |
| C. 통과 백테스트 ≥ 1건만 (return·DD 무관) | 최소 조건 | -100% 백테스트도 통과 허용 — 자본 보호 본질 위배 |
| D. 게이트 0 (현 baseline) | 검증 없음 | 결함 root cause 그대로 |

옵션 A 채택. hyperparameter는 v3.8.x dogfooding 누적 후 amend 후보 — backlog 등재.

### 3-3. 거부 검토 옵션

- **별도 ADR 분리 (KillSwitchQueryPort + Strategy 활성화 게이트 2건)** — 둘 다 "자본 path 진입 게이트" 본질 통합 → 단일 ADR
- **Redis cache TTL=30s 적용** — query 부담 ↓이나 fail-closed 정책 시 cache 정합 risk → MVP는 JPA 직접 + Redis cache는 amend 후보
- **활성화 시 백테스트 즉시 실행 자동 트리거** — 사용자 의도 외 백테스트 실행 + 자본 path 우회 risk → 사용자 명시 트리거만
- **Wave별 분기 (W3까지 게이트 0, W4 게이트 ON)** — 자본 보호 본질이 W1~W4 무관 → 즉시 LOCK

---

## 4. 결과 및 영향

### 4-1. 직접 영향 (PR-2 본 ADR)

- domain port 본문 LOCK + adapter 본문 LOCK (코드 0 touch — PR-3 신설)
- StrategyService 활성화 게이트 본문 LOCK (코드 0 touch — PR-3 신설)
- 자본 영향 0 (본 ADR PR)
- KIS endpoint 호출 0 (본 ADR PR)

### 4-2. 후속 영향 (PR-3 dispatch)

| 항목 | 본문 |
|------|------|
| 신규 파일 (domain) | `KillSwitchQueryPort.kt` |
| 신규 파일 (api) | `KillSwitchQueryAdapter.kt` |
| 신규 파일 (domain exception) | `StrategyActivationNotReadyException.kt` |
| 신규 BacktestRunJpaRepository 메서드 | `existsAutoActivationReadyRun(strategyId, userId): Boolean` |
| 수정 위치 | `SignalGeneratedKafkaListener` (RiskContext 빌드) + `StrategyService.activate()` + 활성화 controller endpoint error mapping |
| 단위 테스트 | adapter fail-closed 5 시나리오 + 활성화 게이트 통과/실패 4 시나리오 (R3 일괄) |
| 통합 검증 | dogfooding "신규 전략 즉시 활성화 시도 → 차단" + "KillSwitch DB 결함 race → fail-closed" |
| 관측성 | Micrometer counter `magicjar.killswitch.query.{success,failure,fail_closed}` + `magicjar.strategy.activation.{success,blocked}` |

### 4-3. 운영 ADR 양식 3 슬롯 (헌법 §9-5 정합)

| 슬롯 | 본문 |
|------|------|
| **SLO 영향** | S-1 (시그널→주문 P95<5s) 직접 영향 0 — query는 sub-ms. fail-closed 정책 시 일시적 시그널 차단률 증가 가능 (수용 baseline). |
| **rollback plan** | KillSwitchQueryPort revert 시 caller 분산 query 복귀 (이전 결함 재발) + Strategy 활성화 게이트 revert 시 사용자가 신규 전략 즉시 ACTIVE 가능. 둘 다 별 PR 분리 가능. |
| **관측 매트릭스** | (1) `magicjar.killswitch.query.success` rate ≈ 시그널 발사 rate (2) `magicjar.killswitch.query.failure` ≤ 1/일 (3) `magicjar.killswitch.query.fail_closed` 발생 시 즉시 Slack alert (4) `magicjar.strategy.activation.success` ≥ 1/일 (5) `magicjar.strategy.activation.blocked` 자연 발생 (사용자 dogfooding evidence) |

---

## 5. ADR cross-link

| ADR | cross 사유 |
|-----|-----------|
| 0043 (KillSwitch 행동 정의) | 본 ADR이 query path port 권위 본문화 — 결정 정책 본문은 0043 유지. amend mark — query 시점 port 단일 진입점 LOCK |
| 0024 (Risk Guard chain 6단) | 첫 번째 guard `killSwitchOn` 검사 source가 본 port |
| 0056 (Backtest Engine) | 활성화 게이트의 "통과 백테스트" 정의 source — BacktestRunJpaRepository.existsAutoActivationReadyRun 본 ADR 정합 |
| 0066 (T-SIG 실시간 시그널 엔진) | SignalGeneratedKafkaListener caller가 본 port 호출 |
| 0067 (T-RISK 리스크 관리) | Risk Gateway consumer 정합 — RiskContext 빌드 본 port 호출 |
| 0070 (T-RECON/T-NOTIFY) | auto-PAUSE/auto-ON 정합 — ADR 0043 §2-11 race-safe A+B+C 후속 트랙 |
| 0097 (ExecutionPositionUpdatePort) | 양 ADR 모두 자본 path 보호 본질 — 0097은 체결 후 position 갱신, 0098은 진입 전 게이트 검증 |

---

## 6. 변경 이력

| 일자 | 변경 |
|------|------|
| 2026-05-23 | 초안 + Accepted (architect, opus 4.7 1M) — 코덱스 협업 P0 4건 일괄 fix 사이클 PR-2 |
