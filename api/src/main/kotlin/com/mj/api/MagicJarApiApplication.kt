package com.mj.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["com.mj"])
@EnableScheduling
class MagicJarApiApplication

fun main(args: Array<String>) {
    runApplication<MagicJarApiApplication>(*args)
}
