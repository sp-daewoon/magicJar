package com.mj.api.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain

// Phase E1 security config: all /api/** permitAll. Real auth (JWT/OAuth2) is Phase F+.
@Configuration
@EnableWebSecurity
class WebSecurityConfig {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { auth ->
                auth.anyRequest().permitAll()
            }.csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            // Devlog HTML이 frontend(localhost:3000)의 iframe에서 렌더돼야 함.
            // Spring Security default DENY와 nginx의 SAMEORIGIN이 충돌해 브라우저가 deny로 fallback하던 문제 해소.
            .headers { headers ->
                headers.frameOptions { it.sameOrigin() }
            }
        return http.build()
    }
}
