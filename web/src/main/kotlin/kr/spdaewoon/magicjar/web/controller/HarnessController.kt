package kr.spdaewoon.magicjar.web.controller

import kr.spdaewoon.magicjar.core.port.HarnessStateReader
import kr.spdaewoon.magicjar.web.config.AiEnabledFlag
import kr.spdaewoon.magicjar.web.service.ClaudeMdChangeLogReader
import kr.spdaewoon.magicjar.web.service.WorkspaceTreeReader
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
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
}
