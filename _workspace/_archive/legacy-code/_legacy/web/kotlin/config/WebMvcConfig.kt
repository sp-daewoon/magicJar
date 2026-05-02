package kr.spdaewoon.magicjar.web.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * MVP용 최소 WebMvc 설정.
 * - 한국어 locale은 application-web.yml 의 spring.web.locale 로 고정(ko_KR).
 * - `/` 는 HomeController가 담당하므로 여기서는 별도 뷰 매핑 없음.
 */
@Configuration
class WebMvcConfig(
    @Value("\${magicjar.ai.enabled:false}") private val aiEnabled: Boolean,
) : WebMvcConfigurer {
    override fun addViewControllers(registry: ViewControllerRegistry) {
        // Swagger UI는 Springdoc가 자동 매핑. 추가 뷰 없음.
    }

    @Bean
    fun aiEnabledFlag(): AiEnabledFlag = AiEnabledFlag(aiEnabled)
}

/** 컨트롤러가 공통으로 주입받아 뷰에 `aiEnabled` 모델을 넘길 때 사용. */
data class AiEnabledFlag(
    val enabled: Boolean,
)
