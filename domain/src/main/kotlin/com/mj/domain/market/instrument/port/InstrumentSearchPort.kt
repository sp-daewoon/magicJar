package com.mj.domain.market.instrument.port

import com.mj.domain.market.instrument.Instrument

/**
 * 종목 검색 포트 (ADR 0037 §2-4 Redis 검색 인덱스).
 *
 * 구현체:
 * - consumer: RedisInstrumentSearchAdapter — Redis ZSET 기반 prefix + substring
 *
 * 1차 단순 전략:
 * - 코드 prefix 매칭
 * - 종목명 substring 매칭
 * - 정교 검색(형태소 분석·초성 등)은 후속 트랙
 */
interface InstrumentSearchPort {
    /**
     * 검색어로 종목 목록 반환.
     * @param query 종목코드 prefix 또는 종목명 substring
     * @param limit 최대 결과 수 (기본 20)
     * @return 매칭된 Instrument 목록 (관련도 순)
     */
    fun search(
        query: String,
        limit: Int = 20,
    ): List<Instrument>

    /**
     * 전체 종목 목록으로 검색 인덱스를 재빌드한다.
     * InstrumentMasterSyncJob 완료 후 호출.
     * @param instruments 인덱싱할 종목 전체 목록
     */
    fun rebuild(instruments: List<Instrument>)
}
