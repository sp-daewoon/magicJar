package kr.spdaewoon.magicjar.consumer.order

import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisDailyCcldExecution
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisOrderInquireCcldHttpAdapter
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.consumer.ws.KisWebSocketBridge
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEvent
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEventType
import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderAuditEventRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderExecutionRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * KIS WS 재연결 backfill 서비스 — ADR 0084 P0-3 (외부 코드 리뷰 결함 #4).
 *
 * ### 배경
 * KIS WS H0STCNI0/H0STCNI9 체결 통보는 disconnect 사이 발생한 체결을 누락 가능.
 * 본 service는 [KisWebSocketBridge.onReconnect] hook을 register 하여 재연결 직후 KIS REST 일별 체결 조회 발사 + 누락 체결 backfill.
 *
 * ### 흐름 (재연결 시점)
 *  1. KisWebSocketBridge.onReconnect 콜백 발사
 *  2. ConsumerUserKisCredentialJpaRepository에서 REAL 자격증명 사용자 enumeration
 *  3. 각 사용자별 [KisOrderInquireCcldHttpAdapter.fetchTodayExecutions] 호출 (당일 한정)
 *  4. 응답 row 각각:
 *     - eventId 합성: `BACKFILL:{kisOrderNo}:{ordTmd}` (cumulative 단위 — WS event_id와 다른 namespace)
 *     - [OrderExecutionRepository.existsByEventId] true 시 skip (이미 합류)
 *     - false 시:
 *       - OrderRequest 조회 (`findByKisOrderNo`) — 부재 시 pre-insert sentinel row 생성 (KisExecutionWsListener.preInsertOrderRequest 동일 정합)
 *       - OrderExecution INSERT (executedQuantity=totCcldQty, executedPrice=avgPrice, eventId=BACKFILL prefix)
 *       - audit FILL_RECEIVED + extra `{"source":"REST_BACKFILL"}`
 *
 * ### eventId namespace 분리 사유
 * WS 통보 event_id = `{odno}:{stckCntgHour}:{cntgQty}` — 분할 체결 1건 단위
 * REST backfill event_id = `BACKFILL:{odno}:{ordTmd}` — cumulative 총합 단위 (raw inquire-daily-ccld는 분할 체결을 cumulative `tot_ccld_qty`로 한번에 반환)
 *
 * 따라서 WS가 동일 주문의 4건 분할 체결 후 disconnect 발생 시:
 *  - 정상: WS event_id 4건 INSERT (qty=2+3+4+1 각각)
 *  - backfill: 1건 REST event_id INSERT (qty=10 cumulative)
 *  - audit trace로 운영자가 어느 path로 들어왔는지 확인 가능 (source field)
 *
 * **현 1차 정책**: WS path가 정상 합류 후 backfill이 들어오면 `existsByOrderId`로도 중복 가능. 본 PR 1차 정책은 단순 event_id UNIQUE만 의존 (cumulative vs 분할 mix 시 운영자 audit + 후속 backlog §62에서 reconcile)
 *
 * ### 호출 시점
 * - [ApplicationReadyEvent] 진입 시점에 [KisWebSocketBridge.onReconnect] 콜백 register (lazy wiring)
 * - 첫 연결도 본 hook 발사됨 → 부팅 직후 1회 backfill 실행 → 직전 종료 동안 누적 체결도 흡수
 *
 * ### Rate Limit (ADR 0037)
 * - REAL 사용자 N명 × 1회 KIS REST 호출 → N건/재연결
 * - WS 폭주(헬스체크 미스 → 무한 재연결) 시 KIS rate limit hit 가능 → backlog §62에서 throttle (예: 60s 내 1회 cap) 검토
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0068 §8 + ADR 0084 §2-8).
 */
@Component
class KisExecutionBackfillService(
    private val kisOrderInquireCcldHttpAdapter: KisOrderInquireCcldHttpAdapter,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
    private val orderExecutionRepositoryProvider: ObjectProvider<OrderExecutionRepository>,
    private val orderAuditEventRepositoryProvider: ObjectProvider<OrderAuditEventRepository>,
    private val webSocketBridge: KisWebSocketBridge,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String,
    @Value("\${magicjar.order.ws-backfill.enabled:true}")
    private val backfillEnabled: Boolean,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(KisExecutionBackfillService::class.java)

    companion object {
        /** ADR 0084 P0-3 — REST backfill eventId namespace prefix. */
        const val BACKFILL_EVENT_ID_PREFIX: String = "BACKFILL"

        fun buildBackfillEventId(
            kisOrderNo: String,
            ordTmd: String,
        ): String = "$BACKFILL_EVENT_ID_PREFIX:$kisOrderNo:$ordTmd"
    }

    /**
     * Spring Boot 완전 부팅 직후 KisWebSocketBridge.onReconnect 콜백 register.
     * Boot order: ApplicationReadyEvent는 모든 bean 합류 + 부팅 완료 후 발사 → wiring 안전.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun registerOnReconnect() {
        if (!backfillEnabled) {
            log.warn(
                "ADR 0084 P0-3 — WS 재연결 backfill 비활성 (magicjar.order.ws-backfill.enabled=false). 자본 audit risk 인지 후 운영 결정.",
            )
            return
        }
        webSocketBridge.onReconnect = { runBackfillSafely() }
        log.info("ADR 0084 P0-3 — WS 재연결 backfill hook register 완료")
    }

    /**
     * 재연결 직후 backfill 실행 — 예외 catch + WS thread 안 죽이기.
     */
    internal fun runBackfillSafely() {
        try {
            runBackfill()
        } catch (e: Exception) {
            log.error("ADR 0084 P0-3 — backfill 실행 실패: err={}", e.message, e)
        }
    }

    /**
     * 본 backfill 진입점 — REAL 사용자별 KIS REST 일별 체결 조회 + 누락 INSERT.
     */
    internal fun runBackfill() {
        val orderRepo = orderRequestRepositoryProvider.getIfAvailable()
        val execRepo = orderExecutionRepositoryProvider.getIfAvailable()
        if (orderRepo == null || execRepo == null) {
            log.warn(
                "ADR 0084 P0-3 — backfill skip (Phase 2 wiring 미합류): orderRepo={} execRepo={}",
                orderRepo != null,
                execRepo != null,
            )
            return
        }

        // 자격증명 사용자별 backfill 분기 (MOCK·REAL 모두 분기)
        val accountType = if (magicjarMode == "REAL") AccountType.REAL else AccountType.MOCK
        val creds = credentialRepository.findAllByAccountType(accountType.name)
        if (creds.isEmpty()) {
            log.info(
                "ADR 0084 P0-3 — backfill skip: 자격증명 등록 사용자 0건 (accountType={})",
                accountType.name,
            )
            return
        }

        var totalFetched = 0
        var totalInserted = 0
        creds.forEach { cred ->
            val userId = cred.id.userId
            val executions =
                try {
                    kisOrderInquireCcldHttpAdapter.fetchTodayExecutions(userId, accountType)
                } catch (e: Exception) {
                    log.error("backfill REST 호출 실패: userId={} err={}", maskUserId(userId), e.message)
                    return@forEach
                }
            totalFetched += executions.size
            executions.forEach { exec ->
                if (persistBackfillRow(orderRepo, execRepo, exec, userId, accountType)) {
                    totalInserted++
                }
            }
        }

        log.info(
            "ADR 0084 P0-3 — backfill 완료: 사용자={}건 fetched={} inserted={} (skipped={})",
            creds.size,
            totalFetched,
            totalInserted,
            totalFetched - totalInserted,
        )
    }

    /**
     * 1건의 backfill row 영속 — eventId UNIQUE 가드 + OrderRequest pre-insert 합류.
     *
     * @return true = INSERT 합류 / false = skip (dedup 또는 영속 실패)
     */
    internal fun persistBackfillRow(
        orderRepo: OrderRequestRepository,
        execRepo: OrderExecutionRepository,
        exec: KisDailyCcldExecution,
        userId: String,
        accountType: AccountType,
    ): Boolean {
        val eventId = buildBackfillEventId(exec.kisOrderNo, exec.ordTmd)

        // 1단계: eventId UNIQUE 가드
        val alreadyPersisted =
            try {
                execRepo.existsByEventId(eventId)
            } catch (e: Exception) {
                log.error("backfill existsByEventId 실패 — skip (자본 안전): eventId={} err={}", eventId, e.message)
                return false
            }
        if (alreadyPersisted) {
            log.debug("backfill skip (event_id dedup): eventId={}", eventId)
            return false
        }

        // 2단계: OrderRequest 조회 — 부재 시 pre-insert sentinel row 생성
        val request =
            try {
                orderRepo.findByKisOrderNo(exec.kisOrderNo)
            } catch (e: Exception) {
                log.error("backfill findByKisOrderNo 실패: kisOrderNo={} err={}", exec.kisOrderNo, e.message)
                null
            } ?: preInsertOrderRequestForBackfill(orderRepo, exec, userId, accountType)

        if (request == null || request.id == null) {
            log.warn("backfill skip — OrderRequest pre-insert 실패: kisOrderNo={}", exec.kisOrderNo)
            return false
        }

        // 3단계: OrderExecution INSERT
        val now = Instant.now(clock)
        val saved =
            try {
                execRepo.save(
                    OrderExecution(
                        orderId = request.id!!,
                        userId = request.userId,
                        kisOrderNo = exec.kisOrderNo,
                        executedQuantity = exec.totCcldQty,
                        executedPrice = exec.avgPrice,
                        executedAt = exec.executedAt,
                        eventId = eventId,
                        createdAt = now,
                    ),
                )
            } catch (e: Exception) {
                log.error(
                    "backfill orderExecution save 실패: kisOrderNo={} eventId={} err={}",
                    exec.kisOrderNo,
                    eventId,
                    e.message,
                )
                return false
            }

        // 4단계: audit FILL_RECEIVED + source=REST_BACKFILL
        val auditRepo = orderAuditEventRepositoryProvider.getIfAvailable()
        auditRepo?.runCatching {
            save(
                OrderAuditEvent(
                    orderId = request.id,
                    userId = request.userId,
                    eventType = OrderAuditEventType.FILL_RECEIVED,
                    payloadJson =
                        """{"source":"REST_BACKFILL","eventId":"$eventId","kisOrderNo":"${exec.kisOrderNo}",""" +
                            """"totCcldQty":${exec.totCcldQty},"avgPrice":"${exec.avgPrice.toPlainString()}"}""",
                    createdAt = now,
                ),
            )
        }

        log.info(
            "ADR 0084 P0-3 — backfill INSERT: orderId={} kisOrderNo={} eventId={} qty={} price={} executionId={}",
            request.id,
            exec.kisOrderNo,
            eventId,
            exec.totCcldQty,
            exec.avgPrice.toPlainString(),
            saved.id,
        )
        return true
    }

    /**
     * backfill 시점 OrderRequest stub row 생성 — KisExecutionWsListener.preInsertOrderRequest 동일 정합.
     *
     * sentinel: signalId=0L, strategyId=0L, priceType=MARKET, status=ACK
     */
    internal fun preInsertOrderRequestForBackfill(
        orderRepo: OrderRequestRepository,
        exec: KisDailyCcldExecution,
        userId: String,
        accountType: AccountType,
    ): OrderRequest? {
        val now = Instant.now(clock)
        val sentinelKey = "REST_BACKFILL_PREINSERT:${exec.kisOrderNo}"

        val existing =
            try {
                orderRepo.findByIdempotencyKey(sentinelKey)
            } catch (e: Exception) {
                log.error("backfill preInsert findByIdempotencyKey 실패: kisOrderNo={} err={}", exec.kisOrderNo, e.message)
                null
            }
        if (existing != null) {
            return existing
        }

        val side =
            if (exec.sellBuyCls == "01") {
                kr.spdaewoon.magicjar.domain.order.execution.OrderSide.SELL
            } else {
                kr.spdaewoon.magicjar.domain.order.execution.OrderSide.BUY
            }
        val stub =
            OrderRequest(
                userId = userId,
                signalId = 0L,
                strategyId = 0L,
                code = exec.code,
                side = side,
                priceType = kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType.MARKET,
                quantity = exec.ordQty.coerceAtLeast(exec.totCcldQty),
                limitPrice = null,
                status = OrderStatus.ACK,
                mode =
                    if (accountType == AccountType.REAL) {
                        kr.spdaewoon.magicjar.domain.order.execution.TradingMode.REAL
                    } else {
                        kr.spdaewoon.magicjar.domain.order.execution.TradingMode.MOCK
                    },
                kisOrderNo = exec.kisOrderNo,
                idempotencyKey = sentinelKey,
                requestedAt = now,
                acknowledgedAt = now,
            )
        return try {
            orderRepo.save(stub)
        } catch (e: Exception) {
            log.error("backfill preInsert save 실패: kisOrderNo={} err={}", exec.kisOrderNo, e.message)
            null
        }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
