package kr.spdaewoon.magicjar.domain.market.session

import kotlinx.datetime.Instant

/**
 * 한국 주식시장 세션 sealed class.
 * KST 기준 운영 시간 정합.
 */
sealed class MarketSession {
    /** 정규장 (09:00~15:30 KST) */
    object Regular : MarketSession()

    /** 시간외 단일가 — 장전 (08:30~09:00 KST) */
    object PreMarket : MarketSession()

    /** 시간외 단일가 — 장후 (15:40~16:00 KST) */
    object PostMarket : MarketSession()

    /** 휴장 (주말·공휴일) */
    data class Closed(
        val reason: String? = null,
    ) : MarketSession()
}

/**
 * 시장 세션 결정 포트.
 * 구현은 공휴일 캘린더 데이터 포함 어댑터에서.
 */
interface MarketSessionResolver {
    fun resolve(instant: Instant): MarketSession

    fun isOpen(instant: Instant): Boolean = resolve(instant) is MarketSession.Regular
}
