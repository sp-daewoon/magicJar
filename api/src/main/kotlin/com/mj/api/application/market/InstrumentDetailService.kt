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
     * 종목 상세 조회.
     *
     * @param code KRX 종목 코드 6자리 (대문자)
     * @param name 종목명 (InstrumentService에서 이미 조회한 값 재사용)
     * @return InstrumentDetail (KIS/DART 미설정 시 code·name만 채워진 객체)
     */
    fun getDetail(
        code: String,
        name: String,
    ): InstrumentDetail {
        // 1. 캐시 hit
        detailRepository.find(code)?.let {
            log.debug("[detail-svc] cache hit code={}", code)
            return it
        }

        log.debug("[detail-svc] cache miss — KIS+DART 호출 code={}", code)

        // 2. KIS 기본정보 조회
        val kisDetail = kisAdapter.fetchInstrumentInfo(code)

        // 3. DART 재무정보 조회
        val dartFinancials = dartAdapter.fetchFinancials(code)

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

        return merged
    }

    /**
     * 캐시 강제 갱신 — KIS/DART 키 변경 후 호출 시 사용.
     */
    fun refreshDetail(code: String): Unit = detailRepository.evict(code)
}
