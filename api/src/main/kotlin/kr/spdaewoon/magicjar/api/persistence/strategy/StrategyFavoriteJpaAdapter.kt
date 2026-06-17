package kr.spdaewoon.magicjar.api.persistence.strategy

import kr.spdaewoon.magicjar.domain.strategy.port.StrategyFavoritePort
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 전략 즐겨찾기 JPA 어댑터 — [StrategyFavoritePort] 구현 (spec 2026-06-18 §5.2).
 *
 * 헌법 §6 (ADR 0051): helper 로직은 interface 0건, 이 클래스 위치.
 *
 * ### 멱등 add 설계 (진보 페르소나 — DRY·안전 동시)
 * - 1차: `existsByUserIdAndStrategyId`로 선체크 후 미존재 시에만 insert (정상 경로 멱등).
 * - 2차: 동시성으로 선체크-insert 사이 UNIQUE 충돌 시 [DataIntegrityViolationException] 흡수(no-op) —
 *   존재 체크만으로는 race window가 남으므로 DB UNIQUE 제약(ux_strategy_favorite_user_strategy)을
 *   최종 권위로 삼고 충돌을 멱등 흡수한다.
 */
@Component
class StrategyFavoriteJpaAdapter(
    private val repository: StrategyFavoriteJpaRepository,
) : StrategyFavoritePort {
    @Transactional
    override fun add(
        userId: Long,
        strategyId: Long,
    ) {
        if (repository.existsByUserIdAndStrategyId(userId, strategyId)) return
        runCatching {
            repository.save(StrategyFavoriteJpaEntity(userId = userId, strategyId = strategyId))
        }.recover { e ->
            // 동시 add race — UNIQUE 충돌은 멱등 흡수(이미 즐겨찾기 존재). 그 외 예외는 전파.
            if (e is DataIntegrityViolationException) null else throw e
        }.getOrThrow()
    }

    @Transactional
    override fun remove(
        userId: Long,
        strategyId: Long,
    ) {
        repository.deleteByUserIdAndStrategyId(userId, strategyId)
    }

    @Transactional(readOnly = true)
    override fun strategyIdsOf(userId: Long): List<Long> = repository.findAllByUserId(userId).map { it.strategyId }
}
