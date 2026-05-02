package com.mj.domain.market.instrument

import com.mj.domain.common.Market
import java.math.BigDecimal
import java.time.LocalDate

/**
 * KIS 종목 마스터 원시 데이터 (파싱 직후, 정규화 전).
 *
 * RealKisInstrumentMasterAdapter가 MST 파일에서 읽은 값을 담는다.
 * InstrumentMasterSyncJob이 이를 Instrument 도메인 모델로 변환해 upsert한다.
 */
data class InstrumentRaw(
    /** 단축코드 (6자리) */
    val code: String,
    /** 표준코드 / ISIN (12자리) */
    val isin: String?,
    /** 한글 종목명 */
    val name: String,
    /** 상장 시장 */
    val market: Market,
    /** 상품 유형 문자열 (원시값, 정규화는 Job에서) */
    val typeName: String?,
    /** 업종명 */
    val sector: String?,
    /** 상장일 (yyyyMMdd) */
    val listedAtStr: String?,
    /** 액면가 */
    val parValue: BigDecimal?,
    /** 거래 수량 단위 */
    val tradingUnit: Int,
    /** 거래정지 여부 ('Y' = 거래정지) */
    val tradingHalted: Boolean,
) {
    /** 상장일을 LocalDate로 변환 (변환 불가 시 null) */
    fun listedAt(): LocalDate? =
        listedAtStr
            ?.takeIf { it.length == 8 && it.all { c -> c.isDigit() } }
            ?.let {
                runCatching {
                    LocalDate.of(it.substring(0, 4).toInt(), it.substring(4, 6).toInt(), it.substring(6, 8).toInt())
                }.getOrNull()
            }
}
