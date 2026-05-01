package com.mj.consumer.application.guard

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * GuardChain Bean 등록. Spring이 @Order(100/200/300)에 따라 List<OrderGuard>를 자동 정렬해서 주입.
 *
 * 주의: GuardChain 자신은 OrderGuard를 구현하지만 Bean으로 등록되지 않도록 List 인자에서 제외해야 한다 —
 * @Component를 GuardChain에 붙이면 자기 자신을 list에 포함하려는 순환 의존이 생김. 그래서 @Bean 형태로만.
 */
@Configuration
class GuardChainConfig {
    @Bean
    fun guardChain(guards: List<OrderGuard>): GuardChain = GuardChain(guards)
}
