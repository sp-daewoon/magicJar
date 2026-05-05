plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":domain"))

    implementation("org.springframework.boot:spring-boot-starter-batch")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // actuator/health 노출을 위해 servlet web 부착 (jobs는 spring.batch.job.enabled=false로 차단)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    implementation("org.springframework.kafka:spring-kafka") // 결과 publish
    implementation("org.springframework.ai:spring-ai-openai-spring-boot-starter")

    // Redisson — KIS 토큰 분산 single-flight (R3-5c-3 PR2). default 비활성, PR4에서 활성화.
    // batch는 spring-data-redis 미부착 — Redisson 자체 connection 풀 사용 (config는 RedisProperties 재사용).
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.redisson:redisson-spring-boot-starter:3.40.2")

    // OpenAPI / Swagger UI — /swagger-ui/index.html, /v3/api-docs
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.batch:spring-batch-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("com.github.tomakehurst:wiremock-standalone:3.0.1")
}
