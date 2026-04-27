package com.mj.api.config

import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@Configuration
@EntityScan(basePackages = ["com.mj"])
@EnableJpaRepositories(basePackages = ["com.mj"])
class JpaConfig
