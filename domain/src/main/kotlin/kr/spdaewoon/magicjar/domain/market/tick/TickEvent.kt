package kr.spdaewoon.magicjar.domain.market.tick

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * KIS WS H0STCNT0 체결가 tick 이벤트 도메인 모델 (ADR 0048 §3-2).
 * - PR-9b market-data가 생성 (dispatch §4-1 LOCK)
 * - producer: KisWebSocketBridge (live) / WatchlistPollingWorker (polling)
 * - consumer: TickToMinuteAggregator (Redis candle 갱신), Kafka market.tick.v1 publish
 *
 * dogfooding fix: 등락 필드 추가 (결함 1 — 싱크 불일치 해소).
 * KIS-first 출처:
 *   https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
 *   ccnl_krx() columns 배열: [3]=PRDY_VRSS_SIGN, [4]=PRDY_VRSS, [5]=PRDY_CTRT
 * - priceChange: 전일대비 (PRDY_VRSS) — nullable: polling source는 미제공
 * - changeRate: 전일대비율 (PRDY_CTRT) — nullable: polling source는 미제공
 * - changeSign: 부호 코드 (PRDY_VRSS_SIGN: 1=상한/2=상승/3=보합/4=하한/5=하락) — nullable
 */
data class TickEvent(
    val code: String,
    val price: BigDecimal,
    val volume: Long,
    val ts: Instant,
    val source: String = "live",
    val stale: Boolean = false,
    /** 전일대비 (PRDY_VRSS) — KIS H0STCNT0 [4] */
    val priceChange: BigDecimal? = null,
    /** 전일대비율 % (PRDY_CTRT) — KIS H0STCNT0 [5] */
    val changeRate: BigDecimal? = null,
    /** 전일대비부호 (PRDY_VRSS_SIGN: 1=상한/2=상승/3=보합/4=하한/5=하락) — KIS H0STCNT0 [3] */
    val changeSign: String? = null,
)
