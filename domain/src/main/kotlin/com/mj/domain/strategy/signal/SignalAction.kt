package com.mj.domain.strategy.signal

/**
 * Signal Engine 출력 액션 (트랙11 R1, ADR 0041).
 *
 * 룰 매칭 only — AI 호출 0. DSL entry/exit 평가 결과를 BUY/SELL/HOLD로 분류한다.
 *
 * 우선순위:
 *   - exit=true → SELL (포지션 청산이 entry 진입보다 우선)
 *   - entry=true & exit=false → BUY
 *   - 그 외 → HOLD
 *
 * 강도 (strength): 0.0~1.0. R1은 단순 binary (true=1.0, false=0.0).
 * R2에서 multi-condition AND/OR 누적·가중치 도입 예정.
 */
sealed interface SignalAction {
    /** 액션 식별자 ("BUY" | "SELL" | "HOLD"). 직렬화/UI 표시용. */
    val name: String

    /**
     * 매수 신호 — entry 충족 + exit 불충족.
     *
     * @property strength 0.0 (정보용) ~ 1.0 (강한 매수). R1은 entry 충족 시 1.0.
     */
    data class Buy(
        val strength: Double = 1.0,
    ) : SignalAction {
        override val name: String = "BUY"

        init {
            require(strength in 0.0..1.0) { "BUY strength must be in [0.0, 1.0], got $strength" }
        }
    }

    /**
     * 매도 신호 — exit 충족.
     *
     * @property strength 0.0 ~ 1.0. R1은 exit 충족 시 1.0.
     */
    data class Sell(
        val strength: Double = 1.0,
    ) : SignalAction {
        override val name: String = "SELL"

        init {
            require(strength in 0.0..1.0) { "SELL strength must be in [0.0, 1.0], got $strength" }
        }
    }

    /**
     * 관망 — entry/exit 모두 불충족, 또는 데이터 부족.
     *
     * strength는 항상 0.0.
     */
    data object Hold : SignalAction {
        override val name: String = "HOLD"
    }
}

/**
 * 강도 추출 helper.
 */
fun SignalAction.strengthValue(): Double =
    when (this) {
        is SignalAction.Buy -> strength
        is SignalAction.Sell -> strength
        is SignalAction.Hold -> 0.0
    }
