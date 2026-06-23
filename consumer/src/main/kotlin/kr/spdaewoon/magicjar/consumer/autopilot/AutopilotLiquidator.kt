package kr.spdaewoon.magicjar.consumer.autopilot

/**
 * magicjar-autopilot **포지션 청산 포트** (spec 2026-06-22 §3 롤백 · Phase 0 Task 4·5 계약).
 *
 * `owner=AUTOPILOT` 보유(quantity>0) 포지션을 **시장가 SELL 청산**한다. 본 인터페이스는 **backend-core(T4)가
 * 선언**하고, 구현([AutopilotLiquidationService])은 **market-data(T5)가** 기존 `OrderIntent` 경로(신규 KIS endpoint 0)로
 * 작성한다. 제어 리스너([AutopilotControlListener])는 ROLLBACK 시 이 포트를 **주입**해 호출한다.
 *
 * ## 격리 LOCK (spec §2-0-C)
 * owner=AUTOPILOT 포지션만 청산 — 사용자 수동 전략·후성 오버나잇은 **절대 비대상**. 구현체가 격리를 보장한다.
 *
 * ## 빌드 독립
 * 구현 빈([AutopilotLiquidationService])이 아직 없어도 본 인터페이스만으로 컴파일된다. 제어 리스너는
 * `ObjectProvider`로 lazy 주입(빈 부재 시 ROLLBACK 만 graceful skip — STOP·RESUME 은 영향 0).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 포트 (ADR 0023 §2-4 정합)
 */
interface AutopilotLiquidator {
    /**
     * owner=AUTOPILOT 보유 포지션 전부를 시장가 SELL 청산한다.
     *
     * @param userId 청산 대상 사용자(null = account-wide 전체). 단일 계좌 canary 는 보통 단일 userId.
     * @param reason 청산 사유(audit·intent 메타).
     * @return 청산 결과([AutopilotLiquidationOutcome]) — 청산 종목·발행 SELL intent 수.
     */
    fun liquidateAll(
        userId: String?,
        reason: String,
    ): AutopilotLiquidationOutcome

    /**
     * owner=AUTOPILOT **AND allow_overnight=false** 보유 포지션만 시장가 SELL 청산한다 (ADR 0129 레버 1 — EOD 청산).
     *
     * [liquidateAll] 과 동일 메커니즘(기존 OrderIntent 경로·멱등 키)이되 청산 대상을 오버나잇 금지 lot 으로 좁힌다.
     * EOD 워커([kr.spdaewoon.magicjar.consumer.autopilot.AutopilotEodLiquidationWorker])가 장마감 전 cutoff 에 호출한다.
     *
     * @param userId 청산 대상 사용자(null = account-wide). @param reason 청산 사유(audit·intent 메타).
     * @return 청산 결과 — 청산 종목·발행 SELL intent 수. allow_overnight=true·USER/SYSTEM lot 은 절대 비대상(격리 LOCK).
     */
    fun liquidateNoOvernightAutopilot(
        userId: String?,
        reason: String,
    ): AutopilotLiquidationOutcome
}

/**
 * 청산 결과 — 어떤 종목을 몇 건의 SELL intent 로 청산했는가(롤백 audit·테스트 검증용).
 *
 * @param liquidatedCodes 청산 SELL intent 가 발행된 종목 코드 목록.
 * @param totalSellIntents 발행된 SELL intent 총 건수.
 */
data class AutopilotLiquidationOutcome(
    val liquidatedCodes: List<String>,
    val totalSellIntents: Int,
) {
    companion object {
        /** 청산 대상 0(보유 없음) 또는 구현 빈 부재 시 graceful no-op 결과. */
        val EMPTY = AutopilotLiquidationOutcome(emptyList(), 0)
    }
}
