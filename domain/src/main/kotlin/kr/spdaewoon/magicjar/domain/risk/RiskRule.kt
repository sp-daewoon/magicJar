package kr.spdaewoon.magicjar.domain.risk

/**
 * Risk 위반 룰 (ADR 0067 §5-2 RiskRule enum 7건).
 *
 * 평가 순서 (RiskGatewayEvaluator §7-2 short-circuit — 첫 BLOCKED 시 즉시 반환):
 *  1. [KILL_SWITCH_ON]              — KillSwitch ON 시 즉시 BLOCKED (ADR 0043 정합)
 *  2. [DAILY_LOSS_LIMIT]           — 일일 손실 한도 도달 (의제 B)
 *  3. [MAX_CONCURRENT_POSITIONS]   — 동시 보유 한도 (의제 C, BUY만)
 *  4. [TRADE_FREQUENCY_MINUTE]     — strategy당 분당 거래 한도 (의제 D)
 *  5. [TRADE_FREQUENCY_DAY]        — strategy당 일별 거래 한도 (의제 D)
 *  6. [POSITION_LIMIT]             — 단일 포지션 금액 한도 (의제 A, min(절대, %))
 *  - [USER_DISABLED]               — strategy.status=DISABLED 시 (별도 룰)
 *
 * spec 인용: ADR 0067 §5-2.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
enum class RiskRule {
    POSITION_LIMIT,
    DAILY_LOSS_LIMIT,
    MAX_CONCURRENT_POSITIONS,
    TRADE_FREQUENCY_MINUTE,
    TRADE_FREQUENCY_DAY,
    KILL_SWITCH_ON,
    USER_DISABLED,
    INVALID_SIGNAL_PRICE,
    SELL_WITHOUT_POSITION,
    INVALID_ORDER_INTENT,
    KIS_EXECUTION_SYNC_STALE,
}

/**
 * Risk verdict — ALLOWED · BLOCKED.
 *
 * spec 인용: ADR 0067 §5-2.
 */
enum class RiskVerdict {
    ALLOWED,
    BLOCKED,
}
