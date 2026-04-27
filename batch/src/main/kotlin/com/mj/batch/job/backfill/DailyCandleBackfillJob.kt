package com.mj.batch.job.backfill

import com.mj.batch.infrastructure.persistence.market.CandleJpa
import com.mj.batch.infrastructure.persistence.market.CandleJpaRepository
import com.mj.domain.common.Interval
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
import java.time.LocalDate

/**
 * DailyCandleBackfillJob — KisRestPort를 통해 일봉 캔들을 DB에 적재하는 Spring Batch 잡.
 *
 * JobParameters:
 *   - code     : 종목코드 (default: 005930)
 *   - from     : 조회 시작일 yyyy-MM-dd (default: 1년 전)
 *   - to       : 조회 종료일 yyyy-MM-dd (default: 오늘)
 *
 * stub-kis 프로파일: StubKisRestAdapter → kis-samples JSON 반환
 * real-kis 프로파일: RealKisRestAdapter → 실제 KIS REST 호출
 */
@Configuration
class DailyCandleBackfillJob(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val kisRestPort: KisRestPort,
    private val candleJpaRepository: CandleJpaRepository,
) {
    private val log = LoggerFactory.getLogger(DailyCandleBackfillJob::class.java)

    @Bean
    fun dailyCandleBackfillJob(): Job =
        JobBuilder("dailyCandleBackfillJob", jobRepository)
            .start(backfillStep())
            .build()

    @Bean
    fun backfillStep(): Step =
        StepBuilder("backfillStep", jobRepository)
            .tasklet(backfillTasklet(null, null, null), transactionManager)
            .build()

    @Bean
    @StepScope
    fun backfillTasklet(
        @Value("#{jobParameters['code']}") code: String?,
        @Value("#{jobParameters['from']}") fromStr: String?,
        @Value("#{jobParameters['to']}") toStr: String?,
    ): Tasklet {
        val resolvedCode = code ?: "005930"
        val resolvedFrom = fromStr?.let { LocalDate.parse(it) } ?: LocalDate.now().minusYears(1)
        val resolvedTo = toStr?.let { LocalDate.parse(it) } ?: LocalDate.now()

        return Tasklet { _, _ ->
            log.info(
                "[backfill] fetching candles code={} from={} to={}",
                resolvedCode,
                resolvedFrom,
                resolvedTo,
            )

            val candles =
                kisRestPort.fetchCandles(
                    code = resolvedCode,
                    interval = Interval.D1,
                    from = resolvedFrom,
                    to = resolvedTo,
                )

            if (candles.isEmpty()) {
                log.warn("[backfill] no candles returned for code={}", resolvedCode)
                return@Tasklet RepeatStatus.FINISHED
            }

            // upsert: 기존 동일 PK 데이터는 덮어쓰기
            val entities =
                candles.map { c ->
                    CandleJpa(
                        code = c.code,
                        interval = c.interval,
                        openTime = c.openTime,
                        open = c.open,
                        high = c.high,
                        low = c.low,
                        close = c.close,
                        volume = c.volume,
                        tradeCount = c.tradeCount,
                    )
                }

            candleJpaRepository.saveAll(entities)
            log.info("[backfill] inserted/updated {} candle rows for code={}", entities.size, resolvedCode)

            RepeatStatus.FINISHED
        }
    }
}
