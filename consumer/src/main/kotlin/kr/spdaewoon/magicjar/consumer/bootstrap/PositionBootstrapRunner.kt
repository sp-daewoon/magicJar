package kr.spdaewoon.magicjar.consumer.bootstrap

import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisPositionCacheAdapter
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisBalanceAdapter
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.KillSwitchJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.PositionJpaRepository
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 부팅 잔고 워밍 (ADR 0042 Bootstrap — consumer ApplicationRunner).
 *
 * 실행 순서:
 *   STAGE 0-1: KIS REST 잔고 조회 (KisBalanceAdapter → inquire-balance TTTC8434R/VTTC8434R)
 *   STAGE 0-2: DB position upsert (PositionRepository.upsertAll — idempotent)
 *   STAGE 0-3: Redis position:current:{userId} SET (TTL 24h)
 *   STAGE 0-4: KIS WS 구독은 T-W2 비범위
 *
 * ADR 0042 §5-6 idempotency: upsert = ON CONFLICT DO UPDATE → 부팅 N회 안전.
 * ADR 0042 §2-5 실패 처리: 해당 user만 KillSwitch auto-ON + 다른 user 부팅 계속.
 * ADR 0043 KillSwitch 통합: enable("POSITION_BOOTSTRAP_FAILED", e.message)
 *
 * ### Bootstrap trigger 조건 (2026-05-20 A 의제 hotfix — 사용자 자동매매 의도 정합)
 *
 * 사용자 dogfooding evidence: 2026-05-20 08:53:30 자동매매 비활성 상태에서 Bootstrap 자동 실행
 * → KIS 500 Internal Server Error → KillSwitch auto-ON (audit_trail id=66).
 *
 * 진짜 root cause = "REAL 키 등록 = Bootstrap 자동 실행" 단순 trigger. 사용자 자동매매 의도
 * (3중 게이트 충족)와 무관하게 매 부팅마다 KIS 호출 → transient 결함 시 KillSwitch ON 폭주.
 *
 * fix: 3중 게이트 의무 (헌법 §실전 계좌 이중 게이트 + magicjar.bootstrap.position-on-startup):
 *   1. magicjar.mode = REAL
 *   2. magicjar.allow-real = true
 *   3. magicjar.bootstrap.position-on-startup = true (default true — 회귀 안전)
 *
 * 셋 중 하나라도 false → Bootstrap 전체 skip (KIS 호출 0 + KillSwitch ON risk 0).
 * dev/staging/test 환경에서 자동 skip 안전.
 *
 * per-user consent_at 검증은 후속 의제 (D — KillSwitch trigger 정책 보수화와 함께).
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class PositionBootstrapRunner(
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    private val kisBalanceAdapter: KisBalanceAdapter,
    private val positionJpaRepository: PositionJpaRepository,
    private val instrumentJpaRepository: ConsumerInstrumentJpaRepository,
    private val redisPositionCacheAdapter: RedisPositionCacheAdapter,
    private val killSwitchJpaRepository: KillSwitchJpaRepository,
    private val auditTrailJpaRepository: ConsumerAuditTrailJpaRepository,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String,
    @Value("\${magicjar.allow-real:false}")
    private val magicjarAllowReal: Boolean,
    @Value("\${magicjar.bootstrap.position-on-startup:true}")
    private val bootstrapOnStartup: Boolean,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(PositionBootstrapRunner::class.java)

    companion object {
        private const val BOOTSTRAP_ACCOUNT_TYPE = "REAL" // 실전 계좌 대상
        private const val AUDIT_ACTOR = "consumer"
        private val CACHE_TTL = Duration.ofHours(24)
    }

    /**
     * 부팅 시 1회 실행 (ADR 0042 §5-1).
     * 활성 사용자 0명이면 idle return (정상 부팅 계속).
     * 3중 게이트 (mode + allowReal + bootstrapOnStartup) 미충족 시 전체 skip (A 의제 hotfix).
     */
    override fun run(args: ApplicationArguments) {
        // A 의제 hotfix — 3중 게이트 검증 (KIS 호출 자체를 차단)
        if (!shouldRunBootstrap()) {
            return
        }

        log.info("Position bootstrap 시작")

        val activeCredentials = credentialRepository.findAllByAccountType(BOOTSTRAP_ACCOUNT_TYPE)
        if (activeCredentials.isEmpty()) {
            log.info("Position bootstrap — 활성 사용자 0명 idle (셋업 미완)")
            return
        }

        log.info("Position bootstrap — 활성 사용자 {}명 처리 시작", activeCredentials.size)

        // forEach 직렬 (ADR 0042 §2-5 — rate limit 안전)
        activeCredentials.forEach { cred ->
            val userId = cred.id.userId
            try {
                runBootstrap(userId, AccountType.valueOf(cred.id.accountType))
            } catch (e: Exception) {
                // 해당 user만 KillSwitch ON + 다른 user 계속 (ADR 0042 §2-5)
                val reason = "POSITION_BOOTSTRAP_FAILED"
                val detail = e.message?.take(200) ?: "unknown error"
                runCatching {
                    killSwitchJpaRepository.enableKillSwitch(
                        userId = userId,
                        reason = reason,
                        updatedBy = AUDIT_ACTOR,
                    )
                }.onFailure { ksErr ->
                    log.error("KillSwitch enable 실패 — user={}: {}", maskUserId(userId), ksErr.message)
                }
                runCatching {
                    auditTrailJpaRepository.save(
                        ConsumerAuditTrailJpaEntity(
                            actor = AUDIT_ACTOR,
                            action = reason,
                            entityType = "user",
                            entityId = userId,
                            detailJson = """{"reason":"$detail"}""",
                        ),
                    )
                }
                log.error(
                    "Position bootstrap 실패: user={} — KillSwitch auto-ON. error={}",
                    maskUserId(userId),
                    detail,
                    e,
                )
            }
        }

        log.info("Position bootstrap 완료")
    }

    private fun runBootstrap(
        userId: String,
        accountType: AccountType,
    ) {
        log.info(
            "STAGE 0-1 KIS 잔고 조회: user={} type={}",
            maskUserId(userId),
            accountType.name,
        )
        val kisBalance = kisBalanceAdapter.fetchBalance(userId, accountType)

        log.info(
            "STAGE 0-2 DB position upsert: user={} holdings={}건",
            maskUserId(userId),
            kisBalance.holdings.size,
        )
        kisBalance.holdings.forEach { holding ->
            // instrument stub upsert (position FK 의무 — §5-7)
            instrumentJpaRepository.upsertStub(holding.code)
            // position upsert — idempotent (ON CONFLICT DO UPDATE)
            positionJpaRepository.upsert(
                userId = userId,
                code = holding.code,
                quantity = holding.quantity,
                avgPrice = holding.avgPrice,
            )
        }
        // 보유 0건이면 position 변경 없음 (이전 포지션은 quantity=0으로 갱신 안 함 — T-W2에서 정합)

        log.info(
            "STAGE 0-3 Redis 워밍: user={} holdings={}건 ttl={}",
            maskUserId(userId),
            kisBalance.holdings.size,
            CACHE_TTL,
        )
        redisPositionCacheAdapter.putHoldings(userId, kisBalance.holdings, CACHE_TTL)

        // audit row INSERT — 성공
        runCatching {
            auditTrailJpaRepository.save(
                ConsumerAuditTrailJpaEntity(
                    actor = AUDIT_ACTOR,
                    action = "POSITION_BOOTSTRAP",
                    entityType = "user",
                    entityId = userId,
                    detailJson = """{"holdings":${kisBalance.holdings.size},"accountType":"${accountType.name}"}""",
                ),
            )
        }

        log.info(
            "Position bootstrap 완료: user={} holdings={}건",
            maskUserId(userId),
            kisBalance.holdings.size,
        )
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    /**
     * A 의제 hotfix (2026-05-20) — Bootstrap 실행 여부 3중 게이트 검증.
     *
     * 검증 매트릭스 (모두 true 일 때만 실행):
     *  - magicjar.mode = REAL (대소문자 무관)
     *  - magicjar.allow-real = true
     *  - magicjar.bootstrap.position-on-startup = true (default true — 회귀 안전)
     *
     * 셋 중 하나라도 false → log.info + return → KIS 호출 0 + KillSwitch ON risk 0.
     * dev/staging/test 환경에서 자동 skip 안전.
     */
    internal fun shouldRunBootstrap(): Boolean {
        if (!bootstrapOnStartup) {
            log.info(
                "Position bootstrap skip — magicjar.bootstrap.position-on-startup=false (명시 비활성)",
            )
            return false
        }
        if (!magicjarMode.equals("REAL", ignoreCase = true)) {
            log.info(
                "Position bootstrap skip — magicjar.mode={} (REAL 외 환경, 자동매매 비활성)",
                magicjarMode,
            )
            return false
        }
        if (!magicjarAllowReal) {
            log.info(
                "Position bootstrap skip — magicjar.allow-real=false (실주문 게이트 미충족)",
            )
            return false
        }
        return true
    }
}
