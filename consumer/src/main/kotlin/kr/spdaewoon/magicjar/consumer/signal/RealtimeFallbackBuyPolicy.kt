package kr.spdaewoon.magicjar.consumer.signal

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.persistence.shared.system.SystemConfigJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * fallback source candle 신규 BUY 허용 정책.
 *
 * OFF여도 SELL 평가는 막지 않는다. 손절/익절/보유 청산은 자본 회수 경로이기 때문이다.
 *
 * ## default = 허용(true) — 사용자 결재 전 불변 (감사 §6 P2-16 · 2026-07-03)
 * fallback 캔들(REST polling 합성·no-tick fallback)은 WS tick 확정 캔들보다 신선도·정밀도가 낮아 BUY 근거로
 * 쓰면 진입 품질이 열화될 수 있다(감사가 default false 검토를 권고). **default 변경은 자본 흐름 결재 사항**이라
 * 여기서는 바꾸지 않고, 실제로 fallback 캔들에서 BUY 가 허용될 때 전략당 세션 1회 WARN([noteFallbackBuyAllowed])으로
 * 가시화만 한다 — 사용자가 발생 빈도를 보고 결재할 수 있게.
 */
@Component
class RealtimeFallbackBuyPolicy(
    private val systemConfigRepositoryProvider: ObjectProvider<SystemConfigJpaRepository>,
    @Value("\${magicjar.signal.fallback-buy.default-enabled:true}")
    private val defaultEnabled: Boolean = true,
    @Value("\${magicjar.signal.fallback-buy.cache-ttl-ms:5000}")
    private val cacheTtlMs: Long = 5000L,
) {
    private val log = LoggerFactory.getLogger(RealtimeFallbackBuyPolicy::class.java)
    private val cache = AtomicReference(CacheEntry(defaultEnabled, 0L))

    /** fallback BUY 허용 WARN 1회 가드 — strategyId 단위(세션 내 · 감사 §6 P2-16 가시화). */
    private val fallbackBuyWarnedStrategies: MutableSet<Long> =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet()

    fun allowsBuy(candle: Candle): Boolean {
        if (!isFallbackSource(candle.source)) return true
        return currentEnabled()
    }

    /**
     * fallback 캔들 기반 BUY 가 실제로 발행될 때 호출(감사 §6 P2-16) — 전략당 세션 1회 WARN 가시화.
     * 행동 변화 0(로그만). fallback source 가 아니면 no-op.
     */
    fun noteFallbackBuyAllowed(
        strategyId: Long,
        candle: Candle,
    ) {
        if (!isFallbackSource(candle.source)) return
        if (fallbackBuyWarnedStrategies.add(strategyId)) {
            log.warn(
                "fallback source 캔들로 BUY 발행 허용(정책 default 유지 — 결재 전 불변 · 전략당 세션 1회 경고): strategyId={} source={}",
                strategyId,
                candle.source,
            )
        }
    }

    fun isFallbackSource(source: String?): Boolean {
        val normalized = source?.trim()?.uppercase().orEmpty()
        if (normalized.isBlank()) return false
        return normalized == "KIS-POLLING" ||
            normalized == "KIS-NOTICK-FALLBACK" ||
            normalized.contains("FALLBACK") ||
            normalized.contains("POLLING")
    }

    private fun currentEnabled(): Boolean {
        val now = System.currentTimeMillis()
        val cached = cache.get()
        if (now - cached.loadedAtMs < cacheTtlMs.coerceAtLeast(0L)) {
            return cached.enabled
        }

        val repository = systemConfigRepositoryProvider.ifAvailable ?: return defaultEnabled
        val enabled =
            runCatching {
                repository.findSingleton()?.realtimeFallbackBuyEnabled ?: defaultEnabled
            }.onFailure { e ->
                log.warn("fallback BUY 정책 조회 실패 — default={} 적용: err={}", defaultEnabled, e.message)
            }.getOrDefault(defaultEnabled)
        cache.set(CacheEntry(enabled, now))
        return enabled
    }

    private data class CacheEntry(
        val enabled: Boolean,
        val loadedAtMs: Long,
    )
}
