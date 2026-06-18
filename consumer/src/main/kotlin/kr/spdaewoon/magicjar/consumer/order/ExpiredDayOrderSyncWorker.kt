package kr.spdaewoon.magicjar.consumer.order

import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEvent
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEventType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderInquireDailyCcldPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderAuditEventRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderInquireResult
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 만료 일중(day) 주문 동기화 worker — canary D2 hotfix #1 (오늘 09:01~09:04 incident 근본 fix).
 *
 * ## 결함 (이 hotfix 가 닫는 것)
 * KIS 당일 LIMIT 주문(예: 익절 SELL)은 장 마감으로 **만료**되지만, 클라이언트는 만료 통보를 못 받아
 * DB `order_request.status` 가 `ACK`(또는 `REQUESTED`)로 **영구 잔존**한다. 다음 날 매도 시그널이 들어오면
 * [kr.spdaewoon.magicjar.consumer.risk.SignalGeneratedKafkaListener.publishSell] 의
 * "SELL 익절 LIMIT skip (이미 정합 auto-tp open)" 분기가 그 잔존 ACK 를 살아있는 익절 선주문으로 **오판**해
 * 실제 매도를 영원히 skip 한다(오늘 incident — 수동 정정으로 해소). 잔존 ACK 가 [RestingSellOrderCanceller]
 * 선취소 대상도 아니어서(KIS 에는 이미 없음) 청산이 마비된다.
 *
 * ## 보정 (신규 로직 최소 — 기존 컴포넌트 재사용)
 * 전일 이전(`requestedAt < 오늘 0시 KST`) 의 미종결(ACK·REQUESTED) day-order 를 발견하면 KIS 일별체결조회
 * ([KisOrderInquireDailyCcldPort.inquireByOrderNo] — [KisExecutionBackfillService] 와 동일 KIS-first 출처)로 대조해:
 *  - 전량 체결(Found, filled ≥ orderQty) → 그대로 FILLED 동기화(누락 체결 흡수는 별 worker 책임이라 여기선 status 만).
 *  - 부분/미체결(Found, filled < orderQty) 또는 미접수(NotFound) → 잔량 만료 → **CANCELLED + 사유** 동기화.
 *  - KIS 응답 실패(Failed) → 다음 tick 재시도(상태 불변).
 *
 * day-order 만료 동기화는 실돈 여부와 무관(조회·상태 보정만)하므로 MODE 분기 없이 모든 모드 동작.
 *
 * ## canary D5 RT_CD_7 근본 fix (2026-06-18 후성 익절 deadlock — issue #1019)
 * 위 KIS 일별체결조회의 INQR_STRT_DT 가 `today.minusDays(2)` 고정이라 KIS TTTC0081R 이 `rt_cd=7:조회일자를
 * 확인하십시오` 로 거부 → 좀비 ACK 영구 잔존 → 매분 익절 SELL 이 "이미 정합 auto-tp open" 으로 영구 skip →
 * 청산 마비. 본 hotfix 는 (1) 조회 시작일을 **주문 발주일(requestedAt KST date) anchor** 로 정정(고정 today-2
 * 폐기)하고, (2) 그럼에도 KIS 가 date 유효성을 영구 거부하면 과거 발주 day-order 를 N회 후 EXPIRE 하는 2차
 * 안전장치를 둔다([processRow] 참고). EXPIRE 는 이미 청산 의도된 stale SELL 의 status 정정이라 오버셀 risk 0.
 *
 * ## 실행 시점 (장 마감 후/개장 전)
 * fixedDelay cron 30분 — 만료는 장 마감(15:30) 이후 발생하고 다음 개장(09:00) 전까지 정리되면 충분하므로
 * 빈번할 필요 없다(KIS rate limit 보호). 장마감+grace 전에는 전일 이전 주문만, 이후에는 당일 장마감 전
 * 미종결 주문까지 KIS 대조한다. 미종결 0건이면 즉시 종료(평상시 부담 0).
 *
 * ## ENV
 * `magicjar.order.day-order-expiry.enabled`(default true) — 본 hotfix 가 도입하는 유일한 신규 토글.
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 운영 가드 (ADR 0023 §2-4 + ADR 0084 P0-2 정합)
 */
@Component
class ExpiredDayOrderSyncWorker(
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
    private val orderAuditEventRepositoryProvider: ObjectProvider<OrderAuditEventRepository>,
    private val kisOrderInquireDailyCcldPort: KisOrderInquireDailyCcldPort,
    private val systemEventLogger: SystemEventLogger,
    @Value("\${magicjar.order.day-order-expiry.enabled:true}")
    private val enabled: Boolean = true,
    @Value("\${magicjar.order.day-order-expiry.market-close-grace-minutes:20}")
    private val marketCloseGraceMinutes: Long = 20,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val log = LoggerFactory.getLogger(ExpiredDayOrderSyncWorker::class.java)

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /** 미종결 day-order 추적 상태 — KIS 가 만료시켰을 수 있으나 DB 가 살아있는 상태. */
        internal val STALE_STATUSES = listOf(OrderStatus.ACK, OrderStatus.REQUESTED)

        /** 한 tick 처리 상한 — KIS rate limit 보호. */
        internal const val BATCH_LIMIT = 50

        /** 정규장 day-order 만료 기준. grace 이후에는 당일 장마감 전 주문도 만료 대조 대상이다. */
        private val REGULAR_MARKET_CLOSE: LocalTime = LocalTime.of(15, 30)

        private const val SYSTEM_EVENT_ACTOR = "ExpiredDayOrderSyncWorker"
        private const val EXPIRE_REASON = "DAY_ORDER_EXPIRED:day-order 장마감 만료 (KIS 일별체결 대조)"

        /** inner(TTTC0081R/VTTC0081R) 3개월 이내 경계. start 가 이보다 과거면 clamp(date 유효성 보호). */
        private const val INNER_LOOKBACK_DAYS = 90L

        /**
         * KIS date-error(RT_CD_7류) 연속 N회 도달 시 과거 발주 day-order EXPIRE fallback 임계.
         * 영구 retry 로 청산이 마비되는 것보다, 이미 만료 확실한 stale SELL 의 status 정정이 안전하다.
         */
        internal const val DATE_ERROR_EXPIRE_THRESHOLD = 3

        /**
         * KIS 응답 실패가 "조회일자 유효성 거부(RT_CD_7류)" 인지 식별.
         * [KisOrderInquireDailyCcldHttpAdapter] 는 rt_cd!=0 을 `RT_CD_<n>:<msg>` 로 wrap 한다.
         * rt_cd=7 또는 메시지에 "조회일자" 가 포함되면 date-error 로 판정(폭 제한·일시 5xx·timeout 과 구분).
         */
        internal fun isKisDateError(cause: String): Boolean = cause.startsWith("RT_CD_7:") || cause.contains("조회일자")
    }

    /**
     * RT_CD_7 fallback 연속 카운터(orderId → 연속 date-error 횟수). 정상 응답·일반 Failed·EXPIRE 시 reset.
     * 단일 worker 인스턴스 내 cron tick 간 상태 — 영속 불필요(만료 확정되면 row 가 종결되어 더 안 잡힘).
     */
    private val dateErrorFailCount = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    /**
     * 30분 cron (fixedDelay). 장 마감 후 누적된 전일 만료 day-order 를 다음 개장 전까지 정리한다.
     * initialDelay 로 부팅 직후 부하 회피.
     */
    @Scheduled(fixedDelay = 1_800_000, initialDelay = 120_000)
    fun expireTick() {
        if (!enabled) {
            log.debug("ExpiredDayOrderSyncWorker — 비활성(magicjar.order.day-order-expiry.enabled=false) skip")
            return
        }
        val repo = orderRequestRepositoryProvider.getIfAvailable()
        if (repo == null) {
            log.warn("ExpiredDayOrderSyncWorker — OrderRequestRepository 미합류 skip")
            return
        }

        val now = Instant.now(clock)
        val staleCutoff = staleRequestedAtCutoff(now)

        val stale =
            STALE_STATUSES.flatMap { status ->
                runCatching { repo.findByStatusAndRequestedAtBefore(status, staleCutoff, BATCH_LIMIT) }
                    .onFailure { e -> log.warn("미종결 day-order lookup 실패: status={} err={}", status, e.message) }
                    .getOrDefault(emptyList())
            }
        if (stale.isEmpty()) {
            log.debug("ExpiredDayOrderSyncWorker — 미종결 day-order 0건 (cron skip): cutoff={}", staleCutoff)
            return
        }

        log.warn(
            "ExpiredDayOrderSyncWorker tick — 미종결 day-order {}건 KIS 대조(만료 동기화): cutoff={}",
            stale.size,
            staleCutoff,
        )
        emitSystemEvent(
            severity = SystemEventSeverity.INFO,
            action = "DAY_ORDER_EXPIRY_SCAN",
            userId = null,
            detailJson = """{"staleCount":${stale.size}}""",
        )

        for (row in stale) {
            try {
                processRow(repo, row, now)
            } catch (e: Exception) {
                log.error(
                    "ExpiredDayOrderSyncWorker.processRow 실패(다음 tick 재시도): orderId={} userId={} err={}",
                    row.id,
                    row.userId,
                    e.message,
                    e,
                )
            }
        }
    }

    /**
     * 단일 전일 미종결 row 처리 — KIS 일별체결 대조 후 FILLED/CANCELLED 동기화.
     */
    internal fun processRow(
        repo: OrderRequestRepository,
        row: OrderRequest,
        now: Instant,
    ) {
        val orderId = row.id ?: return
        val kisOrderNo = row.kisOrderNo
        if (kisOrderNo.isNullOrBlank()) {
            // ODNO 미발급 전일 주문 — KIS 미접수일 가능성 높지만 단정 위험. 만료 사유로 CANCELLED(잔존 ACK 제거가 본 hotfix 목적).
            log.warn("전일 미종결 day-order — kisOrderNo 부재, 만료 CANCELLED: orderId={} userId={}", orderId, row.userId)
            expire(repo, row, now, resolution = "NO_KIS_ORDER_NO")
            return
        }

        // KIS 일별체결 조회 window — 주문 발주일 anchor (canary D5 RT_CD_7 근본 fix #1019).
        //
        // ## RT_CD_7("조회일자를 확인하십시오") 근본원인 (KIS-first 규명)
        // 기존 코드는 INQR_STRT_DT = today.minusDays(2) 고정. KIS TTTC0081R(주식일별주문체결조회) 가
        // 이 start date 를 rt_cd=7 로 거부했다. KIS 공식 raw(examples_llm/.../inquire_daily_ccld.py L51~59)
        // 확인 결과 inner(3개월 이내·TTTC0081R) 경로에는 "조회기간 폭 N일 상한" 같은 제약이 문서화돼 있지 않고
        // (예시도 strt==end 단일일), 3개월 이전 분리(before·CTSC9215R)만 규정한다. 즉 RT_CD_7 은 폭 제한이
        // 아니라 KIS 의 **조회 시작일 자체에 대한 date 유효성 거부**다(주말/휴장/계좌 개시 등 KIS 캘린더 경계에서
        // start date 가 too-far-back/invalid 로 판정). 실증: 동일 endpoint 를 today.minusDays(1)..today 로
        // 호출하는 OrderReconciliationWorker 는 정상 — start date 가 1일 더 과거인 점만 차이.
        //
        // ## 채택 fix: 발주일(requestedAt KST date) anchor (고정 today-2 폐기)
        // day-order 만료 대조는 "그 주문이 실제 발주된 날" 의 체결내역만 보면 충분하다. 발주일을 start 로 잡으면
        // 불필요하게 과거로 늘어나 RT_CD_7 을 유발하던 window 가 사라진다. 안전 clamp:
        //   - end   = today (장중/익일 어디서 호출되든 발주일이 포함되도록 상한은 오늘)
        //   - start = min(발주일, today-1) — 발주일을 넘어 새로(today) 잡지 않되, 전일 day-order 면 그 발주일.
        //             today-1 clamp 는 OrderReconciliationWorker 와 동일한 검증된 최소 과거 보장(미반영 회피).
        //   - 3개월(inner tr_id) 경계 보호: start 가 today-90 보다 과거면 today-90 으로 clamp(전일 day-order 는
        //             해당 없으나 stale row 가 오래 잔존했을 극단 케이스 방어).
        val today = LocalDate.now(clock.withZone(KST))
        val requestedDate = row.requestedAt.atZone(KST).toLocalDate()
        val inquireStart = inquireStartDate(requestedDate, today)
        val result =
            kisOrderInquireDailyCcldPort.inquireByOrderNo(
                userId = row.userId,
                mode = row.mode,
                kisOrderNo = kisOrderNo,
                inquireStartDate = inquireStart,
                inquireEndDate = today,
            )

        // ## 2차 안전장치: RT_CD_7 N회 연속 + 발주일 명백 과거 → EXPIRE fallback
        // KIS 가 date 유효성으로 영구 거부(RT_CD_7류)하면 무한 재시도로 청산이 마비된다(후성 incident 의 실제 양상).
        // 발주일이 전 영업일 이전(< 오늘 0시 KST)인 day-order 는 KIS 당일주문 특성상 이미 만료가 확실하고,
        // 본 EXPIRE 는 이미 청산 의도된 stale SELL 의 status 정정일 뿐 보유수량/신규주문에 영향 0(오버셀 risk 0)이므로,
        // N(=DATE_ERROR_EXPIRE_THRESHOLD)회 연속 date-error Failed 면 만료 동기화한다.
        if (result is OrderInquireResult.Failed && isKisDateError(result.cause) && requestedDate.isBefore(today)) {
            val fails = dateErrorFailCount.merge(orderId, 1, Int::plus) ?: 1
            log.warn(
                "day-order KIS date-error(RT_CD_7류) {}회 연속: orderId={} userId={} ODNO={} start={} cause={}",
                fails,
                orderId,
                row.userId,
                kisOrderNo,
                inquireStart,
                result.cause,
            )
            if (fails >= DATE_ERROR_EXPIRE_THRESHOLD) {
                log.warn(
                    "day-order KIS date-error {}회 도달 — 과거 발주 day-order 만료 EXPIRE fallback: orderId={} 발주일={} ",
                    fails,
                    orderId,
                    requestedDate,
                )
                expire(repo, row, now, resolution = "KIS_DATE_ERROR_EXPIRE:fails=$fails:${result.cause}")
                dateErrorFailCount.remove(orderId)
                return
            }
            // 임계 미만 — 상태 불변, 다음 tick 재시도(아래 when 의 Failed 분기와 동일 효과지만 카운터 누적 위해 조기 return).
            return
        }
        // date-error 가 아닌 정상 응답/일반 Failed 면 카운터 reset(일시 오류였던 경우 누적 방지).
        dateErrorFailCount.remove(orderId)

        when (result) {
            is OrderInquireResult.Found -> {
                if (result.filledQty >= result.orderQty && result.orderQty > 0) {
                    // 전량 체결됐는데 DB 가 ACK 잔존 — FILLED 동기화(체결 row 흡수는 backfill worker 책임, 여기선 status 종결).
                    log.warn(
                        "day-order 전량 체결 확인 — FILLED 동기화: orderId={} userId={} ODNO={} filled={}/{}",
                        orderId,
                        row.userId,
                        kisOrderNo,
                        result.filledQty,
                        result.orderQty,
                    )
                    repo.markCompleted(orderId, now, OrderStatus.FILLED)
                    writeAudit(orderId, row.userId, OrderAuditEventType.KIS_RECONCILE_RESOLVED, "FOUND_FILLED", result.filledQty)
                } else {
                    // 부분/미체결 — 장 마감으로 잔량 만료. CANCELLED 동기화(잔존 ACK 제거).
                    log.warn(
                        "day-order 부분/미체결 만료 — CANCELLED 동기화: orderId={} userId={} ODNO={} filled={}/{}",
                        orderId,
                        row.userId,
                        kisOrderNo,
                        result.filledQty,
                        result.orderQty,
                    )
                    expire(repo, row, now, resolution = "FOUND_UNFILLED:filled=${result.filledQty}/${result.orderQty}")
                }
            }
            is OrderInquireResult.NotFound -> {
                log.warn(
                    "day-order KIS 미존재 — 만료 CANCELLED 동기화: orderId={} userId={} ODNO={}",
                    orderId,
                    row.userId,
                    kisOrderNo,
                )
                expire(repo, row, now, resolution = "NOT_FOUND")
            }
            is OrderInquireResult.Failed -> {
                // KIS 응답 실패 — 다음 tick 재시도(상태 불변, 잘못된 CANCELLED 회피).
                log.warn(
                    "day-order KIS 조회 실패(다음 tick 재시도): orderId={} userId={} ODNO={} cause={}",
                    orderId,
                    row.userId,
                    kisOrderNo,
                    result.cause,
                )
            }
        }
    }

    /**
     * 조회 cutoff:
     * - 장마감 grace 전: 오늘 0시 KST 이전만(기존 전일 잔류 정리).
     * - 장마감 grace 이후: 오늘 정규장 day-order도 만료됐으므로 15:30+grace 이전 요청까지 정리.
     */
    private fun staleRequestedAtCutoff(now: Instant): Instant {
        val nowKst = now.atZone(KST)
        val today = nowKst.toLocalDate()
        val todayStartKst = today.atStartOfDay(KST).toInstant()
        val sameDayExpiryCutoff =
            today
                .atTime(REGULAR_MARKET_CLOSE)
                .plusMinutes(marketCloseGraceMinutes.coerceAtLeast(0))
                .atZone(KST)
                .toInstant()
        return if (now.isBefore(sameDayExpiryCutoff)) todayStartKst else sameDayExpiryCutoff
    }

    /**
     * KIS 일별체결 조회 시작일 — 발주일 anchor + 검증된 안전 clamp(RT_CD_7 근본 fix).
     *
     * - 기본: 주문 발주일(requestedDate KST). 전일 day-order 면 그 발주일이라 KIS 가 기대하는 정확한 window.
     * - upper clamp: today-1 — 발주일을 넘어 today 로 새로 잡지 않되, OrderReconciliationWorker 와 동일한
     *   검증된 최소 과거(미반영 회피)를 보장. (당일 발주분도 end=today 로 커버되므로 start 는 최대 today-1.)
     * - lower clamp: today-90 — inner(TTTC0081R) 3개월 경계 보호(극단적 오래된 stale row 의 date 거부 회피).
     */
    internal fun inquireStartDate(
        requestedDate: LocalDate,
        today: LocalDate,
    ): LocalDate {
        val upperBound = today.minusDays(1)
        val lowerBound = today.minusDays(INNER_LOOKBACK_DAYS)
        // start 는 [today-90, today-1] 범위로 clamp 한 발주일.
        val anchored = if (requestedDate.isAfter(upperBound)) upperBound else requestedDate
        return if (anchored.isBefore(lowerBound)) lowerBound else anchored
    }

    private fun expire(
        repo: OrderRequestRepository,
        row: OrderRequest,
        now: Instant,
        resolution: String,
    ) {
        val orderId = row.id ?: return
        // updateStatus 로 사유 기록 + markCompleted 로 completedAt 종결(기존 port 재사용 — 신규 fun 0, 헌법 §6 함정 회피).
        repo.updateStatus(orderId, OrderStatus.CANCELLED, EXPIRE_REASON)
        repo.markCompleted(orderId, now, OrderStatus.CANCELLED)
        writeAudit(orderId, row.userId, OrderAuditEventType.KIS_RECONCILE_RESOLVED, resolution, 0)
        emitSystemEvent(
            severity = SystemEventSeverity.WARN,
            action = "DAY_ORDER_EXPIRED",
            userId = row.userId,
            detailJson = """{"orderId":$orderId,"code":"${row.code}","resolution":"$resolution"}""",
        )
    }

    private fun writeAudit(
        orderId: Long,
        userId: String,
        eventType: OrderAuditEventType,
        resolution: String,
        filledQty: Int,
    ) {
        val auditRepo = orderAuditEventRepositoryProvider.getIfAvailable() ?: return
        runCatching {
            auditRepo.save(
                OrderAuditEvent(
                    orderId = orderId,
                    userId = userId,
                    eventType = eventType,
                    payloadJson = """{"source":"DAY_ORDER_EXPIRY","resolution":"$resolution","filledQty":$filledQty}""",
                    createdAt = Instant.now(clock),
                ),
            )
        }.onFailure { e -> log.error("ExpiredDayOrderSyncWorker writeAudit 실패: orderId={} err={}", orderId, e.message) }
    }

    private fun emitSystemEvent(
        severity: SystemEventSeverity,
        action: String,
        userId: String?,
        detailJson: String,
    ) {
        runCatching {
            systemEventLogger.emit(
                SystemEvent(
                    category = SystemEventCategory.RECONCILE,
                    severity = severity,
                    actor = SYSTEM_EVENT_ACTOR,
                    action = action,
                    userId = userId,
                    detailJson = detailJson,
                ),
            )
        }.onFailure { e -> log.error("SystemEventLogger.emit 실패: action={} err={}", action, e.message) }
    }
}
