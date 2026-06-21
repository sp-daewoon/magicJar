package kr.spdaewoon.magicjar.api.autopilot

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * V92 autopilot kill 저널 + lifetime 컬럼(`V92__autopilot_kill_journal.sql`) 구조 무결성 테스트.
 *
 * ### 검증 대상 (spec 2026-06-22 §2-0-B·§2-0-D · Phase 0 Task 4)
 * - `autopilot_state.lifetime_realized_loss` ADD COLUMN IF NOT EXISTS(하드 플로어 — kill-reset 에도 보존).
 * - `autopilot_kill_journal` 테이블 IF NOT EXISTS + 사후분석 컬럼(손실·한도·held_codes·resume 갱신).
 * - 폐기 철학: autopilot_ prefix 격리 + ROLLBACK 노트(DROP TABLE / DROP COLUMN) 동봉.
 *
 * Testcontainers 없이 SQL 텍스트만 검증(V91 선례 정합). AI 호출 0 · mock 0.
 */
class V92AutopilotKillJournalMigrationTest {
    private val sqlText: String by lazy {
        val f = locateMigration()
        assertTrue(f.exists(), "V92 마이그레이션 파일을 찾지 못했습니다: ${f.absolutePath}")
        f.readText()
    }

    @Test
    fun `V92는 lifetime_realized_loss를 ADD COLUMN IF NOT EXISTS로 추가한다`() {
        assertTrue(
            Regex(
                "ALTER TABLE autopilot_state\\s+ADD COLUMN IF NOT EXISTS lifetime_realized_loss\\s+NUMERIC",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).containsMatchIn(sqlText),
            "lifetime_realized_loss ADD COLUMN IF NOT EXISTS 절이 없습니다.",
        )
    }

    @Test
    fun `V92는 autopilot_kill_journal 테이블을 IF NOT EXISTS로 생성한다`() {
        assertTrue(
            Regex("CREATE TABLE IF NOT EXISTS autopilot_kill_journal", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "autopilot_kill_journal CREATE TABLE IF NOT EXISTS 절이 없습니다.",
        )
    }

    @Test
    fun `kill 저널은 사후분석 스냅샷 컬럼을 갖는다`() {
        // 손실(cum/daily/lifetime) + 격리(paused_strategy_count/held_codes) + 한도 스냅샷 + resume 갱신.
        listOf(
            "occurred_at",
            "reason",
            "cum_realized_loss",
            "daily_realized_loss",
            "lifetime_realized_loss",
            "paused_strategy_count",
            "held_codes",
            "pool_pct",
            "resumed_at",
            "resume_reason",
            "corrected_limits_json",
        ).forEach { col ->
            assertTrue(sqlText.contains(col), "kill 저널 컬럼 누락: $col")
        }
    }

    @Test
    fun `V92는 폐기 철학 ROLLBACK 노트를 동봉한다`() {
        assertTrue(sqlText.contains("ROLLBACK"), "ROLLBACK 노트가 없습니다(폐기 철학 의무).")
        assertTrue(
            Regex("DROP TABLE IF EXISTS autopilot_kill_journal", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "autopilot_kill_journal DROP TABLE 되돌림 노트가 없습니다.",
        )
        assertTrue(
            Regex("DROP COLUMN IF EXISTS lifetime_realized_loss", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "lifetime_realized_loss DROP COLUMN 되돌림 노트가 없습니다.",
        )
        // 기존 매매 스키마 무손상(zero-regression).
        assertFalse(
            Regex("DROP TABLE strategy\\b", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "기존 strategy 테이블을 폐기 대상으로 삼으면 안 됩니다.",
        )
    }

    private fun locateMigration(): File {
        val rel = "api/src/main/resources/db/migration/$MIGRATION_NAME"
        val relInModule = "src/main/resources/db/migration/$MIGRATION_NAME"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            File(dir, rel).let { if (it.exists()) return it }
            File(dir, relInModule).let { if (it.exists()) return it }
            dir = dir.parentFile
        }
        return File(relInModule)
    }

    private companion object {
        const val MIGRATION_NAME = "V92__autopilot_kill_journal.sql"
    }
}
