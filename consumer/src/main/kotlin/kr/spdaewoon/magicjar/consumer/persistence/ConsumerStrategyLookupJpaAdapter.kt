package kr.spdaewoon.magicjar.consumer.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import kr.spdaewoon.magicjar.domain.strategy.Universe
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyLookupPort
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Consumer-side StrategyLookupPort implementation.
 *
 * api 모듈 StrategyLookupJpaAdapter는 consumer classpath에 없으므로, 실시간 자동매매 JVM에서 같은 테이블을
 * 직접 조회하고 dsl_entry/dsl_exit 텍스트를 AST로 재파싱한다.
 */
@Component
class ConsumerStrategyLookupJpaAdapter(
    private val strategyJpaRepository: ConsumerStrategyJpaRepository,
    private val objectMapper: ObjectMapper,
    private val kisYamlDslParser: KisYamlDslParser,
) : StrategyLookupPort {
    private val log = LoggerFactory.getLogger(ConsumerStrategyLookupJpaAdapter::class.java)

    @Transactional(readOnly = true)
    override fun findActiveByCodeAndInterval(
        code: String,
        interval: CandleInterval,
    ): List<Strategy> {
        val entities =
            strategyJpaRepository.findActiveByCodeAndInterval(
                codeJson = objectMapper.writeValueAsString(listOf(code)),
                intervalJson = objectMapper.writeValueAsString(listOf(interval.code)),
            )
        return entities.mapNotNull { toDomainOrNull(it) }
    }

    private fun toDomainOrNull(entity: ConsumerStrategyJpaEntity): Strategy? {
        val buy = parseDslOrNull(entity.id, "buy", entity.dslEntry)
        if (buy == null) {
            log.warn("ACTIVE strategy 매수 DSL 파싱 실패로 realtime 평가 제외: strategyId={}", entity.id)
            return null
        }
        val sell = entity.dslExit?.let { parseDslOrNull(entity.id, "sell", it) }
        val dsl =
            DslStrategyDefinition(
                name = entity.name,
                nameKo = entity.nameKo ?: "",
                buyCondition = buy,
                sellCondition = sell,
                buyConditionText = entity.dslEntry,
                sellConditionText = entity.dslExit,
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

        @Suppress("UNCHECKED_CAST")
        val evalMetricSummary =
            entity.evalMetricSummary?.let {
                runCatching { objectMapper.readValue(it, Map::class.java) as Map<String, Any> }.getOrNull()
            }

        return Strategy(
            id = entity.id,
            userId = entity.userId,
            definition = definition,
            complexity = complexity,
            disabledReason = safeDisabledReason(entity.disabledReason),
            status = safeStatus(entity.status),
            codes = safeStringList(entity.codes),
            intervals = safeStringList(entity.intervals),
            lastDisabledAt = entity.lastDisabledAt,
            evalMetricSummary = evalMetricSummary,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )
    }

    private fun parseDslOrNull(
        strategyId: Long?,
        side: String,
        text: String,
    ): DslNode? {
        if (text.isBlank()) return null
        return runCatching { kisYamlDslParser.parse(text) }
            .onFailure { e ->
                log.warn("consumer strategy DSL parse 실패: strategyId={} side={} err={}", strategyId, side, e.message)
            }.getOrNull()
    }

    private fun safeComplexityClass(value: String): ComplexityClass =
        runCatching { ComplexityClass.valueOf(value) }.getOrDefault(ComplexityClass.LOW)

    private fun safeDisabledReason(value: String?): StrategyDisabledReason =
        if (value == null) {
            StrategyDisabledReason.NULL
        } else {
            runCatching { StrategyDisabledReason.valueOf(value) }.getOrDefault(StrategyDisabledReason.NULL)
        }

    private fun safeStatus(value: String): StrategyStatus =
        runCatching { StrategyStatus.valueOf(value) }.getOrDefault(StrategyStatus.ACTIVE)

    private fun safeStringList(value: String?): List<String> =
        value
            ?.let {
                runCatching {
                    objectMapper
                        .readValue(it, List::class.java)
                        .mapNotNull { item ->
                            item?.toString()?.takeIf(String::isNotBlank)
                        }
                }.getOrNull()
            }
            ?: emptyList()
}
