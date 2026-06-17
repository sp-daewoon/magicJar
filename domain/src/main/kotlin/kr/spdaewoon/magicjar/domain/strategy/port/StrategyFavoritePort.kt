package kr.spdaewoon.magicjar.domain.strategy.port

/**
 * 전략 즐겨찾기 영속 포트 — api 모듈 `StrategyFavoriteJpaAdapter` 구현 (spec 2026-06-18 §5.2).
 *
 * 헥사고날 아키텍처 — domain이 port 정의, api가 adapter 구현.
 * ADR 0051 함정 회피: 구현 클래스에서만 helper 메서드 허용, 본 interface는 abstract fun 만.
 */
interface StrategyFavoritePort {
    /** 즐겨찾기 추가 — 멱등 (UNIQUE (user_id, strategy_id) 충돌 무시). */
    fun add(
        userId: Long,
        strategyId: Long,
    )

    /** 즐겨찾기 해제 — 없으면 no-op. */
    fun remove(
        userId: Long,
        strategyId: Long,
    )

    /** 사용자의 즐겨찾기 strategyId 목록. */
    fun strategyIdsOf(userId: Long): List<Long>
}
