package kr.spdaewoon.magicjar.consumer.bootstrap

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisPositionCacheAdapter
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisBalanceAdapter
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialId
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.KillSwitchJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.PositionJpaRepository
import kr.spdaewoon.magicjar.domain.position.port.KisBalance
import kr.spdaewoon.magicjar.domain.position.port.KisHolding
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import java.math.BigDecimal
import java.time.Instant

/**
 * PositionBootstrapRunner 단위 테스트 (ADR 0042 idempotency·실패 fallback — 룰 4 정합).
 * - 테스트 1: 활성 사용자 0명 → idle return (KIS 호출 0건)
 * - 테스트 2: KIS 오류 → KillSwitch auto-ON + 다른 사용자 계속
 */
class PositionBootstrapRunnerTest {
    private val credentialRepository = mockk<ConsumerUserKisCredentialJpaRepository>()
    private val kisBalanceAdapter = mockk<KisBalanceAdapter>()
    private val positionJpaRepository = mockk<PositionJpaRepository>()
    private val instrumentJpaRepository = mockk<ConsumerInstrumentJpaRepository>()
    private val redisPositionCacheAdapter = mockk<RedisPositionCacheAdapter>()
    private val killSwitchJpaRepository = mockk<KillSwitchJpaRepository>()
    private val auditTrailJpaRepository = mockk<ConsumerAuditTrailJpaRepository>()

    private val runner =
        PositionBootstrapRunner(
            credentialRepository = credentialRepository,
            kisBalanceAdapter = kisBalanceAdapter,
            positionJpaRepository = positionJpaRepository,
            instrumentJpaRepository = instrumentJpaRepository,
            redisPositionCacheAdapter = redisPositionCacheAdapter,
            killSwitchJpaRepository = killSwitchJpaRepository,
            auditTrailJpaRepository = auditTrailJpaRepository,
        )

    private val args = DefaultApplicationArguments()

    @Test
    fun `활성 사용자 0명이면 idle return 하고 KIS 호출 없음`() {
        every { credentialRepository.findAllByAccountType("REAL") } returns emptyList()

        runner.run(args)

        // KIS 호출 없음 검증
        verify(exactly = 0) { kisBalanceAdapter.fetchBalance(any(), any()) }
    }

    @Test
    fun `KIS 호출 실패 시 KillSwitch auto-ON 후 다른 사용자 계속`() {
        val cred1Id = ConsumerUserKisCredentialId(userId = "11111111", accountType = "REAL")
        val cred1 =
            ConsumerUserKisCredentialJpaEntity(
                id = cred1Id,
                accountNumber = "11111111",
                appKeyEnc = ByteArray(0),
                secretKeyEnc = ByteArray(0),
            )
        val cred2Id = ConsumerUserKisCredentialId(userId = "22222222", accountType = "REAL")
        val cred2 =
            ConsumerUserKisCredentialJpaEntity(
                id = cred2Id,
                accountNumber = "22222222",
                appKeyEnc = ByteArray(0),
                secretKeyEnc = ByteArray(0),
            )

        every { credentialRepository.findAllByAccountType("REAL") } returns listOf(cred1, cred2)

        // user1 KIS 호출 실패
        every { kisBalanceAdapter.fetchBalance("11111111", AccountType.REAL) } throws RuntimeException("KIS timeout")
        justRun { killSwitchJpaRepository.enableKillSwitch(any(), any(), any()) }
        every { auditTrailJpaRepository.save(any()) } answers { firstArg() }

        // user2 KIS 호출 성공
        val balance2 =
            KisBalance(
                userId = "22222222",
                accountType = AccountType.REAL,
                holdings =
                    listOf(
                        KisHolding(code = "005930", quantity = 10, avgPrice = BigDecimal("70000")),
                    ),
                fetchedAt = Instant.now(),
            )
        every { kisBalanceAdapter.fetchBalance("22222222", AccountType.REAL) } returns balance2
        justRun { instrumentJpaRepository.upsertStub(any()) }
        justRun { positionJpaRepository.upsert(any(), any(), any(), any()) }
        justRun { redisPositionCacheAdapter.putHoldings(any(), any(), any()) }

        runner.run(args)

        // user1 KillSwitch ON 검증
        verify(exactly = 1) { killSwitchJpaRepository.enableKillSwitch("11111111", any(), any()) }
        // user2 position upsert 검증 — 계속 진행
        verify(exactly = 1) { positionJpaRepository.upsert("22222222", "005930", 10, BigDecimal("70000")) }
    }
}
