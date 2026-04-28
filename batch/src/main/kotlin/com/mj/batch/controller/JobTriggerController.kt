package com.mj.batch.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
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

@Tag(
    name = "BatchJobTrigger",
    description = """
        Spring Batch 잡 HTTP 트리거.

        api-app(8080)의 `BacktestService`가 내부적으로 `POST /jobs/backtest`를 호출해 잡을 기동한다.
        운영자/개발자는 이 Swagger를 통해 직접 잡을 돌려 KIS 어댑터 동작·캔들 적재·백테스트 결과를 확인할 수 있다.

        | 잡 | 트리거 | 핵심 동작 |
        |---|---|---|
        | DailyCandleBackfillJob | `POST /jobs/backfill` | KIS 어댑터에서 일봉 받아 `candle` 테이블 INSERT |
        | BacktestRunJob | `POST /jobs/backtest` (선행: backfill) | DB 캔들로 전략 실행 후 매매일지 INSERT + Kafka publish |

        > KIS 어댑터는 Profile로 분기. 기본 `stub-kis`는 fixture 응답, `real-kis`는 실 KIS REST.
    """,
)
@RestController
@RequestMapping("/jobs")
class JobTriggerController(
    private val jobLauncher: JobLauncher,
    private val jobRepository: JobRepository,
    @Qualifier("dailyCandleBackfillJob") private val dailyCandleBackfillJob: Job,
    @Qualifier("backtestRunJob") private val backtestRunJob: Job,
) {
    private val log = LoggerFactory.getLogger(JobTriggerController::class.java)

    @Schema(description = "백테스트 잡 트리거 요청. 모든 필드 기본값 있음(데모 친화).")
    data class BacktestTriggerRequest(
        @field:Schema(description = "백테스트 실행 식별자 (api-app이 부여)", example = "8f6c1c10-9d0e-4b2c-a5b6-1234567890ab", required = true)
        val runId: String,
        @field:Schema(description = "전략 식별자", example = "sma-cross-5-20", defaultValue = "sma-cross-5-20")
        val strategyId: String = "sma-cross-5-20",
        @field:Schema(description = "KRX 종목 코드", example = "005930", defaultValue = "005930")
        val symbol: String = "005930",
        @field:Schema(description = "시작일 (기본: 1년 전 오늘)", example = "2024-04-28")
        val from: String = LocalDate.now().minusYears(1).toString(),
        @field:Schema(description = "종료일 (기본: 오늘)", example = "2025-04-28")
        val to: String = LocalDate.now().toString(),
    )

    @Schema(description = "캔들 backfill 잡 트리거 요청. 단독 실행용.")
    data class BackfillTriggerRequest(
        @field:Schema(description = "KRX 종목 코드", example = "005930", defaultValue = "005930")
        val code: String = "005930",
        @field:Schema(description = "시작일 (기본: 1년 전)", example = "2024-04-28")
        val from: String = LocalDate.now().minusYears(1).toString(),
        @field:Schema(description = "종료일 (기본: 오늘)", example = "2025-04-28")
        val to: String = LocalDate.now().toString(),
    )

    @Operation(
        summary = "백테스트 잡 트리거 (backfill → backtest 체인)",
        description = """
            2-step 체인 실행:
            1. **DailyCandleBackfillJob** — KIS 어댑터에서 [from, to] 구간 일봉을 받아 `candle` 테이블 적재.
            2. **BacktestRunJob** — DB 캔들로 전략 실행, `trade_journal` INSERT, `magicjar.backtest.result.v1` Kafka publish.

            JobLauncher는 동기 실행이며 api-app은 fire-and-forget RestClient로 호출(논블로킹).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "202", description = "잡 등록/실행 시작 (jobExecutionId·status 반환)"),
            ApiResponse(responseCode = "500", description = "JobLauncher 예외 (중복 파라미터·DB 장애 등)"),
        ],
    )
    @PostMapping("/backtest")
    fun triggerBacktest(
        @RequestBody req: BacktestTriggerRequest,
    ): ResponseEntity<Map<String, String>> {
        log.info("[job-trigger] backtest request runId={} symbol={}", req.runId, req.symbol)

        runBackfill(req.symbol, req.from, req.to)

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

    @Operation(
        summary = "캔들 backfill 단독 트리거",
        description = """
            KIS 어댑터에서 일봉만 받아 `candle` 테이블 적재. 백테스트는 실행하지 않는다.

            차트 화면이 백창인 경우 가장 먼저 시도해볼 트리거 — 캔들 시드 검증용.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "202", description = "잡 실행 시작"),
            ApiResponse(responseCode = "500", description = "잡 예외"),
        ],
    )
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
