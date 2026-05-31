package kr.spdaewoon.magicjar.consumer.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 차트 캔들 페이지네이션 매트릭스 properties (ADR 0093 §3-1 LOCK · consumer 모듈 mirror).
 *
 * 권위: ADR 0093 §결정 LOCK 매트릭스 — chart-curator spec PR #609 §5 정합.
 *
 * api 모듈 [kr.spdaewoon.magicjar.api.config.ChartCandleProperties]와 1:1 mirror.
 * 모듈 경계(domain Spring 의존 0) 제약상 공통 위치 회피 — 각 모듈에 동일 schema class 분리.
 *
 * 키 매트릭스 (api 모듈과 동일):
 *
 * | Interval | maxPages | cover 봉 (×100) | 사유 |
 * |----------|----------|------------------|------|
 * | `1m`     | 48       | 4800             | 차트 lazy fetch 페이지 fallback. 장기 1m backfill 범위는 one-minute 설정 사용 |
 * | `3m`·`5m`·`15m`·`30m`·`90m`·`1h` | 38 | 3800 | 합산 가공 정합 |
 * | `4h`     | 20       | 2000             | 고타임프레임 합산 |
 * | `12h`    | 12       | 1200             | 정규장 전체 합산 |
 * | `1d`     | 50       | 5000             | ≈ 20년 일봉 |
 * | `1wk`    | 20       | 2000             | ≈ 38년 주봉 |
 * | `1mo`    | 12       | 1200             | ≈ 100년 월봉 |
 * | `1y`     | 6        | 600              | ≈ 600년 |
 *
 * Default fallback (config 부재 시): 6 (기존 hardcode 회귀 안전).
 *
 * 진보 페르소나 결정 메모 (ADR 0087):
 *   - 🔄 api·consumer 각 모듈 별도 class — domain Spring 의존 0 헌법 정합 + DRY trade-off 명시
 *   - ✨ `getMaxPagesFor(interval)` 확장 fun = api 모듈과 1:1 시그니처
 *   - 💡 application.yml 키 prefix 통일 (`magicjar.candle`) → api·consumer 동일 yml 매트릭스 share 가능
 *
 * ADR 0105:
 *   - 1m 장기 backfill/reconciliation 목표는 별도 [oneMinute] 설정으로 관리한다.
 *   - 기본 최근 6개월 거래일(126), 사용자/admin 최대 1년 거래일(252), 일자별 8 page safety cap.
 */
@ConfigurationProperties(prefix = "magicjar.candle")
data class ChartCandleProperties(
    /**
     * Interval → cursor 페이지네이션 max page 수 매트릭스.
     * 누락 키 fallback = [defaultMaxPages] (회귀 안전).
     */
    val maxPagesByInterval: Map<String, Int> = emptyMap(),
    /**
     * 누락 interval fallback. default 6 = 기존 hardcode 값 (회귀 안전).
     */
    val defaultMaxPages: Int = 6,
    /**
     * KIS `FHKST03010230` 기반 1m 장기 backfill/reconciliation 설정.
     */
    val oneMinute: OneMinuteBackfillProperties = OneMinuteBackfillProperties(),
) {
    /**
     * Interval code → max pages 안전 lookup.
     *
     * @param interval CandleInterval.code (예: "1d"·"1m"·"1wk")
     * @return 매트릭스 명시 값 또는 [defaultMaxPages]
     */
    fun getMaxPagesFor(interval: String): Int = maxPagesByInterval[interval] ?: defaultMaxPages
}

/**
 * 1m backfill/reconciliation 전용 설정 (ADR 0105 Phase 1).
 *
 * `maxPagesPerDay`는 KIS 120건/page 기준 NXT 720 bar/day까지 커버하는 8 pages를
 * 운영 safety cap으로 둔다. 사용자가 과도하게 크게 설정해도 8을 넘기지 않는다.
 */
data class OneMinuteBackfillProperties(
    val defaultLookbackTradingDays: Int = 126,
    val maxLookbackTradingDays: Int = 252,
    val maxPagesPerDay: Int = 8,
) {
    fun normalizedLookbackTradingDays(): Int {
        val maxTradingDays = maxLookbackTradingDays.coerceAtLeast(1)
        return defaultLookbackTradingDays.coerceIn(1, maxTradingDays)
    }

    fun normalizedMaxPagesPerDay(): Int = maxPagesPerDay.coerceIn(1, MAX_PAGES_PER_DAY_CAP)

    companion object {
        const val MAX_PAGES_PER_DAY_CAP: Int = 8
    }
}
