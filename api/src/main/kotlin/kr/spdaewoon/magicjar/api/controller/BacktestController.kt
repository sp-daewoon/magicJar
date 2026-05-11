package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.controller.dto.BacktestPerformanceDto
import kr.spdaewoon.magicjar.api.controller.dto.BacktestPositionDto
import kr.spdaewoon.magicjar.api.controller.dto.BacktestRunRequest
import kr.spdaewoon.magicjar.api.controller.dto.BacktestRunResponse
import kr.spdaewoon.magicjar.api.controller.dto.BacktestTradeDto
import kr.spdaewoon.magicjar.api.service.backtest.BacktestService
import kr.spdaewoon.magicjar.domain.backtest.BacktestException
import kr.spdaewoon.magicjar.domain.backtest.BacktestPerformance
import kr.spdaewoon.magicjar.domain.backtest.BacktestPosition
import kr.spdaewoon.magicjar.domain.backtest.BacktestResult
import kr.spdaewoon.magicjar.domain.backtest.BacktestTrade
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * 백테스트 REST 컨트롤러 (ADR 0056 §5-1 LOCK).
 *
 * ### Swagger 4단 어노테이션 (feedback_swagger_pattern.md 정합)
 * 1. @Tag: 컨트롤러 레벨
 * 2. @Operation: 메서드 레벨
 * 3. @RequestBody + @ApiResponses: 파라미터/응답 레벨
 * 4. DTO @Schema: DTO 필드 레벨 (BacktestDto.kt)
 *
 * ### fitness gap
 * - 룰 5 준수: LLM provider import 0건
 * - ADR 0047 정합: evaluateBacktest 경유 — controller 레이어는 service 위임만
 * - feedback_no_mock_data.md: Aborted(NO_CANDLE_DATA) 시 503 반환
 */
@Tag(name = "Backtest", description = "백테스트 — DSL 전략을 과거 candle 데이터로 시뮬레이션 · W3 T-BT")
@RestController
@RequestMapping("/api/backtest")
class BacktestController(
    private val backtestService: BacktestService,
) {
    private val log = LoggerFactory.getLogger(BacktestController::class.java)

    @Operation(
        summary = "백테스트 실행",
        description =
            "DSL 전략을 과거 candle 데이터로 시뮬레이션하여 매매 기록 + 성과 지표를 반환합니다. " +
                "ADR 0047 §2-5 — 평가 timeout 1000ms · 누적 50회 초과 시 abort (HTTP 206). " +
                "ADR 0023 정합 — 룰 매칭만, AI 호출 0건. " +
                "feedback_no_mock_data.md 정합 — candle 데이터 0건 시 HTTP 503 반환.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "백테스트 성공 — 매매 기록 + 성과 지표"),
        ApiResponse(responseCode = "206", description = "백테스트 abort — 부분 결과 + 사유 (TIMEOUT_QUOTA_EXCEEDED 등)"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 요청 — strategyId 미존재 / 기간 역전 / universe 0건 / 잘못된 파라미터",
            content = [Content(schema = Schema(implementation = ErrorResponseDto::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "캔들 데이터 부족 — backfill 미완료 (NO_CANDLE_DATA)",
            content = [Content(schema = Schema(implementation = ErrorResponseDto::class))],
        ),
    )
    @PostMapping("/run")
    fun run(
        @RequestBody req: BacktestRunRequest,
    ): ResponseEntity<BacktestRunResponse> {
        log.info(
            "POST /api/backtest/run: strategyId={}, universe={}, interval={}, from={}, to={}",
            req.strategyId,
            req.universe,
            req.interval,
            req.from,
            req.to,
        )

        val result: BacktestResult =
            try {
                backtestService.runBacktest(
                    strategyId = req.strategyId,
                    universe = req.universe,
                    interval = req.interval,
                    fromStr = req.from,
                    toStr = req.to,
                    initialCapital = req.initialCapital,
                    taxModel = req.taxModel,
                    commissionModel = req.commissionModel,
                    slippageModel = req.slippageModel,
                    priceAdjustmentMode = req.priceAdjustmentMode,
                )
            } catch (e: BacktestException.StrategyNotFoundException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
            } catch (e: BacktestException.InvalidRequestException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
            } catch (e: BacktestException.InvalidStrategyDslException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
            } catch (e: BacktestException.InsufficientCandleDataException) {
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.message, e)
            } catch (e: IllegalArgumentException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
            }

        return when (result) {
            is BacktestResult.Success -> {
                val response =
                    BacktestRunResponse(
                        status = "SUCCESS",
                        trades = result.trades.map { toTradeDto(it) },
                        positions = result.positions.map { toPositionDto(it) },
                        finalCapital = result.finalCapital,
                        performance = toPerformanceDto(result.performance),
                        abortReason = null,
                        durationMs = result.durationMs,
                    )
                ResponseEntity.ok(response)
            }

            is BacktestResult.Aborted -> {
                val httpStatus =
                    if (result.reason == "NO_CANDLE_DATA") {
                        HttpStatus.SERVICE_UNAVAILABLE
                    } else {
                        HttpStatus.PARTIAL_CONTENT
                    }

                val response =
                    BacktestRunResponse(
                        status = "ABORTED",
                        trades = result.partialTrades.map { toTradeDto(it) },
                        positions = emptyList(),
                        finalCapital = java.math.BigDecimal.ZERO,
                        performance = null,
                        abortReason = result.reason,
                        durationMs = result.durationMs,
                    )
                ResponseEntity.status(httpStatus).body(response)
            }
        }
    }

    // =====================================================================
    // 도메인 → DTO 변환 (private helper — ADR 0051 Repository body fun 함정 무관, controller helper)
    // =====================================================================

    private fun toTradeDto(trade: BacktestTrade): BacktestTradeDto =
        BacktestTradeDto(
            code = trade.code,
            side = trade.side.name,
            quantity = trade.quantity,
            executedPrice = trade.executedPrice,
            rawPrice = trade.rawPrice,
            executedAt = trade.executedAt.toString(),
            commission = trade.commission,
            tax = trade.tax,
            slippage = trade.slippage,
        )

    private fun toPositionDto(pos: BacktestPosition): BacktestPositionDto =
        BacktestPositionDto(
            code = pos.code,
            quantity = pos.quantity,
            avgEntryPrice = pos.avgEntryPrice,
            openedAt = pos.openedAt.toString(),
            closedAt = pos.closedAt?.toString(),
            realizedPnl = pos.realizedPnl,
        )

    private fun toPerformanceDto(perf: BacktestPerformance): BacktestPerformanceDto =
        BacktestPerformanceDto(
            totalReturn = perf.totalReturn,
            cagr = perf.cagr,
            maxDrawdown = perf.maxDrawdown,
            sharpeRatio = perf.sharpeRatio,
            winRate = perf.winRate,
            totalTrades = perf.totalTrades,
            winningTrades = perf.winningTrades,
            losingTrades = perf.losingTrades,
            avgWin = perf.avgWin,
            avgLoss = perf.avgLoss,
            profitFactor = perf.profitFactor,
            tradingFrequency = perf.tradingFrequency,
        )

    /**
     * 에러 응답 DTO — Swagger @Schema 참조용.
     */
    @Schema(description = "에러 응답")
    data class ErrorResponseDto(
        @field:Schema(description = "에러 코드", example = "BACKTEST_STRATEGY_NOT_FOUND")
        val code: String,
        @field:Schema(description = "에러 메시지", example = "전략을 찾을 수 없습니다: strategyId=12")
        val message: String,
    )
}
