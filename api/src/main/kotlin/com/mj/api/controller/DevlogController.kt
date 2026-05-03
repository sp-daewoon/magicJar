package com.mj.api.controller

import com.mj.api.controller.dto.DevlogEntry
import com.mj.api.controller.dto.DevlogListResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * 개발일지 (devlog) 메뉴 backend API (T22 R1).
 *
 * 일자별 HTML 파일은 `_workspace/devlog/YYYY-MM-DD.html`에 영속.
 * `/devlog` 슬래시 커멘드(.claude/commands/devlog.md)가 작성, 본 controller가 frontend로 노출.
 *
 * Multi-tenant 비대상 — 모든 사용자가 같은 devlog 공유.
 *
 * Security: path traversal 차단 — date 정규식 `^\d{4}-\d{2}-\d{2}$` 강제.
 */
@RestController
@RequestMapping("/api/devlog")
@Tag(name = "Devlog", description = "개발일지 — 일자별 HTML 세션 요약 열람")
class DevlogController(
    @Value("\${magicjar.devlog.dir:_workspace/devlog}")
    private val devlogDir: String,
) {
    private val datePattern = Regex("""^(\d{4}-\d{2}-\d{2})\.html$""")
    private val dateOnly = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val koDayOfWeek =
        mapOf(
            DayOfWeek.MONDAY to "월",
            DayOfWeek.TUESDAY to "화",
            DayOfWeek.WEDNESDAY to "수",
            DayOfWeek.THURSDAY to "목",
            DayOfWeek.FRIDAY to "금",
            DayOfWeek.SATURDAY to "토",
            DayOfWeek.SUNDAY to "일",
        )

    @Operation(
        summary = "개발일지 목록",
        description = """
            `_workspace/devlog/YYYY-MM-DD.html` 파일을 일자 DESC로 반환.
            index.html은 list에 포함하지 않음 (메타 페이지).
            summary는 1차 null — R2에서 본문 첫 줄 또는 index.html 파싱으로 채움.
        """,
    )
    @GetMapping("/list")
    fun list(): DevlogListResponse {
        val dirFile = Path.of(devlogDir).toFile()
        if (!dirFile.isDirectory) return DevlogListResponse(emptyList())

        val entries =
            (dirFile.listFiles { f -> f.isFile && datePattern.matches(f.name) } ?: emptyArray())
                .map { f ->
                    val date = datePattern.matchEntire(f.name)!!.groupValues[1]
                    val day = LocalDate.parse(date).dayOfWeek
                    DevlogEntry(
                        date = date,
                        dayOfWeek = koDayOfWeek[day] ?: day.getDisplayName(TextStyle.SHORT, Locale.KOREAN),
                        mtime = java.time.Instant.ofEpochMilli(f.lastModified()),
                        sizeBytes = f.length(),
                        summary = null,
                    )
                }.sortedByDescending { it.date }
        return DevlogListResponse(entries)
    }

    @Operation(
        summary = "특정 일자 개발일지 HTML",
        description = "`_workspace/devlog/{date}.html` 파일 raw 반환. iframe 렌더용.",
    )
    @GetMapping("/{date}", produces = [MediaType.TEXT_HTML_VALUE])
    fun get(
        @Parameter(description = "일자 (YYYY-MM-DD, KST)", example = "2026-05-03", required = true)
        @PathVariable("date") date: String,
    ): ResponseEntity<ByteArray> {
        if (!dateOnly.matches(date)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date format. Expected YYYY-MM-DD.")
        }
        val file = Path.of(devlogDir, "$date.html").toAbsolutePath().normalize()
        val baseDir = Path.of(devlogDir).toAbsolutePath().normalize()
        if (!file.startsWith(baseDir)) {
            // path traversal 추가 가드 (이중 안전장치)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Path traversal blocked.")
        }
        if (!Files.isRegularFile(file)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Devlog not found: $date")
        }
        val bytes = Files.readAllBytes(file)
        return ResponseEntity
            .ok()
            .contentType(MediaType.TEXT_HTML)
            .body(bytes)
    }
}
