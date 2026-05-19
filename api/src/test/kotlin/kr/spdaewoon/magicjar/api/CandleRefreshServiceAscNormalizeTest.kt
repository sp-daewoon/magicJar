package kr.spdaewoon.magicjar.api

import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.api.secret.AesGcmCipher
import kr.spdaewoon.magicjar.api.service.CandleRefreshService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.web.reactive.function.client.WebClient

/**
 * CandleRefreshService.parseKisDailyOutput2 — KIS DESC 응답 ASC 정규화 회귀 가드 (P0-B hotfix 2026-05-19 backend-core + ADR 0086 §11).
 *
 * 결함 evidence (한국석유 004090, 2026-05-19 — market-data PR #555 보고):
 *   KIS `inquire-daily-itemchartprice` (FHKST03010100) 응답 output2[]는 **최신→과거 DESC 순서**.
 *   햇징 룰 §1 (이중 검증) monotonic은 ASC를 강제하므로,
 *   parseKisDailyOutput2가 정규화 누락 시 idx 1+ 전부 reject → insertedCount=0.
 *   2025-12-31~2026-05-18 5개월 일봉 갭 누적의 root cause.
 *   consumer KisRestCandleAdapter(PR #537)·batch CandleRefreshJob(PR #540)은 fix 완료,
 *   api CandleRefreshService는 별도 path drift로 누락 → 본 fix로 정합 회복.
 *
 * Fix:
 *   `parseKisDailyOutput2` 반환 직전 `.sortedBy { it.epochSec }` 적용.
 *
 * raw spec:
 *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
 *
 * 테스트 매트릭스 (ADR 0086 §11 cover — FHKST03010100 4 PERIOD_DIV_CODE 공통 schema):
 *   (asc-normalize-daily-output2) KIS DESC 일봉(D) → parseKisDailyOutput2 ASC 검증 — 한국석유 evidence
 *   (asc-normalize-daily-output2-idempotent) ASC 입력 → ASC 유지 (sortedBy idempotent)
 *
 * 매트릭스 LOCK: 본 KIS endpoint는 4 PERIOD_DIV_CODE 공통 schema이므로 단일 parse 함수가 4 timeframe 전부 cover.
 * 일봉 회귀 가드 1건으로 함수 ASC 정규화 책임이 검증되며, 주봉·월봉·연봉은 동일 함수를 통과하므로 동일 보장.
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
class CandleRefreshServiceAscNormalizeTest {
    private fun buildService(): CandleRefreshService {
        // ASC 정규화는 외부 의존성 0이므로 모든 dependency mock 가능.
        return CandleRefreshService(
            webClient = mock<WebClient>(),
            instrumentJpaRepository = mock<InstrumentJpaRepository>(),
            credentialJpaRepository = mock<UserKisCredentialJpaRepository>(),
            candleJpaRepository = mock<CandleJpaRepository>(),
            aesGcmCipher = mock<AesGcmCipher>(),
            redisTemplate = mock<StringRedisTemplate>(),
            kisBaseProd = "https://openapi.koreainvestment.com:9443",
        )
    }

    /**
     * private fun parseKisDailyOutput2(output2, code, intervalCode) — reflection 호출.
     * 반환은 private nested data class KisDailyRow 리스트 → epochSec 필드 reflection 추출.
     */
    @Suppress("UNCHECKED_CAST")
    private fun invokeParseDailyOutput2(
        service: CandleRefreshService,
        output2: List<*>,
        code: String,
        intervalCode: String,
    ): List<Long> {
        val method =
            CandleRefreshService::class.java.getDeclaredMethod(
                "parseKisDailyOutput2",
                List::class.java,
                String::class.java,
                String::class.java,
            )
        method.isAccessible = true
        val rows = method.invoke(service, output2, code, intervalCode) as List<Any>

        // KisDailyRow.epochSec 필드 reflection 추출 (private nested data class).
        return rows.map { row ->
            val field = row.javaClass.getDeclaredField("epochSec")
            field.isAccessible = true
            field.getLong(row)
        }
    }

    @Test
    fun `asc-normalize-daily-output2 KIS DESC 일봉 응답 → ASC 변환 검증 (한국석유 결함 회귀 가드)`() {
        // given — KIS 응답이 DESC 순서 (최신→과거)로 들어옴. 한국석유(004090) evidence 동일 패턴.
        // 2025-12-30, 2025-12-29, 2025-12-26, 2025-12-24, 2025-12-23 5건.
        val output2 =
            listOf(
                mapOf(
                    "stck_bsop_date" to "20251230",
                    "stck_oprc" to "14450",
                    "stck_hgpr" to "14500",
                    "stck_lwpr" to "13850",
                    "stck_clpr" to "13950",
                    "acml_vol" to "56873",
                ),
                mapOf(
                    "stck_bsop_date" to "20251229",
                    "stck_oprc" to "13920",
                    "stck_hgpr" to "13960",
                    "stck_lwpr" to "13700",
                    "stck_clpr" to "13930",
                    "acml_vol" to "88870",
                ),
                mapOf(
                    "stck_bsop_date" to "20251226",
                    "stck_oprc" to "14060",
                    "stck_hgpr" to "14070",
                    "stck_lwpr" to "13840",
                    "stck_clpr" to "13920",
                    "acml_vol" to "92923",
                ),
                mapOf(
                    "stck_bsop_date" to "20251224",
                    "stck_oprc" to "14100",
                    "stck_hgpr" to "14150",
                    "stck_lwpr" to "13900",
                    "stck_clpr" to "14060",
                    "acml_vol" to "70000",
                ),
                mapOf(
                    "stck_bsop_date" to "20251223",
                    "stck_oprc" to "13800",
                    "stck_hgpr" to "14100",
                    "stck_lwpr" to "13750",
                    "stck_clpr" to "14080",
                    "acml_vol" to "65000",
                ),
            )

        // when
        val epochSecs = invokeParseDailyOutput2(buildService(), output2, "004090", "1d")

        // then — ASC monotonic (epochSec 오름차순) 검증 (햇징 룰 §1 정합).
        assertThat(epochSecs).hasSize(5)
        assertThat(epochSecs).isSorted
        // 첫 row가 가장 과거(2025-12-23), 마지막 row가 가장 최신(2025-12-30) — DESC 입력 → ASC 출력.
        // 2025-12-23 KST 00:00 → epoch second 1766415600
        // 2025-12-30 KST 00:00 → epoch second 1767020400
        assertThat(epochSecs.first()).isLessThan(epochSecs.last())
    }

    @Test
    fun `asc-normalize-daily-output2 ASC 정상 입력은 그대로 ASC 유지 (idempotent)`() {
        // given — 이미 ASC 정렬된 입력 (sortedBy idempotent 검증).
        val output2 =
            listOf(
                mapOf(
                    "stck_bsop_date" to "20251223",
                    "stck_oprc" to "13800",
                    "stck_hgpr" to "14100",
                    "stck_lwpr" to "13750",
                    "stck_clpr" to "14080",
                    "acml_vol" to "65000",
                ),
                mapOf(
                    "stck_bsop_date" to "20251224",
                    "stck_oprc" to "14100",
                    "stck_hgpr" to "14150",
                    "stck_lwpr" to "13900",
                    "stck_clpr" to "14060",
                    "acml_vol" to "70000",
                ),
                mapOf(
                    "stck_bsop_date" to "20251226",
                    "stck_oprc" to "14060",
                    "stck_hgpr" to "14070",
                    "stck_lwpr" to "13840",
                    "stck_clpr" to "13920",
                    "acml_vol" to "92923",
                ),
            )

        // when
        val epochSecs = invokeParseDailyOutput2(buildService(), output2, "004090", "1d")

        // then — 정렬 후에도 ASC 유지.
        assertThat(epochSecs).hasSize(3)
        assertThat(epochSecs).isSorted
    }
}
