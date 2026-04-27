pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "magicjar"

include(
    // === 신규 (P1~) ===
    "domain",
    "api",
    "batch",
    "consumer",

    // === 구 (P1 Task 1.10에서 일괄 제거 예정) ===
    "app",
    "core",
    "market-data",
    "strategy",
    "ai",
    "order-exec",
    "web",
)
