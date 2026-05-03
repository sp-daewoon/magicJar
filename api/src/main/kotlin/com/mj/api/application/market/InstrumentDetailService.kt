package com.mj.api.application.market

import com.mj.api.infrastructure.dart.DartFinancialAdapter
import com.mj.api.infrastructure.kis.KisInstrumentInfoAdapter
import com.mj.domain.market.instrument.InstrumentDetail
import com.mj.domain.market.instrument.port.InstrumentDetailRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 종목 상세 메타 조회 서비스 (T20-T4, inquire-price 통합 버전).
 *
 * 호출 흐름:
 * 1. Redis 캐시 hit → 즉시 반환
 * 2. 캐시 miss →
 *    a. KIS search-stock-info (CTPF1002R): 종목명·상장주식수·시장구분·업종코드
 *    b. KIS inquire-price (FHKST01010100): PER·PBR·EPS·BPS·시가총액·업종한글명·외국인보유율
 *    c. DART fnlttSinglAcntAll: 매출액·영업이익·당기순이익
 * 3. 세 결과 병합 → Redis 저장 (TTL 6h)
 *
 * KIS/DART 키 미설정 시:
 * - KIS: code·name만 채워진 InstrumentDetail 반환 (기본정보 null)
 * - DART: 재무정보 null (나머지는 KIS 결과로 채움)
 * - inquire-price: PER/PBR/EPS/BPS/시총 null (graceful degradation)
 *
 * @see InstrumentDetailRedisAdapter
 * @see KisInstrumentInfoAdapter
 * @see DartFinancialAdapter
 */
@Service
class InstrumentDetailService(
    private val detailRepository: InstrumentDetailRepository,
    private val kisAdapter: KisInstrumentInfoAdapter,
    private val dartAdapter: DartFinancialAdapter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 종목 상세 조회 결과 + 데이터 소스 경고 목록.
     *
     * warnings: KIS/DART 키 미설정 또는 외부 API 실패 시 사유 코드 포함.
     *   - KIS_KEY_NOT_SET           : KIS appKey/appSecret 미설정
     *   - KIS_API_FAILED            : KIS API 호출은 했으나 실패
     *   - KIS_PRICE_METRICS_FAILED  : inquire-price 호출 실패 (기본정보는 OK)
     *   - DART_KEY_NOT_SET          : DART API key 미설정
     *   - DART_CORP_CODE_NOT_FOUND  : DART corp_code 매핑 없음
     */
    data class DetailResult(
        val detail: InstrumentDetail,
        val warnings: List<String>,
    )

    /**
     * 종목 상세 조회.
     *
     * @param code KRX 종목 코드 6자리 (대문자)
     * @param name 종목명 (InstrumentService에서 이미 조회한 값 재사용)
     * @return DetailResult (detail + warnings 목록)
     */
    fun getDetail(
        code: String,
        name: String,
    ): DetailResult {
        // 1. 캐시 hit (캐시에는 warnings 미저장 — 캐시 hit 시 warnings 빈 목록)
        detailRepository.find(code)?.let {
            log.info("[detail-svc] cache hit code={}", code)
            return DetailResult(detail = it, warnings = emptyList())
        }

        log.info("[detail-svc] cache miss — KIS+DART 호출 시작 code={}", code)

        val warnings = mutableListOf<String>()

        // 2a. KIS 기본정보 조회 (search-stock-info / CTPF1002R)
        val kisDetail = kisAdapter.fetchInstrumentInfo(code)
        if (kisDetail == null) {
            log.info("[detail-svc] KIS search-stock-info 응답 없음 code={} — 키 미설정 또는 API 실패.", code)
            if (kisAdapter.isKeyConfigured()) {
                warnings.add("KIS_API_FAILED")
            } else {
                warnings.add("KIS_KEY_NOT_SET")
            }
        } else {
            log.info("[detail-svc] KIS search-stock-info OK code={} name={}", code, kisDetail.name)
        }

        // 2b. KIS 투자지표 조회 (inquire-price / FHKST01010100)
        //     - PER·PBR·EPS·BPS + 정확한 시가총액(억원→원 환산) + 업종 한글명 + 외국인보유율
        val priceMetrics =
            if (kisAdapter.isKeyConfigured()) {
                kisAdapter.fetchPriceMetrics(code).also { m ->
                    if (m == null) {
                        log.warn("[detail-svc] KIS inquire-price 실패 code={} — PER/PBR/EPS/BPS/시총 null", code)
                        warnings.add("KIS_PRICE_METRICS_FAILED")
                    } else {
                        log.info(
                            "[detail-svc] KIS inquire-price OK code={} per={} pbr={} marketCap={} industry={}",
                            code,
                            m.per,
                            m.pbr,
                            m.marketCap,
                            m.industry,
                        )
                    }
                }
            } else {
                null
            }

        // 3. DART 재무정보 조회
        val dartFinancials = dartAdapter.fetchFinancials(code)
        if (dartFinancials == null) {
            log.info("[detail-svc] DART 응답 없음 code={} — 키 미설정 또는 corp_code 미발견.", code)
            if (dartAdapter.isKeyConfigured()) {
                warnings.add("DART_CORP_CODE_NOT_FOUND")
            } else {
                warnings.add("DART_KEY_NOT_SET")
            }
        } else {
            log.info("[detail-svc] DART 응답 OK code={} revenue={} opIncome={}", code, dartFinancials.revenue, dartFinancials.operatingIncome)
        }

        // 4. 병합 (search-stock-info + inquire-price + DART 3-way merge)
        val merged =
            if (kisDetail != null) {
                kisDetail.copy(
                    name = kisDetail.name.ifBlank { name },
                    // inquire-price 우선: 시총·PER·PBR·EPS·BPS·업종한글명·외국인보유율
                    marketCap = priceMetrics?.marketCap ?: kisDetail.marketCap,
                    per = priceMetrics?.per,
                    pbr = priceMetrics?.pbr,
                    eps = priceMetrics?.eps,
                    bps = priceMetrics?.bps,
                    industry = priceMetrics?.industry ?: kisDetail.industry,
                    foreignRatio = priceMetrics?.foreignRatio ?: kisDetail.foreignRatio,
                    // DART 재무정보
                    revenue = dartFinancials?.revenue,
                    operatingIncome = dartFinancials?.operatingIncome,
                    netIncome = dartFinancials?.netIncome,
                )
            } else {
                // KIS 키 미설정: 최소 정보만 채운 객체
                InstrumentDetail(
                    code = code,
                    name = name,
                    per = priceMetrics?.per,
                    pbr = priceMetrics?.pbr,
                    eps = priceMetrics?.eps,
                    bps = priceMetrics?.bps,
                    marketCap = priceMetrics?.marketCap,
                    industry = priceMetrics?.industry,
                    foreignRatio = priceMetrics?.foreignRatio,
                    revenue = dartFinancials?.revenue,
                    operatingIncome = dartFinancials?.operatingIncome,
                    netIncome = dartFinancials?.netIncome,
                )
            }

        // 5. 캐시 저장 (KIS/DART 미설정이어도 저장 — 다음 요청 시 불필요한 외부 호출 방지)
        detailRepository.save(merged)
        log.info(
            "[detail-svc] 저장 완료 code={} kisNull={} priceMetricsNull={} dartNull={} warnings={}",
            code,
            kisDetail == null,
            priceMetrics == null,
            dartFinancials == null,
            warnings,
        )

        return DetailResult(detail = merged, warnings = warnings)
    }

    /**
     * 캐시 강제 갱신 — KIS/DART 키 변경 후 호출 시 사용.
     */
    fun refreshDetail(code: String): Unit = detailRepository.evict(code)
}
