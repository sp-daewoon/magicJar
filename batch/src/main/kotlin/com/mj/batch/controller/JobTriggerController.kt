package com.mj.batch.controller

import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParameter
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.batch.core.repository.JobRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * Batch 잡 HTTP 트리거 엔드포인트.
 *
 * api-app의 BacktestService가 `POST /jobs/backtest`를 호출해 잡을 기동한다.
 * Spring Batch JobLauncher는 동기 실행 — api-app에서 비동기 호출(RestClient fire-and-forget).
 */
@RestController
@RequestMapping("/jobs")
class JobTriggerController(
    private val jobLauncher: JobLauncher,
    private val jobRepository: JobRepository,
    @Qualifier("dailyCandleBackfillJob") private val dailyCandleBackfillJob: Job,
    @Qualifier("backtestRunJob") private val backtestRunJob: Job,
) {
    private val log = LoggerFactory.getLogger(JobTriggerController::class.java)

    data class BacktestTriggerRequest(
        val runId: String,
        val strategyId: String = "sma-cross-5-20",
        val symbol: String = "005930",
        val from: String = LocalDate.now().minusYears(1).toString(),
        val to: String = LocalDate.now().toString(),
    )

    data class BackfillTriggerRequest(
        val code: String = "005930",
        val from: String = LocalDate.now().minusYears(1).toString(),
        val to: String = LocalDate.now().toString(),
    )

    /**
     * POST /jobs/backtest — api-app이 백테스트 잡을 트리거.
     *
     * 흐름:
     * 1. DailyCandleBackfillJob 먼저 실행 (stub 어댑터 → DB 캔들 적재)
     * 2. BacktestRunJob 실행 (DB 캔들 읽어 백테스트 + journal INSERT)
     */
    @PostMapping("/backtest")
    fun triggerBacktest(
        @RequestBody req: BacktestTriggerRequest,
    ): ResponseEntity<Map<String, String>> {
        log.info("[job-trigger] backtest request runId={} symbol={}", req.runId, req.symbol)

        // Step 1: candle backfill
        runBackfill(req.symbol, req.from, req.to)

        // Step 2: backtest
        val backtestParams =
            JobParameters(
                mapOf(
                    "runId" to JobParameter(req.runId, String::class.java),
                    "strategyId" to JobParameter(req.strategyId, String::class.java),
                    "symbol" to JobParameter(req.symbol, String::class.java),
                    "from" to JobParameter(req.from, String::class.java),
                    "to" to JobParameter(req.to, String::class.java),
                    "triggeredAt" to JobParameter(System.currentTimeMillis(), Long::class.java),
                ),
            )

        val execution = jobLauncher.run(backtestRunJob, backtestParams)
        log.info("[job-trigger] backtestRunJob launched executionId={}", execution.id)

        return ResponseEntity.accepted().body(
            mapOf(
                "runId" to req.runId,
                "jobExecutionId" to execution.id.toString(),
                "status" to execution.status.name,
            ),
        )
    }

    /** POST /jobs/backfill — 캔들 backfill 단독 트리거. */
    @PostMapping("/backfill")
    fun triggerBackfill(
        @RequestBody req: BackfillTriggerRequest,
    ): ResponseEntity<Map<String, String>> {
        log.info("[job-trigger] backfill request code={} from={} to={}", req.code, req.from, req.to)
        val execution = runBackfill(req.code, req.from, req.to)
        return ResponseEntity.accepted().body(
            mapOf(
                "jobExecutionId" to execution.id.toString(),
                "status" to execution.status.name,
            ),
        )
    }

    private fun runBackfill(
        code: String,
        from: String,
        to: String,
    ): org.springframework.batch.core.JobExecution {
        val backfillParams =
            JobParameters(
                mapOf(
                    "code" to JobParameter(code, String::class.java),
                    "from" to JobParameter(from, String::class.java),
                    "to" to JobParameter(to, String::class.java),
                    "triggeredAt" to JobParameter(System.currentTimeMillis(), Long::class.java),
                ),
            )
        val execution = jobLauncher.run(dailyCandleBackfillJob, backfillParams)
        log.info("[job-trigger] backfillJob launched executionId={}", execution.id)
        return execution
    }
}
