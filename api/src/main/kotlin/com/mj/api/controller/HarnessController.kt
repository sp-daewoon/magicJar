package com.mj.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path

/**
 * 하네스 운영 현황 (T22 R2).
 *
 * `_workspace/dashboard.html` 단일 파일 노출 — `node scripts/render-status.mjs`가 task 파일을 파싱해 생성.
 * frontend 개발일지 페이지의 "하네스" 탭이 본 endpoint를 iframe으로 렌더.
 */
@RestController
@RequestMapping("/api/harness")
@Tag(name = "Harness", description = "하네스 운영 현황 — dashboard.html iframe 노출")
class HarnessController(
    @Value("\${magicjar.harness.dashboard-path:_workspace/dashboard.html}")
    private val dashboardPath: String,
) {
    @Operation(
        summary = "하네스 운영 현황 HTML",
        description = "`_workspace/dashboard.html` raw 반환. iframe 렌더용.",
    )
    @GetMapping("/dashboard", produces = [MediaType.TEXT_HTML_VALUE])
    fun dashboard(): ResponseEntity<ByteArray> {
        val file = Path.of(dashboardPath)
        if (!Files.isRegularFile(file)) {
            throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Harness dashboard not found. Run `npm run status` to generate it.",
            )
        }
        val bytes = Files.readAllBytes(file)
        return ResponseEntity
            .ok()
            .contentType(MediaType.TEXT_HTML)
            .body(bytes)
    }
}
