package com.mj.domain.common

/**
 * 종목 식별자 경량 모델.
 *
 * @deprecated T3(Instrument Master) 트랙에서 [com.mj.domain.market.instrument.Instrument]로 교체됨.
 * 기존 호환용으로 유지. 신규 코드에서는 com.mj.domain.market.instrument.Instrument 사용.
 */
@Deprecated(
    "com.mj.domain.market.instrument.Instrument 사용 권장 (T3 트랙 업그레이드).",
    replaceWith = ReplaceWith("Instrument", "com.mj.domain.market.instrument.Instrument"),
)
data class Instrument(
    val code: String,
    val name: String,
    val market: Market,
)
