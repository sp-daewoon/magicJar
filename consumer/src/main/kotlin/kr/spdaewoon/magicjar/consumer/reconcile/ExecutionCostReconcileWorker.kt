package kr.spdaewoon.magicjar.consumer.reconcile

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

/**
 * 거래비용 reconcile cron 워커 — 손익 net화 (follow-up H, 2026-06-22 market-data).
 *
 * 장 마감 후 당일 매도 체결의 fee/tax 를 KIS 실비용(TTTC8715R)으로 안분 영속한다([ExecutionCostReconcileService]).
 * 이후 PerformanceService FIFO 가 자동으로 net 을 내 대시보드·autopilot 손익이 gross 과대표시를 멈춘다.
 *
 * ### cron (장 마감 후)
 * 평일 16:30 KST — 정규장(15:30) + 시간외(~16:00) 종료 후 KIS 기간별매매손익이 당일 비용을 확정한 시점.
 * BalanceReconcileJob(시장 OPEN 5분) 과 시간대 분리 — rate limit 충돌 회피.
 *
 * ### REAL 전용·자본 안전
 * MOCK 은 KIS 손익 TR 부재라 skip. read-then-update(멱등 fee=0&tax=0 행만) — 주문·포지션 변경 0.
 */
@Component
class ExecutionCostReconcileWorker(
    private val executionCostReconcileService: ExecutionCostReconcileService,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    // GAP-2 잔여(감사 §8) — accountType을 DB 활성모드 기준으로. null(구 테스트)이면 env fallback.
    private val activeAccountTypeResolver: kr.spdaewoon.magicjar.consumer.order.ActiveAccountTypeResolver? = null,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String,
    @Value("\${magicjar.performance.cost-reconcile.enabled:true}")
    private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(ExecutionCostReconcileWorker::class.java)
    private val kst = ZoneId.of("Asia/Seoul")

    /**
     * 장 마감 후 당일 비용 reconcile — KST 평일 16:30.
     */
    @Scheduled(cron = "\${magicjar.performance.cost-reconcile.cron:0 30 16 * * MON-FRI}", zone = "Asia/Seoul")
    fun runDailyCron() {
        if (!enabled) {
            log.info("비용 reconcile cron 비활성(magicjar.performance.cost-reconcile.enabled=false) — skip")
            return
        }
        val today = LocalDate.now(kst)
        runForAllRealUsers(today, today)
    }

    /**
     * REAL 자격증명 사용자 전체에 대해 [from, to] 비용 reconcile 실행. 관리 endpoint·cron 공용 진입점.
     */
    internal fun runForAllRealUsers(
        from: LocalDate,
        to: LocalDate,
    ): List<ExecutionCostReconcileResult> {
        val accountType =
            activeAccountTypeResolver?.activeAccountType()
                ?: RuntimeModeParser.accountTypeOrMock(magicjarMode)
        if (accountType != AccountType.REAL) {
            log.info("비용 reconcile — REAL 아님(accountType={}) skip. KIS 손익 TR 은 REAL 전용.", accountType.name)
            return emptyList()
        }
        val creds = credentialRepository.findAllByAccountType(AccountType.REAL.name)
        if (creds.isEmpty()) {
            log.info("비용 reconcile — REAL 자격증명 사용자 0건 idle")
            return emptyList()
        }
        log.info("비용 reconcile 시작 — 사용자={}명 [{}~{}]", creds.size, from, to)
        return creds.map { cred ->
            val userId = cred.id.userId
            try {
                executionCostReconcileService.reconcile(userId, from, to)
            } catch (e: Exception) {
                log.error("비용 reconcile — user 처리 실패: userId={} err={}", maskUserId(userId), e.message, e)
                ExecutionCostReconcileResult.failed(userId, from, to, e.message)
            }
        }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
