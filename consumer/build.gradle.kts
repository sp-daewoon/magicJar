plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":domain"))
    // pattern-engine 라이브러리 의존 — JvmStrategyEvaluator가 patternRecognizer 빈 주입 (spec §4-1 LOCK)
    implementation(project(":pattern-engine"))
    // indicator-impl 라이브러리 의존 — IndicatorEngineConfig가 NativeIndicatorSource·KisStrategyBuilderSource 자동 collect (ADR 0083 + spec §4-5 LOCK)
    implementation(project(":indicator-impl"))
    // persistence-shared 라이브러리 의존 — Outbox/Stomp 어댑터를 api·consumer 양쪽 ApplicationContext에 노출 (ADR 0088 + spec §3 LOCK)
    implementation(project(":persistence-shared"))

    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webflux") // KIS REST/WS 클라이언트
    implementation("org.springframework.boot:spring-boot-starter-web") // @Scheduled enablement (dispatch §K)

    // Kafka (KafkaListener + KIS WS 데이터 발행)
    implementation("org.springframework.kafka:spring-kafka")

    // YAML — KisYamlDslParser .kis.yaml import (T-DSL · spec §8-8)
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // PostgreSQL driver
    implementation("org.postgresql:postgresql")

    // Flyway 미포함 (api 모듈만 마이그레이션)
    // LLM provider 의존 0건 (W4 트랙 한정 — CLAUDE.md 개발 룰 §5)

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveClassifier.set("")
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("plain")
}
