package com.mj.api.infrastructure.persistence.screener

import com.mj.api.infrastructure.persistence.market.InstrumentJpaRepository
import com.mj.domain.market.instrument.InstrumentType
import com.mj.domain.strategy.screener.Universe
import com.mj.domain.strategy.screener.UniverseResolver
import org.springframework.stereotype.Component

/**
 * [UniverseResolver] JPA 구현체.
 *
 * - KOSPI200 / KOSDAQ150: instrument_index_member.index_group 기준
 * - ETF / ETN: instrument.type 기준 + active=true
 * - AllListed: instrument.active=true 전체
 * - Custom: 입력 코드 중 instrument 테이블에 존재하는 것만 (유효성 필터)
 * - SymbolGroup: market + type + active 동적 조합
 */
@Component
class UniverseResolverAdapter(
    private val instrumentRepo: InstrumentJpaRepository,
    private val indexMemberRepo: InstrumentIndexMemberJpaRepository,
) : UniverseResolver {
    override fun resolve(universe: Universe): List<String> =
        when (universe) {
            is Universe.Kospi200 ->
                indexMemberRepo
                    .findActiveCodesByIndexGroup("KOSPI200")
                    .distinct()

            is Universe.Kosdaq150 ->
                indexMemberRepo
                    .findActiveCodesByIndexGroup("KOSDAQ150")
                    .distinct()

            is Universe.Etf ->
                instrumentRepo
                    .findByTypeAndActive(InstrumentType.ETF, true)
                    .map { it.code }
                    .distinct()

            is Universe.Etn ->
                instrumentRepo
                    .findByTypeAndActive(InstrumentType.ETN, true)
                    .map { it.code }
                    .distinct()

            is Universe.AllListed ->
                instrumentRepo
                    .findByActive(true)
                    .map { it.code }
                    .distinct()

            is Universe.Custom ->
                // 존재 확인 — 없는 코드 제거 (방어적)
                universe.codes
                    .distinct()
                    .filter { code -> instrumentRepo.existsById(code) }

            is Universe.SymbolGroup -> {
                val markets = universe.markets
                val types = universe.types
                val sectors = universe.sectors
                val all = instrumentRepo.findByActive(universe.activeOnly)
                all
                    .asSequence()
                    .filter { inst ->
                        markets == null || inst.market in markets
                    }.filter { inst ->
                        types == null || inst.type in types
                    }.filter { inst ->
                        sectors == null || sectors.isEmpty() || (inst.sector != null && inst.sector in sectors)
                    }.map { it.code }
                    .distinct()
                    .toList()
            }
        }
}
