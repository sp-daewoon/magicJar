package kr.spdaewoon.magicjar.consumer.risk

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kr.spdaewoon.magicjar.domain.risk.port.DailyPnlProvider
import kr.spdaewoon.magicjar.domain.risk.port.RiskPositionPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration

/**
 * 일일 실현 P&L 산출기 — KST 자정 reset (ADR 0067 §7-1 + B-1 정합).
 *
 *  1. Redis cache `daily_pnl:{userId}:{kstDate}` 우선 조회 (TTL 자정까지)
 *  2. miss 시 `RiskPositionPort.sumDailyRealizedPnl` 위임 (Phase 2 어댑터 부재 시 0 fallback)
 *  3. 결과 cache SET
 *
 * spec 인용: ADR 0067 §5-5 + §7-1 DailyPnlCalculator.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Component
class DailyPnlCalculator(
    private val redisTemplate: StringRedisTemplate,
    private val positionPortProvider: ObjectProvider<RiskPositionPort>,
) : DailyPnlProvider {
    private val log = LoggerFactory.getLogger(DailyPnlCalculator::class.java)

    companion object {
        private const val KEY_PREFIX = "daily_pnl:"
        private val KST = TimeZone.of("Asia/Seoul")
    }

    override fun getDailyPnl(
        userId: String,
        kstNow: Instant,
    ): BigDecimal {
        val kstDate = kstNow.toLocalDateTime(KST).date
        val key = "$KEY_PREFIX$userId:$kstDate"

        val cached = readCache(key)
        if (cached != null) return cached

        val port = positionPortProvider.getIfAvailable()
        val pnl =
            if (port == null) {
                log.debug(
                    "DailyPnlCalculator RiskPositionPort 미합류 — fallback 0 (userId={} kstDate={})",
                    userId,
                    kstDate,
                )
                BigDecimal.ZERO
            } else {
                try {
                    val kstMidnight = kstMidnightInstant(kstDate)
                    port.sumDailyRealizedPnl(userId, kstMidnight)
                } catch (e: Exception) {
                    log.warn(
                        "DailyPnlCalculator port.sumDailyRealizedPnl 실패 (fallback 0): userId={} err={}",
                        userId,
                        e.message,
                    )
                    BigDecimal.ZERO
                }
            }

        writeCache(key, pnl, kstNow, kstDate)
        return pnl
    }

    private fun readCache(key: String): BigDecimal? =
        try {
            redisTemplate.opsForValue().get(key)?.toBigDecimalOrNull()
        } catch (e: Exception) {
            log.warn("DailyPnlCalculator readCache 실패 (fail-open): key={} err={}", key, e.message)
            null
        }

    private fun writeCache(
        key: String,
        value: BigDecimal,
        kstNow: Instant,
        kstDate: LocalDate,
    ) {
        try {
            val nextMidnight = kstMidnightInstant(kstDate.plus(DatePeriod(days = 1)))
            val ttlSeconds = (nextMidnight.epochSeconds - kstNow.epochSeconds).coerceAtLeast(60L)
            redisTemplate.opsForValue().set(key, value.toPlainString(), Duration.ofSeconds(ttlSeconds))
        } catch (e: Exception) {
            log.warn("DailyPnlCalculator writeCache 실패 (skip): key={} err={}", key, e.message)
        }
    }

    private fun kstMidnightInstant(kstDate: LocalDate): Instant = kstDate.atTime(LocalTime(0, 0, 0)).toInstant(KST)
}
