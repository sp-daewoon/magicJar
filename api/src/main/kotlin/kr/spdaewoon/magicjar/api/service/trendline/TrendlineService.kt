package kr.spdaewoon.magicjar.api.service.trendline

import kr.spdaewoon.magicjar.api.controller.TrendlineCreateRequest
import kr.spdaewoon.magicjar.api.controller.TrendlineResponse
import kr.spdaewoon.magicjar.api.controller.TrendlineUpdateRequest
import kr.spdaewoon.magicjar.api.persistence.CandleJpaEntity
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaEntity
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.api.persistence.trendline.TrendlineJpaEntity
import kr.spdaewoon.magicjar.api.persistence.trendline.TrendlineJpaRepository
import kr.spdaewoon.magicjar.api.service.strategy.StrategyService
import kr.spdaewoon.magicjar.api.service.strategy.StrategyTrendlineReferenceService
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.strategy.port.TrendlineAnchorCacheInvalidator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant

@Service
class TrendlineService(
    private val trendlineJpaRepository: TrendlineJpaRepository,
    private val candleJpaRepository: CandleJpaRepository,
    private val instrumentJpaRepository: InstrumentJpaRepository,
    private val userAccountJpaRepository: UserAccountJpaRepository,
    // ADR 0140 §3-3 — 선-전략 참조 무결성(추세매매 Phase 2 R2). 삭제 가드·강제삭제·참조 정리.
    //   nullable default(기존 단위 테스트 생성자 무손실). 운영에선 Spring 이 항상 주입.
    private val trendlineReferenceService: StrategyTrendlineReferenceService? = null,
    // 강제 삭제 시 참조 전략 PAUSED. StrategyService 는 TrendlineService 에 의존하지 않아 순환 없음.
    private val strategyService: StrategyService? = null,
    // 선 수정/삭제 시 평가 어댑터(persistence-shared) anchor 캐시 무효화(ADR 0140 §3-2).
    private val trendlineCacheInvalidator: TrendlineAnchorCacheInvalidator? = null,
    // 강제 삭제 P2 알림 — SystemEvent(WARN) → Kafka → Slack(consumer notification). getIfAvailable() graceful.
    private val systemEventLoggerProvider: ObjectProvider<SystemEventLogger>? = null,
) {
    private val log = LoggerFactory.getLogger(TrendlineService::class.java)

    @Transactional(readOnly = true)
    fun list(code: String): List<TrendlineResponse> {
        validateCode(code)
        return trendlineJpaRepository
            .findByUserIdAndCodeOrderByCreatedAtDesc(currentUserId(), code)
            .map { it.toResponse() }
    }

    @Transactional
    fun createManual(
        code: String,
        request: TrendlineCreateRequest,
    ): TrendlineResponse {
        validateCode(code)
        upsertInstrumentStub(code)
        val userId = currentUserId()
        val lineType = parseLineType(request.type)
        val sourceInterval = request.sourceInterval.trim()
        if (sourceInterval.isBlank() || sourceInterval.length > 8) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceInterval 값이 올바르지 않습니다")
        }
        if (request.anchor1Ts == request.anchor2Ts) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "추세선 시작/끝 시각은 달라야 합니다")
        }

        val (firstTs, firstPrice, secondTs, secondPrice) =
            orderedAnchors(
                request.anchor1Ts,
                request.anchor1Price,
                request.anchor2Ts,
                request.anchor2Price,
            )

        val saved =
            trendlineJpaRepository.save(
                TrendlineJpaEntity(
                    userId = userId,
                    code = code,
                    sourceInterval = sourceInterval,
                    lineType = lineType,
                    source = SOURCE_MANUAL,
                    anchor1Ts = firstTs,
                    anchor1Price = firstPrice,
                    anchor2Ts = secondTs,
                    anchor2Price = secondPrice,
                    extendRight = request.extendRight,
                    extendLeft = request.extendLeft,
                    label =
                        request.label
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.take(80),
                    color =
                        request.color
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.take(16),
                ),
            )

        log.info("trendline manual saved — code={} interval={} type={}", code, sourceInterval, lineType)
        return saved.toResponse()
    }

    @Transactional
    fun createAuto(
        code: String,
        interval: String,
        lookback: Int,
    ): List<TrendlineResponse> {
        validateCode(code)
        validateCandleInterval(interval)
        upsertInstrumentStub(code)
        val userId = currentUserId()
        val limit = lookback.coerceIn(MIN_AUTO_LOOKBACK, MAX_AUTO_LOOKBACK)
        val candles =
            candleJpaRepository
                .findByCodeAndIntervalLatest(code, interval, PageRequest.of(0, limit))
                .asReversed()
                .filter { it.highRaw != null && it.lowRaw != null }

        if (candles.size < MIN_AUTO_CANDLE_COUNT) {
            return emptyList()
        }

        val support = buildAutoTrendline(userId, code, interval, TYPE_SUPPORT, findPivotLows(candles))
        val resistance = buildAutoTrendline(userId, code, interval, TYPE_RESISTANCE, findPivotHighs(candles))
        val candidates = listOfNotNull(support, resistance)
        if (candidates.isEmpty()) {
            return emptyList()
        }

        trendlineJpaRepository.deleteByUserIdAndCodeAndSourceAndSourceInterval(
            userId = userId,
            code = code,
            source = SOURCE_AUTO,
            sourceInterval = interval,
        )
        val saved = trendlineJpaRepository.saveAll(candidates)

        log.info("trendline auto saved — code={} interval={} count={}", code, interval, saved.size)
        return saved.map { it.toResponse() }
    }

    @Transactional
    fun update(
        code: String,
        id: Long,
        request: TrendlineUpdateRequest,
    ): TrendlineResponse {
        validateCode(code)
        val userId = currentUserId()
        val existing =
            trendlineJpaRepository.findByIdAndUserIdAndCode(id, userId, code)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "추세선을 찾을 수 없습니다")
        val lineType = parseLineType(request.type)
        if (request.anchor1Ts == request.anchor2Ts) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "추세선 시작/끝 시각은 달라야 합니다")
        }
        val (firstTs, firstPrice, secondTs, secondPrice) =
            orderedAnchors(
                request.anchor1Ts,
                request.anchor1Price,
                request.anchor2Ts,
                request.anchor2Price,
            )

        val saved =
            trendlineJpaRepository.save(
                TrendlineJpaEntity(
                    id = existing.id,
                    userId = existing.userId,
                    code = existing.code,
                    sourceInterval = existing.sourceInterval,
                    lineType = lineType,
                    source = existing.source,
                    anchor1Ts = firstTs,
                    anchor1Price = firstPrice,
                    anchor2Ts = secondTs,
                    anchor2Price = secondPrice,
                    extendRight = request.extendRight,
                    extendLeft = request.extendLeft,
                    label =
                        request.label
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.take(80),
                    color =
                        request.color
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.take(16),
                    createdAt = existing.createdAt,
                    updatedAt = Instant.now(),
                ),
            )

        // ADR 0140 §3-2 — anchor 변경 → 평가 어댑터 캐시 무효화(같은 JVM 즉시, 다른 JVM 은 TTL 만료).
        trendlineCacheInvalidator?.invalidate(id)
        log.info("trendline updated — code={} id={} type={}", code, id, lineType)
        return saved.toResponse()
    }

    /**
     * 추세선 삭제 — 참조 전략 존재 시 가드(ADR 0140 §3-3).
     *
     * - `force=false`(기본): 이 선을 참조하는 **살아있는** 전략(ACTIVE·BUY_BLOCKED·PAUSED·DISABLED)이 있으면
     *   **409** 로 차단(사유에 전략 이름/id 포함). 자본 기준 고아화 방지 — 전략 정리 유도.
     * - `force=true`: 참조 전략 중 매매 중인(ACTIVE·BUY_BLOCKED) 것을 **자동 PAUSED** + Slack P2 알림 + 참조 정리 후 삭제.
     *
     * @throws ResponseStatusException 404(선 없음/타인 소유) · 409(참조 전략 존재 & force 아님)
     */
    @Transactional
    fun delete(
        code: String,
        id: Long,
        force: Boolean = false,
    ) {
        validateCode(code)
        val userId = currentUserId()
        // 소유권 확인(존재하지 않거나 타인 소유면 404) — 삭제 전에 참조 조회를 위해 선행.
        trendlineJpaRepository.findByIdAndUserIdAndCode(id, userId, code)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "추세선을 찾을 수 없습니다")

        val referencing = trendlineReferenceService?.findLiveReferencing(id).orEmpty()
        if (referencing.isNotEmpty() && !force) {
            val names = referencing.joinToString(", ") { "${it.name}(#${it.strategyId})" }
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "이 추세선을 참조하는 전략 ${referencing.size}개가 있습니다 — 전략을 먼저 보관/정리하세요: $names",
            )
        }
        if (referencing.isNotEmpty()) {
            // 강제 삭제 — 참조 전략 자동 PAUSED + P2 알림(기준 증발 상태 매매 지속 금지, ADR 0140 §3-3).
            referencing.forEach { strategyService?.forcePauseForTrendlineRemoval(it.strategyId) }
            emitForceDeleteAlert(id, code, referencing)
        }

        // 참조 행 정리(FK CASCADE 안전망 + 명시 정리).
        trendlineReferenceService?.clearByTrendlineId(id)
        val deleted = trendlineJpaRepository.deleteByIdAndUserIdAndCode(id, userId, code)
        if (deleted == 0) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "추세선을 찾을 수 없습니다")
        }
        // 평가 어댑터 캐시 무효화 — 삭제된 선을 anchorsOf 가 즉시 부재로 인식(§11-2 조건 false).
        trendlineCacheInvalidator?.invalidate(id)
        if (referencing.isNotEmpty()) {
            log.warn("trendline 강제 삭제 — code={} id={} PAUSED 전략 {}개", code, id, referencing.size)
        }
    }

    /**
     * 강제 삭제 P2 알림 — SystemEvent(WARN) emit. consumer notification dispatcher 가 Slack 으로 fanout.
     * emit 실패는 삭제 트랜잭션을 죽이지 않는다(runCatching, graceful — SystemEventLogger 계약).
     */
    private fun emitForceDeleteAlert(
        trendlineId: Long,
        code: String,
        referencing: List<kr.spdaewoon.magicjar.api.persistence.strategy.ReferencingStrategyView>,
    ) {
        val logger = systemEventLoggerProvider?.getIfAvailable() ?: return
        val pausedIds = referencing.joinToString(",") { it.strategyId.toString() }
        val detailJson =
            """{"trendlineId":$trendlineId,"code":"$code","pausedStrategyIds":[$pausedIds],"count":${referencing.size}}"""
        runCatching {
            logger.emit(
                SystemEvent(
                    category = SystemEventCategory.OTHER,
                    severity = SystemEventSeverity.WARN,
                    actor = "TrendlineForceDelete",
                    action = "TRENDLINE_FORCE_DELETE_STRATEGY_PAUSED",
                    userId = null,
                    detailJson = detailJson,
                ),
            )
        }.onFailure { e ->
            log.error("추세선 강제 삭제 P2 알림 emit 실패 — trendlineId={} err={}", trendlineId, e.message)
        }
    }

    private fun buildAutoTrendline(
        userId: String,
        code: String,
        interval: String,
        lineType: String,
        pivots: List<Pivot>,
    ): TrendlineJpaEntity? {
        if (pivots.size < 2) return null
        val second = pivots.last()
        val first = pivots.dropLast(1).lastOrNull { it.ts != second.ts } ?: return null
        return TrendlineJpaEntity(
            userId = userId,
            code = code,
            sourceInterval = interval,
            lineType = lineType,
            source = SOURCE_AUTO,
            anchor1Ts = first.ts,
            anchor1Price = first.price,
            anchor2Ts = second.ts,
            anchor2Price = second.price,
            extendRight = true,
            extendLeft = false,
            label = "AUTO ${if (lineType == TYPE_SUPPORT) "SUPPORT" else "RESISTANCE"} $interval",
            color = if (lineType == TYPE_SUPPORT) SUPPORT_COLOR else RESISTANCE_COLOR,
        )
    }

    private fun findPivotLows(candles: List<CandleJpaEntity>): List<Pivot> =
        findPivots(candles) { center, neighbor ->
            val centerLow = center.lowRaw ?: return@findPivots false
            val neighborLow = neighbor.lowRaw ?: return@findPivots false
            centerLow <= neighborLow
        }.mapNotNull { candle -> candle.lowRaw?.let { Pivot(candle.ts, it) } }

    private fun findPivotHighs(candles: List<CandleJpaEntity>): List<Pivot> =
        findPivots(candles) { center, neighbor ->
            val centerHigh = center.highRaw ?: return@findPivots false
            val neighborHigh = neighbor.highRaw ?: return@findPivots false
            centerHigh >= neighborHigh
        }.mapNotNull { candle -> candle.highRaw?.let { Pivot(candle.ts, it) } }

    private fun findPivots(
        candles: List<CandleJpaEntity>,
        predicate: (CandleJpaEntity, CandleJpaEntity) -> Boolean,
    ): List<CandleJpaEntity> {
        if (candles.size < MIN_AUTO_CANDLE_COUNT) return emptyList()
        return (PIVOT_WING until candles.size - PIVOT_WING)
            .mapNotNull { index ->
                val center = candles[index]
                val neighbors =
                    (index - PIVOT_WING..index + PIVOT_WING)
                        .filter { it != index }
                        .map { candles[it] }
                if (neighbors.all { predicate(center, it) }) center else null
            }
    }

    private fun orderedAnchors(
        anchor1Ts: Instant,
        anchor1Price: BigDecimal,
        anchor2Ts: Instant,
        anchor2Price: BigDecimal,
    ): AnchorTuple =
        if (anchor1Ts.isBefore(anchor2Ts)) {
            AnchorTuple(anchor1Ts, anchor1Price, anchor2Ts, anchor2Price)
        } else {
            AnchorTuple(anchor2Ts, anchor2Price, anchor1Ts, anchor1Price)
        }

    private fun currentUserId(): String =
        userAccountJpaRepository
            .findAll(Sort.by(Sort.Direction.ASC, "createdAt"))
            .firstOrNull()
            ?.userId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "셋업 완료 후 사용 가능합니다")

    private fun validateCode(code: String) {
        if (!code.matches(Regex("^\\d{6}$"))) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "종목코드는 6자리 숫자여야 합니다")
        }
    }

    private fun validateCandleInterval(interval: String) {
        try {
            CandleInterval.fromCode(interval)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 interval: $interval")
        }
    }

    private fun parseLineType(type: String): String =
        when (type.trim().uppercase()) {
            TYPE_SUPPORT -> TYPE_SUPPORT
            TYPE_RESISTANCE -> TYPE_RESISTANCE
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "type은 SUPPORT 또는 RESISTANCE만 가능합니다")
        }

    private fun upsertInstrumentStub(code: String) {
        if (!instrumentJpaRepository.existsByCode(code)) {
            runCatching {
                instrumentJpaRepository.save(
                    InstrumentJpaEntity(
                        code = code,
                        name = code,
                        market = "UNKNOWN",
                        type = "UNKNOWN",
                    ),
                )
            }.onFailure { e ->
                log.debug("instrument stub upsert race — code={}: {}", code, e.message)
            }
        }
    }

    private fun TrendlineJpaEntity.toResponse(): TrendlineResponse =
        TrendlineResponse(
            id = id ?: 0L,
            code = code,
            sourceInterval = sourceInterval,
            type = lineType,
            source = source,
            anchor1Ts = anchor1Ts,
            anchor1Price = anchor1Price.toPlainString(),
            anchor2Ts = anchor2Ts,
            anchor2Price = anchor2Price.toPlainString(),
            extendRight = extendRight,
            extendLeft = extendLeft,
            label = label,
            color = color,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private data class Pivot(
        val ts: Instant,
        val price: BigDecimal,
    )

    private data class AnchorTuple(
        val anchor1Ts: Instant,
        val anchor1Price: BigDecimal,
        val anchor2Ts: Instant,
        val anchor2Price: BigDecimal,
    )

    companion object {
        private const val TYPE_SUPPORT = "SUPPORT"
        private const val TYPE_RESISTANCE = "RESISTANCE"
        private const val SOURCE_MANUAL = "MANUAL"
        private const val SOURCE_AUTO = "AUTO"
        private const val SUPPORT_COLOR = "#10b981"
        private const val RESISTANCE_COLOR = "#ef4444"
        private const val MIN_AUTO_LOOKBACK = 30
        private const val MAX_AUTO_LOOKBACK = 1000
        private const val MIN_AUTO_CANDLE_COUNT = 9
        private const val PIVOT_WING = 2
    }
}
