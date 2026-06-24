package kr.spdaewoon.magicjar.api.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * 초단타 정합 백테스트 properties wiring — ADR 0132 §4-A R2b.
 *
 * `@EnableConfigurationProperties(IntradayRealisticBacktestProperties::class)` 단일 책임 — 별도 Bean 0.
 * Spring Boot 3.3 `@ConfigurationProperties` constructor binding 자동 적용(ChartCandleConfiguration 패턴 정합).
 */
@Configuration
@EnableConfigurationProperties(IntradayRealisticBacktestProperties::class)
class IntradayRealisticBacktestConfiguration
