package kr.spdaewoon.magicjar.api.autopilot

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * V94 오버나잇 토글 마이그레이션(`V94__strategy_allow_overnight.sql`) 구조 무결성 테스트 — ADR 0129 레버 1·4.
 *
 * ### 검증 대상 (spec §2)
 * - `strategy.allow_overnight` BOOLEAN NOT NULL DEFAULT true (zero-regression — 기존 전략 현행 거동 보존).
 * - ADD COLUMN IF NOT EXISTS (재실행 안전).
 * - autopilot 시드 min_hold_bars=3 UPDATE (owner='AUTOPILOT' AND min_hold_bars IS NULL — 보수: 기존 값 미덮음).
 * - 기존 AUTOPILOT 전략 allow_overnight 소급 변경 0 (UPDATE allow_overnight 절 없음 — true 유지).
 *
 * Testcontainers 없이 SQL 파일 텍스트만 검증(V91 선례 정합). AI 호출 0 · mock 0.
 */
class V94StrategyAllowOvernightMigrationTest {
    private val sqlText: String by lazy {
        val f = locateMigration()
        assertTrue(f.exists(), "V94 마이그레이션 파일을 찾지 못했습니다: ${f.absolutePath}")
        f.readText()
    }

    @Test
    fun `allow_overnight 컬럼을 NOT NULL DEFAULT true 로 add-only 추가한다 (zero-regression)`() {
        assertTrue(
            Regex(
                "ADD COLUMN IF NOT EXISTS allow_overnight\\s+BOOLEAN\\s+NOT NULL\\s+DEFAULT true",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(sqlText),
            "allow_overnight BOOLEAN NOT NULL DEFAULT true ADD COLUMN IF NOT EXISTS 절이 없습니다.",
        )
    }

    @Test
    fun `autopilot 시드 min_hold_bars=3 을 IS NULL 조건으로만 주입한다 (기존 값 미덮음 — 레버 4)`() {
        assertTrue(
            Regex(
                "UPDATE strategy[\\s\\S]*min_hold_bars\\s*=\\s*3[\\s\\S]*owner\\s*=\\s*'AUTOPILOT'[\\s\\S]*min_hold_bars IS NULL",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(sqlText),
            "autopilot min_hold_bars=3 (owner='AUTOPILOT' AND min_hold_bars IS NULL) UPDATE 절이 없습니다.",
        )
    }

    @Test
    fun `기존 AUTOPILOT 전략 allow_overnight 을 소급 변경하지 않는다 (UPDATE allow_overnight 절 없음 — true 유지)`() {
        assertTrue(
            !Regex("UPDATE strategy\\s+SET\\s+allow_overnight", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "기존 전략 allow_overnight 을 소급 UPDATE 하면 안 됩니다(DDL default true 로 현행 거동 보존).",
        )
    }

    private fun locateMigration(): File {
        val candidates =
            listOf(
                "src/main/resources/db/migration/V94__strategy_allow_overnight.sql",
                "api/src/main/resources/db/migration/V94__strategy_allow_overnight.sql",
            )
        return candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: File("api/src/main/resources/db/migration/V94__strategy_allow_overnight.sql")
    }
}
