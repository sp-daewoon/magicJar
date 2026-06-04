package kr.spdaewoon.magicjar.consumer.ingestion

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistRepositoryAdapter
import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import kr.spdaewoon.magicjar.domain.market.session.MarketSession
import kr.spdaewoon.magicjar.domain.market.session.MarketSessionResolver
import kr.spdaewoon.magicjar.domain.user.AccountType
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
    /** J-1: 부팅 사전 검증 — REAL 자격증명 존재 확인 후 KIS WS connect 진입 (ADR 0077 J-1). */
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
) {
    private val log = LoggerFactory.getLogger(WatchlistIngestionWorker::class.java)

    @Volatile
    private var lastSession: MarketSession = MarketSession.Closed(reason = "init")

    /**
     * 부팅 reconcile — watchlist 전체 조회 후 KIS WS 연결 + priority 분배 (ADR 0028 §4-5).
     *
     * 순서 (2026-06-04 watchlist 1m stale race hotfix — 점검 사이클 #3):
     *   1. watchlist 조회 → 사용자 추출
     *   2. SubscriptionRouter.recompute() — 종목별 subscribe() 호출 → session.subscribedCodes 등록
     *      ★ connect()보다 선행 — onConnected 재구독이 전체 종목 set을 스냅샷하도록 보장.
     *   3. kisWsPort.connect() — WS 연결 시작 (approval_key 발급 + 핸드셰이크)
     *      handshake 완료 시 onConnected가 session.subscribedCodes 전체를 단일 권위로 재구독.
     *   4. 세션 확인 → polling worker 상태 결정
     *
     * ★ race fix 배경 (운영 evidence 2026-06-04 16:21 부팅):
     *   기존 순서(connect → recompute)는 connect()가 비동기(scheduleConnect)이므로
     *   doConnect handshake 스레드와 recompute(main) 스레드가 race했다.
     *   onConnected 재구독이 recompute보다 먼저 진입하면 `existingCodes` 스냅샷에
     *   일부 종목(예: 005930 1건)만 잡혀 "재구독 완료: 종목 1건" → 나머지 watchlist 종목은
     *   재구독에서 누락 + recompute의 늦은 subscribe()는 sendSink null/구독 전 윈도우에 emit되어 폐기 →
     *   해당 종목 tick 0건 → 1m candle 미적재 → 매 부팅 stale 감지 + 백필 폭주.
     *   recompute를 connect보다 먼저 호출하면 handshake 진입 전에 subscribedCodes가 전부 등록되어
     *   onConnected 재구독이 항상 전체 종목을 발사한다. (subscribe()의 emit 자체는 미연결 시 폐기되어도
     *   onConnected 재구독이 단일 권위이므로 무해 — KisWebSocketBridge.subscribe connected 가드 정합.)
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

            // J-1: 자격증명 사전 검증 — REAL KIS 키 미등록 시 connect skip + WARN 안내.
            // ADR 0077 J-1: onStartup 진입 시 REAL 자격증명 존재 확인 후 connect.
            // 미등록 시 KIS WS connect 자체를 차단 → ERROR_APPROVAL_KEY → OPSP8996 무한 루프 방지.
            // 사용자 메모리 [feedback_kis_key_realonly_for_data]: "정보 수집=실전 키".
            val realCredentials = credentialRepository.findAllByAccountType(AccountType.REAL.name)
            if (realCredentials.isEmpty()) {
                log.warn(
                    "WatchlistIngestionWorker: REAL KIS 자격증명 미등록 — KIS WS connect skip. " +
                        "SetupWizard(⚙️ SettingsModal)에서 REAL KIS 앱키·시크릿 등록 후 재부팅 필요. " +
                        "watchlist={}건 users={}",
                    allEntries.size,
                    users,
                )
                return
            }

            // recompute 선행 — connect() handshake 진입 전에 session.subscribedCodes 전체 등록.
            // onConnected 재구독이 전체 종목 set을 스냅샷하도록 보장 (race fix — 위 KDoc 참조).
            users.forEach { userId ->
                subscriptionRouter.recompute(userId)
            }
            // KIS WS 연결 시작 — onConnected가 등록된 subscribedCodes 전체를 단일 권위로 재구독.
            kisWsPort.connect()
            log.info("KIS WS connect() 호출 완료 — 핸드셰이크 진행 중 (등록 종목 onConnected 재구독 위임)")
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
