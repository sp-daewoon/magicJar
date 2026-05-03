package com.mj.domain.strategy.indicator.port

import com.mj.domain.common.Candle
import com.mj.domain.strategy.indicator.IndicatorDescriptor
import com.mj.domain.strategy.indicator.IndicatorResult
import com.mj.domain.strategy.indicator.IndicatorSpec

/**
 * 기술지표 계산 소스 추상화 (ADR 0039).
 *
 * 구현체:
 * - NativeIndicatorSource (priority=50)  — Kotlin 자체 구현 핵심 지표
 * - KisStrategyBuilderSource (priority=100) — KIS Strategy Builder 80지표 메타 + 계산
 * - TaLibSource (priority=200)           — (post-W2) TA-Lib JNI
 * - KrxSource (priority=300)             — (post-MVP) KRX 공식 지표
 */
interface IndicatorSource {
    /** source 식별자 — "native" · "kis-strategy-builder" · "ta-lib" · "krx" */
    val name: String

    /**
     * 우선순위 — 같은 spec을 지원하는 source 중 낮은 priority가 먼저 선택된다.
     * 예) native(50) < kis-strategy-builder(100) < ta-lib(200)
     */
    val priority: Int

    /** 이 source가 지원하는 모든 지표 descriptor 목록 */
    fun availableIndicators(): List<IndicatorDescriptor>

    /**
     * 본 source가 spec을 지원하는지 여부.
     * name 대소문자 비교는 구현체 책임.
     */
    fun supports(spec: IndicatorSpec): Boolean

    /**
     * 지표 계산.
     *
     * @throws IndicatorComputeException spec을 지원하지 않거나 계산 실패 시
     */
    fun calculate(
        spec: IndicatorSpec,
        candles: List<Candle>,
    ): IndicatorResult
}

/** 지표 계산 중 발생하는 예외 */
class IndicatorComputeException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
