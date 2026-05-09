package kr.spdaewoon.magicjar.domain.strategy

/**
 * DSL 전략 예외 sealed class — ADR 0047 §2-1 정합.
 *
 * HTTP 에러 매핑:
 * - DSL 파싱·검증 실패 → 400 Bad Request
 * - StrategyTimeoutException → 502/504 (auto-disable 후 전략 비활성)
 * - InsufficientCandlesException → 400 Bad Request
 */
sealed class StrategyException(
    message: String,
) : RuntimeException(message) {
    /** DSL 텍스트 문법 오류 */
    class DslSyntaxException(
        detail: String,
    ) : StrategyException("DSL syntax error: $detail")

    /** AST depth 한도 초과 (MAGICJAR_DSL_AST_DEPTH_LIMIT=32) — ADR 0047 §2-1-1 */
    class DslDepthExceededException(
        depth: Int,
        limit: Int = 32,
    ) : StrategyException("AST depth $depth > limit $limit")

    /** AST 노드 수 한도 초과 (MAGICJAR_DSL_NODE_COUNT_LIMIT=10000) — ADR 0047 §2-1-2 */
    class DslNodeCountExceededException(
        count: Int,
        limit: Int = 10000,
    ) : StrategyException("AST node count $count > limit $limit")

    /** 지표 호출 수 한도 초과 (MAGICJAR_DSL_INDICATOR_CALL_LIMIT=50) — ADR 0047 §2-1-3 */
    class DslIndicatorCallExceededException(
        count: Int,
        limit: Int = 50,
    ) : StrategyException("Indicator calls $count > limit $limit")

    /** 화이트리스트 미등록 DSL 식별자 — ADR 0047 §2-1-A */
    class UnknownDslSymbolException(
        symbol: String,
    ) : StrategyException("Unknown DSL identifier: $symbol")

    /** T-PAT 미진입 시 pattern() 호출 — T-PAT 머지 후 제거 */
    class UnknownDslPatternException(
        pattern: String,
    ) : StrategyException("Unknown DSL pattern: $pattern (T-PAT not yet)")

    /** 평가 timeout (100ms 초과) — ADR 0047 §2-2-A */
    class StrategyTimeoutException(
        strategyId: Long,
        timeoutMs: Long,
    ) : StrategyException("Strategy $strategyId timeout ${timeoutMs}ms")

    /** 평가에 필요한 캔들 수 부족 */
    class InsufficientCandlesException(
        required: Int,
        actual: Int,
    ) : StrategyException("Insufficient candles: required=$required actual=$actual")
}
