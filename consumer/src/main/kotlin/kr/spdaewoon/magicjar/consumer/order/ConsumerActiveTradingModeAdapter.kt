package kr.spdaewoon.magicjar.consumer.order

import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.ActiveTradingModePort
import kr.spdaewoon.magicjar.persistence.shared.system.SystemConfigJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * 활성 주문 모드 DB 조회 어댑터 (감사 2026-09-19 §5 A · [ActiveTradingModePort] 구현).
 *
 * system_config.active_trading_mode(V102) 싱글톤 값을 읽어 주문 시점 활성 모드를 제공한다.
 * RealtimeFallbackBuyPolicy 와 동일 패턴 — ObjectProvider graceful + 짧은 TTL 캐시 + 실패 시 안전 default.
 *
 * ### 안전 불변식
 * - 조회 실패/불명/파싱 실패 → [TradingMode.MOCK] fail-closed (자본 손실 방지 기본값).
 * - 반환 REAL 은 TradingModeGate 의 env 상한(MAGICJAR_MODE=REAL + ALLOW_REAL + 동의)이 열려 있을 때만
 *   실제 REAL 주문으로 이어진다 — 이 어댑터 단독으로는 REAL 주문을 열지 못한다.
 * - 짧은 TTL 캐시(default 2s) — 주문 burst 시 DB 부하 회피 + "재기동 없는 전환" 반영 지연 최소화.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Component
class ConsumerActiveTradingModeAdapter(
    private val systemConfigRepositoryProvider: ObjectProvider<SystemConfigJpaRepository>,
    @Value("\${magicjar.trading.active-mode.cache-ttl-ms:2000}")
    private val cacheTtlMs: Long = 2000L,
) : ActiveTradingModePort {
    private val log = LoggerFactory.getLogger(ConsumerActiveTradingModeAdapter::class.java)
    private val cache = AtomicReference(CacheEntry(TradingMode.MOCK, 0L))

    override fun activeMode(): TradingMode {
        val now = System.currentTimeMillis()
        val cached = cache.get()
        if (now - cached.loadedAtMs < cacheTtlMs.coerceAtLeast(0L)) {
            return cached.mode
        }

        val repository = systemConfigRepositoryProvider.ifAvailable ?: return TradingMode.MOCK
        val mode =
            runCatching {
                val raw =
                    repository
                        .findSingleton()
                        ?.activeTradingMode
                        ?.trim()
                        ?.uppercase()
                when (raw) {
                    "REAL" -> TradingMode.REAL
                    "MOCK" -> TradingMode.MOCK
                    else -> {
                        // null(row 없음) 또는 미정의 값 → MOCK fail-closed
                        if (raw != null) {
                            log.warn("active_trading_mode 미정의 값='{}' — MOCK fail-closed", raw)
                        }
                        TradingMode.MOCK
                    }
                }
            }.onFailure { e ->
                log.warn("active_trading_mode 조회 실패 — MOCK fail-closed: err={}", e.message)
            }.getOrDefault(TradingMode.MOCK)
        cache.set(CacheEntry(mode, now))
        return mode
    }

    private data class CacheEntry(
        val mode: TradingMode,
        val loadedAtMs: Long,
    )
}
