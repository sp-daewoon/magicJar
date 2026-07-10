package kr.spdaewoon.magicjar.consumer.signal

import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerMarketIndexDailyJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerMarketIndexDailyJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.LocalDate

/**
 * 진입 가드 ① RegimeEntryGate 단위 테스트 (2026-07-08).
 *
 * skip(차단 레짐)/통과(비차단)/graceful(조회실패·null·bean 부재)/토글 off 를 고정한다.
 * 레짐은 지수 단위 전역이라 code 는 로그/dedupe 용일 뿐 판정에 미영향(SELL/청산 무영향은 호출부 RealtimeSignalGenerator 가 BUY 한정).
 */
class RegimeEntryGateTest {
    private val repo = mockk<ConsumerMarketIndexDailyJpaRepository>()

    private fun entity(regime: String?): ConsumerMarketIndexDailyJpaEntity =
        ConsumerMarketIndexDailyJpaEntity(
            indexCode = "0001",
            tradeDate = LocalDate.parse("2026-07-07"),
            regime = regime,
        )

    private fun gate(
        enabled: Boolean = true,
        blockRegimes: String = "DOWNTREND",
        repoPresent: Boolean = true,
    ): RegimeEntryGate =
        RegimeEntryGate(
            marketIndexDailyRepositoryProvider = if (repoPresent) providerOf(repo) else emptyProvider(),
            enabled = enabled,
            indexCode = "0001",
            blockRegimesRaw = blockRegimes,
            cacheTtlMillis = 60_000,
        )

    @Test
    fun `DOWNTREND 레짐이면 BUY skip true`() {
        every { repo.findLatestByIndexCode("0001") } returns entity("DOWNTREND")
        assertThat(gate().isBuyBlockedByRegime("005930")).isTrue()
    }

    @Test
    fun `UPTREND 레짐이면 통과 false`() {
        every { repo.findLatestByIndexCode("0001") } returns entity("UPTREND")
        assertThat(gate().isBuyBlockedByRegime("005930")).isFalse()
    }

    @Test
    fun `SIDEWAYS 레짐이면 통과 false`() {
        every { repo.findLatestByIndexCode("0001") } returns entity("SIDEWAYS")
        assertThat(gate().isBuyBlockedByRegime("005930")).isFalse()
    }

    @Test
    fun `regime null이면 graceful false`() {
        every { repo.findLatestByIndexCode("0001") } returns entity(null)
        assertThat(gate().isBuyBlockedByRegime("005930")).isFalse()
    }

    @Test
    fun `행 부재(null)면 graceful false`() {
        every { repo.findLatestByIndexCode("0001") } returns null
        assertThat(gate().isBuyBlockedByRegime("005930")).isFalse()
    }

    @Test
    fun `조회 예외면 fail-open false`() {
        every { repo.findLatestByIndexCode("0001") } throws RuntimeException("db down")
        assertThat(gate().isBuyBlockedByRegime("005930")).isFalse()
    }

    @Test
    fun `repository bean 부재면 graceful false`() {
        assertThat(gate(repoPresent = false).isBuyBlockedByRegime("005930")).isFalse()
    }

    @Test
    fun `토글 off면 조회조차 안 하고 false`() {
        // repo 스텁 없이도 통과 — enabled=false 는 조회 전 즉시 false.
        assertThat(gate(enabled = false).isBuyBlockedByRegime("005930")).isFalse()
    }

    @Test
    fun `block-regimes에 HIGH_VOL 추가하면 HIGH_VOL도 차단`() {
        every { repo.findLatestByIndexCode("0001") } returns entity("HIGH_VOL")
        assertThat(gate(blockRegimes = "DOWNTREND,HIGH_VOL").isBuyBlockedByRegime("005930")).isTrue()
    }

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also { every { it.getIfAvailable() } returns value }

    private fun <T : Any> emptyProvider(): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also { every { it.getIfAvailable() } returns null }
}
