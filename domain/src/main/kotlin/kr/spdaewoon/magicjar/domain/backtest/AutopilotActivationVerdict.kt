package kr.spdaewoon.magicjar.domain.backtest

/**
 * autopilot 전략 ACTIVE 검증 게이트 판정 — ADR 0133 §3-B (#5 · 값객체 LOCK).
 *
 * 도메인 모듈에만 위치 — Spring·JPA·Kafka import 절대 금지(헥사고날 정합).
 *
 * owner=AUTOPILOT 전략을 INTRADAY_REALISTIC 백테스트([BacktestPassCriteria]) 로 검증한 결과.
 * - [PASS] → ACTIVE 유지 + backtest_run 영속(=existsAutoActivationReadyRun 충족 · 기존 게이트 수렴).
 * - [FAIL] → BUY_BLOCKED(매수 억제 · 보유 lot 은 hold-until-exit · 강제 청산 X — ADR 0131 정합 · 자본 안전).
 */
enum class AutopilotActivationVerdict {
    /** 합격 기준 전부 통과 — ACTIVE 유지/승격. */
    PASS,

    /** 하나 이상 미달 / 데이터 부족 / 백테스트 에러 — BUY_BLOCKED(보수). */
    FAIL,
}

/**
 * 검증 FAIL 사유 코드 — ADR 0133 §3-B1 (미달 기준 + fail-safe 코드).
 *
 * 운영 가시성: SystemEvent·로그·frontend 배지에 정직 노출(어떤 기준이 깨졌는지). mock 합성 0.
 */
enum class ActivationFailReason {
    /** 거래수 < minTrades — 표본 부족(승률 우연 risk). */
    INSUFFICIENT_SAMPLE,

    /** 승률 < minWinRate. */
    LOW_WIN_RATE,

    /** net 기대값 ≤ minExpectancy(default 0) — 1거래당 순기대 수익 양수 아님. */
    NEGATIVE_EXPECTANCY,

    /** MDD > maxMdd — drawdown 한도 초과(자본 안전 위협). */
    MDD_EXCEEDED,

    /** 정합성 등급이 requireConfidence 미만(결측률 높음 — 결과 신뢰도 낮음). */
    LOW_CONFIDENCE,

    /** 결측률 과다(INSUFFICIENT_DATA) 또는 거래 0 — 판정 불가 → 보수 FAIL. */
    INSUFFICIENT_DATA,

    /** 백테스트 실행 자체 에러(fail-safe) → 거짓 PASS 0 보수 FAIL. */
    BACKTEST_ERROR,
}
