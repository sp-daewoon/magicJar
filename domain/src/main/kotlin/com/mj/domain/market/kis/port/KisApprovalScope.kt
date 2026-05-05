package com.mj.domain.market.kis.port

/**
 * KIS WebSocket approval(`/oauth2/Approval`) 의 인증 scope. ADR 0050 R3-5c-3.
 *
 * WS approval 은 KIS REST token 과 별도 endpoint·별도 응답(`approval_key`)이라
 * [KisAuthScope] 와 분리한다.
 *
 * scope:
 *   - [System]: 시세 WS approval (`H0UNCNT0`/`H0UNASP0`/`H0UPCNT0` 등 데이터 채널)
 *   - [User]:   체결통보 WS approval (`H0STCNI0`/`H0STCNI9` — htsId·계좌 종속)
 */
sealed class KisApprovalScope {
    abstract val mode: String

    /** Redis HASH 키 (approval_key 저장). spec §8-1. */
    abstract fun approvalKey(): String

    /** Redisson RLock 키. spec §8-1. */
    abstract fun lockKey(): String

    /** RTopic invalidate 페이로드용 식별자. */
    abstract fun identifier(): String

    data class System(
        override val mode: String,
    ) : KisApprovalScope() {
        override fun approvalKey(): String = "magicjar:kis:approval:system:$mode"

        override fun lockKey(): String = "magicjar:kis:approval:lock:system:$mode"

        override fun identifier(): String = "approval:system:$mode"
    }

    data class User(
        val userId: String,
        override val mode: String,
    ) : KisApprovalScope() {
        override fun approvalKey(): String = "magicjar:kis:approval:user:$userId:$mode"

        override fun lockKey(): String = "magicjar:kis:approval:lock:user:$userId:$mode"

        override fun identifier(): String = "approval:user:$userId:$mode"
    }
}
