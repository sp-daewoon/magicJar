package kr.spdaewoon.magicjar.consumer.reconciliation

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlinx.datetime.Instant as KtInstant

/**
 * [DataReadinessService] readiness 게이트 단위 테스트.
 *
 * 핵심 검증: 2026-06-22 라이브에서 발견된 **off-by-one race false positive** 정정.
 *  - 방금 마감된 boundary 캔들은 Kafka payload 로 in-hand(fresh)지만 DB 에는 비동기 영속되어
 *    평가 순간엔 아직 안 들어가 있다(DB max ts = boundaryTs − 1 interval). 이 정상 lag 을
 *    기존 게이트가 STALE 로 오판 → 신규 구독 종목 BUY 영구 차단.
 *  - 정정: 직전 완결 경계(boundaryTs − 1 interval)까지 따라잡았으면 READY 허용(race 흡수).
 *    2 interval 이상 뒤/결측이면 여전히 STALE 차단(fail-closed 보존).
 */
class DataReadinessServiceTest {
    private val candleRepository = mockk<CandleRepository>()
    private val stateStore = CandleReconciliationStateStore()
    private val auditRepository = mockk<ConsumerAuditTrailJpaRepository>(relaxed = true)
    private val objectMapper = ObjectMapper()

    private val service =
        DataReadinessService(
            candleRepository = candleRepository,
            stateStore = stateStore,
            auditTrailJpaRepository = auditRepository,
            objectMapper = objectMapper,
        )

    private val code = "005930"
    private val interval = CandleInterval.FIVE_MIN

    /** boundaryTs = 정규장 시간대 한 5m 경계(UTC). */
    private val boundaryJava: Instant = Instant.parse("2026-06-22T01:30:00Z")
    private val boundaryTs: KtInstant = KtInstant.fromEpochSeconds(boundaryJava.epochSecond)

    /** intraday 게이트 통과를 위해 1m maxTs 는 항상 fresh(=boundary 직전)로 둔다. */
    private fun fresh1m() {
        every { candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_MIN) } returns
            boundaryJava.minus(Duration.ofMinutes(1))
    }

    /**
     * race 시나리오: 직전 경계봉(boundaryTs − 1 interval)은 영속됨 · 현재 경계봉은 아직 미영속.
     * → 기존엔 `latestIntervalTs < boundaryTs` 로 STALE 였으나, 정정 후 READY(BUY 허용)여야 한다.
     */
    @Test
    fun `직전 경계봉만 영속된 race 는 READY(BUY 허용)`() {
        fresh1m()
        every { candleRepository.findMaxTsByCodeAndInterval(code, interval) } returns
            boundaryJava.minus(Duration.ofMinutes(5)) // 정확히 1 interval 뒤 = 직전 완결 경계

        val result = service.check(code, interval, boundaryTs)

        assertThat(result.status).isEqualTo(DataReadinessStatus.READY)
        assertThat(result.ready).isTrue()
    }

    /**
     * 정상(현재 경계봉까지 영속): latestIntervalTs == boundaryTs → READY (기존 동작 보존).
     */
    @Test
    fun `현재 경계봉까지 영속되면 READY`() {
        fresh1m()
        every { candleRepository.findMaxTsByCodeAndInterval(code, interval) } returns boundaryJava

        val result = service.check(code, interval, boundaryTs)

        assertThat(result.status).isEqualTo(DataReadinessStatus.READY)
    }

    /**
     * 진짜 stale: 2 interval 뒤(직전 경계봉조차 결측). → 여전히 STALE 차단(fail-closed 보존).
     */
    @Test
    fun `2 interval 뒤 진짜 stale 은 STALE 차단 유지`() {
        fresh1m()
        every { candleRepository.findMaxTsByCodeAndInterval(code, interval) } returns
            boundaryJava.minus(Duration.ofMinutes(10)) // 2 interval 뒤 — 직전 완결 경계보다 더 뒤

        val result = service.check(code, interval, boundaryTs)

        assertThat(result.status).isEqualTo(DataReadinessStatus.STALE)
        assertThat(result.reason).isEqualTo("interval-candle-behind-boundary")
    }

    /**
     * 장기 stale: 1시간 뒤. → STALE 차단 유지.
     */
    @Test
    fun `장기 stale 은 STALE 차단 유지`() {
        fresh1m()
        every { candleRepository.findMaxTsByCodeAndInterval(code, interval) } returns
            boundaryJava.minus(Duration.ofHours(1))

        val result = service.check(code, interval, boundaryTs)

        assertThat(result.status).isEqualTo(DataReadinessStatus.STALE)
        assertThat(result.reason).isEqualTo("interval-candle-behind-boundary")
    }

    /**
     * 결측: interval 캔들 0건. → STALE(interval-candle-missing) 차단 유지.
     */
    @Test
    fun `interval 캔들 결측은 STALE 차단 유지`() {
        every { candleRepository.findMaxTsByCodeAndInterval(code, interval) } returns null

        val result = service.check(code, interval, boundaryTs)

        assertThat(result.status).isEqualTo(DataReadinessStatus.STALE)
        assertThat(result.reason).isEqualTo("interval-candle-missing")
    }

    /**
     * 비-intraday(1d) 에도 동일 grace 적용: 직전 일봉(boundaryTs − 1d)까지면 READY.
     */
    @Test
    fun `일봉도 직전 경계까지 영속되면 READY`() {
        val dayInterval = CandleInterval.ONE_DAY
        every { candleRepository.findMaxTsByCodeAndInterval(code, dayInterval) } returns
            boundaryJava.minus(Duration.ofDays(1))

        val result = service.check(code, dayInterval, boundaryTs)

        // 1d 는 intraday 아님 → 1m 게이트 우회, interval grace 만으로 READY.
        assertThat(result.status).isEqualTo(DataReadinessStatus.READY)
    }
}
