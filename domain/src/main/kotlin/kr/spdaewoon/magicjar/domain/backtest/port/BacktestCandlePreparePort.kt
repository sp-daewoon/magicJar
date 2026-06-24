package kr.spdaewoon.magicjar.domain.backtest.port

import kotlinx.datetime.LocalDate
import kr.spdaewoon.magicjar.domain.market.candle.CandleIntegrityReport

/**
 * 백테스트 분봉 선수집 + 정합성 보고 포트 — ADR 0132 §1-E (기둥2·3 · R2a 구현).
 *
 * 도메인 모듈에만 위치 — Spring·JPA·Kafka import 절대 금지(헥사고날 정합).
 *
 * ## 책임 (R1 = 시그니처 LOCK · 구현은 R2a market-data)
 * INTRADAY_REALISTIC 백테스트는 분봉이 없으면 결과가 무의미하므로, 엔진(api)이 본 포트로 **동기 선수집**을 호출한다:
 * 1. universe × interval × [from,to] 의 DB 분봉 gap 산출.
 * 2. gap 을 KIS 1m on-demand backfill — [kr.spdaewoon.magicjar.domain.market.port.KisOneMinuteCandleRangePort]
 *    + [kr.spdaewoon.magicjar.domain.ratelimit.KisCallPriority] BACKFILL(최저 — 라이브 매매·warmup 쿼터 침범 0) +
 *    EGW backoff + 집계봉(3m/5m) 재생성([AggregateBackfillRunner]). (`SymbolDetailMinuteGapFillService` 패턴 재사용.)
 * 3. KIS 1m lookback bound(최대 1년·ENV `max-lookback-days`)로 from clamp(거짓 결과 차단 — lookbackClamped 플래그).
 * 4. backfill 후 기대/실제 봉수 대비 정합성 보고([CandleIntegrityReport]) — **mock 보간 0**(결측은 등급으로만).
 *
 * ## 구현 모듈 (ADR 0133 §3-A1 — consumer → api 이동 · 인터페이스 불변 LOCK)
 * 본 포트의 구현은 **api 모듈**(`BacktestCandlePrepareApiAdapter`)에 둔다. 호출자 [JvmBacktestEngine] 이
 * api 프로세스라, 구현이 consumer 에 있던 ADR 0132 R2a 배치는 cross-process 단절(api 에서 빈 null →
 * `@Autowired(required=false)` → backfill 0)이었다(ADR 0133 #4). api 의 `CandleRefreshService` 가 이미
 * KIS 1m fetch + 합산봉 재생성 + `BACKFILL` priority 를 보유하므로, 신규 KIS 어댑터 0 으로 port 구현을
 * api in-process 로 둔다. **본 인터페이스 시그니처는 불변**(엔진 호출부 변경 0).
 */
interface BacktestCandlePreparePort {
    /**
     * universe 의 분봉 gap 을 (게이트 ON 시) KIS backfill 후 종목별 정합성 보고를 반환한다.
     *
     * @param universe 백테스트 종목코드 리스트(비어 있지 않음).
     * @param interval 분봉 interval 코드("1m"·"3m"·"5m" 등).
     * @param from 백테스트 시작일(포함) — KIS 1m bound 보다 과거면 clamp.
     * @param to 백테스트 종료일(포함).
     * @param backfillEnabled backfill on/off(ENV 게이트 `backfill-enabled` AND 결과). false 면 fetch 0 · DB 기존 봉만 평가.
     * @return code → 정합성 보고. backfill 여부와 무관하게 모든 universe 종목에 대해 평가 결과를 담는다.
     */
    fun prepareAndReport(
        universe: List<String>,
        interval: String,
        from: LocalDate,
        to: LocalDate,
        backfillEnabled: Boolean,
    ): Map<String, CandleIntegrityReport>
}
