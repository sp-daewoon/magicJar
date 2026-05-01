package com.mj.consumer

import com.mj.consumer.application.mock.MockTickProperties
import com.mj.consumer.config.ai.AiProperties
import com.mj.consumer.infrastructure.kis.KisProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["com.mj"])
@EntityScan(basePackages = ["com.mj"])
@EnableJpaRepositories(basePackages = ["com.mj"])
@EnableKafka
@EnableScheduling
@EnableConfigurationProperties(KisProperties::class, AiProperties::class, MockTickProperties::class)
class MagicJarConsumerApplication

fun main(args: Array<String>) {
    runApplication<MagicJarConsumerApplication>(*args)
}
