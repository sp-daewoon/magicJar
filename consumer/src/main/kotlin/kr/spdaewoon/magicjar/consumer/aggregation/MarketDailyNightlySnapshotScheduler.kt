package kr.spdaewoon.magicjar.consumer.aggregation

import kr.spdaewoon.magicjar.consumer.config.MarketDailySnapshotProperties
import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

/**
 * MarketDailyNightlySnapshotScheduler — ADR 0118 R2 야간 전종목 일봉 today snapshot cron 트리거.
 *
 * 매 영업일 **23:00 KST**(default · ENV `MAGICJAR_MARKET_DAILY_SNAPSHOT_CRON` 외부화) 1회 발사.
 * KRX 정규장 15:30 + NXT after-hour 20:00 마감 후 + KIS 야간 점검(23:40~00:10) 이전 안전 마진.
 *
 * ## 게이트 (2단계)
 *   1. **cron `zone=Asia/Seoul`** — 발사 시각(SpEL `\${...:default}` 로 ENV 외부화).
 *   2. **영업일 가드** — [BusinessDayCalendar.isBusinessDay] (market_calendar DB → 공휴일·임시휴장 skip).
 *      InstrumentMasterSyncScheduler·MarketIndexScheduler 와 동일 KRX 영업일 판정 재사용(정합).
 *   3. **ENV kill-switch** — [MarketDailySnapshotProperties.enabled] (서비스 내부에서 DISABLED 즉시 종료).
 *
 * ## 분업 (★ ADR 0118 §2-C·§2-D LOCK — 본체는 [MarketDailyNightlySnapshotService])
 *   - R2(본 스케줄러, 23:00, 전종목): today 1d **1봉만** 멀티시세 공식 종가로 DO UPDATE(권위·후행 교정).
 *   - DailyCloseFinalizer(20:10, managed watchlist): Redis WS 집계 INSERT(DO NOTHING) — R2 가 나중에 덮음.
 *   - R-BF(MarketDailyBackfillCampaign, admin /run): 과거 10년 1d saveAll(DO NOTHING) — 과거봉 immutable.
 *   R2 는 today 1봉만 전진(과거 gap 채우기 아님). 시스템 며칠 down 시 누락 과거분은 R-BF 로 메운다.
 *
 * ## cron ENV 외부화 함정 (Spring @Scheduled 제약)
 *   @Scheduled(cron=...)는 컴파일 타임 상수 또는 SpEL property placeholder 만 받는다(메서드 호출 불가).
 *   따라서 cron 은 `\${magicjar.market-daily-snapshot.cron:0 0 23 * * *}` placeholder 로 주입한다
 *   ([MarketDailySnapshotProperties.cron] 과 동일 key·default — 정합). zone 은 상수 "Asia/Seoul" 고정.
 *
 * 자본 영향 0 — 데이터 적재만. LLM import 0 (룰 5).
 */
@Component
class MarketDailyNightlySnapshotScheduler(
    private val snapshotService: MarketDailyNightlySnapshotService,
    private val businessDayCalendar: BusinessDayCalendar,
) {
    private val log = LoggerFactory.getLogger(MarketDailyNightlySnapshotScheduler::class.java)
    private val kst = ZoneId.of("Asia/Seoul")

    /**
     * 야간 snapshot cron — default 23:00 KST 매일 발사, 영업일만 실행.
     *
     * cron 은 ENV `MAGICJAR_MARKET_DAILY_SNAPSHOT_CRON`(property `magicjar.market-daily-snapshot.cron`)
     * 으로 외부화 — default `0 0 23 * * *`. 비거래일(주말·공휴일·임시휴장)은 영업일 가드로 skip.
     * ENV kill-switch(`...enabled`)는 서비스가 DISABLED 즉시 종료로 처리(콜 0).
     */
    @Scheduled(cron = "\${magicjar.market-daily-snapshot.cron:0 0 23 * * *}", zone = "Asia/Seoul")
    fun runNightlySnapshot() {
        val today = LocalDate.now(kst)
        if (!businessDayCalendar.isBusinessDay(today)) {
            log.info("MarketDailyNightlySnapshotScheduler skip — KRX 비거래일 ({})", today)
            return
        }
        try {
            val result = snapshotService.runSnapshot()
            log.info(
                "MarketDailyNightlySnapshotScheduler 발사 완료: status={} universe={} upserted={} durationMs={}",
                result.status,
                result.universeSize,
                result.upsertedRows,
                result.durationMs,
            )
        } catch (e: Exception) {
            // 야간 1회 best-effort — 실패해도 다음 영업일 재시도(누락 today 는 다음날 R-BF catch-up 가능).
            log.warn("MarketDailyNightlySnapshotScheduler 발사 실패 (다음 영업일 재시도): err={}", e.message, e)
        }
    }
}
