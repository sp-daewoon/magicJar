package kr.spdaewoon.magicjar.domain.market.port

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import java.time.Instant

/**
 * KIS 과거 1분봉 range 조회 포트 — **BACKFILL 버킷 전용** (ADR 0132 기둥2 · R2a).
 *
 * 왜 [KisOneMinuteCandleRangePort] 가 아니라 별도 포트인가:
 *   - [KisOneMinuteCandleRangePort.fetchOneMinuteCandlesBetween] 의 acquire 는 **USER_PATH** 버킷이다
 *     (SymbolDetail 재진입·NoTickMinuteFallback 등 사용자 직접 path 가 결과를 즉시 기다리므로).
 *   - 백테스트 분봉 선수집은 사용자 결과를 기다리는 동기 REST 지만, **라이브 매매(USER_PATH)·warmup
 *     (REALTIME_MARKET)·ingestion 쿼터를 절대 침범하면 안 되는 백그라운드 대량 수집**이다
 *     (ADR 0132 §D2-3 LOCK — 라이브 자본경로를 굶기면 안 됨).
 *   - 따라서 ADR 0118 R-BF([KisDailyCandleRangePort]) 패턴과 동일하게, 기존 USER_PATH 1m range path 를
 *     건드리지 않고(회귀 0) **BACKFILL([kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority.BACKFILL])
 *     버킷 + EGW00201 backoff** 전용 메서드를 분리한다.
 *
 * KIS endpoint·tr_id·페이지네이션 알고리즘은 동일(FHKST03010230) — 어댑터에서 재사용한다.
 *
 * KIS-first raw 출처 (헌법 §3):
 *   FHKST03010230 inquire-time-dailychartprice — 최대 120건/call · `FID_INPUT_DATE_1`+`FID_INPUT_HOUR_1` cursor
 *   · KIS 원문 "최대 1년 분봉 보관"
 *   raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
 */
interface KisMinuteBackfillRangePort {
    /**
     * [from] 이상 [to] 이하의 KIS 1m candle 을 ASC 로 반환한다 (**BACKFILL 버킷**).
     *
     * - acquire 는 [kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority.BACKFILL](최저) 고정 — 라이브
     *   매매·warmup 쿼터 침범 0.
     * - KIS upstream/rate-limit(EGW00201)/credential 실패는 빈 결과로 숨기지 않고 예외로 전파한다(호출자가
     *   data readiness 를 fail-closed 로 닫고 정합성 등급으로 노출). 단, KIS 가 정상 응답했으나 해당 종목·구간
     *   데이터가 0건이면(거래 부재·휴장) 빈 list 반환(예외 아님 — uncovered).
     * - **mock 보간 0**: 결측 구간을 합성하지 않는다. 없는 만큼만 반환.
     *
     * @param code 종목코드 6자리
     * @param from 시작 경계(inclusive) — 호출자가 KIS 1년 bound·ENV max-lookback-days 로 clamp 의무
     * @param to   종료 경계(inclusive)
     * @return ASC 정렬 1m Candle list. 빈 list = 해당 구간 KIS 미보유(uncovered).
     */
    fun fetchOneMinuteCandlesForBackfill(
        code: String,
        from: Instant,
        to: Instant,
    ): List<Candle>
}
