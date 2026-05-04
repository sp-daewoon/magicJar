package com.mj.api.controller

import com.mj.api.application.instrument.InstrumentService
import com.mj.api.application.market.QuoteQueryService
import com.mj.api.controller.dto.QuoteResponse
import com.mj.api.controller.dto.toResponse
import com.mj.api.messaging.producer.MarketCandleBackfillCommandPublisher
import com.mj.api.messaging.producer.MarketSubscriptionCommandPublisher
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 종목 현재 시세 REST 컨트롤러. ADR 0033, spec §5.
 *
 * GET /api/instruments/{code}/quote
 *
 * 3-tier fallback 순서:
 *   1. Redis QuoteCache (LIVE — stale < 10초)
 *   2. Redis QuoteCache (CACHED — stale ≥ 10초)
 *   3. DB 캔들 전일종가 (LAST_DAY_CLOSE)
 *   없으면 404
 */
@Tag(
    name = "Quote",
    description = """
        KRX 종목 현재 시세. 3-tier fallback (ADR 0033):

        - Tier 1 LIVE: KIS WebSocket 실시간 체결가 (stale < 10초)
        - Tier 2 CACHED: Redis 캐시 마지막 tick (stale ≥ 10초, source=CACHED, stale=true)
        - Tier 3 LAST_DAY_CLOSE: DB 캔들 전일종가 (캐시 miss 시, source=LAST_DAY_CLOSE, stale=true)

        응답에 source 필드로 어떤 tier에서 반환됐는지 구분 가능.
        stale=true 면 fallback 데이터이므로 UI에서 시각적 표시 권장.

        STOMP /topic/market.tick.{code} 로 실시간 업데이트를 수신하면 stale 상태 자동 해소.
    """,
)
@RestController
@RequestMapping("/api/instruments")
class QuoteController(
    private val instrumentService: InstrumentService,
    private val quoteQueryService: QuoteQueryService,
    private val subscriptionCommandPublisher: MarketSubscriptionCommandPublisher,
    private val backfillCommandPublisher: MarketCandleBackfillCommandPublisher,
) {
    @Operation(
        summary = "종목 현재 시세 조회",
        description = """
            3-tier fallback 순서로 시세를 반환한다.

            1. LIVE: KIS WebSocket 실시간 tick (stale < 10초)
            2. CACHED: Redis 캐시 마지막 tick (stale ≥ 10초)
            3. LAST_DAY_CLOSE: DB 캔들 전일종가

            응답의 source 필드로 어느 단계에서 반환됐는지 확인할 수 있다.
            stale=true 이면 실시간이 아닌 fallback 데이터임을 표시해야 한다.

            mock profile 에서는 MockTickGenerator가 1초마다 tick을 발행하므로 항상 source=LIVE.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "시세 조회 성공",
                content = [Content(schema = Schema(implementation = QuoteResponse::class))],
            ),
            ApiResponse(responseCode = "404", description = "해당 코드의 종목 없음 또는 시세 데이터 없음"),
        ],
    )
    @GetMapping("/{code}/quote")
    fun quote(
        @Parameter(description = "KRX 종목코드 6자리", example = "005930")
        @PathVariable code: String,
    ): ResponseEntity<QuoteResponse> {
        val normalized = code.uppercase()

        // 종목 존재 여부 검증
        instrumentService.get(normalized) ?: return ResponseEntity.notFound().build()

        // R-B (Quote hotfix B): consumer 측 KIS WebSocket 동적 구독 보장.
        // 멱등 — KisWsRouter가 이미 구독 중이면 frame 미전송. 응답 블로킹하지 않는다.
        try {
            subscriptionCommandPublisher.ensureSubscribe(normalized)
        } catch (e: Exception) {
            // 구독 명령 발행 실패는 시세 조회 자체를 막지 않는다 (best-effort).
        }

        // D (handoff 2026-05-04): 일봉 candle backfill 보장. batch listener가 멱등 dedup.
        // 차트 데이터 즉시 채워 사용자 진입 시 빈 차트 회피.
        try {
            backfillCommandPublisher.ensureBackfill(normalized)
        } catch (e: Exception) {
            // 본 명령 실패는 시세 조회 응답을 막지 않는다.
        }

        // 3-tier fallback 조회
        val result =
            quoteQueryService.query(normalized)
                ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(result.toResponse())
    }
}
