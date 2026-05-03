package com.mj.batch.job.corp_action

import com.mj.batch.infrastructure.persistence.corp_action.CorpActionJpaRepository
import com.mj.batch.infrastructure.persistence.corp_action.toDomain
import com.mj.batch.infrastructure.persistence.market.CandleJpaRepository
import com.mj.batch.infrastructure.persistence.market.InstrumentJpaRepository
import com.mj.domain.market.corp_action.AdjustedPriceCalculator
import com.mj.domain.market.corp_action.port.CorpActionRepository
import com.mj.domain.market.corp_action.port.KisCorpActionPort
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Corp Action 일일 backfill + adjusted price 재계산 배치 (ADR 0038 T4).
 *
 * cron: 매일 06:30 KST (21:30 UTC) — 장 시작(09:00) 전 사전 정합 완료
 *
 * 흐름:
 * 1. 전체 활성 종목 목록 조회 (instrument.active=true)
 * 2. 종목별 KisCorpActionPort.fetchAll() 호출 (최근 1년 범위)
 * 3. Corp Action upsert (멱등)
 * 4. AdjustedPriceCalculator로 candle.adjusted 컬럼 재계산 (retroactive)
 * 5. applied_at 갱신
 *
 * profile 분기:
 * - mock: 비활성 (@ConditionalOnProperty)
 * - dev·prd: 활성 (실제 KIS API 호출)
 *
 * 룩어헤드 차단:
 * - retroactive 재계산은 이 job에서만 수행
 * - backtest 런타임에서는 재계산 금지 (AdjustedPriceCalculator를 backtest에서 직접 호출 X)
 */
@Component
@Profile("dev | prd")
class CorpActionBackfillJob(
    private val kisCorpActionPort: KisCorpActionPort,
    private val corpActionRepository: CorpActionRepository,
    private val corpActionJpaRepository: CorpActionJpaRepository,
    private val instrumentJpaRepository: InstrumentJpaRepository,
    private val candleJpaRepository: CandleJpaRepository,
    private val adjustedPriceCalculator: AdjustedPriceCalculator,
) {
    private val log = LoggerFactory.getLogger(CorpActionBackfillJob::class.java)
    private val zone = ZoneId.of("Asia/Seoul")

    /**
     * 매일 06:30 KST 자동 실행.
     * cron: "0 30 6 * * *" (초 분 시 일 월 요일)
     */
    @Scheduled(cron = "0 30 6 * * *", zone = "Asia/Seoul")
    @Transactional
    fun run() {
        log.info("[corp-action-backfill] starting corp action backfill at {}", Instant.now())

        val today = LocalDate.now(zone)
        val from = today.minusYears(1)
        val to = today

        // 활성 종목 목록
        val instruments = instrumentJpaRepository.findByActiveTrue()
        if (instruments.isEmpty()) {
            log.warn("[corp-action-backfill] no active instruments found")
            return
        }

        log.info("[corp-action-backfill] processing {} active instruments", instruments.size)

        var totalFetched = 0
        var totalApplied = 0

        instruments.forEach { instrument ->
            try {
                // 1. KIS에서 Corp Action 조회
                val corpActions = kisCorpActionPort.fetchAll(instrument.code, from, to)

                if (corpActions.isNotEmpty()) {
                    // 2. DB upsert (멱등)
                    corpActionRepository.upsertAll(corpActions)
                    totalFetched += corpActions.size
                    log.debug(
                        "[corp-action-backfill] fetched {} corp actions for {}",
                        corpActions.size,
                        instrument.code,
                    )

                    // 3. retroactive adjusted price 재계산
                    val applied = recalculateAdjusted(instrument.code)
                    totalApplied += applied
                }

                // 레이트리밋 대응: KIS API 초당 20건 제한 → 50ms 지연
                Thread.sleep(50)
            } catch (ex: Exception) {
                log.error(
                    "[corp-action-backfill] error processing instrument={} — {}",
                    instrument.code,
                    ex.message,
                )
                // 개별 종목 실패는 전체 job 중단 없이 계속
            }
        }

        log.info(
            "[corp-action-backfill] completed — fetched={} applied={}",
            totalFetched,
            totalApplied,
        )
    }

    /**
     * 특정 종목의 모든 캔들에 대해 adjusted price를 retroactive 재계산한다.
     *
     * @return 업데이트된 candle row 수
     */
    fun recalculateAdjusted(instrumentCode: String): Int {
        // 해당 종목의 모든 Corp Action 조회 (exDate ASC)
        val corpActions =
            corpActionJpaRepository
                .findByInstrumentCodeAndExDateLessThanEqualOrderByExDateAsc(
                    instrumentCode,
                    LocalDate.now(zone).plusYears(1),
                ).map { it.toDomain() }

        if (corpActions.isEmpty()) return 0

        // 해당 종목의 일봉 캔들 조회
        val candles =
            candleJpaRepository
                .findByCodeAndIntervalOrderByOpenTimeAsc(instrumentCode, com.mj.domain.common.Interval.D1)

        if (candles.isEmpty()) return 0

        // batch 계산
        val priceDatePairs =
            candles.map { candle ->
                val date = candle.openTime.atZone(zone).toLocalDate()
                date to candle.close
            }

        val results = adjustedPriceCalculator.calculateBatch(priceDatePairs, corpActions)

        // 캔들 업데이트
        results.forEachIndexed { idx, result ->
            val candle = candles[idx]
            val factor = result.adjustmentFactor

            candle.closeAdjusted = result.adjustedClose
            candle.openAdjusted = candle.open.multiply(factor)
            candle.highAdjusted = candle.high.multiply(factor)
            candle.lowAdjusted = candle.low.multiply(factor)
            candle.volumeAdjusted =
                if (factor.compareTo(java.math.BigDecimal.ONE) == 0) {
                    candle.volume
                } else {
                    // 분할 시 거래량은 역비율 (주가 낮아지면 거래량 증가)
                    val invFactor =
                        java.math.BigDecimal.ONE
                            .divide(factor, 8, java.math.RoundingMode.HALF_UP)
                    (candle.volume.toBigDecimal().multiply(invFactor)).toLong()
                }
            candle.adjustmentFactor = factor
        }

        candleJpaRepository.saveAll(candles)

        // Corp Action applied_at 갱신
        corpActionJpaRepository.markApplied(instrumentCode, Instant.now())

        log.debug(
            "[corp-action-backfill] recalculated {} candles for {}",
            candles.size,
            instrumentCode,
        )

        return candles.size
    }
}
