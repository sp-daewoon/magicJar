package kr.spdaewoon.magicjar.domain.account.port

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * KIS 실현손익 ↔ 우리 FIFO 계산 크로스체크 결과 (2026-06-22 market-data 신설).
 *
 * ### 목적
 * 우리 성과 API(PerformanceService.buildPnlDailyFromTrades)가 우리 DB 체결(order_execution/order_request)에서
 * FIFO 로 계산한 실현손익이 **KIS 실제 실현손익(TTTC8494R)** 과 맞는지 증명한다. "우리 숫자가 진짜인가" 를
 * KIS 권위로 대조하는 프로덕션 토대 — 애매하면 KIS 와 대조한다(에이전틱 검증 표준).
 *
 * ### KIS 실현손익 semantics (raw 출처 — 추측 0)
 * raw: open-trading-api `examples_llm/domestic_stock/inquire_balance_rlz_pl/{inquire_balance_rlz_pl,chk_...}.py`
 *  - tr_id `TTTC8494R`, screen HTS [0800] **국내 체결기준잔고** (체결기준 = 보유분 기준).
 *  - 공식 예제 파라미터: `INQR_DVSN=02`, `UNPR_DVSN=01`, `PRCS_DVSN=01`(전일매매미포함), `COST_ICLD_YN=N`(비용 미포함).
 *  - output1 종목별 `rlzt_pfls`(실현손익)·`rlzt_erng_rt`(실현수익율) / output2 계좌 `rlzt_pfls`(실현손익 합계).
 *
 * ### 우리 계산과의 구조적 차이 (tolerance 분류 근거)
 *  1. **비용(수수료·세금)**: KIS 는 `COST_ICLD_YN=N` → 실현손익에 비용 미포함(gross). 우리는 매도 비용 차감(net).
 *     → 동일 비교를 위해 우리도 gross(비용 미차감) 실현손익을 함께 산출해 KIS 와 대조한다.
 *  2. **집계 범위**: KIS `rlzt_pfls` 는 **체결기준잔고(보유 종목)** 의 보유기간 실현손익이라, 당일 전량 청산해
 *     hldg_qty=0 이 된 종목은 output1 에서 빠질 수 있다(0800 화면 잔고 행 기준). 우리 DAY 계산은 당일 매도 왕복 전부.
 *     → 종목별 대조는 "KIS 에 잔고 행이 남아있는 종목" 한정 정밀 비교 + 계좌합계는 semantics 차이를 명시한다.
 *  3. **당일/전일**: `PRCS_DVSN=01`(전일매매미포함) → 전일 체결분 제외. 우리 DAY 도 당일 한정이라 정합.
 *
 * @property matched gross 기준 계좌합계 drift 가 tolerance 이내인지 (정확 일치 기대 — 원 단위)
 */
data class RealizedPnlCrossCheck(
    val userId: String,
    val period: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    /** KIS output2 계좌 실현손익 합계(rlzt_pfls) — gross(비용 미포함, COST_ICLD_YN=N). */
    val kisAccountRealizedPnl: BigDecimal?,
    /** KIS output2 실평가손익(real_evlu_pfls) — 참고용. */
    val kisRealEvaluationPnl: BigDecimal?,
    /** 우리 net 실현손익(매도 비용 차감) — PerformanceService 와 동일 정의. */
    val ourNetRealizedPnl: BigDecimal,
    /** 우리 gross 실현손익(비용 미차감) — KIS gross 와 직접 비교용. */
    val ourGrossRealizedPnl: BigDecimal,
    /** 우리 매도 비용 합계(fee+tax) — gross↔net 차이 설명. */
    val ourTotalCost: BigDecimal,
    /** gross drift = KIS account - 우리 gross. null = KIS 합계 부재. */
    val accountDriftGross: BigDecimal?,
    /** net drift = KIS account - 우리 net (참고 — KIS 가 gross 라 비용만큼 벌어짐). */
    val accountDriftNet: BigDecimal?,
    /** tolerance(원). drift 절대값이 이 값 이하이면 matched=true. */
    val toleranceWon: BigDecimal,
    val matched: Boolean,
    /** drift 발생 시 원인 분류 매트릭스(미체결 지연·비용·당일/누계·페이지네이션 등). */
    val driftCauses: List<String>,
    /** 종목별 대조 행. */
    val perCode: List<RealizedPnlCrossCheckRow>,
    /** KIS 호출 성공 여부(rt_cd=0). false 면 errorMessage 참조. */
    val kisCallOk: Boolean,
    val errorMessage: String? = null,
    val checkedAt: Instant = Instant.now(),
)

/**
 * 종목별 KIS ↔ 우리 실현손익 대조 행.
 *
 * @property presentInKis KIS output1 에 이 종목 행이 존재했는지(전량 청산 시 false 가능 — semantics 차이 마커).
 */
data class RealizedPnlCrossCheckRow(
    val code: String,
    val name: String?,
    /** KIS output1 종목 실현손익(rlzt_pfls) — gross. */
    val kisRealizedPnl: BigDecimal?,
    /** 우리 gross 실현손익(비용 미차감). */
    val ourGrossRealizedPnl: BigDecimal,
    /** 우리 net 실현손익(비용 차감). */
    val ourNetRealizedPnl: BigDecimal,
    /** gross drift = KIS - 우리 gross. */
    val driftGross: BigDecimal?,
    val presentInKis: Boolean,
    val matched: Boolean,
)
