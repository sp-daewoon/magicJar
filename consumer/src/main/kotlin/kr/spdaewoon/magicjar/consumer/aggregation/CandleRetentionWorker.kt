package kr.spdaewoon.magicjar.consumer.aggregation

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerCandleJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant

/**
 * 1m/3m 캔들 시간 기반 retention worker (2026-07-03 감사 §2-① — retention 잡 신설).
 *
 * ### 배경
 * 1m 캔들은 종목당 하루 ~720행 무한 누적됐고 시간 기반 삭제 경로가 0이었다(감사 실측).
 * BoundaryAggregator 풀스캔은 범위 쿼리로 절단했으나, 테이블 자체의 단조 증가(디스크·인덱스 팽창)는
 * retention 삭제로만 해소된다. 3m도 1m 파생 최다 행 interval이라 함께 대상에 둔다.
 *
 * ### 안전 게이트 (opt-in — 사용자 데이터 삭제)
 * - `magicjar.candle.retention.enabled=true`(default **false**)일 때만 동작 — 명시 opt-in.
 * - 삭제 대상 interval 은 [RETENTION_INTERVALS](1m·3m) 화이트리스트 LOCK — 그 외 interval 절대 삭제 0
 *   (MarketDailyWipeService MIGRATE_WIPE_INTERVALS 동일 패턴).
 * - cutoff = 실행 시각 - `magicjar.candle.retention.days`(default 90) — cutoff 이후(최근) row 무손상.
 * - `ctid` 청크 삭제([ConsumerCandleJpaRepository.deleteByIntervalInAndTsBeforeBatch]) + 청크당 독립
 *   트랜잭션(TransactionTemplate) — long-transaction 회피. run당 청크 상한(MAX_BATCHES_PER_RUN)으로
 *   폭주 차단(잔여분은 다음 날 run 이월).
 *
 * ### 스케줄
 * 매일 03:40 KST — 장외·야간 배치(market-daily-backfill 등)와 위상 분리. `@Modifying` native query 는
 * 트랜잭션 필수 — @Scheduled 진입이라 TransactionTemplate 로 청크 단위 정밀 래핑(TickToMinuteAggregator 정합).
 *
 * ### retention.days 운용 주의
 * `magicjar.candle.one-minute.default-lookback-trading-days`(126 거래일 ≈ 6개월)보다 짧게 잡으면
 * 1m 장기 backfill/reconciliation(ADR 0105)이 삭제 구간을 재수집하려 시도할 수 있다 — 활성 시
 * retention.days 를 lookback 이상으로 맞추는 것을 권장(default 90은 opt-in 후 사용자 결정 대상).
 */
@Component
class CandleRetentionWorker(
    private val candleRepository: ConsumerCandleJpaRepository,
    private val transactionTemplate: TransactionTemplate,
    @Value("\${magicjar.candle.retention.enabled:false}")
    private val retentionEnabled: Boolean = false,
    @Value("\${magicjar.candle.retention.days:90}")
    private val retentionDays: Long = 90L,
) {
    private val log = LoggerFactory.getLogger(CandleRetentionWorker::class.java)

    companion object {
        /**
         * 삭제 대상 interval 화이트리스트 LOCK — 1m·3m 한정.
         * 1d/1wk 등 KIS REST 재생성 가능 패밀리는 별도 캠페인(ADR 0118) 책임이며 본 worker 대상 아님.
         */
        private val RETENTION_INTERVALS = listOf("1m", "3m")

        /** ctid 청크 크기 — deleteByIntervalInBatch 계열과 동일 규모(짧은 트랜잭션 유지). */
        private const val BATCH_SIZE = 5000

        /** run당 청크 상한 — 최초 활성 시 대량 잔여분 폭주 차단(잔여는 다음 run 이월). */
        private const val MAX_BATCHES_PER_RUN = 200
    }

    /**
     * 매일 03:40 KST — retention 삭제 1 run.
     * enabled=false(default)면 즉시 no-op (로그 0 — 매일 노이즈 방지).
     */
    @Scheduled(cron = "0 40 3 * * *", zone = "Asia/Seoul")
    fun purgeExpiredMinuteCandles() {
        if (!retentionEnabled) return
        if (retentionDays <= 0L) {
            log.warn("candle retention skip — retention.days({}) 비정상(≤0). 설정 확인 필요.", retentionDays)
            return
        }
        val cutoff = Instant.now().minus(Duration.ofDays(retentionDays))
        log.info(
            "candle retention 시작: intervals={} cutoff={} (retention.days={})",
            RETENTION_INTERVALS,
            cutoff,
            retentionDays,
        )
        var totalDeleted = 0L
        var batches = 0
        try {
            while (batches < MAX_BATCHES_PER_RUN) {
                val deleted =
                    transactionTemplate.execute {
                        candleRepository.deleteByIntervalInAndTsBeforeBatch(
                            intervals = RETENTION_INTERVALS,
                            cutoff = cutoff,
                            batchSize = BATCH_SIZE,
                        )
                    } ?: 0
                totalDeleted += deleted
                batches++
                if (deleted < BATCH_SIZE) break
            }
        } catch (e: Exception) {
            log.error(
                "candle retention 실패(부분 진행분 {}행은 유지 — 청크 독립 트랜잭션): err={}",
                totalDeleted,
                e.message,
                e,
            )
            return
        }
        if (batches >= MAX_BATCHES_PER_RUN) {
            log.warn(
                "candle retention run 상한 도달 — 잔여분 다음 run 이월: deleted={}행 batches={}",
                totalDeleted,
                batches,
            )
        } else {
            log.info("candle retention 완료: deleted={}행 batches={} cutoff={}", totalDeleted, batches, cutoff)
        }
    }
}
