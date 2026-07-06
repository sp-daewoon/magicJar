package kr.spdaewoon.magicjar.domain.strategy.port

/**
 * 추세선 anchor 캐시 무효화 port (ADR 0140 §3-2·§3-3 — 선 수정/삭제 시 무효화 의무).
 *
 * [TrendlinePricePort] 구현(R2 JPA 어댑터)이 sandbox 예산 보호를 위해 anchor 를 캐시한다.
 * anchor 는 **선 수정(PUT)·삭제(DELETE)** 전까지 불변이므로, 선을 변경하는 쪽(api `TrendlineService`)이
 * 이 port 로 즉시 무효화해 stale anchor 로 매매하는 것을 막는다.
 *
 * ### 왜 [TrendlinePricePort] 와 분리했나 (진보 페르소나 — 책임 분리 SRP)
 * `TrendlinePricePort` 는 평가기(strategy-engine)가 소비하는 **조회 전용** 계약이다. 무효화(write-side)를
 * 거기 얹으면 평가기 계약이 오염된다. 무효화는 선을 mutate 하는 api 경로만 필요하므로 별도 port 로 격리.
 *
 * ### cross-JVM 주의 (설계 노트)
 * 어댑터 캐시는 프로세스 로컬(in-memory)이다. api JVM 에서 선을 수정하면 api 프로세스 캐시는 즉시 무효화되지만
 * consumer 프로세스 캐시는 이 호출을 받지 못한다 → consumer 는 **짧은 TTL** 로 자연 만료해 다음 봉 경계 평가에서
 * 최신 anchor 를 읽는다(ADR 0140 §3-2 "TTL 짧은 in-memory 캐시"). 봉 경계 평가 주기(분 단위) > TTL 이므로
 * 실매매 기준 drift 는 TTL 상한(default 30s)으로 bound 된다.
 *
 * ### 헌법 §6 무관
 * Repository 아님 — port 인터페이스. abstract fun 만.
 */
interface TrendlineAnchorCacheInvalidator {
    /** 단일 선 캐시 무효화 — 선 수정(PUT)·삭제(DELETE) 시 호출. */
    fun invalidate(trendlineId: Long)

    /** 전체 캐시 비움 — 대량 정정·테스트용(운영 hot path 미사용). */
    fun invalidateAll()
}
