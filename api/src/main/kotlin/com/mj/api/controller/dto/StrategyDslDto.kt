package com.mj.api.controller.dto

import com.mj.api.infrastructure.dsl.PresetEntry
import com.mj.domain.strategy.dsl.DslError
import com.mj.domain.strategy.dsl.StrategyDsl
import com.mj.domain.strategy.dsl.StrategyEvalResult
import com.mj.domain.strategy.dsl.ValidationResult
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

// ────────────────────────────────────────────────────────────
// Preset 응답
// ────────────────────────────────────────────────────────────

@Schema(description = "프리셋 전략 1건 — yaml + 파싱된 도메인 표현 동시 노출")
data class PresetResponse(
    @Schema(description = "전략 id (snake_case)", example = "golden_cross_5_20")
    val id: String,
    @Schema(description = "사용자 표시 이름", example = "골든크로스 5/20")
    val name: String,
    @Schema(description = "한글 설명", example = "5일·20일 이동평균 골든크로스 진입 + 데드크로스 청산")
    val description: String?,
    @Schema(description = "리소스 파일명", example = "01-golden-cross.yaml")
    val file: String,
    @Schema(description = "원본 yaml 문자열")
    val yaml: String,
    @Schema(description = "태그", example = "[\"trend_following\", \"ma_cross\"]")
    val tags: List<String>,
    @Schema(description = "사용 지표 id 목록", example = "[\"ma\"]")
    val indicators: List<String>,
    @Schema(description = "사용 패턴 코드 목록", example = "[]")
    val patterns: List<String>,
)

fun PresetEntry.toResponse(): PresetResponse =
    PresetResponse(
        id = dsl.strategy.id,
        name = dsl.metadata.name,
        description = dsl.metadata.description,
        file = file,
        yaml = yaml,
        tags = dsl.metadata.tags,
        indicators = dsl.strategy.indicators.map { it.id },
        patterns = dsl.strategy.patterns.map { it.code },
    )

// ────────────────────────────────────────────────────────────
// Validate 응답
// ────────────────────────────────────────────────────────────

@Schema(description = "DSL 검증 요청 — yaml string")
data class StrategyValidateRequest(
    @Schema(description = "yaml content", example = "version: \"1.0\"\nmetadata:\n  name: my_strategy\n...")
    val yaml: String,
)

@Schema(description = "DSL 검증 결과")
data class StrategyValidateResponse(
    @Schema(description = "검증 통과 여부", example = "true")
    val valid: Boolean,
    @Schema(description = "검증 오류 목록 (valid=false인 경우)")
    val errors: List<ValidationErrorDto>,
    @Schema(description = "yaml 파싱 자체가 실패한 경우 메시지", example = "invalid yaml: ...")
    val parseError: String?,
)

@Schema(description = "DSL 검증 오류 1건")
data class ValidationErrorDto(
    @Schema(description = "필드 경로", example = "strategy.entry.nodes[0].lhs")
    val path: String,
    @Schema(description = "오류 코드", example = "UNKNOWN_INDICATOR")
    val code: String,
    @Schema(description = "사람용 메시지", example = "indicator id 'foo' not in catalog")
    val message: String,
)

fun ValidationResult.toResponse(): StrategyValidateResponse =
    when (this) {
        ValidationResult.Ok ->
            StrategyValidateResponse(valid = true, errors = emptyList(), parseError = null)
        is ValidationResult.Errors ->
            StrategyValidateResponse(
                valid = false,
                errors = errors.map { it.toDto() },
                parseError = null,
            )
    }

fun DslError.toDto(): ValidationErrorDto = ValidationErrorDto(path = path, code = code, message = message)

// ────────────────────────────────────────────────────────────
// Parse 응답 — yaml → 도메인 모델 (간략 표현)
// ────────────────────────────────────────────────────────────

@Schema(description = "yaml 파싱 결과 — 검증 + 정규화된 yaml + 메타 정보")
data class StrategyParseResponse(
    @Schema(description = "스키마 버전")
    val version: String,
    @Schema(description = "전략 id")
    val id: String,
    @Schema(description = "사용자 표시 이름")
    val name: String,
    @Schema(description = "한글 설명")
    val description: String?,
    @Schema(description = "사용 지표 alias·id·params")
    val indicators: List<IndicatorRefDto>,
    @Schema(description = "사용 패턴 코드")
    val patterns: List<String>,
    @Schema(description = "정규화된 yaml (re-serialize 결과)")
    val normalizedYaml: String,
    @Schema(description = "검증 결과")
    val validation: StrategyValidateResponse,
)

@Schema(description = "지표 참조 (IndicatorRef)")
data class IndicatorRefDto(
    @Schema(description = "IndicatorCatalog id", example = "ma")
    val id: String,
    @Schema(description = "조건식 alias", example = "ma5")
    val alias: String?,
    @Schema(description = "파라미터 Map", example = "{\"period\": 5}")
    val params: Map<String, Any>,
    @Schema(description = "조건별 timeframe", example = "D1")
    val timeframe: String?,
)

// ────────────────────────────────────────────────────────────
// Evaluate 요청·응답
// ────────────────────────────────────────────────────────────

@Schema(description = "DSL 평가 요청 — yaml + 캔들")
data class StrategyEvaluateRequest(
    @Schema(description = "yaml content")
    val yaml: String,
    @Schema(description = "캔들 시계열 (시간 오름차순)")
    val candles: List<CandleInputDto>,
    @Schema(description = "평가 기준 인덱스 (미지정 시 마지막 봉)", example = "99")
    val currentIndex: Int? = null,
)

@Schema(description = "DSL 평가 결과")
data class StrategyEvaluateResponse(
    @Schema(description = "전략 id", example = "golden_cross_5_20")
    val strategyId: String,
    @Schema(description = "평가 기준 봉의 epoch second", example = "1704067200")
    val evaluatedAt: Long,
    @Schema(description = "entry 그룹 평가 결과", example = "true")
    val entry: Boolean,
    @Schema(description = "exit 그룹 평가 결과", example = "false")
    val exit: Boolean,
    @Schema(description = "entry 평가 trace (디버그용)")
    val entryReasons: List<EvalReasonDto>,
    @Schema(description = "exit 평가 trace")
    val exitReasons: List<EvalReasonDto>,
)

@Schema(description = "단일 평가 흔적")
data class EvalReasonDto(
    @Schema(description = "평가 종류 — compare / cross / pattern / miss", example = "compare")
    val kind: String,
    @Schema(description = "사람용 설명", example = "[idx=99] ma5=70.4 > ma20=68.2")
    val description: String,
    @Schema(description = "조건 충족 여부", example = "true")
    val satisfied: Boolean,
)

fun StrategyEvalResult.toResponse(strategyId: String): StrategyEvaluateResponse =
    StrategyEvaluateResponse(
        strategyId = strategyId,
        evaluatedAt = evaluatedAt.epochSecond,
        entry = entry,
        exit = exit,
        entryReasons = entryReasons.map { EvalReasonDto(it.kind, it.description, it.satisfied) },
        exitReasons = exitReasons.map { EvalReasonDto(it.kind, it.description, it.satisfied) },
    )

internal fun StrategyDsl.toIndicatorRefDtos(): List<IndicatorRefDto> =
    strategy.indicators.map { ref ->
        IndicatorRefDto(
            id = ref.id,
            alias = ref.alias,
            params =
                ref.params.mapValues { (_, v) ->
                    // BigDecimal 직렬화 안전 — Number는 그대로, String 등은 그대로
                    if (v is BigDecimal) v.toPlainString() as Any else v
                },
            timeframe = ref.timeframe?.name,
        )
    }
