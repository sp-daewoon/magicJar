package com.mj.consumer.application.guard

import com.mj.domain.common.Order
import com.mj.domain.market.session.MarketSessionState
import com.mj.domain.market.session.port.MarketSessionResolver
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.core.annotation.Order as SpringOrder

/**
 * 장 상태(MarketSession) 기반 주문 사전 가드. spec §7.1, ADR 0034, ADR 0042 @Order(150).
 *
 * - mock profile: Bean 미등록 (NoOpMarketSessionResolverImpl 사용 — 장 상태 무관 항상 PASS).
 * - dev profile: `magicjar.guard.market-session.offline-fallback=true` 시 Closed/Holiday에도 PASS
 *   (토요일 dogfooding 등 개발 편의용).
 * - prd profile: offline-fallback 무시 — Closed/Holiday는 항상 REJECT.
 *
 * OPEN 상태일 때는 항상 PASS. PreMarket·PostMarket은 KIS가 부분적으로 허용하므로 PASS
 * (KIS 거부 시 KisApiError audit trail로 처리 — ADR 0034 §2-3).
 */
@Component
@Profile("dev | prd")
@SpringOrder(150)
class MarketSessionGuard(
    private val sessionResolver: MarketSessionResolver,
    /** dev profile에서 장외 시간에도 주문 통과 허용 여부. prd에선 무시. */
    @Value("\${magicjar.guard.market-session.offline-fallback:false}")
    private val offlineFallback: Boolean,
    @Value("\${spring.profiles.active:dev}")
    private val activeProfile: String,
) : OrderGuard {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun check(
        userId: String,
        accountNo: String,
        order: Order,
    ): GuardDecision {
        val session = sessionResolver.resolve()
        val state = session.state

        return when (state) {
            // 정규장·동시호가·장후 — PASS (KIS 자체 검증에 위임)
            MarketSessionState.Open,
            MarketSessionState.PreMarket,
            MarketSessionState.PostMarket,
            -> {
                log.debug("[SESSION_GUARD] PASS — state={} order_id={}", state, order.id)
                GuardDecision.Pass
            }

            // 주말·오프아워
            is MarketSessionState.Closed -> {
                if (isOfflineFallbackAllowed()) {
                    log.info(
                        "[SESSION_GUARD] PASS (offline-fallback) — state={} reason={} order_id={}",
                        state::class.simpleName,
                        state.reason,
                        order.id,
                    )
                    GuardDecision.Pass
                } else {
                    log.warn(
                        "[SESSION_GUARD] REJECT — state=Closed reason={} order_id={}",
                        state.reason,
                        order.id,
                    )
                    GuardDecision.Reject("SESSION_CLOSED:${state.reason}")
                }
            }

            // 공휴일·임시휴장
            is MarketSessionState.Holiday -> {
                if (isOfflineFallbackAllowed()) {
                    log.info(
                        "[SESSION_GUARD] PASS (offline-fallback) — state=Holiday name={} order_id={}",
                        state.name,
                        order.id,
                    )
                    GuardDecision.Pass
                } else {
                    log.warn(
                        "[SESSION_GUARD] REJECT — state=Holiday name={} order_id={}",
                        state.name,
                        order.id,
                    )
                    GuardDecision.Reject("NOT_BUSINESS_DAY:${state.name}")
                }
            }
        }
    }

    /**
     * offline-fallback 허용 여부 결정.
     * - prd profile에서는 항상 false (실거래 환경 — 장외 주문 허용 금지).
     * - dev profile에서만 설정값 적용.
     */
    private fun isOfflineFallbackAllowed(): Boolean = !activeProfile.contains("prd") && offlineFallback
}
