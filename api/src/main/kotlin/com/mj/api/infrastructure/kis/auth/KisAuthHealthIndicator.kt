package com.mj.api.infrastructure.kis.auth

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.search.Search
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * R3-5c-3 PR3 — KIS 인증 토큰 상태 노출 (`/actuator/health/kisAuth`).
 *
 * spec §10-4 정합:
 *   - UP — Redis 정상 + 최근 5분 fallback 미발생
 *   - DEGRADED — 최근 5분 `kis_token_redis_unavailable_total` 증분 > 0
 *
 * 프론트는 30초 폴링으로 banner 표시(별도 사이클).
 *
 * 게이트: `magicjar.kis.redis-token.enabled=true` 시에만 등록 (기존 InMemory path 운영 중에는 노출 0).
 */
@Component("kisAuth")
@ConditionalOnProperty(
    prefix = "magicjar.kis.redis-token",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class KisAuthHealthIndicator(
    private val registry: MeterRegistry,
) : HealthIndicator {
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /** 직전 측정 시점의 누적 카운트(5분 윈도우 증분 계산용). */
    private val lastUnavailableCount = AtomicLong(0L)
    private val lastFallbackCount = AtomicLong(0L)
    private val lastSampledAt = AtomicReference<Instant>(Instant.EPOCH)

    /** 최근 fallback 발생 시각 (DEGRADED 상태에서 detail 노출). */
    private val lastDownAt = AtomicReference<Instant?>(null)

    override fun health(): Health {
        val now = Instant.now()
        val windowSeconds = 300L // 5분

        val currentUnavailable = sumCounter("kis_token_redis_unavailable_total")
        val currentFallback = sumCounter("kis_token_fallback_used_total")

        val sampledAt = lastSampledAt.get()
        val unavailableDelta: Long
        val fallbackDelta: Long

        if (sampledAt == Instant.EPOCH || now.epochSecond - sampledAt.epochSecond > windowSeconds) {
            // 초기 측정 또는 윈도우 밖 → reset
            unavailableDelta = 0L
            fallbackDelta = 0L
            lastUnavailableCount.set(currentUnavailable)
            lastFallbackCount.set(currentFallback)
            lastSampledAt.set(now)
        } else {
            unavailableDelta = (currentUnavailable - lastUnavailableCount.get()).coerceAtLeast(0L)
            fallbackDelta = (currentFallback - lastFallbackCount.get()).coerceAtLeast(0L)
        }

        if (unavailableDelta > 0L) {
            lastDownAt.set(now)
        }

        val status =
            if (unavailableDelta > 0L) {
                DEGRADED_STATUS
            } else {
                Status.UP
            }

        val builder = Health.Builder(status)
        builder.withDetail("redisUnavailableLast5m", unavailableDelta)
        builder.withDetail("fallbackUsedLast5m", fallbackDelta)
        lastDownAt.get()?.let {
            builder.withDetail(
                "lastDownAt",
                isoFormatter.format(it.atZone(zone)),
            )
        }
        if (status == DEGRADED_STATUS) {
            builder.withDetail("raceRisk", "high")
            builder.withDetail(
                "advice",
                "redis 컨테이너 즉시 복구 — 다중 JVM이 KIS API에 동시 발급 중 (EGW00133 한도 초과 위험)",
            )
        }
        return builder.build()
    }

    private fun sumCounter(name: String): Long =
        Search
            .`in`(registry)
            .name(name)
            .counters()
            .sumOf { it.count().toLong() }

    companion object {
        /**
         * Spring Boot 표준 status 외 커스텀 status.
         * `management.endpoint.health.status.http-mapping.DEGRADED=200` (default 200, no mapping needed)로 노출.
         */
        val DEGRADED_STATUS: Status = Status("DEGRADED")
    }
}
