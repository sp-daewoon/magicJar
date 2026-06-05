package kr.spdaewoon.magicjar.api.service.strategy

import kr.spdaewoon.magicjar.api.cache.RedisStrategyCache
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestRunJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.PartialExit
import kr.spdaewoon.magicjar.domain.strategy.ScaleInPlan
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
 * ADR 0066 §2-6 F-1 status 전환 정합.
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
    @Value("\${magicjar.strategy.scale-in.enabled:false}") private val scaleInEnabled: Boolean = false,
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
        stopLossPct: BigDecimal? = null,
        takeProfitPct: BigDecimal? = null,
        scaleIn: ScaleInPlan? = null,
        partialExits: List<PartialExit>? = null,
        requireCodes: Boolean = false,
    ): Strategy {
        val entryComplexity = validateAndComputeComplexity(entryAst)
        val exitComplexity = exitAst?.let { validateAndComputeComplexity(it) }
        val combinedComplexity = mergeComplexity(entryComplexity, exitComplexity)
        val normalizedStopLossPct = normalizeStopLossPct(stopLossPct)
        val normalizedTakeProfitPct = normalizeTakeProfitPct(takeProfitPct)

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
                fixedStopLoss = normalizedStopLossPct,
                takeProfitPct = normalizedTakeProfitPct,
                // ADR 0111 scale-in/partial-exit — 도메인 init이 cap·weight 검증(분할매수 R3-a).
                scaleIn = scaleIn,
                partialExits = partialExits?.takeIf { it.isNotEmpty() },
            )
        val normalizedCodes = normalizeCodes(codes, requireCodes)
        val strategy =
            Strategy(
                userId = userId,
                definition = definition,
                complexity = combinedComplexity,
                status = StrategyStatus.PAUSED,
                codes = normalizedCodes,
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
        stopLossPct: BigDecimal? = null,
        takeProfitPct: BigDecimal? = null,
        scaleIn: ScaleInPlan? = null,
        partialExits: List<PartialExit>? = null,
        requireCodes: Boolean = false,
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
        val normalizedStopLossPct = normalizeStopLossPct(stopLossPct)
        val normalizedTakeProfitPct = normalizeTakeProfitPct(takeProfitPct)
        val dsl =
            DslStrategyDefinition(
                name = name,
                nameKo = nameKo ?: "",
                buyCondition = entryAst,
                sellCondition = exitAst,
                buyConditionText = entryText,
                sellConditionText = exitText,
            )
        val normalizedCodes = normalizeCodes(codes, requireCodes)
        val updated =
            existing.copy(
                definition =
                    StrategyDefinition(
                        dsl = dsl,
                        universe = universe,
                        tf = tf,
                        fixedStopLoss = normalizedStopLossPct,
                        takeProfitPct = normalizedTakeProfitPct,
                        // ADR 0111 scale-in/partial-exit (분할매수 R3-a).
                        scaleIn = scaleIn,
                        partialExits = partialExits?.takeIf { it.isNotEmpty() },
                    ),
                complexity = combinedComplexity,
                codes = normalizedCodes,
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

    fun activationBlockedReason(strategy: Strategy): String? {
        if (normalizeCodes(strategy.codes).isEmpty()) {
            return "자동매매 대상 종목이 없는 전략은 ACTIVE로 전환할 수 없습니다"
        }
        if (normalizeIntervals(strategy.intervals, strategy.definition.tf).isEmpty()) {
            return "평가 인터벌이 없는 전략은 ACTIVE로 전환할 수 없습니다"
        }
        if (strategy.definition.fixedStopLoss == null || strategy.definition.takeProfitPct == null) {
            return "손절/익절 RiskPlan이 없어 자동매매를 시작할 수 없습니다. 손절가와 익절가를 먼저 설정하세요"
        }
        // scale-in/exit "리플레이 먼저" 활성화 게이트 (spec §8-1 · ADR 0111 §2-7).
        // toggle(`magicjar.strategy.scale-in.enabled`) OFF 동안 scaleIn/partialExits 설정 전략의 ACTIVE 전이를
        // 서버 검증에서 거부(SCALE_IN_NOT_ENABLED). DRAFT/PAUSED 저장은 허용(리플레이/백테스트 검증 대상) —
        // UI 미노출이 아니라 서버 검증으로 막아 직접 API 호출도 방어.
        val hasScaleInExit = strategy.definition.scaleIn != null || !strategy.definition.partialExits.isNullOrEmpty()
        if (!scaleInEnabled && hasScaleInExit) {
            return "SCALE_IN_NOT_ENABLED: 분할매수/분할익절 전략은 리플레이·백테스트 정합 검증 후 활성화됩니다 (현재 비활성)"
        }
        val strategyId = strategy.id ?: return "저장되지 않은 전략은 ACTIVE로 전환할 수 없습니다"
        val ready = backtestRunJpaRepository.existsAutoActivationReadyRun(strategyId, strategy.userId.toString())
        return if (ready) {
            null
        } else {
            "성공한 백테스트가 있어야 전략을 ACTIVE로 전환할 수 있습니다"
        }
    }

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
            val strategy =
                strategyRepositoryPort.findById(id)
                    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
            activationBlockedReason(strategy)?.let { reason ->
                throw ResponseStatusException(HttpStatus.CONFLICT, "$reason: id=$id")
            }
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
     * timeout/CPU budget은 DISABLED, RISK_VIOLATION은 기존 포지션 방어를 위해 BUY_BLOCKED로 전환한다.
     */
    fun autoDisable(
        strategyId: Long,
        reason: StrategyDisabledReason,
    ) {
        strategyRepositoryPort.autoDisable(strategyId, reason)
        redisStrategyCache.invalidateAll()
        if (reason == StrategyDisabledReason.RISK_VIOLATION) {
            log.warn("전략 신규 BUY 차단: id={} reason={}", strategyId, reason)
        } else {
            log.warn("전략 auto-disable: id={} reason={}", strategyId, reason)
        }
    }

    override fun onAutoDisable(
        strategyId: Long,
        reason: StrategyDisabledReason,
    ) = autoDisable(strategyId, reason)

    /**
     * 전략 재활성화 — 사용자 명시 활성화 (DISABLED → ACTIVE).
     */
    fun enable(strategyId: Long) {
        strategyJpaRepository.findByIdAndStatusNot(strategyId, StrategyStatus.DELETED.name)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$strategyId")
        val strategy =
            strategyRepositoryPort.findById(strategyId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$strategyId")
        activationBlockedReason(strategy)?.let { reason ->
            throw ResponseStatusException(HttpStatus.CONFLICT, "$reason: id=$strategyId")
        }
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
                StrategyStatus.ACTIVE ->
                    current == StrategyStatus.PAUSED ||
                        current == StrategyStatus.DISABLED ||
                        current == StrategyStatus.BUY_BLOCKED
                StrategyStatus.PAUSED -> current == StrategyStatus.ACTIVE || current == StrategyStatus.BUY_BLOCKED
                StrategyStatus.DELETED -> true
                StrategyStatus.DISABLED,
                StrategyStatus.BUY_BLOCKED,
                ->
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "${next.name} 전환은 시스템 보호 장치 전용 — 사용자 전환은 ACTIVE·PAUSED·DELETED만 허용",
                    )
            }
        if (!allowed) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "전략 상태 전환 불가: id=$id $current → $next",
            )
        }
    }

    private fun normalizeCodes(
        codes: List<String>,
        requireNonEmpty: Boolean = false,
    ): List<String> {
        val normalized =
            codes
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        if (requireNonEmpty && normalized.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "자동매매 대상 종목을 1개 이상 지정해야 합니다")
        }
        return normalized
    }

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

    private fun normalizeStopLossPct(value: BigDecimal?): BigDecimal? {
        if (value == null) return null
        if (value <= BigDecimal.ZERO) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "stopLossPct는 0보다 커야 합니다")
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
