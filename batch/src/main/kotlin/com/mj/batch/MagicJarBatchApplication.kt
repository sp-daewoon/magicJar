package com.mj.batch

import com.mj.batch.infrastructure.kis.KisRestProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["com.mj"])
@EntityScan(basePackages = ["com.mj"])
@EnableJpaRepositories(basePackages = ["com.mj"])
@EnableConfigurationProperties(KisRestProperties::class)
@EnableScheduling
class MagicJarBatchApplication

fun main(args: Array<String>) {
    runApplication<MagicJarBatchApplication>(*args)
}
