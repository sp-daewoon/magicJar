package com.mj.domain.market.instrument.port

/** KIS 종목 마스터 다운로드·파싱 실패 시 발생하는 예외. */
class KisInstrumentMasterException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
