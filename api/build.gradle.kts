plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":domain"))

    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // Kafka
    implementation("org.springframework.kafka:spring-kafka")

    // Flyway (api 모듈만 마이그레이션 — ADR 0006 §마이그레이션)
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // PostgreSQL driver
    implementation("org.postgresql:postgresql")

    // Swagger (사용자 1차 API 문서 — memory feedback_swagger_pattern 정합)
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    // Spring AI Anthropic (T-DSL 자연어→DSL 변환 helper — ADR 0026 사용자 트리거 옵션 한정)
    // 사용자 결재로 W2 진입. 매매 결정권 0 — REST endpoint에서 사용자가 명시 호출 시에만 활성화.
    // MAGICJAR_AI_ENABLED=false 시 503, MAGICJAR_AI_MOCK_MODE=auto + key 없을 때 mock 응답.
    implementation("org.springframework.ai:spring-ai-anthropic-spring-boot-starter") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    }

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveClassifier.set("")
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("plain")
}
