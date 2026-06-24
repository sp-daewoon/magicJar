package kr.spdaewoon.magicjar.consumer.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 초단타 정합 백테스트(INTRADAY_REALISTIC) 분봉 backfill 설정 — ADR 0132 §3·§D2-2 (`magicjar.backtest.intraday-realistic`).
 *
 * R2a(market-data·consumer) 소관: 분봉 선수집 lookback clamp 한계.
 *   - api(R2b) 가 쓰는 `enabled`/`backfill-enabled` 게이트는 api 모듈 properties 에서 관리(본 properties 는
 *     consumer 어댑터의 KIS 분봉 backfill clamp 한계만 보유 — 모듈 경계 LOCK).
 *
 * KIS 분봉 보관 한계 (KIS-first raw — 헌법 §3):
 *   FHKST03010230 inquire-time-dailychartprice — KIS 원문 "과거 분봉 조회 시 ... 최대 1년 분봉 보관".
 *   raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
 *
 * | key | default | 의미 |
 * |-----|---------|------|
 * | max-lookback-days | 30 | 백테스트 backfill 최대 lookback 거래일(달력일 근사). 요청 from 이 이보다 과거면 clamp + LOOKBACK_CLAMPED 경고 |
 *
 * default 30 거래일 사유(ADR 0132 §D2-2): 30일 × ~5 call ≈ 150 call/종목. 1년(252거래일×~5≈1,260 call/종목)
 * 은 호출 폭증 — 사용자가 ENV 로 명시 확장. **상한 = KIS 1년 bound**([KIS_HARD_BOUND_CALENDAR_DAYS]).
 */
@ConfigurationProperties(prefix = "magicjar.backtest.intraday-realistic")
data class BacktestIntradayProperties(
    /**
     * 백테스트 분봉 backfill 최대 lookback(달력일 근사). 요청 from 이 `to - maxLookbackDays` 보다 과거면
     * 그 경계로 clamp 하고 [CandleIntegrityReport.lookbackClamped]=true 로 표기(거짓 결과 차단).
     */
    val maxLookbackDays: Int = 30,
) {
    /**
     * 정규화된 lookback 달력일 — 1 이상, KIS 1년 bound 이하로 clamp.
     *
     * KIS 가 최대 1년만 분봉 보관하므로, 사용자가 ENV 로 더 크게 설정해도 [KIS_HARD_BOUND_CALENDAR_DAYS]
     * 를 넘기지 않는다(KIS 가 어차피 그 이전을 반환하지 않음 — 거짓 기대봉수 차단).
     */
    fun normalizedMaxLookbackDays(): Int = maxLookbackDays.coerceIn(1, KIS_HARD_BOUND_CALENDAR_DAYS)

    companion object {
        /** KIS 분봉 보관 한계(최대 1년) 달력일 hard bound — KIS-first raw 원문 정합. */
        const val KIS_HARD_BOUND_CALENDAR_DAYS: Int = 365
    }
}
