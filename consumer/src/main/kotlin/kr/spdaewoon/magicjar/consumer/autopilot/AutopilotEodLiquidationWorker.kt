package kr.spdaewoon.magicjar.consumer.autopilot

import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

/**
 * magicjar-autopilot **EOD(장마감 전) 청산 워커** — ADR 0129 레버 1.
 *
 * ## 정책 (ADR 0129 레버 1 LOCK)
 * `allow_overnight=false` AND `owner=AUTOPILOT` 전략의 보유 포지션을 장마감 전 cutoff(default 15:15 KST)에
 * **시장가 SELL 청산**한다. 늦은 오후 진입분이 당일 청산되지 못하고 오버나잇 갭 리스크에 노출되는 것을 회피한다
 * (2026-06-23 080220 −16,137 = 손실 55% 복기). **USER/SYSTEM 전략·allow_overnight=true AUTOPILOT 전략은 절대
 * 비대상**([AutopilotLiquidationService.liquidateNoOvernightAutopilot] 의 repository 쿼리가 owner+토글로 격리).
 *
 * ## 청산 메커니즘 (신규 KIS endpoint 0)
 * 기존 [AutopilotLiquidationService.liquidateNoOvernightAutopilot] → [AutopilotLiquidator] → OrderIntent 경로 재사용.
 * 멱등은 ① 청산 service 내부 [kr.spdaewoon.magicjar.consumer.order.AutoOrderIdempotencyKeys.autopilotLiquidation]
 * (reason+ts 차원) + ② 본 워커의 **KST 거래일 1회 가드**([lastLiquidatedDate]) 이중으로 흡수한다.
 *
 * ## 스케줄 (fixedDelay 폴링 + LocalTime cutoff 비교 + 거래일 게이트)
 * cron 고정 대신 [EntryTimeoutCancelWorker] 패턴(fixedDelay + 시각 비교)을 따른다 — 휴장일 게이트(ADR 0104)를
 * [BusinessDayCalendar] 로 결합하기 위함이다. tick 마다:
 *  1. enabled·KST 거래일·cutoff 도달·당일 미발사 4 게이트를 모두 통과해야 청산.
 *  2. 통과 시 [AutopilotLiquidator.liquidateNoOvernightAutopilot] 1회 발사 + [lastLiquidatedDate] 마킹(멱등).
 *
 * application.yml `magicjar.autotrading.eod-liquidation.enabled` toggle(default true). 비활성 시 no-op = 즉시 롤백.
 *
 * ## 롱온리·격리 LOCK
 * 청산(SELL)만 발행 — 보유분을 닫는 행위(공매도 0, 롱온리 §13). BUY 억제 게이트(late-entry)는 매수 경로
 * ([kr.spdaewoon.magicjar.consumer.risk.SignalGeneratedKafkaListener.applyAutopilotBuyGuard])가 별도 담당.
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 (ADR 0023 §2-4)
 */
@Component
class AutopilotEodLiquidationWorker(
    private val autopilotLiquidatorProvider: ObjectProvider<AutopilotLiquidator>,
    private val businessDayCalendarProvider: ObjectProvider<BusinessDayCalendar>,
    private val autopilotPropertiesProvider: ObjectProvider<MagicjarAutopilotProperties>,
    @Value("\${magicjar.autotrading.eod-liquidation.enabled:true}")
    private val enabled: Boolean = true,
    @Value("\${magicjar.autotrading.eod-liquidation.cutoff:15:15}")
    private val cutoffRaw: String = "15:15",
    private val clock: Clock = Clock.system(KST),
) {
    private val log = LoggerFactory.getLogger(AutopilotEodLiquidationWorker::class.java)

    /** 당일 1회 멱등 가드 — 마지막 청산 발사 KST 거래일. 같은 거래일 재발사 0(중복 청산 방지). */
    private val lastLiquidatedDate = AtomicReference<LocalDate?>(null)

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        const val LIQUIDATION_REASON: String = "EOD_NO_OVERNIGHT"
        private val FALLBACK_CUTOFF: LocalTime = LocalTime.of(15, 15)
    }

    /**
     * 30초 cron — fixedDelay. cutoff(KST) 도달 + 거래일 + 당일 미발사 시 1회 청산. 평상시 즉시 종료(부담 0).
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    fun eodTick() {
        if (!enabled) return
        // autopilot enabled=false 면 루프 자체가 비활성 — 청산 대상도 0이므로 호출 절약(쿼리도 격리되어 무해하나 명시적 skip).
        val properties = autopilotPropertiesProvider.getIfAvailable()
        if (properties != null && !properties.enabled) return

        val nowKst = LocalDate.now(clock)
        val nowTime = LocalTime.now(clock)
        val cutoff = parseCutoff()

        // cutoff 미도달 → skip.
        if (nowTime.isBefore(cutoff)) return
        // 당일 이미 발사 → 멱등 skip.
        if (lastLiquidatedDate.get() == nowKst) return
        // 휴장일 → skip(거래일 게이트 — ADR 0104). calendar 미가용 시 보수적으로 주말만 거른 fallback 은 calendar 책임.
        val calendar = businessDayCalendarProvider.getIfAvailable()
        if (calendar != null && !runCatching { calendar.isBusinessDay(nowKst) }.getOrDefault(true)) {
            log.debug("autopilot EOD 청산 skip — 휴장일: date={}", nowKst)
            // 휴장일도 당일 마킹해 같은 날 재평가 비용 0(다음 거래일에 자연 리셋 — date 비교).
            lastLiquidatedDate.set(nowKst)
            return
        }

        val liquidator = autopilotLiquidatorProvider.getIfAvailable()
        if (liquidator == null) {
            log.warn("autopilot EOD 청산 skip — AutopilotLiquidator 미합류: date={}", nowKst)
            return
        }

        // 멱등 마킹을 발사 **전**에 set — 발사 중 다음 tick(30초)이 겹쳐도 이중 발사 0(청산 service 내부 멱등 키가 2차 방어).
        lastLiquidatedDate.set(nowKst)
        log.warn(
            "autopilot EOD 청산 발사 — allow_overnight=false owner=AUTOPILOT 보유분 시장가 청산: date={} cutoff={} reason={}",
            nowKst,
            cutoff,
            LIQUIDATION_REASON,
        )
        runCatching {
            val outcome = liquidator.liquidateNoOvernightAutopilot(userId = null, reason = LIQUIDATION_REASON)
            log.warn(
                "autopilot EOD 청산 완료 — 종목 {}개·SELL intent {}건: date={}",
                outcome.liquidatedCodes.size,
                outcome.totalSellIntents,
                nowKst,
            )
        }.onFailure { e ->
            // 실패해도 당일 마킹은 유지(다음 거래일 재시도) — 같은 날 반복 발사로 인한 이중 청산 위험을 멱등으로 우선 봉인.
            //   (체결 미발행분은 다음 거래일·수동 정합으로 회복. 자본 안전 방향 = 이중 청산 회피 > 1회 누락.)
            log.error("autopilot EOD 청산 실패(당일 마킹 유지·다음 거래일 재시도): date={} err={}", nowKst, e.message, e)
        }
    }

    /** cutoff 파싱 — "HH:mm". 파싱 실패 시 fallback 15:15(보수적 — 너무 이르거나 늦지 않게). */
    private fun parseCutoff(): LocalTime =
        runCatching { LocalTime.parse(cutoffRaw.trim()) }
            .getOrElse {
                log.warn("autopilot EOD cutoff 파싱 실패 — fallback {}: raw={}", FALLBACK_CUTOFF, cutoffRaw)
                FALLBACK_CUTOFF
            }
}
