package com.mj.domain.market.kis.port

/**
 * KIS REST 토큰의 인증 scope. ADR 0050 R3-5c-3.
 *
 * 토큰은 모드 단위 + 사용자 단위로 분리된다:
 *   - [System]: 시스템 키 (시세·메타·지수·호가·체결 등 데이터 조회 9경로)
 *   - [User]:   사용자 키 (계좌 연동 — 주문·체결통보 1경로)
 *
 * Redis 키와 분산 lock 키는 scope별로 분리하여 무관한 사용자가 시스템 토큰 락에 묶이지 않도록 한다.
 *
 * `mode`: `"vps"` (모의) 또는 `"prod"` (실전)
 * `userId`: String (예: "74683223", V21 정합)
 */
sealed class KisAuthScope {
    abstract val mode: String

    /** Redis HASH 키 (토큰 저장). spec §8-1. */
    abstract fun tokenKey(): String

    /** Redisson RLock 키 (분산 single-flight). spec §8-1. */
    abstract fun lockKey(): String

    /** RTopic invalidate 페이로드용 식별자. */
    abstract fun identifier(): String

    data class System(
        override val mode: String,
    ) : KisAuthScope() {
        override fun tokenKey(): String = "magicjar:kis:token:system:$mode"

        override fun lockKey(): String = "magicjar:kis:token:lock:system:$mode"

        override fun identifier(): String = "system:$mode"
    }

    data class User(
        val userId: String,
        override val mode: String,
    ) : KisAuthScope() {
        override fun tokenKey(): String = "magicjar:kis:token:user:$userId:$mode"

        override fun lockKey(): String = "magicjar:kis:token:lock:user:$userId:$mode"

        override fun identifier(): String = "user:$userId:$mode"
    }
}
