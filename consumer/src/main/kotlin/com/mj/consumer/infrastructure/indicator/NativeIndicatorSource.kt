package com.mj.consumer.infrastructure.indicator

import com.mj.domain.strategy.indicator.NativeIndicatorCalculator
import com.mj.domain.strategy.indicator.port.IndicatorSource
import org.springframework.stereotype.Component

/**
 * consumer 모듈 Spring 컴포넌트 — NativeIndicatorCalculator delegate (ADR 0039).
 */
@Component
class NativeIndicatorSource : IndicatorSource by NativeIndicatorCalculator()
