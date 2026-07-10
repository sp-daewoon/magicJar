package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * 분봉 수집 풀 후보 조회 Repository (2026-07-10 · market-data · minute-pool).
 *
 * 신규 테이블 0 — existing `candle`(거래대금) + `discovery_scorecard`(발굴 후보) 재사용. KIS 콜 0(DB만).
 *
 * ★ 헌법 §6 (Repository default fun 함정 · ADR 0051) 정합:
 *   interface body 가진 fun 0건. native @Query 만 사용. 반환은 스칼라 컬럼(code String) → List<String>.
 *   [ConsumerMarketDailyUniverseJpaRepository] 선례와 동일 패턴 (JpaRepository 타입파라미터는 재사용).
 */
interface ConsumerMinutePoolCandidateJpaRepository : JpaRepository<ConsumerInstrumentJpaEntity, String> {
    /**
     * 거래대금 상위 N 종목코드 — 최신 1d 봉의 `close_raw × volume` 내림차순.
     *
     * 전종목 일봉 완성(ADR 0118) 전제상 대다수 종목이 최신 거래일 1d 봉을 공유하므로 글로벌 max(ts) 기준이
     * 안정적이다. close_raw·volume NULL(미체결·상장폐지 잔재) 종목은 제외.
     *
     * @param limit 상위 N
     */
    @Query(
        value = """
            SELECT code FROM candle
            WHERE interval = '1d'
              AND ts = (SELECT MAX(ts) FROM candle WHERE interval = '1d')
              AND close_raw IS NOT NULL
              AND volume IS NOT NULL
              AND volume > 0
            ORDER BY close_raw * volume DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findTopTurnoverCodes(
        @Param("limit") limit: Int,
    ): List<String>

    /**
     * 발굴 스코어카드 최신 snapshot 후보 종목코드 — preset 교차 최소 rank ASC.
     *
     * `discovery_scorecard`(V77 · api 모듈 발행 스케줄러가 08:30/23:30 적재)의 최신 snapshot_date 기준.
     * 프리셋별 중복 종목은 GROUP BY 로 distinct + MIN(rank) 로 최상위 우선. 테이블 미존재/빈 경우
     * 호출부(provider)가 예외를 흡수하고 빈 목록으로 degrade(WARN 로그 — silent 0).
     *
     * @param limit 상위 N
     */
    @Query(
        value = """
            SELECT instrument_code FROM discovery_scorecard
            WHERE snapshot_date = (SELECT MAX(snapshot_date) FROM discovery_scorecard)
            GROUP BY instrument_code
            ORDER BY MIN(rank) ASC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findLatestScorecardCandidateCodes(
        @Param("limit") limit: Int,
    ): List<String>
}
