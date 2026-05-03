plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-annotations")
    api("com.fasterxml.jackson.module:jackson-module-kotlin")
    api("org.slf4j:slf4j-api")

    api("org.springframework.ai:spring-ai-core")
    api("org.springframework.ai:spring-ai-openai")

    api("jakarta.persistence:jakarta.persistence-api:3.1.0")

    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
}
