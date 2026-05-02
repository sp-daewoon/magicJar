package com.mj.domain.market.session.port

import com.mj.domain.market.session.MarketSession

/**
 * 현재 장 상태를 조회하는 포트. spec §4.1, ADR 0032.
 *
 * 구현체:
 * - consumer: MarketSessionResolverImpl (KST 시간 + MarketCalendarRepository + 1분 cache)
 * - mock profile: NoOpMarketSessionResolverImpl (항상 Open 반환)
 */
interface MarketSessionResolver {
    /** 현재(또는 지정 시각) 장 상태 스냅샷 반환. */
    fun resolve(): MarketSession
}
