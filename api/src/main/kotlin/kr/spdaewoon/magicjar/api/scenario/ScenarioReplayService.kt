package kr.spdaewoon.magicjar.api.scenario

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import kr.spdaewoon.magicjar.api.persistence.CandleJpaEntity
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.api.service.backtest.BacktestEvaluatorBridge
import kr.spdaewoon.magicjar.domain.backtest.LookaheadGuard
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.scenario.ScenarioCandle
import kr.spdaewoon.magicjar.domain.scenario.ScenarioFault
import kr.spdaewoon.magicjar.domain.scenario.ScenarioFaultType
import kr.spdaewoon.magicjar.domain.scenario.ScenarioPositionSeed
import kr.spdaewoon.magicjar.domain.scenario.ScenarioReplayEngine
import kr.spdaewoon.magicjar.domain.scenario.ScenarioReplayRequest
import kr.spdaewoon.magicjar.domain.scenario.ScenarioRiskContext
import kr.spdaewoon.magicjar.domain.scenario.ScenarioRiskDecision
import kr.spdaewoon.magicjar.domain.scenario.ScenarioRiskEvaluator
import kr.spdaewoon.magicjar.domain.scenario.ScenarioSignalDecision
import kr.spdaewoon.magicjar.domain.scenario.ScenarioSignalSide
import kr.spdaewoon.magicjar.domain.scenario.ScenarioStrategyEvaluator
import kr.spdaewoon.magicjar.domain.strategy.Signal
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyRepositoryPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class ScenarioReplayService(
    private val strategyRepository: StrategyRepositoryPort,
    private val candleRepository: CandleJpaRepository,
    private val evaluatorBridge: BacktestEvaluatorBridge,
    private val persister: ScenarioReplayPersister,
    private val queryService: ScenarioReplayQueryService,
    private val objectMapper: ObjectMapper,
    @Value("\${magicjar.scenario-replay.enabled:false}") private val enabled: Boolean,
    @Value("\${magicjar.scenario-replay.max-candles-per-run:5000}") private val maxCandlesPerRun: Int,
    @Value("\${magicjar.scenario-replay.max-events-per-run:20000}") private val maxEventsPerRun: Int,
) {
    private val log = LoggerFactory.getLogger(ScenarioReplayService::class.java)

    fun runAndPersist(requestDto: ScenarioReplayRunRequestDto): ScenarioRunDetailDto {
        ensureEnabled()
        val request = requestDto.toDomainRequest()
        val strategy = loadStrategy(request)
        val replayRequest = request.copy(strategyName = strategy.displayName())
        val buyAst =
            strategy.definition.dsl.buyCondition
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "전략 buyCondition이 없어 리플레이를 실행할 수 없습니다.")
        val sellAst = strategy.definition.dsl.sellCondition
        val candles = loadScenarioCandles(replayRequest)

        val engine =
            ScenarioReplayEngine(
                strategyEvaluator = buildStrategyEvaluator(strategy, buyAst, sellAst),
                riskEvaluator = buildRiskEvaluator(strategy),
            )
        val result = engine.run(replayRequest, candles)

        if (result.events.size > maxEventsPerRun) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "리플레이 이벤트 수가 한도를 초과했습니다: ${result.events.size} > $maxEventsPerRun",
            )
        }

        val savedRun = persister.persist(result)
        val runId = requireNotNull(savedRun.id)
        log.info("Scenario Replay 저장 완료: runId={} userId={} events={}", runId, request.userId, result.events.size)

        val events =
            queryService
                .listEvents(runId, request.userId, page = 0, size = maxEventsPerRun.coerceIn(1, 500))
                .content
                .map { ScenarioReplayMapper.toEvent(it, objectMapper) }
        val positions = queryService.listPositions(runId, request.userId).map(ScenarioReplayMapper::toPosition)

        return ScenarioRunDetailDto(
            run = ScenarioReplayMapper.toSummary(savedRun),
            events = events,
            positions = positions,
        )
    }

    private fun ensureEnabled() {
        if (!enabled) {
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Scenario Replay는 비활성 상태입니다. MAGICJAR_SCENARIO_REPLAY_ENABLED=true 설정 후 API를 재기동하세요.",
            )
        }
    }

    private fun loadStrategy(request: ScenarioReplayRequest): Strategy {
        val strategy =
            strategyRepository.findById(request.strategyId)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "전략을 찾을 수 없습니다: strategyId=${request.strategyId}")
        if (strategy.userId.toString() != request.userId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "시나리오 리플레이 대상 전략을 찾을 수 없습니다: strategyId=${request.strategyId}")
        }
        return strategy
    }

    private fun Strategy.displayName(): String =
        definition.dsl.nameKo
            ?.takeIf { it.isNotBlank() }
            ?: definition.dsl.name

    private fun loadScenarioCandles(request: ScenarioReplayRequest): List<ScenarioCandle> {
        val entities =
            candleRepository.findByCodeAndIntervalAndTsBetween(
                code = request.code,
                interval = CandleInterval.fromCode(request.interval).code,
                fromTs = request.from.toJavaInstant(),
                toTs = request.to.toJavaInstant(),
            )
        if (entities.size > maxCandlesPerRun) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "리플레이 candle 수가 한도를 초과했습니다: ${entities.size} > $maxCandlesPerRun",
            )
        }
        return entities.mapNotNull(::toScenarioCandle)
    }

    private fun buildStrategyEvaluator(
        strategy: Strategy,
        buyAst: DslNode,
        sellAst: DslNode?,
    ): ScenarioStrategyEvaluator =
        ScenarioStrategyEvaluator { context ->
            val strategyId = requireNotNull(strategy.id)
            val holdingQty =
                context.ledger.positions
                    .firstOrNull { it.code == context.request.code }
                    ?.quantity
                    ?: 0L
            val domainCandles = context.candlesUntilNow.map { it.toStrategyCandle() }
            LookaheadGuard.safeSlice(domainCandles, domainCandles.lastIndex)

            if (holdingQty > 0 && sellAst != null) {
                val sellResult = evaluatorBridge.evaluateBacktest(strategyId, sellAst, domainCandles)
                if (sellResult.signal == Signal.SELL) {
                    return@ScenarioStrategyEvaluator ScenarioSignalDecision.sell(
                        quantity = 1,
                        reason = reasonText("SELL", sellResult.reason),
                    )
                }
                return@ScenarioStrategyEvaluator ScenarioSignalDecision.hold(reasonText("SELL_HOLD", sellResult.reason))
            }

            val buyResult = evaluatorBridge.evaluateBacktest(strategyId, buyAst, domainCandles)
            if (buyResult.signal == Signal.BUY) {
                ScenarioSignalDecision.buy(
                    quantity = 1,
                    reason = reasonText("BUY", buyResult.reason),
                )
            } else {
                ScenarioSignalDecision.hold(reasonText("BUY_HOLD", buyResult.reason))
            }
        }

    private fun buildRiskEvaluator(strategy: Strategy): ScenarioRiskEvaluator =
        ScenarioRiskEvaluator { context ->
            when {
                !strategy.status.isEvaluable() -> blockedByStatus(strategy.status, context)
                strategy.status == StrategyStatus.BUY_BLOCKED && context.signal.side == ScenarioSignalSide.BUY ->
                    ScenarioRiskDecision.Blocked(
                        reasonCode = "STRATEGY_BUY_BLOCKED",
                        message = "전략 상태가 BUY_BLOCKED라 리플레이 가상 BUY 주문을 만들지 않았습니다.",
                        payload = mapOf("strategyStatus" to strategy.status.name),
                    )
                else -> ScenarioRiskDecision.Allowed
            }
        }

    private fun blockedByStatus(
        status: StrategyStatus,
        context: ScenarioRiskContext,
    ): ScenarioRiskDecision =
        ScenarioRiskDecision.Blocked(
            reasonCode = "STRATEGY_NOT_EVALUABLE",
            message = "전략 상태가 ${status.name}라 리플레이 가상 ${context.signal.side} 주문을 만들지 않았습니다.",
            payload = mapOf("strategyStatus" to status.name),
        )

    private fun ScenarioReplayRunRequestDto.toDomainRequest(): ScenarioReplayRequest {
        val from = parseInstant(from, "from")
        val to = parseInstant(to, "to")
        return ScenarioReplayRequest(
            userId = userId,
            strategyId = strategyId,
            strategyName = null,
            code = code,
            interval = interval,
            from = from,
            to = to,
            initialCash = initialCash,
            initialPositions = initialPositions.map { it.toDomainSeed() },
            faults = faults.map { it.toDomainFault() },
        )
    }

    private fun ScenarioPositionSeedDto.toDomainSeed(): ScenarioPositionSeed =
        ScenarioPositionSeed(
            code = code,
            quantity = quantity,
            averagePrice = averagePrice,
        )

    private fun ScenarioFaultDto.toDomainFault(): ScenarioFault =
        ScenarioFault(
            at = parseInstant(at, "fault.at"),
            type =
                runCatching { ScenarioFaultType.valueOf(type.uppercase()) }
                    .getOrElse {
                        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "알 수 없는 scenario fault type입니다: $type")
                    },
            message = message,
        )

    private fun parseInstant(
        value: String,
        fieldName: String,
    ): kotlinx.datetime.Instant =
        runCatching { kotlinx.datetime.Instant.parse(value) }
            .getOrElse {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$fieldName 시각 형식이 올바르지 않습니다: $value")
            }

    private fun toScenarioCandle(entity: CandleJpaEntity): ScenarioCandle? {
        val open = entity.openAdj ?: entity.openRaw ?: return null
        val high = entity.highAdj ?: entity.highRaw ?: return null
        val low = entity.lowAdj ?: entity.lowRaw ?: return null
        val close = entity.closeAdj ?: entity.closeRaw ?: return null
        val ts = entity.ts.toKotlinInstant()
        return ScenarioCandle(
            code = entity.code,
            interval = entity.interval,
            openedAt = ts,
            closedAt = ts,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = entity.volume ?: 0L,
        )
    }

    private fun ScenarioCandle.toStrategyCandle(): Candle =
        Candle(
            id = null,
            code = code,
            interval = CandleInterval.fromCode(interval),
            ts = closedAt,
            openRaw = open,
            highRaw = high,
            lowRaw = low,
            closeRaw = close,
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = volume,
            source = "SCENARIO_REPLAY",
        )

    private fun reasonText(
        prefix: String,
        reason: Map<String, Any>,
    ): String =
        runCatching { "리플레이 전략 평가 $prefix: ${objectMapper.writeValueAsString(reason)}" }
            .getOrDefault("리플레이 전략 평가 $prefix")
}
