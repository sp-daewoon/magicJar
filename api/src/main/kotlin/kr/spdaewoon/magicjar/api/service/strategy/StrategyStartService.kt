package kr.spdaewoon.magicjar.api.service.strategy

import kr.spdaewoon.magicjar.api.controller.dto.StrategyStartItem
import kr.spdaewoon.magicjar.api.controller.dto.StrategyStartResponse
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.domain.strategy.StrategyOwner
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyFavoritePort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 전략 시작 화면 조회 서비스 — 검증 템플릿(SYSTEM) + 내 전략(USER) + 즐겨찾기 합성 (spec 2026-06-18 §6.1).
 *
 * ### 책임 (진보 페르소나 — SRP)
 * 세 재료(템플릿·내 전략·즐겨찾기)를 단일 [StrategyStartResponse]로 합성하는 것만 담당한다.
 * 전략 CRUD([StrategyService])·즐겨찾기 영속([StrategyFavoritePort])은 각자 책임에 위임.
 *
 * ### mock 0건 (메모리 feedback_no_mock_data)
 * 재료 0건이면 빈 리스트 — 합성 데이터 0. 프론트가 "아직 없음 + 빈 캔버스로" 안내.
 *
 * ### 헌법 §6
 * Repository default fun 미사용 — [StrategyJpaRepository.findAllByOwner]·`findAllByUserIdAndStatusNot`(파생) 조합.
 */
@Service
class StrategyStartService(
    private val strategyJpaRepository: StrategyJpaRepository,
    private val favoritePort: StrategyFavoritePort,
) {
    @Transactional(readOnly = true)
    fun load(userId: Long): StrategyStartResponse {
        val favoriteIds = favoritePort.strategyIdsOf(userId).toSet()

        val templates =
            strategyJpaRepository
                .findAllByOwner(StrategyOwner.SYSTEM.name)
                .map { it.toStartItem(favorite = it.id != null && it.id in favoriteIds) }

        val myStrategies =
            strategyJpaRepository
                .findAllByUserIdAndStatusNot(
                    userId,
                    StrategyStatus.DELETED.name,
                    org.springframework.data.domain.PageRequest.of(
                        0,
                        MY_STRATEGY_PAGE_SIZE,
                        org.springframework.data.domain.Sort
                            .by(org.springframework.data.domain.Sort.Direction.DESC, "updatedAt"),
                    ),
                ).content
                .map { it.toStartItem(favorite = it.id != null && it.id in favoriteIds) }

        return StrategyStartResponse(
            templates = templates,
            myStrategies = myStrategies,
            favoriteIds = favoriteIds.toList().sorted(),
        )
    }

    private fun StrategyJpaEntity.toStartItem(favorite: Boolean): StrategyStartItem =
        StrategyStartItem(
            id = id ?: 0L,
            name = name,
            nameKo = nameKo,
            tf = tf,
            dslEntry = dslEntry,
            dslExit = dslExit,
            takeProfitPct = takeProfitPct,
            fixedStopLossPct = fixedStopLossPct,
            favorite = favorite,
        )

    private companion object {
        // 시작 화면은 내 전략 전건(상한). 운영 전략 수가 페이지를 넘기지 않는다(StrategyJpaAdapter 정합).
        const val MY_STRATEGY_PAGE_SIZE = 10000
    }
}
