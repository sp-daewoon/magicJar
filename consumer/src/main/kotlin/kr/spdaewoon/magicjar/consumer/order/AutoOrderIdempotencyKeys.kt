package kr.spdaewoon.magicjar.consumer.order

import java.security.MessageDigest

/**
 * 청산 안전망 intent 멱등 키 빌더 (ADR 0114 R2-C2b — Codex `AutoOrderIdempotencyKeys` 패턴 흡수).
 *
 * tick 손절 watcher([PositionStopLossTickWatcher])·체결 즉시 TP 선주문([AutoExitOrderCoordinator])이
 * 발행하는 OrderIntent의 `idempotencyKey`를 생성한다. **prefix가 의미를 갖는다**:
 * - `auto-sl:` — 손절 MARKET intent. open SELL 검사([PositionStopLossTickWatcher.hasOpenStopOrder])가 prefix로 식별.
 * - `auto-tp:` — 익절 LIMIT 선주문. 재발행(scale-in 평단 변동) 시 기존 TP를 prefix로 찾아 취소.
 *
 * OrderRouter 멱등 가드(`idem:order:{key}` Redis SETNX 60s — [OrderRouterKafkaListener])가 동일 키 재발행을
 * 흡수하므로, **동일 (사용자·전략·종목·수량·평단·체결시각)** 입력은 같은 키 → 이중 발화 멱등.
 *
 * ## Codex 대비 정합
 * Codex와 동일한 SHA-256 32-hex digest. 단 우리는 prefix별 의도를 KDoc으로 LOCK하고
 * 1m 시그널 경로(`{signalId}:{userId}`)와 키 공간이 겹치지 않음을 보장한다(prefix 격리).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 순수 유틸 (ADR 0023 §2-4)
 */
internal object AutoOrderIdempotencyKeys {
    /** 익절 LIMIT 선주문 키 — `auto-tp:<digest>`. parts: userId·strategyId·code·quantity·avgPrice·epoch. */
    fun takeProfit(vararg parts: Any?): String = shortKey(PREFIX_TAKE_PROFIT, parts.asList())

    /** 손절 MARKET 키 — `auto-sl:<digest>`. parts: userId·strategyId·code·quantity·epoch. */
    fun stopLoss(vararg parts: Any?): String = shortKey(PREFIX_STOP_LOSS, parts.asList())

    const val PREFIX_TAKE_PROFIT: String = "auto-tp"
    const val PREFIX_STOP_LOSS: String = "auto-sl"

    private fun shortKey(
        prefix: String,
        parts: List<Any?>,
    ): String {
        val raw = parts.joinToString("|") { it?.toString() ?: "" }
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(32)
        return "$prefix:$digest"
    }
}
