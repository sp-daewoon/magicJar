# ADR 0023: Signal Engine = 룰 매칭 only (AI 호출 0)

- 상태: Accepted
- 작성일: 2026-05-03
- 결정자: architect (opus 4.7 1M)
- master §위치: `docs/architecture/master-architecture.md` v2 §C.11
- 트랙: T-SIG (Signal Engine, W3)
- 선행: ADR 0001 (시스템 비전), ADR 0022

---

## 1. 맥락

이전 architect 1차안의 ADR 0023 (Signal Engine)은 "DSL 평가 + AI 컨설트 분리"였다. 즉, AI 호출이 시그널 흐름의 정상 노드. 사용자(2026-05-02) 명시 위반:

> "신호에 의한이고 AI는 옵션이다 명심해야한다 ... AI가 그냥 이거 산다 만다의 결정권을 갖는 시스템이 아님"

Signal Engine은 **DSL 평가 결과만** — AI 호출 0. AI 컨설트는 별도 트랙(W4 T-AI) 사용자 트리거 코멘트.

## 2. 결정

### 2-1. 도메인 모델

```kotlin
// domain/.../signal/Signal.kt
data class Signal(
    val id: UUID,
    val instrumentCode: String,
    val action: SignalAction,
    val strength: Int,            // 0~100
    val reason: String,           // 룰 매칭 이유 (예: "MA(5) cross up MA(20) at 09:32")
    val strategyId: Long,
    val timeframe: String,        // "D1" · "5m" · "60m"
    val generatedAt: Instant,
    val expiresAt: Instant?,
)

enum class SignalAction { BUY, SELL, HOLD }

interface SignalGenerator {
    fun generate(strategy: StrategyDefinition, candles: List<Candle>, context: SignalContext): Signal?
}
```

### 2-2. 평가 흐름

```
KIS WS tick → 분봉 합성 → Candle 영속 → SignalGenerator.generate(activeStrategy, candles, ctx)
   ↓
StrategyEvaluator.evaluate(strategy, candles, ctx)
   ↓ (DSL 룰 매칭만, AI 호출 0)
entry.conditions 모두 충족 → Signal(BUY)
exit.conditions 충족 → Signal(SELL)
그 외 → Signal(HOLD) 또는 null
   ↓
Kafka `signal.generated.v1` publish
   ↓
api STOMP `/topic/signals` fanout → frontend Dashboard SignalsPanel
   ↓ (선택, 사용자 토글)
order.intent.v1 발행 → Risk Guard chain → KisOrderSender
```

**전 흐름에 LLM 호출 0건**.

### 2-3. 두 모드

| 모드 | 트리거 | 위치 |
|---|---|---|
| Realtime | tick 수신 시 | `consumer/.../application/signal/RealtimeSignalGenerator.kt` |
| Batch | T-SC screener 결과 후속 | `consumer/.../application/signal/BatchSignalGenerator.kt` |

### 2-4. AI 호출 차단 검증

- T-SIG 트랙 코드에서 AI provider import (Anthropic SDK·Spring AI ChatClient·Gemini SDK) 0건
- 트랙 R3 진입 시 architect 1회 grep 검증 의무
- W4 트랙 진입 전까지 동일 정책

### 2-5. 자동 주문 토글

사용자가 시그널을 받고 자동 주문을 보낼지 결정 (UI 토글):
- `user_trading_config.auto_order_from_signal = true|false`
- true 시 `signal.generated.v1` → consumer가 `order.intent.v1` 발행 (사용자 사전 한도 + sizing 적용)
- false 시 시그널만 표시 (사용자 수동 주문)

기본 false — 사용자 신중한 dogfooding 단계.

## 3. 결과·영향

- T-SIG·T-RISK 협업 — Risk Guard chain이 order.intent에 합류, 시그널 자체엔 영향 0.
- T-AI (AI Option Console)은 별도 흐름 — 사용자가 종목 차트 보면서 [AI 분석 요청] 버튼 클릭, AnthropicCommentAdapter 호출. 시그널·주문 흐름과 무관.
- AI 호출 audit는 W4의 `ai_comment_log`만 (V20).

## 4. 검토 대안

- **(a) 1차안 — DSL + AI 컨설트 시그널 보강** — 사용자 비전 위반.
- **(b) AI fully 배제** — W4 옵션 가치 X.
- **(c) Signal=룰 only + AI=별도 옵션 트랙 (채택)** — 사용자 비전 정합.

## 5. References

- master-architecture.md §C.11, §3-1
- ADR 0001 (비전 v2)
- ADR 0022 (DSL)
- ADR 0026 (AI 옵션 보조)
- KIS `core/signal.py` (참조용)
