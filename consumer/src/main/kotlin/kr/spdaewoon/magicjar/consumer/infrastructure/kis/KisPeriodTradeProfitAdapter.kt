package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.account.port.KisPeriodTradeProfitPort
import kr.spdaewoon.magicjar.domain.account.port.KisPeriodTradeProfitRow
import kr.spdaewoon.magicjar.domain.account.port.KisPeriodTradeProfitSnapshot
import kr.spdaewoon.magicjar.domain.account.port.KisPeriodTradeProfitSummary
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * KIS 기간별매매손익현황조회(TTTC8715R) read-only adapter (2026-06-22 market-data 신설 — 개선 A).
 *
 * ### KIS-first raw 출처 (헌법 §3 — 추측 0)
 * open-trading-api `examples_llm/domestic_stock/inquire_period_trade_profit/inquire_period_trade_profit.py`:
 *  - API_URL = `/uapi/domestic-stock/v1/trading/inquire-period-trade-profit`, tr_id = `TTTC8715R`.
 *  - HTS [0856] 기간별 매매손익 "종목별" — 잔고 보유 여부 무관, 매도가 발생한 모든 종목.
 *  - params: CANO·ACNT_PRDT_CD·SORT_DVSN(02=최근)·INQR_STRT_DT·INQR_END_DT·CBLC_DVSN(00=전체)·PDNO(전체="")·CTX_AREA_FK100/NK100.
 *  - output1 field: trad_dt·pdno·prdt_name·sll_qty·sll_amt·rlzt_pfls·pfls_rt·fee·tl_tax·loan_int (chk_*.py COLUMN_MAPPING 인용).
 *  - output2 field: tot_rlzt_pfls·sll_fee_smtl·sll_tltx_smtl·tot_fee·tot_tltx·tot_pftrt.
 *
 * 공식 예제에 모의 TR 변형 부재 → REAL 전용. 주문 hot path 호출 금지(reconcile/검증 경로, BACKFILL priority).
 * KisRealizedProfitAdapter(TTTC8494R) 의 credential·auth·rate limit·페이지네이션 패턴을 동형 재사용한다.
 */
@Component
class KisPeriodTradeProfitAdapter(
    private val webClient: WebClient,
    private val secretCipher: SecretCipher,
    private val kisAuthAdapter: KisAuthAdapter,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    private val kisRateLimiterPort: KisRateLimiterPort,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
) : KisPeriodTradeProfitPort {
    private val log = LoggerFactory.getLogger(KisPeriodTradeProfitAdapter::class.java)

    override fun fetchPeriodTradeProfit(
        userId: String,
        mode: TradingMode,
        from: LocalDate,
        to: LocalDate,
    ): KisPeriodTradeProfitSnapshot {
        require(mode == TradingMode.REAL) {
            "KIS 기간별매매손익현황조회는 공식 예제 기준 REAL 전용입니다 — mode=$mode"
        }
        require(!from.isAfter(to)) { "조회 시작일이 종료일보다 늦습니다 — from=$from to=$to" }

        val cred = loadCredential(userId)
        val accessToken = kisAuthAdapter.getAccessToken(userId, AccountType.REAL)

        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null
        return try {
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)
            val appKeyStr = String(appKey)
            val secretKeyStr = String(secretKey)

            val response = fetchAllPages(cred, accessToken, appKeyStr, secretKeyStr, userId, from, to)
            KisPeriodTradeProfitSnapshot(
                userId = userId,
                mode = mode,
                from = from,
                to = to,
                rows = parseRows(response.output1),
                summary = parseSummary(response.output2),
            )
        } finally {
            appKey?.fill(0)
            secretKey?.fill(0)
        }
    }

    private fun fetchAllPages(
        cred: ConsumerUserKisCredentialJpaEntity,
        accessToken: String,
        appKey: String,
        secretKey: String,
        userId: String,
        from: LocalDate,
        to: LocalDate,
    ): PeriodTradeProfitRawResponse {
        val output1 = mutableListOf<Map<String, String>>()
        val output2 = mutableListOf<Map<String, String>>()
        var fk100 = ""
        var nk100 = ""
        var trCont = ""

        repeat(MAX_PAGE_DEPTH) { depth ->
            val page =
                requestPage(
                    cred = cred,
                    accessToken = accessToken,
                    appKey = appKey,
                    secretKey = secretKey,
                    userId = userId,
                    from = from,
                    to = to,
                    fk100 = fk100,
                    nk100 = nk100,
                    trCont = trCont,
                )
            output1 += page.output1
            output2 += page.output2

            val nextTrCont = page.trCont
            if (nextTrCont !in CONTINUATION_HEADERS) {
                return PeriodTradeProfitRawResponse(output1 = output1, output2 = output2)
            }
            fk100 = page.ctxAreaFk100
            nk100 = page.ctxAreaNk100
            trCont = NEXT_PAGE_TR_CONT

            if (depth == MAX_PAGE_DEPTH - 1) {
                log.warn("KIS 기간별매매손익 조회 페이지 제한 도달: user={}", maskUserId(userId))
            }
        }

        return PeriodTradeProfitRawResponse(output1 = output1, output2 = output2)
    }

    @Suppress("UNCHECKED_CAST")
    private fun requestPage(
        cred: ConsumerUserKisCredentialJpaEntity,
        accessToken: String,
        appKey: String,
        secretKey: String,
        userId: String,
        from: LocalDate,
        to: LocalDate,
        fk100: String,
        nk100: String,
        trCont: String,
    ): PeriodTradeProfitPageResponse {
        var acquiredToken = false
        var signalSent = false
        return try {
            kisRateLimiterPort.acquire(KisCallPriority.BACKFILL, timeoutMillis = RATE_LIMIT_ACQUIRE_TIMEOUT_MS, trId = TR_ID)
            acquiredToken = true

            val response =
                webClient
                    .get()
                    .uri { builder ->
                        builder
                            .scheme("https")
                            .host(parseHost(kisBaseProd))
                            .port(parsePort(kisBaseProd))
                            .path(PATH)
                            .queryParam("CANO", cred.accountNumber)
                            .queryParam("ACNT_PRDT_CD", cred.accountPrdtCd)
                            .queryParam("SORT_DVSN", "02")
                            .queryParam("INQR_STRT_DT", from.format(YMD))
                            .queryParam("INQR_END_DT", to.format(YMD))
                            .queryParam("CBLC_DVSN", "00")
                            .queryParam("PDNO", "")
                            .queryParam("CTX_AREA_FK100", fk100)
                            .queryParam("CTX_AREA_NK100", nk100)
                            .build()
                    }.header("authorization", "Bearer $accessToken")
                    .header("appkey", appKey)
                    .header("appsecret", secretKey)
                    .header("tr_id", TR_ID)
                    .header("tr_cont", trCont)
                    .header("custtype", "P")
                    .retrieve()
                    .toEntity(Map::class.java)
                    .timeout(TIMEOUT)
                    .block()
                    ?: error("KIS 기간별매매손익 조회 응답 없음 — userId=$userId")

            kisRateLimiterPort.onSuccess()
            signalSent = true

            val body = response.body as? Map<String, Any?> ?: error("KIS 기간별매매손익 조회 body 형식 오류")
            val rtCd = body["rt_cd"]?.toString()
            if (rtCd != "0") {
                error("KIS 기간별매매손익 조회 실패: rt_cd=$rtCd msg=${body["msg1"]}")
            }

            PeriodTradeProfitPageResponse(
                output1 = rows(body["output1"]),
                output2 = rows(body["output2"]),
                ctxAreaFk100 = body["ctx_area_fk100"]?.toString().orEmpty(),
                ctxAreaNk100 = body["ctx_area_nk100"]?.toString().orEmpty(),
                trCont = response.headers.getFirst("tr_cont").orEmpty(),
            )
        } catch (e: WebClientResponseException) {
            if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                kisRateLimiterPort.onTpsExceeded()
                signalSent = true
            }
            log.warn(
                "KIS 기간별매매손익 조회 HTTP 실패: user={} status={} body={}",
                maskUserId(userId),
                e.statusCode.value(),
                e.responseBodyAsString.take(200),
            )
            throw e
        } finally {
            if (acquiredToken && !signalSent) {
                log.warn("[KisRateLimit] period-trade-profit acquire 후 통계 시그널 미발행 가능 — user={}", maskUserId(userId))
            }
        }
    }

    internal fun parseRows(rows: List<Map<String, String>>): List<KisPeriodTradeProfitRow> =
        rows.mapNotNull { row ->
            val code = row["pdno"]?.ifBlank { null } ?: return@mapNotNull null
            KisPeriodTradeProfitRow(
                tradeDate = row["trad_dt"]?.ifBlank { null },
                code = code,
                name = row["prdt_name"]?.ifBlank { null },
                sellQty = row["sll_qty"]?.toBigDecimalOrNull(),
                sellAmount = row["sll_amt"]?.toBigDecimalOrNull(),
                realizedPnl = row["rlzt_pfls"]?.toBigDecimalOrNull(),
                profitRate = row["pfls_rt"]?.toBigDecimalOrNull(),
                fee = row["fee"]?.toBigDecimalOrNull(),
                tax = row["tl_tax"]?.toBigDecimalOrNull(),
                loanInterest = row["loan_int"]?.toBigDecimalOrNull(),
                raw = row,
            )
        }

    internal fun parseSummary(rows: List<Map<String, String>>): KisPeriodTradeProfitSummary? {
        val row = rows.firstOrNull { it.isNotEmpty() } ?: return null
        return KisPeriodTradeProfitSummary(
            totalRealizedPnl = bd(row["tot_rlzt_pfls"]),
            sellFeeTotal = bd(row["sll_fee_smtl"]),
            sellTaxTotal = bd(row["sll_tltx_smtl"]),
            // 개선 E: 매수수수료합계(buy_fee_smtl) — KIS rlzt_pfls 가 매수fee 까지 반영하는 잔차 분해/정확 보정용.
            // raw 출처 chk_inquire_period_trade_profit.py COLUMN_MAPPING: buy_fee_smtl = 매수수수료합계.
            buyFeeTotal = bd(row["buy_fee_smtl"]),
            totalFee = bd(row["tot_fee"]),
            totalTax = bd(row["tot_tltx"]),
            totalProfitRate = bd(row["tot_pftrt"]),
            raw = row,
        )
    }

    private fun loadCredential(userId: String): ConsumerUserKisCredentialJpaEntity =
        credentialRepository
            .findAllByAccountType(AccountType.REAL.name)
            .firstOrNull { it.id.userId == userId }
            ?: throw IllegalStateException("KIS 기간별매매손익 조회 자격증명 없음 — userId=${maskUserId(userId)} type=REAL")

    private fun rows(raw: Any?): List<Map<String, String>> =
        when (raw) {
            is List<*> -> raw.mapNotNull { (it as? Map<*, *>)?.toStringMap() }
            is Map<*, *> -> listOf(raw.toStringMap())
            else -> emptyList()
        }

    private fun Map<*, *>.toStringMap(): Map<String, String> =
        entries.associate { (key, value) -> key.toString() to (value?.toString() ?: "") }

    private fun bd(value: String?): BigDecimal? = value?.toBigDecimalOrNull()

    private fun parseHost(baseUrl: String): String = baseUrl.removePrefix("https://").substringBefore(":")

    private fun parsePort(baseUrl: String): Int =
        baseUrl
            .removePrefix("https://")
            .substringAfter(":", "")
            .toIntOrNull() ?: 443

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    private data class PeriodTradeProfitRawResponse(
        val output1: List<Map<String, String>>,
        val output2: List<Map<String, String>>,
    )

    private data class PeriodTradeProfitPageResponse(
        val output1: List<Map<String, String>>,
        val output2: List<Map<String, String>>,
        val ctxAreaFk100: String,
        val ctxAreaNk100: String,
        val trCont: String,
    )

    companion object {
        const val PATH = "/uapi/domestic-stock/v1/trading/inquire-period-trade-profit"
        const val TR_ID = "TTTC8715R"
        private val YMD = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val TIMEOUT = Duration.ofSeconds(10)
        private val CONTINUATION_HEADERS = setOf("M", "F")
        private const val NEXT_PAGE_TR_CONT = "N"
        private const val MAX_PAGE_DEPTH = 10
        private const val RATE_LIMIT_ACQUIRE_TIMEOUT_MS = 3000L
    }
}
