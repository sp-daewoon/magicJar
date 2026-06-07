package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [KisRestAskingPriceAdapter] 단위 테스트 — ADR 0114 §10-3 R2-C1 FHKST01010200 응답 파싱.
 *
 * HTTP 호출 자체는 mock 없이(WebClient mock 복잡도 회피, 기존 KisOrderInquireCcldHttpAdapterTest 선례) —
 * **KIS 응답 output1 fixture → EntryQuote 매핑** helper(parseOutput1) 중심 검증.
 *
 *  (a) 정상 output1(askp1·bidp1·askp_rsqn1·aspr_acpt_hour) → EntryQuote
 *  (b) askp1 blank → null (호가 미가용)
 *  (c) askp1 0 → null
 *  (d) askp_rsqn1 누락 → askQty1 null 허용
 *  (e) aspr_acpt_hour HHMMSS → KST 당일 시각 합성 (초 단위)
 *
 * KIS-first: output1 필드명(askp1·bidp1·askp_rsqn1·aspr_acpt_hour)은 FHKST01010200 공식 규약.
 * AI 호출 0.
 */
class KisRestAskingPriceAdapterTest {
    private val adapter =
        KisRestAskingPriceAdapter(
            webClient = mockk(relaxed = true),
            kisAuthAdapter = mockk(relaxed = true),
            kisRateLimiterPort = mockk(relaxed = true),
            kisBaseProd = "https://openapi.koreainvestment.com:9443",
            defaultUserId = "tester",
        )

    @Test
    fun `정상 output1이면 EntryQuote로 매핑한다`() {
        val output1 =
            mapOf<String, Any>(
                "askp1" to "71000",
                "bidp1" to "70900",
                "askp_rsqn1" to "250",
                "aspr_acpt_hour" to "093015",
            )

        val q = adapter.parseOutput1("005930", output1)

        assertThat(q).isNotNull
        assertThat(q!!.ask1).isEqualByComparingTo("71000")
        assertThat(q.bid1).isEqualByComparingTo("70900")
        assertThat(q.askQty1).isEqualTo(250L)
    }

    @Test
    fun `askp1이 blank면 null을 반환한다`() {
        val output1 = mapOf<String, Any>("askp1" to "", "bidp1" to "70900")
        assertThat(adapter.parseOutput1("005930", output1)).isNull()
    }

    @Test
    fun `askp1이 0이면 null을 반환한다`() {
        val output1 = mapOf<String, Any>("askp1" to "0", "bidp1" to "70900")
        assertThat(adapter.parseOutput1("005930", output1)).isNull()
    }

    @Test
    fun `askp_rsqn1이 없어도 매핑된다 (잔량 optional)`() {
        val output1 = mapOf<String, Any>("askp1" to "71000", "bidp1" to "70900")

        val q = adapter.parseOutput1("005930", output1)

        assertThat(q).isNotNull
        assertThat(q!!.askQty1).isNull()
    }

    @Test
    fun `aspr_acpt_hour HHMMSS가 KST 당일 시각으로 합성된다`() {
        val output1 =
            mapOf<String, Any>(
                "askp1" to "71000",
                "bidp1" to "70900",
                "aspr_acpt_hour" to "093015",
            )

        val q = adapter.parseOutput1("005930", output1)

        // KST 09:30:15 → UTC 00:30:15. 초 단위까지 합성되었는지(now fallback 아님) 검증.
        assertThat(q).isNotNull
        assertThat(q!!.asOf.toString()).endsWith(":30:15Z")
    }
}
