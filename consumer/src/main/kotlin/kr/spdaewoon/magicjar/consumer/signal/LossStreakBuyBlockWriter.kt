package kr.spdaewoon.magicjar.consumer.signal

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 연패 서킷의 **BUY_BLOCKED 전환 쓰기 트랜잭션 경계** — 진입 가드 ③ (2026-07-08).
 *
 * ## 왜 별도 bean 인가 (self-invocation·트랜잭션 함정 fix — D3 교훈 재사용)
 * [LossStreakCircuitBreaker] 는 체결 어댑터([ConsumerExecutionStrategyPositionAdapter.applyExecution]) 의 SELL 체결
 * hook 에서 동기 호출된다. 거기서 [ConsumerStrategyJpaRepository.autoBuyBlock](`@Modifying` UPDATE)을 직접 부르면
 * ① 어댑터의 활성 트랜잭션(position upsert)에 편승해 서킷 UPDATE 실패가 정상 체결 반영까지 롤백시키거나
 * ② tracker 안에서 `@Transactional` 자기호출은 프록시를 안 타 트랜잭션이 안 열리는(D3 사고, [SellRejectStrategyPauseWriter])
 * 함정에 빠진다. 따라서 쓰기 책임을 **별도 bean + `REQUIRES_NEW`** 로 분리해 독립 트랜잭션 경계를 확보한다 —
 * 서킷 전환은 체결 반영과 무관하게 독립 커밋되고, 서킷 write 실패가 체결 권위를 손상시키지 않는다.
 *
 * ## BUY_BLOCKED 선택 (기존 status 재사용 — 청산 계속)
 * [ConsumerStrategyJpaRepository.autoBuyBlock] 은 status=BUY_BLOCKED + disabled_reason=RISK_VIOLATION 로 전환한다.
 * BUY_BLOCKED 는 평가 path 를 유지하되 신규 BUY 만 막고 SELL/손절/익절은 허용하는 보호 상태다(StrategyStatus 정의).
 * ACTIVE 복귀는 자동 안 함(사용자 수동 — 다음날 판단).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 운영 가드 (ADR 0023 §2-4 정합)
 */
@Component
class LossStreakBuyBlockWriter(
    private val strategyRepository: ConsumerStrategyJpaRepository,
) {
    private val log = LoggerFactory.getLogger(LossStreakBuyBlockWriter::class.java)

    /**
     * 전략을 BUY_BLOCKED 로 전환(독립 쓰기 트랜잭션 — `REQUIRES_NEW`). 반환 = 갱신 행 수(0=대상 없음/이미 DELETED).
     *
     * caller(체결 어댑터)의 트랜잭션과 분리돼, 본 UPDATE 실패가 position 체결 반영을 롤백하지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun blockBuy(
        strategyId: Long,
        reason: String,
    ): Int {
        val updated = strategyRepository.autoBuyBlock(strategyId, reason)
        log.warn(
            "LOSS_STREAK_CIRCUIT → BUY_BLOCKED 전환: strategyId={} updatedRows={} reason={}",
            strategyId,
            updated,
            reason,
        )
        return updated
    }
}
