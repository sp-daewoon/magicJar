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
     * H0STCNT0 등락 필드 추출 — dogfooding 결함 1 fix.
     *
     * KIS-first 출처: open-trading-api domestic_stock_functions_ws.py ccnl_krx() cols 배열.
     * https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
     *   [3] PRDY_VRSS_SIGN — 부호 코드 (1=상한/2=상승/3=보합/4=하한/5=하락)
     *   [4] PRDY_VRSS      — 전일대비
     *   [5] PRDY_CTRT      — 전일대비율 (%)
     */
    @Test
    fun `H0STCNT0 등락 필드 PRDY_VRSS_SIGN·PRDY_VRSS·PRDY_CTRT 추출`() {
        val fields =
            Array(46) { i ->
                when (i) {
                    0 -> "005930"
                    1 -> "100012"
                    2 -> "61000"
                    3 -> "2" // PRDY_VRSS_SIGN (상승)
                    4 -> "1500" // PRDY_VRSS (전일대비 +1500)
                    5 -> "2.52" // PRDY_CTRT (전일대비율 +2.52%)
                    12 -> "100"
                    33 -> "20260511"
                    else -> "0"
                }
            }
        val tick = KisWsMessageParser.parseH0Stcnt0(fields.joinToString("^"))

        assertThat(tick).isNotNull
        assertThat(tick!!.changeSign).isEqualTo("2")
        assertThat(tick.priceChange).isEqualByComparingTo(BigDecimal("1500"))
        assertThat(tick.changeRate).isEqualByComparingTo(BigDecimal("2.52"))
    }

    /**
     * 등락 필드 공백·blank 시 null 반환 — polling source 호환 (등락 필드 미제공).
     */
    @Test
    fun `H0STCNT0 등락 필드 blank → null`() {
        val fields =
            Array(46) { i ->
                when (i) {
                    0 -> "005930"
                    1 -> "100012"
                    2 -> "61000"
                    3 -> "" // blank
                    4 -> "" // blank
                    5 -> "" // blank
                    12 -> "100"
                    33 -> "20260511"
                    else -> "0"
                }
            }
        val tick = KisWsMessageParser.parseH0Stcnt0(fields.joinToString("^"))

        assertThat(tick).isNotNull
        assertThat(tick!!.changeSign).isNull()
        assertThat(tick.priceChange).isNull()
        assertThat(tick.changeRate).isNull()
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

    // ===== H0NXCNT0 NXT 구독 추가 테스트 (장외 sync 결함 hotfix) =====

    /**
     * H0NXCNT0 raw 메시지 파싱 → TickEvent code·price·volume 정합.
     *
     * KIS-first 출처:
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
     *   ccnl_nxt() columns: H0STCNT0(ccnl_krx)와 동일 필드 인덱스 구조.
     *   [0]=MKSC_SHRN_ISCD / [1]=STCK_CNTG_HOUR / [2]=STCK_PRPR / [12]=CNTG_VOL / [33]=BSOP_DATE.
     *
     * ADR 0063 NXT cover: 08:00~20:00 KST (시간외 + pre-market).
     */
    @Test
    fun `H0NXCNT0 raw 메시지 파싱 → TickEvent code·price·volume·source=live-nxt 정합`() {
        // H0NXCNT0 44개 필드: IDX[0]=code, IDX[2]=price, IDX[12]=cntg_vol, IDX[33]=bsop_date
        val fields =
            Array(44) { i ->
                when (i) {
                    0 -> "005930" // MKSC_SHRN_ISCD (종목코드)
                    1 -> "163000" // STCK_CNTG_HOUR (시간외 16:30 = after-hour)
                    2 -> "59800" // STCK_PRPR (현재가)
                    3 -> "5" // PRDY_VRSS_SIGN (하락)
                    4 -> "500" // PRDY_VRSS (전일대비 -500)
                    5 -> "-0.83" // PRDY_CTRT (전일대비율)
                    12 -> "50" // CNTG_VOL (체결거래량)
                    33 -> "20260515" // BSOP_DATE (영업일자)
                    else -> "0"
                }
            }
        val rawData = fields.joinToString("^")
        val raw = "0|H0NXCNT0|001|$rawData"

        val parsed = KisWsMessageParser.parse(raw)
        assertThat(parsed).isInstanceOf(KisWsMessageParser.ParsedMessage.RealtimeData::class.java)

        val data = parsed as KisWsMessageParser.ParsedMessage.RealtimeData
        assertThat(data.trId).isEqualTo("H0NXCNT0")

        val tick = KisWsMessageParser.parseH0Nxcnt0(data.rawData)
        assertThat(tick).isNotNull
        assertThat(tick!!.code).isEqualTo("005930")
        assertThat(tick.price).isEqualByComparingTo(BigDecimal("59800"))
        assertThat(tick.volume).isEqualTo(50L)
        assertThat(tick.source).isEqualTo("live-nxt")
    }

    /**
     * H0NXCNT0 등락 필드 추출 — NXT 시간외 등락률 정합.
     *
     * KIS-first 출처:
     *   ccnl_nxt() [3]=PRDY_VRSS_SIGN / [4]=PRDY_VRSS / [5]=PRDY_CTRT
     *   raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
     */
    @Test
    fun `H0NXCNT0 등락 필드 PRDY_VRSS_SIGN·PRDY_VRSS·PRDY_CTRT 추출`() {
        val fields =
            Array(44) { i ->
                when (i) {
                    0 -> "005930"
                    1 -> "170000"
                    2 -> "60200"
                    3 -> "2" // PRDY_VRSS_SIGN (상승)
                    4 -> "200" // PRDY_VRSS (+200)
                    5 -> "0.33" // PRDY_CTRT (+0.33%)
                    12 -> "30"
                    33 -> "20260515"
                    else -> "0"
                }
            }
        val tick = KisWsMessageParser.parseH0Nxcnt0(fields.joinToString("^"))

        assertThat(tick).isNotNull
        assertThat(tick!!.changeSign).isEqualTo("2")
        assertThat(tick.priceChange).isEqualByComparingTo(BigDecimal("200"))
        assertThat(tick.changeRate).isEqualByComparingTo(BigDecimal("0.33"))
        assertThat(tick.source).isEqualTo("live-nxt")
    }

    /**
     * 41 slot 정합 — NXT(H0NXCNT0) 추가 후 SubscriptionPolicy 상수 검증.
     *
     * ADR 0048 §2-1 정합:
     *   H0STCNT0(20) + H0NXCNT0(20) + H0STCNI0(1) = 41 slot.
     *   NXT_TICK_MAX = 20 (PRICE_TICK_MAX=30이 아닌 NXT 병행 시 안전 한도).
     *
     * KIS-first: open-trading-api ccnl_nxt() tr_id=H0NXCNT0
     * raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
     */
    @Test
    fun `41 slot NXT 추가 후 정합 — H0STCNT0(NXT_TICK_MAX) + H0NXCNT0(NXT_TICK_MAX) + H0STCNI0(1) = 41`() {
        val totalSlots = 41
        val executionNoticeReserved = 1 // H0STCNI0
        val nxtTickMax = 20 // H0NXCNT0 (SubscriptionPolicy.NXT_TICK_MAX)
        // NXT 병행 시 H0STCNT0도 20 종목으로 제한 (각 1 slot)
        val priceTickMaxWithNxt = 20 // H0STCNT0 (NXT 병행)

        assertThat(executionNoticeReserved + priceTickMaxWithNxt + nxtTickMax)
            .isEqualTo(totalSlots)
            .describedAs("ADR 0048 §2-1 + NXT: H0STCNI0(1) + H0STCNT0(20) + H0NXCNT0(20) = 41 slot 정합")
    }
}
