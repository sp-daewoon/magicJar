package kr.spdaewoon.magicjar.api.controller

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.api.service.CandleQueryService
import kr.spdaewoon.magicjar.api.service.CandleRefreshService
import kr.spdaewoon.magicjar.api.service.SymbolMetaService
import kr.spdaewoon.magicjar.api.service.dto.CandleRecollectResponse
import kr.spdaewoon.magicjar.api.service.dto.CandleRefreshResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * 종목 REST 컨트롤러 (spec §3-1 LOCK — 4 endpoint).
 *
 * - GET  /api/symbols/{code}                        — SymbolDto (현재가·PER·PBR·시총·업종)
 * - GET  /api/symbols/{code}/candles                — CandlesResponseDto (차트 series)
 * - POST /api/symbols/{code}/realtime/subscribe     — 현재가+호가 slot ENTER 명령
 * - POST /api/symbols/{code}/realtime/unsubscribe   — 현재가+호가 slot EXIT 명령
 * - POST /api/symbols/{code}/orderbook/subscribe    — 기존 호환 alias
 * - POST /api/symbols/{code}/orderbook/unsubscribe  — 기존 호환 alias
 *
 * OnboardingGuardFilter 통과 의무 (PR-6 정합 — 자동).
 * Swagger 4단 어노테이션 (사용자 메모리 feedback_swagger_pattern 정합).
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Tag(name = "Symbol", description = "종목 상세 REST — 현재가·캔들·호가·캔들 갱신 (T-S1)")
@RestController
@RequestMapping("/api/symbols")
class SymbolController(
    private val symbolMetaService: SymbolMetaService,
    private val candleQueryService: CandleQueryService,
    private val candleRefreshService: CandleRefreshService,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val userAccountJpaRepository: UserAccountJpaRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(SymbolController::class.java)

    @Operation(
        summary = "종목 메타·현재가 조회",
        description =
            "종목코드(6자리)로 현재가·전일대비·등락률·PER·PBR·시총·업종·종목명을 반환합니다. " +
                "1순위: Redis quote:last (실시간), 2순위: KIS inquire-price REST (FHKST01010100). " +
                "KIS 장애 시 stale:true로 마지막 cache 반환. 데이터 부재 시 503.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "401",
            description = "셋업 미완 — OnboardingGuardFilter 차단",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "종목 데이터 없음 — KIS·Redis 모두 실패",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{code}")
    fun getSymbol(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
    ): SymbolDto {
        validateCode(code)
        return try {
            symbolMetaService.getSymbol(code)
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            log.error("getSymbol 오류: code={} err={}", code, e.message)
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "종목 데이터를 가져올 수 없습니다",
            )
        }
    }

    @Operation(
        summary = "종목 캔들(차트) 시계열 조회",
        description =
            "interval 인터벌의 캔들 시계열을 반환합니다 (DB candle + Redis current·today 합류). " +
                "interval: 1m·3m·5m·15m·30m·90m·1h·4h·12h·1d·1wk·1mo·1y. " +
                "before: 페이지네이션 cursor (ISO8601, 미지정 시 최신). " +
                "limit: 1 ≤ limit ≤ 5000 (default 600 — ADR 0093 §3-1 LOCK). " +
                "응답 candles는 ts ASC 정렬 (LWC v4 setData 기대). " +
                "hasMore:true이면 nextBefore cursor로 추가 fetch 가능. " +
                "데이터 0건이면 candles:[] + hasMore:false (graceful — '데이터 수집 중' 표시).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 파라미터 (interval 형식 오류 등)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "셋업 미완",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{code}/candles")
    fun getCandles(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
        @Parameter(description = "인터벌 (1m·3m·5m·15m·30m·90m·1h·4h·12h·1d·1wk·1mo·1y)", example = "1d")
        @RequestParam(defaultValue = "1d") interval: String,
        @Parameter(description = "페이지네이션 cursor — 이 시각 이전 candle 반환 (ISO8601)", example = "2026-05-07T15:30:00Z")
        @RequestParam(required = false) before: String?,
        @Parameter(description = "응답 캔들 수 (1~5000, default 600 — ADR 0093 §3-1 LOCK)", example = "600")
        @RequestParam(defaultValue = "600") limit: Int,
    ): CandlesResponseDto {
        validateCode(code)
        if (interval !in candleQueryService.supportedIntervals) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "지원하지 않는 interval: $interval (허용: 1m·3m·5m·15m·30m·90m·1h·4h·12h·1d·1wk·1mo·1y)",
            )
        }
        return candleQueryService.getCandles(code, interval, before, limit)
    }

    @Operation(
        summary = "캔들 수동 갱신",
        description =
            "지정 종목의 캔들 데이터를 KIS OpenAPI에서 즉시 fetch하여 DB에 upsert합니다 (dogfooding qa #5). " +
                "intervals 파라미터로 갱신 대상 인터벌 지정 (기본값: 1d,1wk,1mo,1y). " +
                "분봉(1m) 포함 시 직전 30 거래일 분봉 전수 수집. " +
                "KIS-first: FHKST03010100(일/주/월/연봉), FHKST03010230(1m) 직접 호출. " +
                "KIS raw: open-trading-api/examples_llm/domestic_stock/inquire_daily_itemchartprice " +
                "응답: refreshedIntervals 배열 (interval, fetchedCount, insertedCount, latestTs, status).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "갱신 성공 (일부 interval 실패 시에도 200 — status 필드로 구분)"),
        ApiResponse(
            responseCode = "404",
            description = "종목 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "429",
            description = "KIS Rate Limit 초과",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "500",
            description = "KIS 인증 실패 또는 내부 오류",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping("/{code}/candles/refresh")
    fun refreshCandles(
        @Parameter(description = "KIS 종목코드 6자리", example = "006110")
        @PathVariable code: String,
        @Parameter(
            description = "갱신 대상 interval CSV (기본값: 1d,1wk,1mo,1y). 지원: 1m,1d,1wk,1mo,1y",
            example = "1d,1wk,1mo,1y",
        )
        @RequestParam(defaultValue = "") intervals: String,
    ): CandleRefreshResponse {
        validateCode(code)
        val intervalList =
            if (intervals.isBlank()) {
                emptyList()
            } else {
                intervals.split(",").map { it.trim() }.filter { it.isNotBlank() }
            }
        return try {
            candleRefreshService.refresh(code, intervalList)
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            log.error("refreshCandles 오류: code={} err={}", code, e.message)
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "캔들 갱신 중 오류가 발생했습니다")
        }
    }

    @Operation(
        summary = "캔들 초기화 후 재수집",
        description =
            "차트 캔들 데이터가 잘못 수집된 것으로 보일 때 사용자가 명시적으로 실행하는 복구 endpoint입니다. " +
                "지정 interval의 기존 DB candle을 삭제한 뒤 KIS OpenAPI에서 다시 수집합니다. " +
                "3m·5m·15m·30m·90m·1h·4h·12h는 KIS 직접 제공 interval이 아니므로 1m을 함께 재수집한 뒤 서버에서 합산 재생성합니다. " +
                "Mock·합성 보정 없이 KIS/DB 기준 데이터만 사용합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "재수집 완료 (일부 interval 실패 시 status 필드로 구분)"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 interval 또는 파라미터",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "종목 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "500",
            description = "KIS 인증 실패 또는 내부 오류",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping("/{code}/candles/recollect")
    fun recollectCandles(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
        @Parameter(
            description = "재수집 대상 interval CSV. 합산봉(3m~12h)은 1m도 함께 재수집 후 재생성",
            example = "3m",
        )
        @RequestParam(defaultValue = "") intervals: String,
    ): CandleRecollectResponse {
        validateCode(code)
        val intervalList =
            intervals.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return try {
            candleRefreshService.recollect(code, intervalList)
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            log.error("recollectCandles 오류: code={} intervals={} err={}", code, intervals, e.message)
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "캔들 재수집 중 오류가 발생했습니다")
        }
    }

    @Operation(
        summary = "종목 상세 실시간 구독 시작",
        description =
            "SymbolDetail 페이지 진입 시 호출 — watchlist 등록 여부와 무관하게 KIS WS 현재가(H0STCNT0/H0NXCNT0)와 " +
                "호가(H0STASP0)를 요청합니다. Kafka symbol.detail.command.v1 {action:ENTER} 발행 → consumer가 " +
                "DETAIL owner 실시간 가격 구독 + 호가 LRU slot을 처리합니다. " +
                "STOMP /topic/market.tick.{code}, /topic/market.orderbook.{code}로 push 시작.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "구독 명령 발행 성공"),
        ApiResponse(responseCode = "401", description = "셋업 미완"),
    )
    @PostMapping("/{code}/realtime/subscribe")
    fun subscribeRealtime(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
    ): RealtimeSubscribeResponse {
        validateCode(code)
        publishSymbolDetailCommand(code, "ENTER")
        return RealtimeSubscribeResponse(subscribed = true, priceSlot = "live", orderbookSlot = "live")
    }

    @Operation(
        summary = "종목 상세 실시간 구독 해제",
        description =
            "SymbolDetail 페이지 이탈 시 호출 — DETAIL owner 현재가 수요와 호가 slot을 회수합니다. " +
                "해당 종목이 watchlist 또는 strategy owner로도 요구 중이면 현재가 WS 구독은 유지됩니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "구독 해제 명령 발행 성공"),
        ApiResponse(responseCode = "401", description = "셋업 미완"),
    )
    @PostMapping("/{code}/realtime/unsubscribe")
    fun unsubscribeRealtime(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
    ): RealtimeUnsubscribeResponse {
        validateCode(code)
        publishSymbolDetailCommand(code, "EXIT")
        return RealtimeUnsubscribeResponse(unsubscribed = true)
    }

    @Operation(
        summary = "호가 구독 시작 (호환 alias)",
        description =
            "기존 클라이언트 호환용 alias입니다. 실제로는 종목 상세 실시간 구독 ENTER 명령을 발행해 " +
                "현재가+호가를 함께 요청합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "구독 명령 발행 성공"),
        ApiResponse(responseCode = "401", description = "셋업 미완"),
    )
    @PostMapping("/{code}/orderbook/subscribe")
    fun subscribeOrderbook(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
    ): OrderbookSubscribeResponse {
        validateCode(code)
        publishSymbolDetailCommand(code, "ENTER")
        return OrderbookSubscribeResponse(subscribed = true, slot = "live")
    }

    @Operation(
        summary = "호가 구독 해제 (호환 alias)",
        description =
            "기존 클라이언트 호환용 alias입니다. 실제로는 종목 상세 실시간 구독 EXIT 명령을 발행해 " +
                "DETAIL owner 현재가 수요와 호가 slot을 함께 회수합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "구독 해제 명령 발행 성공"),
        ApiResponse(responseCode = "401", description = "셋업 미완"),
    )
    @PostMapping("/{code}/orderbook/unsubscribe")
    fun unsubscribeOrderbook(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
    ): OrderbookUnsubscribeResponse {
        validateCode(code)
        publishSymbolDetailCommand(code, "EXIT")
        return OrderbookUnsubscribeResponse(unsubscribed = true)
    }

    // ── private helpers ─────────────────────────────────────────────────────

    private fun publishSymbolDetailCommand(
        code: String,
        action: String,
    ) {
        val userId =
            try {
                userAccountJpaRepository.findAll().firstOrNull()?.userId ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "action" to action,
                    "code" to code,
                    "userId" to userId,
                    "ts" to Instant.now().toString(),
                ),
            )
        kafkaTemplate.send("symbol.detail.command.v1", code, payload)
        log.debug("symbol.detail.command 발행: action={} code={}", action, code)
    }

    private fun validateCode(code: String) {
        if (!code.matches(Regex("^\\d{6}$"))) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "종목 코드는 6자리 숫자여야 합니다 (예: 005930)",
            )
        }
    }
}
