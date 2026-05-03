package com.mj.domain.market.instrument.port

import com.mj.domain.market.instrument.InstrumentDetail

/**
 * 종목 상세 정보 캐시 포트 (T20-T1).
 *
 * Redis 전용 캐시 어댑터가 구현한다.
 * - key: instrument:detail:{code}
 * - TTL: 6시간
 */
interface InstrumentDetailRepository {
    /** 종목 상세 조회 (캐시 hit → InstrumentDetail, miss → null) */
    fun find(code: String): InstrumentDetail?

    /** 종목 상세 캐시 저장 (TTL 6h) */
    fun save(detail: InstrumentDetail)

    /** 캐시 강제 삭제 (KIS/DART 키 갱신 후 강제 재조회 용도) */
    fun evict(code: String)
}
