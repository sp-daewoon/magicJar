package com.mj.domain.strategy.screener

import com.mj.domain.common.Market
import com.mj.domain.market.instrument.InstrumentType

/**
 * 스크리너 대상 종목 유니버스 (ADR 0040).
 *
 * sealed hierarchy — 타입 안전 매칭으로 [UniverseResolver]가 종목 코드 목록으로 변환.
 *
 * 사용 예:
 * ```
 * val universe = Universe.Kospi200          // KOSPI200 구성종목
 * val universe = Universe.Custom(listOf("005930", "000660"))
 * val universe = Universe.SymbolGroup(markets = setOf(Market.KOSPI), types = setOf(InstrumentType.STOCK))
 * ```
 */
sealed class Universe {
    /** KOSPI 200 구성 종목 (instrument_master.index_group = 'KOSPI200') */
    data object Kospi200 : Universe()

    /** KOSDAQ 150 구성 종목 (instrument_master.index_group = 'KOSDAQ150') */
    data object Kosdaq150 : Universe()

    /** 전체 ETF */
    data object Etf : Universe()

    /** 전체 ETN */
    data object Etn : Universe()

    /** 전체 상장 종목 (거래 가능 active=true 기준) */
    data object AllListed : Universe()

    /**
     * 사용자 지정 종목 코드 리스트.
     *
     * @param codes KIS 단축코드 6자리 목록 (중복 허용 — resolver에서 dedup)
     */
    data class Custom(
        val codes: List<String>,
    ) : Universe() {
        init {
            require(codes.isNotEmpty()) { "Custom universe must have at least one code" }
        }
    }

    /**
     * 동적 필터 기반 유니버스.
     *
     * @param markets   대상 시장 (null = 전체)
     * @param types     대상 종목 유형 (null = 전체)
     * @param sectors   대상 업종 (null = 전체, 비어있으면 전체)
     * @param activeOnly 거래 가능 종목만 (default true)
     */
    data class SymbolGroup(
        val markets: Set<Market>? = null,
        val types: Set<InstrumentType>? = null,
        val sectors: Set<String>? = null,
        val activeOnly: Boolean = true,
    ) : Universe()
}
