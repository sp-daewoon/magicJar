package com.mj.api.infrastructure.dart

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.LocalDate

/**
 * DART 재무정보 REST 어댑터 (T20-T3).
 *
 * DART OpenAPI (https://opendart.fss.or.kr):
 * 1. GET /api/corpCode.xml — 전종목 고유번호 ZIP 다운로드 (앱 최초 1회 + 1일 캐시)
 * 2. GET /api/fnlttSinglAcntAll.json — 단일회사 전체 재무제표 (연간)
 *    파라미터: crtfc_key, corp_code, bsns_year, reprt_code(11011=연간), fs_div(CFS)
 *    응답 account_nm 별 thstrm_amount: 매출액·영업이익·당기순이익
 *
 * DART API 키 미설정(opendartApiKey blank) 시 graceful degradation — null 반환.
 *
 * 종목코드(6자리) → DART corp_code(8자리) 매핑:
 * - 앱 시작 후 최초 요청 시 전체 ZIP 다운로드, 메모리 맵 캐시 (1일 갱신).
 * - ZIP 다운로드 실패 시 null 반환 (graceful degradation).
 */
@Component
class DartFinancialAdapter(
    @Value("\${magicjar.dart.api-key:}")
    private val opendartApiKey: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** stock_code(6자리) → corp_code(8자리) 맵. 최초 API 호출 시 초기화. */
    @Volatile
    private var corpCodeMap: Map<String, String>? = null

    /** 마지막 맵 갱신 시각 (1일 캐시) */
    @Volatile
    private var corpMapLoadedAt: Long = 0L

    private val restClient: RestClient by lazy {
        RestClient
            .builder()
            .baseUrl(BASE_URL)
            .requestFactory(
                SimpleClientHttpRequestFactory().also {
                    it.setConnectTimeout(15_000)
                    it.setReadTimeout(30_000)
                },
            ).build()
    }

    /**
     * DART API 키 설정 여부. 경고 코드 구분에 사용.
     */
    fun isKeyConfigured(): Boolean = opendartApiKey.isNotBlank()

    /**
     * 종목코드로 최근 연간 재무정보 조회.
     * 실패 또는 키 미설정 시 null 반환.
     */
    fun fetchFinancials(stockCode: String): DartFinancials? {
        if (opendartApiKey.isBlank()) {
            log.info("[dart] api-key 미설정 — fetchFinancials 건너뜀 code={}", stockCode)
            return null
        }

        log.info("[dart] fetchFinancials 시작 code={}", stockCode)
        return try {
            val corpCode =
                resolveCorpCode(stockCode) ?: run {
                    log.info("[dart] corp_code 미발견 code={} — DART에 상장 정보 없거나 corp_code 맵 미로드", stockCode)
                    return null
                }
            log.info("[dart] corp_code 조회 성공 stockCode={} corpCode={}", stockCode, corpCode)
            fetchStatement(corpCode)
        } catch (ex: Exception) {
            log.warn("[dart] fetchFinancials 실패 code={} error={}", stockCode, ex.message)
            null
        }
    }

    /** 연간 재무제표 조회 (fnlttSinglAcntAll) */
    private fun fetchStatement(corpCode: String): DartFinancials? {
        val currentYear = LocalDate.now().year
        // 최근 결산 연도: 현재 4월 이전이면 전전년도, 4월 이후이면 전년도
        val targetYear =
            if (LocalDate.now().monthValue < 4) {
                (currentYear - 2).toString()
            } else {
                (currentYear - 1).toString()
            }

        val response =
            restClient
                .get()
                .uri { b ->
                    b
                        .path("/api/fnlttSinglAcntAll.json")
                        .queryParam("crtfc_key", opendartApiKey)
                        .queryParam("corp_code", corpCode)
                        .queryParam("bsns_year", targetYear)
                        .queryParam("reprt_code", "11011") // 연간
                        .queryParam("fs_div", "CFS") // 연결재무제표
                        .build()
                }.retrieve()
                .body(DartStatementResponse::class.java)
                ?: return null

        if (response.status != "000") {
            log.debug("[dart] 재무제표 없음 corp_code={} status={} message={}", corpCode, response.status, response.message)
            return null
        }

        return parseFinancials(response.list)
    }

    /**
     * account_nm 기준으로 주요 재무 수치 추출.
     * XBRL 계정명은 회사마다 약간 다를 수 있어 포함 여부로 매칭.
     */
    private fun parseFinancials(items: List<StatementItem>): DartFinancials {
        val byAccount = items.associateBy { it.accountNm.trim() }

        fun findAmount(vararg keys: String): BigDecimal? {
            for (key in keys) {
                val item = byAccount.entries.firstOrNull { (k, _) -> k.contains(key) }?.value ?: continue
                item.thstrmAmount
                    .replace(",", "")
                    .replace("-", "")
                    .trim()
                    .let { if (it.isBlank()) return@let else return it.toBigDecimalOrNull() }
            }
            return null
        }

        return DartFinancials(
            revenue = findAmount("매출액", "수익"),
            operatingIncome = findAmount("영업이익"),
            netIncome = findAmount("당기순이익"),
        )
    }

    /**
     * stock_code → corp_code 변환.
     * 맵이 없거나 1일 이상 경과 시 ZIP 재다운로드.
     */
    private fun resolveCorpCode(stockCode: String): String? {
        val now = System.currentTimeMillis()
        // 1일(86400초) 캐시
        if (corpCodeMap == null || now - corpMapLoadedAt > 86_400_000L) {
            loadCorpCodeMap()
        }
        return corpCodeMap?.get(stockCode)
    }

    /**
     * DART 전체 고유번호 ZIP 다운로드 후 메모리 맵 구성.
     * ZIP 안의 CORPCODE.xml을 파싱해 stock_code(6자리) → corp_code(8자리) 맵 생성.
     */
    private fun loadCorpCodeMap() {
        log.info("[dart] corp_code 맵 다운로드 시작 (ZIP ~3.5MB, 최초 1회)")
        try {
            val bytes =
                restClient
                    .get()
                    .uri { b ->
                        b
                            .path("/api/corpCode.xml")
                            .queryParam("crtfc_key", opendartApiKey)
                            .build()
                    }.retrieve()
                    .body(ByteArray::class.java)
                    ?: run {
                        log.warn("[dart] corp_code ZIP 응답 null — DART API 키 또는 네트워크 확인 필요")
                        return
                    }

            log.info("[dart] corp_code ZIP 수신 완료 size={}bytes — 파싱 중", bytes.size)
            val map = parseCorpCodeZip(bytes)
            corpCodeMap = map
            corpMapLoadedAt = System.currentTimeMillis()
            log.info("[dart] corp_code 맵 로드 완료: {} 건", map.size)
        } catch (ex: Exception) {
            log.warn("[dart] corp_code 맵 로드 실패: {}", ex.message)
        }
    }

    /**
     * ZIP → CORPCODE.xml 파싱.
     * XML 구조: <list><corp><corp_code>...</corp_code><stock_code>...</stock_code></corp>...</list>
     */
    private fun parseCorpCodeZip(zipBytes: ByteArray): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val zipStream = java.util.zip.ZipInputStream(zipBytes.inputStream())
            while (true) {
                val entry = zipStream.nextEntry ?: break
                if (!entry.name.endsWith(".xml", ignoreCase = true)) {
                    zipStream.closeEntry()
                    continue
                }

                val content = zipStream.readBytes().toString(Charsets.UTF_8)
                // 간단한 정규식 파싱 (DOM 파서 의존 없음)
                val corpPattern = Regex("<corp>(.*?)</corp>", RegexOption.DOT_MATCHES_ALL)
                val corpCodePat = Regex("<corp_code>(.*?)</corp_code>")
                val stockCodePat = Regex("<stock_code>(.*?)</stock_code>")

                corpPattern.findAll(content).forEach { match ->
                    val corpBlock = match.groupValues[1]
                    val corpCode =
                        corpCodePat
                            .find(corpBlock)
                            ?.groupValues
                            ?.get(1)
                            ?.trim() ?: return@forEach
                    val stockCode =
                        stockCodePat
                            .find(corpBlock)
                            ?.groupValues
                            ?.get(1)
                            ?.trim() ?: return@forEach
                    if (stockCode.isNotBlank() && stockCode.length == 6) {
                        map[stockCode] = corpCode
                    }
                }
                zipStream.closeEntry()
            }
        } catch (ex: Exception) {
            log.warn("[dart] corpCode.xml 파싱 실패: {}", ex.message)
        }
        return map
    }

    // ── 결과 DTO ──────────────────────────────────────────────────────────────

    data class DartFinancials(
        val revenue: BigDecimal? = null,
        val operatingIncome: BigDecimal? = null,
        val netIncome: BigDecimal? = null,
    )

    // ── 내부 DTO ──────────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class DartStatementResponse(
        @JsonProperty("status") val status: String = "",
        @JsonProperty("message") val message: String = "",
        @JsonProperty("list") val list: List<StatementItem> = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class StatementItem(
        @JsonProperty("sj_nm") val sjNm: String = "",
        @JsonProperty("account_nm") val accountNm: String = "",
        @JsonProperty("thstrm_amount") val thstrmAmount: String = "",
    )

    companion object {
        private const val BASE_URL = "https://opendart.fss.or.kr"
    }
}
