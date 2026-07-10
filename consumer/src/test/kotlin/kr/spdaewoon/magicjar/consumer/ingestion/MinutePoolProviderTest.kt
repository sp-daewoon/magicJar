package kr.spdaewoon.magicjar.consumer.ingestion

import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.consumer.config.MinutePoolBackfillProperties
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerMinutePoolCandidateJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MinutePoolProviderTest {
    private val candidateRepository = mockk<ConsumerMinutePoolCandidateJpaRepository>()
    private val activeStrategyCodeProvider = mockk<ActiveStrategyCodeProvider>()

    private fun provider(props: MinutePoolBackfillProperties): MinutePoolProvider =
        MinutePoolProvider(
            candidateRepository = candidateRepository,
            activeStrategyCodeProvider = activeStrategyCodeProvider,
            properties = props,
        )

    @Test
    fun `union 순서 보존 + dedupe + poolSize clamp`() {
        val props =
            MinutePoolBackfillProperties(
                poolSize = 4,
                seedCodes = listOf("005930", "000660"),
                turnoverTopN = 10,
                scorecardTopN = 10,
            )
        every { activeStrategyCodeProvider.activeCodes() } returns setOf("035720")
        // 거래대금 상위에 seed 중복(005930) 포함 — dedupe 확인
        every { candidateRepository.findTopTurnoverCodes(10) } returns listOf("005930", "051910")
        every { candidateRepository.findLatestScorecardCandidateCodes(10) } returns listOf("068270")

        val breakdown = provider(props).resolveBreakdown()

        // seed(2) → strategy(035720) → turnover(051910 신규) = 4 에서 clamp (scorecard 068270 잘림)
        assertThat(breakdown.finalPool).containsExactly("005930", "000660", "035720", "051910")
        assertThat(breakdown.unionBeforeClamp).isEqualTo(5)
    }

    @Test
    fun `동적 소스 실패는 격리되어 seed 로 부분 진행`() {
        val props =
            MinutePoolBackfillProperties(
                poolSize = 30,
                seedCodes = listOf("005930"),
            )
        every { activeStrategyCodeProvider.activeCodes() } throws RuntimeException("db down")
        every { candidateRepository.findTopTurnoverCodes(any()) } throws RuntimeException("no candle")
        every { candidateRepository.findLatestScorecardCandidateCodes(any()) } throws RuntimeException("no table")

        val pool = provider(props).resolvePool()

        assertThat(pool).containsExactly("005930")
    }

    @Test
    fun `includeActiveStrategyCodes false면 전략 종목 제외`() {
        val props =
            MinutePoolBackfillProperties(
                poolSize = 30,
                includeActiveStrategyCodes = false,
                seedCodes = listOf("005930"),
            )
        every { candidateRepository.findTopTurnoverCodes(any()) } returns emptyList()
        every { candidateRepository.findLatestScorecardCandidateCodes(any()) } returns emptyList()

        val breakdown = provider(props).resolveBreakdown()

        assertThat(breakdown.strategy).isZero()
        assertThat(breakdown.finalPool).containsExactly("005930")
    }

    @Test
    fun `잘못된 종목코드는 필터링된다`() {
        val props =
            MinutePoolBackfillProperties(
                poolSize = 30,
                seedCodes = listOf("005930", "INVALID", "12345"),
            )
        every { activeStrategyCodeProvider.activeCodes() } returns emptySet()
        every { candidateRepository.findTopTurnoverCodes(any()) } returns emptyList()
        every { candidateRepository.findLatestScorecardCandidateCodes(any()) } returns emptyList()

        val pool = provider(props).resolvePool()

        assertThat(pool).containsExactly("005930")
    }
}
