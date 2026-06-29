package kr.spdaewoon.magicjar.domain.market.session

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * KRX 단일가(동시호가) 구간 판정 순수 함수 — 2026-06-29 국순당(043650) -9% 오버나잇 사고 D1 fix.
 *
 * ## 왜 별도 utility 인가 ([MarketSession] 한계)
 * [MarketSession] FSM 은 09:00~15:30 을 통째로 [MarketSession.Regular] 로 본다. 그러나 KRX 정규장 내부에는
 * **시장가(MARKET) 주문이 거부되는 단일가(동시호가) 구간**이 있다:
 * - 장 개시 동시호가: 08:30~09:00 (체결 09:00)
 * - 장 마감 동시호가: 15:20~15:30 (종가 단일가 — 체결 15:30)
 *
 * 이 구간에 KIS 국내주식 OpenAPI 로 시장가 SELL 을 내면 KIS 가 거부한다(2026-06-29 라이브 evidence —
 * order_request SELL MARKET 4건 15:21~15:27 전부 CANCELLED, 15:30 1건 `MARKET_CLOSED:NOT_REGULAR`).
 * 롱온리 청산(손절·EOD·수동)은 **체결 보장**이 본질이므로(헌법 §13 SELL=체결보장), 이 구간엔 시장가 대신
 * 하한가 근처 marketable LIMIT 로 발행해야 한다. 그 시간대 판정을 본 순수 함수가 단일 출처로 제공한다.
 *
 * ## 순수성 (LOCK)
 * Spring·JPA·Clock·IO 의존 0. (Instant, zone) → Boolean 결정론. 동일 입력 → 동일 출력.
 * 영업일 판정은 책임 밖이다(시간대만 본다) — caller 가 영업일·정규장 게이트와 조합한다.
 *
 * AI 호출 0 — 룰 기반 자본 흐름 순수 유틸 (ADR 0023 §2-4)
 */
object KrxAuctionWindow {
    private val SEOUL = TimeZone.of("Asia/Seoul")

    /** 장 개시 동시호가 시작 (KST 분 단위, 08:30 = 8*60+30). */
    const val OPENING_AUCTION_START_MINUTE: Int = 8 * 60 + 30 // 510

    /** 장 개시(정규 연속매매 시작) — 09:00. 이 시각부터는 연속매매(시장가 허용). */
    const val REGULAR_OPEN_MINUTE: Int = 9 * 60 // 540

    /** 장 마감 동시호가 시작 — 15:20. 이 시각부터 종가 단일가(시장가 거부). */
    const val CLOSING_AUCTION_START_MINUTE: Int = 15 * 60 + 20 // 920

    /** 장 마감 동시호가 종료(종가 체결) — 15:30. */
    const val CLOSING_AUCTION_END_MINUTE: Int = 15 * 60 + 30 // 930

    /**
     * [at] 시각(KST)이 **단일가(동시호가) 구간**인지 — 시장가 SELL 이 KIS 에 거부되는 구간.
     *
     * true 구간(상한 exclusive):
     * - 장 개시 동시호가 `[08:30, 09:00)`
     * - 장 마감 동시호가 `[15:20, 15:30)`
     *
     * 그 외(연속매매 09:00~15:20, 장 시작 전·마감 후)는 false. 영업일 여부는 보지 않는다(caller 조합).
     */
    fun isSinglePriceAuction(
        at: Instant,
        zone: TimeZone = SEOUL,
    ): Boolean {
        val minuteOfDay = minuteOfDay(at, zone)
        val openingAuction = minuteOfDay in OPENING_AUCTION_START_MINUTE until REGULAR_OPEN_MINUTE
        val closingAuction = minuteOfDay in CLOSING_AUCTION_START_MINUTE until CLOSING_AUCTION_END_MINUTE
        return openingAuction || closingAuction
    }

    private fun minuteOfDay(
        at: Instant,
        zone: TimeZone,
    ): Int {
        val local = at.toLocalDateTime(zone)
        return local.hour * 60 + local.minute
    }
}
