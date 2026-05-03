package com.mj.domain.strategy.backtest

import java.math.BigDecimal
import java.time.Instant

/**
 * 백테스트 엔진 입력용 봉 모델 (ADR 0038·0040).
 *
 * 호출자(api/batch)가 candle 테이블의 `*_adjusted` 컬럼을 채워 주입한다.
 * 도메인 엔진은 이 값을 그대로 사용하므로 룩어헤드·corp action 보정 책임은 호출자에 있다.
 *
 * - `close`는 수정 종가(adjusted_close)이다.
 * - `rawClose`는 원주가(보고용). 매도 시 거래세 계산은 raw·adjusted 동일 비율이라 close만으로 충분.
 * - `openTime`은 봉 시작 시각(KST 기준이지만 Instant). 룩어헤드 차단 검증에 사용.
 */
data class BacktestBar(
    val openTime: Instant,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
    /** 원주가 종가(보고용, optional). 백테스트 계산에는 사용 안 함. */
    val rawClose: BigDecimal? = null,
)
