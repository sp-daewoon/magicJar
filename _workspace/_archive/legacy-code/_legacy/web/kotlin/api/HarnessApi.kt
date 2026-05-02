package kr.spdaewoon.magicjar.web.api

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.core.domain.HarnessState
import kr.spdaewoon.magicjar.core.port.HarnessStateReader
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/harness")
@ConditionalOnProperty(prefix = "magicjar.harness", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@Tag(name = "Harness", description = "하네스 운영 상태 JSON (읽기 전용, 로컬 한정 — ADR 0008)")
class HarnessApi(
    private val harnessStateReader: HarnessStateReader,
) {
    @Operation(summary = "하네스 상태 스냅샷")
    @GetMapping("/state")
    fun state(): HarnessState = harnessStateReader.read()
}
