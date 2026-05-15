package kr.spdaewoon.magicjar.api.service

import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.api.persistence.WatchlistJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalTime
import java.time.ZoneId

/**
 * 장중 일/주/월/년봉 자동 갱신 cron.
 *
 * 결함 배경 (2026-05-15 hotfix):
 *   - 분봉(1m·5m·15m·30m·90m)은 KIS WS + BoundaryAggregator로 실시간 OK
 *   - 일/주/월/년봉은 ColdStartExecutor 1회 backfill 후 polling 부재 → stale
 *   - 사용자가 장중 SymbolDetail 진입 시 캔들이 며칠 전 데이터로 표시되는 결함
 *
 * 본 cron:
 *   - 5분 주기 (KIS Rate Limit ADR 0037 정합 + Watchlist 16종목 기준 0.8초 처리)
 *   - 장 시간 가드: 08:00~20:00 KST (NXT cover ADR 0063 정합)
 *   - 활성 watchlist 종목 distinct code → CandleRefreshService.refresh(code, ["1d"]) 호출
 *   - 일봉만 매 5분 갱신. 주/월/년봉은 별도 backlog 의제 (Phase 2)
 *
 * 환경 toggle: magicjar.live-candle.enabled (default true)
 */
@Component
class LiveDailyCandleScheduler(
    private val candleRefreshService: CandleRefreshService,
    private val watchlistJpaRepository: WatchlistJpaRepository,
    private val userAccountJpaRepository: UserAccountJpaRepository,
    @Value("\${magicjar.live-candle.enabled:true}")
    private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(LiveDailyCandleScheduler::class.java)
    private val seoul = ZoneId.of("Asia/Seoul")
    private val marketOpen: LocalTime = LocalTime.of(8, 0)
    private val marketClose: LocalTime = LocalTime.of(20, 0)

    @Scheduled(fixedRate = 300_000L, initialDelay = 30_000L)
    fun tick() {
        if (!enabled) return

        val now = LocalTime.now(seoul)
        if (now.isBefore(marketOpen) || now.isAfter(marketClose)) {
            return
        }

        val userId = userAccountJpaRepository.findFirstUserIds(PageRequest.of(0, 1)).firstOrNull()
        if (userId == null) {
            log.debug("[LiveDailyCandle] user_account 부재 — skip")
            return
        }

        val watchlistRows = watchlistJpaRepository.findByUserIdOrderByPriorityAscCreatedAtDesc(userId)
        if (watchlistRows.isEmpty()) {
            log.debug("[LiveDailyCandle] watchlist 0건 — skip")
            return
        }

        val codes = watchlistRows.map { it.code }.distinct()
        val startMs = System.currentTimeMillis()
        var okCount = 0
        var failCount = 0

        for (code in codes) {
            try {
                val response = candleRefreshService.refresh(code, listOf("1d"))
                val ok = response.refreshedIntervals.all { it.status == "OK" }
                if (ok) okCount++ else failCount++
            } catch (e: Exception) {
                failCount++
                log.warn("[LiveDailyCandle] code={} 갱신 실패: {}", code, e.message)
            }
        }

        val elapsedMs = System.currentTimeMillis() - startMs
        log.info(
            "[LiveDailyCandle] {} 종목 갱신 완료 — ok={} fail={} elapsedMs={}",
            codes.size,
            okCount,
            failCount,
            elapsedMs,
        )
    }
}
