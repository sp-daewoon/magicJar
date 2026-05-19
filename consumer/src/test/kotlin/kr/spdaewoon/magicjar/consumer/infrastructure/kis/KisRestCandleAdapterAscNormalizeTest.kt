package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import io.mockk.mockk
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.time.LocalDate

/**
 * KisRestCandleAdapter — KIS DESC 응답 ASC 정규화 회귀 가드 (P0 fix 2026-05-19 chart-curator).
 *
 * 결함 evidence (한국석유 004090, 2026-05-19):
 *   KIS `inquire-daily-itemchartprice` (FHKST03010100) 응답 output2[]는 **최신→과거 DESC 순서**.
 *   햇징 룰 §1 (이중 검증) monotonic은 ASC를 강제하므로,
 *   parseKisDailyResponse가 정규화 누락 시 idx 1+ 전부 reject → insertedCount=0.
 *   2025-12-31~2026-05-18 5개월 일봉 갭 누적의 root cause.
 *
 * Fix:
 *   `parseKisDailyResponse` / `parseKis1mResponse` 마지막 `.sortedBy { it.ts.epochSeconds }` 적용.
 *
 * raw spec:
 *   - inquire-daily-itemchartprice: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
 *   - inquire-time-dailychartprice: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
 *
 * 테스트 매트릭스:
 *   (asc-normalize-daily) KIS DESC 응답 → parseKisDailyResponse 반환 ASC 검증
 *   (asc-normalize-minute) KIS DESC 1m 응답 → parseKis1mResponse 반환 ASC 검증
 *   (rt-cd-fail) rt_cd != "0" → emptyList 반환 (기존 동작 회귀 가드)
 *   (empty-output) output2 = empty → emptyList 반환
 */
class KisRestCandleAdapterAscNormalizeTest {
    private fun buildAdapter(): KisRestCandleAdapter {
        // ASC 정규화는 외부 의존성 0이므로 모든 dependency mock 가능.
        return KisRestCandleAdapter(
            webClient = mockk<WebClient>(relaxed = true),
            kisAuthAdapter = mockk<KisAuthAdapter>(relaxed = true),
            secretCipher = mockk<SecretCipher>(relaxed = true),
            credentialRepository = mockk<ConsumerUserKisCredentialJpaRepository>(relaxed = true),
            instrumentRepository = mockk<ConsumerInstrumentJpaRepository>(relaxed = true),
            kisBaseProd = "https://openapi.koreainvestment.com:9443",
            kisBaseVps = "https://openapivts.koreainvestment.com:29443",
        )
    }

    /**
     * private fun parseKisDailyResponse(body, code, interval) — reflection 호출.
     */
    @Suppress("UNCHECKED_CAST")
    private fun invokeParseDaily(
        adapter: KisRestCandleAdapter,
        body: Map<*, *>,
        code: String,
        interval: CandleInterval,
    ): List<Candle> {
        val method =
            KisRestCandleAdapter::class.java.getDeclaredMethod(
                "parseKisDailyResponse",
                Map::class.java,
                String::class.java,
                CandleInterval::class.java,
            )
        method.isAccessible = true
        return method.invoke(adapter, body, code, interval) as List<Candle>
    }

    /**
     * private fun parseKis1mResponse(body, code, targetDate) — reflection 호출.
     */
    @Suppress("UNCHECKED_CAST")
    private fun invokeParse1m(
        adapter: KisRestCandleAdapter,
        body: Map<*, *>,
        code: String,
        targetDate: LocalDate,
    ): List<Candle> {
        val method =
            KisRestCandleAdapter::class.java.getDeclaredMethod(
                "parseKis1mResponse",
                Map::class.java,
                String::class.java,
                LocalDate::class.java,
            )
        method.isAccessible = true
        return method.invoke(adapter, body, code, targetDate) as List<Candle>
    }

    @Test
    fun `asc-normalize-daily KIS DESC 일봉 응답 → ASC 변환 검증 (한국석유 결함 회귀 가드)`() {
        // given — KIS 응답이 DESC 순서 (최신→과거)로 들어옴. 한국석유 evidence 동일 패턴.
        // 2025-12-30, 2025-12-29, 2025-12-26, 2025-12-24, 2025-12-23 5건.
        val body =
            mapOf(
                "rt_cd" to "0",
                "msg1" to "OK",
                "output2" to
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
                    ),
            )

        // when
        val candles = invokeParseDaily(buildAdapter(), body, "004090", CandleInterval.ONE_DAY)

        // then — ASC monotonic (epochSeconds 오름차순) 검증 (햇징 룰 §1 정합).
        assertThat(candles).hasSize(5)
        val epochSeconds = candles.map { it.ts.epochSeconds }
        assertThat(epochSeconds).isSorted
        // 첫 row가 가장 과거(2025-12-23), 마지막 row가 가장 최신(2025-12-30) — DESC 입력 → ASC 출력.
        assertThat(candles.first().closeRaw.toLong()).isEqualTo(14080L) // 2025-12-23 close
        assertThat(candles.last().closeRaw.toLong()).isEqualTo(13950L) // 2025-12-30 close
        // 모든 row code·interval·source 정합.
        assertThat(candles).allSatisfy { c ->
            assertThat(c.code).isEqualTo("004090")
            assertThat(c.interval).isEqualTo(CandleInterval.ONE_DAY)
            assertThat(c.source).isEqualTo("KIS")
        }
    }

    @Test
    fun `asc-normalize-daily ASC 정상 입력은 그대로 ASC 유지 (idempotent)`() {
        // given — 이미 ASC 정렬된 입력 (KIS spec 변경 또는 다른 adapter 경로).
        val body =
            mapOf(
                "rt_cd" to "0",
                "output2" to
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
                    ),
            )

        // when
        val candles = invokeParseDaily(buildAdapter(), body, "004090", CandleInterval.ONE_DAY)

        // then — 정렬 후에도 ASC 유지 (sortedBy는 idempotent).
        assertThat(candles).hasSize(3)
        assertThat(candles.map { it.ts.epochSeconds }).isSorted
        assertThat(candles.first().closeRaw.toLong()).isEqualTo(14080L)
        assertThat(candles.last().closeRaw.toLong()).isEqualTo(13920L)
    }

    @Test
    fun `asc-normalize-minute KIS DESC 1m 응답 → ASC 변환 검증`() {
        // given — KIS inquire-time-dailychartprice 1m 응답 DESC 가정.
        // 2026-05-19 14:30, 14:29, 14:28, 14:27, 14:26 5건.
        val body =
            mapOf(
                "rt_cd" to "0",
                "output2" to
                    listOf(
                        mapOf(
                            "stck_bsop_date" to "20260519",
                            "stck_cntg_hour" to "143000",
                            "stck_oprc" to "13000",
                            "stck_hgpr" to "13050",
                            "stck_lwpr" to "12980",
                            "stck_prpr" to "13020",
                            "acml_vol" to "1000",
                        ),
                        mapOf(
                            "stck_bsop_date" to "20260519",
                            "stck_cntg_hour" to "142900",
                            "stck_oprc" to "12990",
                            "stck_hgpr" to "13010",
                            "stck_lwpr" to "12970",
                            "stck_prpr" to "13000",
                            "acml_vol" to "950",
                        ),
                        mapOf(
                            "stck_bsop_date" to "20260519",
                            "stck_cntg_hour" to "142800",
                            "stck_oprc" to "12980",
                            "stck_hgpr" to "13000",
                            "stck_lwpr" to "12960",
                            "stck_prpr" to "12990",
                            "acml_vol" to "900",
                        ),
                    ),
            )

        // when
        val candles = invokeParse1m(buildAdapter(), body, "004090", LocalDate.of(2026, 5, 19))

        // then — ASC monotonic.
        assertThat(candles).hasSize(3)
        assertThat(candles.map { it.ts.epochSeconds }).isSorted
        // 첫 row가 14:28, 마지막 row가 14:30.
        assertThat(candles.first().closeRaw.toLong()).isEqualTo(12990L)
        assertThat(candles.last().closeRaw.toLong()).isEqualTo(13020L)
        // 모든 row interval = 1m.
        assertThat(candles).allSatisfy { c ->
            assertThat(c.interval).isEqualTo(CandleInterval.ONE_MIN)
            assertThat(c.code).isEqualTo("004090")
        }
    }

    @Test
    fun `rt-cd-fail rt_cd != 0 → emptyList 반환 (기존 동작 회귀 가드)`() {
        // given — KIS 인증실패·종목없음 등 rt_cd != "0" 응답.
        val body =
            mapOf(
                "rt_cd" to "1",
                "msg1" to "유효하지 않은 종목코드입니다.",
                "output2" to emptyList<Map<String, Any>>(),
            )

        // when
        val candles = invokeParseDaily(buildAdapter(), body, "999999", CandleInterval.ONE_DAY)

        // then
        assertThat(candles).isEmpty()
    }

    @Test
    fun `empty-output output2 = empty → emptyList 반환`() {
        // given — KIS 정상 응답이지만 데이터 0건 (휴장일 등).
        val body =
            mapOf(
                "rt_cd" to "0",
                "msg1" to "OK",
                "output2" to emptyList<Map<String, Any>>(),
            )

        // when
        val candles = invokeParseDaily(buildAdapter(), body, "004090", CandleInterval.ONE_DAY)

        // then
        assertThat(candles).isEmpty()
    }
}
