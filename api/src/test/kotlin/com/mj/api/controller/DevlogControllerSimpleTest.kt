package com.mj.api.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path

/**
 * DevlogController 단위 테스트.
 *
 * - list (빈 / 일자 파일 / index·noise 제외 / 최신 우선)
 * - get (200·HTML / 404·존재 X / 400·잘못된 date 형식)
 *
 * `magicjar.devlog.dir`을 임시 디렉토리로 격리.
 */
class DevlogControllerSimpleTest {
    @Test
    fun `list 빈 디렉토리는 빈 리스트`(
        @TempDir tempDir: Path,
    ) {
        val controller = DevlogController(devlogDir = tempDir.toString())
        val resp = controller.list()
        assertEquals(0, resp.entries.size)
    }

    @Test
    fun `list 일자 파일 발견 + index·noise 제외 + 최신 우선`(
        @TempDir tempDir: Path,
    ) {
        Files.writeString(tempDir.resolve("2026-05-03.html"), "<html>3</html>")
        Files.writeString(tempDir.resolve("2026-05-02.html"), "<html>2</html>")
        Files.writeString(tempDir.resolve("index.html"), "<html>idx</html>")
        Files.writeString(tempDir.resolve("notdate.html"), "<html>x</html>")

        val controller = DevlogController(devlogDir = tempDir.toString())
        val resp = controller.list()

        assertEquals(2, resp.entries.size)
        assertEquals("2026-05-03", resp.entries[0].date)
        assertEquals("2026-05-02", resp.entries[1].date)
        assertEquals("일", resp.entries[0].dayOfWeek) // 2026-05-03
        assertEquals("토", resp.entries[1].dayOfWeek) // 2026-05-02
    }

    @Test
    fun `get 잘못된 date 형식 BAD_REQUEST`(
        @TempDir tempDir: Path,
    ) {
        val controller = DevlogController(devlogDir = tempDir.toString())
        val ex =
            assertThrows(ResponseStatusException::class.java) {
                controller.get("2026-5-3")
            }
        assertEquals(400, ex.statusCode.value())
    }

    @Test
    fun `get 존재하지 않는 일자 NOT_FOUND`(
        @TempDir tempDir: Path,
    ) {
        val controller = DevlogController(devlogDir = tempDir.toString())
        val ex =
            assertThrows(ResponseStatusException::class.java) {
                controller.get("2099-12-31")
            }
        assertEquals(404, ex.statusCode.value())
    }

    @Test
    fun `get 정상 HTML 응답`(
        @TempDir tempDir: Path,
    ) {
        val html = "<html><body>2026-05-03 devlog</body></html>"
        Files.writeString(tempDir.resolve("2026-05-03.html"), html)

        val controller = DevlogController(devlogDir = tempDir.toString())
        val resp = controller.get("2026-05-03")

        assertEquals(200, resp.statusCode.value())
        val body = String(resp.body!!, Charsets.UTF_8)
        assertTrue(body.contains("2026-05-03 devlog"))
    }
}
