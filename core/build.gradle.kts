plugins {
    kotlin("jvm")
}

dependencies {
    // core는 순수 Kotlin 라이브러리: Spring/JPA 의존 금지.
    // 직렬화 어노테이션과 로깅 파사드만 허용.
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    implementation("org.slf4j:slf4j-api")
}
