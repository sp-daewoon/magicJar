package kr.spdaewoon.magicjar.web.controller

import kr.spdaewoon.magicjar.core.port.HarnessStateReader
import kr.spdaewoon.magicjar.web.config.AiEnabledFlag
import kr.spdaewoon.magicjar.web.service.ClaudeMdChangeLogReader
import kr.spdaewoon.magicjar.web.service.WorkspaceTreeReader
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 하네스 운영 상황 페이지 (`/harness`). ADR 0008 — 읽기 전용·로컬 한정.
 * `magicjar.harness.enabled=false` 면 빈이 등록되지 않아 404.
 */
@Controller
@ConditionalOnProperty(prefix = "magicjar.harness", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class HarnessController(
    private val harnessStateReader: HarnessStateReader,
    private val claudeMdChangeLogReader: ClaudeMdChangeLogReader,
    private val workspaceTreeReader: WorkspaceTreeReader,
    private val aiEnabledFlag: AiEnabledFlag,
) {
    @GetMapping("/harness")
    fun index(model: Model): String {
        val state = harnessStateReader.read()
        model.addAttribute("title", "하네스 운영")
        model.addAttribute("aiEnabled", aiEnabledFlag.enabled)
        model.addAttribute("serverTimeKst", ZonedDateTime.now(ZoneId.of("Asia/Seoul")))
        model.addAttribute("state", state)
        model.addAttribute("changelog", claudeMdChangeLogReader.recent(10))
        model.addAttribute("workspaceTree", workspaceTreeReader.snapshot())
        return "harness/index"
    }

    /**
     * `_workspace/dashboard.html`(scripts/render-status.mjs 산출물)을 그대로 서빙.
     * /harness 페이지의 "에이전트 대시보드" 탭이 iframe 으로 임베드한다.
     * 파일이 아직 없으면 안내 페이지를 반환.
     */
    @GetMapping("/harness/dashboard", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun dashboard(): String {
        val file = Path.of("_workspace/dashboard.html")
        return if (Files.exists(file)) {
            Files.readString(file)
        } else {
            """
            <!doctype html><html lang="ko"><body style="font-family:system-ui;padding:2rem;color:#475569">
                <h2 style="margin-top:0">대시보드 미생성</h2>
                <p><code>_workspace/dashboard.html</code> 파일이 아직 없습니다.</p>
                <p>터미널에서 <code>node scripts/render-status.mjs</code> 실행 후 새로고침하세요.</p>
            </body></html>
            """.trimIndent()
        }
    }
}
