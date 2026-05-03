package com.mj.api.controller

import com.mj.api.controller.dto.PatternCandleInputDto
import com.mj.api.controller.dto.PatternDescriptorResponse
import com.mj.api.controller.dto.PatternRecognizeRequest
import com.mj.api.controller.dto.PatternRecognizeResponse
import com.mj.api.controller.dto.toResponse
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.strategy.pattern.PatternCategory
import com.mj.domain.strategy.pattern.PatternMatch
import com.mj.domain.strategy.pattern.PatternRecognizer
import com.mj.domain.strategy.pattern.catalog.PatternCatalog
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

@Tag(
    name = "Pattern",
    description = """
        캔들스틱 패턴 카탈로그 + 인식 API (트랙6 R1).

        - GET  /api/patterns         — 57개 KIS Strategy Builder 정합 패턴 카탈로그
        - POST /api/patterns/recognize — 캔들 시계열 → 패턴 매칭 결과

        R1 인식기 정확 매칭 10패턴: doji, hammer, hanging_man, inverted_hammer, shooting_star,
        engulfing, morning_star, evening_star, three_white_soldiers, three_black_crows.
        나머지 47개는 카탈로그에 노출되지만 매칭 함수는 R2 보강 대상.

        Lean(QuantConnect) 미지원 9개 — opening_marubozu, tweezer_top/bottom, meeting_lines,
        matching_high, deliberation, rising/falling_three_methods, side_by_side_white_lines —
        는 KIS Strategy Builder에서는 정의되어 있으나 카탈로그에서 제외(상세는
        `_workspace/strategy/pattern-catalog.md` 참조).
    """,
)
@RestController
@RequestMapping("/api/patterns")
class PatternController {
    @Operation(
        summary = "패턴 카탈로그 조회",
        description = """
            등록된 모든 패턴 descriptor를 반환한다.

            - category 파라미터로 카테고리 필터 가능 (대소문자 무관)
            - candleCount 파라미터로 캔들 수 필터 가능 (1~5)
            - 카테고리: BULLISH_REVERSAL / BEARISH_REVERSAL / BULLISH_CONTINUATION /
              BEARISH_CONTINUATION / INDECISION / REVERSAL_DIRECTIONAL
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 (0건이어도 200)"),
            ApiResponse(responseCode = "400", description = "잘못된 category 또는 candleCount"),
        ],
    )
    @GetMapping
    fun catalog(
        @Parameter(description = "카테고리 필터 (미지정 시 전체)", example = "BULLISH_REVERSAL")
        @RequestParam(required = false) category: String?,
        @Parameter(description = "캔들 수 필터 1~5 (미지정 시 전체)", example = "3")
        @RequestParam(required = false) candleCount: Int?,
    ): ResponseEntity<List<PatternDescriptorResponse>> {
        var patterns = PatternCatalog.ALL
        if (!category.isNullOrBlank()) {
            val cat =
                runCatching { PatternCategory.valueOf(category.uppercase()) }
                    .getOrElse { return ResponseEntity.badRequest().build() }
            patterns = patterns.filter { it.category == cat }
        }
        if (candleCount != null) {
            if (candleCount !in 1..5) return ResponseEntity.badRequest().build()
            patterns = patterns.filter { it.candleCount == candleCount }
        }
        return ResponseEntity.ok(patterns.map { it.toResponse() })
    }

    @Operation(
        summary = "패턴 인식",
        description = """
            캔들 시계열에서 패턴 매칭을 검사하여 발견된 매칭 리스트를 반환한다.

            - candles: OHLCV 배열 (시간 오름차순, 같은 종목·인터벌 가정)
            - lookback: 끝에서 거꾸로 검사할 캔들 수 (기본 50, 1 이상)
            - patterns: 검사할 패턴 code 리스트 (미지정 시 전체 57개 카탈로그)
            - 매칭 결과는 endIndex 오름차순. 같은 endIndex에서 여러 패턴 동시 매칭 가능
              (예: 도지 + 잉걸핑).

            R1 정확 매칭 10패턴 외 코드는 빈 결과 반환 (R2 보강).

            예시 (전체 카탈로그 검사):
            {
              "candles": [...],
              "lookback": 50
            }

            예시 (특정 패턴만):
            {
              "candles": [...],
              "patterns": ["morning_star", "evening_star"]
            }
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "인식 성공 (매칭 0건이어도 200)"),
            ApiResponse(responseCode = "400", description = "candles 비어있음, time 형식 오류, lookback ≤ 0, 잘못된 패턴 코드"),
        ],
    )
    @PostMapping("/recognize")
    fun recognize(
        @RequestBody request: PatternRecognizeRequest,
    ): ResponseEntity<PatternRecognizeResponse> {
        if (request.candles.isEmpty()) return ResponseEntity.badRequest().build()
        val lookback = request.lookback ?: 50
        if (lookback <= 0) return ResponseEntity.badRequest().build()

        // 패턴 필터
        val targetPatterns =
            if (request.patterns.isNullOrEmpty()) {
                PatternCatalog.ALL
            } else {
                val unknown = request.patterns.filter { PatternCatalog.findByCode(it) == null }
                if (unknown.isNotEmpty()) return ResponseEntity.badRequest().build()
                request.patterns.mapNotNull { PatternCatalog.findByCode(it) }
            }

        // 캔들 변환
        val candles =
            request.candles.map { dto ->
                val time =
                    runCatching { Instant.parse(dto.time) }
                        .getOrElse { return ResponseEntity.badRequest().build() }
                dto.toCandle(time)
            }

        val matches: List<PatternMatch> =
            if (request.patterns.isNullOrEmpty()) {
                PatternRecognizer.recognize(candles, lookback)
            } else {
                // 특정 패턴만 — 직접 sliding window
                val out = mutableListOf<PatternMatch>()
                val startIdx = maxOf(0, candles.size - lookback)
                for (endIdx in startIdx until candles.size) {
                    for (p in targetPatterns) {
                        val m = PatternRecognizer.match(p, candles, endIdx)
                        if (m != null) out += m
                    }
                }
                out
            }

        return ResponseEntity.ok(
            PatternRecognizeResponse(
                candleCount = candles.size,
                lookback = lookback,
                matches = matches.map { it.toResponse() },
            ),
        )
    }
}

private fun PatternCandleInputDto.toCandle(time: Instant): Candle =
    Candle(
        code = "UNKNOWN",
        interval = Interval.D1,
        openTime = time,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
        tradeCount = 0L,
    )
