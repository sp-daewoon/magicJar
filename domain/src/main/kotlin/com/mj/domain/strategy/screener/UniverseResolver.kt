package com.mj.domain.strategy.screener

/**
 * Universe → 종목 코드 목록 변환 포트 (ADR 0040).
 *
 * 구현체는 api/infrastructure/persistence 레이어에서 InstrumentJpaRepository를 통해 제공.
 * instrument_master 테이블의 active, type, market, index_group 컬럼 활용.
 *
 * - KOSPI200 / KOSDAQ150: instrument_master.index_group 컬럼 기준
 * - ETF / ETN: type = ETF / ETN 기준
 * - AllListed: active = true 기준
 * - Custom: 코드 유효성 검사 후 반환 (존재하지 않는 코드 제거)
 * - SymbolGroup: market + type + sector 필터 조합
 */
interface UniverseResolver {
    /**
     * universe에 속하는 활성 종목 코드 목록을 반환.
     *
     * @param universe  대상 유니버스
     * @return KIS 단축코드 6자리 목록 (중복 없음, 정렬 없음)
     */
    fun resolve(universe: Universe): List<String>
}
