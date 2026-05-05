package com.mj.domain.market.kis.port

/**
 * KIS OpenAPI access token 단일 store port. ADR 0050 R3-5c-2 / R3-5c-3.
 *
 * 모든 모듈(api·batch·consumer)의 KIS REST 어댑터가 공유. 토큰 발급은 1분당 1회 한도이므로
 * 어댑터별 발급 시 EGW00133 폭주. 단일 store + Redis 분산 single-flight(Redisson RLock)로 회피.
 *
 * 토큰 정책 (`docs/architecture/external-api-policy.md` §2):
 *   - 24h 유효, 23h 시점에 갱신 유도 (1h 마진)
 *   - scope별 분리 ([KisAuthScope.System] / [KisAuthScope.User])
 *
 * 구현체:
 *   - api: `ApiRedisKisTokenStoreAdapter` — 시스템 키 (R3-5c-3 PR3에서 통합)
 *   - batch: `BatchRedisKisTokenStoreAdapter` — 시스템 키 (R3-5c-3 PR4)
 *   - consumer: `ConsumerRedisKisTokenStoreAdapter` — 시스템 + 사용자 키 (R3-5c-3 PR5)
 *
 * 현재 구현(R3-5c-3 PR1): `InMemoryKisTokenStoreAdapter` (api) — system scope만, 단일 JVM `synchronized`.
 */
interface KisTokenStorePort {
    /**
     * 유효한 access token 반환. 만료 1h 이내면 새로 발급(분산 lock).
     *
     * @param scope 인증 scope (시스템 키 또는 사용자 키 + 모드)
     * @return access token 문자열
     * @throws IllegalStateException KIS 키 미설정 시
     */
    fun currentTokenOrIssue(scope: KisAuthScope): String

    /**
     * 토큰 무효화 — 401 응답 시 호출. 다음 [currentTokenOrIssue] 호출 시 새로 발급.
     */
    fun invalidate(scope: KisAuthScope)

    // -------------------------------------------------------------------------
    // Deprecated overload — R3-5c-2 시점 시그니처 호환 보존
    // -------------------------------------------------------------------------

    /**
     * Deprecated: R3-5c-3 — [KisAuthScope] 기반으로 전환.
     * caller는 `currentTokenOrIssue(KisAuthScope.System(mode))` 사용.
     */
    @Deprecated(
        "R3-5c-3: KisAuthScope 기반으로 전환",
        ReplaceWith(
            "currentTokenOrIssue(KisAuthScope.System(mode))",
            "com.mj.domain.market.kis.port.KisAuthScope",
        ),
    )
    fun currentTokenOrIssue(mode: String): String = currentTokenOrIssue(KisAuthScope.System(mode))

    /**
     * Deprecated: R3-5c-3 — [KisAuthScope] 기반으로 전환.
     */
    @Deprecated(
        "R3-5c-3: KisAuthScope 기반으로 전환",
        ReplaceWith(
            "invalidate(KisAuthScope.System(mode))",
            "com.mj.domain.market.kis.port.KisAuthScope",
        ),
    )
    fun invalidate(mode: String) = invalidate(KisAuthScope.System(mode))
}
