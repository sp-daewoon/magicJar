plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":core"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.ai:spring-ai-anthropic-spring-boot-starter")
    implementation("org.springframework.ai:spring-ai-ollama-spring-boot-starter")
}
