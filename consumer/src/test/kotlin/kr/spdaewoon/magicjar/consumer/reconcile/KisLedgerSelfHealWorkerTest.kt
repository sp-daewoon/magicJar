package kr.spdaewoon.magicjar.consumer.reconcile

import kr.spdaewoon.magicjar.consumer.order.HoldingPositionIndex
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerExecutionStrategyPositionAdapter
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialId
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileHolding
import kr.spdaewoon.magicjar.domain.reconcile.port.ReconcileKisBalancePort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal

/**
 * [KisLedgerSelfHealWorker] 단위 테스트 — ADR 0136 (KIS 실잔고 ↔ strategy_position self-heal).
 *
 * 핵심 검증:
 * - drift 감지(KIS qty ≠ ledger sum) → KIS 권위 주입 rebuild 정정.
 * - rebuild 적용 성공 → WARN(KIS_LEDGER_SELF_HEAL_CORRECTED) + index refresh.
 * - rebuild 미적용(교차검산 불일치) → CRITICAL(KIS_LEDGER_SELF_HEAL_UNRESOLVED) P0 알림(임의 wipe 0).
 * - KIS 조회 실패 → graceful skip(예외 전파 0).
 * - 임계 미만 diff → 정정 skip. 종목 실패 격리.
 */
class KisLedgerSelfHealWorkerTest {
    private val kisBalancePort: ReconcileKisBalancePort = mock()
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository = mock()
    private val strategyPositionRepo: StrategyPositionJpaRepository = mock()
    private val adapter: ConsumerExecutionStrategyPositionAdapter = mock()
    private val lockAdapter: ReconcileLockAdapter = mock()
    private val systemEventLogger: SystemEventLogger = mock()
    private val systemEventLoggerProvider: ObjectProvider<SystemEventLogger> = mock()
    private val holdingIndex: HoldingPositionIndex = mock()
    private val holdingIndexProvider: ObjectProvider<HoldingPositionIndex> = mock()

    private val worker =
        KisLedgerSelfHealWorker(
            kisBalancePort = kisBalancePort,
            credentialRepository = credentialRepository,
            strategyPositionJpaRepository = strategyPositionRepo,
            strategyPositionAdapter = adapter,
            reconcileLockAdapter = lockAdapter,
            systemEventLoggerProvider = systemEventLoggerProvider,
            holdingPositionIndexProvider = holdingIndexProvider,
            magicjarMode = "REAL",
            enabled = true,
            driftThreshold = 1,
        )

    @BeforeEach
    fun setUp() {
        whenever(systemEventLoggerProvider.ifAvailable).thenReturn(systemEventLogger)
        whenever(holdingIndexProvider.getIfAvailable()).thenReturn(holdingIndex)
        whenever(lockAdapter.acquireLock(any())).thenReturn(true)
        whenever(strategyPositionRepo.findDistinctCodesByUserIdAndQuantityPositive(any())).thenReturn(emptyList())
    }

    @Test
    fun `재진입 stranded — KIS 26주 vs ledger 0 drift → KIS 권위 rebuild 정정 + WARN`() {
        // 095500 미래나노텍 재현: KIS 26주 보유인데 strategy_position ledger 0 (재진입 BUY 미반영).
        whenever(kisBalancePort.fetchAllBalances("user-A"))
            .thenReturn(listOf(holding("095500", 26)))
        whenever(strategyPositionRepo.sumQuantityByUserAndCode("user-A", "095500")).thenReturn(0)
        whenever(adapter.rebuildStrategyPositionFromKisAuthority("user-A", "095500", "REAL", 26)).thenReturn(true)

        val corrected = worker.selfHealForUser("user-A", "REAL")

        assertThat(corrected).isEqualTo(1)
        verify(adapter).rebuildStrategyPositionFromKisAuthority("user-A", "095500", "REAL", 26)
        verify(holdingIndex).onFillRefresh("095500")
        assertThat(emittedActions()).contains("KIS_LEDGER_SELF_HEAL_CORRECTED")
        assertThat(emittedSeverities()).contains(SystemEventSeverity.WARN)
    }

    @Test
    fun `유령 over-count — KIS 0 vs ledger 5 drift → KIS 권위(0) rebuild 정정`() {
        // KIS 미보유(응답 없음 = 0)인데 strategy_position 5주 유령 잔존. union 으로 도달.
        whenever(kisBalancePort.fetchAllBalances("user-A")).thenReturn(emptyList())
        whenever(strategyPositionRepo.findDistinctCodesByUserIdAndQuantityPositive("user-A"))
            .thenReturn(listOf("005930"))
        whenever(strategyPositionRepo.sumQuantityByUserAndCode("user-A", "005930")).thenReturn(5)
        whenever(adapter.rebuildStrategyPositionFromKisAuthority("user-A", "005930", "REAL", 0)).thenReturn(true)

        val corrected = worker.selfHealForUser("user-A", "REAL")

        assertThat(corrected).isEqualTo(1)
        verify(adapter).rebuildStrategyPositionFromKisAuthority("user-A", "005930", "REAL", 0)
    }

    @Test
    fun `rebuild 교차검산 불일치 → 미적용 + CRITICAL P0 알림(임의 wipe 0)`() {
        whenever(kisBalancePort.fetchAllBalances("user-A"))
            .thenReturn(listOf(holding("095500", 26)))
        whenever(strategyPositionRepo.sumQuantityByUserAndCode("user-A", "095500")).thenReturn(0)
        // rebuild 가 KIS 권위와 정확히 일치 못 함(외부 주문 혼재·진실원 누락) → false.
        whenever(adapter.rebuildStrategyPositionFromKisAuthority("user-A", "095500", "REAL", 26)).thenReturn(false)

        val corrected = worker.selfHealForUser("user-A", "REAL")

        assertThat(corrected).isEqualTo(0)
        // index refresh 안 함(정정 미적용), CRITICAL 알림.
        verify(holdingIndex, never()).onFillRefresh(any())
        assertThat(emittedActions()).contains("KIS_LEDGER_SELF_HEAL_UNRESOLVED")
        assertThat(emittedSeverities()).contains(SystemEventSeverity.CRITICAL)
    }

    @Test
    fun `drift 없음(KIS == ledger) → 정정 skip`() {
        whenever(kisBalancePort.fetchAllBalances("user-A"))
            .thenReturn(listOf(holding("005930", 10)))
        whenever(strategyPositionRepo.sumQuantityByUserAndCode("user-A", "005930")).thenReturn(10)

        val corrected = worker.selfHealForUser("user-A", "REAL")

        assertThat(corrected).isEqualTo(0)
        verify(adapter, never()).rebuildStrategyPositionFromKisAuthority(any(), any(), any(), any())
    }

    @Test
    fun `KIS 조회 실패 → graceful skip(예외 전파 0, rebuild 호출 0)`() {
        whenever(kisBalancePort.fetchAllBalances("user-A")).thenThrow(RuntimeException("KIS 9443 down"))

        val corrected = worker.selfHealForUser("user-A", "REAL")

        assertThat(corrected).isEqualTo(0)
        verify(adapter, never()).rebuildStrategyPositionFromKisAuthority(any(), any(), any(), any())
    }

    @Test
    fun `종목 실패 격리 — 한 종목 rebuild 예외여도 나머지 정정 진행`() {
        whenever(kisBalancePort.fetchAllBalances("user-A"))
            .thenReturn(listOf(holding("095500", 26), holding("005930", 3)))
        whenever(strategyPositionRepo.sumQuantityByUserAndCode("user-A", "095500")).thenReturn(0)
        whenever(strategyPositionRepo.sumQuantityByUserAndCode("user-A", "005930")).thenReturn(0)
        whenever(adapter.rebuildStrategyPositionFromKisAuthority("user-A", "095500", "REAL", 26))
            .thenThrow(RuntimeException("boom"))
        whenever(adapter.rebuildStrategyPositionFromKisAuthority("user-A", "005930", "REAL", 3)).thenReturn(true)

        val corrected = worker.selfHealForUser("user-A", "REAL")

        assertThat(corrected).isEqualTo(1)
        verify(adapter).rebuildStrategyPositionFromKisAuthority("user-A", "005930", "REAL", 3)
    }

    @Test
    fun `게이트 off → runForAllActiveUsers 미호출(cron no-op)`() {
        val gatedOff =
            KisLedgerSelfHealWorker(
                kisBalancePort = kisBalancePort,
                credentialRepository = credentialRepository,
                strategyPositionJpaRepository = strategyPositionRepo,
                strategyPositionAdapter = adapter,
                reconcileLockAdapter = lockAdapter,
                systemEventLoggerProvider = systemEventLoggerProvider,
                holdingPositionIndexProvider = holdingIndexProvider,
                magicjarMode = "REAL",
                enabled = false,
                driftThreshold = 1,
            )

        gatedOff.runSelfHealCron()

        verify(credentialRepository, never()).findAllByAccountType(any())
    }

    @Test
    fun `runForAllActiveUsers — lock 미취득 user skip`() {
        whenever(credentialRepository.findAllByAccountType("REAL"))
            .thenReturn(listOf(cred("user-A"), cred("user-B")))
        whenever(lockAdapter.acquireLock("user-A")).thenReturn(false)
        whenever(lockAdapter.acquireLock("user-B")).thenReturn(true)
        whenever(kisBalancePort.fetchAllBalances("user-B")).thenReturn(emptyList())

        worker.runForAllActiveUsers()

        // user-A 는 lock 미취득 → KIS 조회 0. user-B 만 진행.
        verify(kisBalancePort, never()).fetchAllBalances("user-A")
        verify(kisBalancePort).fetchAllBalances("user-B")
        verify(lockAdapter).releaseLock("user-B")
    }

    // --- helpers ---

    private fun holding(
        code: String,
        qty: Int,
    ) = ReconcileHolding(symbol = code, quantity = qty, avgPrice = BigDecimal("10000"))

    private fun cred(userId: String) =
        ConsumerUserKisCredentialJpaEntity(
            id = ConsumerUserKisCredentialId(userId = userId, accountType = "REAL"),
            accountNumber = "50000000",
            accountPrdtCd = "01",
            appKeyEnc = ByteArray(8),
            secretKeyEnc = ByteArray(8),
        )

    private fun emittedActions(): List<String> = capturedEvents().map { it.action }

    private fun emittedSeverities(): List<SystemEventSeverity> = capturedEvents().map { it.severity }

    private fun capturedEvents(): List<SystemEvent> {
        val captor = argumentCaptor<SystemEvent>()
        verify(systemEventLogger, org.mockito.kotlin.atLeastOnce()).emit(captor.capture())
        return captor.allValues
    }
}
