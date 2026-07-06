package kr.spdaewoon.magicjar.api.service.strategy

import kr.spdaewoon.magicjar.api.cache.RedisStrategyCache
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestRunJpaRepository
import kr.spdaewoon.magicjar.api.persistence.order.OrderRequestJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyPositionReadJpaRepository
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.MinHoldLimits
import kr.spdaewoon.magicjar.domain.strategy.PartialExit
import kr.spdaewoon.magicjar.domain.strategy.ScaleInLimits
import kr.spdaewoon.magicjar.domain.strategy.ScaleInPlan
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import kr.spdaewoon.magicjar.domain.strategy.Universe
import kr.spdaewoon.magicjar.domain.strategy.ViActionPolicy
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
    // ADR 0128 amend 갭① — catastrophic floor 운영 default. scaleIn 의 stopLossFloorPct 가 null 이면 저장 시 이 값으로
    // 채워 항상 명시 floor 로 persist. yml(magicjar.strategy.scale-in.floor-default-pct) → 운영 중 튜닝, 컴파일 상수 회피.
    @Value("\${magicjar.strategy.scale-in.floor-default-pct:15}") private val floorDefaultPct: BigDecimal = BigDecimal("15"),
    private val realtimeDemandPublisher: StrategyRealtimeDemandPublisher? = null,
    // V98 전략 아카이브 가드 — 보유 포지션·미체결 주문 존재 시 archive 거부(자본 고아화 방지).
    //   realtimeDemandPublisher 선례처럼 nullable default(기존 단위 테스트 생성자 무손실). 운영에선 Spring 이 항상 주입.
    private val strategyPositionReadJpaRepository: StrategyPositionReadJpaRepository? = null,
    private val orderRequestJpaRepository: OrderRequestJpaRepository? = null,
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
        trailingStopPct: BigDecimal? = null,
        scaleIn: ScaleInPlan? = null,
        partialExits: List<PartialExit>? = null,
        minHoldBars: Int? = null,
        viActionPolicy: ViActionPolicy? = null,
        requireCodes: Boolean = false,
    ): Strategy {
        val entryComplexity = validateAndComputeComplexity(entryAst)
        val exitComplexity = exitAst?.let { validateAndComputeComplexity(it) }
        val combinedComplexity = mergeComplexity(entryComplexity, exitComplexity)
        val normalizedStopLossPct = normalizeStopLossPct(stopLossPct)
        val normalizedTakeProfitPct = normalizeTakeProfitPct(takeProfitPct)
        val normalizedTrailingStopPct = normalizeTrailingStopPct(trailingStopPct)
        val normalizedMinHoldBars = normalizeMinHoldBars(minHoldBars)
        // ADR 0128 amend 갭① — floor null → yml default 채워 항상 명시 floor 로 persist + floor 깊이 검증.
        val resolvedScaleIn = resolveScaleIn(scaleIn)

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
                // ADR 0135 trailing stop — take_profit·fixed_stop 동급 청산 레버. null=비활성(현행 zero-regression).
                trailingStop = normalizedTrailingStopPct,
                // ADR 0111 scale-in/partial-exit — 도메인 init이 cap·weight 검증(분할매수 R3-a). floor 는 resolveScaleIn 가 명시화.
                scaleIn = resolvedScaleIn,
                partialExits = partialExits?.takeIf { it.isNotEmpty() },
                minHoldBars = normalizedMinHoldBars,
                viActionPolicy = viActionPolicy ?: defaultViActionPolicy(name),
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
        trailingStopPct: BigDecimal? = null,
        scaleIn: ScaleInPlan? = null,
        partialExits: List<PartialExit>? = null,
        minHoldBars: Int? = null,
        viActionPolicy: ViActionPolicy? = null,
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
        val normalizedTrailingStopPct = normalizeTrailingStopPct(trailingStopPct)
        val normalizedMinHoldBars = normalizeMinHoldBars(minHoldBars)
        // ADR 0128 amend 갭① — floor null → yml default 채워 항상 명시 floor 로 persist + floor 깊이 검증.
        val resolvedScaleIn = resolveScaleIn(scaleIn)
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
                        // ADR 0135 trailing stop — take_profit·fixed_stop 동급 청산 레버. null=비활성(현행 zero-regression).
                        trailingStop = normalizedTrailingStopPct,
                        // ADR 0111 scale-in/partial-exit (분할매수 R3-a). floor 는 resolveScaleIn 가 명시화.
                        scaleIn = resolvedScaleIn,
                        partialExits = partialExits?.takeIf { it.isNotEmpty() },
                        minHoldBars = normalizedMinHoldBars,
                        viActionPolicy = viActionPolicy ?: existing.definition.viActionPolicy,
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
        if (strategy.isPaperDiscovery()) {
            return null
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
     *
     * 정렬: 자동매매 시작(ACTIVE) 전략 우선 + 그룹 내 최신 변경순(updatedAt DESC).
     * 우선순위 ACTIVE > BUY_BLOCKED > PAUSED > DISABLED. CASE식 정렬은 @Query(findAllByUserIdActiveFirst)가 담당하므로
     * 여기서는 Sort 미지정 PageRequest 만 전달한다(Sort 지정 시 @Query ORDER BY 와 충돌·중복).
     */
    @Transactional(readOnly = true)
    fun listByUserId(
        userId: Long,
        page: Int,
        size: Int,
    ): Page<Strategy> = listByUserId(userId, page, size, q = null, sort = null)

    /**
     * 전략 목록 페이징 조회 (DELETED 제외) — 검색(q)·정렬(sort) 지원 (LIST 트랙 LOCKED CONTRACT).
     *
     * ## zero-regression 경로
     * `q` 가 null/빈값이고 `sort` 가 null·`recent`·`status` 이면 현행 [StrategyJpaRepository.findAllByUserIdActiveFirst]
     * (JPQL CASE)를 **그대로** 호출한다 → q·sort 미지정 기존 클라이언트는 완전 동일 동작.
     *
     * ## 검색·정렬 경로
     * `q` 가 있거나 `sort=name` 이면 [StrategyJpaRepository.searchByUserId](native)로 위임한다.
     * - `q`: name·nameKo·codes(종목코드) 대소문자 무시 부분일치(contains). `%q%` 로 변환·LOWER 비교.
     * - `sort`: recent(default)·name·status. recent·status 는 현행과 동일한 status 우선순위+updatedAt DESC 규칙.
     *
     * 정렬은 모두 DB(@Query) 위임 — 페이지 정합 유지를 위해 PageRequest 에 Sort 를 박지 않는다.
     */
    @Transactional(readOnly = true)
    fun listByUserId(
        userId: Long,
        page: Int,
        size: Int,
        q: String?,
        sort: String?,
    ): Page<Strategy> {
        val pageable = PageRequest.of(page, size)
        val normalizedSort = normalizeListSort(sort)
        val qLike = q?.trim()?.takeIf { it.isNotBlank() }?.let { "%${it.lowercase()}%" }

        // 현행 경로 보존(zero-regression): q 없음 + recent/status 는 기존 JPQL 메서드 그대로.
        val entityPage =
            if (qLike == null && normalizedSort != ListSort.NAME) {
                strategyJpaRepository.findAllByUserIdActiveFirst(userId, pageable)
            } else {
                strategyJpaRepository.searchByUserId(userId, qLike, normalizedSort.param, pageable)
            }
        return entityPage.map { entity ->
            strategyRepositoryPort.findById(entity.id!!) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
    }

    /** 목록 정렬 옵션 — 미지정·미지원 값은 default(recent)로 흡수(하위호환). */
    private enum class ListSort(
        val param: String,
    ) {
        RECENT("recent"),
        NAME("name"),
        STATUS("status"),
    }

    private fun normalizeListSort(sort: String?): ListSort =
        sort?.trim()?.lowercase()?.let { v -> ListSort.entries.firstOrNull { it.param == v } } ?: ListSort.RECENT

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
        val strategy = strategyRepositoryPort.findById(id)
        val updated = strategyJpaRepository.updateStatus(id, StrategyStatus.DELETED.name)
        if (updated == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
        strategy?.let { realtimeDemandPublisher?.exit(it) }
        redisStrategyCache.invalidateAll()
        log.info("전략 soft delete: id={}", id)
    }

    /**
     * 전략 보관(archive) — V98 전략 아카이브.
     *
     * ## 자본 안전 가드 (409 거부)
     * 보관은 전략을 자동매매 평가·관리 대상에서 완전히 빼는 행위이므로, 그 전략이 관리하던 **보유 포지션·미체결 주문이
     * 남아 있으면 고아화**된다(손절·익절·청산을 아무도 안 봄). 그래서 다음 중 하나라도 있으면 409 로 거부한다:
     * - `strategy_position.quantity > 0` (보유 물량) → "청산 후 보관 가능"
     * - open order(REQUESTED·ACK·PARTIALLY_FILLED·PENDING_RECONCILE) 존재 → "미체결 주문 정리 후 보관 가능"
     *
     * ## 허용 원본 상태
     * DELETED 를 제외한 모든 상태(ACTIVE·BUY_BLOCKED·PAUSED·DISABLED)에서 호출 가능. 이미 ARCHIVED 면 멱등(재보관 no-op 통과).
     * 가드 통과 시 status=ARCHIVED + archived_at=now + 실시간 수요 해제 + Redis 캐시 무효화.
     *
     * @throws ResponseStatusException 404(전략 없음/소유자 불일치) · 409(보유 포지션 또는 미체결 주문 존재)
     */
    @Transactional
    fun archive(
        id: Long,
        userId: Long,
    ): Strategy {
        val strategy = requireOwnedStrategy(id, userId)

        // 가드 ① 보유 포지션(quantity>0) — 전략별 포지션 귀속(ADR 0110). 종목 여러 개면 하나라도 보유면 거부.
        val positionRepo =
            strategyPositionReadJpaRepository
                ?: error("StrategyPositionReadJpaRepository 미주입 — archive 가드 불가")
        val heldQty = positionRepo.findByStrategyId(id).filter { it.quantity > 0 }.sumOf { it.quantity }
        if (heldQty > 0) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "보유 포지션 ${heldQty}주 존재 — 청산 후 보관 가능합니다: id=$id",
            )
        }

        // 가드 ② 미체결 주문 — 체결 대기 중 주문이 남아 있으면 고아 주문이 되므로 거부.
        val orderRepo =
            orderRequestJpaRepository
                ?: error("OrderRequestJpaRepository 미주입 — archive 가드 불가")
        val openOrders = orderRepo.countOpenByStrategyId(id)
        if (openOrders > 0) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "미체결 주문 ${openOrders}건 존재 — 주문 정리 후 보관 가능합니다: id=$id",
            )
        }

        strategyJpaRepository.archive(id)
        realtimeDemandPublisher?.exit(strategy)
        redisStrategyCache.invalidateAll()
        log.info("전략 보관(ARCHIVED): id={} userId={} from={}", id, userId, strategy.status)

        return strategyRepositoryPort.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
    }

    /**
     * 전략 보관 해제(unarchive) — V98 전략 아카이브. ARCHIVED → PAUSED(보수적 — 즉시 자동매매 재개 금지).
     *
     * 보관 해제는 곧바로 ACTIVE 로 켜지 않고 PAUSED 로 복귀시켜, 사용자가 검토 후 명시적으로 재활성(toggleStatus ACTIVE)하도록 한다.
     * ARCHIVED 상태가 아니면 409(전환할 대상 아님).
     *
     * @throws ResponseStatusException 404(전략 없음/소유자 불일치) · 409(보관 상태가 아님)
     */
    @Transactional
    fun unarchive(
        id: Long,
        userId: Long,
    ): Strategy {
        val strategy = requireOwnedStrategy(id, userId)
        if (strategy.status != StrategyStatus.ARCHIVED) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "보관 상태(ARCHIVED)가 아닌 전략은 보관 해제할 수 없습니다: id=$id status=${strategy.status}",
            )
        }

        strategyJpaRepository.unarchive(id)
        redisStrategyCache.invalidateAll()
        log.info("전략 보관 해제(ARCHIVED→PAUSED): id={} userId={}", id, userId)

        return strategyRepositoryPort.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
    }

    /**
     * 소유자 일치 + 미삭제 전략 조회 — 없거나 타인 소유면 404(존재 은닉, ADR 0010 사용자 격리 정합).
     */
    private fun requireOwnedStrategy(
        id: Long,
        userId: Long,
    ): Strategy {
        val strategy =
            strategyRepositoryPort.findById(id)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
        if (strategy.userId != userId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
        }
        return strategy
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
        val strategy =
            strategyRepositoryPort.findById(id)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "전략 없음: id=$id")
        if (newStatus == StrategyStatus.ACTIVE) {
            activationBlockedReason(strategy)?.let { reason ->
                throw ResponseStatusException(HttpStatus.CONFLICT, "$reason: id=$id")
            }
            strategyRepositoryPort.enable(id)
            realtimeDemandPublisher?.enter(strategy)
        } else {
            strategyJpaRepository.updateStatus(id, newStatus.name)
            realtimeDemandPublisher?.exit(strategy)
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
        realtimeDemandPublisher?.enter(strategy)
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
                // ARCHIVED 전환은 toggleStatus 경로가 아니라 전용 archive()/unarchive() 로만 수행한다(보유·미체결 가드 필수).
                StrategyStatus.ARCHIVED ->
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "ARCHIVED 전환은 보관 전용 API(/archive)로만 가능 — 보유 포지션·미체결 주문 가드가 필요합니다",
                    )
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

    /**
     * ADR 0135 — trailing stop(트레일링 스탑) 비율 정규화.
     *
     * 고점(high-watermark) 대비 trailingStopPct% 하락 시 TRAIL 청산이 발화한다(TradePlanEvaluator). null=비활성(현행).
     * 0 이하는 의미 없는 입력이므로 takeProfitPct·stopLossPct 패턴과 동일하게 400으로 선노출한다(도메인 init 500 회피).
     */
    private fun normalizeTrailingStopPct(value: BigDecimal?): BigDecimal? {
        if (value == null) return null
        if (value <= BigDecimal.ZERO) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "trailingStopPct는 0보다 커야 합니다")
        }
        return value
    }

    /**
     * 분할매수(scale-in) 저장 전 정규화 — catastrophic floor 명시화 (ADR 0128 amend 갭①).
     *
     * ## 왜 채우나
     * floor 운영 default 가 컴파일 상수(domain [ScaleInLimits.DEFAULT_STOP_LOSS_FLOOR_PCT])였던 것을 yml
     * (`magicjar.strategy.scale-in.floor-default-pct`)로 외부화했다. 저장 시 `stopLossFloorPct == null` 이면 yml
     * 값으로 **채워서 persist** → 저장된 [ScaleInPlan]은 항상 명시 floor 를 갖는다(consumer 평가가 엔진 default 에
     * 암묵 의존하지 않고, 운영 중 yml 튜닝이 신규 저장에 즉시 반영). null 이 아니면 사용자 명시값을 그대로 존중한다.
     *
     * 채운 뒤 [ScaleInLimits.validateFloorBelowDeepestEntry]로 floor 가 분할 최저 진입가보다 얕아 분할이 깨지는
     * 잘못된 설정을 저장 시점에 400 으로 차단한다. (≤0 명시 = floor 비활성 의도이므로 채우지 않고 검증도 skip.)
     *
     * @return floor 채운(혹은 그대로인) ScaleInPlan. null in → null out(scaleIn 미설정).
     * @throws ResponseStatusException 400 — floor 가 분할 최저 진입가보다 얕음(validateFloorBelowDeepestEntry 위반).
     */
    private fun resolveScaleIn(scaleIn: ScaleInPlan?): ScaleInPlan? {
        if (scaleIn == null) return null
        val resolved =
            if (scaleIn.stopLossFloorPct == null) {
                scaleIn.copy(stopLossFloorPct = floorDefaultPct)
            } else {
                scaleIn
            }
        ScaleInLimits.validateFloorBelowDeepestEntry(resolved)?.let { reason ->
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "분할매수 floor 설정 오류: $reason")
        }
        return resolved
    }

    /**
     * 잔익절 방어 B안 — minHoldBars 정규화.
     *
     * 0 또는 null은 비활성(현행 — DSL 매도 즉시 평가)으로 통일(0 저장 노이즈 회피). 음수·cap 초과는 400.
     * 도메인 [StrategyDefinition] init([MinHoldLimits])도 동일 검증하지만 사용자 입력 오류를 500이 아닌 400으로
     * 선노출하기 위해 service 진입에서 명시 검증한다(takeProfitPct·stopLossPct 패턴 정합).
     */
    private fun normalizeMinHoldBars(value: Int?): Int? {
        if (value == null || value == 0) return null
        if (value < 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "minHoldBars는 0 이상이어야 합니다")
        }
        if (value > MinHoldLimits.MAX_BARS) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "minHoldBars는 ${MinHoldLimits.MAX_BARS} 이하여야 합니다")
        }
        return value
    }

    private fun defaultViActionPolicy(name: String): ViActionPolicy {
        val upper = name.uppercase()
        return when {
            "DAYTRADE" in upper || "SCALP" in upper -> ViActionPolicy.EXIT_MARKET
            "SWING" in upper || "POSITION" in upper -> ViActionPolicy.HOLD
            else -> ViActionPolicy.ALERT_ONLY
        }
    }

    companion object {
        /**
         * 잔익절 방어 A안 임계 — 익절% ≤ 이 값이면 저장 응답에 경고를 첨부한다(차단 아님).
         *
         * 왕복 비용(수수료 0.014%×2 + 매도 거래세·농특세 ~0.15%) ≈ 0.18~0.2%. 익절 목표가 이 수준 이하면
         * 수수료·세금으로 실현 손익이 음수가 될 수 있어 "잔익절(noise exit)" risk를 사용자에게 알린다.
         */
        val LOW_TAKE_PROFIT_WARN_THRESHOLD_PCT: BigDecimal = BigDecimal("0.3")
    }

    /**
     * 잔익절 방어 A안 — 저장/수정된 전략의 잔익절 risk 경고 목록(비차단).
     *
     * 익절%(takeProfitPct) ≤ [LOW_TAKE_PROFIT_WARN_THRESHOLD_PCT]면 경고 1건을 반환한다. 미설정(null)·임계 초과면 빈 목록.
     * 컨트롤러가 응답 DTO `warnings` 필드에 실어 전략 빌더가 표시한다(저장은 이미 허용됨).
     */
    fun exitNoiseWarnings(strategy: Strategy): List<String> {
        val tp = strategy.definition.takeProfitPct ?: return emptyList()
        if (tp <= LOW_TAKE_PROFIT_WARN_THRESHOLD_PCT) {
            return listOf(
                "익절 목표 ${tp.toPlainString()}%는 왕복 거래비용(수수료+세금 약 0.2%)에 근접·미만입니다. " +
                    "이 수준의 익절은 수수료·세금 차감 후 실현 손익이 음수가 될 수 있습니다(잔익절). " +
                    "익절 목표를 0.3% 초과로 높이거나 minHoldBars(최소 보유 캔들)로 노이즈 매도를 방어하세요.",
            )
        }
        return emptyList()
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
            // TrendlineRef — anchor 참조 leaf, indicator 예산 0 (ADR 0140 §3-1)
            is kr.spdaewoon.magicjar.domain.strategy.dsl.TrendlineRef -> AstStats(depth, 1, 0)
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
