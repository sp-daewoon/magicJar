package kr.spdaewoon.magicjar.consumer.application.indicator

import kr.spdaewoon.magicjar.domain.indicator.IndicatorEngine
import kr.spdaewoon.magicjar.domain.indicator.port.IndicatorSource
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * IndicatorEngine bean 등록 — Spring autowire List<IndicatorSource>로 모든 등록된 source 자동 주입.
 *
 * 1차 등록: NativeIndicatorSource(priority=50) + KisStrategyBuilderSource(priority=100).
 * spec §6-2 LOCK + ADR 0021 §2-2 정합.
 *
 * spec §3-2 — consumer 모듈 책임. api 모듈은 별도 IndicatorEngineConfig 등록 (consumer dep 추가).
 */
@Configuration
class IndicatorEngineConfig {
    private val log = LoggerFactory.getLogger(IndicatorEngineConfig::class.java)

    @Bean
    fun indicatorEngine(sources: List<IndicatorSource>): IndicatorEngine {
        val engine = IndicatorEngine(sources)
        log.info(
            "Registered indicator sources: {}",
            engine.describeSources().joinToString(", ") { (n, p) -> "$n (priority=$p)" },
        )
        return engine
    }
}
