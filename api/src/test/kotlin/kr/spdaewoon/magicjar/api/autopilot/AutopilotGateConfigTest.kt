package kr.spdaewoon.magicjar.api.autopilot

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * autopilot 게이트 config(`magicjar.autopilot.*`) 등록·default 검증 — Phase 0 Task 1.
 *
 * ### 핵심 계약 (spec 2026-06-22 §3-bis Tier A — 폐기 철학)
 * - `MAGICJAR_AUTOPILOT_ENABLED` default **false** → 미설정 시 autopilot 전 기능 비활성 = zero-regression.
 * - 5건 한도 ENV(pool/per-trade/max-positions/daily/cum)가 보수적 default와 함께 등록.
 *
 * application.yml 텍스트만 검증(가벼운 단위 — Spring 부팅·DB 불필요). AI 0 · mock 0.
 */
class AutopilotGateConfigTest {
    private val ymlText: String by lazy {
        val f = locate("api/src/main/resources/application.yml", "src/main/resources/application.yml")
        assertTrue(f.exists(), "application.yml을 찾지 못했습니다: ${f.absolutePath}")
        f.readText()
    }

    @Test
    fun `MAGICJAR_AUTOPILOT_ENABLED는 default false로 등록된다 (zero-regression)`() {
        assertTrue(
            Regex("enabled:\\s*\\$\\{MAGICJAR_AUTOPILOT_ENABLED:false\\}").containsMatchIn(ymlText),
            "MAGICJAR_AUTOPILOT_ENABLED default false 게이트가 application.yml에 없습니다.",
        )
    }

    @Test
    fun `5건 한도 ENV가 보수적 default와 함께 등록된다`() {
        assertTrue(ymlText.contains("\${MAGICJAR_AUTOPILOT_POOL_PCT:5}"), "POOL_PCT default 5 누락")
        assertTrue(ymlText.contains("\${MAGICJAR_AUTOPILOT_PER_TRADE_PCT:20}"), "PER_TRADE_PCT default 20 누락")
        assertTrue(ymlText.contains("\${MAGICJAR_AUTOPILOT_MAX_POSITIONS:3}"), "MAX_POSITIONS default 3 누락")
        assertTrue(ymlText.contains("\${MAGICJAR_AUTOPILOT_DAILY_LOSS_PCT:30}"), "DAILY_LOSS_PCT default 30 누락")
        assertTrue(ymlText.contains("\${MAGICJAR_AUTOPILOT_CUM_LOSS_PCT:50}"), "CUM_LOSS_PCT default 50 누락")
    }

    private fun locate(
        rel: String,
        relInModule: String,
    ): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            File(dir, rel).let { if (it.exists()) return it }
            File(dir, relInModule).let { if (it.exists()) return it }
            dir = dir.parentFile
        }
        return File(relInModule)
    }
}
