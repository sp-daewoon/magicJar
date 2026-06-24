package kr.spdaewoon.magicjar.domain.backtest

/**
 * 백테스트 실행 모드 — ADR 0132 §1-A (라이브 parity 청산 vs 기존 DSL 청산).
 *
 * 도메인 모듈에만 위치 — Spring·JPA·Kafka import 절대 금지(헥사고날 정합).
 *
 * ## 왜 모드 분기인가 (방향성 가드 — 신규 엔진 X)
 * 초단타 정합 백테스트는 라이브 [kr.spdaewoon.magicjar.strategy.TradePlanEvaluator] 청산 두뇌를 그대로 재사용해
 * parity 를 보장한다(ADR 0132 §3 기둥1). 신규 백테스트 엔진/컨트롤러를 만들면 세금·슬리피지·MTM·룩어헤드를
 * 재구현해 회귀 표면이 폭증하므로, 기존 [BacktestRequest] 에 모드 플래그만 add-only 로 추가하고 엔진이 분기한다
 * (LEGACY default = byte-동일 zero-regression).
 */
enum class BacktestExecutionMode {
    /**
     * 기존 동작 — DSL sell + scale-in/exit 경로 (default · zero-regression LOCK).
     *
     * 기존 백테스트 호출은 본 값 default 로 동작이 byte-동일하게 유지된다.
     */
    LEGACY,

    /**
     * 라이브 parity — `TradePlanEvaluator` 청산(SL>MAX_HOLD>exitConditions>TP>TRAIL) + intrabar SL 우선 +
     * EOD(15:15)/개장 blackout(09:00~09:15)/min_hold (ADR 0132 §3 기둥1).
     *
     * 진입은 기존 DSL entry 를 유지하고 **청산만** 라이브 두뇌로 교체한다 — 라이브와 같은 매매를 검증.
     */
    INTRADAY_REALISTIC,
}
