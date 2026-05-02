package com.mj.batch.infrastructure.kis.master

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.mj.domain.common.Market
import com.mj.domain.market.instrument.port.KisInstrumentMasterException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * RealKisInstrumentMasterAdapterIT — WireMock으로 KIS 정적 파일 서버를 모킹해
 * ZIP 다운로드 + MST 파싱 흐름을 검증한다.
 *
 * 시나리오:
 *  1. KOSPI 정상 ZIP → 종목 2건 파싱 성공
 *  2. KOSDAQ 정상 ZIP → 종목 1건 파싱 성공
 *  3. ZIP 내 빈 MST → emptyList 반환
 *  4. HTTP 500 응답 → KisInstrumentMasterException 발생
 *  5. ETF·ETN 요청 → downloadAll 자체가 emptyList 반환 (HTTP 요청 없음)
 */
class RealKisInstrumentMasterAdapterIT {
    private lateinit var wireMock: WireMockServer
    private lateinit var adapter: RealKisInstrumentMasterAdapter

    companion object {
        /** KOSPI Part2 고정폭 228 바이트 */
        private const val KOSPI_PART2_SIZE = 228

        /** KOSDAQ Part2 고정폭 222 바이트 */
        private const val KOSDAQ_PART2_SIZE = 222

        /**
         * 테스트용 MST 파일 한 행을 바이트 배열로 생성한다.
         *
         * Part1: code(9) + isin(12) + name(variable)
         * Part2: 고정폭 (part2Size 바이트)
         *
         * Part2 주요 offset:
         *  - [0..1]  : 2 바이트 패딩
         *  - [2..5]  : sector code 4자리
         *  - [44]    : trading halt flag ('N' or 'Y')
         *  - [45..49]: trading unit 5자리 (right-padded)
         *  - [47..58]: par value 12자리 (right-padded) — offset 47 기준
         *  - [79..86]: listed date 8자리 (yyyyMMdd)
         */
        fun buildMstRow(
            code: String,
            isin: String,
            name: String,
            part2Size: Int,
            sectorCode: String = "1000",
            tradingHalted: Boolean = false,
            tradingUnit: Int = 1,
            parValue: String = "5000",
            listedAt: String = "20050101",
        ): ByteArray {
            val cp949 = charset("CP949")
            val out = ByteArrayOutputStream()

            // Part1
            val codeBytes = code.padEnd(9, ' ').toByteArray(cp949).copyOf(9)
            val isinBytes = isin.padEnd(12, ' ').toByteArray(cp949).copyOf(12)
            val nameBytes = name.toByteArray(cp949)
            out.write(codeBytes)
            out.write(isinBytes)
            out.write(nameBytes)

            // Part2: 228 or 222 bytes (all spaces default)
            val part2 = ByteArray(part2Size) { ' '.code.toByte() }

            // sector offset=2 len=4
            sectorCode.padEnd(4, ' ').toByteArray(cp949).copyInto(part2, 2, 0, 4)

            // trading halt offset=44 len=1
            part2[44] = (if (tradingHalted) 'Y' else 'N').code.toByte()

            // trading unit offset=45 len=5
            tradingUnit
                .toString()
                .padStart(5, ' ')
                .toByteArray(cp949)
                .copyInto(part2, 45, 0, 5)

            // par value offset=47 len=12
            parValue.padStart(12, ' ').toByteArray(cp949).copyInto(part2, 47, 0, 12)

            // listed date offset=79 len=8
            listedAt.padEnd(8, ' ').toByteArray(cp949).copyInto(part2, 79, 0, 8)

            out.write(part2)
            return out.toByteArray()
        }

        /**
         * MST 행 목록을 ISO_8859_1 줄 단위 텍스트로 직렬화한 뒤 ZIP 바이트 배열을 반환한다.
         * RealKisInstrumentMasterAdapter는 CP949로 디코딩 하기 전에 ISO_8859_1로 줄을 읽는다.
         */
        fun buildMstZip(
            entryName: String,
            rows: List<ByteArray>,
        ): ByteArray {
            val baos = ByteArrayOutputStream()
            ZipOutputStream(baos).use { zip ->
                zip.putNextEntry(ZipEntry(entryName))
                rows.forEach { row ->
                    // 각 행을 ISO_8859_1 문자열로 변환 후 줄 구분자 추가
                    zip.write(row)
                    zip.write('\n'.code)
                }
                zip.closeEntry()
            }
            return baos.toByteArray()
        }
    }

    @BeforeEach
    fun setUp() {
        wireMock = WireMockServer(wireMockConfig().dynamicPort())
        wireMock.start()
        WireMock.configureFor("localhost", wireMock.port())

        // RealKisInstrumentMasterAdapter 내부 BASE_URL을 WireMock 서버로 교체
        adapter = RealKisInstrumentMasterAdapter("http://localhost:${wireMock.port()}/common/master")
    }

    @AfterEach
    fun tearDown() {
        wireMock.stop()
    }

    // -------------------------------------------------------------------------
    // T1. KOSPI 정상 ZIP — 종목 2건 파싱 성공
    // -------------------------------------------------------------------------
    @Test
    fun `KOSPI 정상 ZIP 다운로드 시 종목 2건이 파싱된다`() {
        val rows =
            listOf(
                buildMstRow(
                    code = "005930",
                    isin = "KR7005930003",
                    name = "삼성전자",
                    part2Size = KOSPI_PART2_SIZE,
                    sectorCode = "0260",
                    tradingUnit = 1,
                    parValue = "100",
                    listedAt = "19750611",
                ),
                buildMstRow(
                    code = "000660",
                    isin = "KR7000660001",
                    name = "SK하이닉스",
                    part2Size = KOSPI_PART2_SIZE,
                    sectorCode = "0260",
                    tradingUnit = 1,
                    parValue = "5000",
                    listedAt = "19960226",
                ),
            )
        val zipBytes = buildMstZip("kospi_code.mst", rows)

        wireMock.stubFor(
            get(urlPathMatching("/common/master/kospi_code.mst.zip"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/zip")
                        .withBody(zipBytes),
                ),
        )

        val result = adapter.downloadAll(Market.KOSPI)

        assertEquals(2, result.size)
        val samsung = result.find { it.code == "005930" }
        assertNotNull(samsung)
        assertEquals("삼성전자", samsung!!.name)
        assertEquals("KR7005930003", samsung.isin)
        assertEquals("19750611", samsung.listedAtStr)
        assertEquals(1, samsung.tradingUnit)
        assertFalse(samsung.tradingHalted)
    }

    // -------------------------------------------------------------------------
    // T2. KOSDAQ 정상 ZIP — 종목 1건 파싱 성공
    // -------------------------------------------------------------------------
    @Test
    fun `KOSDAQ 정상 ZIP 다운로드 시 종목 1건이 파싱된다`() {
        val rows =
            listOf(
                buildMstRow(
                    code = "035720",
                    isin = "KR7035720002",
                    name = "카카오",
                    part2Size = KOSDAQ_PART2_SIZE,
                    sectorCode = "5000",
                    tradingUnit = 1,
                    parValue = "100",
                    listedAt = "20170710",
                ),
            )
        val zipBytes = buildMstZip("kosdaq_code.mst", rows)

        wireMock.stubFor(
            get(urlPathMatching("/common/master/kosdaq_code.mst.zip"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/zip")
                        .withBody(zipBytes),
                ),
        )

        val result = adapter.downloadAll(Market.KOSDAQ)

        assertEquals(1, result.size)
        assertEquals("035720", result[0].code)
        assertEquals("카카오", result[0].name)
        assertEquals(Market.KOSDAQ, result[0].market)
    }

    // -------------------------------------------------------------------------
    // T3. 빈 MST 파일 → emptyList
    // -------------------------------------------------------------------------
    @Test
    fun `빈 MST ZIP 다운로드 시 빈 리스트가 반환된다`() {
        val zipBytes = buildMstZip("kospi_code.mst", emptyList())

        wireMock.stubFor(
            get(urlPathMatching("/common/master/kospi_code.mst.zip"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/zip")
                        .withBody(zipBytes),
                ),
        )

        val result = adapter.downloadAll(Market.KOSPI)

        assertTrue(result.isEmpty())
    }

    // -------------------------------------------------------------------------
    // T4. HTTP 500 → KisInstrumentMasterException
    // -------------------------------------------------------------------------
    @Test
    fun `HTTP 500 응답 시 KisInstrumentMasterException 이 발생한다`() {
        wireMock.stubFor(
            get(urlPathMatching("/common/master/kospi_code.mst.zip"))
                .willReturn(
                    aResponse()
                        .withStatus(500)
                        .withBody("Internal Server Error"),
                ),
        )

        assertThrows(KisInstrumentMasterException::class.java) {
            adapter.downloadAll(Market.KOSPI)
        }
    }

    // -------------------------------------------------------------------------
    // T5. ETF·ETN 요청 → HTTP 요청 없이 emptyList 반환
    // -------------------------------------------------------------------------
    @Test
    fun `ETF 요청 시 HTTP 요청 없이 빈 리스트가 반환된다`() {
        val result = adapter.downloadAll(Market.ETF)
        assertTrue(result.isEmpty())
        // WireMock에 요청이 없어야 한다
        wireMock.verify(0, WireMock.getRequestedFor(urlPathMatching("/common/master/.*")))
    }

    @Test
    fun `ETN 요청 시 HTTP 요청 없이 빈 리스트가 반환된다`() {
        val result = adapter.downloadAll(Market.ETN)
        assertTrue(result.isEmpty())
        wireMock.verify(0, WireMock.getRequestedFor(urlPathMatching("/common/master/.*")))
    }

    // -------------------------------------------------------------------------
    // T6. 거래정지 종목 플래그 파싱
    // -------------------------------------------------------------------------
    @Test
    fun `거래정지 종목의 tradingHalted 가 true 로 파싱된다`() {
        val rows =
            listOf(
                buildMstRow(
                    code = "999999",
                    isin = "KR7999999001",
                    name = "거래정지종목",
                    part2Size = KOSPI_PART2_SIZE,
                    tradingHalted = true,
                ),
            )
        val zipBytes = buildMstZip("kospi_code.mst", rows)

        wireMock.stubFor(
            get(urlPathMatching("/common/master/kospi_code.mst.zip"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/zip")
                        .withBody(zipBytes),
                ),
        )

        val result = adapter.downloadAll(Market.KOSPI)

        assertEquals(1, result.size)
        assertTrue(result[0].tradingHalted)
    }
}
