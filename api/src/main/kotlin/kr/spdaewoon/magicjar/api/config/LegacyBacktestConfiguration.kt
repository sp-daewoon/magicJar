package kr.spdaewoon.magicjar.api.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * LEGACY 백테스트 정합 옵션 properties wiring — 2026-07-03 감사 §6 P2-1·P2-2.
 *
 * `@EnableConfigurationProperties(LegacyBacktestProperties::class)` 단일 책임 — 별도 Bean 0.
 * Spring Boot 3.3 `@ConfigurationProperties` constructor binding 자동 적용(IntradayRealisticBacktestConfiguration 패턴 정합).
 */
@Configuration
@EnableConfigurationProperties(LegacyBacktestProperties::class)
class LegacyBacktestConfiguration
