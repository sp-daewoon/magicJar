package kr.spdaewoon.magicjar.consumer.reconcile

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerExecutionStrategyPositionAdapter
import kr.spdaewoon.magicjar.consumer.persistence.PositionJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * strategy_position 부족 drift 주기 정합 워커 — ADR 0115 §P1-1 amend (2026-06-10 incident 재발 방지).
 *
 * ## 왜 별도 worker 인가 (incident 근본)
 * 체결 직후 drift 정합([ConsumerExecutionStrategyPositionAdapter.applyExecution] → reconcileDrift)은
 * **그 체결이 strategy_position 에 반영되는 경로가 살아 있을 때만** 작동한다. 그러나 2026-06-10 incident 는
 * `StrategyPositionExecutionReplayService` 의 "row 기존재 → replay skip"(over-count 회피 의도)로
 * applyExecution 경로 자체가 우회됐고, 그 strategy_position(qty=0 vs KIS 3주)은 **다음 체결 전까지 영구 잔존**해
 * 시그널 54건이 BLOCKED 됐다.
 *
 * 본 워커는 [BalanceReconcileJob] 주기(시장 OPEN 5분 cron)에 편승해 — **신규 cron 신설 0**(ADR 0115 §2-4 audit:
 * 신규 마이그레이션·토픽·cron 0 원칙) — user 의 KIS 권위 보유 종목 전체를 훑어 체결 이벤트와 무관하게
 * strategy_position drift 를 회복한다. 초과는 [PositionDriftCorrection] 내림, 부족은 귀속 ledger 정정이 자동 작동.
 *
 * ## 보수성 (자본 경로)
 * - DB 귀속 정렬만 — 주문 발행 0. KillSwitch·실주문 게이트 무영향.
 * - 정정 로직은 체결 직후 경로와 **동일 함수**([ConsumerExecutionStrategyPositionAdapter.reconcileStrategyPositionDrift]) —
 *   3중 게이트(귀속 명확·멱등·전체 정합 검산) 그대로. 모호하면 WARN-only.
 * - 종목별 try-catch — 한 종목 실패가 나머지 정합을 막지 않음.
 *
 * AI 호출 0 — 룰 기반 자본 흐름 정합 (ADR 0023 §2-4 정합).
 */
@Component
class StrategyPositionReconcileWorker(
    private val positionJpaRepository: PositionJpaRepository,
    private val strategyPositionAdapter: ConsumerExecutionStrategyPositionAdapter,
) {
    private val log = LoggerFactory.getLogger(StrategyPositionReconcileWorker::class.java)

    /**
     * 단일 user 의 KIS 권위 보유 종목 전체에 대해 strategy_position drift 정합 1회 수행.
     *
     * [BalanceReconcileJob.runForAllActiveUsers] 가 user 별 [BalanceReconcileService.reconcileUser] 직후 호출한다
     * (position 테이블이 KIS 잔고로 최신화된 직후 — strategy_position 정합의 권위 기준이 신선).
     *
     * @param userId 정합 대상 사용자.
     * @param modeName position.source 분기 (REAL/MOCK) — KIS 권위 잔고 조회 키.
     * @return 정합 시도한 종목 수(에러 종목 포함). 로깅·테스트용.
     */
    fun reconcileForUser(
        userId: String,
        modeName: String,
    ): Int {
        val holdings =
            try {
                positionJpaRepository.findByUserIdAndSourceAndQuantityGreaterThan(userId, modeName, 0)
            } catch (e: Exception) {
                log.warn(
                    "StrategyPositionReconcileWorker — KIS 권위 보유 조회 실패, skip: userId={} mode={} err={}",
                    maskUserId(userId),
                    modeName,
                    e.message,
                )
                return 0
            }
        if (holdings.isEmpty()) return 0

        var attempted = 0
        for (holding in holdings) {
            try {
                strategyPositionAdapter.reconcileStrategyPositionDrift(userId, holding.code, modeName)
                attempted++
            } catch (e: Exception) {
                log.error(
                    "StrategyPositionReconcileWorker — 종목 정합 실패: userId={} code={} mode={} err={}",
                    maskUserId(userId),
                    holding.code,
                    modeName,
                    e.message,
                )
            }
        }
        log.debug(
            "StrategyPositionReconcileWorker — user 정합 완료: userId={} mode={} 종목={}",
            maskUserId(userId),
            modeName,
            attempted,
        )
        return attempted
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
