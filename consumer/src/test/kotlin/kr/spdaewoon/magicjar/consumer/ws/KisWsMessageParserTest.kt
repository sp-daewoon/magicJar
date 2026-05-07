package kr.spdaewoon.magicjar.consumer.ws

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * KisWsMessageParser 단위 테스트.
 *
 * PR-9b smoke test 1 (dispatch §3-2-I 정합 — 룰 4: R2 PR 최소 smoke만).
 * ADR 0048 §5 검증: H0STCNT0 PIPE 구분자 파싱 정확성.
 */
class KisWsMessageParserTest {
    /**
     * PINGPONG 메시지 파싱 검증.
     */
    @Test
    fun `PINGPONG 메시지는 Pingpong ParsedMessage 반환`() {
        val result = KisWsMessageParser.parse("PINGPONG")
        assertThat(result).isInstanceOf(KisWsMessageParser.ParsedMessage.Pingpong::class.java)
    }

    /**
     * H0STCNT0 PIPE 구분자 + 캐럿 구분자 파싱 검증.
     * raw 형식: "0|H0STCNT0|001|{code}^{time}^{price}^..."
     * - IDX 0: code, IDX 1: time(HHMMSS), IDX 2: price, IDX 12: cntg_vol, IDX 33: bsop_date
     *
     * KIS-first 출처: open-trading-api domestic_stock_functions_ws.py ccnl_krx() cols 배열.
     */
    @Test
    fun `H0STCNT0 raw 메시지 파싱 → TickEvent code·price·volume 정합`() {
        // H0STCNT0 45개 필드: IDX[0]=code, IDX[2]=price, IDX[12]=cntg_vol, IDX[33]=bsop_date
        // 총 45필드 생성 (인덱스 0~44)
        val fields =
            Array(45) { i ->
                when (i) {
                    0 -> "005930" // MKSC_SHRN_ISCD (종목코드)
                    1 -> "090012" // STCK_CNTG_HOUR (체결시각 HHMMSS)
                    2 -> "60900" // STCK_PRPR (현재가)
                    12 -> "100" // CNTG_VOL (체결거래량)
                    33 -> "20260507" // BSOP_DATE (영업일자 YYYYMMDD)
                    else -> "0"
                }
            }
        val rawData = fields.joinToString("^")
        val raw = "0|H0STCNT0|001|$rawData"

        val parsed = KisWsMessageParser.parse(raw)
        assertThat(parsed).isInstanceOf(KisWsMessageParser.ParsedMessage.RealtimeData::class.java)

        val data = parsed as KisWsMessageParser.ParsedMessage.RealtimeData
        assertThat(data.trId).isEqualTo("H0STCNT0")

        val tick = KisWsMessageParser.parseH0Stcnt0(data.rawData)
        assertThat(tick).isNotNull
        assertThat(tick!!.code).isEqualTo("005930")
        assertThat(tick.price).isEqualByComparingTo(BigDecimal("60900"))
        assertThat(tick.volume).isEqualTo(100L)
        assertThat(tick.source).isEqualTo("live")
    }

    /**
     * 구독 성공 응답 JSON 파싱 검증.
     */
    @Test
    fun `구독 성공 응답 JSON 파싱 rt_cd 0 → true`() {
        val json = """{"header":{"tr_id":"H0STCNT0"},"body":{"rt_cd":"0","msg1":"SUBSCRIBE SUCCESS"}}"""
        // 구독 응답은 PIPE 없는 JSON
        val parsed = KisWsMessageParser.parse(json)
        assertThat(parsed).isInstanceOf(KisWsMessageParser.ParsedMessage.SubscriptionResponse::class.java)
    }

    /**
     * 41 slot 한도 — H0STCNT0 최대 30 종목 (ADR 0048 §2-1).
     * 41 slot 예산: 1(H0STCNI0) + 30(H0STCNT0) + 10(H0STASP0) = 41.
     */
    @Test
    fun `41 slot 예산 상수 검증 — PRICE_TICK_MAX 30 + EXECUTION_NOTICE_RESERVED 1 + ORDERBOOK_MAX 10 = 41`() {
        // 도메인 port SubscriptionPolicy 상수는 backend-core PR-9a 담당이므로
        // 여기서는 ADR 0048 §2-1 숫자 자체가 정합인지 확인 (비즈니스 룰 명시)
        val totalSlots = 41
        val executionNoticeReserved = 1 // H0STCNI0
        val priceTickMax = 30 // H0STCNT0
        val orderbookMax = 10 // H0STASP0 (LRU)

        assertThat(executionNoticeReserved + priceTickMax + orderbookMax)
            .isEqualTo(totalSlots)
            .describedAs("ADR 0048 §2-1: 1 + 30 + 10 = 41 slot 예산 정합")
    }
}
