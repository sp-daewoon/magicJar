package com.mj.batch.infrastructure.kis.master

import com.mj.domain.common.Market
import com.mj.domain.market.instrument.InstrumentRaw
import com.mj.domain.market.instrument.port.KisInstrumentMasterPort
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * KIS 종목 마스터 stub 어댑터 (mock profile).
 *
 * 실제 KIS 다운로드 없이 고정 fixture를 반환한다.
 * 테스트·mock 환경에서 InstrumentMasterSyncJob이 동작 가능.
 */
@Component
@Profile("mock")
class StubKisInstrumentMasterAdapter : KisInstrumentMasterPort {
    private val log = LoggerFactory.getLogger(StubKisInstrumentMasterAdapter::class.java)

    override fun downloadAll(market: Market): List<InstrumentRaw> {
        log.debug("[stub-kis-master] fixture 반환: market={}", market)
        return FIXTURES.filter { it.market == market }
    }

    companion object {
        val FIXTURES: List<InstrumentRaw> =
            listOf(
                InstrumentRaw(
                    code = "005930",
                    isin = "KR7005930003",
                    name = "삼성전자",
                    market = Market.KOSPI,
                    typeName = null,
                    sector = "0264",
                    listedAtStr = "19750611",
                    parValue = BigDecimal("100"),
                    tradingUnit = 1,
                    tradingHalted = false,
                ),
                InstrumentRaw(
                    code = "000660",
                    isin = "KR7000660001",
                    name = "SK하이닉스",
                    market = Market.KOSPI,
                    typeName = null,
                    sector = "0264",
                    listedAtStr = "19961122",
                    parValue = BigDecimal("5000"),
                    tradingUnit = 1,
                    tradingHalted = false,
                ),
                InstrumentRaw(
                    code = "005380",
                    isin = "KR7005380001",
                    name = "현대차",
                    market = Market.KOSPI,
                    typeName = null,
                    sector = "0261",
                    listedAtStr = "19740606",
                    parValue = BigDecimal("5000"),
                    tradingUnit = 1,
                    tradingHalted = false,
                ),
                InstrumentRaw(
                    code = "051910",
                    isin = "KR7051910008",
                    name = "LG화학",
                    market = Market.KOSPI,
                    typeName = null,
                    sector = "0263",
                    listedAtStr = "20010711",
                    parValue = BigDecimal("5000"),
                    tradingUnit = 1,
                    tradingHalted = false,
                ),
                InstrumentRaw(
                    code = "035420",
                    isin = "KR7035420009",
                    name = "NAVER",
                    market = Market.KOSPI,
                    typeName = null,
                    sector = "0275",
                    listedAtStr = "20020611",
                    parValue = BigDecimal("100"),
                    tradingUnit = 1,
                    tradingHalted = false,
                ),
                InstrumentRaw(
                    code = "035720",
                    isin = "KR7035720004",
                    name = "카카오",
                    market = Market.KOSDAQ,
                    typeName = null,
                    sector = "0275",
                    listedAtStr = "19991121",
                    parValue = BigDecimal("100"),
                    tradingUnit = 1,
                    tradingHalted = false,
                ),
                InstrumentRaw(
                    code = "000660",
                    isin = "KR7000660001",
                    name = "SK하이닉스",
                    market = Market.KOSDAQ,
                    typeName = null,
                    sector = "0264",
                    listedAtStr = "19961122",
                    parValue = BigDecimal("5000"),
                    tradingUnit = 1,
                    tradingHalted = false,
                ),
                InstrumentRaw(
                    code = "293490",
                    isin = "KR7293490002",
                    name = "카카오게임즈",
                    market = Market.KOSDAQ,
                    typeName = null,
                    sector = "0275",
                    listedAtStr = "20200910",
                    parValue = BigDecimal("400"),
                    tradingUnit = 1,
                    tradingHalted = false,
                ),
            )
    }
}
