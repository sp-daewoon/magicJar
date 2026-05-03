package com.mj.api.controller

import com.mj.api.controller.dto.PresetResponse
import com.mj.api.controller.dto.StrategyEvaluateRequest
import com.mj.api.controller.dto.StrategyEvaluateResponse
import com.mj.api.controller.dto.StrategyParseResponse
import com.mj.api.controller.dto.StrategyValidateRequest
import com.mj.api.controller.dto.StrategyValidateResponse
import com.mj.api.controller.dto.toIndicatorRefDtos
import com.mj.api.controller.dto.toResponse
import com.mj.api.infrastructure.dsl.DslParseException
import com.mj.api.infrastructure.dsl.StrategyDslParser
import com.mj.api.infrastructure.dsl.StrategyDslPresets
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.strategy.dsl.StrategyDslEvaluator
import com.mj.domain.strategy.dsl.StrategyDslValidator
import com.mj.domain.strategy.dsl.ValidationResult
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Strategy DSL REST 엔드포인트 (트랙7 R1, ADR 0040).
 *
 * - GET  /api/strategies/presets        — KIS 정합 10 프리셋 (yaml + 메타)
 * - GET  /api/strategies/presets/{id}   — 프리셋 1건
 * - POST /api/strategies/validate       — yaml string → 검증 결과
 * - POST /api/strategies/parse          — yaml string → 도메인 모델 + normalized yaml
 * - POST /api/strategies/evaluate       — yaml + 캔들 → entry/exit 결과
 */
@Tag(
    name = "Strategy DSL",
    description = """
        Strategy DSL 관리 — `.kis.yaml` 호환 superset (ADR 0040).

        KIS Strategy Builder 표준 yaml을 그대로 import 가능 + magicJar 확장 4건
        (다중 timeframe / 시간 필터 / 종목 그룹 / 패턴 조건).

        흐름:
         1. /presets 로 사전 정의 10 프리셋 yaml 확보
         2. /validate 로 yaml 검증 (지표 id / 패턴 code / alias 정합)
         3. /parse 로 yaml → 도메인 모델 + 정규화된 yaml (round-trip 확인)
         4. /evaluate 로 캔들 + yaml → entry/exit boolean (백테스트·실시간 신호 엔진 입력)
    """,
)
@RestController
@RequestMapping("/api/strategies")
class StrategyController(
    private val parser: StrategyDslParser,
    private val presets: StrategyDslPresets,
    private val evaluator: StrategyDslEvaluator,
) {
    @Operation(
        summary = "프리셋 전략 목록",
        description = """
            KIS Strategy Builder 정합 10 프리셋을 반환한다. 각 프리셋은 원본 yaml + 도메인 메타
            (id·name·description·indicators·patterns·tags)를 함께 노출하므로 프론트엔드 빌더에서
            바로 로드 가능하다.

            10개 프리셋:
              1. golden_cross_5_20         — 이동평균 골든크로스
              2. rsi_momentum_14           — RSI 30/70 과매도·과매수
              3. ema_trend_filter          — EMA 60 위 + RSI 50 돌파
              4. week52_high_breakout      — 250일선 돌파 (52주 신고가 모방)
              5. consecutive_three_white_soldiers — 3 연속 양봉 패턴
              6. disparity_mean_reversion  — 이격도 95/105
              7. breakout_fail             — 볼린저 상단 돌파 후 회귀
              8. strong_close_volume_breakout — 강세 마감 + 거래량
              9. atr_volatility_breakout   — ATR 변동성 돌파
             10. bb_mean_reversion         — 볼린저 하단 + RSI 35 진입
        """,
    )
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "프리셋 10건")])
    @GetMapping("/presets")
    fun listPresets(): ResponseEntity<List<PresetResponse>> = ResponseEntity.ok(presets.all.map { it.toResponse() })

    @Operation(
        summary = "프리셋 단건 조회",
        description = "전략 id로 프리셋 1건을 조회한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "프리셋 1건"),
            ApiResponse(responseCode = "404", description = "해당 id 없음"),
        ],
    )
    @GetMapping("/presets/{id}")
    fun getPreset(
        @Parameter(description = "전략 id (snake_case)", example = "golden_cross_5_20")
        @PathVariable id: String,
    ): ResponseEntity<PresetResponse> {
        val entry = presets.byId(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(entry.toResponse())
    }

    @Operation(
        summary = "DSL 검증",
        description = """
            yaml string을 받아 검증한다. yaml 파싱 실패는 200 + parseError 메시지로,
            도메인 검증 실패는 200 + valid=false + errors[]로 응답한다 (모두 클라이언트가
            UI에 inline display 하기 위함, 4xx 아님).

            검증 항목 (12개):
             1. version 지원 (1.0+)
             2. metadata.name 비어있지 않음
             3. strategy.id 비어있지 않음
             4. indicators[*].id ∈ catalog (80개)
             5. patterns[*].code ∈ catalog (57개)
             6. alias 충돌 없음
             7. operand alias가 declared indicators 또는 catalog id
             8. pattern 조건이 declared patterns 또는 catalog code
             9. CROSS_ABOVE/BELOW는 시계열 LHS 필수 (RHS는 상수 허용 — RSI cross 50 등)
            10. logical group 깊이 ≤ 5
            11. group 노드 비어있지 않음
            12. risk 값 범위 (0~1)
        """,
    )
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "검증 결과")])
    @PostMapping("/validate")
    fun validate(
        @RequestBody request: StrategyValidateRequest,
    ): ResponseEntity<StrategyValidateResponse> {
        val parsed =
            try {
                parser.parse(request.yaml)
            } catch (e: DslParseException) {
                return ResponseEntity.ok(
                    StrategyValidateResponse(valid = false, errors = emptyList(), parseError = e.message),
                )
            } catch (e: IllegalArgumentException) {
                // 도메인 init 단계의 require 실패도 parse 에러로 취급
                return ResponseEntity.ok(
                    StrategyValidateResponse(valid = false, errors = emptyList(), parseError = e.message),
                )
            }
        val result = StrategyDslValidator.validate(parsed)
        return ResponseEntity.ok(result.toResponse())
    }

    @Operation(
        summary = "DSL 파싱",
        description = """
            yaml string을 받아 도메인 모델 + 정규화된 yaml(re-serialize)을 반환한다.
            양방향 변환(GUI 블록 빌더 ↔ yaml) 검증용. 파싱 자체가 실패하면 400.

            응답에는 검증 결과도 포함되어 한 번의 호출로 parse + validate 효과.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "파싱 + 검증 결과"),
            ApiResponse(responseCode = "400", description = "yaml 파싱 실패"),
        ],
    )
    @PostMapping("/parse")
    fun parse(
        @RequestBody request: StrategyValidateRequest,
    ): ResponseEntity<StrategyParseResponse> {
        val parsed =
            try {
                parser.parse(request.yaml)
            } catch (e: DslParseException) {
                return ResponseEntity.badRequest().build()
            } catch (e: IllegalArgumentException) {
                return ResponseEntity.badRequest().build()
            }
        val validation = StrategyDslValidator.validate(parsed)
        val normalized = parser.serialize(parsed)
        return ResponseEntity.ok(
            StrategyParseResponse(
                version = parsed.version,
                id = parsed.strategy.id,
                name = parsed.metadata.name,
                description = parsed.metadata.description,
                indicators = parsed.toIndicatorRefDtos(),
                patterns = parsed.strategy.patterns.map { it.code },
                normalizedYaml = normalized,
                validation = validation.toResponse(),
            ),
        )
    }

    @Operation(
        summary = "DSL 평가",
        description = """
            yaml + 캔들 시계열을 받아 currentIndex(기본값=마지막 봉) 위치에서
            entry/exit 그룹을 평가한다.

            응답에는 평가 trace(reasons)도 포함 — 각 조건의 lhs/rhs 실값 + 충족 여부를 사람이
            읽기 쉬운 형식으로 노출. UI에서 "왜 매수 신호가 나왔는가/안 나왔는가" 표시 가능.

            422 — 검증 실패 (유효하지 않은 전략은 평가 거부)
            400 — yaml 파싱 실패 / 캔들 비어있음 / index 범위 초과
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "평가 성공"),
            ApiResponse(responseCode = "400", description = "잘못된 입력"),
            ApiResponse(responseCode = "422", description = "검증 실패한 전략 — 평가 거부"),
        ],
    )
    @PostMapping("/evaluate")
    fun evaluate(
        @RequestBody request: StrategyEvaluateRequest,
    ): ResponseEntity<StrategyEvaluateResponse> {
        if (request.candles.isEmpty()) return ResponseEntity.badRequest().build()
        val parsed =
            try {
                parser.parse(request.yaml)
            } catch (e: Exception) {
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
                        code = "UNKNOWN",
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
            } catch (e: Exception) {
                return ResponseEntity.badRequest().build()
            }

        val currentIndex = request.currentIndex ?: candles.lastIndex
        if (currentIndex !in candles.indices) return ResponseEntity.badRequest().build()

        val result =
            try {
                evaluator.evaluate(parsed, candles, currentIndex)
            } catch (e: IllegalArgumentException) {
                return ResponseEntity.badRequest().build()
            } catch (e: Exception) {
                return ResponseEntity.unprocessableEntity().build()
            }

        return ResponseEntity.ok(result.toResponse(parsed.strategy.id))
    }
}
