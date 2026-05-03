package com.mj.consumer.application.signal

import com.mj.consumer.messaging.producer.SignalGeneratedPublisher
import com.mj.domain.market.event.TickEvent
import com.mj.domain.strategy.signal.Signal
import com.mj.domain.strategy.signal.SignalAction
import com.mj.domain.strategy.signal.SignalEngine
import com.mj.domain.strategy.signal.SignalEvalRequest
import com.mj.domain.strategy.signal.event.SignalGeneratedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Signal Engine application service (트랙11 R1, ADR 0041).
 *
 * 흐름:
 *  1. tick ingest → CandleBuffer 누적
 *  2. 종목에 활성 전략이 있으면 SignalEngine 평가
 *  3. action ≠ HOLD → SignalGeneratedEvent를 `signal.generated.v1` 토픽 발행
 *
 * 룰 매칭 only — AI 호출 0 (ADR 0041).
 *
 * R1 정책:
 *   - HOLD 액션은 발행 X (시그널 토픽 트래픽 절감, UI 측 노이즈 제거).
 *   - 같은 평가 결과 연속 발행은 R1에선 허용 (R2에서 dedup·threshold 도입).
 *   - 사용자별 fanout은 anonymous(userId=null) 단일.
 *
 * 결정론:
 *   - SignalEngine은 결정론. 같은 candle snapshot + 같은 dsl → 같은 action.
 *   - tick ingest 동시성으로 평가 시점 봉 수 차이는 가능 — 운영상 허용.
 */
@Component
class SignalEngineExecutor(
    private val signalEngine: SignalEngine,
    private val candleBuffer: CandleBuffer,
    private val activeStrategyRegistry: ActiveStrategyRegistry,
    private val publisher: SignalGeneratedPublisher,
) {
    private val log = LoggerFactory.getLogger(SignalEngineExecutor::class.java)

    /**
     * tick 처리 — buffer 누적 + 평가 + 발행.
     *
     * 발행한 시그널 목록 반환 (테스트·모니터링용). HOLD 시그널은 포함하지 않음.
     */
    fun onTick(tick: TickEvent): List<Signal> {
        // 1. buffer 누적
        candleBuffer.ingest(tick)
        val candles = candleBuffer.snapshot(tick.code)
        if (candles.isEmpty()) return emptyList()

        // 2. 활성 전략 조회 + 평가
        val subscriptions = activeStrategyRegistry.subscriptionsFor(tick.code)
        if (subscriptions.isEmpty()) return emptyList()

        val emitted = mutableListOf<Signal>()
        for (sub in subscriptions) {
            val signal =
                try {
                    signalEngine.generate(
                        SignalEvalRequest(
                            strategyDsl = sub.strategyDsl,
                            symbol = tick.code,
                            candles = candles,
                            asOfBar = candles.lastIndex,
                            userId = sub.userId,
                        ),
                    )
                } catch (ex: IllegalArgumentException) {
                    log.debug(
                        "SignalEngine evaluation skipped (code={}, strategy={}): {}",
                        tick.code,
                        sub.strategyDsl.strategy.id,
                        ex.message,
                    )
                    continue
                } catch (ex: Exception) {
                    log.warn(
                        "SignalEngine evaluation error (code={}, strategy={}): {}",
                        tick.code,
                        sub.strategyDsl.strategy.id,
                        ex.message,
                    )
                    continue
                }

            // 3. HOLD는 발행 X
            if (signal.action is SignalAction.Hold) continue

            val event = signal.toEvent()
            publisher.publish(event)
            emitted += signal
            log.info(
                "Signal generated (code={}, strategy={}, action={}, strength={})",
                signal.symbol,
                signal.strategyId,
                signal.action.name,
                signal.strength,
            )
        }
        return emitted
    }
}

/**
 * Signal 도메인 → Kafka payload 변환.
 *
 * reasons는 trace description만 직렬화 (kind·satisfied는 R2에서 구조화).
 */
internal fun Signal.toEvent(): SignalGeneratedEvent =
    SignalGeneratedEvent(
        eventId = eventId,
        strategyId = strategyId,
        symbol = symbol,
        action = action.name,
        strength = strength,
        generatedAt = generatedAt,
        evaluatedAt = evaluatedAt,
        reasons = reasons.map { it.description },
        userId = userId,
    )
