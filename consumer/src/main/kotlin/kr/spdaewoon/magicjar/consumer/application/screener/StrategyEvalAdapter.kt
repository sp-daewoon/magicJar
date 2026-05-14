package kr.spdaewoon.magicjar.consumer.application.screener

import kr.spdaewoon.magicjar.consumer.application.strategy.JvmStrategyEvaluator
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.strategy.StrategyEvalResult
import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.TimeoutException

/**
 * T-DSL strategy-eval-pool reuse adapter — universe screening 모드 한정 (ADR 0071 §2-3 C-1 + spec §2-2).
 *
 * ### C-1 채택 사유
 * - DSL evaluator 단일 출처 LOCK (ADR 0047 sandbox 정책 reuse).
 * - 별도 screener-eval-pool 신규 생성 폐기 (1차 MVP — 코드 중복 cost ↑).
 * - timeout만 universe screening 한정 500ms 상향 (개별 평가 — 5x).
 *
 * ### Sandbox 정책 (ADR 0047 §2-2 정합)
 * - JvmStrategyEvaluator의 corePoolSize=4 fixed thread pool reuse.
 * - timeout 500ms LOCK (universe screening 한정 — `magicjar.screener.eval-timeout-ms`).
 * - CPU budget·auto-disable은 JvmStrategyEvaluator 내부 정책 reuse 0 (Phase 1은 timeout LOCK만 본 adapter 책임).
 *
 * ### Phase 1 책임
 * - `evaluate(strategyId, ast, candles)`: timeout 500ms 강제 + result wrap or [EvalOutcome] sealed.
 * - sandbox auto-disable은 본 adapter 비범위 (JvmStrategyEvaluator.evaluate 직접 호출 시 트리거 — universe screening은 별도 trigger 없이 graceful skip 의무).
 *
 * ### AI 호출 0 LOCK (ADR 0071 §2-9 + 개발 룰 5)
 * 본 adapter는 LLM provider import 0 — grep 검증 의무.
 */
interface StrategyEvalAdapter {
    /**
     * universe screening 한정 timeout 500ms로 DSL 평가.
     *
     * 결과 sealed:
     * - [EvalOutcome.Success]: 정상 결과 (BUY/SELL/HOLD)
     * - [EvalOutcome.Timeout]: 500ms 초과 (universe 평가 skip 매트릭스 트리거)
     * - [EvalOutcome.Error]: DSL syntax·indicator·sandbox 등 평가 오류
     *
     * @param strategyId 전략 ID
     * @param ast DSL AST root
     * @param candles 평가 시점까지의 candle list (시간순 오름차순)
     * @return [EvalOutcome] sealed (Success·Timeout·Error)
     */
    fun evaluate(
        strategyId: Long,
        ast: DslNode,
        candles: List<Candle>,
    ): EvalOutcome

    /** universe screening 한정 timeout — env override 가능 (ADR 0071 §2-3 LOCK). */
    fun timeoutMs(): Long
}

/**
 * StrategyEvalAdapter 본체 구현 — JvmStrategyEvaluator reuse.
 *
 * Phase 1 LOCK: JvmStrategyEvaluator의 외부 노출 API `evaluate(strategyId, ast, candles)`를 호출하여
 * 내부 strategy-eval-pool + sandbox 정책 reuse. universe screening 한정 timeout 500ms는
 * 본 adapter 위치에서 추가로 caller-thread future wrap을 시도하지 않고 — JvmStrategyEvaluator 자체의
 * timeout 100ms를 reuse한다 (1차 MVP LOCK — 500ms 상향은 env `magicjar.dsl.eval.timeout-ms` 설정으로
 * universe screening 모드용 별도 인스턴스 분리는 backlog).
 *
 * caller (ScreenerEngineImpl)가 동기 inline으로 universe 순회 — 평가 단건 timeout은 JvmStrategyEvaluator 내부.
 */
@Component
class StrategyEvalAdapterImpl(
    private val jvmEvaluator: JvmStrategyEvaluator,
    @Value("\${magicjar.screener.eval-timeout-ms:500}") private val timeoutMs: Long,
) : StrategyEvalAdapter {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun evaluate(
        strategyId: Long,
        ast: DslNode,
        candles: List<Candle>,
    ): EvalOutcome =
        try {
            val result = jvmEvaluator.evaluate(strategyId, ast, candles)
            EvalOutcome.Success(result)
        } catch (e: StrategyException.StrategyTimeoutException) {
            log.debug("Screener DSL eval timeout: strategyId={} err={}", strategyId, e.message)
            EvalOutcome.Timeout
        } catch (e: TimeoutException) {
            log.debug("Screener DSL eval TimeoutException: strategyId={} err={}", strategyId, e.message)
            EvalOutcome.Timeout
        } catch (e: StrategyException.InsufficientCandlesException) {
            // upstream에서 candle 충분성 검증 → 도달 0 (graceful guard)
            EvalOutcome.Error("candle 부족: ${e.message ?: ""}")
        } catch (e: StrategyException) {
            EvalOutcome.Error(e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            log.warn("Screener DSL eval unexpected error: strategyId={} err={}", strategyId, e.message)
            EvalOutcome.Error(e.message ?: e.javaClass.simpleName)
        }

    override fun timeoutMs(): Long = timeoutMs
}

/**
 * DSL 평가 결과 sealed (ADR 0071 §2-7 G-1 정합).
 */
sealed class EvalOutcome {
    data class Success(
        val result: StrategyEvalResult,
    ) : EvalOutcome()

    object Timeout : EvalOutcome()

    data class Error(
        val message: String,
    ) : EvalOutcome()
}
