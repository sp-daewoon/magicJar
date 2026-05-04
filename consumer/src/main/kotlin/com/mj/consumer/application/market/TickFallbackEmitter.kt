package com.mj.consumer.application.market

import com.mj.consumer.messaging.producer.TickPublisher
import com.mj.domain.market.cache.port.QuoteCache
import com.mj.domain.market.event.TickEvent
import com.mj.domain.market.port.CandleRepository
import com.mj.domain.market.session.MarketSessionState
import com.mj.domain.market.session.port.MarketSessionResolver
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * 장 마감·KIS 끊김·휴일에 stale tick을 발행해 frontend 차트 정지를 방지한다.
 *
 * plan T2-R2-A3 §T19 + ADR 0033 (3-tier fallback).
 *
 * 동작 (10초 주기):
 *   1. 활성 구독 종목 = SubscriptionManager.activeCodes()
 *   2. 각 종목에 대해 MarketSession.state로 분기:
 *      - **Open / PreMarket**: QuoteCache.getLatest 조회.
 *        없거나 asOf > 30초 전이면 cache stale tick 1회 발행 (frontend stale 배지 노출).
 *        그렇지 않으면 skip (라이브 tick 정상 흐름).
 *      - **PostMarket / Closed / Holiday**:
 *        a) QuoteCache.getLatest 있으면 stale=true source="cache" emit.
 *        b) 없으면 candleRepository.findLastClose → stale=true source="last-day-close" emit.
 *        c) 둘 다 없으면 emit 없음.
 *
 * Profile dev|prd 한정. mock에서는 MockTickGenerator가 1초마다 tick 발행하므로 fallback 불필요.
 *
 * 운영 토글: `magicjar.market.tick-fallback.enabled` (default true).
 */
@Component
@Profile("dev | prd")
@ConditionalOnProperty(
    prefix = "magicjar.market.tick-fallback",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class TickFallbackEmitter(
    private val subscriptionManager: SubscriptionManager,
    private val sessionResolver: MarketSessionResolver,
    private val quoteCache: QuoteCache,
    private val candleRepository: CandleRepository,
    private val tickPublisher: TickPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${magicjar.market.tick-fallback.interval-ms:10000}")
    fun emit() {
        val codes = subscriptionManager.activeCodes()
        if (codes.isEmpty()) return

        val session =
            try {
                sessionResolver.resolve()
            } catch (e: Exception) {
                log.warn("[TICK_FALLBACK] sessionResolver error — skip cycle: {}", e.message)
                return
            }

        val state = session.state
        val now = Instant.now()
        val tradingHours = state is MarketSessionState.Open || state is MarketSessionState.PreMarket
        var emitted = 0

        for (code in codes) {
            try {
                val event =
                    if (tradingHours) {
                        buildOpenStaleTick(code, now)
                    } else {
                        buildClosedTick(code, session.lastBusinessDate, now)
                    }
                if (event != null) {
                    tickPublisher.publish(event)
                    emitted++
                }
            } catch (e: Exception) {
                log.warn("[TICK_FALLBACK] emit error code={}: {}", code, e.message)
            }
        }

        if (emitted > 0) {
            log.debug(
                "[TICK_FALLBACK] state={} cycle: emitted={}/{}",
                state::class.simpleName,
                emitted,
                codes.size,
            )
        }
    }

    /**
     * 정규장 시간대에 30초 이상 라이브 tick이 끊긴 종목에만 cache stale tick 발행.
     * 라이브 흐름이 정상이면 null (skip).
     */
    private fun buildOpenStaleTick(
        code: String,
        now: Instant,
    ): TickEvent? {
        val snapshot = quoteCache.getLatest(code) ?: return null
        val gap = Duration.between(snapshot.asOf, now)
        if (gap < OPEN_STALE_THRESHOLD) return null
        return TickEvent(
            code = code,
            at = now,
            price = snapshot.price,
            volume = snapshot.volume ?: 0L,
            source = SOURCE_CACHE,
            stale = true,
            asOf = snapshot.asOf,
        )
    }

    /**
     * 장외 시간대에 cache 또는 last-day-close 기반 stale tick 발행.
     * 둘 다 없으면 null (skip).
     */
    private fun buildClosedTick(
        code: String,
        asOfDate: java.time.LocalDate,
        now: Instant,
    ): TickEvent? {
        val cached = quoteCache.getLatest(code)
        if (cached != null) {
            return TickEvent(
                code = code,
                at = now,
                price = cached.price,
                volume = cached.volume ?: 0L,
                source = SOURCE_CACHE,
                stale = true,
                asOf = cached.asOf,
            )
        }
        val candle = candleRepository.findLastClose(code, asOfDate) ?: return null
        return TickEvent(
            code = code,
            at = now,
            price = candle.close,
            volume = candle.volume,
            source = SOURCE_LAST_DAY_CLOSE,
            stale = true,
            asOf = candle.openTime,
        )
    }

    companion object {
        /** 정규장 시간 ↔ 마지막 라이브 tick 사이 허용 격차. 초과 시 cache stale tick 발행. */
        val OPEN_STALE_THRESHOLD: Duration = Duration.ofSeconds(30)

        const val SOURCE_CACHE: String = "CACHED"
        const val SOURCE_LAST_DAY_CLOSE: String = "LAST_DAY_CLOSE"
    }
}
