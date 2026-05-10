package kr.spdaewoon.magicjar.api.persistence.strategy

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.Signal
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.domain.strategy.Universe
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyRepositoryPort
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 전략 JPA 어댑터 — StrategyRepositoryPort 구현.
 *
 * ADR 0051 준수: 헬퍼 메서드는 interface 0건, 이 클래스 private fun 위치.
 */
@Component
class StrategyJpaAdapter(
    private val strategyJpaRepository: StrategyJpaRepository,
    private val objectMapper: ObjectMapper,
) : StrategyRepositoryPort {
    @Transactional
    override fun save(strategy: Strategy): Strategy {
        val entity = toEntity(strategy)
        val saved = strategyJpaRepository.save(entity)
        return toDomain(saved)
    }

    @Transactional(readOnly = true)
    override fun findById(id: Long): Strategy? = strategyJpaRepository.findById(id).map { toDomain(it) }.orElse(null)

    @Transactional(readOnly = true)
    override fun findAllByUserId(userId: Long): List<Strategy> = strategyJpaRepository.findAllByUserId(userId).map { toDomain(it) }

    @Transactional
    override fun deleteById(id: Long) {
        strategyJpaRepository.deleteById(id)
    }

    @Transactional
    override fun autoDisable(
        strategyId: Long,
        reason: StrategyDisabledReason,
    ) {
        strategyJpaRepository.updateDisabledReason(strategyId, reason.name)
    }

    @Transactional
    override fun enable(strategyId: Long) {
        strategyJpaRepository.clearDisabledReason(strategyId)
    }

    // --- private helper ---

    private fun toEntity(strategy: Strategy): StrategyJpaEntity {
        val dsl = strategy.definition.dsl
        return StrategyJpaEntity(
            id = strategy.id,
            userId = strategy.userId,
            name = dsl.name,
            nameKo = dsl.nameKo?.takeIf { it.isNotBlank() },
            dslEntry = extractDslText(dsl.buyCondition),
            dslExit = dsl.sellCondition?.let { extractDslText(it) },
            universeMarket = strategy.definition.universe.market,
            tf = strategy.definition.tf,
            astDepth = strategy.complexity.astDepth,
            nodeCount = strategy.complexity.nodeCount,
            indicatorCallCount = strategy.complexity.indicatorCallCount,
            estimatedEvalTimeMs = strategy.complexity.estimatedEvalTimeMs,
            complexityClass = strategy.complexity.complexityClass.name,
            disabledReason = if (strategy.disabledReason == StrategyDisabledReason.NULL) null else strategy.disabledReason.name,
            lastDisabledAt = strategy.lastDisabledAt,
            evalMetricSummary = strategy.evalMetricSummary?.let { objectMapper.writeValueAsString(it) },
            createdAt = strategy.createdAt,
            updatedAt = strategy.updatedAt,
        )
    }

    private fun toDomain(entity: StrategyJpaEntity): Strategy {
        val dsl =
            DslStrategyDefinition(
                name = entity.name,
                nameKo = entity.nameKo ?: "",
                buyCondition = null, // AST는 텍스트 재파싱 필요 — 조회 시 dslEntry 텍스트 제공
                sellCondition = null,
            )
        val definition =
            StrategyDefinition(
                dsl = dsl,
                universe = Universe(market = entity.universeMarket),
                tf = entity.tf,
            )
        val complexity =
            StrategyComplexity(
                astDepth = entity.astDepth,
                nodeCount = entity.nodeCount,
                indicatorCallCount = entity.indicatorCallCount,
                estimatedEvalTimeMs = entity.estimatedEvalTimeMs,
                complexityClass = safeComplexityClass(entity.complexityClass),
            )
        val disabledReason = safeDisabledReason(entity.disabledReason)

        @Suppress("UNCHECKED_CAST")
        val evalMetricSummary: Map<String, Any>? =
            entity.evalMetricSummary?.let {
                objectMapper.readValue(it, Map::class.java) as Map<String, Any>
            }

        return Strategy(
            id = entity.id,
            userId = entity.userId,
            definition = definition,
            complexity = complexity,
            disabledReason = disabledReason,
            lastDisabledAt = entity.lastDisabledAt,
            evalMetricSummary = evalMetricSummary,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )
    }

    private fun extractDslText(node: kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode?): String = node?.toString() ?: ""

    private fun safeComplexityClass(value: String): ComplexityClass =
        runCatching { ComplexityClass.valueOf(value) }.getOrDefault(ComplexityClass.LOW)

    private fun safeDisabledReason(value: String?): StrategyDisabledReason =
        if (value == null) {
            StrategyDisabledReason.NULL
        } else {
            runCatching { StrategyDisabledReason.valueOf(value) }.getOrDefault(StrategyDisabledReason.NULL)
        }

    /** 평가 결과 outcome 문자열 — 외부 참조용 */
    object EvalOutcome {
        const val OK = "OK"
        const val TIMEOUT = "TIMEOUT"
        const val CPU_EXCEEDED = "CPU_EXCEEDED"
        const val UNKNOWN_SYMBOL = "UNKNOWN_SYMBOL"
        const val SYNTAX_ERROR = "SYNTAX_ERROR"
    }

    /** 미사용이지만 컴파일 참조 제거 방지 */
    @Suppress("unused")
    private val signalRef = Signal.HOLD
}
