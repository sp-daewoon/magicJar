package kr.spdaewoon.magicjar.domain.strategy.port

import kr.spdaewoon.magicjar.domain.strategy.dsl.TrendlineAnchors

/**
 * 추세선 anchor 조회 port (ADR 0140 §3-2 — 추세매매 Phase 2).
 *
 * domain은 Spring·JPA 의존 0(port 패턴, ADR 0088 정합)이므로, 평가기가 선 anchor를 얻는 유일한 경로다.
 * 구현(JPA 어댑터)은 R2 backend-core가 `trendline` 테이블에서 조회하며 `@Component`로
 * consumer(실시간 평가)·api(백테스트) ApplicationContext에 합류한다(헌법 §11-1).
 *
 * ### 캐시 의무 (ADR 0140 §3-2 LOCK)
 * 평가는 sandbox 100ms + CPU 50ms 예산(ADR 0047) 안에서 tick·봉 경계마다 반복 호출된다.
 * anchor는 선 수정 전까지 불변이므로 **구현은 선 id별 anchor를 캐시**(in-memory/Redis)하고
 * **선 수정(PUT)·삭제(DELETE) 시 무효화**해야 한다. 선형보간 자체는 순수 산술이라 예산 무영향이지만,
 * 캐시 없는 DB 왕복은 hot path 예산을 잠식한다.
 *
 * ### 부재/실패 시 (헌법 §11-2 silent fallback 금지)
 * bean 미주입 또는 `anchorsOf`가 null(선 부재)이면 평가기는 **ERROR 로그 가시화 + 해당 조건 false**로
 * 처리한다(silent 0). TrendlineRef는 "조건 미충족(false)"이 자본 안전 방향(BUY 안 함)이므로
 * degraded 진행이 허용되나, 반드시 로그로 드러낸다.
 *
 * ### 헌법 §6 정합
 * abstract fun 만 — body 가진 default fun 0건.
 */
interface TrendlinePricePort {
    /**
     * @param trendlineId 추세선 id (`trendline` 테이블 PK)
     * @return anchor 스냅샷. 선이 존재하지 않으면 `null`(평가기가 조건 false + ERROR 로그).
     */
    fun anchorsOf(trendlineId: Long): TrendlineAnchors?
}
