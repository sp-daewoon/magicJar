package com.mj.domain.strategy.backtest

import java.math.BigDecimal
import java.time.Instant

/**
 * 백테스트 루프와 [BacktestOrderSender] 사이의 봉별 컨텍스트.
 *
 * ThreadLocal 보관 — 멀티스레드 백테스트는 별도 ExecutorService에서 각자 컨텍스트를 set/clear한다.
 */
data class BacktestExecutionContext(
    val nextBarOpen: BigDecimal?,
    val slippageBp: BigDecimal,
    val fillAt: Instant?,
)

object BacktestExecutionContextHolder {
    private val tl = ThreadLocal<BacktestExecutionContext?>()

    fun setContext(ctx: BacktestExecutionContext) {
        tl.set(ctx)
    }

    fun clear() {
        tl.remove()
    }

    fun current(): BacktestExecutionContext? = tl.get()
}
