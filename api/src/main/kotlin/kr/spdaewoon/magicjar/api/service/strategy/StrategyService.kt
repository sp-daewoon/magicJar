package kr.spdaewoon.magicjar.api.service.strategy

import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.Signal
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import kr.spdaewoon.magicjar.domain.strategy.Universe
import kr.spdaewoon.magicjar.domain.strategy.dsl.CompositeCondition
import kr.spdaewoon.magicjar.domain.strategy.dsl.Condition
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.dsl.IndicatorRef
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyRepositoryPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * 전략 서비스 — 등록·검증·조회·auto-disable.
 *
 * - 등록 시: DSL 파싱 → static 검증 → complexity 계산 → DB persist
 * - auto-disable: StrategyJpaAdapter.autoDisable() 위임
 *
 * spec §6-1 호출 시퀀스 LOCK.
 * ADR 0047 §2-1-B complexity 계산 + §2-2-D auto-disable 정합.
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Service
class StrategyService(
    private val strategyRepositoryPort: StrategyRepositoryPort,
    @Value("\${magicjar.dsl.ast-depth-limit:32}") private val astDepthLimit: Int,
    @Value("\${magicjar.dsl.node-count-limit:10000}") private val nodeCountLimit: Int,
    @Value("\${magicjar.dsl.indicator-call-limit:50}") private val indicatorCallLimit: Int,
) {
    private val log = LoggerFactory.getLogger(StrategyService::class.java)

    /**
     * DSL 텍스트 → static 검증 → complexity 계산.
     * ADR 0047 §2-1 정합.
     *
     * @throws StrategyException.DslDepthExceededException depth 한도 초과
     * @throws StrategyException.DslNodeCountExceededException 노드 수 한도 초과
     * @throws StrategyException.DslIndicatorCallExceededException 지표 호출 수 한도 초과
     */
    fun validateAndComputeComplexity(ast: DslNode): StrategyComplexity {
        val stats = collectStats(ast, depth = 1)
        if (stats.maxDepth > astDepthLimit) {
            throw StrategyException.DslDepthExceededException(stats.maxDepth, astDepthLimit)
        }
        if (stats.nodeCount > nodeCountLimit) {
            throw StrategyException.DslNodeCountExceededException(stats.nodeCount, nodeCountLimit)
        }
        if (stats.indicatorCallCount > indicatorCallLimit) {
            throw StrategyException.DslIndicatorCallExceededException(stats.indicatorCallCount, indicatorCallLimit)
        }
        val complexityClass = calcComplexityClass(stats)
        val estimatedMs = estimateEvalTimeMs(stats)
        return StrategyComplexity(
            astDepth = stats.maxDepth,
            nodeCount = stats.nodeCount,
            indicatorCallCount = stats.indicatorCallCount,
            estimatedEvalTimeMs = estimatedMs,
            complexityClass = complexityClass,
        )
    }

    /**
     * 전략 등록 — AST + complexity + definition → DB persist.
     */
    fun create(
        userId: Long,
        name: String,
        nameKo: String?,
        entryAst: DslNode,
        exitAst: DslNode?,
        universe: Universe,
        tf: String,
        entryText: String,
        exitText: String?,
    ): Strategy {
        val entryComplexity = validateAndComputeComplexity(entryAst)
        val exitComplexity = exitAst?.let { validateAndComputeComplexity(it) }
        val combinedComplexity = mergeComplexity(entryComplexity, exitComplexity)

        val dsl =
            DslStrategyDefinition(
                name = name,
                nameKo = nameKo ?: "",
                buyCondition = entryAst,
                sellCondition = exitAst,
            )
        val definition =
            StrategyDefinition(
                dsl = dsl,
                universe = universe,
                tf = tf,
            )
        val strategy =
            Strategy(
                userId = userId,
                definition = definition,
                complexity = combinedComplexity,
            )
        val saved = strategyRepositoryPort.save(strategy)
        log.info("전략 등록 완료: id={} userId={} complexity={}", saved.id, userId, combinedComplexity.complexityClass)
        return saved
    }

    fun findById(id: Long): Strategy? = strategyRepositoryPort.findById(id)

    fun findAllByUserId(userId: Long): List<Strategy> = strategyRepositoryPort.findAllByUserId(userId)

    fun deleteById(id: Long) {
        strategyRepositoryPort.deleteById(id)
        log.info("전략 삭제: id={}", id)
    }

    /**
     * auto-disable — ADR 0047 §2-2-D.
     * JvmStrategyEvaluator timeout 발생 시 consumer에서 호출.
     */
    fun autoDisable(
        strategyId: Long,
        reason: StrategyDisabledReason,
    ) {
        strategyRepositoryPort.autoDisable(strategyId, reason)
        log.warn("전략 auto-disable: id={} reason={}", strategyId, reason)
    }

    /**
     * 전략 재활성화 — 사용자 명시 활성화.
     */
    fun enable(strategyId: Long) {
        strategyRepositoryPort.enable(strategyId)
        log.info("전략 재활성화: id={}", strategyId)
    }

    // --- private helper ---

    private data class AstStats(
        val maxDepth: Int,
        val nodeCount: Int,
        val indicatorCallCount: Int,
    )

    private fun collectStats(
        node: DslNode,
        depth: Int,
    ): AstStats =
        when (node) {
            is IndicatorRef -> AstStats(depth, 1, 1)
            is kr.spdaewoon.magicjar.domain.strategy.dsl.LiteralValue -> AstStats(depth, 1, 0)
            is kr.spdaewoon.magicjar.domain.strategy.dsl.ArithmeticExpr -> {
                val left = collectStats(node.left, depth + 1)
                val right = collectStats(node.right, depth + 1)
                AstStats(
                    maxOf(left.maxDepth, right.maxDepth),
                    1 + left.nodeCount + right.nodeCount,
                    left.indicatorCallCount + right.indicatorCallCount,
                )
            }
            is Condition -> {
                val left = collectStats(node.left, depth + 1)
                val right = collectStats(node.right, depth + 1)
                AstStats(
                    maxOf(left.maxDepth, right.maxDepth),
                    1 + left.nodeCount + right.nodeCount,
                    left.indicatorCallCount + right.indicatorCallCount,
                )
            }
            is CompositeCondition -> {
                val left = collectStats(node.left, depth + 1)
                val right = collectStats(node.right, depth + 1)
                AstStats(
                    maxOf(left.maxDepth, right.maxDepth),
                    1 + left.nodeCount + right.nodeCount,
                    left.indicatorCallCount + right.indicatorCallCount,
                )
            }
        }

    private fun calcComplexityClass(stats: AstStats): ComplexityClass =
        when {
            stats.indicatorCallCount >= 30 || stats.maxDepth >= 20 || stats.nodeCount >= 1000 -> ComplexityClass.CRITICAL
            stats.indicatorCallCount >= 15 || stats.maxDepth >= 10 || stats.nodeCount >= 200 -> ComplexityClass.HIGH
            stats.indicatorCallCount >= 5 || stats.maxDepth >= 5 || stats.nodeCount >= 50 -> ComplexityClass.MEDIUM
            else -> ComplexityClass.LOW
        }

    private fun estimateEvalTimeMs(stats: AstStats): Int = maxOf(1, stats.indicatorCallCount * 2 + stats.nodeCount / 10)

    private fun mergeComplexity(
        entry: StrategyComplexity,
        exit: StrategyComplexity?,
    ): StrategyComplexity {
        if (exit == null) return entry
        val merged =
            AstStats(
                maxOf(entry.astDepth, exit.astDepth),
                entry.nodeCount + exit.nodeCount,
                entry.indicatorCallCount + exit.indicatorCallCount,
            )
        val complexityClass = calcComplexityClass(merged)
        return StrategyComplexity(
            astDepth = merged.maxDepth,
            nodeCount = merged.nodeCount,
            indicatorCallCount = merged.indicatorCallCount,
            estimatedEvalTimeMs = estimateEvalTimeMs(merged),
            complexityClass = complexityClass,
        )
    }

    /** 미사용 이지만 컴파일 참조 제거 방지 */
    @Suppress("unused")
    private val signalRef = Signal.HOLD
}
