package kr.spdaewoon.magicjar.consumer.ws

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kr.spdaewoon.magicjar.domain.market.tick.TickEvent
import org.slf4j.LoggerFactory

/**
 * KIS WebSocket 수신 메시지 파서 — PIPE(|)·캐럿(^) 구분자.
 *
 * KIS-first 출처: open-trading-api examples_user/kis_auth.py
 * 수신 raw 메시지 형식:
 *   {타입}|{TR_ID}|{N}|{데이터}
 *   - 타입: "0" = 실시간 데이터, "1" = 구독 응답, "PINGPONG" = heartbeat
 *   - TR_ID: H0UNCNT0(통합 체결가), H0UNASP0(통합 호가), H0STOUP0(시간외 단일가), H0STCNI0(체결통보)
 *   - N: 데이터 건수
 *   - 데이터: ^(캐럿) 구분 필드열
 *
 * 공통 체결가 필드 인덱스 (H0UNCNT0·H0STOUP0 — parseH0Stcnt0가 공유 base parser):
 * KIS-first raw 출처:
 *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/ccnl_total/ccnl_total.py
 *   ccnl_total() tr_id="H0UNCNT0" — 국내주식 실시간체결가 (통합). 필드 인덱스는 ccnl_krx와 동일 위치.
 *   [0]  MKSC_SHRN_ISCD      — 종목코드
 *   [1]  STCK_CNTG_HOUR      — 체결시각 (HHMMSS)
 *   [2]  STCK_PRPR           — 현재가
 *   [3]  PRDY_VRSS_SIGN      — 전일대비부호
 *   [4]  PRDY_VRSS           — 전일대비
 *   [5]  PRDY_CTRT           — 전일대비율 (%)
 *  [12]  CNTG_VOL            — 체결거래량
 *  [14]  ACML_TR_PBMN        — 누적거래대금(원)
 *  [18]  CTTR                — 체결강도
 *  [33]  BSOP_DATE           — 영업일자 (YYYYMMDD)
 *
 * H0STOUP0 필드 인덱스 (open-trading-api examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py columns 배열):
 * KIS-first raw 출처:
 *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
 *   overtime_ccnl_krx() tr_id="H0STOUP0" — 국내주식 시간외 실시간체결가 (KRX) [실시간-042]
 *   활성 시간: 16:00~18:00 KST (시간외 단일가 세션)
 *   columns 배열 43개 필드 (인덱스 0~42):
 *   [0]  mksc_shrn_iscd          — 종목코드
 *   [1]  stck_cntg_hour          — 체결시각 (HHMMSS)
 *   [2]  stck_prpr               — 현재가(체결가)
 *   [3]  prdy_vrss_sign          — 전일대비부호 (1=상한/2=상승/3=보합/4=하한/5=하락)
 *   [4]  prdy_vrss               — 전일대비
 *   [5]  prdy_ctrt               — 전일대비율 (%)
 *  [12]  cntg_vol                — 체결거래량
 *  [13]  acml_vol                — 누적거래량
 *  [33]  bsop_date               — 영업일자 (YYYYMMDD)
 */
object KisWsMessageParser {
    private val log = LoggerFactory.getLogger(KisWsMessageParser::class.java)
    private val objectMapper = ObjectMapper()

    data class SubscriptionResponseFields(
        val trId: String?,
        val trKey: String?,
        val rtCd: String,
        val msgCd: String?,
        val msg1: String?,
        val iv: String?,
        val key: String?,
    )

    private val kst = TimeZone.of("Asia/Seoul")

    // H0STCNT0 필드 인덱스 (KIS-first raw 출처)
    // https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
    // ccnl_krx() columns 배열 기반
    private const val IDX_CODE = 0
    private const val IDX_TIME = 1
    private const val IDX_PRICE = 2
    private const val IDX_PRDY_VRSS_SIGN = 3 // 전일대비부호
    private const val IDX_PRDY_VRSS = 4 // 전일대비
    private const val IDX_PRDY_CTRT = 5 // 전일대비율(%)
    private const val IDX_CNTG_VOL = 12

    // R1-A 체결강도 흡수 (ADR 0117 §3-R1 — KIS 계산 완료값 흡수만, 자체 산식 0)
    // KIS-first raw 출처(2026-06-09 fetch 재검증):
    //   https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
    //   ccnl_krx() columns 배열: [14]=ACML_TR_PBMN, [18]=CTTR, [19]=SELN_CNTG_SMTN, [20]=SHNU_CNTG_SMTN
    private const val IDX_ACML_TR_PBMN = 14 // 누적거래대금(원)
    private const val IDX_CTTR = 18 // 체결강도 (KIS 계산 완료값)

    private const val IDX_BSOP_DATE = 33

    /**
     * NEW_MKOP_CLS_CODE (신 장운영 구분 코드) — H0UNCNT0 통합 스트림 세션 구분 (ADR 0124 수용 기준 #2).
     *
     * KIS-first raw 출처 (헌법 §3 — 자체 추론 0):
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/ccnl_total/ccnl_total.py
     *   ccnl_total() columns 배열 — [33]=BSOP_DATE 직후 [34]=NEW_MKOP_CLS_CODE (2026-07-03 raw 재검증).
     *
     * TODO(ADR 0124 수용기준 #2): NEW_MKOP_CLS_CODE 값 테이블(예상체결 판별 코드) KIS-first 원문 미확보 —
     *   인덱스는 확정, 값 의미는 [TickEvent.session] 태그 + Kafka payload 전파로 라이브 관측 후 확정한다.
     *   확정 시 `magicjar.candle.expected-conclusion-session-codes` 설정에 코드를 채우면 캔들 제외가 활성된다.
     */
    private const val IDX_NEW_MKOP_CLS_CODE = 34

    /**
     * raw 메시지 타입 판별.
     * - PINGPONG: 서버 heartbeat응답
     * - DATA: 실시간 데이터 (타입 "0")
     * - RESPONSE: 구독 응답 (타입 "1")
     * - UNKNOWN: 알 수 없음
     */
    sealed class ParsedMessage {
        /**
         * KIS WS heartbeat.
         * @param rawMessage echo 시 그대로 전송할 원본 메시지
         *   KIS-first: kis_auth.py "await ws.pong(raw)" — raw 그대로 echo 의무
         *   raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
         */
        data class Pingpong(
            val rawMessage: String,
        ) : ParsedMessage()

        data class RealtimeData(
            val trId: String,
            val rawData: String,
            // hotfix 2026-05-20 — KIS 첫 필드 `0`=평문 / `1`=AES-256-CBC 암호화 분기.
            // H0STCNI0/9 (체결통보) 등은 항상 encrypted=true → KisWebSocketBridge.handleMessage에서 AES 복호화 후 listener forward.
            val encrypted: Boolean = false,
        ) : ParsedMessage()

        data class SubscriptionResponse(
            val trId: String,
            val rawJson: String,
        ) : ParsedMessage()

        data class Unknown(
            val raw: String,
        ) : ParsedMessage()
    }

    fun parse(raw: String): ParsedMessage {
        // KIS-first 출처: kis_auth.py __subscriber()
        // PINGPONG 분기: raw 텍스트가 "PINGPONG" 이거나,
        //   JSON header.tr_id == "PINGPONG" 인 경우 모두 heartbeat로 처리.
        // KIS 공식 예제: rdic = json.loads(data); tr_id = rdic["header"]["tr_id"]; isPingPong = True if tr_id == "PINGPONG" else False
        // raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
        if (raw.trim() == "PINGPONG") return ParsedMessage.Pingpong(rawMessage = raw.trim())
        if (raw.trimStart().startsWith("{") && raw.contains("\"tr_id\":\"PINGPONG\"")) {
            return ParsedMessage.Pingpong(rawMessage = raw)
        }

        val parts = raw.split("|")
        if (parts.size < 4) {
            // JSON 응답 (구독 성공/실패 등)
            return ParsedMessage.SubscriptionResponse(trId = "", rawJson = raw)
        }

        // hotfix 2026-05-20 — KIS 공식 spec: 첫 필드 0=평문, 1=AES-256-CBC 암호화.
        // 기존 결함: `1`을 SubscriptionResponse로 잘못 분류 → H0STCNI9 체결통보 KisExecutionWsListener forward 0
        //   → order_request status=ACK stuck (사용자 dogfooding 2026-05-20 evidence).
        // fix: `0`/`1` 둘 다 RealtimeData (encrypted flag 분기) — KisWebSocketBridge.handleMessage에서 AES 복호화.
        return when (val type = parts[0]) {
            "0" -> ParsedMessage.RealtimeData(trId = parts[1], rawData = parts[3], encrypted = false)
            "1" -> ParsedMessage.RealtimeData(trId = parts[1], rawData = parts[3], encrypted = true)
            else -> {
                log.debug("KIS WS 알 수 없는 메시지 타입: type={} length={}", type, raw.length)
                ParsedMessage.Unknown(raw)
            }
        }
    }

    /**
     * H0STCNT0 실시간 체결가 데이터 파싱 → TickEvent.
     * 캐럿(^) 구분 46개 필드.
     *
     * KIS-first: open-trading-api domestic_stock/domestic_stock_functions_ws.py ccnl_krx() columns 배열 기반.
     * raw URL: https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
     *
     * 등락률 필드 추출 (dogfooding 결함 1 — 싱크 불일치 해소):
     *   [3] PRDY_VRSS_SIGN — 전일대비부호
     *   [4] PRDY_VRSS      — 전일대비
     *   [5] PRDY_CTRT      — 전일대비율(%)
     */
    fun parseH0Stcnt0(
        rawData: String,
        expectedCode: String? = null,
    ): TickEvent? {
        return try {
            val fields = rawData.split("^")
            // 필드 게이트 (ADR 0117 §3-R1): index 18(CTTR)·14(ACML_TR_PBMN) 추출 보장 위해 < 21.
            // 기존 < 34는 BSOP_DATE(33) 전제였으나 체결강도 흡수에 과도 → < 21로 완화.
            // 21~33 구간의 BSOP_DATE/TIME·CTTR 등 누락 필드는 아래 getOrNull로 개별 graceful (전체 tick reject 금지 — 하위호환).
            if (fields.size < 21) {
                log.warn("H0STCNT0 필드 수 부족: expected ≥21 got={}", fields.size)
                return null
            }

            val code = fields[IDX_CODE].trim()
            if (expectedCode != null && code != expectedCode) {
                log.debug("H0STCNT0 code 불일치: expected={} got={}", expectedCode, code)
                return null
            }

            // tick 가격 sanity 가드 (2026-07-03 감사 §2′-⑤) — blank/0/음수 가격 tick reject.
            // 기존 `ifBlank { "0" }` coerce는 글리치 1건이 low=0 캔들로 영속(합산봉·지표 전파 오염)되던 결함.
            // 다른 파싱 실패 경로와 동일하게 null 반환 — 호출자(KisWebSocketBridge)가 reject 카운터로 관측.
            val priceStr = fields[IDX_PRICE].trim()
            val price = priceStr.toBigDecimalOrNull()
            if (price == null || price.signum() <= 0) {
                log.warn("H0STCNT0 tick 가격 무효 — reject (0원 캔들 영속 차단): code={} price='{}'", code, priceStr)
                return null
            }

            val volumeStr = fields[IDX_CNTG_VOL].trim()
            val volume = volumeStr.ifBlank { "0" }.toLongOrNull() ?: 0L

            // 등락 필드 파싱 — KIS H0STCNT0 [3][4][5]
            // KIS-first raw URL: https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
            val changeSign = fields.getOrNull(IDX_PRDY_VRSS_SIGN)?.trim()?.ifBlank { null }
            val priceChange =
                fields.getOrNull(IDX_PRDY_VRSS)?.trim()?.let { s ->
                    s.ifBlank { null }?.toBigDecimalOrNull()
                }
            val changeRate =
                fields.getOrNull(IDX_PRDY_CTRT)?.trim()?.let { s ->
                    s.ifBlank { null }?.toBigDecimalOrNull()
                }

            // R1-A 체결강도 흡수 (ADR 0117 §3-R1) — KIS 계산 완료값 흡수만, 자체 산식 0.
            // CTTR(18)·ACML_TR_PBMN(14) graceful: blank/파싱불가/필드결손 시 해당 필드만 null (전체 tick reject 금지).
            // KIS-first raw URL: https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
            val tradeIntensity =
                fields.getOrNull(IDX_CTTR)?.trim()?.let { s ->
                    s.ifBlank { null }?.toBigDecimalOrNull()
                }
            val turnoverAccum =
                fields.getOrNull(IDX_ACML_TR_PBMN)?.trim()?.let { s ->
                    s.ifBlank { null }?.toBigDecimalOrNull()
                }

            // 시각 파싱: BSOP_DATE(YYYYMMDD) + STCK_CNTG_HOUR(HHMMSS) → KST Instant
            // 게이트 완화(< 21)로 BSOP_DATE(33) 결손 가능 → getOrNull graceful (없으면 Clock.now() fallback).
            val dateStr = fields.getOrNull(IDX_BSOP_DATE)?.trim().orEmpty()
            val timeStr = fields.getOrNull(IDX_TIME)?.trim().orEmpty()

            val ts =
                if (dateStr.length == 8 && timeStr.length == 6) {
                    try {
                        val year = dateStr.substring(0, 4).toInt()
                        val month = dateStr.substring(4, 6).toInt()
                        val day = dateStr.substring(6, 8).toInt()
                        val hour = timeStr.substring(0, 2).toInt()
                        val minute = timeStr.substring(2, 4).toInt()
                        val second = timeStr.substring(4, 6).toInt()
                        kotlinx.datetime
                            .LocalDateTime(year, month, day, hour, minute, second)
                            .toInstant(kst)
                    } catch (e: Exception) {
                        log.debug("H0STCNT0 시각 파싱 실패 — Clock.now() 사용: date={} time={}", dateStr, timeStr)
                        Clock.System.now()
                    }
                } else {
                    Clock.System.now()
                }

            TickEvent(
                code = code,
                price = price,
                volume = volume,
                ts = ts,
                source = "live",
                stale = false,
                priceChange = priceChange,
                changeRate = changeRate,
                changeSign = changeSign,
                tradeIntensity = tradeIntensity,
                turnoverAccum = turnoverAccum,
            )
        } catch (e: Exception) {
            log.error("H0STCNT0 파싱 실패: payloadLength={} type={}", rawData.length, e.javaClass.simpleName)
            null
        }
    }

    /**
     * H0UNCNT0 통합 실시간 체결가 데이터 파싱 → TickEvent (ADR 0124 — 단일 체결가 채널).
     *
     * KIS-first raw 출처:
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/ccnl_total/ccnl_total.py
     *   ccnl_total() columns 배열 — ccnl_krx와 동일 핵심 필드 인덱스 구조.
     *
     * TickEvent.source = "live-unified".
     *
     * 세션 태그 (ADR 0124 수용 기준 #2 — 2026-07-03 감사 §2′-⑥ fix):
     *   [34] NEW_MKOP_CLS_CODE (신 장운영 구분 코드)를 [TickEvent.session]에 태그한다.
     *   base parser(parseH0Stcnt0)는 H0STOUP0와 공유되므로(해당 채널 [34] 계약 미검증) 여기서만 판독 —
     *   graceful: 필드 결손/blank 시 session=null (전체 tick reject 금지 — 기존 옵션 필드 패턴 정합).
     */
    fun parseH0Uncnt0(
        rawData: String,
        expectedCode: String? = null,
    ): TickEvent? {
        val base = parseH0Stcnt0(rawData, expectedCode) ?: return null
        val session =
            rawData
                .split("^")
                .getOrNull(IDX_NEW_MKOP_CLS_CODE)
                ?.trim()
                ?.ifBlank { null }
        return base.copy(source = "live-unified", session = session)
    }

    /**
     * H0STOUP0 시간외 단일가 실시간 체결가 데이터 파싱 → TickEvent.
     *
     * 활성 시간: 16:00~18:00 KST (시간외 단일가 세션 — OvertimeSinglePriceSubscriber 관리).
     *
     * KIS-first raw 출처:
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
     *   overtime_ccnl_krx() tr_id="H0STOUP0" — 국내주식 시간외 실시간체결가 (KRX) [실시간-042]
     *
     * 필드 인덱스 (43개 columns 배열 기반):
     *   [0]  mksc_shrn_iscd      — 종목코드
     *   [1]  stck_cntg_hour      — 체결시각 (HHMMSS)
     *   [2]  stck_prpr           — 현재가(체결가)
     *   [3]  prdy_vrss_sign      — 전일대비부호
     *   [4]  prdy_vrss           — 전일대비
     *   [5]  prdy_ctrt           — 전일대비율 (%)
     *  [12]  cntg_vol            — 체결거래량
     *  [33]  bsop_date           — 영업일자 (YYYYMMDD)
     *
     * TickEvent.source = "live-overtime" 로 구분 (H0UNCNT0 "live-unified"와 분리).
     *
     * 필드 인덱스가 ccnl_krx와 동일 위치 매핑이므로 parseH0Stcnt0 base parser 재사용.
     */
    fun parseH0Stoup0(
        rawData: String,
        expectedCode: String? = null,
    ): TickEvent? {
        // H0STOUP0 필드 인덱스 구조가 ccnl_krx와 동일 위치 매핑.
        // KIS-first: overtime_ccnl_krx.py columns 배열 [0][1][2][3][4][5][12][33] 인덱스 정합.
        // raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
        val base = parseH0Stcnt0(rawData, expectedCode) ?: return null
        return base.copy(source = "live-overtime")
    }

    /**
     * 구독/해제 응답 JSON에서 rt_cd(응답코드) 확인.
     * "0" = 성공, 그 외 = 실패.
     */
    fun isSubscriptionSuccess(rawJson: String): Boolean = parseSubscriptionResponse(rawJson)?.rtCd == "0"

    fun parseSubscriptionResponse(rawJson: String): SubscriptionResponseFields? =
        try {
            val root = objectMapper.readTree(rawJson)
            val header = root.get("header")?.takeIf { it.isObject } ?: return null
            val body = root.get("body")?.takeIf { it.isObject } ?: return null
            val rtCdNode = body.get("rt_cd")?.takeIf { it.isTextual } ?: return null
            val output = body.get("output")?.takeIf { it.isObject }
            SubscriptionResponseFields(
                trId =
                    header
                        .get("tr_id")
                        ?.takeIf { it.isTextual }
                        ?.asText()
                        ?.trim()
                        ?.takeIf { it.isNotBlank() },
                trKey =
                    header
                        .get("tr_key")
                        ?.takeIf { it.isTextual }
                        ?.asText()
                        ?.trim()
                        ?.takeIf { it.isNotBlank() },
                rtCd = rtCdNode.asText(),
                msgCd =
                    body
                        .get("msg_cd")
                        ?.takeIf { it.isTextual }
                        ?.asText()
                        ?.trim()
                        ?.takeIf { it.isNotBlank() },
                msg1 =
                    body
                        .get("msg1")
                        ?.takeIf { it.isTextual }
                        ?.asText()
                        ?.trim()
                        ?.takeIf { it.isNotBlank() },
                iv = output?.get("iv")?.takeIf { it.isTextual }?.asText(),
                key = output?.get("key")?.takeIf { it.isTextual }?.asText(),
            )
        } catch (e: Exception) {
            log.debug("KIS WS 구독 응답 strict parse 실패: type={}", e.javaClass.simpleName)
            null
        }

    /**
     * 구독 응답에서 OPSP8996 "ALREADY IN USE appkey" 에러 감지.
     *
     * ADR 0076 §3 F-1: OPSP8996 self-heal 진입 판단.
     * KIS 응답 예시:
     *   {"header":{"tr_id":"H0UNCNT0","datetime":"..."},"body":{"rt_cd":"1","msg_cd":"OPSP8996","msg1":"ALREADY IN USE appkey",...}}
     *
     * msg_cd 필드 기반 감지 (rt_cd "1" + msg_cd "OPSP8996" 동시 조건).
     */
    fun isOpsp8996(rawJson: String): Boolean =
        try {
            rawJson.contains("OPSP8996")
        } catch (e: Exception) {
            false
        }

    /**
     * 구독 응답에서 OPSP0008 "MAX SUBSCRIBE OVER" 에러 감지 (ADR 0123 R1 — OPSP8996 1:1 패턴).
     *
     * KIS WS 동시 구독 한도(40) 초과 시 신규 subscribe 요청이 거부된다.
     * KIS 응답 예시 (2026-06-11 라이브 실측 evidence — ADR 0123 §1-1):
     *   {"header":{"tr_id":"H0UNASP0","tr_key":"093370"},
     *    "body":{"rt_cd":"1","msg_cd":"OPSP0008","msg1":"MAX SUBSCRIBE OVER"}}
     *
     * msg_cd 문자열 기반 감지 (rt_cd "1" + msg_cd "OPSP0008"). isSubscriptionSuccess=false 분기에서만 평가.
     */
    fun isOpsp0008(rawJson: String): Boolean =
        try {
            rawJson.contains("OPSP0008")
        } catch (e: Exception) {
            false
        }

    /**
     * 구독/해제 응답에서 OPSP8997 "invalid tr_type" 에러 감지 (결함③ 진단 관측 — 2026-06-15).
     *
     * DETAIL 종목 EXIT/15분 idle 해제 시 H0UNASP0(+H0UNCNT0) unsubscribe가 KIS
     * `OPSP8997 invalid tr_type` 으로 거부된 사례(REAL 세션 재현). 응답 header `tr_id=null` 이라
     * 추적이 불가했다. 후속 hotfix의 "2" 추론은 2026-07-16 공식 샘플 재확인으로 supersede됐고,
     * 현행 공식 해제값은 "0"이다. 본 감지는 OPSP8997 분기에서
     * [KisWebSocketBridge] 가 최근 unsubscribe 이력을
     * 상관관계 덤프하기 위한 관측 전용 hook 이다(부작용 0 — 코드 동작 변경 없음).
     *
     * KIS 응답 예시(추정 — 실측 시 본 KDoc 갱신): {"body":{"rt_cd":"1","msg_cd":"OPSP8997","msg1":"invalid tr_type"}}
     * msg_cd "OPSP8997" 또는 msg1 "invalid tr_type"(대소문자 무시) 문자열 기반 감지. isSubscriptionSuccess=false 분기에서만 평가.
     */
    fun isOpsp8997InvalidTrType(rawJson: String): Boolean =
        try {
            rawJson.contains("OPSP8997") || rawJson.contains("invalid tr_type", ignoreCase = true)
        } catch (e: Exception) {
            false
        }

    /**
     * 구독 거부 응답에서 (trId, trKey) 채널 식별자 추출 (ADR 0123 R1 — 회계 보정용).
     *
     * KIS 거부 응답 header에 거부된 채널의 tr_id·tr_key가 담긴다:
     *   {"header":{"tr_id":"H0UNASP0","tr_key":"093370"}, "body":{"msg_cd":"OPSP0008",...}}
     * ledger가 해당 (trId, trKey) 채널 점유를 취소(회계 보정)하기 위해 사용.
     *
     * strict Jackson tree 파싱으로 root.header의 tr_id·tr_key만 허용한다.
     * 추출 실패 시 null 반환 → 호출자(KisWebSocketBridge)가 graceful 처리.
     *
     * @return Pair(trId, trKey) — 둘 다 추출 성공 시. 하나라도 누락 시 null.
     */
    fun extractSubscriptionChannel(rawJson: String): Pair<String, String>? {
        val response = parseSubscriptionResponse(rawJson) ?: return null
        val trId = response.trId ?: return null
        val trKey = response.trKey ?: return null
        return trId to trKey
    }

    fun extractRejectedChannel(rawJson: String): Pair<String, String>? = extractSubscriptionChannel(rawJson)

    /**
     * 구독 해제(unsubscribe) ACK 감지.
     *
     * ADR 0076 E-1: destroy() unsubscribe ACK 2s 대기 시 수신 확인용.
     *
     * KIS-first 출처:
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
     *   isUnSub = True if tr_msg[:5] == "UNSUB" else False
     *   — body.msg1 앞 5글자가 "UNSUB"이면 구독 해제 응답.
     *
     * 보완: msg_cd "OPSP0002" (UNSUBSCRIBE SUCCESS) 추가 감지
     *   — ADR 0076 E-1 명세 정합.
     */
    fun isUnsubscribeAck(rawJson: String): Boolean =
        parseSubscriptionResponse(rawJson)?.let { response ->
            response.rtCd == "0" &&
                (response.msgCd == "OPSP0002" || response.msg1?.startsWith("UNSUB", ignoreCase = true) == true)
        } ?: false
}
