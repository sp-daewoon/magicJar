package com.mj.consumer.application.session

import com.mj.domain.market.session.MarketSession
import com.mj.domain.market.session.MarketSessionState
import com.mj.domain.market.session.port.MarketSessionResolver
import com.mj.domain.market.session.zoneKst
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * mock profile용 NoOp MarketSessionResolver. 항상 Open 반환.
 * spec §8 component×profile 매트릭스, ADR 0032.
 * CI·단위테스트·로컬 demo에서 캘린더 DB 의존 없이 작동.
 */
@Component
@Profile("mock")
class NoOpMarketSessionResolverImpl : MarketSessionResolver {
    override fun resolve(): MarketSession {
        val zone = zoneKst()
        val now = OffsetDateTime.now(zone)
        return MarketSession(
            state = MarketSessionState.Open,
            kstAt = now,
            lastBusinessDate = LocalDate.now(zone),
            nextOpenAt = null,
        )
    }
}
