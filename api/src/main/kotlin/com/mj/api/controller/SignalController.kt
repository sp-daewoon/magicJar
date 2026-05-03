package com.mj.api.controller

import com.mj.api.application.signal.SignalHistoryService
import com.mj.api.controller.dto.SignalHistoryItemResponse
import com.mj.api.controller.dto.SignalHistoryResponse
import com.mj.api.controller.dto.SignalTestRequest
import com.mj.api.controller.dto.SignalTestResponse
import com.mj.api.controller.dto.toResponse
import com.mj.api.infrastructure.dsl.DslParseException
import com.mj.api.infrastructure.dsl.StrategyDslParser
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.strategy.dsl.StrategyDslValidator
import com.mj.domain.strategy.dsl.ValidationResult
import com.mj.domain.strategy.signal.SignalEngine
import com.mj.domain.strategy.signal.SignalEvalRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Signal Engine REST 엔드포인트 (트랙11 R1, ADR 0041).
 *
 * - GET  /api/signals?userId=...&from=...&to=...  — 시그널 이력 조회 (in-memory R1)
 * - POST /api/signals/test                        — 임의 yaml + 캔들로 시그널 평가 (UI 빌더 미리보기)
 *
 * /test는 Kafka 발행 X — 순수 평가만. 실제 시그널은 SignalEngineExecutor가 tick 흐름에서 발행.
 */
@Tag(
    name = "Signal Engine",
    description = """
        Signal Engine — DSL + 실시간 캔들 → BUY/SELL/HOLD (룰 매칭 only).

        AI 호출 0 (ADR 0041 — AI 컨설트는 W4 옵션). 1차 R1은 단일 전략(`golden_cross_5_20`)
        구독 + in-memory candle buffer + in-memory 이력. R2에서 사용자별 구독·DB 영속·
        다중 timeframe 본격.

        흐름:
         1. consumer가 market.tick.v1 → 종목별 candle 누적 → DSL 평가 →
            BUY/SELL → signal.generated.v1 발행
         2. api가 signal.generated.v1 → /topic/signal.{userId} STOMP fanout +
            메모리 history 누적
         3. 클라이언트가 GET /api/signals 또는 STOMP 구독으로 수신
    """,
)
@RestController
@RequestMapping("/api/signals")
class SignalController(
    private val historyService: SignalHistoryService,
    private val parser: StrategyDslParser,
    private val signalEngine: SignalEngine,
) {
    @Operation(
        summary = "Signal 이력 조회",
        description = """
            in-memory ring buffer에 누적된 시그널 이력을 시간 내림차순으로 조회한다.

            R1 한정사항:
             - HOLD 시그널은 미발행이라 이력에도 없음
             - userId=null인 시그널은 userId 필터 미적용 시 함께 노출
             - 최대 1000건 보관 (FIFO eviction). limit≤1000.
        """,
    )
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "이력 페이지")])
    @GetMapping
    fun list(
        @Parameter(description = "사용자 식별자 (정확 매칭, 미지정 시 전체)", example = "user-123")
        @RequestParam(required = false) userId: String?,
        @Parameter(description = "시작 시각 (epoch second, inclusive)", example = "1746000000")
        @RequestParam(required = false) from: Long?,
        @Parameter(description = "끝 시각 (epoch second, exclusive)", example = "1746500000")
        @RequestParam(required = false) to: Long?,
        @Parameter(description = "최대 건수 (1~1000, default 100)", example = "100")
        @RequestParam(required = false, defaultValue = "100") limit: Int,
    ): ResponseEntity<SignalHistoryResponse> {
        val items: List<SignalHistoryItemResponse> =
            historyService
                .query(
                    userId = userId,
                    from = from?.let(Instant::ofEpochSecond),
                    to = to?.let(Instant::ofEpochSecond),
                    limit = limit,
                ).map { it.toResponse() }
        return ResponseEntity.ok(SignalHistoryResponse(count = items.size, items = items))
    }

    @Operation(
        summary = "Signal 테스트 평가",
        description = """
            임의의 yaml + 캔들 시계열로 SignalEngine을 한 번 호출한다 (Kafka 미발행).

            UI 빌더에서 사용자가 작성한 전략을 미리보기할 때 호출. /api/strategies/evaluate와의
            차이는 응답이 entry/exit boolean이 아닌 BUY/SELL/HOLD 액션 형태라는 것.

             - 422 — 검증 실패 (validator errors)
             - 400 — yaml 파싱 실패 / 캔들 비어있음 / asOfBar 범위 초과
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "평가 결과 (BUY/SELL/HOLD)"),
            ApiResponse(responseCode = "400", description = "잘못된 입력"),
            ApiResponse(responseCode = "422", description = "검증 실패한 전략 — 평가 거부"),
        ],
    )
    @PostMapping("/test")
    fun test(
        @RequestBody request: SignalTestRequest,
    ): ResponseEntity<SignalTestResponse> {
        if (request.candles.isEmpty()) return ResponseEntity.badRequest().build()
        val parsed =
            try {
                parser.parse(request.yaml)
            } catch (_: DslParseException) {
                return ResponseEntity.badRequest().build()
            } catch (_: IllegalArgumentException) {
                return ResponseEntity.badRequest().build()
            }
        val validation = StrategyDslValidator.validate(parsed)
        if (validation is ValidationResult.Errors) {
            return ResponseEntity.unprocessableEntity().build()
        }

        val candles =
            try {
                request.candles.map { dto ->
                    Candle(
                        code = request.symbol,
                        interval = Interval.D1,
                        openTime = Instant.parse(dto.time),
                        open = dto.open,
                        high = dto.high,
                        low = dto.low,
                        close = dto.close,
                        volume = dto.volume,
                        tradeCount = 0L,
                    )
                }
            } catch (_: Exception) {
                return ResponseEntity.badRequest().build()
            }

        val asOfBar = request.asOfBar ?: candles.lastIndex
        if (asOfBar !in candles.indices) return ResponseEntity.badRequest().build()

        val signal =
            try {
                signalEngine.generate(
                    SignalEvalRequest(
                        strategyDsl = parsed,
                        symbol = request.symbol,
                        candles = candles,
                        asOfBar = asOfBar,
                        userId = request.userId,
                    ),
                )
            } catch (_: IllegalArgumentException) {
                return ResponseEntity.badRequest().build()
            } catch (_: Exception) {
                return ResponseEntity.unprocessableEntity().build()
            }

        return ResponseEntity.ok(
            SignalTestResponse(
                strategyId = signal.strategyId,
                symbol = signal.symbol,
                action = signal.action.name,
                strength = signal.strength,
                evaluatedAt = signal.evaluatedAt.epochSecond,
                eventId = signal.eventId,
                reasons = signal.reasons.map { it.description },
            ),
        )
    }
}
