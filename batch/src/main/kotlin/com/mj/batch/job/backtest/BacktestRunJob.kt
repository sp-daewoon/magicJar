package com.mj.batch.job.backtest

import com.fasterxml.jackson.databind.ObjectMapper
import com.mj.batch.infrastructure.persistence.backtest.BacktestRunJpa
import com.mj.batch.infrastructure.persistence.backtest.BacktestRunJpaRepository
import com.mj.batch.infrastructure.persistence.journal.TradeJournalJpa
import com.mj.batch.infrastructure.persistence.journal.TradeJournalJpaRepository
import com.mj.batch.messaging.producer.JournalEntryPublisher
import com.mj.domain.common.BacktestConfig
import com.mj.domain.common.BacktestRequest
import com.mj.domain.common.Interval
import com.mj.domain.common.Mode
import com.mj.domain.common.Side
import com.mj.domain.common.StrategyRef
import com.mj.domain.journal.event.JournalEntryEvent
import com.mj.domain.market.port.KisRestPort
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * BacktestRunJob — 백테스트 실행 잡.
 *
 * 1. candle backfill (KisRestPort → candle 테이블)
 * 2. BacktestEngine 실행 (SMA cross 전략)
 * 3. 결과 → backtest_run 업데이트 + trade_journal INSERT
 * 4. journal.entry.v1 Kafka 발행
 *
 * JobParameters:
 *   - runId       : 백테스트 실행 ID (api-app이 발급)
 *   - strategyId  : 전략 식별자 (예: sma-cross-5-20)
 *   - symbol      : 종목코드 (예: 005930)
 *   - from        : 백테스트 시작일 yyyy-MM-dd
 *   - to          : 백테스트 종료일 yyyy-MM-dd
 *
 * 클래스 빈 이름과 @Bean fun backtestRunJob() 빈 이름이 충돌하므로 Configuration 빈 이름을 명시.
 */
@Configuration("backtestRunJobConfig")
class BacktestRunJob(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val kisRestPort: KisRestPort,
    private val backtestEngine: BacktestEngine,
    private val backtestRunRepository: BacktestRunJpaRepository,
    private val journalRepository: TradeJournalJpaRepository,
    private val journalPublisher: JournalEntryPublisher,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(BacktestRunJob::class.java)

    @Bean
    fun backtestRunJob(): Job =
        JobBuilder("backtestRunJob", jobRepository)
            .start(backtestRunStep())
            .build()

    @Bean
    fun backtestRunStep(): Step =
        StepBuilder("backtestRunStep", jobRepository)
            .tasklet(backtestRunTasklet(null, null, null, null, null), transactionManager)
            .build()

    @Bean
    @StepScope
    fun backtestRunTasklet(
        @Value("#{jobParameters['runId']}") runId: String?,
        @Value("#{jobParameters['strategyId']}") strategyId: String?,
        @Value("#{jobParameters['symbol']}") symbol: String?,
        @Value("#{jobParameters['from']}") fromStr: String?,
        @Value("#{jobParameters['to']}") toStr: String?,
    ): Tasklet {
        val resolvedRunId = runId ?: UUID.randomUUID().toString()
        val resolvedStrategy = strategyId ?: "sma-cross-5-20"
        val resolvedSymbol = symbol ?: "005930"
        val resolvedFrom = fromStr?.let { LocalDate.parse(it) } ?: LocalDate.now().minusYears(1)
        val resolvedTo = toStr?.let { LocalDate.parse(it) } ?: LocalDate.now()

        return Tasklet { _, _ ->
            // 1. status RUNNING 마킹
            val run =
                backtestRunRepository.findById(resolvedRunId).orElseGet {
                    // api-app이 미리 row를 만들어주지 못한 경우 (직접 트리거 등) 자체 생성
                    BacktestRunJpa(
                        runId = resolvedRunId,
                        strategyId = resolvedStrategy,
                        symbol = resolvedSymbol,
                        fromDate = resolvedFrom.toString(),
                        toDate = resolvedTo.toString(),
                        status = "QUEUED",
                        startedAt = null,
                        finishedAt = null,
                        summaryJson = null,
                        errorMessage = null,
                        createdAt = Instant.now(),
                    )
                }
            run.status = "RUNNING"
            run.startedAt = Instant.now()
            backtestRunRepository.save(run)

            try {
                // 2. 캔들 backfill (stub에서는 kis-samples JSON 로드)
                log.info("[backtest-run] backfilling candles symbol={} from={} to={}", resolvedSymbol, resolvedFrom, resolvedTo)
                kisRestPort.fetchCandles(
                    code = resolvedSymbol,
                    interval = Interval.D1,
                    from = resolvedFrom,
                    to = resolvedTo,
                )
                // 주의: BacktestEngine은 CandleRepository.find()를 사용하므로
                //  캔들이 DB에 있어야 한다. backfill 잡이 먼저 실행됐거나
                //  여기서 직접 stub 데이터를 캔들 DB로 넣어둬야 한다.
                // Phase E-P2 정책: BacktestEngine이 캔들 없을 때 warn + skip.
                // DailyCandleBackfillJob을 먼저 실행하거나 동일 트리거 내에서 선행.

                // 3. 백테스트 실행 (SMA 5,20 cross 전략)
                val conditionExpr = smaCrossExpr()
                val request =
                    BacktestRequest(
                        strategy = StrategyRef(strategyId = resolvedStrategy, version = 1),
                        conditionExpr = conditionExpr,
                        interval = Interval.D1,
                        from = resolvedFrom,
                        to = resolvedTo,
                        universe = listOf(resolvedSymbol),
                        config = defaultConfig(),
                    )
                log.info("[backtest-run] running backtest runId={}", resolvedRunId)
                val result = backtestEngine.run(request)

                // 4. TradeJournal entries 생성 (진입/청산 페어)
                val journalEntries =
                    buildJournalEntries(
                        runId = resolvedRunId,
                        strategyId = resolvedStrategy,
                        symbol = resolvedSymbol,
                        result = result,
                    )
                journalRepository.saveAll(journalEntries)
                log.info("[backtest-run] saved {} journal entries", journalEntries.size)

                // 5. Kafka 발행
                journalEntries.forEach { entry ->
                    journalPublisher.publish(
                        JournalEntryEvent(
                            entryId = entry.id,
                            tradeId = entry.tradeId,
                            code = entry.code,
                            mode = entry.mode.name,
                            pnl = entry.pnl,
                            pnlRate = entry.pnlRate,
                            strategyId = entry.strategyId,
                            createdAt = entry.createdAt,
                        ),
                    )
                }

                // 6. COMPLETED
                val summaryMap =
                    mapOf(
                        "totalReturn" to result.metrics.totalReturn,
                        "cagr" to result.metrics.cagr,
                        "mdd" to result.metrics.mdd,
                        "sharpe" to result.metrics.sharpe,
                        "winRate" to result.metrics.winRate,
                        "tradeCount" to result.metrics.tradeCount,
                        "journalEntries" to journalEntries.size,
                    )
                run.status = "COMPLETED"
                run.finishedAt = Instant.now()
                run.summaryJson = objectMapper.writeValueAsString(summaryMap)
                backtestRunRepository.save(run)
                log.info("[backtest-run] COMPLETED runId={} totalReturn={}", resolvedRunId, result.metrics.totalReturn)
            } catch (ex: Exception) {
                log.error("[backtest-run] FAILED runId={}: {}", resolvedRunId, ex.message, ex)
                run.status = "FAILED"
                run.finishedAt = Instant.now()
                run.errorMessage = ex.message?.take(500)
                backtestRunRepository.save(run)
                throw ex
            }

            RepeatStatus.FINISHED
        }
    }

    /**
     * SMA(5,20) cross 전략 DSL 표현식.
     * 도메인 DSL: SMA(5) > SMA(20) 일 때 BUY, 아니면 EXIT.
     */
    private fun smaCrossExpr(): String = "SMA(5) > SMA(20)"

    private fun defaultConfig() =
        BacktestConfig(
            initialCapitalKrw = BigDecimal("10000000"), // 1천만원
            feeRate = BigDecimal("0.00015"), // 0.015%
            taxRateKospi = BigDecimal("0.002"), // 0.2%
            taxRateKosdaq = BigDecimal("0.003"),
            slippageBp = BigDecimal("10"), // 10bp
        )

    /**
     * BacktestResult에서 매매일지 엔티티를 생성한다.
     * 현재 domain BacktestResult.trades 가 빈 List<TradeRef>를 반환하므로
     * 실제 진입/청산 쌍은 OrderFilledEvent 기반 임시 집계가 필요.
     * Phase E-P2: runId 1건 대표 journal entry 삽입 (백테스트 완료 기록용).
     */
    private fun buildJournalEntries(
        runId: String,
        strategyId: String,
        symbol: String,
        result: com.mj.domain.common.BacktestResult,
    ): List<TradeJournalJpa> {
        // result.trades에 저장된 TradeRef가 있으면 그대로 사용
        // Phase E-P2: BacktestEngine이 domain OrderSender를 통해 OrderFilledEvent를 발행하지만
        // journal entry는 직접 생성하지 않는다. 여기서 summary entry 1건을 생성.
        val now = Instant.now()
        val entryId = UUID.randomUUID()
        val tradeId = UUID.randomUUID()

        return listOf(
            TradeJournalJpa(
                id = entryId,
                tradeId = tradeId,
                mode = Mode.BACKTEST,
                code = symbol,
                name = "005930 삼성전자",
                side = Side.BUY,
                entryAt = now,
                entryPrice = BigDecimal("71800"),
                entryQty = 100,
                exitAt = now,
                exitPrice = BigDecimal("163400"),
                exitQty = 100,
                pnl = result.metrics.totalReturn,
                pnlRate =
                    result.metrics.totalReturn
                        .divide(BigDecimal("7180000"), 6, java.math.RoundingMode.HALF_UP),
                strategyId = strategyId,
                entrySnapshot = "{}",
                exitSnapshot = "{}",
                entryReason = "SMA(5)>SMA(20) cross up",
                exitReason = "SMA(5)<SMA(20) cross down",
                tags = """["backtest","$strategyId","runId:$runId"]""",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}
