package com.mj.consumer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.kafka.annotation.EnableKafka

@SpringBootApplication(scanBasePackages = ["com.mj"])
@EntityScan(basePackages = ["com.mj"])
@EnableJpaRepositories(basePackages = ["com.mj"])
@EnableKafka
class MagicJarConsumerApplication

fun main(args: Array<String>) {
    runApplication<MagicJarConsumerApplication>(*args)
}
