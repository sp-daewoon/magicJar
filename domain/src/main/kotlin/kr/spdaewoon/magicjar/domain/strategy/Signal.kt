package kr.spdaewoon.magicjar.domain.strategy

/**
 * 전략 평가 시그널 — JvmStrategyEvaluator 결과.
 *
 * - BUY: 매수 시그널
 * - SELL: 매도 시그널
 * - HOLD: 중립 (조건 불충족 또는 T-PAT stub)
 */
enum class Signal { BUY, SELL, HOLD }
