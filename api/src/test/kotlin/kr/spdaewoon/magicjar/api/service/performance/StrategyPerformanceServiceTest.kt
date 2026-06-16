package kr.spdaewoon.magicjar.api.service.performance

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kr.spdaewoon.magicjar.api.controller.dto.performance.StrategyNoteUpsertRequest
import kr.spdaewoon.magicjar.api.persistence.performance.StrategyDailyNoteJpaEntity
import kr.spdaewoon.magicjar.api.persistence.performance.StrategyDailyNoteJpaRepository
import kr.spdaewoon.magicjar.api.persistence.performance.StrategyDailyPerformanceJpaEntity
import kr.spdaewoon.magicjar.api.persistence.performance.StrategyDailyPerformanceJpaRepository
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * StrategyPerformanceService 단위 테스트 — 요약 산출(승률·신뢰도·레짐 그룹핑·평균 α·빈 데이터) + 메모 UPSERT.
 *
 * 신뢰도 통계의 산식 정확성은 domain StrategyConfidenceCalculatorTest 가 단일 진실원으로 검증하므로,
 * 여기서는 서비스가 영속 엔티티를 올바르게 집계·매핑하고 배치 미가동(0건) 빈 응답을 정상 처리하는지에 집중한다.
 */
class StrategyPerformanceServiceTest {
    private val performanceRepository = mockk<StrategyDailyPerformanceJpaRepository>()
    private val noteRepository = mockk<StrategyDailyNoteJpaRepository>()
    private val service = StrategyPerformanceService(performanceRepository, noteRepository)

    private val userId = "1"
    private val strategyId = 86L

    private fun perfRow(
        date: LocalDate,
        roundTrips: Int,
        wins: Int,
        losses: Int,
        realizedPnl: String,
        costBasis: String,
        returnPct: String?,
        alphaPct: String? = null,
        regime: String? = null,
    ): StrategyDailyPerformanceJpaEntity =
        StrategyDailyPerformanceJpaEntity(
            id = date.toEpochDay(),
            userId = userId,
            strategyId = strategyId,
            tradeDate = date,
            roundTripCount = roundTrips,
            winCount = wins,
            lossCount = losses,
            realizedPnl = BigDecimal(realizedPnl),
            totalCostBasis = BigDecimal(costBasis),
            returnPct = returnPct?.let { BigDecimal(it) },
            benchmarkIndexCode = "0001",
            benchmarkChangePct = null,
            alphaPct = alphaPct?.let { BigDecimal(it) },
            marketRegime = regime,
        )

    @Test
    fun `summary - 빈 데이터는 hasData false + LOW 신뢰도 0건`() {
        every { performanceRepository.findByUserIdAndStrategyId(userId, strategyId) } returns emptyList()

        val result = service.getSummary(strategyId, userId)

        result.hasData shouldBe false
        result.totalRoundTrips shouldBe 0
        result.tradingDays shouldBe 0
        result.winRatePct.shouldBeNull()
        result.avgDailyReturnPct.shouldBeNull()
        result.avgAlphaPct.shouldBeNull()
        result.confidence.label shouldBe "LOW"
        result.confidence.labelText shouldBe "신뢰도 낮음 0건"
        result.confidence.sampleRoundTrips shouldBe 0
        result.regimeBreakdown.shouldHaveSize(0)
    }

    @Test
    fun `summary - 누적 승률 패 카운트만 분모 break-even 제외`() {
        every { performanceRepository.findByUserIdAndStrategyId(userId, strategyId) } returns
            listOf(
                // 왕복3 (승2 패0 break-even1) — 승률 분모는 승+패=2
                perfRow(LocalDate.of(2026, 6, 16), 3, 2, 0, "30000", "1000000", "3.0"),
            )

        val result = service.getSummary(strategyId, userId)

        result.hasData shouldBe true
        result.totalRoundTrips shouldBe 3
        result.totalWins shouldBe 2
        result.totalLosses shouldBe 0
        // wins / (wins+losses) = 2/2 = 100%
        result.winRatePct shouldBe BigDecimal("100.00")
    }

    @Test
    fun `summary - 레짐별 승률 GROUP BY + null 레짐 제외`() {
        every { performanceRepository.findByUserIdAndStrategyId(userId, strategyId) } returns
            listOf(
                perfRow(LocalDate.of(2026, 6, 12), 4, 3, 1, "40000", "1000000", "4.0", regime = "SIDEWAYS"),
                perfRow(LocalDate.of(2026, 6, 13), 6, 4, 2, "20000", "1000000", "2.0", regime = "SIDEWAYS"),
                perfRow(LocalDate.of(2026, 6, 14), 2, 1, 1, "5000", "500000", "1.0", regime = "UPTREND"),
                // 지수 부재 (regime null) — 브레이크다운에서 제외돼야 함
                perfRow(LocalDate.of(2026, 6, 15), 1, 1, 0, "3000", "300000", "1.0", regime = null),
            )

        val result = service.getSummary(strategyId, userId)

        // SIDEWAYS·UPTREND 2개만 (null 제외)
        result.regimeBreakdown.shouldHaveSize(2)
        // roundTripCount DESC → SIDEWAYS(10) 먼저
        val sideways = result.regimeBreakdown[0]
        sideways.regime shouldBe "SIDEWAYS"
        sideways.roundTripCount shouldBe 10
        sideways.winCount shouldBe 7
        sideways.lossCount shouldBe 3
        // 7 / (7+3) = 70%
        sideways.winRatePct shouldBe BigDecimal("70.00")

        val uptrend = result.regimeBreakdown[1]
        uptrend.regime shouldBe "UPTREND"
        uptrend.winRatePct shouldBe BigDecimal("50.00")
    }

    @Test
    fun `summary - 평균 알파는 alpha 채워진 행만 평균`() {
        every { performanceRepository.findByUserIdAndStrategyId(userId, strategyId) } returns
            listOf(
                perfRow(LocalDate.of(2026, 6, 12), 1, 1, 0, "10000", "500000", "2.0", alphaPct = "1.0"),
                perfRow(LocalDate.of(2026, 6, 13), 1, 1, 0, "10000", "500000", "2.0", alphaPct = "3.0"),
                // alpha null — 평균 표본 제외
                perfRow(LocalDate.of(2026, 6, 14), 1, 1, 0, "10000", "500000", "2.0", alphaPct = null),
            )

        val result = service.getSummary(strategyId, userId)

        // (1.0 + 3.0) / 2 = 2.0
        result.avgAlphaPct shouldBe BigDecimal("2.0000")
    }

    @Test
    fun `history - 행 매핑 + 그날 승률 산출`() {
        val from = LocalDate.of(2026, 6, 11)
        val to = LocalDate.of(2026, 6, 16)
        every {
            performanceRepository.findByStrategyIdAndTradeDateBetweenOrderByTradeDateDesc(strategyId, from, to)
        } returns
            listOf(
                perfRow(LocalDate.of(2026, 6, 16), 4, 3, 1, "40000", "1000000", "4.0", regime = "UPTREND"),
            )

        val result = service.getHistory(strategyId, userId, from, to)

        result.rows.shouldHaveSize(1)
        val row = result.rows[0]
        row.roundTripCount shouldBe 4
        // wins / roundTrips = 3/4 = 75%
        row.winRatePct shouldBe BigDecimal("75.00")
        row.returnPct shouldBe BigDecimal("4.0")
        row.marketRegime shouldBe "UPTREND"
    }

    @Test
    fun `history - userId 불일치 행은 필터`() {
        val from = LocalDate.of(2026, 6, 11)
        val to = LocalDate.of(2026, 6, 16)
        val mine = perfRow(LocalDate.of(2026, 6, 16), 1, 1, 0, "1000", "10000", "10.0")
        val other =
            StrategyDailyPerformanceJpaEntity(
                id = 999L,
                userId = "other",
                strategyId = strategyId,
                tradeDate = LocalDate.of(2026, 6, 15),
                roundTripCount = 1,
                winCount = 1,
                lossCount = 0,
                realizedPnl = BigDecimal("1000"),
                totalCostBasis = BigDecimal("10000"),
                returnPct = BigDecimal("10.0"),
                benchmarkIndexCode = null,
                benchmarkChangePct = null,
                alphaPct = null,
                marketRegime = null,
            )
        every {
            performanceRepository.findByStrategyIdAndTradeDateBetweenOrderByTradeDateDesc(strategyId, from, to)
        } returns listOf(mine, other)

        val result = service.getHistory(strategyId, userId, from, to)

        result.rows.shouldHaveSize(1)
        result.rows[0].tradeDate shouldBe LocalDate.of(2026, 6, 16)
    }

    @Test
    fun `upsertNote - 신규 생성`() {
        val date = LocalDate.of(2026, 6, 16)
        every { noteRepository.findByUserIdAndStrategyIdAndTradeDate(userId, strategyId, date) } returns null
        val saved = slot<StrategyDailyNoteJpaEntity>()
        every { noteRepository.save(capture(saved)) } answers {
            saved.captured.let {
                StrategyDailyNoteJpaEntity(
                    id = 1L,
                    userId = it.userId,
                    strategyId = it.strategyId,
                    tradeDate = it.tradeDate,
                    noteText = it.noteText,
                    tags = it.tags,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            }
        }

        val result =
            service.upsertNote(
                strategyId,
                userId,
                date,
                StrategyNoteUpsertRequest(noteText = "손절 잘됨", tags = listOf("손절잘됨")),
            )

        result.id shouldBe 1L
        result.noteText shouldBe "손절 잘됨"
        result.tags shouldBe listOf("손절잘됨")
        // 신규는 id null 로 저장 요청
        saved.captured.id.shouldBeNull()
    }

    @Test
    fun `upsertNote - 기존 수정은 id createdAt 보존 updatedAt 갱신`() {
        val date = LocalDate.of(2026, 6, 16)
        val createdAt = Instant.parse("2026-06-16T00:00:00Z")
        val existing =
            StrategyDailyNoteJpaEntity(
                id = 7L,
                userId = userId,
                strategyId = strategyId,
                tradeDate = date,
                noteText = "old",
                tags = listOf("갭상승"),
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        every { noteRepository.findByUserIdAndStrategyIdAndTradeDate(userId, strategyId, date) } returns existing
        val saved = slot<StrategyDailyNoteJpaEntity>()
        every { noteRepository.save(capture(saved)) } answers { saved.captured }

        val result =
            service.upsertNote(
                strategyId,
                userId,
                date,
                StrategyNoteUpsertRequest(noteText = "new", tags = listOf("변동성과다")),
            )

        result.id shouldBe 7L
        result.noteText shouldBe "new"
        result.tags shouldBe listOf("변동성과다")
        // id·createdAt 보존, updatedAt 은 새 값(>= createdAt)
        saved.captured.id shouldBe 7L
        saved.captured.createdAt shouldBe createdAt
        saved.captured.updatedAt.shouldNotBeNull()
        (saved.captured.updatedAt >= createdAt) shouldBe true
    }
}
