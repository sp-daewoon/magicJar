package kr.spdaewoon.magicjar.consumer.ingestion

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistRepositoryAdapter
import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import kr.spdaewoon.magicjar.domain.market.session.MarketSession
import kr.spdaewoon.magicjar.domain.market.session.MarketSessionResolver
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Watchlist Ingestion Worker — facade (ADR 0028 §2-1 정합).
 *
 * 역할:
 * - @PostConstruct: 부팅 reconcile (watchlist 전체 조회 → SubscriptionRouter.recompute)
 * - @Scheduled 1분 cron: MarketSession 변경 감지 → onSessionChanged 호출
 * - @PreDestroy: graceful shutdown (KIS WS unsubscribe 전체 + polling stop — ADR 0045)
 */
@Component
class WatchlistIngestionWorker(
    private val subscriptionRouter: SubscriptionRouter,
    private val pollingWorker: WatchlistPollingWorker,
    private val kisWsPort: KisWsPort,
    private val watchlistRepository: ConsumerWatchlistRepositoryAdapter,
    private val marketSessionResolver: MarketSessionResolver,
) {
    private val log = LoggerFactory.getLogger(WatchlistIngestionWorker::class.java)

    @Volatile
    private var lastSession: MarketSession = MarketSession.Closed(reason = "init")

    /**
     * 부팅 reconcile — watchlist 전체 조회 후 KIS WS 연결 + priority 분배 (ADR 0028 §4-5).
     *
     * 순서:
     *   1. watchlist 조회 → 사용자 추출
     *   2. kisWsPort.connect() — WS 연결 시작 (approval_key 발급 + 핸드셰이크)
     *      ★ subscribe()보다 반드시 선행 — sendSink가 null이면 subscribe 메시지 폐기됨
     *   3. SubscriptionRouter.recompute() — 종목별 subscribe() 호출
     *   4. 세션 확인 → polling worker 상태 결정
     *
     * ADR 0028 §2-1: WatchlistIngestionWorker가 KIS WS 연결·구독의 단일 책임자.
     * ADR 0048 §2-4: connect 실패 시 Bridge 내부에서 지수 백오프 재시도.
     */
    @PostConstruct
    fun onStartup() {
        log.info("WatchlistIngestionWorker 부팅 reconcile 시작")
        try {
            val allEntries = watchlistRepository.findAll()
            val users = allEntries.map { it.userId }.distinct()
            if (users.isEmpty()) {
                log.info("watchlist 없음 — reconcile 생략")
                return
            }
            // KIS WS 연결 시작 — subscribe()보다 선행 의무 (sendSink null 방지)
            kisWsPort.connect()
            log.info("KIS WS connect() 호출 완료 — 핸드셰이크 진행 중")
            users.forEach { userId ->
                subscriptionRouter.recompute(userId)
            }
            // 현재 세션 확인 후 polling worker 상태 결정
            val now = Clock.System.now()
            lastSession = marketSessionResolver.resolve(now)
            if (lastSession is MarketSession.Regular) {
                pollingWorker.start()
            }
            log.info(
                "WatchlistIngestionWorker 부팅 완료: users={}건 session={}",
                users.size,
                lastSession::class.simpleName,
            )
        } catch (e: Exception) {
            log.error("WatchlistIngestionWorker 부팅 reconcile 실패: {}", e.message, e)
        }
    }

    /**
     * 1분 주기 세션 변경 감지 (ADR 0048 §2-5).
     */
    @Scheduled(cron = "0 * * * * ?")
    fun checkSessionChange() {
        val now = Clock.System.now()
        val currentSession = marketSessionResolver.resolve(now)
        if (currentSession::class != lastSession::class) {
            log.info("MarketSession 변경 감지: {} → {}", lastSession::class.simpleName, currentSession::class.simpleName)
            subscriptionRouter.onSessionChanged(lastSession, currentSession)
            lastSession = currentSession
        }
    }

    /**
     * Graceful shutdown — SIGTERM 시 KIS WS unsubscribe 전체 + polling stop (ADR 0045).
     */
    @PreDestroy
    fun onShutdown() {
        log.info("WatchlistIngestionWorker graceful shutdown 시작")
        try {
            pollingWorker.stop()
            val allCodes = kisWsPort.subscribedCodes().toList()
            if (allCodes.isNotEmpty()) {
                kisWsPort.unsubscribe(allCodes)
                log.info("KIS WS 전체 unsubscribe: {}건", allCodes.size)
            }
            // shutdown 완료 대기 (최대 5초 — ADR 0045)
            Thread.sleep(500)
            log.info("WatchlistIngestionWorker graceful shutdown 완료")
        } catch (e: Exception) {
            log.warn("WatchlistIngestionWorker shutdown 중 오류: {}", e.message)
        }
    }
}
