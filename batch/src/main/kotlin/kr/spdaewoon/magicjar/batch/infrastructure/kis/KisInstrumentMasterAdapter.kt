package kr.spdaewoon.magicjar.batch.infrastructure.kis

import kotlinx.datetime.LocalDate
import kr.spdaewoon.magicjar.domain.instrument.Instrument
import kr.spdaewoon.magicjar.domain.instrument.InstrumentMarket
import kr.spdaewoon.magicjar.domain.instrument.InstrumentType
import kr.spdaewoon.magicjar.domain.instrument.port.KisInstrumentMasterPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.time.Duration
import java.util.zip.ZipInputStream

/**
 * KIS 종목 마스터 fetch 어댑터 (ADR 0017 §2-3 — T-IM1 PR-11a).
 *
 * KIS-first 출처:
 *   KOSPI 파싱 기준: https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/kis_kospi_code_mst.py
 *   KOSDAQ 파싱 기준: https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/kis_kosdaq_code_mst.py
 *   docs/operations/kis-api-reference.md §6
 *
 * 다운로드 URL (KIS-first — 자체 추론 0건):
 *   KOSPI: https://new.real.download.dws.co.kr/common/master/kospi_code.mst.zip
 *   KOSDAQ: https://new.real.download.dws.co.kr/common/master/kosdaq_code.mst.zip
 *
 * 파일 형식: fixed-width text, EUC-KR 인코딩.
 * kis_kospi_code_mst.py 기반 KOSPI 레이아웃 (단위: byte, EUC-KR 기준 한글 2바이트):
 *   cols:  9 + 1 + 12 + 1 + 40 + 40 + ... 이후 가변 sector 정보 포함
 *   [단축코드:  0~ 8]  (9자)  — trim
 *   [표준코드: 10~21]  (12자) — ISIN
 *   [한글명:   23~62]  (40자) — trim (EUC-KR 2byte/한글)
 *   [영문명:   63~102] (40자) — trim
 *   이후 필드: 시장구분, 증권그룹, 거래정지, 관리종목, 상장일 등 (파이썬 스크립트 pandas 칼럼 순서 참조)
 *
 * 실패 처리 (ADR 0039 §2-4):
 *   5xx → exponential backoff 3회 (1s/2s/4s)
 *   네트워크 오류 → backoff 동일
 *   실패 지속 시 throw → 호출자(InstrumentSyncJob)가 catch + WARN 로그 + 시드 fallback 유지
 */
@Component
class KisInstrumentMasterAdapter(
    private val webClient: WebClient,
    @Value("\${magicjar.instrument.kospi-mst-url:https://new.real.download.dws.co.kr/common/master/kospi_code.mst.zip}")
    private val kospiMstUrl: String,
    @Value("\${magicjar.instrument.kosdaq-mst-url:https://new.real.download.dws.co.kr/common/master/kosdaq_code.mst.zip}")
    private val kosdaqMstUrl: String,
    @Value("\${magicjar.instrument.kis-disabled:false}")
    private val kisDisabled: Boolean,
) : KisInstrumentMasterPort {
    private val log = LoggerFactory.getLogger(KisInstrumentMasterAdapter::class.java)

    companion object {
        // 고정 폭 레이아웃 상수 (KIS-first: kis_kospi_code_mst.py 기준)
        private const val CODE_START = 0
        private const val CODE_LEN = 9
        private const val ISIN_START = 10
        private const val ISIN_LEN = 12
        private const val NAME_START = 23
        private const val NAME_LEN = 40
        private const val NAME_EN_START = 63
        private const val NAME_EN_LEN = 40

        private val EUC_KR = Charset.forName("EUC-KR")
        private val BACKOFF_DELAYS = listOf(1000L, 2000L, 4000L)
    }

    /**
     * KIS 종목 마스터 전체 fetch — KOSPI + KOSDAQ.
     * KONEX·ETF·ETN은 1차 비범위 (KOSPI/KOSDAQ 파일에 일부 포함됨).
     */
    override fun fetchAll(): List<Instrument> {
        if (kisDisabled) {
            log.info("[instrument-master] KIS disabled (magicjar.instrument.kis-disabled=true) — fetch skip")
            return emptyList()
        }

        log.info("[instrument-master] KIS 종목 마스터 fetch 시작")
        val instruments = mutableListOf<Instrument>()

        instruments.addAll(fetchMarket(kospiMstUrl, InstrumentMarket.KOSPI, "KOSPI"))
        instruments.addAll(fetchMarket(kosdaqMstUrl, InstrumentMarket.KOSDAQ, "KOSDAQ"))

        log.info("[instrument-master] fetch 완료 — total={}", instruments.size)
        return instruments
    }

    private fun fetchMarket(
        url: String,
        market: InstrumentMarket,
        marketName: String,
    ): List<Instrument> {
        var lastException: Exception? = null

        for ((idx, delay) in BACKOFF_DELAYS.withIndex()) {
            try {
                val zipBytes = downloadWithTimeout(url)
                val instruments = parseZipBytes(zipBytes, market)
                log.info("[instrument-master] {} 파싱 완료 — {} 건", marketName, instruments.size)
                return instruments
            } catch (e: WebClientResponseException) {
                lastException = e
                if (e.statusCode.value() in 400..499) {
                    // 4xx 클라이언트 오류 — backoff 불필요, 즉시 fail
                    log.error(
                        "[instrument-master] {} 4xx 오류 — 즉시 실패: status={} body={}",
                        marketName,
                        e.statusCode,
                        e.responseBodyAsString.take(200),
                    )
                    throw e
                }
                log.warn(
                    "[instrument-master] {} 5xx 오류 (시도 {}/{}) — {}ms 후 재시도: status={}",
                    marketName,
                    idx + 1,
                    BACKOFF_DELAYS.size,
                    delay,
                    e.statusCode,
                )
            } catch (e: Exception) {
                lastException = e
                log.warn(
                    "[instrument-master] {} 네트워크 오류 (시도 {}/{}) — {}ms 후 재시도: {}",
                    marketName,
                    idx + 1,
                    BACKOFF_DELAYS.size,
                    delay,
                    e.message,
                )
            }
            if (idx < BACKOFF_DELAYS.size - 1) {
                Thread.sleep(delay)
            }
        }

        log.error("[instrument-master] {} fetch 3회 모두 실패 — 시드 fallback 유지됨", marketName)
        throw lastException ?: RuntimeException("$marketName 종목 마스터 fetch 실패")
    }

    private fun downloadWithTimeout(url: String): ByteArray {
        log.debug("[instrument-master] 다운로드: {}", url)
        return webClient
            .get()
            .uri(url)
            .retrieve()
            .bodyToMono(ByteArray::class.java)
            .timeout(Duration.ofSeconds(30))
            .block()
            ?: error("종목 마스터 zip 다운로드 응답 없음: url=$url")
    }

    /**
     * zip 압축 해제 + fixed-width 파싱 (EUC-KR).
     *
     * KIS-first 출처:
     *   kis_kospi_code_mst.py: `df.columns` 순서 + byte 오프셋 기반 파싱 패턴 참조
     *   각 레코드: code(9) + sp + isin(12) + sp + name(40, EUC-KR) + nameEn(40) + ...
     *
     * 실제 파일은 EUC-KR 인코딩. 한글 2바이트 처리 주의.
     * 종목코드 6자리 추출 (단축코드 9자 중 앞 6자리가 종목코드 — python 스크립트 기준).
     */
    private fun parseZipBytes(
        zipBytes: ByteArray,
        market: InstrumentMarket,
    ): List<Instrument> {
        val instruments = mutableListOf<Instrument>()

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val content = zis.readBytes()
                    val reader = BufferedReader(InputStreamReader(ByteArrayInputStream(content), EUC_KR))
                    reader.forEachLine { line ->
                        val instrument = parseLine(line, market)
                        if (instrument != null) {
                            instruments.add(instrument)
                        }
                    }
                }
                entry = zis.nextEntry
            }
        }

        return instruments
    }

    /**
     * 고정 폭 레코드 한 줄 파싱.
     *
     * KIS-first 출처: kis_kospi_code_mst.py 필드 인덱스 (Python 코드 기준 byte offset).
     * 종목코드: 앞 9자 중 공백 trim → 6자리 유효 코드 추출.
     * 파싱 실패 행은 WARN 로그 후 skip (부분 실패 graceful — ADR 0039 §2-4).
     */
    private fun parseLine(
        line: String,
        market: InstrumentMarket,
    ): Instrument? {
        // PR-20 hotfix — KIS open-trading-api는 byte 오프셋 기반(EUC-KR 한글 2byte).
        // Java/Kotlin String.substring은 char 단위라 한글 종목명("SK하이닉스" 등)이 깨짐.
        // 라인을 EUC-KR byte 배열로 복원해 byte-level slicing 후 String으로 decode.
        val lineBytes = line.toByteArray(EUC_KR)
        if (line.isBlank() || lineBytes.size < NAME_EN_START + NAME_EN_LEN) return null

        return try {
            val rawCode = String(lineBytes, CODE_START, CODE_LEN, EUC_KR).trim()
            // 종목코드는 6자리 숫자 (단축코드 9자에서 우측 패딩 공백 제거 후 앞 6자리)
            val code = rawCode.take(6).padStart(6, '0').trim()
            if (code.length != 6 || !code.all { it.isDigit() }) return null

            val isin = String(lineBytes, ISIN_START, ISIN_LEN, EUC_KR).trim().ifBlank { null }
            val name = String(lineBytes, NAME_START, NAME_LEN, EUC_KR).trim()
            val nameEn = String(lineBytes, NAME_EN_START, NAME_EN_LEN, EUC_KR).trim().ifBlank { null }

            if (name.isBlank()) return null

            // 종목 유형 판별 (이름 기반 — KOSPI/KOSDAQ 파일에 ETF·우선주 포함)
            val type = detectType(name, code)

            Instrument(
                code = code,
                isin = isin,
                name = name,
                nameEn = nameEn,
                market = market,
                type = type,
                sector = null, // 1차: sector 필드 파싱 생략 (고정 폭 이후 가변 필드 — post-MVP에서 정밀화)
                listedAt = null, // 1차: listed_at 파싱 생략 (이후 필드 offset 복잡 — post-MVP)
                delistedAt = null,
                active = true,
            )
        } catch (e: Exception) {
            log.warn("[instrument-master] 줄 파싱 실패 skip: market={} line='{}'... err={}", market, line.take(30), e.message)
            null
        }
    }

    /**
     * 종목명 기반 타입 판별 (1차 heuristic — KIS 마스터 파일 type 필드 파싱 생략).
     * ETF: 이름에 "ETF" 또는 "KODEX"·"TIGER"·"KINDEX"·"KOSEF"·"ARIRANG"·"ACE"·"HANARO" 등 포함.
     * ETN: 이름에 "ETN" 포함.
     * 우선주: 코드 끝자리 5 (1자리)·코드 5번째 자리 비표준 (KRX 우선주 코드 체계).
     * 나머지: STOCK.
     */
    private fun detectType(
        name: String,
        code: String,
    ): InstrumentType {
        val upper = name.uppercase()
        return when {
            "ETN" in upper -> InstrumentType.ETN
            "ETF" in upper ||
                upper.startsWith("KODEX") ||
                upper.startsWith("TIGER") ||
                upper.startsWith("KINDEX") ||
                upper.startsWith("KOSEF") ||
                upper.startsWith("ARIRANG") ||
                upper.startsWith("ACE ") ||
                upper.startsWith("HANARO") ||
                upper.startsWith("KBSTAR") ||
                upper.startsWith("FOCUS") ||
                upper.startsWith("SOL ") ||
                upper.startsWith("PLUS ") -> InstrumentType.ETF
            // 우선주: 코드 5번째 자리가 5 (KRX 우선주 코드 체계 — 예: 005935)
            code.getOrNull(4) == '5' -> InstrumentType.PREFERRED
            else -> InstrumentType.STOCK
        }
    }
}

/**
 * 종목 마스터 동기화 진행일 (LocalDate 임시 생성 헬퍼).
 * batch 모듈에서 사용.
 */
fun todayKst(): LocalDate {
    val zdt = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
    return LocalDate(zdt.year, zdt.monthValue, zdt.dayOfMonth)
}
