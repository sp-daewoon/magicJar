package com.mj.api.infrastructure.dart

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * DART corpCode ZIP 파싱 단위 테스트.
 *
 * 배경: 이전 구현이 <corp> 태그를 찾았으나 실제 CORPCODE.xml은 <list> 태그를 사용함.
 * 이 테스트는 수정된 parseCorpCodeZip이 <list> 태그를 정상 파싱하는지 검증한다.
 */
class DartCorpCodeParseTest {
    /**
     * 실제 DART CORPCODE.xml 구조를 모방한 ZIP bytes 생성.
     */
    private fun makeFakeZip(xmlContent: String): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("CORPCODE.xml"))
            zos.write(xmlContent.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return bos.toByteArray()
    }

    private val sampleXml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <result>
            <list>
                <corp_code>00126380</corp_code>
                <corp_name>삼성전자</corp_name>
                <corp_eng_name>SAMSUNG ELECTRONICS CO,.LTD</corp_eng_name>
                <stock_code>005930</stock_code>
                <modify_date>20251201</modify_date>
            </list>
            <list>
                <corp_code>00164779</corp_code>
                <corp_name>SK하이닉스</corp_name>
                <corp_eng_name>SK hynix Inc.</corp_eng_name>
                <stock_code>000660</stock_code>
                <modify_date>20251201</modify_date>
            </list>
            <list>
                <corp_code>00434003</corp_code>
                <corp_name>비상장법인</corp_name>
                <corp_eng_name>Unlisted Corp</corp_eng_name>
                <stock_code> </stock_code>
                <modify_date>20170630</modify_date>
            </list>
        </result>
        """.trimIndent()

    @Test
    fun `실제 list 태그 구조 파싱 성공`() {
        val adapter = DartFinancialAdapter(opendartApiKey = "test-key")
        val zipBytes = makeFakeZip(sampleXml)
        val map = adapter.parseCorpCodeZipForTest(zipBytes)

        map.containsKey("005930") shouldBe true
        map["005930"] shouldBe "00126380"
        map.containsKey("000660") shouldBe true
        map["000660"] shouldBe "00164779"
    }

    @Test
    fun `비상장 법인(stock_code 공백)은 맵에서 제외`() {
        val adapter = DartFinancialAdapter(opendartApiKey = "test-key")
        val zipBytes = makeFakeZip(sampleXml)
        val map = adapter.parseCorpCodeZipForTest(zipBytes)

        // " " → padStart(6,'0') = "000000" → 제외 조건
        map.containsKey("000000") shouldBe false
        map.containsKey("      ") shouldBe false
    }

    @Test
    fun `zero-padding 없는 stock_code도 정규화되어 6자리로 저장`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <result>
                <list>
                    <corp_code>99999999</corp_code>
                    <corp_name>패딩테스트</corp_name>
                    <corp_eng_name>PaddingTest</corp_eng_name>
                    <stock_code>5930</stock_code>
                    <modify_date>20251201</modify_date>
                </list>
            </result>
            """.trimIndent()
        val adapter = DartFinancialAdapter(opendartApiKey = "test-key")
        val zipBytes = makeFakeZip(xml)
        val map = adapter.parseCorpCodeZipForTest(zipBytes)

        map.containsKey("005930") shouldBe true
        map["005930"] shouldBe "99999999"
    }
}
