package kr.spdaewoon.magicjar.consumer.signal

import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.consumer.risk.RiskGatewayEvaluator
import kr.spdaewoon.magicjar.domain.risk.port.CapitalOutcome
import kr.spdaewoon.magicjar.domain.risk.port.CapitalSnapshotProvider
import kr.spdaewoon.magicjar.domain.risk.port.DailyPnlProvider
import kr.spdaewoon.magicjar.domain.risk.port.RiskConfigRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * 일일 손실 한도(DAILY_LOSS_LIMIT) 도달 여부를 **신호 생성 앞단**에서 판정하는 게이트 (2026-06-25 헛 신호 skip 개선 ①).
 *
 * ## 목적 — 헛수고 제거(zero-regression)
 * 일일손실 한도 도달 후에도 전략들은 매 boundary 캔들마다 BUY 신호를 생성하고, 그 신호가 risk listener
 * ([kr.spdaewoon.magicjar.consumer.risk.SignalGeneratedKafkaListener])에서 전부 `DAILY_LOSS_LIMIT` 으로 BLOCKED 된다
 * (2026-06-25 실측 2,297건). 신호 생성→차단을 무한 반복하며 CPU·로그·DB 를 낭비한다. 본 게이트는 그 BUY 발행을
 * **생성 단계에서 앞당겨 skip** 한다. **최종 가드는 그대로 RiskGateway 가 유지**(이중 안전망) — 본 게이트는 순수 최적화다.
 *
 * ## 산식 단일 출처 (중복 금지)
 * 한도 도달 판정은 [RiskGatewayEvaluator.isDailyLossLimitReached](RiskGateway `DAILY_LOSS_LIMIT` 차단과 **동일 함수**)
 * 에 위임한다. 입력(config·dailyRealizedPnl·currentCapital)도 risk listener 가 [RiskContext] 를 빌드할 때 쓰는
 * 동일 포트([RiskConfigRepository]·[DailyPnlProvider]·[CapitalSnapshotProvider])에서 가져온다.
 *
 * ## 캐시 — 매 캔들 KIS/DB 부담 회피 (정확성 우선의 보수적 캐시)
 * dailyPnl(REAL 모드는 DB 재계산)·capital(KIS 잔고) 조회를 매 캔들·매 종목마다 태우면 KIS rate limit 자가 압박이
 * 재발한다. userId 별 boolean 판정을 짧은 TTL(default 10s) 로 캐시한다. 캐시가 약간 stale 해도 zero-regression 이다:
 *  - 캐시가 "도달"인데 실제 미도달 → BUY 가 잠깐 더 막힐 뿐, 다음 TTL 에서 풀린다(자본 안전 방향).
 *  - 캐시가 "미도달"인데 실제 도달 → BUY 가 잠깐 더 발행되지만 **RiskGateway 가 그대로 BLOCKED**(최종 가드 불변).
 *
 * ## graceful — 포트 부재·조회 실패 시 false (skip 안 함)
 * 어떤 포트라도 부재하거나 조회가 실패하면 [isBuyBlockedByDailyLoss] 는 false 를 반환한다(= 앞단 skip 안 함 →
 * 신호가 그대로 RiskGateway 로 흘러 최종 판정). 즉 본 게이트는 "추가로 막을 수 있을 때만 막고, 못 막아도 안전"하다.
 */
@Component
class DailyLossLimitGate(
    private val riskGatewayEvaluator: RiskGatewayEvaluator,
    private val riskConfigRepositoryProvider: ObjectProvider<RiskConfigRepository>,
    private val dailyPnlProviderProvider: ObjectProvider<DailyPnlProvider>,
    private val capitalSnapshotProviderProvider: ObjectProvider<CapitalSnapshotProvider>,
    @Value("\${magicjar.signal.daily-loss-gate.enabled:true}")
    private val enabled: Boolean = true,
    @Value("\${magicjar.signal.daily-loss-gate.cache-ttl-millis:10000}")
    private val cacheTtlMillis: Long = 10_000,
    private val clock: Clock = Clock.System,
) {
    private val log = LoggerFactory.getLogger(DailyLossLimitGate::class.java)

    private data class CacheEntry(
        val reached: Boolean,
        val expiresAtEpochMs: Long,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * userId 의 일일손실 한도가 도달했는가 → true 면 그 user 의 신규 BUY 발행을 앞단에서 skip 해도 좋다(zero-regression).
     *
     * SELL(청산)은 **절대 본 게이트로 막지 않는다** — 손실 한도 도달 시에도 보유 청산은 허용(기존 fail-closed 불변식 동등).
     * 따라서 호출부는 BUY 발행 직전에만 본 함수를 호출한다.
     */
    fun isBuyBlockedByDailyLoss(userId: String): Boolean {
        if (!enabled) return false

        val nowMs = clock.now().toEpochMilliseconds()
        cache[userId]?.let { if (it.expiresAtEpochMs > nowMs) return it.reached }

        val reached = computeReached(userId)
        cache[userId] = CacheEntry(reached, nowMs + cacheTtlMillis)
        return reached
    }

    private fun computeReached(userId: String): Boolean {
        val configRepo = riskConfigRepositoryProvider.getIfAvailable() ?: return false
        val dailyPnlProvider = dailyPnlProviderProvider.getIfAvailable() ?: return false
        val capitalProvider = capitalSnapshotProviderProvider.getIfAvailable() ?: return false

        return try {
            val nowKst = clock.now()
            val config = configRepo.getOrDefault(userId, nowKst)
            // DLL 비활성이면 capital/pnl 조회조차 생략(불필요한 KIS/DB 호출 회피).
            if (!config.isDailyLossLimitActive) return false

            val dailyPnl = dailyPnlProvider.getDailyPnl(userId, nowKst)
            // percent 한도는 capital 분모가 필요. amount 한도는 capital 불요지만, 단일 산식 재사용을 위해 함께 산출.
            //   capital 조회 불가(KIS rate limit 등)면 0 으로 진행 → percent 한도 자연 비활성(0 fabricate 금지 정합과 동일 방향).
            val capital =
                when (val outcome = capitalProvider.capitalOutcome(userId)) {
                    is CapitalOutcome.Available -> outcome.capital
                    is CapitalOutcome.Unavailable -> BigDecimal.ZERO
                }
            riskGatewayEvaluator.isDailyLossLimitReached(config, dailyPnl, capital)
        } catch (e: Exception) {
            // 조회·산정 실패 → skip 안 함(false). RiskGateway 가 최종 판정(zero-regression).
            log.warn("DailyLossLimitGate 판정 실패 — 앞단 skip 미적용(RiskGateway 최종 가드): userId={} err={}", userId, e.message)
            false
        }
    }
}
