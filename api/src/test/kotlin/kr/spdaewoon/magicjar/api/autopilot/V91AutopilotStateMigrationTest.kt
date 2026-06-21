package kr.spdaewoon.magicjar.api.autopilot

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * V91 autopilot 안전 하니스 마이그레이션(`V91__autopilot_state.sql`) 구조 무결성 테스트.
 *
 * ### 검증 대상 (spec 2026-06-22 §2-0-A · §3-bis Tier A)
 * - `autopilot_state` 테이블 IF NOT EXISTS 생성 + seed 1행(account-wide id=1).
 * - 회로차단기 한도 default 컬럼(pool/per-trade/max-positions/daily/cum) = 보수적 값.
 * - kill_active default false(zero-regression — 미설정 안전측).
 * - 폐기 철학: autopilot_ prefix 격리 + 하단 ROLLBACK 노트(DROP TABLE) 동봉.
 *
 * Testcontainers 없이 SQL 파일 텍스트만 검증(가벼운 단위 테스트 — V76·V80 선례 정합).
 * AI 호출 0 · mock 0.
 */
class V91AutopilotStateMigrationTest {
    private val sqlText: String by lazy {
        val f = locateMigration()
        assertTrue(f.exists(), "V91 마이그레이션 파일을 찾지 못했습니다: ${f.absolutePath}")
        f.readText()
    }

    @Test
    fun `V91은 autopilot_state 테이블을 IF NOT EXISTS로 생성한다`() {
        assertTrue(
            Regex("CREATE TABLE IF NOT EXISTS autopilot_state", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "autopilot_state CREATE TABLE IF NOT EXISTS 절이 없습니다.",
        )
        // autopilot_ prefix 격리(폐기 철학 Tier A).
        assertTrue(sqlText.contains("autopilot_state"), "autopilot_ prefix 테이블명 격리가 없습니다.")
    }

    @Test
    fun `kill_active는 default false다 (zero-regression 안전측)`() {
        assertTrue(
            Regex("kill_active\\s+BOOLEAN\\s+NOT NULL\\s+DEFAULT false", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "kill_active BOOLEAN NOT NULL DEFAULT false 컬럼이 없습니다.",
        )
    }

    @Test
    fun `회로차단기 한도 default가 스펙 2-0-A 보수적 값과 일치한다`() {
        // 풀5% / 건당20% / 동시3 / 일일30% / 누적50%
        assertTrue(
            Regex("pool_pct\\s+NUMERIC\\([0-9, ]+\\)\\s+NOT NULL\\s+DEFAULT 5", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "pool_pct default 5 누락",
        )
        assertTrue(
            Regex("per_trade_pct\\s+NUMERIC\\([0-9, ]+\\)\\s+NOT NULL\\s+DEFAULT 20", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "per_trade_pct default 20 누락",
        )
        assertTrue(
            Regex("max_positions\\s+INTEGER\\s+NOT NULL\\s+DEFAULT 3", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "max_positions default 3 누락",
        )
        assertTrue(
            Regex("daily_loss_pct\\s+NUMERIC\\([0-9, ]+\\)\\s+NOT NULL\\s+DEFAULT 30", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "daily_loss_pct default 30 누락",
        )
        assertTrue(
            Regex("cum_loss_pct\\s+NUMERIC\\([0-9, ]+\\)\\s+NOT NULL\\s+DEFAULT 50", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "cum_loss_pct default 50 누락",
        )
    }

    @Test
    fun `손실 추적 컬럼은 default 0이고 last_reset_date를 둔다`() {
        assertTrue(
            Regex("cum_realized_loss\\s+NUMERIC\\([0-9, ]+\\)\\s+NOT NULL\\s+DEFAULT 0", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "cum_realized_loss default 0 누락",
        )
        assertTrue(
            Regex(
                "daily_realized_loss\\s+NUMERIC\\([0-9, ]+\\)\\s+NOT NULL\\s+DEFAULT 0",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(sqlText),
            "daily_realized_loss default 0 누락",
        )
        assertTrue(sqlText.contains("last_reset_date"), "last_reset_date 컬럼 누락")
    }

    @Test
    fun `V91은 seed 단일 행을 멱등 INSERT한다`() {
        assertTrue(
            Regex("INSERT INTO autopilot_state", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "seed INSERT 절이 없습니다.",
        )
        assertTrue(
            Regex("ON CONFLICT \\(id\\) DO NOTHING", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "seed INSERT가 ON CONFLICT DO NOTHING으로 멱등 가드되지 않았습니다(재적용 안전).",
        )
    }

    @Test
    fun `V91은 폐기 철학 ROLLBACK 노트를 동봉한다`() {
        // spec §3-bis Tier A — 되돌림 SQL 주석 의무.
        assertTrue(sqlText.contains("ROLLBACK"), "ROLLBACK 노트가 없습니다(폐기 철학 의무).")
        assertTrue(
            Regex("DROP TABLE IF EXISTS autopilot_state", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "DROP TABLE 되돌림 노트가 없습니다.",
        )
        assertTrue(
            sqlText.contains("owner = 'AUTOPILOT'"),
            "owner=AUTOPILOT 데이터 정리 되돌림 노트가 없습니다.",
        )
        // 본 마이그레이션이 기존 매매 스키마를 건드리지 않음을 명시 검증(zero-regression).
        assertFalse(
            Regex("DROP TABLE strategy\\b", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "기존 strategy 테이블을 폐기 대상으로 삼으면 안 됩니다(사용자 전략 보존).",
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
        const val MIGRATION_NAME = "V91__autopilot_state.sql"
    }
}
