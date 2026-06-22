package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import io.mockk.mockk
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal

/**
 * KisPeriodTradeProfitAdapter(TTTC8715R) 파싱 단위 테스트.
 *
 * raw 출처 정합 검증: chk_inquire_period_trade_profit.py COLUMN_MAPPING 기준 output1·output2 필드 매핑.
 * 개선 E: buy_fee_smtl(매수수수료합계) 신규 매핑 — KIS rlzt_pfls 가 매수fee 까지 반영하는 잔차 분해/정확 보정용.
 */
class KisPeriodTradeProfitAdapterTest {
    private val adapter =
        KisPeriodTradeProfitAdapter(
            webClient = mockk<WebClient>(relaxed = true),
            secretCipher = mockk<SecretCipher>(relaxed = true),
            kisAuthAdapter = mockk<KisAuthAdapter>(relaxed = true),
            credentialRepository = mockk<ConsumerUserKisCredentialJpaRepository>(relaxed = true),
            kisRateLimiterPort = mockk<KisRateLimiterPort>(relaxed = true),
            kisBaseProd = "https://openapi.koreainvestment.com:9443",
        )

    @Test
    fun `output1 종목별 행을 실현손익·수수료·제세금으로 매핑한다`() {
        val rows =
            adapter.parseRows(
                listOf(
                    mapOf(
                        "trad_dt" to "20260622",
                        "pdno" to "000500",
                        "prdt_name" to "가나다",
                        "sll_qty" to "10",
                        "sll_amt" to "1100000",
                        "rlzt_pfls" to "-748",
                        "pfls_rt" to "-0.07",
                        "fee" to "80",
                        "tl_tax" to "668",
                        "loan_int" to "0",
                    ),
                ),
            )

        assertThat(rows).hasSize(1)
        assertThat(rows[0].code).isEqualTo("000500")
        assertThat(rows[0].realizedPnl).isEqualByComparingTo(BigDecimal("-748"))
        assertThat(rows[0].fee).isEqualByComparingTo(BigDecimal("80"))
        assertThat(rows[0].tax).isEqualByComparingTo(BigDecimal("668"))
    }

    @Test
    fun `output2 계좌합계를 총실현손익·매도비용·매수수수료로 매핑한다 (개선 E buy_fee_smtl)`() {
        val summary =
            adapter.parseSummary(
                listOf(
                    mapOf(
                        "tot_rlzt_pfls" to "21542",
                        "sll_fee_smtl" to "320",
                        "sll_tltx_smtl" to "5239",
                        "buy_fee_smtl" to "319",
                        "tot_fee" to "639",
                        "tot_tltx" to "5239",
                        "tot_pftrt" to "0.5",
                    ),
                ),
            )

        assertThat(summary).isNotNull
        assertThat(summary!!.totalRealizedPnl).isEqualByComparingTo(BigDecimal("21542"))
        assertThat(summary.sellFeeTotal).isEqualByComparingTo(BigDecimal("320"))
        assertThat(summary.sellTaxTotal).isEqualByComparingTo(BigDecimal("5239"))
        // 개선 E: buy_fee_smtl 매핑 — KIS rlzt_pfls 의 매수fee 반영분을 잔차 0 으로 흡수하는 핵심 필드.
        assertThat(summary.buyFeeTotal).isEqualByComparingTo(BigDecimal("319"))
        assertThat(summary.totalFee).isEqualByComparingTo(BigDecimal("639"))
    }

    @Test
    fun `buy_fee_smtl 미수신이면 buyFeeTotal=null`() {
        val summary =
            adapter.parseSummary(
                listOf(
                    mapOf(
                        "tot_rlzt_pfls" to "1000",
                        "sll_fee_smtl" to "10",
                        "sll_tltx_smtl" to "20",
                    ),
                ),
            )

        assertThat(summary).isNotNull
        assertThat(summary!!.buyFeeTotal).isNull()
    }
}
