package kr.spdaewoon.magicjar.domain.strategy

/**
 * DSL 전략 복잡도 — ADR 0047 §2-1-B 정합.
 *
 * KisYamlDslParser.validateStatic() 결과로 산출.
 * StrategyController.create 응답 + frontend ComplexityBadge 색상 표시용.
 *
 * @param astDepth AST 최대 depth (한도: 32 — MAGICJAR_DSL_AST_DEPTH_LIMIT)
 * @param nodeCount AST 총 노드 수 (한도: 10000 — MAGICJAR_DSL_NODE_COUNT_LIMIT)
 * @param indicatorCallCount 지표 호출 수 (한도: 50 — MAGICJAR_DSL_INDICATOR_CALL_LIMIT)
 * @param estimatedEvalTimeMs 예상 평가 시간 ms
 * @param complexityClass 복잡도 등급 (LOW·MEDIUM·HIGH·CRITICAL)
 */
data class StrategyComplexity(
    val astDepth: Int,
    val nodeCount: Int,
    val indicatorCallCount: Int,
    val estimatedEvalTimeMs: Int,
    val complexityClass: ComplexityClass,
)
