package com.mj.batch.config

import com.mj.domain.market.corp_action.AdjustedPriceCalculator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Corp Action 관련 Bean 설정.
 *
 * AdjustedPriceCalculator는 순수 계산 클래스 (Spring 의존 없음).
 * batch/consumer 모듈에서 공통으로 사용 가능하도록 Bean으로 등록.
 */
@Configuration
class CorpActionConfig {
    @Bean
    fun adjustedPriceCalculator(): AdjustedPriceCalculator = AdjustedPriceCalculator()
}
