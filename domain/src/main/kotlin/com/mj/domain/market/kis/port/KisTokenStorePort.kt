package com.mj.domain.market.kis.port

/**
 * KIS OpenAPI access token 단일 store port. ADR 0050 R3-5c-2.
 *
 * 모든 모듈(api·batch·consumer)의 KIS 어댑터가 공유. 토큰 발급은 1분당 1회 한도이므로
 * 어댑터별 발급 시 EGW00133 폭주. 단일 store + 분산 lock(Redis SETNX)로 회피.
 *
 * 토큰 정책 (`docs/architecture/external-api-policy.md` §2):
 *   - 24h 유효, 23h 시점에 갱신 유도 (1h 마진)
 *   - 모드별 분리: vps(모의) / prod(실전)
 *
 * 구현체:
 *   - api: `RedisKisTokenStoreAdapter` — Redis HASH 영속 + SETNX 분산 lock
 *   - batch·consumer: 동일 패턴 어댑터 (Redis 공유)
 */
interface KisTokenStorePort {
    /**
     * 유효한 access token 반환. 만료 1h 이내면 새로 발급(분산 lock).
     *
     * @param mode "vps"(모의) 또는 "prod"(실전)
     * @return access token 문자열
     * @throws IllegalStateException KIS 키 미설정 시
     */
    fun currentTokenOrIssue(mode: String): String

    /**
     * 토큰 무효화 — 401 응답 시 호출. 다음 currentTokenOrIssue 호출 시 새로 발급.
     */
    fun invalidate(mode: String)
}
