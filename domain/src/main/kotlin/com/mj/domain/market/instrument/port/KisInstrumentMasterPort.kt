package com.mj.domain.market.instrument.port

import com.mj.domain.common.Market
import com.mj.domain.market.instrument.InstrumentRaw

/**
 * KIS 종목 마스터 다운로드 포트 (ADR 0037).
 *
 * 구현체:
 * - dev/prd profile: RealKisInstrumentMasterAdapter — KIS 정적 파일 서버에서 zip 다운로드
 * - mock profile: StubKisInstrumentMasterAdapter — fixture 반환
 *
 * 4 시장 지원: KOSPI · KOSDAQ · KONEX · ETF(KOSPI/KOSDAQ ETF는 각 mst 포함)
 */
interface KisInstrumentMasterPort {
    /**
     * 지정 시장의 종목 원시 데이터를 다운로드해 반환한다.
     * @param market 대상 시장 (KOSPI·KOSDAQ·KONEX)
     * @return 해당 시장의 InstrumentRaw 목록
     * @throws KisInstrumentMasterException 다운로드·파싱 실패 시
     */
    fun downloadAll(market: Market): List<InstrumentRaw>
}
