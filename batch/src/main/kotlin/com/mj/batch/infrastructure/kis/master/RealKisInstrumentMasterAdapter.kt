package com.mj.batch.infrastructure.kis.master

import com.mj.domain.common.Market
import com.mj.domain.market.instrument.InstrumentRaw
import com.mj.domain.market.instrument.port.KisInstrumentMasterException
import com.mj.domain.market.instrument.port.KisInstrumentMasterPort
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.util.zip.ZipInputStream

/**
 * KIS 종목 마스터 다운로드 어댑터 (dev·prd profile).
 *
 * KIS 정적 파일 서버에서 MST zip 파일을 다운로드해 파싱한다.
 *
 * 채택 방식 (MCP 검색 결과 기반):
 * - URL: https://new.real.download.dws.co.kr/common/master/{market}_code.mst.zip
 * - KOSPI: kospi_code.mst.zip
 * - KOSDAQ: kosdaq_code.mst.zip
 * - KONEX: konex_code.mst.zip
 * - trId: 없음 (HTTP 파일 다운로드, REST API 아님)
 * - 파일 인코딩: CP949
 *
 * 파싱 구조 (KIS genfile_*.py 참조):
 * - Part1: 바이트 0-9(단축코드), 9-21(ISIN), 21-(한글명)
 * - Part2: 마지막 228/222 바이트 고정폭 필드 (KOSPI=228, KOSDAQ=222, KONEX=222)
 *   주요 필드 offset:
 *   - 업종코드(sector): offset 2, len 4
 *   - 거래정지: offset 44, len 1
 *   - 거래 수량 단위: offset 45, len 5
 *   - 액면가: offset 47, len 12
 *   - 상장일: offset 79, len 8
 */
@Component
@Profile("dev | prd")
class RealKisInstrumentMasterAdapter(
    /** 테스트에서 WireMock URL로 오버라이드 가능. 기본값: KIS 정적 파일 서버 */
    private val baseUrl: String = DEFAULT_BASE_URL,
) : KisInstrumentMasterPort {
    private val log = LoggerFactory.getLogger(RealKisInstrumentMasterAdapter::class.java)

    companion object {
        const val DEFAULT_BASE_URL = "https://new.real.download.dws.co.kr/common/master"

        /** KOSPI Part2 고정폭 레이아웃: 마지막 228 바이트 */
        private const val KOSPI_PART2_SIZE = 228

        /** KOSDAQ Part2 고정폭 레이아웃: 마지막 222 바이트 */
        private const val KOSDAQ_PART2_SIZE = 222

        /** KONEX Part2 고정폭 레이아웃: KOSDAQ와 동일 구조 */
        private const val KONEX_PART2_SIZE = 222

        // 공통 Part2 주요 필드 offset (KOSPI/KOSDAQ/KONEX 동일 구조)
        private const val OFFSET_SECTOR_CODE = 2
        private const val OFFSET_TRADING_HALT = 44
        private const val OFFSET_TRADING_UNIT = 45
        private const val LEN_TRADING_UNIT = 5
        private const val OFFSET_PAR_VALUE = 47
        private const val LEN_PAR_VALUE = 12
        private const val OFFSET_LISTED_DATE = 79
        private const val LEN_LISTED_DATE = 8
    }

    override fun downloadAll(market: Market): List<InstrumentRaw> {
        val (fileName, part2Size) =
            when (market) {
                Market.KOSPI -> Pair("kospi_code.mst.zip", KOSPI_PART2_SIZE)
                Market.KOSDAQ -> Pair("kosdaq_code.mst.zip", KOSDAQ_PART2_SIZE)
                Market.KONEX -> Pair("konex_code.mst.zip", KONEX_PART2_SIZE)
                Market.ETF, Market.ETN -> {
                    log.warn("[kis-master] {} 는 별도 mst 파일 없음 — KOSPI/KOSDAQ에 포함", market)
                    return emptyList()
                }
            }

        val url = "$baseUrl/$fileName"
        log.info("[kis-master] 종목 마스터 다운로드 시작: url={} market={}", url, market)

        return try {
            val inputStream = downloadZip(url)
            val rows = parseMstStream(inputStream, market, part2Size)
            log.info("[kis-master] 종목 마스터 파싱 완료: market={} count={}", market, rows.size)
            rows
        } catch (ex: KisInstrumentMasterException) {
            throw ex
        } catch (ex: Exception) {
            throw KisInstrumentMasterException(
                "[kis-master] 종목 마스터 다운로드/파싱 실패: market=$market url=$url — ${ex.message}",
                ex,
            )
        }
    }

    /**
     * ZIP 파일 다운로드 후 내부 MST 파일의 InputStream 반환.
     */
    private fun downloadZip(url: String): InputStream {
        val connection = URI(url).toURL().openConnection()
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.setRequestProperty(
            "User-Agent",
            "magicJar/1.0 (market-data-agent; https://github.com/sp-daewoon/magicJar)",
        )

        val zipStream = ZipInputStream(connection.getInputStream())
        val entry = zipStream.nextEntry ?: throw KisInstrumentMasterException("ZIP 파일 내 entry 없음: url=$url")
        log.debug("[kis-master] ZIP entry: {}", entry.name)
        return zipStream
    }

    /**
     * MST 파일 스트림 파싱.
     * CP949 인코딩, 줄 단위 고정폭 레이아웃.
     */
    private fun parseMstStream(
        inputStream: InputStream,
        market: Market,
        part2Size: Int,
    ): List<InstrumentRaw> {
        val result = mutableListOf<InstrumentRaw>()
        // ISO_8859_1으로 줄 단위 읽기: CP949 raw bytes를 1:1 보존.
        // 이후 각 필드를 String(bytes, "CP949")로 정확히 디코딩한다.
        val reader = BufferedReader(InputStreamReader(inputStream, "ISO_8859_1"))

        reader.lines().forEach { line ->
            if (line.isBlank()) return@forEach

            val bytes = line.toByteArray(Charsets.ISO_8859_1)
            if (bytes.size < part2Size + 21) return@forEach

            // Part1: code(9) + isin(12) + name(remaining - part2Size)
            val part1Bytes = bytes.copyOfRange(0, bytes.size - part2Size)
            val part2Bytes = bytes.copyOfRange(bytes.size - part2Size, bytes.size)

            val code = String(bytes.copyOfRange(0, 9), charset("CP949")).trim()
            if (code.length < 6) return@forEach

            val isin = String(bytes.copyOfRange(9, 21), charset("CP949")).trim().takeIf { it.isNotBlank() }
            val name =
                if (part1Bytes.size > 21) {
                    String(part1Bytes.copyOfRange(21, part1Bytes.size), charset("CP949")).trim()
                } else {
                    ""
                }

            if (name.isBlank()) return@forEach

            // Part2 파싱 — 주요 필드만 추출
            val tradingHalted = extractString(part2Bytes, OFFSET_TRADING_HALT, 1).trim() == "Y"
            val tradingUnit = extractString(part2Bytes, OFFSET_TRADING_UNIT, LEN_TRADING_UNIT).trim().toIntOrNull() ?: 1
            val parValueStr = extractString(part2Bytes, OFFSET_PAR_VALUE, LEN_PAR_VALUE).trim()
            val parValue = parValueStr.toBigDecimalOrNull()
            val listedAtStr = extractString(part2Bytes, OFFSET_LISTED_DATE, LEN_LISTED_DATE).trim()
            val sectorCode = extractString(part2Bytes, OFFSET_SECTOR_CODE, 4).trim().takeIf { it.isNotBlank() }

            // 단축코드 6자리 (앞 0 패딩)
            val shortCode = code.padStart(6, '0').takeLast(6)

            result.add(
                InstrumentRaw(
                    code = shortCode,
                    isin = isin,
                    name = name,
                    market = market,
                    typeName = null,
                    sector = sectorCode,
                    listedAtStr = listedAtStr,
                    parValue = parValue,
                    tradingUnit = tradingUnit,
                    tradingHalted = tradingHalted,
                ),
            )
        }

        return result
    }

    private fun extractString(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): String {
        if (offset < 0 || offset + length > bytes.size) return ""
        return runCatching {
            String(bytes.copyOfRange(offset, offset + length), charset("CP949"))
        }.getOrDefault("")
    }
}
