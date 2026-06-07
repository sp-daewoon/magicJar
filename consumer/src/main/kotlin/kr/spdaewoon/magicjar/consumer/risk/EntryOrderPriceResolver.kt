package kr.spdaewoon.magicjar.consumer.risk

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.port.EntryQuotePort
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.domain.strategy.ResolvedEntryPolicy
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.strategy.entry.EntryPriceDecision
import kr.spdaewoon.magicjar.strategy.entry.EntryPriceInput
import kr.spdaewoon.magicjar.strategy.entry.EntryPricePolicy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * 진입(BUY) marketable limit 가격 산정 배선 — ADR 0114 §3-1·§6 (R2-C2a).
 *
 * ## 책임 경계 (SRP — 진보 페르소나 ADR 0087)
 * - 순수 계산은 [EntryPricePolicy](strategy-engine) 단일 함수가 책임진다(live·백테스트·리플레이 공유).
 * - 본 컴포넌트는 그 **호출 배선**만 책임진다: 전략의 [kr.spdaewoon.magicjar.domain.strategy.EntryPolicy] +
 *   전역 default(env) → [ResolvedEntryPolicy], [EntryQuotePort]로 호가 동기 read → [EntryPriceInput] 조립 → evaluate.
 * - 주문 발행/스킵 audit은 호출자([SignalGeneratedKafkaListener.handleAllowed])가 결정([EntryOrderDecision] 소비).
 *
 * ## 시장가 fallback 금지 (ADR 0114 §3-1-5 LOCK)
 * 호가 source([EntryQuotePort]) 부재·캐시 miss + REST 실패면 [EntryQuote]=null → [EntryPriceInput.ask1/bid1]=null →
 * [EntryPricePolicy]가 [EntryPriceDecision.Skip.NoQuote] 반환. 어떤 경우에도 MARKET fallback 0(슬리피지 캡 자기 부정 회피).
 *
 * ## 전역 default (env `magicjar.order.entry.*` — ADR 0114 §10-5)
 * 전략별 [kr.spdaewoon.magicjar.domain.strategy.EntryPolicy]가 null이거나 개별 필드 null이면 본 env default로 폴백한다
 * ([kr.spdaewoon.magicjar.domain.strategy.EntryPolicy.resolved]). 기존 전략 0 변경(하위호환 LOCK).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 (ADR 0023 §2-4)
 */
@Component
class EntryOrderPriceResolver(
    private val entryQuotePortProvider: ObjectProvider<EntryQuotePort>,
    @Value("\${magicjar.order.entry.max-slippage-pct:0.2}") private val defaultMaxSlippagePct: String = "0.2",
    @Value("\${magicjar.order.entry.timeout-sec:15}") private val defaultTimeoutSec: Int = 15,
    @Value("\${magicjar.order.entry.max-spread-pct:0.3}") private val defaultMaxSpreadPct: String = "0.3",
    private val clock: Clock = Clock.System,
) {
    private val log = LoggerFactory.getLogger(EntryOrderPriceResolver::class.java)

    private val globalDefault: ResolvedEntryPolicy by lazy {
        ResolvedEntryPolicy(
            maxEntrySlippagePct = parsePct(defaultMaxSlippagePct, ResolvedEntryPolicy.DEFAULTS.maxEntrySlippagePct),
            entryTimeoutSec = defaultTimeoutSec.takeIf { it > 0 } ?: ResolvedEntryPolicy.DEFAULTS.entryTimeoutSec,
            maxSpreadPct = parsePct(defaultMaxSpreadPct, ResolvedEntryPolicy.DEFAULTS.maxSpreadPct),
        )
    }

    /**
     * BUY 시그널의 진입 LIMIT 가격을 산정한다.
     *
     * @param signal BUY 시그널(priceAtSignal = 신호가 = cap 산정 base, 추격 금지 §3-2).
     * @param strategy 시그널 발행 전략(entryPolicy override 보유 가능. null이면 전역 default만 적용).
     * @return [EntryOrderDecision.Enter]면 호출자가 LIMIT 발행, [EntryOrderDecision.Skip]이면 주문 0 + audit.
     */
    fun resolve(
        signal: Signal,
        strategy: Strategy?,
    ): EntryOrderDecision {
        val resolved = strategy?.definition?.entryPolicy?.resolved(globalDefault) ?: globalDefault

        val quotePort = entryQuotePortProvider.getIfAvailable()
        val quote =
            if (quotePort == null) {
                log.warn(
                    "EntryQuotePort bean 부재 — 진입 호가 미가용 → NoQuote skip: code={} signalId={}",
                    signal.code,
                    signal.id,
                )
                null
            } else {
                runCatching { quotePort.latestQuote(signal.code) }
                    .onFailure { e ->
                        log.warn("EntryQuotePort.latestQuote 실패 — NoQuote skip: code={} err={}", signal.code, e.message)
                    }.getOrNull()
            }

        val input =
            EntryPriceInput(
                priceAtSignal = signal.priceAtSignal,
                ask1 = quote?.ask1,
                bid1 = quote?.bid1,
                askQty1 = quote?.askQty1,
                maxEntrySlippagePct = resolved.maxEntrySlippagePct,
                maxSpreadPct = resolved.maxSpreadPct,
                quoteAsOf = quote?.asOf,
                evalAt = clock.now(),
            )

        return when (val decision = EntryPricePolicy.evaluate(input)) {
            is EntryPriceDecision.Enter ->
                EntryOrderDecision.Enter(
                    limitPrice = decision.limitPrice,
                    entryTimeoutSec = resolved.entryTimeoutSec,
                )
            is EntryPriceDecision.Skip ->
                EntryOrderDecision.Skip(
                    auditCode = decision.auditCode,
                    ask1 = quote?.ask1,
                    bid1 = quote?.bid1,
                    quoteAsOf = quote?.asOf,
                )
        }
    }

    private fun parsePct(
        raw: String,
        fallback: BigDecimal,
    ): BigDecimal = raw.trim().toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO } ?: fallback
}

/**
 * 진입 가격 산정 결과 — sealed(LIMIT 발행 vs 스킵 audit 명확 분기 · ADR 0114 §6-1).
 *
 * [EntryPriceDecision](strategy-engine)의 도메인 결정을 **배선 관점**(timeout·호가 메타 포함)으로 감싼 consumer 전용 표현이다.
 */
sealed interface EntryOrderDecision {
    /** 진입 — priceType=LIMIT + limitPrice 발행. entryTimeoutSec는 미체결 취소 워커가 사용(§3-2). */
    data class Enter(
        val limitPrice: BigDecimal,
        val entryTimeoutSec: Int,
    ) : EntryOrderDecision

    /** 진입 스킵 — 주문 0 + audit(auditCode = SIGNAL_SKIPPED_*). 시장가 fallback 0. */
    data class Skip(
        val auditCode: String,
        val ask1: BigDecimal?,
        val bid1: BigDecimal?,
        val quoteAsOf: Instant?,
    ) : EntryOrderDecision
}
