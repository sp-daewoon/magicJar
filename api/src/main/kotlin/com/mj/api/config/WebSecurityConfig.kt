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
        return http.build()
    }
}
