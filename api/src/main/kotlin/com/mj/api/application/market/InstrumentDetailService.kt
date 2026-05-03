package com.mj.api.application.market

import com.mj.api.infrastructure.dart.DartFinancialAdapter
import com.mj.api.infrastructure.kis.KisInstrumentInfoAdapter
import com.mj.domain.market.instrument.InstrumentDetail
import com.mj.domain.market.instrument.port.InstrumentDetailRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 종목 상세 메타 조회 서비스 (T20-T4).
 *
 * 흐름:
 * 1. Redis 캐시 hit → 즉시 반환
 * 2. 캐시 miss → KIS inquire-stock-info 호출
 * 3. DART 재무정보 병합 (revenue, operatingIncome, netIncome)
 * 4. 결과 Redis 저장 (TTL 6h)
 *
 * KIS/DART 키 미설정 시:
 * - KIS: code·name만 채워진 InstrumentDetail 반환 (기본정보 null)
 * - DART: 재무정보 null (나머지는 KIS 결과로 채움)
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
     *   - KIS_KEY_NOT_SET : KIS appKey/appSecret 미설정
     *   - KIS_API_FAILED  : KIS API 호출은 했으나 실패
     *   - DART_KEY_NOT_SET: DART API key 미설정
     *   - DART_CORP_CODE_NOT_FOUND: DART corp_code 매핑 없음
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

        // 2. KIS 기본정보 조회
        val kisDetail = kisAdapter.fetchInstrumentInfo(code)
        if (kisDetail == null) {
            log.info("[detail-svc] KIS 응답 없음 code={} — 키 미설정 또는 API 실패. code·name만 반환.", code)
            // 키 설정 여부에 따라 경고 코드 구분
            if (kisAdapter.isKeyConfigured()) {
                warnings.add("KIS_API_FAILED")
            } else {
                warnings.add("KIS_KEY_NOT_SET")
            }
        } else {
            log.info("[detail-svc] KIS 응답 OK code={} name={} marketCap={}", code, kisDetail.name, kisDetail.marketCap)
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

        // 4. 병합
        val merged =
            if (kisDetail != null) {
                kisDetail.copy(
                    name = kisDetail.name.ifBlank { name },
                    revenue = dartFinancials?.revenue,
                    operatingIncome = dartFinancials?.operatingIncome,
                    netIncome = dartFinancials?.netIncome,
                )
            } else {
                // KIS 키 미설정: 최소 정보만 채운 객체
                InstrumentDetail(
                    code = code,
                    name = name,
                    revenue = dartFinancials?.revenue,
                    operatingIncome = dartFinancials?.operatingIncome,
                    netIncome = dartFinancials?.netIncome,
                )
            }

        // 5. 캐시 저장 (KIS/DART 미설정이어도 저장 — 다음 요청 시 불필요한 외부 호출 방지)
        detailRepository.save(merged)
        log.info("[detail-svc] 저장 완료 code={} kisNull={} dartNull={} warnings={}", code, kisDetail == null, dartFinancials == null, warnings)

        return DetailResult(detail = merged, warnings = warnings)
    }

    /**
     * 캐시 강제 갱신 — KIS/DART 키 변경 후 호출 시 사용.
     */
    fun refreshDetail(code: String): Unit = detailRepository.evict(code)
}
