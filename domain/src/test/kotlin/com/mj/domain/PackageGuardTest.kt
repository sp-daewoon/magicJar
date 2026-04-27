package com.mj.domain

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

class PackageGuardTest {
    @Test
    fun `domain 모듈은 6 서브 패키지 디렉토리를 가진다`() {
        val baseDir = File("src/main/kotlin/com/mj/domain")
        baseDir.exists() shouldBe true
        val subDirs =
            baseDir
                .listFiles { f -> f.isDirectory }
                .orEmpty()
                .map { it.name }
        subDirs shouldContainAll listOf("common", "market", "strategy", "ai", "order", "journal")
    }

    @Test
    fun `domain 공통 패키지에 최소 1개의 Kotlin 소스가 존재한다`() {
        val commonDir = File("src/main/kotlin/com/mj/domain/common")
        val ktFiles = commonDir.walk().filter { it.extension == "kt" }.toList()
        (ktFiles.size > 0) shouldBe true
    }
}
