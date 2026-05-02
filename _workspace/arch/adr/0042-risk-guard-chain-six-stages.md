# ADR 0042: Risk Guard chain 6단

- 상태: Accepted
- 작성일: 2026-05-03
- 결정자: architect (opus 4.7 1M) — 사용자 결정(2026-05-03 default 12 §9) 정합
- master §위치: `docs/architecture/master-architecture.md` v2 §C.13
- 트랙: T12 (W3, T1 트랙1 완료분 흡수)
- 선행: ADR 0032, 0033, 0034

---

## 1. 맥락

1번 트랙(KIS Order Adapter, 2026-05-02 완료)에서 Guard chain 3단(KillSwitch·DailyOrderLimit·PriceSanity)을 도입했다. v2에서는 시그널·주문 자동화가 본격화되면서 위험 관리가 핵심 — Position 한도·Drawdown 한도·MarketSession 등 추가 의무.

사용자 결정(default 12 §9): "1번 트랙 방식 따라 매우 느슨하게 시작 (PositionLimit 종목당 10%, DrawdownStop 일일 -3%)".

## 2. 결정

Guard chain 6단 + Spring `@Order` 우선순위:

| @Order | Guard | 책임 | 1차 default | reject 시 |
|---|---|---|---|---|
| 100 | **KillSwitchGuard** | 사용자 KillSwitch DB row | 기본 OFF (트랙1) | RejectReason.KillSwitchActive |
| 150 | **MarketSessionGuard** | 영업일 + 장중/장외 | session=Open 이외 reject (mock=bypass, dev=toggle) | MarketSessionClosed / NotBusinessDay |
| 200 | **DailyOrderLimitGuard** | 일별 주문 횟수 + 명목금액 | 종목당 50건/일, 전체 200건/일, 명목 1억/일 (매우 느슨) | DailyOrderLimitExceeded |
| 250 | **PositionLimitGuard** | 종목당 + 전체 포지션 비중 | 종목당 10%, 전체 80% (느슨) | PositionLimitExceeded |
| 280 | **DrawdownStopGuard** | 일일·주간·전체 drawdown | 일일 -3%, 주간 -7%, 전체 -15% (느슨) | DrawdownStopTriggered |
| 300 | **PriceSanityGuard** | tick stale + 호가 sanity + 주문가 oddprice | stale 10초 이상 reject + 호가 ±20% 이상 reject | PriceSanityFailed |

### 2-1. Guard 인터페이스

```kotlin
interface OrderGuard {
    val order: Int
    fun check(intent: OrderIntent, ctx: OrderContext): GuardResult
}

sealed class GuardResult {
    object Pass : GuardResult()
    data class Reject(val reason: RejectReason) : GuardResult()
}
```

체인 평가:

```kotlin
class GuardChain(private val guards: List<OrderGuard>) {
    fun check(intent: OrderIntent, ctx: OrderContext): GuardResult {
        guards.sortedBy { it.order }.forEach { guard ->
            val result = guard.check(intent, ctx)
            if (result is GuardResult.Reject) return result
        }
        return GuardResult.Pass
    }
}
```

### 2-2. 1차 default 매우 느슨 — 정상 매매 차단 0

| Guard | 한도 | 사유 |
|---|---|---|
| DailyOrderLimit | 50/종목/일·200/전체/일·1억/일 | 사용자 dogfooding 시 차단 0건 |
| PositionLimit | 종목당 10%, 전체 80% | 자산의 80%까지 운용 가능 (현금 비중 20% 보장) |
| DrawdownStop | 일일 -3%, 주간 -7%, 전체 -15% | 단기 변동에 자동 정지하지 않음, 큰 사고만 방지 |
| PriceSanity | stale 10초·호가 ±20% | 명백한 비정상만 차단 |

post-MVP: 사용자가 dogfooding 데이터 쌓이면 한도 점진 강화.

### 2-3. Position·Drawdown 계산

- Position: KIS WS H0STCNI0 체결통보 → consumer Position 갱신 + Redis `position:current:{userId}` (TTL 30초)
- Drawdown: `pnl_daily` 테이블 + `risk_event` 테이블 (V18). batch DailyDrawdownCheckJob (cron 09:00·12:00·15:30) 갱신.

### 2-4. 데이터 영향

| 테이블 | Flyway |
|---|---|
| `position` (현재 포지션) | V18 |
| `pnl_daily` (일별 손익) | V18 |
| `risk_event` (Guard reject 이력 + Drawdown 트리거 이력) | V18 |

Kafka `risk.event.v1` — Drawdown trigger·KillSwitch toggle·PositionLimit 도달 시 publish. STOMP `/topic/risk` fanout으로 사용자 알림.

## 3. 결과·영향

- T12·T14·T1(트랙1) 모두 본 ADR 직접 의존.
- 1차 매우 느슨 default — 실서비스 진입 시 ADR 추가 발행으로 한도 강화.
- 모든 Guard reject은 ADR 0034 정합 — orders + order_reject_audit INSERT.

## 4. 검토 대안

- **(a) Guard 4단 또는 5단** — Session 또는 Drawdown 부재 시 위험.
- **(b) 한도 처음부터 strict** — 정상 매매 차단 우려, dogfooding 막힘.
- **(c) 6단 + 매우 느슨 default + 점진 강화 (채택)** — 사용자 결정 정합.

## 5. References

- master-architecture.md §C.13, §3-1
- ADR 0032 (Market Session)
- ADR 0033 (PriceSanity의 stale 판정)
- ADR 0034 (REJECTED Audit)
- 트랙1 KIS Order Adapter (`_workspace/_archive/dogfooding/2026-05-02.md`)
