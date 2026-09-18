package kr.spdaewoon.magicjar.domain.market.cache

/**
 * 캐시 접근 서킷브레이커 (Phase B — 사용자 "안정 성능 다 잡자" · 캐시 위험성 서킷브레이크).
 *
 * ## 왜 필요한가
 * Redis/로컬 캐시를 실시간 hot path(boundary 합산·시세 조회)에서 쓰면 처리량이 크게 오르지만,
 * 캐시가 느려지거나 죽으면 매 호출이 timeout까지 블록되어 오히려 hot path 전체를 마비시킨다
 * (사용자 지적: "정합성/캐시 코드가 성능을 해칠 수 있다"). 본 서킷브레이커는 연속 실패가 임계에
 * 도달하면 **회로를 열어(OPEN) 캐시 호출을 즉시 skip**하고 fallback(예: DB)으로 흘려보낸다.
 * 일정 시간 후 half-open으로 1회 탐침, 성공하면 close로 복구한다.
 *
 * ## 순수성 (LOCK)
 * Spring·Redis·IO 의존 0. 시간은 [nowMillis] 함수로 주입(결정론 테스트). 상태 전이는 스레드 안전
 * (synchronized) — 다중 스레드 hot path 공유 안전. 캐시 자체가 아니라 "호출해도 되는가" 판정만.
 *
 * ## 상태 전이
 * - CLOSED: 정상. 캐시 호출 허용. 실패 누적이 [failureThreshold] 도달 → OPEN.
 * - OPEN: 회로 열림. 캐시 호출 즉시 skip(fallback). [openDurationMillis] 경과 → HALF_OPEN.
 * - HALF_OPEN: 탐침 1회 허용. 성공 → CLOSED(복구), 실패 → OPEN(재차단).
 */
class CacheCircuitBreaker(
    private val failureThreshold: Int = 5,
    private val openDurationMillis: Long = 30_000L,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    @Volatile
    private var state: State = State.CLOSED
    private var consecutiveFailures: Int = 0
    private var openedAtMillis: Long = 0L
    private var halfOpenProbeInFlight: Boolean = false

    /** 현재 캐시 호출을 허용할지. OPEN이고 아직 open 기간 중이면 false(즉시 fallback). */
    @Synchronized
    fun allowRequest(): Boolean =
        when (state) {
            State.CLOSED -> true
            State.HALF_OPEN -> {
                // half-open은 탐침 1회만 허용(동시 폭주 차단).
                if (halfOpenProbeInFlight) {
                    false
                } else {
                    halfOpenProbeInFlight = true
                    true
                }
            }
            State.OPEN -> {
                if (nowMillis() - openedAtMillis >= openDurationMillis) {
                    state = State.HALF_OPEN
                    halfOpenProbeInFlight = true
                    true // half-open 첫 탐침 허용
                } else {
                    false
                }
            }
        }

    /** 캐시 호출 성공 보고 — CLOSED 복구 + 실패 카운터 리셋. */
    @Synchronized
    fun recordSuccess() {
        consecutiveFailures = 0
        halfOpenProbeInFlight = false
        state = State.CLOSED
    }

    /** 캐시 호출 실패 보고 — 임계 도달 시 OPEN. half-open 탐침 실패면 즉시 재-OPEN. */
    @Synchronized
    fun recordFailure() {
        halfOpenProbeInFlight = false
        if (state == State.HALF_OPEN) {
            open()
            return
        }
        consecutiveFailures += 1
        if (consecutiveFailures >= failureThreshold) {
            open()
        }
    }

    @Synchronized
    fun currentState(): State = state

    private fun open() {
        state = State.OPEN
        openedAtMillis = nowMillis()
    }

    /**
     * 캐시 호출을 서킷브레이커로 감싸 실행. 회로가 열려 있으면 [cacheCall] 자체를 skip하고 [fallback] 반환.
     *
     * @param cacheCall 캐시 접근(예: Redis get). 예외 던지면 실패로 집계 + fallback.
     * @param fallback 회로 open 또는 캐시 실패 시 대체값(예: DB 조회 결과 또는 null).
     */
    inline fun <T> withCache(
        cacheCall: () -> T,
        fallback: () -> T,
    ): T {
        if (!allowRequest()) return fallback()
        return try {
            val result = cacheCall()
            recordSuccess()
            result
        } catch (e: Exception) {
            recordFailure()
            fallback()
        }
    }
}
