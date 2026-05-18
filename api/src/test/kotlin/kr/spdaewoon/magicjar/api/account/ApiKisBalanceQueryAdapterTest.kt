package kr.spdaewoon.magicjar.api.account

import kr.spdaewoon.magicjar.api.infrastructure.kis.ApiKisBalanceQueryAdapter
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal

/**
 * ApiKisBalanceQueryAdapter 단위 테스트 — T-ACCT Phase 1B (ADR 0081 §2-6).
 *
 * KIS-first raw fetch 인용 (헌법 §3 LOCK · spec §3-1):
 * - https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/inquire_balance.py
 * - https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/chk_inquire_balance.py
 *
 * 검증 대상:
 * - output1 보유 종목 8 컬럼 파싱 + qty 0 필터
 * - output2 11 필드 → AccountBalance 매핑 (한글 의미 매트릭스 LOCK)
 * - output2 null/empty → mock 합성 0 LOCK (IllegalStateException 발행)
 * - asst_icdc_amt / asst_icdc_erng_rt 옵션 필드 null 허용
 *
 * WebClient 호출 흐름은 통합 테스트 영역 (본 단위 테스트는 파싱 책임만 검증).
 */
class ApiKisBalanceQueryAdapterTest {
    private val webClient: WebClient = mock()
    private val secretCipher: SecretCipher = mock()
    private val credentialRepository: UserKisCredentialJpaRepository = mock()
    private val redis: StringRedisTemplate = mock()

    private val sut =
        ApiKisBalanceQueryAdapter(
            webClient = webClient,
            secretCipher = secretCipher,
            credentialRepository = credentialRepository,
            redisTemplate = redis,
            kisBaseProd = "https://openapi.koreainvestment.com:9443",
            kisBaseVps = "https://openapivts.koreainvestment.com:29443",
        )

    @Test
    fun `output2 11 필드 매핑 — KIS 한글 의미 매트릭스 LOCK`() {
        val response =
            mapOf<String, Any>(
                "output2" to
                    listOf(
                        mapOf(
                            // dnca_tot_amt          → 예수금 총금액
                            "dnca_tot_amt" to "10000000",
                            // nxdy_excc_amt         → D+1 예수금
                            "nxdy_excc_amt" to "10000000",
                            // prvs_rcdl_excc_amt    → D+2 예수금 (실가용)
                            "prvs_rcdl_excc_amt" to "10000000",
                            // tot_evlu_amt          → 총 평가금액 (자산)
                            "tot_evlu_amt" to "12000000",
                            // nass_amt              → 순자산금액
                            "nass_amt" to "12000000",
                            // pchs_amt_smtl_amt     → 매입금액 합계
                            "pchs_amt_smtl_amt" to "1900000",
                            // evlu_amt_smtl_amt     → 평가금액 합계
                            "evlu_amt_smtl_amt" to "2000000",
                            // evlu_pfls_smtl_amt    → 평가손익 합계
                            "evlu_pfls_smtl_amt" to "100000",
                            // asst_icdc_amt         → 자산증감액 (전일 대비)
                            "asst_icdc_amt" to "50000",
                            // asst_icdc_erng_rt     → 자산증감 수익률 %
                            "asst_icdc_erng_rt" to "0.42",
                        ),
                    ),
            )

        val balance = sut.parseOutput2(response)

        assertThat(balance.cashBalance).isEqualByComparingTo("10000000")
        assertThat(balance.cashD1).isEqualByComparingTo("10000000")
        assertThat(balance.cashD2).isEqualByComparingTo("10000000")
        assertThat(balance.totalEvaluation).isEqualByComparingTo("12000000")
        assertThat(balance.netAssetValue).isEqualByComparingTo("12000000")
        assertThat(balance.totalPurchaseAmount).isEqualByComparingTo("1900000")
        assertThat(balance.totalHoldingEvaluation).isEqualByComparingTo("2000000")
        assertThat(balance.totalProfitLoss).isEqualByComparingTo("100000")
        assertThat(balance.assetIncreaseDecrease).isEqualByComparingTo("50000")
        assertThat(balance.assetIncreaseDecreaseRate).isEqualByComparingTo("0.42")
    }

    @Test
    fun `output2 Map 형식 응답 — List 외에 Map 단일 객체도 흡수`() {
        // KIS 응답이 단일 Map인 경우 (일부 환경에서 단건 응답)
        val response =
            mapOf<String, Any>(
                "output2" to
                    mapOf(
                        "dnca_tot_amt" to "5000000",
                        "nxdy_excc_amt" to "5000000",
                        "prvs_rcdl_excc_amt" to "5000000",
                        "tot_evlu_amt" to "5000000",
                        "nass_amt" to "5000000",
                        "pchs_amt_smtl_amt" to "0",
                        "evlu_amt_smtl_amt" to "0",
                        "evlu_pfls_smtl_amt" to "0",
                    ),
            )

        val balance = sut.parseOutput2(response)

        assertThat(balance.cashBalance).isEqualByComparingTo("5000000")
        // 옵션 필드 부재 → null
        assertThat(balance.assetIncreaseDecrease).isNull()
        assertThat(balance.assetIncreaseDecreaseRate).isNull()
    }

    @Test
    fun `output2 null — mock 합성 0 LOCK (IllegalStateException 발행)`() {
        val response = mapOf<String, Any>("output1" to emptyList<Map<String, Any>>())

        assertThatThrownBy { sut.parseOutput2(response) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("output2 응답 부재")
            .hasMessageContaining("mock 합성 0 LOCK")
    }

    @Test
    fun `output2 empty list — mock 합성 0 LOCK (IllegalStateException 발행)`() {
        val response = mapOf<String, Any>("output2" to emptyList<Map<String, Any>>())

        assertThatThrownBy { sut.parseOutput2(response) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("output2 응답 부재")
    }

    @Test
    fun `output1 8 컬럼 매핑 — pdno·prdt_name·hldg_qty·pchs_avg_pric·prpr·evlu_amt·evlu_pfls_amt·evlu_pfls_rt`() {
        val response =
            mapOf<String, Any>(
                "output1" to
                    listOf(
                        mapOf(
                            "pdno" to "005930",
                            "prdt_name" to "삼성전자",
                            "hldg_qty" to "10",
                            "pchs_avg_pric" to "70000",
                            "prpr" to "71000",
                            "evlu_amt" to "710000",
                            "evlu_pfls_amt" to "10000",
                            "evlu_pfls_rt" to "1.43",
                        ),
                        mapOf(
                            "pdno" to "000660",
                            "prdt_name" to "SK하이닉스",
                            "hldg_qty" to "5",
                            "pchs_avg_pric" to "240000",
                            "prpr" to "258000",
                            "evlu_amt" to "1290000",
                            "evlu_pfls_amt" to "90000",
                            "evlu_pfls_rt" to "7.50",
                        ),
                    ),
            )

        val holdings = sut.parseOutput1(response)

        assertThat(holdings).hasSize(2)
        val samsung = holdings[0]
        assertThat(samsung.code).isEqualTo("005930")
        assertThat(samsung.name).isEqualTo("삼성전자")
        assertThat(samsung.quantity).isEqualTo(10)
        assertThat(samsung.avgPrice).isEqualByComparingTo(BigDecimal("70000"))
        assertThat(samsung.currentPrice).isEqualByComparingTo(BigDecimal("71000"))
        assertThat(samsung.evaluationAmount).isEqualByComparingTo(BigDecimal("710000"))
        assertThat(samsung.profitLoss).isEqualByComparingTo(BigDecimal("10000"))
        assertThat(samsung.profitLossRate).isEqualByComparingTo(BigDecimal("1.43"))

        val hynix = holdings[1]
        assertThat(hynix.code).isEqualTo("000660")
        assertThat(hynix.name).isEqualTo("SK하이닉스")
    }

    @Test
    fun `output1 qty 0 항목 필터 — Bootstrap 어댑터와 동일 규칙`() {
        val response =
            mapOf<String, Any>(
                "output1" to
                    listOf(
                        mapOf(
                            "pdno" to "005930",
                            "prdt_name" to "삼성전자",
                            "hldg_qty" to "10",
                            "pchs_avg_pric" to "70000",
                        ),
                        mapOf(
                            "pdno" to "000660",
                            "prdt_name" to "SK하이닉스",
                            "hldg_qty" to "0",
                            "pchs_avg_pric" to "240000",
                        ),
                    ),
            )

        val holdings = sut.parseOutput1(response)

        // qty 0인 SK하이닉스는 제외
        assertThat(holdings).hasSize(1)
        assertThat(holdings[0].code).isEqualTo("005930")
    }

    @Test
    fun `output1 부재 — 빈 리스트 (정상 케이스)`() {
        val response = mapOf<String, Any>("output2" to listOf(mapOf("dnca_tot_amt" to "0")))

        val holdings = sut.parseOutput1(response)

        assertThat(holdings).isEmpty()
    }

    @Test
    fun `output1 누락 필드 — 0 fallback (KIS 일부 필드 누락 graceful)`() {
        val response =
            mapOf<String, Any>(
                "output1" to
                    listOf(
                        mapOf(
                            "pdno" to "005930",
                            "hldg_qty" to "10",
                            // prdt_name·pchs_avg_pric·prpr·evlu_amt·... 누락
                        ),
                    ),
            )

        val holdings = sut.parseOutput1(response)

        assertThat(holdings).hasSize(1)
        assertThat(holdings[0].name).isEqualTo("")
        assertThat(holdings[0].avgPrice).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(holdings[0].currentPrice).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(holdings[0].profitLossRate).isEqualByComparingTo(BigDecimal.ZERO)
    }
}
