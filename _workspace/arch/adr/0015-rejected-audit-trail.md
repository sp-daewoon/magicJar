# ADR 0015: REJECTED Audit Trail (Guard reject·KisApiException 모두 orders INSERT)

- 상태: Accepted
- 작성일: 2026-05-03
- 결정자: architect (opus 4.7 1M) — 사용자 dogfooding 가시성 확보 정합
- master §위치: `docs/architecture/master-architecture.md` v2 §C.13·§3-1
- 트랙: T-W2 (KIS REJECTED Audit, W1)
- 선행: ADR 0001, 0032

---

## 1. 맥락

1번 트랙 dogfooding(2026-05-02)에서 발견:

- 사용자가 매수 시도 → MarketSessionGuard reject(영업일 외) → 사용자가 "주문이 갔는지 어디서 확인하지?" 질문.
- KIS API 호출 → `EGW00202 GW 라우팅 오류` → orders 테이블에 INSERT 0건 (실패한 호출만 로그). 사용자가 매매일지에서 거부 사유 추적 불가.

기존 정책: Guard reject + KisApiException 시 orders INSERT 안 함, 로그만. **dogfooding 가시성 부족**.

## 2. 결정

옵션 C 채택 — **Guard reject·KisApiException 모두 `orders` 테이블에 INSERT** + `order_events`에 immutable audit row 추가.

### 2-1. 데이터 모델

```sql
-- orders 테이블 기존 컬럼 + 추가
ALTER TABLE orders ADD COLUMN reject_reason VARCHAR(64);  -- ENUM-like (master §RejectReason)
ALTER TABLE orders ADD COLUMN reject_detail TEXT;          -- 상세 메시지 + KIS rt_cd
ALTER TABLE orders ADD COLUMN reject_source VARCHAR(32);   -- "GUARD" | "KIS_API" | "INTERNAL"

-- order_reject_audit (V11 — append-only)
CREATE TABLE order_reject_audit (
    id BIGSERIAL PRIMARY KEY,
    intent_id UUID NOT NULL,
    user_id BIGINT NOT NULL,
    code VARCHAR(8) NOT NULL,
    side VARCHAR(8) NOT NULL,
    reject_source VARCHAR(32) NOT NULL,
    reject_reason VARCHAR(64) NOT NULL,
    reject_detail TEXT,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reject_user_time ON order_reject_audit(user_id, occurred_at DESC);
```

### 2-2. RejectReason sealed class (domain)

```kotlin
sealed class RejectReason(val code: String) {
    object KillSwitchActive : RejectReason("KILL_SWITCH")
    object MarketSessionClosed : RejectReason("SESSION_CLOSED")
    object MarketNotBusinessDay : RejectReason("NOT_BUSINESS_DAY")
    object DailyOrderLimitExceeded : RejectReason("DAILY_LIMIT")
    object PositionLimitExceeded : RejectReason("POSITION_LIMIT")
    object DrawdownStopTriggered : RejectReason("DRAWDOWN_STOP")
    object PriceSanityFailed : RejectReason("PRICE_SANITY")
    data class KisApiError(val rtCd: String, val msg: String) : RejectReason("KIS_API:${rtCd}")
    data class Internal(val message: String) : RejectReason("INTERNAL")
}
```

### 2-3. 발행 흐름

1. Guard chain 통과 실패 → `OrderSender.placeOrder()` 진입 전 `orders` REJECTED row INSERT + `order_reject_audit` row + Kafka `order.rejected.v1` publish + STOMP `/topic/orders` fanout.
2. `KisOrderSender` REST 호출 → KIS rt_cd != "0" (`EGW00202` 등) → 동일 흐름.
3. frontend `/orders` 페이지에서 REJECTED 컬럼 + 사유 + 시각 표시.

### 2-4. 멱등성

- intent_id UNIQUE — 같은 intent의 중복 reject INSERT 차단.
- KIS API 응답이 늦게 도착해 이미 PENDING orders가 존재하는 경우, KIS rt_cd != "0" 시점에 status를 REJECTED로 업데이트 + audit append (orders 자체는 update, audit는 append).

## 3. 결과·영향

- 사용자 dogfooding 시 모든 거부 사유 매매일지·`/orders` 페이지에서 가시. EGW 라우팅 오류·세션 차단·한도 초과 모두 흔적.
- **storage 부담** — 거부 sweep이 늘 수 있음. retention 정책: orders 자체는 영구, order_reject_audit 90일.
- **테스트 부담** — Guard chain 6단 + KIS adapter 의 모든 reject 경로에 audit 의무. R3 일괄 테스트에서 N건 시나리오로 검증 (master 룰북 §4).

## 4. 검토 대안

- **(a) 로그만** (1차안) — 사용자 가시성 부족.
- **(b) Guard reject만 INSERT** — KisApiException 추적 불가, EGW 디버깅 어려움.
- **(c) 모두 INSERT + audit row (채택)** — 풀 가시성. 사용자 dogfooding 정합.

## 5. References

- master-architecture.md §C.13, §3-1, §6-2
- ADR 0024 (Risk Guard chain 6단)
- 관련 트랙: T-W2 (KIS REJECTED Audit, W1) — tracks-roadmap.md §2
