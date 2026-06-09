package kr.spdaewoon.magicjar.api.strategyresearch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * V77 단타 프리셋 재검증 시드(`V77__daytrade_gap_and_go_deactivate.sql`) 무결성 테스트.
 *
 * ### 재검증 리포트 정합 (T-20260610-0344)
 * 출처: `_workspace/strategy/2026-06-10-v77-preset-revalidation-report.md`.
 * 엔진 결함 2건 fix(#862 백테스트 equity mark-to-market · #861 donchian 현재봉 제외) 배포 후,
 * V76 검증을 풀 게이트(G3 MDD≤8% 실제 적용)로 재검증.
 *
 * ### 본 테스트가 막는 회귀
 * 1. **억지 승격 금지** — 6종 전부 클린 MDD G3 탈락 → V77 신규 INSERT 0건. INSERT 절이 새로 생기면 실패.
 * 2. **H1 정직 비활성 LOCK** — `DAYTRADE_GAP_AND_GO`를 is_active=FALSE로 비활성하는 UPDATE 1건이 있어야 한다.
 *    (V76 승격을 클린 MDD가 부정할 때 고수하지 않고 비활성화한 정직 판정 — 이 절이 빠지면 회귀.)
 *
 * Testcontainers 없이 SQL 파일 텍스트만 검사(가벼운 단위 테스트). AI 호출 0 · mock 0.
 */
class V77DaytradeRevalidationSeedTest {
    private val sqlText: String by lazy {
        val f = locateMigration()
        assertTrue(f.exists(), "V77 마이그레이션 파일을 찾지 못했습니다: ${f.absolutePath}")
        f.readText()
    }

    @Test
    fun `V77은 GAP_AND_GO를 is_active=FALSE로 비활성하는 UPDATE 1건을 갖는다 (정직 LOCK)`() {
        assertTrue(
            Regex("UPDATE\\s+strategy_research_candidate", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "GAP_AND_GO 비활성 UPDATE가 없습니다.",
        )
        assertTrue(
            sqlText.contains("is_active = FALSE") &&
                sqlText.contains("WHERE candidate_key = 'DAYTRADE_GAP_AND_GO'"),
            "GAP_AND_GO를 is_active=FALSE로 비활성하는 절이 없습니다(클린 MDD G3 탈락 — 재검증 리포트 §3-1).",
        )
    }

    @Test
    fun `V77은 신규 INSERT 0건이다 (6종 전부 클린 MDD G3 탈락 — 억지 승격 0)`() {
        assertFalse(
            Regex("INSERT\\s+INTO\\s+strategy_research_candidate", RegexOption.IGNORE_CASE).containsMatchIn(sqlText),
            "V77에 신규 INSERT가 있습니다. 재검증 6종 전부 G3(MDD≤8%) 탈락이므로 신규 승격은 0건이어야 합니다.",
        )
        // 재검증 대상 어느 key도 신규 승격 INSERT로 등장하지 않는다.
        REVALIDATED_KEYS.forEach { key ->
            val insertOfKey = Regex("VALUES[\\s\\S]*'$key'", RegexOption.IGNORE_CASE)
            assertFalse(
                insertOfKey.containsMatchIn(sqlText),
                "재검증 key '$key'가 V77 신규 승격 INSERT에 들어갔습니다(G3 탈락 — 억지 승격 금지).",
            )
        }
    }

    @Test
    fun `V77 정정 대상은 GAP_AND_GO 단 1개 candidate_key다`() {
        val keys = INSERTED_KEY_REGEX.findAll(sqlText).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf("DAYTRADE_GAP_AND_GO"),
            keys,
            "V77이 다루는 candidate_key는 GAP_AND_GO(비활성 UPDATE) 1개여야 합니다. 실제: $keys",
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
        const val MIGRATION_NAME = "V77__daytrade_gap_and_go_deactivate.sql"

        // 재검증 6종 — 클린 MDD G3 전부 탈락(재검증 리포트 §3). 어느 것도 신규 승격 금지.
        val REVALIDATED_KEYS =
            listOf(
                "DAYTRADE_VOLUME_BREAKOUT",
                "DAYTRADE_RANGE_BREAKOUT",
                "DAYTRADE_GAP_RVOL_TURNOVER",
                "DAYTRADE_VOLATILITY_EXPANSION",
                "DAYTRADE_MOMENTUM_BREAKOUT",
            )

        val INSERTED_KEY_REGEX = Regex("'(DAYTRADE_[A-Z_]+)'")
    }
}
