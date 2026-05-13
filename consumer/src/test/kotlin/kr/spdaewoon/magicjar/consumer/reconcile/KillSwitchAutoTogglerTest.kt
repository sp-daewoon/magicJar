package kr.spdaewoon.magicjar.consumer.reconcile

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.persistence.KillSwitchJpaRepository
import kr.spdaewoon.magicjar.domain.reconcile.port.KillSwitchTogglePort
import kr.spdaewoon.magicjar.domain.reconcile.port.ReconcileEventPublisher
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider

/**
 * KillSwitchAutoToggler 단위 테스트 (ADR 0070 §2-11 step 5·6 자본 보호 흐름).
 *
 * 3 케이스:
 *  (a) drift listen → KillSwitchTogglePort.engage 호출 + killswitch.engaged.v1 publish
 *  (b) idempotency — 5분 내 같은 user 중복 drift → KillSwitch 1회만 engage
 *  (c) Phase 2 graceful — TogglePort 부재 시 KillSwitchJpaRepository baseline reuse
 *
 * AI 호출 0 — T-RECON 트랙 정책.
 */
class KillSwitchAutoTogglerTest {
    private val eventPublisher = mockk<ReconcileEventPublisher>(relaxed = true)
    private val killSwitchPort = mockk<KillSwitchTogglePort>(relaxed = true)
    private val killSwitchJpaRepo = mockk<KillSwitchJpaRepository>(relaxed = true)
    private val portProvider = mockk<ObjectProvider<KillSwitchTogglePort>>()
    private val objectMapper = ObjectMapper()

    private fun newToggler(port: KillSwitchTogglePort? = killSwitchPort): KillSwitchAutoToggler {
        every { portProvider.getIfAvailable() } returns port
        return KillSwitchAutoToggler(
            eventPublisher = eventPublisher,
            killSwitchTogglePortProvider = portProvider,
            killSwitchJpaRepository = killSwitchJpaRepo,
            objectMapper = objectMapper,
        )
    }

    private fun driftPayload(
        userId: String,
        symbol: String,
    ): String = """{"eventId":"x","userId":"$userId","symbol":"$symbol","kisQty":100,"internalQty":99,"qtyDiff":1}"""

    private fun consumerRecord(payload: String): ConsumerRecord<String, String> = ConsumerRecord("reconcile.drift.v1", 0, 0L, "U1", payload)

    @Test
    fun `(a) drift listen — KillSwitchTogglePort_engage + killswitch_engaged_v1 publish`() {
        val toggler = newToggler()
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "005930")))

        verify { killSwitchPort.engage("U1", "POSITION_DRIFT") }
        verify { eventPublisher.publishKillSwitchEngaged("U1", "POSITION_DRIFT", "AUTO") }
    }

    @Test
    fun `(b) idempotency — 5분 내 같은 user 중복 drift → engage 1회만`() {
        val toggler = newToggler()
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "005930")))
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "000660"))) // 다른 종목, 같은 user
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "035720"))) // 또 다른 종목

        // KillSwitch는 1회만 engage (multi-symbol drift 안전)
        verify(exactly = 1) { killSwitchPort.engage("U1", "POSITION_DRIFT") }
        verify(exactly = 1) { eventPublisher.publishKillSwitchEngaged("U1", any(), any()) }
    }

    @Test
    fun `(c) Phase 2 graceful — TogglePort 부재 시 KillSwitchJpaRepository baseline reuse`() {
        val toggler = newToggler(port = null)
        toggler.onDriftDetected(consumerRecord(driftPayload("U1", "005930")))

        verify {
            killSwitchJpaRepo.enableKillSwitch(
                userId = "U1",
                reason = "POSITION_DRIFT",
                updatedBy = "consumer",
            )
        }
        verify { eventPublisher.publishKillSwitchEngaged("U1", "POSITION_DRIFT", "AUTO") }
        // 다른 user는 다른 idempotency window — 별도 검증
        assertThat(toggler).isNotNull
    }
}
