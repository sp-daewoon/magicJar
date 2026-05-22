# ADR 0097: ExecutionPositionUpdatePort — 체결 직후 내부 position 갱신 도메인 port 신설

- 상태: **Accepted (2026-05-23 — architect 결재, 코덱스 협업 P0 4건 일괄 fix 사이클 PR-2)**
- 작성일: 2026-05-23
- 결정자: architect (자동매매 본질 결함 매트릭스 §3 자본 path LOCK 책임)
- 영역: `domain/order/execution` (port) + `consumer/order` (adapter)
- Amends: 없음 (신규 port)
- 관련 ADR: 0041 (Broker Reconciliation — KIS 잔고 ↔ 내부 position 정합, 본 ADR이 reconcile 사이클 내 "사이" 빈틈을 채움) · 0042 (Position Bootstrap & Recovery — WS 재연결 시 잔고 복구) · 0068 (T-O 주문 실행 — Risk ALLOWED → KIS REST + WS H0STCNI 체결 추적 + 3 테이블 영속) · 0084 (주문 path 안정성 P0 4건 — WS race silent skip 정합) · 0088 (persistence-shared — `PositionJpaRepository` 단일 권위)

## 1. 배경

### 1-1. 결함 root cause — 체결 후 다음 신호 진입까지의 "사이"

자동매매 자본 path는 다음 흐름:

```
KIS REST/WS 체결 → event_id dedup → ??? → (다음 시그널 진입) → RiskGuard 6단(현 position lens) → 주문 발사
```

`???` 구간에서 **내부 position이 최신이 아니다**. ADR 0041 `BalanceReconcileJob`(cron 5분 주기)은 최종 신뢰원이지만 **5분 간격의 reconcile은 자동매매의 다음 신호 진입 race를 cover하지 못한다**. 

구체적 race scenario:

1. T+0s: 매수 시그널 발사 → KIS REST 체결 → WS H0STCNI 체결통보 수신
2. T+1s: 다음 매수 시그널 진입 (동일 종목 또는 자본 한도 영향)
3. T+3s: RiskGuard 6단 — `PositionPort.findByUserAndSymbol()` 호출
4. **결함**: 1번 체결이 PositionJpaRepository.upsert 호출 안 됨 → 자본/수량 한도 우회 가능

ADR 0041 reconcile cron이 T+5min에 보정하지만, **자본 보호 본질은 "다음 신호 진입 전" upsert**가 필요. 

### 1-2. WS + REST backfill 양 경로 일관성 의무

ADR 0068 §체결 추적 본문에서 KIS 체결은 두 path:

- **WS H0STCNI0/H0STCNI9** (실시간 체결통보) — 1차 신뢰원
- **REST `/uapi/domestic-stock/v1/trading/inquire-ccnl`** (체결 조회) — WS miss·재연결·시간외 갭 보정 backfill

두 path 모두 `OrderExecutionJpaEntity`에 영속하지만 **position upsert는 별도**. event_id dedup 직후 단일 port로 호출해야 양 path 일관성 LOCK.

### 1-3. v3.7.x dogfooding 결함 evidence

코덱스 협업 cycle에서 발견된 자본 path P0 4건 중 본 ADR 영역:

- 매수 후 즉시 다음 매수 시그널 진입 시 자본 한도 우회 사례 (사용자 dogfooding 2회 재현)
- WS H0STCNI 수신 → OrderExecutionJpaEntity insert는 됐으나 PositionJpaRepository upsert 호출 0건 (consumer 로그 grep evidence)
- REST backfill (`KisExecutionBackfillService`)도 동일 — execution insert만 + position upsert 0

→ 본 ADR은 이 빈틈의 도메인 port 신설 LOCK + adapter 구현 책임은 PR-3 후속 dispatch.

---

## 2. 결정

### 2-1. 도메인 port 신설 — `ExecutionPositionUpdatePort`

위치: `domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/order/execution/port/ExecutionPositionUpdatePort.kt`

```kotlin
package kr.spdaewoon.magicjar.domain.order.execution.port

import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution

/**
 * 체결 직후 내부 position을 upsert하는 port.
 *
 * - WS H0STCNI 체결통보 수신 직후 호출 (1차 path)
 * - REST inquire-ccnl backfill 직후 호출 (2차 path)
 * - event_id dedup 직후 단일 port로 호출 → 양 path 일관성 LOCK
 * - reconcile job(ADR 0041)은 최종 신뢰원이지만 5분 cron 간격의 race 빈틈을 본 port가 cover
 *
 * 구현은 consumer 모듈 adapter (ConsumerExecutionPositionUpdateAdapter) —
 * PositionJpaRepository.upsertReconciled로 BUY 가중평균/SELL 차감(overflow 경고) 처리.
 */
interface ExecutionPositionUpdatePort {
    fun upsertFromExecution(execution: OrderExecution)
}
```

설계 선택:

| 항목 | 결정 | 사유 |
|------|------|------|
| 위치 | `domain/order/execution/port` | port는 domain 모듈 (헥사고날 — code-convention §3.1) |
| 단일 메서드 | `upsertFromExecution(OrderExecution)` | event_id dedup이 보장된 OrderExecution 도메인 객체만 받음 |
| 반환값 | `Unit` | upsert 결과는 caller가 관여 안 함 (다음 시그널 RiskGuard가 lens 확인) |
| 예외 처리 | port 인터페이스는 throws 명시 0 (adapter 측에서 결정) | domain 순수성 + 의무적 graceful 처리 |
| async 여부 | 동기 호출 (caller가 worker thread에서 호출) | reconcile race 본질 — 즉시 upsert가 본질 |

### 2-2. adapter 구현 — `ConsumerExecutionPositionUpdateAdapter`

위치: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/order/adapter/ConsumerExecutionPositionUpdateAdapter.kt`

책임:

- `PositionJpaRepository.upsertReconciled(userId, symbol, side, qty, price)` 호출 (이미 ADR 0088 persistence-shared 모듈에 존재)
- **BUY**: 기존 position 가중평균 → `new_avg = (old_qty * old_avg + new_qty * new_price) / (old_qty + new_qty)`
- **SELL**: `new_qty = old_qty - sold_qty`, 0 이하면 0 clamp + WARN log (overflow 경고 — KIS 데이터 미수신 또는 reconcile race)
- 실패 시 ERROR log + Micrometer counter `magicjar.execution.position.update.failure` (관측성 LOCK) + reconcile cron이 cover

상세 구현은 **PR-3 자동매매 본질 사이클**에서 흡수 (본 ADR-2는 ADR 본문만, 코드 0 touch).

### 2-3. 호출 위치 — event_id dedup 직후 단일 진입점

| caller | 호출 위치 | 호출 직전 의무 |
|--------|----------|---------------|
| WS H0STCNI consumer | `OrderExecutionWebSocketHandler` 또는 후속 dispatch listener | event_id UQ 검증 후 즉시 |
| REST backfill | `KisExecutionBackfillService.backfillExecutions()` | event_id UQ 검증 후 즉시 |

→ 두 path 모두 `OrderExecutionJpaRepository.save()` 직후 `executionPositionUpdatePort.upsertFromExecution(execution)` 단일 호출. 

---

## 3. 트레이드오프 및 대안

### 3-1. 검토한 4 옵션

| 옵션 | 본문 | 평가 | 채택 |
|------|------|------|------|
| **A. 본 ADR (domain port 신설)** | event_id dedup 직후 도메인 port 단일 호출 | ★★★ 자본 path LOCK + domain 순수 + 양 path 일관성 | **채택** |
| B. JPA repository 직접 호출 | consumer가 `PositionJpaRepository.upsertReconciled()` 직접 호출 | domain 순수 위배 + repository 호출 위치 분산 risk | 폐기 |
| C. domain service 신설 | `ExecutionPositionUpdateService` domain service + port 0 | domain service는 외부 의존 0이어야 하나 repository 호출 필연 | 폐기 |
| D. reconcile job 주기 단축 (5분 → 30초) | ADR 0041 cron 단축 | KIS API 호출 부담 ↑ + 본질 race window 0이 아님 (cover 못함) | 폐기 |

옵션 A 채택 결정 사유:
- ADR 0021 (Indicator Source 추상화) 패턴 1:1 — port 신설 → adapter 구현 → caller 단일 진입점
- ADR 0088 persistence-shared 모듈이 이미 `PositionJpaRepository.upsertReconciled` 보유 → adapter는 thin wrapper

### 3-2. 거부 검토 옵션

- **즉시 동기 호출만 + async 옵션 0** 채택 — reconcile race 본질이 "즉시"이므로 async 큐는 본 결함 cover 안 함
- **port에 throws 명시 0** — domain 순수성 (Spring 예외 import 금지)
- **upsert 결과 boolean 반환** 거부 — caller가 결과 분기 0이 본질 (다음 RiskGuard가 lens 확인)
- **adapter 1 모듈 (api) 채택** 거부 — WS path는 consumer 단독 (api는 WS subscribe 0) → adapter는 consumer 모듈

---

## 4. 결과 및 영향

### 4-1. 직접 영향 (PR-2 본 ADR)

- domain port 본문 LOCK (코드 0 touch — PR-3에서 신설)
- consumer adapter 본문 LOCK (코드 0 touch — PR-3에서 신설)
- 호출 위치 LOCK (event_id dedup 직후 단일 진입점)
- 자본 영향 0 (본 ADR PR)
- KIS endpoint 호출 0 (본 ADR PR)

### 4-2. 후속 영향 (PR-3 dispatch)

| 항목 | 본문 |
|------|------|
| 신규 파일 | `domain/.../port/ExecutionPositionUpdatePort.kt` + `consumer/.../adapter/ConsumerExecutionPositionUpdateAdapter.kt` |
| 호출 추가 위치 | WS H0STCNI listener + `KisExecutionBackfillService` 2건 |
| 단위 테스트 | adapter 단위 BUY 가중평균·SELL overflow 경고 시나리오 (R3 일괄 — code-convention §25 정합) |
| 통합 검증 | dogfooding "매수 → 즉시 다음 매수 시그널 진입" race 0 재현 (사용자 결재 의무) |
| 관측성 | Micrometer counter `magicjar.execution.position.update.{success,failure}` + WARN log overflow |

### 4-3. 운영 ADR 양식 3 슬롯 (헌법 §9-5 정합)

| 슬롯 | 본문 |
|------|------|
| **SLO 영향** | S-1 (시그널→주문 P95<5s) 직접 영향 0 — 본 port 호출은 micro-second 단위. 단, race 차단으로 자본 한도 우회 risk 0 → 간접 SLO 향상. |
| **rollback plan** | port adapter 1건 disable → reconcile cron 5분이 cover (자본 위험 노출 5분). 단, "다음 신호 진입 race" 결함 재발 LOCK. PR 단일 revert + 후속 reconcile cron 주기 단축 amend 후보. |
| **관측 매트릭스** | (1) `magicjar.execution.position.update.success` rate ≈ KIS 체결 수신 rate (2) `magicjar.execution.position.update.failure` ≤ 1/일 (3) overflow WARN ≤ 5/일 (KIS WS miss 발생 시 일시 증가 정상) (4) reconcile drift count 감소 추이 (5) qa Visual 회귀 시나리오 "매수 → 즉시 매수" |

---

## 5. ADR cross-link

| ADR | cross 사유 |
|-----|-----------|
| 0041 (Broker Reconciliation) | 본 ADR이 reconcile 5분 cron의 "사이" 빈틈을 cover. reconcile은 최종 신뢰원 LOCK 유지 |
| 0042 (Position Bootstrap & Recovery) | WS 재연결 시 잔고 복구와 본 ADR upsert 정합 — bootstrap 후 첫 체결도 본 port 호출 |
| 0068 (T-O 주문 실행) | 체결 추적 3 테이블 (order_request + order_execution + position) 중 position upsert 단일 권위 |
| 0084 (주문 path 안정성 P0 4건) | WS race silent skip 결함 정합 (P0-3 #4) — 본 ADR이 race 정합 후 position upsert까지 LOCK |
| 0088 (persistence-shared) | `PositionJpaRepository.upsertReconciled` 단일 권위 → 본 adapter가 thin wrapper |
| 0099 (Reactor `.block()` non-blocking 계약) | KisExecutionBackfillService caller가 본 ADR adapter 호출 시 reactor block 경로 정합 LOCK |

---

## 6. 변경 이력

| 일자 | 변경 |
|------|------|
| 2026-05-23 | 초안 + Accepted (architect, opus 4.7 1M) — 코덱스 협업 P0 4건 일괄 fix 사이클 PR-2 |
