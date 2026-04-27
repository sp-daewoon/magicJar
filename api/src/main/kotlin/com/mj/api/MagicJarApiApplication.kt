package com.mj.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["com.mj"])
@EntityScan(basePackages = ["com.mj"])
@EnableJpaRepositories(basePackages = ["com.mj"])
@EnableScheduling
class MagicJarApiApplication

fun main(args: Array<String>) {
    runApplication<MagicJarApiApplication>(*args)
}
