package com.mj.domain.market.kis.port

/**
 * KIS WebSocket approval(`/oauth2/Approval`) 단일 store port. ADR 0050 R3-5c-3.
 *
 * REST token 과 별도 endpoint·별도 응답(`approval_key`).
 * 시세 WS / 체결통보 WS 두 채널이 공유한다.
 *
 * 정책:
 *   - approval_key 유효 ~24h (KIS 명세상 만료 시각 응답 없음 → 우리 측 23h hardcoded TTL)
 *   - scope별 분리 ([KisApprovalScope.System] / [KisApprovalScope.User])
 *
 * 구현체:
 *   - consumer: `ConsumerRedisKisApprovalStoreAdapter` (R3-5c-3 PR5)
 */
interface KisApprovalStorePort {
    /**
     * 유효한 approval_key 반환. 만료 1h 이내면 새로 발급(분산 lock).
     *
     * @param scope 인증 scope (시세 WS 시스템 또는 체결통보 WS 사용자)
     * @return approval_key 문자열
     * @throws IllegalStateException KIS 키 미설정 시
     */
    fun currentApprovalOrIssue(scope: KisApprovalScope): String

    /**
     * approval_key 무효화 — WS 거부/연결 실패 시 호출.
     */
    fun invalidate(scope: KisApprovalScope)
}
