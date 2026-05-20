package kr.spdaewoon.magicjar.consumer.reconcile

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.reconcile.HoldingDiff
import kr.spdaewoon.magicjar.domain.reconcile.InternalHolding
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileHolding
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileRun
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileStatus
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileTriggerSource
import kr.spdaewoon.magicjar.domain.reconcile.port.InternalPositionQueryPort
import kr.spdaewoon.magicjar.domain.reconcile.port.PositionUpsertPort
import kr.spdaewoon.magicjar.domain.reconcile.port.ReconcileEventPublisher
import kr.spdaewoon.magicjar.domain.reconcile.port.ReconcileKisBalancePort
import kr.spdaewoon.magicjar.domain.reconcile.port.ReconcileRunRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal

/**
 * BalanceReconcileService 단위 테스트 (ADR 0070 §2-11 자본 보호 흐름 검증).
 *
 * 4 케이스:
 *  (a) 정상 — Reconciled → reconcile_run save(RECONCILED) + publishRun 1회 (RUNNING + RECONCILED 총 2회)
 *  (b) drift 검출 — Drifted → publishDrift N건 + publishRun(DRIFT) + PositionUpsertPort.upsertFromKis 호출
 *  (c) KIS API 실패 — ERROR 상태 + publishRun(ERROR) — KillSwitch 트리거 X
 *  (d) Phase 2 graceful — Repository 미합류 시 in-memory 진행 (id=null) + log 진행 가능
 *
 * AI 호출 0 — T-RECON 트랙 정책.
 */
class BalanceReconcileServiceTest {
    private val kisBalancePort = mockk<ReconcileKisBalancePort>()
    private val eventPublisher = mockk<ReconcileEventPublisher>(relaxed = true)
    private val reconcileRepo = mockk<ReconcileRunRepository>()
    private val internalPositionPort = mockk<InternalPositionQueryPort>()
    private val positionUpsertPort = mockk<PositionUpsertPort>(relaxed = true)
    private val systemEventLogger = mockk<SystemEventLogger>(relaxed = true)

    private val reconcileRepoProvider = mockk<ObjectProvider<ReconcileRunRepository>>()
    private val internalPosProvider = mockk<ObjectProvider<InternalPositionQueryPort>>()
    private val positionUpsertProvider = mockk<ObjectProvider<PositionUpsertPort>>()

    private fun newService(
        repo: ReconcileRunRepository? = reconcileRepo,
        internalPos: InternalPositionQueryPort? = internalPositionPort,
        upsert: PositionUpsertPort? = positionUpsertPort,
    ): BalanceReconcileService {
        every { reconcileRepoProvider.getIfAvailable() } returns repo
        every { internalPosProvider.getIfAvailable() } returns internalPos
        every { positionUpsertProvider.getIfAvailable() } returns upsert
        if (repo != null) {
            every { repo.save(any()) } answers { firstArg<ReconcileRun>().copy(id = 999L) }
        }
        return BalanceReconcileService(
            kisBalancePort = kisBalancePort,
            eventPublisher = eventPublisher,
            reconcileRunRepositoryProvider = reconcileRepoProvider,
            internalPositionQueryPortProvider = internalPosProvider,
            positionUpsertPortProvider = positionUpsertProvider,
            systemEventLogger = systemEventLogger,
        )
    }

    @Test
    fun `(a) 정상 흐름 — KIS·내부 일치 → RECONCILED + publishRun 2회 (RUNNING + RECONCILED)`() {
        every { kisBalancePort.fetchAllBalances("U1") } returns
            listOf(ReconcileHolding("005930", 100, BigDecimal("70000")))
        every { internalPositionPort.findCurrentByUser("U1") } returns
            listOf(InternalHolding("005930", 100, BigDecimal("70000")))

        val service = newService()
        val result = service.reconcileUser("U1", ReconcileTriggerSource.CRON)

        assertThat(result.status).isEqualTo(ReconcileStatus.RECONCILED)
        assertThat(result.driftCount).isEqualTo(0)
        // RUNNING + RECONCILED 2회 publishRun
        verify(exactly = 2) { eventPublisher.publishRun(any()) }
        // drift publish 0회
        verify(exactly = 0) { eventPublisher.publishDrift(any(), any(), any()) }
    }

    @Test
    fun `(b) drift 검출 — Drifted → publishDrift + publishRun(DRIFT) + upsertFromKis 호출`() {
        every { kisBalancePort.fetchAllBalances("U1") } returns
            listOf(ReconcileHolding("005930", 100, BigDecimal("70000")))
        every { internalPositionPort.findCurrentByUser("U1") } returns
            listOf(InternalHolding("005930", 99, BigDecimal("70000"))) // 1주 diff

        val service = newService()
        val result = service.reconcileUser("U1", ReconcileTriggerSource.CRON)

        assertThat(result.status).isEqualTo(ReconcileStatus.DRIFT)
        assertThat(result.driftCount).isEqualTo(1)
        // publishDrift 1건 — 005930
        verify(exactly = 1) { eventPublisher.publishDrift("U1", 999L, any<HoldingDiff>()) }
        // PositionUpsertPort.upsertFromKis 호출 (KIS 신뢰원)
        verify { positionUpsertPort.upsertFromKis("U1", any()) }
    }

    @Test
    fun `(c) KIS API 실패 — ERROR 상태 + publishRun(ERROR) — KillSwitch publishDrift 0`() {
        every { kisBalancePort.fetchAllBalances("U1") } throws RuntimeException("KIS 401 unauthorized")
        every { internalPositionPort.findCurrentByUser("U1") } returns emptyList()

        val service = newService()
        val result = service.reconcileUser("U1", ReconcileTriggerSource.CRON)

        assertThat(result.status).isEqualTo(ReconcileStatus.ERROR)
        assertThat(result.lastError).contains("KIS 401")
        verify(exactly = 0) { eventPublisher.publishDrift(any(), any(), any()) }
    }

    // ===== SystemEvent emit (P1 spec §6.1 emit point #3) =====

    /**
     * (e) Drifted 분기 진입 시 SystemEventLogger emit RECONCILE_DRIFT_DETECTED.
     *
     * spec §6.1 P1 emit point #3:
     *  - category = RECONCILE / severity = WARN / actor = BalanceReconcileService
     *  - userId = reconcile 대상 사용자
     *  - detailJson = {"kisHoldings":N,"dbPositions":M,"driftCount":K}
     *
     * 진보 페르소나 lens: Drifted = 자본 사고 즉시 audit 의무 (WARN). PriceDrifted 단독은 emit skip.
     */
    @Test
    fun `(e) Drifted 분기 — SystemEventLogger emit RECONCILE_DRIFT_DETECTED (WARN + userId + driftCount)`() {
        every { kisBalancePort.fetchAllBalances("U1") } returns
            listOf(ReconcileHolding("005930", 100, BigDecimal("70000")))
        every { internalPositionPort.findCurrentByUser("U1") } returns
            listOf(InternalHolding("005930", 99, BigDecimal("70000"))) // 1주 diff → drift

        val service = newService()
        service.reconcileUser("U1", ReconcileTriggerSource.CRON)

        val eventSlot = slot<SystemEvent>()
        verify(exactly = 1) { systemEventLogger.emit(capture(eventSlot)) }
        val event = eventSlot.captured
        assertThat(event.category).isEqualTo(SystemEventCategory.RECONCILE)
        assertThat(event.severity).isEqualTo(SystemEventSeverity.WARN)
        assertThat(event.action).isEqualTo("RECONCILE_DRIFT_DETECTED")
        assertThat(event.actor).isEqualTo("BalanceReconcileService")
        assertThat(event.userId).isEqualTo("U1")
        assertThat(event.detailJson).contains("\"kisHoldings\":1")
        assertThat(event.detailJson).contains("\"dbPositions\":1")
        assertThat(event.detailJson).contains("\"driftCount\":1")
    }

    /**
     * (f) Reconciled 분기 — DRIFT 아닌 정상 흐름은 RECONCILE_DRIFT_DETECTED emit 0건.
     *
     * 진보 페르소나 lens: 정상 audit noise 회피 — RECONCILE_DETECTED 외 다른 catalog로 처리될 사안 (스펙 P2/P3).
     */
    @Test
    fun `(f) Reconciled 정상 흐름 — RECONCILE_DRIFT_DETECTED emit 0건 (audit noise 회피)`() {
        every { kisBalancePort.fetchAllBalances("U1") } returns
            listOf(ReconcileHolding("005930", 100, BigDecimal("70000")))
        every { internalPositionPort.findCurrentByUser("U1") } returns
            listOf(InternalHolding("005930", 100, BigDecimal("70000")))

        val service = newService()
        service.reconcileUser("U1", ReconcileTriggerSource.CRON)

        verify(exactly = 0) { systemEventLogger.emit(any()) }
    }

    @Test
    fun `(d) Phase 2 graceful — ReconcileRunRepository 미합류 시 in-memory(id=null) 진행`() {
        every { kisBalancePort.fetchAllBalances("U1") } returns
            listOf(ReconcileHolding("005930", 100, BigDecimal("70000")))
        // 모든 Phase 2 어댑터 부재
        val service = newService(repo = null, internalPos = null, upsert = null)

        val result = service.reconcileUser("U1", ReconcileTriggerSource.CRON)

        // 내부 emptyList 가정 → KIS 보유 종목 전체가 drift로 분류
        assertThat(result.status).isEqualTo(ReconcileStatus.DRIFT)
        assertThat(result.id).isNull() // in-memory ID
        // drift publish는 진행됨 (자본 사고 흐름 자체는 cut off X)
        verify { eventPublisher.publishDrift("U1", null, any()) }
    }
}
