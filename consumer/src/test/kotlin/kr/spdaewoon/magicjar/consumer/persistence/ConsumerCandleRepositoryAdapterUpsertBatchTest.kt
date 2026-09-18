package kr.spdaewoon.magicjar.consumer.persistence

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import java.math.BigDecimal
import kotlinx.datetime.Instant as KtInstant

/**
 * ConsumerCandleRepositoryAdapter.upsertAll — JDBC batchUpdate 전환 단위테스트 (ADR 0120 amend).
 *
 * 검증:
 *   - 빈 list → batchUpdate 0회 (no-op).
 *   - N봉 → 동일 ON CONFLICT DO UPDATE SQL 로 batchUpdate, params size=N.
 *   - affected count = row 별 update count 합산 (음수 SUCCESS_NO_INFO=-2 는 1 로 보정).
 *   - chunk 경계 — UPSERT_BATCH_CHUNK 초과 시 여러 batch 로 분할.
 */
class ConsumerCandleRepositoryAdapterUpsertBatchTest {
    private val jpa = mockk<ConsumerCandleJpaRepository>(relaxed = true)

    private fun candle(
        c: Long,
        v: Long,
    ): Candle =
        Candle(
            code = "005930",
            interval = CandleInterval.ONE_WEEK,
            ts = KtInstant.fromEpochSeconds(1_700_000_000L + c),
            openRaw = BigDecimal(c),
            highRaw = BigDecimal(c + 10),
            lowRaw = BigDecimal(c - 10),
            closeRaw = BigDecimal(c + 5),
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = v,
            source = "KIS",
        )

    @Test
    fun `빈 list — batchUpdate 0회`() {
        val jdbc = mockk<NamedParameterJdbcTemplate>(relaxed = true)
        val adapter = ConsumerCandleRepositoryAdapter(jpa, jdbc)

        val affected = adapter.upsertAll(emptyList())

        assertThat(affected).isEqualTo(0)
        verify(exactly = 0) { jdbc.batchUpdate(any(), any<Array<SqlParameterSource>>()) }
    }

    @Test
    fun `N봉 — 동일 DO UPDATE SQL batchUpdate + params size N + affected 합산`() {
        val jdbc = mockk<NamedParameterJdbcTemplate>()
        val sqlSlot = slot<String>()
        val paramsSlot = slot<Array<SqlParameterSource>>()
        // 3봉 → row 별 update count 1,1,1.
        every { jdbc.batchUpdate(capture(sqlSlot), capture(paramsSlot)) } returns intArrayOf(1, 1, 1)
        val adapter = ConsumerCandleRepositoryAdapter(jpa, jdbc)

        val affected = adapter.upsertAll(listOf(candle(1, 100), candle(2, 200), candle(3, 300)))

        assertThat(affected).isEqualTo(3)
        assertThat(paramsSlot.captured).hasSize(3)
        // SQL 은 insertOrUpdate @Query 와 동일한 ON CONFLICT DO UPDATE (gapfill WHERE 절 없음).
        assertThat(sqlSlot.captured).contains("ON CONFLICT (code, interval, ts) DO UPDATE")
        assertThat(sqlSlot.captured).doesNotContain("KIS-RANGE-GAPFILL")
        // 단건 insertOrUpdate 루프는 호출 안 됨(batchUpdate 로 대체).
        verify(exactly = 0) { jpa.insertOrUpdate(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `batch update count 음수(SUCCESS_NO_INFO) — 1로 보정`() {
        val jdbc = mockk<NamedParameterJdbcTemplate>()
        // 드라이버가 -2(SUCCESS_NO_INFO) 반환해도 affected 음수 오염 방지 → 1 보정.
        every { jdbc.batchUpdate(any(), any<Array<SqlParameterSource>>()) } returns intArrayOf(-2, 1, -2)
        val adapter = ConsumerCandleRepositoryAdapter(jpa, jdbc)

        val affected = adapter.upsertAll(listOf(candle(1, 100), candle(2, 200), candle(3, 300)))

        assertThat(affected).isEqualTo(3) // (-2→1) + 1 + (-2→1)
    }

    @Test
    fun `chunk 경계 — UPSERT_BATCH_CHUNK 초과 시 여러 batch 분할`() {
        val jdbc = mockk<NamedParameterJdbcTemplate>()
        val n = ConsumerCandleRepositoryAdapter.UPSERT_BATCH_CHUNK + 10
        // chunk 별 size 만큼 1 반환.
        every { jdbc.batchUpdate(any(), any<Array<SqlParameterSource>>()) } answers {
            IntArray(secondArg<Array<SqlParameterSource>>().size) { 1 }
        }
        val adapter = ConsumerCandleRepositoryAdapter(jpa, jdbc)

        val candles = (1..n).map { candle(it.toLong(), it.toLong()) }
        val affected = adapter.upsertAll(candles)

        assertThat(affected).isEqualTo(n)
        // 첫 chunk(500) + 둘째 chunk(10) → batchUpdate 2회.
        verify(exactly = 2) { jdbc.batchUpdate(any(), any<Array<SqlParameterSource>>()) }
    }

    // ── precedence 가드 UPSERT (P0-A Phase A-2 · P0-B) ──

    @Test
    fun `upsertAllWithPrecedence — DO UPDATE에 source_priority 가드 WHERE절 포함`() {
        val jdbc = mockk<NamedParameterJdbcTemplate>()
        val sqlSlot = slot<String>()
        every { jdbc.batchUpdate(capture(sqlSlot), any<Array<SqlParameterSource>>()) } returns intArrayOf(1, 1)
        val adapter = ConsumerCandleRepositoryAdapter(jpa, jdbc)

        adapter.upsertAllWithPrecedence(
            listOf(candle(1, 100), candle(2, 200)),
            kr.spdaewoon.magicjar.domain.market.candle.CandleSource.WS_LIVE,
        )

        // precedence 가드: 신규가 기존보다 신뢰 같거나 높을 때만(작거나 같은 priority) 갱신.
        assertThat(sqlSlot.captured).contains("ON CONFLICT (code, interval, ts) DO UPDATE")
        assertThat(sqlSlot.captured).contains("WHERE EXCLUDED.source_priority <= COALESCE(candle.source_priority, 3)")
        assertThat(sqlSlot.captured).contains("source_priority")
    }

    @Test
    fun `upsertAllWithPrecedence — source enum label과 priority가 params에 실린다`() {
        val jdbc = mockk<NamedParameterJdbcTemplate>()
        val paramsSlot = slot<Array<SqlParameterSource>>()
        every { jdbc.batchUpdate(any(), capture(paramsSlot)) } returns intArrayOf(1)
        val adapter = ConsumerCandleRepositoryAdapter(jpa, jdbc)

        adapter.upsertAllWithPrecedence(
            listOf(candle(1, 100)),
            kr.spdaewoon.magicjar.domain.market.candle.CandleSource.OFFICIAL_DAILY_CLOSE,
        )

        val p = paramsSlot.captured.single()
        // 공식 마감가 = priority 1, 표준 라벨 KIS_DAILY_CLOSE.
        assertThat(p.getValue("sourcePriority")).isEqualTo(1)
        assertThat(p.getValue("source")).isEqualTo("KIS_DAILY_CLOSE")
    }

    @Test
    fun `upsertAllWithPrecedence — 가드로 skip된 행(update count 0)은 affected 미포함`() {
        val jdbc = mockk<NamedParameterJdbcTemplate>()
        // 낮은 신뢰 배치가 기존 고신뢰 봉을 못 덮어 update count 0 반환되는 행 혼재.
        every { jdbc.batchUpdate(any(), any<Array<SqlParameterSource>>()) } returns intArrayOf(1, 0, 1)
        val adapter = ConsumerCandleRepositoryAdapter(jpa, jdbc)

        val affected =
            adapter.upsertAllWithPrecedence(
                listOf(candle(1, 100), candle(2, 200), candle(3, 300)),
                kr.spdaewoon.magicjar.domain.market.candle.CandleSource.POLLING_FALLBACK,
            )

        assertThat(affected).isEqualTo(2) // 0(skip)은 미포함
    }

    @Test
    fun `upsertAllWithPrecedence — 빈 list는 batchUpdate 0회`() {
        val jdbc = mockk<NamedParameterJdbcTemplate>(relaxed = true)
        val adapter = ConsumerCandleRepositoryAdapter(jpa, jdbc)

        val affected =
            adapter.upsertAllWithPrecedence(
                emptyList(),
                kr.spdaewoon.magicjar.domain.market.candle.CandleSource.WS_LIVE,
            )

        assertThat(affected).isEqualTo(0)
        verify(exactly = 0) { jdbc.batchUpdate(any(), any<Array<SqlParameterSource>>()) }
    }
}
