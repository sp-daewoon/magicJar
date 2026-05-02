package com.mj.domain.market.instrument

/** 금융상품 유형. */
enum class InstrumentType {
    /** 보통주 */
    STOCK,

    /** ETF (Exchange Traded Fund) */
    ETF,

    /** ETN (Exchange Traded Note) */
    ETN,

    /** 우선주 */
    PREFERRED,

    /** ELW 등 워런트 */
    WARRANT,

    /** 리츠 (Real Estate Investment Trust) */
    REIT,
}
