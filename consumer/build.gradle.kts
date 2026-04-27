plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":domain"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webflux") // KIS WS 클라이언트용
    implementation("org.springframework.boot:spring-boot-starter-websocket") // KisWsClient (Jakarta WS)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")

    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.ai:spring-ai-openai-spring-boot-starter")
    implementation("io.minio:minio:8.5.12")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:kafka:1.20.4")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.mockk:mockk:1.13.13")
}
