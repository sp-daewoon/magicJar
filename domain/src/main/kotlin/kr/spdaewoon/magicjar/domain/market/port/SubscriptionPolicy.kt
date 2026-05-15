package kr.spdaewoon.magicjar.domain.market.port

/**
 * KIS WS 41 stream slot 예산 결정 (ADR 0048 §2-1).
 * Pure function — Spring 의존 0.
 *
 * 슬롯 구성 (ADR 0048 §2-1 + NXT hotfix):
 *   1  = H0STCNI0 (체결통보 — ADR 0042 STAGE 0-4 reserve, T-O 구현)
 *   30 = H0STCNT0 (체결가 실시간 — 관심종목 WS 구독)
 *   10 = H0STASP0 (호가창 LRU — 선택)
 *
 * NXT(H0NXCNT0) 추가 시 슬롯 정합 (ADR 0048 §2-1 — "41건은 stream 수 단위 한도 (sum across TR_IDs)"):
 *   H0STCNT0 종목당 1 slot + H0NXCNT0 종목당 1 slot = 동일 종목 2 slot 소모.
 *   NXT_TICK_MAX = 20: H0STCNT0(20) + H0NXCNT0(20) + H0STCNI0(1) = 41 정합.
 *   Watchlist 풀 ≤ 20 환경(MVP)에서는 안전. 풀 > 20 시 slot 초과 risk → backlog §53 SubscriptionRouter priority 재조정 의제.
 *
 * KIS-first 출처: open-trading-api examples_user/domestic_stock/domestic_stock_functions_ws.py
 *   ccnl_nxt() tr_id = "H0NXCNT0" — NXT 대체거래소 체결가
 *   raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
 */
object SubscriptionPolicy {
    const val TOTAL_SLOTS = 41
    const val EXECUTION_NOTICE_RESERVED = 1 // H0STCNI0
    const val PRICE_TICK_MAX = 30 // H0STCNT0 (NXT 미추가 시 최대 30)
    const val ORDERBOOK_MAX = 10 // H0STASP0 (LRU)

    /**
     * H0NXCNT0 NXT 대체거래소 체결가 최대 구독 종목 수.
     * H0STCNT0(20) + H0NXCNT0(20) + H0STCNI0(1) = 41 slot 정합.
     * KIS-first: open-trading-api ccnl_nxt() tr_id=H0NXCNT0
     * raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
     */
    const val NXT_TICK_MAX = 20 // H0NXCNT0 (H0STCNT0와 동일 종목 세트, 각 1 slot)

    /**
     * H0STOUP0 시간외 단일가 최대 구독 종목 수 (ADR 0076 Phase 3 B-2).
     *
     * 활성 시간: 16:00~18:00 KST (시간외 단일가 세션).
     * 이 시간대에 H0STCNT0·H0NXCNT0 체결 tick 0건 → slot 미소모 상태.
     * 따라서 H0STOUP0 20 slot 추가해도 실질 동시 사용 slot:
     *   시간외 단일가(16:00~18:00): H0STOUP0(20) + H0STCNI0(1) = 21 ≤ 41 안전.
     *   정규장(09:00~15:30):       H0STCNT0(20) + H0NXCNT0(20) + H0STCNI0(1) = 41 안전.
     *
     * KIS-first 출처: open-trading-api domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
     *   tr_id = "H0STOUP0" — 국내주식 시간외 실시간체결가 (KRX) [실시간-042]
     *   raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
     */
    const val OVERTIME_SINGLE_PRICE_MAX = 20 // H0STOUP0 (16:00~18:00 KST 한정)

    fun priceTickSlotsAvailable(currentSubscribed: Int): Int = (PRICE_TICK_MAX - currentSubscribed).coerceAtLeast(0)
}
