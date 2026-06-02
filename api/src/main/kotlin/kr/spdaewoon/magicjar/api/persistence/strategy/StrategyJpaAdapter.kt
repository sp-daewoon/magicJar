package kr.spdaewoon.magicjar.api.persistence.strategy

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyRepositoryPort
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 전략 JPA 어댑터 — StrategyRepositoryPort 구현.
 *
 * 헌법 §6 (ADR 0051) 준수: 헬퍼 메서드는 interface 0건, 이 클래스 private fun 위치.
 * V14 확장: status + codes/intervals JSONB (ADR 0066 §2-6 F-1).
 *
 * ### P2-4 진보 페르소나 refactor (ADR 0087, 2026-05-19)
 * - `toDomain`·`safeComplexityClass`·`safeDisabledReason` → [StrategyEntityMapper] 추출
 * - 더이상 자기 손에 변환 로직 보유하지 않음 (DRY 위반 해소)
 * - 죽은 ref `private val signalRef = Signal.HOLD` 제거
 *
 * // AI 호출 0 — T-SIG 트랙 정책
 */
@Component
class StrategyJpaAdapter(
    private val strategyJpaRepository: StrategyJpaRepository,
    private val objectMapper: ObjectMapper,
    private val kisYamlDslParser: KisYamlDslParser,
) : StrategyRepositoryPort {
    @Transactional
    override fun save(strategy: Strategy): Strategy {
        val entity = toEntity(strategy)
        val saved = strategyJpaRepository.save(entity)
        return toDomain(saved)
    }

    @Transactional(readOnly = true)
    override fun findById(id: Long): Strategy? =
        strategyJpaRepository
            .findByIdAndStatusNot(id, StrategyStatus.DELETED.name)
            ?.let { toDomain(it) }

    @Transactional(readOnly = true)
    override fun findAllByUserId(userId: Long): List<Strategy> {
        val page =
            strategyJpaRepository.findAllByUserIdAndStatusNot(
                userId,
                StrategyStatus.DELETED.name,
                PageRequest.of(0, 10000, Sort.by(Sort.Direction.DESC, "createdAt")),
            )
        return page.content.map { toDomain(it) }
    }

    @Transactional
    override fun deleteById(id: Long) {
        strategyJpaRepository.deleteById(id)
    }

    @Transactional
    override fun autoDisable(
        strategyId: Long,
        reason: StrategyDisabledReason,
    ) {
        if (reason == StrategyDisabledReason.RISK_VIOLATION) {
            strategyJpaRepository.markBuyBlocked(strategyId, reason.name)
        } else {
            strategyJpaRepository.updateDisabledReason(strategyId, reason.name)
            strategyJpaRepository.updateStatus(strategyId, StrategyStatus.DISABLED.name)
        }
    }

    @Transactional
    override fun enable(strategyId: Long) {
        strategyJpaRepository.clearDisabledReason(strategyId)
        strategyJpaRepository.updateStatus(strategyId, StrategyStatus.ACTIVE.name)
    }

    // --- private helper ---

    private fun toEntity(strategy: Strategy): StrategyJpaEntity {
        val dsl = strategy.definition.dsl
        return StrategyJpaEntity(
            id = strategy.id,
            userId = strategy.userId,
            name = dsl.name,
            nameKo = dsl.nameKo?.takeIf { it.isNotBlank() },
            dslEntry = dsl.buyConditionText?.takeIf { it.isNotBlank() } ?: extractDslText(dsl.buyCondition),
            dslExit = dsl.sellConditionText?.takeIf { it.isNotBlank() } ?: dsl.sellCondition?.let { extractDslText(it) },
            universeMarket = strategy.definition.universe.market,
            tf = strategy.definition.tf,
            fixedStopLossPct = strategy.definition.fixedStopLoss,
            takeProfitPct = strategy.definition.takeProfitPct,
            astDepth = strategy.complexity.astDepth,
            nodeCount = strategy.complexity.nodeCount,
            indicatorCallCount = strategy.complexity.indicatorCallCount,
            estimatedEvalTimeMs = strategy.complexity.estimatedEvalTimeMs,
            complexityClass = strategy.complexity.complexityClass.name,
            disabledReason = if (strategy.disabledReason == StrategyDisabledReason.NULL) null else strategy.disabledReason.name,
            lastDisabledAt = strategy.lastDisabledAt,
            evalMetricSummary = strategy.evalMetricSummary?.let { objectMapper.writeValueAsString(it) },
            status = strategy.status.name,
            codes = strategy.codes.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) },
            intervals = strategy.intervals.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) },
            createdAt = strategy.createdAt,
            updatedAt = strategy.updatedAt,
        )
    }

    private fun extractDslText(node: kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode?): String = node?.toString() ?: ""

    private fun toDomain(entity: StrategyJpaEntity): Strategy =
        StrategyEntityMapper.toDomain(
            entity = entity,
            objectMapper = objectMapper,
            buyCondition = parseDslOrNull(entity.id, "buy", entity.dslEntry),
            sellCondition = entity.dslExit?.let { parseDslOrNull(entity.id, "sell", it) },
        )

    private fun parseDslOrNull(
        strategyId: Long?,
        side: String,
        text: String,
    ): DslNode? {
        if (text.isBlank()) return null
        return runCatching { kisYamlDslParser.parse(text) }
            .onFailure { e ->
                // 과거 저장 row 중 AST.toString()이 dsl_entry에 들어간 경우가 있어 조회 전체 실패 대신 조건만 null 처리한다.
                org.slf4j.LoggerFactory
                    .getLogger(StrategyJpaAdapter::class.java)
                    .warn("strategy DSL parse 실패: strategyId={} side={} err={}", strategyId, side, e.message)
            }.getOrNull()
    }

    /** 평가 결과 outcome 문자열 — 외부 참조용 */
    object EvalOutcome {
        const val OK = "OK"
        const val TIMEOUT = "TIMEOUT"
        const val CPU_EXCEEDED = "CPU_EXCEEDED"
        const val UNKNOWN_SYMBOL = "UNKNOWN_SYMBOL"
        const val SYNTAX_ERROR = "SYNTAX_ERROR"
    }
}
