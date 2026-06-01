package kr.spdaewoon.magicjar.api.service.strategy

import kr.spdaewoon.magicjar.api.cache.RedisStrategyCache
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestRunJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import kr.spdaewoon.magicjar.domain.strategy.Universe
import kr.spdaewoon.magicjar.domain.strategy.dsl.CompositeCondition
import kr.spdaewoon.magicjar.domain.strategy.dsl.Condition
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.dsl.IndicatorRef
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyRepositoryPort
import kr.spdaewoon.magicjar.strategy.EvaluatorAutoDisableCallback
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Primary
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant

/**
 * 전략 서비스 — 등록·검증·조회·status 전환·auto-disable.
 *
 * - 등록 시: DSL 파싱 → static 검증 → complexity 계산 → DB persist
 * - status 전환: toggleStatus (ACTIVE↔PAUSED, DISABLED→ACTIVE reactivate, ANY→DELETED)
 * - auto-disable: StrategyJpaAdapter.autoDisable() 위임
 * - Redis cache invalidate: status 변경 시 [RedisStrategyCache.invalidateAll] 호출
 *
 * spec §6-1 호출 시퀀스 LOCK.
 * ADR 0047 §2-1-B complexity 계산 + §2-2-D auto-disable 정합.
 * ADR 0066 §2-6 F-1 status 4단 전환 정합.
 * 룰 5 위반 금지: LLM provider import 0건.
 *
 * // AI 호출 0 — T-SIG 트랙 정책
 */
@Service
@Primary
class StrategyService(
    private val strategyRepositoryPort: StrategyRepositoryPort,
    private val strategyJpaRepository: StrategyJpaRepository,
    private val backtestRunJpaRepository: BacktestRunJpaRepository,
    private val redisStrategyCache: RedisStrategyCache,
    @Value("\${magicjar.dsl.ast-depth-limit:32}") private val astDepthLimit: Int,
    @Value("\${magicjar.dsl.node-count-limit:10000}") private val nodeCountLimit: Int,
    @Value("\${magicjar.dsl.indicator-call-limit:50}") private val indicatorCallLimit: Int,
) : EvaluatorAutoDisableCallback {
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
        codes: List<String> = emptyList(),
        intervals: List<String> = emptyList(),
        takeProfitPct: BigDecimal? = null,
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
                buyConditionText = entryText,
                sellConditionText = exitText,
            )
        val definition =
            StrategyDefinition(
                dsl = dsl,
                universe = universe,
                tf = tf,
                takeProfitPct = normalizeTakeProfitPct(takeProfitPct),
            )
        val strategy =
            Strategy(
                userId = userId,
                definition = definition,
                complexity = combinedComplexity,
                status = StrategyStatus.PAUSED,
                codes = normalizeCodes(codes),
                intervals = normalizeIntervals(intervals, tf),
            )
        val saved = strategyRepositoryPort.save(strategy)
        log.info("전략 등록 완료: id={} userId={} complexity={}", saved.id, userId, combinedComplexity.complexityClass)
        return saved
    }

    fun update(
        id: Long,
        userId: Long,
        name: String,
        nameKo: String?,
        entryAst: DslNode,
        exitAst: DslNode?,
        universe: Universe,
        tf: String,
        entryText: String,
        exitText: String?,
        codes: List<String> = emptyList(),
        intervals: List<String> = emptyList(),
        takeProfitPct: BigDecimal? = null,
    ): Strategy {
        val existing =
            strategyRepositoryPort.findById(id)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
        if (existing.userId != userId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
        }

        val entryComplexity = validateAndComputeComplexity(entryAst)
        val exitComplexity = exitAst?.let { validateAndComputeComplexity(it) }
        val combinedComplexity = mergeComplexity(entryComplexity, exitComplexity)
        val dsl =
            DslStrategyDefinition(
                name = name,
                nameKo = nameKo ?: "",
                buyCondition = entryAst,
                sellCondition = exitAst,
                buyConditionText = entryText,
                sellConditionText = exitText,
            )
        val updated =
            existing.copy(
                definition =
                    StrategyDefinition(
                        dsl = dsl,
                        universe = universe,
                        tf = tf,
                        takeProfitPct = normalizeTakeProfitPct(takeProfitPct),
                    ),
                complexity = combinedComplexity,
                codes = normalizeCodes(codes),
                intervals = normalizeIntervals(intervals, tf),
                updatedAt = Instant.now(),
            )
        val saved = strategyRepositoryPort.save(updated)
        redisStrategyCache.invalidateAll()
        log.info("전략 수정 완료: id={} userId={} complexity={}", saved.id, userId, combinedComplexity.complexityClass)
        return saved
    }

    fun findById(id: Long): Strategy? = strategyRepositoryPort.findById(id)

    fun findAllByUserId(userId: Long): List<Strategy> = strategyRepositoryPort.findAllByUserId(userId)

    /**
     * 전략 목록 페이징 조회 (DELETED 제외).
     */
    @Transactional(readOnly = true)
    fun listByUserId(
        userId: Long,
        page: Int,
        size: Int,
    ): Page<Strategy> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        return strategyJpaRepository
            .findAllByUserIdAndStatusNot(userId, StrategyStatus.DELETED.name, pageable)
            .map { entity ->
                strategyRepositoryPort.findById(entity.id!!) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
            }
    }

    fun deleteById(id: Long) {
        strategyRepositoryPort.deleteById(id)
        redisStrategyCache.invalidateAll()
        log.info("전략 삭제: id={}", id)
    }

    /**
     * soft delete — status=DELETED (audit 보존).
     * ADR 0066 §2-6 F-1 ANY → DELETED.
     */
    @Transactional
    fun softDelete(id: Long) {
        val updated = strategyJpaRepository.updateStatus(id, StrategyStatus.DELETED.name)
        if (updated == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
        redisStrategyCache.invalidateAll()
        log.info("전략 soft delete: id={}", id)
    }

    /**
     * 전략 상태 전환 (ADR 0066 §2-6 F-1 transition 매트릭스).
     *
     * 허용 전환:
     * - ACTIVE → PAUSED (사용자 토글)
     * - PAUSED → ACTIVE (사용자 토글)
     * - DISABLED → ACTIVE (사용자 명시 reactivate)
     * - ANY → DELETED (soft delete)
     *
     * 시스템 액션 (ACTIVE→DISABLED) 은 [autoDisable] 사용.
     */
    @Transactional
    fun toggleStatus(
        id: Long,
        newStatus: StrategyStatus,
    ): Strategy {
        val entity =
            strategyJpaRepository.findByIdAndStatusNot(id, StrategyStatus.DELETED.name)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")

        val currentStatus = runCatching { StrategyStatus.valueOf(entity.status) }.getOrDefault(StrategyStatus.ACTIVE)
        validateTransition(id, currentStatus, newStatus)
        if (newStatus == StrategyStatus.ACTIVE) {
            validateActivationReady(id, entity.userId)
            strategyRepositoryPort.enable(id)
        } else {
            strategyJpaRepository.updateStatus(id, newStatus.name)
        }
        redisStrategyCache.invalidateAll()
        log.info("전략 status 전환: id={} {} → {}", id, currentStatus, newStatus)

        return strategyRepositoryPort.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
    }

    /**
     * auto-disable — ADR 0047 §2-2-D.
     * strategy-engine JvmStrategyEvaluator timeout 발생 시 호출.
     */
    fun autoDisable(
        strategyId: Long,
        reason: StrategyDisabledReason,
    ) {
        strategyRepositoryPort.autoDisable(strategyId, reason)
        redisStrategyCache.invalidateAll()
        log.warn("전략 auto-disable: id={} reason={}", strategyId, reason)
    }

    override fun onAutoDisable(
        strategyId: Long,
        reason: StrategyDisabledReason,
    ) = autoDisable(strategyId, reason)

    /**
     * 전략 재활성화 — 사용자 명시 활성화 (DISABLED → ACTIVE).
     */
    fun enable(strategyId: Long) {
        val entity =
            strategyJpaRepository.findByIdAndStatusNot(strategyId, StrategyStatus.DELETED.name)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$strategyId")
        validateActivationReady(strategyId, entity.userId)
        strategyRepositoryPort.enable(strategyId)
        redisStrategyCache.invalidateAll()
        log.info("전략 재활성화: id={}", strategyId)
    }

    // --- private helper ---

    private fun validateTransition(
        id: Long,
        current: StrategyStatus,
        next: StrategyStatus,
    ) {
        val allowed =
            when (next) {
                StrategyStatus.ACTIVE -> current == StrategyStatus.PAUSED || current == StrategyStatus.DISABLED
                StrategyStatus.PAUSED -> current == StrategyStatus.ACTIVE
                StrategyStatus.DELETED -> true
                StrategyStatus.DISABLED ->
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "DISABLED 전환은 시스템 auto-disable 전용 — /enable API 사용",
                    )
            }
        if (!allowed) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "전략 상태 전환 불가: id=$id $current → $next",
            )
        }
    }

    private fun validateActivationReady(
        strategyId: Long,
        userId: Long,
    ) {
        val ready = backtestRunJpaRepository.existsAutoActivationReadyRun(strategyId, userId.toString())
        if (!ready) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "성공한 백테스트가 있어야 전략을 ACTIVE로 전환할 수 있습니다: id=$strategyId",
            )
        }
    }

    private fun normalizeCodes(codes: List<String>): List<String> =
        codes
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun normalizeIntervals(
        intervals: List<String>,
        tf: String,
    ): List<String> =
        intervals
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(tf) }
            .distinct()

    private fun normalizeTakeProfitPct(value: BigDecimal?): BigDecimal? {
        if (value == null) return null
        if (value <= BigDecimal.ZERO) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "takeProfitPct는 0보다 커야 합니다")
        }
        return value
    }

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
}
