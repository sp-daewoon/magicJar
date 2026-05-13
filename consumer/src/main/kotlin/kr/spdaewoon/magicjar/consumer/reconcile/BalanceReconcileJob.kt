package kr.spdaewoon.magicjar.consumer.reconcile

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.reconcile.ReconcileTriggerSource
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Reconcile cron 워커 (ADR 0070 §2-9 + §5 분산락).
 *
 * ### cron 매트릭스 (ADR 0070 §2-9 LOCK)
 * | 시점 | cron expression | 사유 |
 * |---|---|---|
 * | 시장 OPEN | `0 *\/5 9-15 * * MON-FRI` (KST) | 매매 활성 — drift 빠른 감지 |
 * | 시장 CLOSED | `0 0 *\/1 * * *` (1시간 주기) | 야간·주말 catch-up |
 *
 * 본 PR은 시장 OPEN cron 단일 LOCK — CLOSED 1시간 catch-up은 backlog §48 (MarketSessionFsm 통합 시 보강).
 *
 * ### 분산락 패턴 (ADR 0038 KIS OAuth 분산락 정합)
 * Redis `reconcile:lock:{userId}` SETNX 5분 — 다중 인스턴스 동시 실행 방지.
 *
 * ### Phase 2 graceful fallback
 * `ConsumerUserKisCredentialJpaRepository`는 baseline 이미 존재 — 활성 user 조회는 Phase 1 단독 가능.
 *
 * AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Component
class BalanceReconcileJob(
    private val balanceReconcileService: BalanceReconcileService,
    private val reconcileLockAdapter: ReconcileLockAdapter,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
) {
    private val log = LoggerFactory.getLogger(BalanceReconcileJob::class.java)

    companion object {
        private const val ACCOUNT_TYPE = "REAL" // 정보 수집 = 실전 키 정책 (사용자 메모리 feedback_kis_key_realonly_for_data)
    }

    /**
     * 시장 OPEN 5분 cron — KST 09:00~15:55 MON-FRI.
     *
     * cron: 매 5분 0초 (예: 09:00, 09:05, ..., 15:55).
     */
    @Scheduled(cron = "0 */5 9-15 * * MON-FRI", zone = "Asia/Seoul")
    fun runMarketOpenCron() {
        log.info("BalanceReconcileJob — 시장 OPEN cron fires")
        runForAllActiveUsers(ReconcileTriggerSource.CRON)
    }

    /**
     * 활성 user 전체 reconcile 실행 (직렬 — Rate Limit 안전).
     *
     * @param triggerSource CRON · MANUAL · API
     */
    internal fun runForAllActiveUsers(triggerSource: ReconcileTriggerSource) {
        val activeCreds = credentialRepository.findAllByAccountType(ACCOUNT_TYPE)
        if (activeCreds.isEmpty()) {
            log.info("BalanceReconcileJob — 활성 사용자 0명 idle")
            return
        }

        log.info("BalanceReconcileJob — 활성 user {}명 처리 시작", activeCreds.size)

        activeCreds.forEach { cred ->
            val userId = cred.id.userId
            if (!reconcileLockAdapter.acquireLock(userId)) {
                log.info("BalanceReconcileJob — lock 미취득 (다른 인스턴스 처리 중): userId={}", maskUserId(userId))
                return@forEach
            }
            try {
                val result = balanceReconcileService.reconcileUser(userId, triggerSource)
                result.id?.let { reconcileLockAdapter.cacheLastRun(userId, it) }
            } catch (e: Exception) {
                log.error("BalanceReconcileJob — user 처리 실패: userId={} err={}", maskUserId(userId), e.message, e)
            } finally {
                reconcileLockAdapter.releaseLock(userId)
            }
        }

        log.info("BalanceReconcileJob — 완료")
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
