package kr.spdaewoon.magicjar.consumer.reconcile

import kr.spdaewoon.magicjar.consumer.order.HoldingPositionIndex
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerExecutionStrategyPositionAdapter
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.reconcile.port.ReconcileKisBalancePort
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * ★ ADR 0136 — KIS 실잔고 ↔ strategy_position(ledger) 주기 self-heal 워커.
 *
 * ## 왜 (최고 관리자 지시 — 2026-06-29)
 * "autopilot 이 이런 drift 를 스스로 찾아 고쳐야 한다. 매번 사람이 지적해야 발견되면 안 된다."
 * 2026-06-29 라이브: 095500(미래나노텍) 09:40 재진입 BUY 26주가 strategy_position(ledger)에 미반영(KIS 26주 vs ledger 0)
 * → 익절 신호 `SELL_WITHOUT_POSITION` 차단(못 팜). 동시에 반대 종목은 ledger 유령(보유)·KIS 0 → `POSITION_LIMIT`
 * 점유로 신규 BUY 차단. **사람이 발견**했고 자동 감지·정정이 없었다.
 *
 * ## 무엇 (broker 진실원 교차검증 + 자동 정정)
 * 기존 [StrategyPositionReconcileWorker] 는 **내부 position 투영**을 권위로 rebuild 한다(체결 직후 경로 정합용). 그러나
 * position 투영 자체가 drift(체결 미반영)였을 때는 교차검산 기준이 오염돼 self-heal 이 작동 못 한다. 본 워커는
 * **KIS inquire-balance(9443·TTTC8434R) 실보유**([ReconcileKisBalancePort.fetchAllBalances])를 직접 권위로 주입해
 * ([ConsumerExecutionStrategyPositionAdapter.rebuildStrategyPositionFromKisAuthority]) order_execution 진실원으로
 * strategy_position 을 재계산·정정한다. broker = 진실원(MEMORY: kis_ground_truth_crosscheck).
 *
 * ## 정정 메커니즘 (안전 — KIS 일치 동기화만)
 * - **rebuild + KIS 교차검산**: `rebuilt.totalQuantity == kisQty` 일 때만 upsert. 불일치(외부 주문 혼재·진실원 누락)면
 *   미적용 + CRITICAL 알림(임의 wipe 금지 — 없는 물량 인식 risk 0).
 * - **순회 범위 union**: KIS 보유(qty>0) ∪ strategy_position 잔존(qty>0) — under-count(KIS만)·over-count(ledger 유령) 양방향.
 * - **HOLDING·PENDING_BUY 보호**: rebuild 는 order_execution 진실원 재계산이므로 보유분을 임의 삭제하지 않는다
 *   (KIS 실보유와 일치할 때만 적용). 따라서 ADR 0114 evict-금지 자원과 충돌 0.
 * - **KIS 조회 실패 → graceful skip**: 그 user 정합 skip(부분 진행), 워커 자체는 계속.
 *
 * ## 중복 회피 (DRY — 기존 자원 재사용)
 * - KIS 잔고 조회: [ReconcileKisBalancePort] (BalanceReconcileService 와 동일 포트).
 * - rebuild 엔진: [ConsumerExecutionStrategyPositionAdapter.rebuildStrategyPositionFromKisAuthority]
 *   (rebuildStrategyPositionFromLedger 의 authority 주입 오버로드 — fold·교차검산·멱등 100% 공유).
 * - 분산락: [ReconcileLockAdapter] (reconcile cron 과 동일 Redis SETNX 키 — 동시 실행 방지).
 * - in-memory 인덱스 refresh: [HoldingPositionIndex.onFillRefresh] (drift 정정 후 손절 인덱스 stale 차단).
 *
 * ## ENV 게이트 (default 보수적)
 * - `magicjar.reconcile.kis-ledger-self-heal.enabled` (default **false** — 명시 opt-in. 라이브 검증 후 활성).
 * - `magicjar.reconcile.kis-ledger-self-heal.cron` (default 장중 10분 — 매 10분 09~15시 MON-FRI KST).
 * - `magicjar.reconcile.kis-ledger-self-heal.drift-threshold` (default 1 — abs(diff) ≥ 임계만 정정 시도).
 *
 * AI 호출 0 — 룰 기반 자본 흐름 정합 (ADR 0023 §2-4 정합).
 */
@Component
class KisLedgerSelfHealWorker(
    private val kisBalancePort: ReconcileKisBalancePort,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    private val strategyPositionJpaRepository: StrategyPositionJpaRepository,
    private val strategyPositionAdapter: ConsumerExecutionStrategyPositionAdapter,
    private val reconcileLockAdapter: ReconcileLockAdapter,
    private val systemEventLoggerProvider: ObjectProvider<SystemEventLogger>,
    private val holdingPositionIndexProvider: ObjectProvider<HoldingPositionIndex>,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String,
    @Value("\${magicjar.reconcile.kis-ledger-self-heal.enabled:false}")
    private val enabled: Boolean,
    @Value("\${magicjar.reconcile.kis-ledger-self-heal.drift-threshold:1}")
    private val driftThreshold: Int,
) {
    private val log = LoggerFactory.getLogger(KisLedgerSelfHealWorker::class.java)

    companion object {
        private const val SYSTEM_EVENT_ACTOR = "KisLedgerSelfHealWorker"
    }

    /**
     * 장중 self-heal cron — default 10분(09~15 MON-FRI KST). reconcile cron(5분)과 다른 위상으로 KIS 호출 분산.
     * 게이트 off 면 즉시 return(no-op) — bean 은 합류하되 동작 0(롤백 안전).
     */
    @Scheduled(
        cron = "\${magicjar.reconcile.kis-ledger-self-heal.cron:0 */10 9-15 * * MON-FRI}",
        zone = "Asia/Seoul",
    )
    fun runSelfHealCron() {
        if (!enabled) {
            log.debug("KisLedgerSelfHealWorker — 게이트 off(self-heal.enabled=false) skip")
            return
        }
        log.info("KisLedgerSelfHealWorker — KIS 실잔고↔ledger self-heal cron fires")
        runForAllActiveUsers()
    }

    /**
     * 활성 user 전체 self-heal 1회. 분산락(reconcile 키 공유)으로 다중 인스턴스·reconcile cron 과 동시 실행 방지.
     * @return 정정 적용된 (user×code) 건수 — 로깅·테스트용.
     */
    internal fun runForAllActiveUsers(): Int {
        val accountType = RuntimeModeParser.accountTypeOrMock(magicjarMode)
        val creds =
            try {
                credentialRepository.findAllByAccountType(accountType.name)
            } catch (e: Exception) {
                log.error("KisLedgerSelfHealWorker — 자격증명 조회 실패: accountType={} err={}", accountType.name, e.message)
                return 0
            }
        if (creds.isEmpty()) {
            log.info("KisLedgerSelfHealWorker — 활성 사용자 0명 idle (accountType={})", accountType.name)
            return 0
        }

        var totalCorrected = 0
        creds.forEach { cred ->
            val userId = cred.id.userId
            if (!reconcileLockAdapter.acquireLock(userId)) {
                log.info("KisLedgerSelfHealWorker — lock 미취득(다른 인스턴스·reconcile 처리 중) skip: userId={}", maskUserId(userId))
                return@forEach
            }
            try {
                totalCorrected += selfHealForUser(userId, accountType.name)
            } catch (e: Exception) {
                log.error("KisLedgerSelfHealWorker — user 처리 실패: userId={} err={}", maskUserId(userId), e.message, e)
            } finally {
                reconcileLockAdapter.releaseLock(userId)
            }
        }
        log.info("KisLedgerSelfHealWorker — 완료: 사용자={}명 corrected={}건", creds.size, totalCorrected)
        return totalCorrected
    }

    /**
     * 단일 user self-heal — KIS 실잔고 흡수 → 종목별 ledger 대조 → drift 종목 rebuild(KIS 권위 주입) 정정.
     *
     * @param userId 정합 대상.
     * @param modeName position.source 분기(REAL/MOCK) — rebuild fold·mode 정합 키.
     * @return 정정 적용된 종목 수.
     */
    internal fun selfHealForUser(
        userId: String,
        modeName: String,
    ): Int {
        // 1) KIS 실잔고(broker 진실원) 흡수 — 실패 시 graceful skip(이 user 만).
        val kisHoldings =
            try {
                kisBalancePort.fetchAllBalances(userId)
            } catch (e: Exception) {
                log.warn(
                    "KisLedgerSelfHealWorker — KIS 실잔고 조회 실패, self-heal skip(다음 주기 회복): userId={} err={}",
                    maskUserId(userId),
                    e.message,
                )
                return 0
            }
        val kisQtyByCode = kisHoldings.associate { it.symbol to it.quantity.coerceAtLeast(0) }

        // 2) 순회 범위 union — KIS 보유(qty>0) ∪ strategy_position 잔존(qty>0). under/over 양방향.
        val codes = LinkedHashSet<String>()
        kisQtyByCode.filterValues { it > 0 }.keys.forEach { codes.add(it) }
        try {
            strategyPositionJpaRepository.findDistinctCodesByUserIdAndQuantityPositive(userId).forEach { codes.add(it) }
        } catch (e: Exception) {
            log.warn(
                "KisLedgerSelfHealWorker — strategy_position 잔존 조회 실패(부분 진행): userId={} err={}",
                maskUserId(userId),
                e.message,
            )
        }
        if (codes.isEmpty()) return 0

        var corrected = 0
        for (code in codes) {
            try {
                if (selfHealCode(userId, code, modeName, kisQtyByCode[code] ?: 0)) {
                    corrected++
                }
            } catch (e: Exception) {
                log.error(
                    "KisLedgerSelfHealWorker — 종목 self-heal 실패: userId={} code={} err={}",
                    maskUserId(userId),
                    code,
                    e.message,
                )
            }
        }
        return corrected
    }

    /**
     * 단일 종목 self-heal — ledger 합 vs KIS 실보유 대조 → drift 면 KIS 권위 주입 rebuild 정정.
     *
     * @return 정정 적용(true) / drift 없거나 미적용(false).
     */
    private fun selfHealCode(
        userId: String,
        code: String,
        modeName: String,
        kisQty: Int,
    ): Boolean {
        val ledgerSum =
            try {
                strategyPositionJpaRepository.sumQuantityByUserAndCode(userId, code)
            } catch (e: Exception) {
                log.warn("KisLedgerSelfHealWorker — ledger 합 조회 실패 skip: userId={} code={} err={}", maskUserId(userId), code, e.message)
                return false
            }

        val diff = kisQty - ledgerSum
        if (kotlin.math.abs(diff) < driftThreshold) return false

        log.warn(
            "KisLedgerSelfHealWorker — DRIFT 감지: userId={} code={} kisQty={} ledgerSum={} diff={} → KIS 권위 rebuild 시도",
            maskUserId(userId),
            code,
            kisQty,
            ledgerSum,
            diff,
        )

        // KIS 실보유를 권위로 주입 — order_execution 진실원 rebuild + 교차검산(== kisQty 일치 시만 적용).
        val applied =
            strategyPositionAdapter.rebuildStrategyPositionFromKisAuthority(userId, code, modeName, kisQty)

        if (applied) {
            refreshHoldingIndex(code)
            emitEvent(
                userId = userId,
                severity = SystemEventSeverity.WARN,
                action = "KIS_LEDGER_SELF_HEAL_CORRECTED",
                detailJson = """{"code":"$code","kisQty":$kisQty,"ledgerSumBefore":$ledgerSum,"diff":$diff}""",
            )
            log.warn(
                "KisLedgerSelfHealWorker — SELF-HEAL 정정 적용(KIS 권위 일치): userId={} code={} kisQty={} (ledger {}→{})",
                maskUserId(userId),
                code,
                kisQty,
                ledgerSum,
                kisQty,
            )
            return true
        }

        // rebuild 미적용 = order_execution 진실원으로 KIS 실보유를 정확히 재구성 못 함(외부 주문 혼재·진실원 누락).
        // 임의 덮어쓰기 금지(없는 물량 인식 risk 0). P0 알림으로 사람 판단 유도 — "스스로 못 고치는 drift" 는 즉시 보고.
        emitEvent(
            userId = userId,
            severity = SystemEventSeverity.CRITICAL,
            action = "KIS_LEDGER_SELF_HEAL_UNRESOLVED",
            detailJson = """{"code":"$code","kisQty":$kisQty,"ledgerSum":$ledgerSum,"diff":$diff,"reason":"REBUILD_CROSSCHECK_MISMATCH"}""",
        )
        log.error(
            "KisLedgerSelfHealWorker — SELF-HEAL 미해결(rebuild 교차검산 불일치 — 자동 정정 보류, P0 알림): " +
                "userId={} code={} kisQty={} ledgerSum={} diff={}",
            maskUserId(userId),
            code,
            kisQty,
            ledgerSum,
            diff,
        )
        return false
    }

    private fun refreshHoldingIndex(code: String) {
        val index = holdingPositionIndexProvider.getIfAvailable() ?: return
        try {
            index.onFillRefresh(code)
        } catch (e: Exception) {
            log.warn("KisLedgerSelfHealWorker — index refresh 실패(정정은 유지): code={} err={}", code, e.message)
        }
    }

    private fun emitEvent(
        userId: String,
        severity: SystemEventSeverity,
        action: String,
        detailJson: String,
    ) {
        systemEventLoggerProvider.ifAvailable?.let { logger ->
            runCatching {
                logger.emit(
                    SystemEvent(
                        category = SystemEventCategory.RECONCILE,
                        severity = severity,
                        actor = SYSTEM_EVENT_ACTOR,
                        action = action,
                        userId = userId,
                        detailJson = detailJson,
                    ),
                )
            }.onFailure { e -> log.error("KisLedgerSelfHealWorker — SystemEvent emit 실패: action={} err={}", action, e.message) }
        }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
