package kr.spdaewoon.magicjar.domain.market.ratelimit

/**
 * KIS Rate Limit Circuit Breaker 순수 판정 로직 (ADR 0037 Phase 3 · 자본 안전 핵심).
 *
 * Redis I/O(윈도 카운터·OPEN 플래그)는 각 모듈 어댑터(api/consumer/batch)가 담당하고,
 * "이 통계·상태에서 회로를 열어야 하는가", "이 priority를 차단해야 하는가"의 **결정만** 본 순수 함수가 권위한다.
 * 3 어댑터가 동일 결정 로직을 공유 — 복제·불일치 0 (domain 단일 진실원).
 *
 * ### 자본 안전 불변식 (정책 C — 사용자 확정 2026-09-20)
 * **USER_PATH(주문·잔고·현재가 등 사용자 직결)는 회로가 OPEN이어도 절대 차단하지 않는다.**
 * 계정 차단(catastrophic)의 주범은 대량 시세/backfill(INGESTION·BACKFILL·INGESTION_LOW·REALTIME_MARKET)이지
 * 주문이 아니다. 회로가 열려도 청산·손절 주문은 항상 나가야 하므로 USER_PATH는 상시 통과시킨다.
 * OPEN은 배경 트래픽만 끊어 TPS를 낮추고, 그것으로 계정 차단을 방지한다.
 *
 * ### OPEN 판정 (fail rate)
 * 윈도(default 5분) 내 (실패 + 성공) 표본이 [minimumSamples] 이상이고 실패율 ≥ [failureRateThreshold]면 OPEN.
 * 표본 부족 시 열지 않는다(산발 EGW00201로 성급히 열어 배경 수집을 죽이지 않도록).
 */
object KisCircuitBreakerDecision {
    /** OPEN 판정 최소 표본 — 이 미만이면 실패율과 무관하게 열지 않는다(산발 오탐 방지). */
    const val DEFAULT_MINIMUM_SAMPLES: Long = 20L

    /**
     * 현재 priority 호출을 회로 때문에 차단해야 하는가.
     *
     * @param circuitOpen Redis OPEN 플래그 존재 여부(어댑터가 조회해 전달).
     * @param priority 호출 우선순위.
     * @return true면 어댑터가 [KisCircuitOpenException] throw(토큰 소비 전). USER_PATH는 항상 false.
     */
    fun shouldBlock(
        circuitOpen: Boolean,
        priority: KisCallPriority,
    ): Boolean {
        if (!circuitOpen) return false
        // 정책 C — USER_PATH는 OPEN이어도 통과 (자본 경로 보호). 그 외 배경 트래픽만 차단.
        return priority != KisCallPriority.USER_PATH
    }

    /**
     * 현재 윈도 통계로 회로를 OPEN 전환해야 하는가.
     *
     * @param failureCount 윈도 내 EGW00201/429 건수.
     * @param successCount 윈도 내 정상 응답 건수.
     * @param failureRateThreshold OPEN 임계 실패율(default 0.5).
     * @param minimumSamples 판정 최소 표본(default 20).
     */
    fun shouldOpen(
        failureCount: Long,
        successCount: Long,
        failureRateThreshold: Double,
        minimumSamples: Long = DEFAULT_MINIMUM_SAMPLES,
    ): Boolean {
        val total = failureCount + successCount
        if (total < minimumSamples.coerceAtLeast(1L)) return false
        if (failureCount <= 0L) return false
        val failureRate = failureCount.toDouble() / total.toDouble()
        return failureRate >= failureRateThreshold
    }
}
