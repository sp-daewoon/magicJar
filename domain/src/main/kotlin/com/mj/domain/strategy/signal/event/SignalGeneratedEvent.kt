package com.mj.domain.strategy.signal.event

import java.time.Instant
import java.util.UUID

/**
 * Kafka 토픽 `signal.generated.v1` 메시지 스키마 (트랙11 R1, ADR 0041).
 *
 * 룰 매칭 only — AI 호출 0. SignalEngine.generate() 결과를 그대로 직렬화한다.
 *
 * camelCase 필드 + Type-Id 헤더 사용 (1번 트랙 Kafka 패턴 정합).
 *
 * @property eventId     UUID — 멱등 dedup key. 같은 시그널을 재발행할 일은 없지만 retry 등 안전망.
 * @property strategyId  발생시킨 전략 id (snake_case 권장)
 * @property symbol      종목코드 (예: "005930")
 * @property action      "BUY" | "SELL" | "HOLD"
 * @property strength    0.0~1.0 — 신호 강도. R1은 binary (1.0 또는 0.0).
 * @property generatedAt 시그널 생성 시각 (epoch UTC)
 * @property evaluatedAt 평가 기준 봉 openTime (시그널이 가리키는 시점)
 * @property reasons     사람용 이유 trace — "[idx=99] ma5=70.4 > ma20=68.2" 같은 description 리스트
 * @property userId      사용자 식별자 (옵션, 멀티테넌트 fanout용)
 */
data class SignalGeneratedEvent(
    val eventId: UUID,
    val strategyId: String,
    val symbol: String,
    val action: String,
    val strength: Double,
    val generatedAt: Instant,
    val evaluatedAt: Instant,
    val reasons: List<String>,
    val userId: String? = null,
) {
    init {
        require(strategyId.isNotBlank()) { "strategyId must not be blank" }
        require(symbol.isNotBlank()) { "symbol must not be blank" }
        require(action in ALLOWED_ACTIONS) { "action must be one of $ALLOWED_ACTIONS, got $action" }
        require(strength in 0.0..1.0) { "strength must be in [0.0, 1.0], got $strength" }
    }

    companion object {
        val ALLOWED_ACTIONS = setOf("BUY", "SELL", "HOLD")
    }
}
