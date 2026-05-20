package kr.spdaewoon.magicjar.consumer.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * 차트 캔들 properties wiring (ADR 0093 §3-1 LOCK · consumer 모듈 mirror).
 *
 * api 모듈 [kr.spdaewoon.magicjar.api.config.ChartCandleConfiguration]와 1:1.
 */
@Configuration
@EnableConfigurationProperties(ChartCandleProperties::class)
class ChartCandleConfiguration
