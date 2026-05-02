package com.mj.consumer.infrastructure.observability

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * 1번 트랙 actuator metrics 헬퍼. spec §3 D12, §12.
 *
 * - kis.api.calls{endpoint}
 * - kis.api.errors{category}
 * - kis.ws.disconnects
 * - orders.placed{mode}
 * - orders.filled{mode}
 * - orders.rejected{reason}
 * - executions.dedup_hits{source}
 * - guard.rejected{guard}
 *
 * 운영 시 Grafana·Prometheus 도입은 별도 트랙. 1차는 actuator /actuator/metrics 그대로.
 */
@Component
class KisMetricsRecorder(
    private val registry: MeterRegistry,
) {
    fun recordKisApiCall(endpoint: String) {
        registry.counter("kis.api.calls", "endpoint", endpoint).increment()
    }

    fun recordKisApiError(category: String) {
        registry.counter("kis.api.errors", "category", category).increment()
    }

    fun recordKisWsDisconnect() {
        registry.counter("kis.ws.disconnects").increment()
    }

    fun recordOrderPlaced(mode: String) {
        registry.counter("orders.placed", "mode", mode).increment()
    }

    fun recordOrderFilled(mode: String) {
        registry.counter("orders.filled", "mode", mode).increment()
    }

    fun recordOrderRejected(reason: String) {
        // reason 카디널리티 폭발 방지 — prefix만 (예: "KILL_SWITCH_ON", "DAILY_LIMIT_COUNT_EXCEEDED ...")
        val tag = reason.substringBefore(' ').take(40)
        registry.counter("orders.rejected", "reason", tag).increment()
    }

    fun recordDedupHit(source: String) {
        registry.counter("executions.dedup_hits", "source", source).increment()
    }

    fun recordGuardReject(guard: String) {
        registry.counter("guard.rejected", "guard", guard).increment()
    }
}
