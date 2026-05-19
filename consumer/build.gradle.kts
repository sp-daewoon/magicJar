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

    // Bucket4j Rate Limiter — KIS REST 초당 10건 강제 (ADR 0037 + spec PR #564 §2-1 옵션 A LOCK)
    // multi-JVM 단일 bucket share — Lettuce Redis backend 직접 발행 (Spring Data Redis와 별도 client)
    // spec §2-3 옵션 a 정합 — api 모듈 Bean과 동일 bucket key 사용 (Redis-level share)
    implementation("com.bucket4j:bucket4j-core:8.10.1")
    implementation("com.bucket4j:bucket4j-redis:8.10.1")

    // Flyway 미포함 (api 모듈만 마이그레이션)
    // LLM provider 의존 0건 (W4 트랙 한정 — CLAUDE.md 개발 룰 §5)

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    // mockito-kotlin — ConsumerRedisKisRateLimiterAdapterTest 사용 (api 모듈 패턴 1:1 정합)
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveClassifier.set("")
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("plain")
}
