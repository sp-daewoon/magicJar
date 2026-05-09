package kr.spdaewoon.magicjar.domain.indicator

/**
 * 지표 계산 예외 계층 (ADR 0021 §2-1, spec §8-4).
 *
 * REST 컨트롤러는 UnsupportedSpecException → 400, InsufficientCandlesException → 400 처리.
 */
sealed class IndicatorException(
    message: String,
) : RuntimeException(message) {
    /** 미지원 지표 ID 요청 시 (spec §8-4 "No source supports {name}"). */
    class UnsupportedSpecException(
        name: String,
    ) : IndicatorException("No source supports $name")

    /** 필요 캔들 개수 미달 시 (spec §8-4). */
    class InsufficientCandlesException(
        required: Int,
        actual: Int,
    ) : IndicatorException("Insufficient candles: required=$required, actual=$actual")

    /** 계산 실패 시 (내부 오류 래핑). */
    class CalculationFailureException(
        spec: IndicatorSpec,
        cause: Throwable,
    ) : IndicatorException("Calculation failed for ${spec.name}: ${cause.message}")
}
