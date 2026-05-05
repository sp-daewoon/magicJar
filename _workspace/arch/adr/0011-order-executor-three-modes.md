# ADR 0011: 주문 실행기 3-mode 구조와 안전 게이트

- 상태: Accepted
- 작성일: 2026-04-25
- 결정자: architect (협의: strategy-engine, backend-core)

## 맥락

동일한 전략을 (a) 과거 데이터로 **백테스트**하고 (b) KIS **모의계좌**에서 실시간 검증한 뒤 (c) **실계좌**에서 돌리는 3단계가 필요하다. 동일 코드 경로를 유지하되, "어디로 주문이 나가는가"만 스왑되어야 한다. 실계좌 직행은 금전 손실 위험이 커서 반드시 게이트가 필요하다.

## 결정

**단일 `OrderSender` 포트 + 3 구현체 + 안전 게이트.**

```
core 모듈:
  interface OrderSender { OrderResult send(Order order); }

order-exec 모듈:
  BacktestOrderSender  : 가상 체결 (슬리피지·수수료·세금 모델링, 메모리 포지션 북)
  MockOrderSender      : KIS 모의계좌 REST 호출
  RealOrderSender      : KIS 실계좌 REST 호출

결정 변수: magicjar.mode ∈ { BACKTEST, MOCK, REAL } + magicjar.allow-real=true 요구
```

**안전 게이트 (모든 모드에 적용, 실계좌는 더 엄격):**

1. **일일 최대 손실** 예) `magicjar.risk.daily-loss-pct=2.0` 도달 시 신규 매수 차단
2. **단일 종목 최대 비중** `magicjar.risk.single-position-pct=20.0`
3. **동일 종목 연속 매수 횟수** `magicjar.risk.consecutive-buy-limit=3`
4. **주문 금액 상한** 실계좌 전용: `magicjar.risk.max-order-krw=1000000`
5. **"실계좌 활성화" 이중 스위치**: `magicjar.mode=REAL` **AND** `magicjar.allow-real=true` **AND** 시작 시 경고 로그 + 사용자 확인 토큰(파일) 검증

게이트 검사는 `OrderSender` 구현체 바깥의 `OrderGuard` 데코레이터에서 수행(관심사 분리).

## 결과

- 긍정: 전략 코드는 모드에 의존하지 않음, 게이트는 단일 책임
- 부정: 게이트 조건이 시간 지남에 따라 복잡해질 수 있음 → `OrderGuardRule` 인터페이스로 확장 가능하게 설계
- 후속: strategy-engine이 `magicjar-backtest-engine` 스킬로 구현. 안전 게이트 단위 테스트 의무.
