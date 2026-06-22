package kr.spdaewoon.magicjar.consumer.ingestion

import io.micrometer.core.instrument.MeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import kr.spdaewoon.magicjar.domain.market.port.SubscriptionPolicy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.util.concurrent.CopyOnWriteArraySet

class RealtimeSubscriptionRouterTest {
    private lateinit var kisWsPort: TestKisWsPort
    private val todayCandleSeedPort = mockk<kr.spdaewoon.magicjar.domain.market.port.TodayCandleSeedPort>(relaxed = true)
    private lateinit var router: RealtimeSubscriptionRouter

    @BeforeEach
    fun setUp() {
        kisWsPort = TestKisWsPort()
        // grace 0 = 기존 즉시 해제 동작(대부분 기존 시나리오 테스트가 이 의미를 검증). churn 테스트는 별도 grace router.
        router = newRouter(graceMs = 0L)
    }

    private fun newRouter(
        graceMs: Long,
        ledger: WsSlotBudgetLedger = newLedger(),
    ): RealtimeSubscriptionRouter =
        RealtimeSubscriptionRouter(
            kisWsPort = kisWsPort,
            todayCandleSeedPort = todayCandleSeedPort,
            wsSlotBudgetLedger = ledger,
            unsubscribeGraceMs = graceMs,
        )

    // admission 게이트가 한도(40) 내에서 발사를 허용하도록 실제 ledger 주입(default 활성·40 slot).
    // meterRegistry는 부재(ifAvailable=null) → metric no-op (회계·admission 로직은 정상).
    private fun newLedger(maxSlots: Int = 40): WsSlotBudgetLedger {
        val provider = mockk<ObjectProvider<MeterRegistry>>(relaxed = true)
        every { provider.ifAvailable } returns null
        return WsSlotBudgetLedger(
            meterRegistryProvider = provider,
            ledgerEnabled = true,
            maxSlots = maxSlots,
            admissionEnabled = true,
        )
    }

    @Test
    fun `DETAIL 진입은 watchlist 없이도 가격 WS를 구독한다`() {
        router.request(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")

        assertTrue(kisWsPort.connected)
        assertTrue(kisWsPort.priceSubscribed.contains("005930"))
        assertTrue(router.isExternallyDemanded("005930"))
    }

    @Test
    fun `DETAIL 이탈은 다른 owner가 없으면 가격 WS를 즉시 해제한다`() {
        router.request(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")
        router.release(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")

        assertFalse(kisWsPort.priceSubscribed.contains("005930"))
        assertFalse(router.isExternallyDemanded("005930"))
        assertTrue(kisWsPort.priceUnsubscribed.contains("005930"))
    }

    @Test
    fun `DETAIL 이탈은 STRATEGY owner가 남으면 가격 WS를 유지한다`() {
        router.request(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")
        router.request(RealtimeDemandOwner.STRATEGY, "strategy:42", "005930", priority = 10)

        router.release(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")

        assertTrue(kisWsPort.priceSubscribed.contains("005930"))
        assertTrue(router.isExternallyDemanded("005930"))
        assertFalse(kisWsPort.priceUnsubscribed.contains("005930"))
        val owners = router.snapshotDemands().first { it.code == "005930" }.owners
        assertTrue(owners.contains("strategy:42"))
        assertFalse(owners.any { it.startsWith("detail:") })
    }

    @Test
    fun `STRATEGY 이탈은 demand가 모두 사라지면 가격 WS를 즉시 해제한다`() {
        // ADR 0124 — watchlist가 WS를 점유하지 않으므로 owner demand 0이면 즉시 unsubscribe.
        router.request(RealtimeDemandOwner.STRATEGY, "strategy:42", "005930", priority = 10)
        router.release(RealtimeDemandOwner.STRATEGY, "strategy:42", "005930")

        assertFalse(router.isExternallyDemanded("005930"))
        assertTrue(kisWsPort.priceUnsubscribed.contains("005930"))
    }

    @Test
    fun `slot 포화 시 신규 진입은 demand 없는 잔여 구독을 evict한다 (ADR 0124)`() {
        // ADR 0124 — eviction victim 후보는 어느 owner demand에도 없는 잔여 구독만.
        // slot 을 demand 없는 종목으로 가득 채운다.
        val orphanCodes = (1..SubscriptionPolicy.PRICE_TICK_MAX).map { "1%05d".format(it) }
        kisWsPort.priceSubscribed.addAll(orphanCodes)

        router.request(RealtimeDemandOwner.STRATEGY, "strategy:42", "005930", priority = 10)

        // 신규 STRATEGY 구독 발사 + orphan 1건 evict.
        assertTrue(kisWsPort.priceSubscribed.contains("005930"))
        assertTrue(kisWsPort.priceUnsubscribed.any { it in orphanCodes })
    }

    @Test
    fun `HOLDING 등록은 가격 WS를 구독하고 최우선 demand로 추적한다 (ADR 0114 P3)`() {
        router.request(RealtimeDemandOwner.HOLDING, "holding:005930", "005930")

        assertTrue(kisWsPort.priceSubscribed.contains("005930"))
        assertTrue(router.isExternallyDemanded("005930"))
        // priority 0 (최우선).
        org.junit.jupiter.api.Assertions
            .assertEquals(0, router.priorityOf("005930"))
    }

    @Test
    fun `slot 포화 + 신규 진입 시 HOLDING 종목은 evict 후보에서 제외된다 (ADR 0114 P3 LOCK)`() {
        // 30 slot 중 1개를 HOLDING(보유 종목), 나머지는 demand 없는 잔여 구독으로 채운다.
        // ADR 0124 — evict 후보는 demand 없는 잔여 구독만이지만 HOLDING demand 종목은 절대 victim 아님.
        val holdingCode = "0HOLD0"
        val orphanCodes = (1 until SubscriptionPolicy.PRICE_TICK_MAX).map { "1%05d".format(it) }
        kisWsPort.priceSubscribed.add(holdingCode)
        kisWsPort.priceSubscribed.addAll(orphanCodes)

        // HOLDING demand 등록(이미 구독 중이라 재구독 X, demand만 추적).
        router.request(RealtimeDemandOwner.HOLDING, "holding:$holdingCode", holdingCode)
        // slot 포화 상태에서 신규 STRATEGY 진입 → evict가 일어나도 HOLDING은 절대 victim 아님.
        router.request(RealtimeDemandOwner.STRATEGY, "strategy:42", "005930", priority = 10)

        assertTrue(kisWsPort.priceSubscribed.contains(holdingCode))
        assertFalse(kisWsPort.priceUnsubscribed.contains(holdingCode))
    }

    @Test
    fun `HOLDING release는 즉시 demand를 제거한다 (전량 청산)`() {
        router.request(RealtimeDemandOwner.HOLDING, "holding:005930", "005930")
        assertTrue(router.isExternallyDemanded("005930"))

        router.release(RealtimeDemandOwner.HOLDING, "holding:005930", "005930")

        assertFalse(router.isExternallyDemanded("005930"))
        assertTrue(kisWsPort.priceUnsubscribed.contains("005930"))
    }

    // ===== today-Hash seed 보강 (이미 WS 구독 중인 종목의 DETAIL 재진입 복구) =====

    @Test
    fun `신규 DETAIL 구독은 KisWsPort subscribe에 seed 책임을 위임하고 라우터 중복 시드는 하지 않는다`() {
        router.request(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")
        Thread.sleep(300)
        verify(exactly = 0) { todayCandleSeedPort.seedTodayCandle("005930") }
    }

    @Test
    fun `이미 구독 중인 종목 재요청은 today-Hash seed를 보강한다`() {
        kisWsPort.priceSubscribed.add("005930")
        router.request(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")
        verify(timeout = 2000) { todayCandleSeedPort.seedTodayCandle("005930") }
    }

    @Test
    fun `이미 구독 중인 종목 재요청 seed는 cooldown 내 중복 호출하지 않는다`() {
        kisWsPort.priceSubscribed.add("005930")
        router.request(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")
        verify(timeout = 2000) { todayCandleSeedPort.seedTodayCandle("005930") }

        router.request(RealtimeDemandOwner.HOLDING, "holding:005930", "005930")
        Thread.sleep(300)
        verify(exactly = 1) { todayCandleSeedPort.seedTodayCandle("005930") }
    }

    @Test
    fun `snapshotDemands — code별 owner 합집합 + since (관리자 실시간 상태)`() {
        router.request(RealtimeDemandOwner.DETAIL, "user001", "005930")
        router.request(RealtimeDemandOwner.STRATEGY, "86", "005930", priority = 10)
        router.request(RealtimeDemandOwner.HOLDING, "holding", "000660")

        val snap = router.snapshotDemands().associateBy { it.code }
        assertTrue(snap.containsKey("005930"))
        assertTrue(snap.containsKey("000660"))
        // 005930은 DETAIL + STRATEGY owner 합집합 (정렬). bare ownerId → 단일 prefix 1회.
        assertTrue(snap["005930"]!!.owners.contains("detail:user001"))
        assertTrue(snap["005930"]!!.owners.contains("strategy:86"))
        assertTrue(snap["005930"]!!.sinceMs != null)
    }

    @Test
    fun `snapshotDemands — legacy prefix 포함 ownerId여도 이중 prefix 방어`() {
        // 롤링 배포·미발견 producer가 prefix 포함 ownerId를 넣어도 스냅샷은 단일 prefix(이중 prefix 회피).
        router.request(RealtimeDemandOwner.STRATEGY, "strategy:86", "005930", priority = 10)
        router.request(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")

        val owners = router.snapshotDemands().first { it.code == "005930" }.owners
        assertTrue(owners.contains("strategy:86")) // strategy:strategy:86 아님
        assertTrue(owners.contains("detail:user001")) // detail:detail:user001 아님
        assertTrue(owners.none { it.startsWith("strategy:strategy:") || it.startsWith("detail:detail:") })
    }

    @Test
    fun `snapshotDemands — since는 재요청에도 최초 구독 시각 보존`() {
        router.request(RealtimeDemandOwner.STRATEGY, "86", "005930", priority = 10)
        val firstSince = router.snapshotDemands().first { it.code == "005930" }.sinceMs
        Thread.sleep(10)
        router.request(RealtimeDemandOwner.STRATEGY, "86", "005930", priority = 10)
        val secondSince = router.snapshotDemands().first { it.code == "005930" }.sinceMs
        assertTrue(firstSince == secondSince)
    }

    // ===== churn 억제 (unsubscribe grace) — REAL canary 2026-06-18 후성 093370 stuck =====

    @Test
    fun `churn 억제 - demand가 grace 안에 재진입하면 KIS UNSUBSCRIBE 없이 sticky 유지`() {
        val r = newRouter(graceMs = 90_000L)
        // 단타 flip 모사: STRATEGY sub → release(잠깐 비움) → 다시 sub.
        r.request(RealtimeDemandOwner.STRATEGY, "86", "093370", priority = 10)
        assertTrue(kisWsPort.priceSubscribed.contains("093370"))

        r.release(RealtimeDemandOwner.STRATEGY, "86", "093370") // demand 0 → grace 예약(즉시 해제 X)
        assertFalse(kisWsPort.priceUnsubscribed.contains("093370")) // 아직 unsubscribe 안 함
        assertTrue(kisWsPort.priceSubscribed.contains("093370")) // WS 구독 sticky 유지

        r.request(RealtimeDemandOwner.STRATEGY, "86", "093370", priority = 10) // grace 안에 재진입
        // pending 취소 → sweep가 와도 해제 안 함. churn wire 왕복 0.
        r.sweepUnsubscribeGrace()
        assertFalse(kisWsPort.priceUnsubscribed.contains("093370"))
        assertTrue(kisWsPort.priceSubscribed.contains("093370"))
    }

    @Test
    fun `grace 만료 sweep - demand가 끝내 돌아오지 않으면 실제 UNSUBSCRIBE`() {
        val r = newRouter(graceMs = 1L) // 1ms grace → 즉시 만료.
        r.request(RealtimeDemandOwner.STRATEGY, "86", "093370", priority = 10)
        r.release(RealtimeDemandOwner.STRATEGY, "86", "093370")
        assertFalse(kisWsPort.priceUnsubscribed.contains("093370")) // grace 예약(아직)

        Thread.sleep(5)
        r.sweepUnsubscribeGrace()

        assertTrue(kisWsPort.priceUnsubscribed.contains("093370")) // 끝내 미복귀 → 실제 해제.
        assertFalse(kisWsPort.priceSubscribed.contains("093370"))
    }

    @Test
    fun `churn 억제 - HOLDING+STRATEGY 동시 release 후 재진입도 sticky (후성 시나리오)`() {
        val r = newRouter(graceMs = 90_000L)
        // 후성: HOLDING + STRATEGY 둘 다 보유. 단타 flip이 둘을 잠깐 동시에 비운 뒤 재진입.
        r.request(RealtimeDemandOwner.HOLDING, "093370", "093370")
        r.request(RealtimeDemandOwner.STRATEGY, "86", "093370", priority = 10)

        r.release(RealtimeDemandOwner.STRATEGY, "86", "093370")
        r.release(RealtimeDemandOwner.HOLDING, "093370", "093370") // demand 0 → grace
        assertFalse(kisWsPort.priceUnsubscribed.contains("093370"))

        r.request(RealtimeDemandOwner.HOLDING, "093370", "093370") // 재매수 체결 모사 — grace 안 재진입
        r.sweepUnsubscribeGrace()
        assertFalse(kisWsPort.priceUnsubscribed.contains("093370")) // churn wire 왕복 0
        assertTrue(kisWsPort.priceSubscribed.contains("093370"))
    }

    // ===== 통합 admission 게이트 (2026-06-22 장중 신규구독 H0UNCNT0 silent 누락 fix) =====

    @Test
    fun `통합 한도 포화 시 신규 STRATEGY tick은 KIS 발사를 보류한다 (silent 누락 선제 차단)`() {
        // 라이브 재현: 체결가 + 호가가 같은 40 한도 공유. ledger를 한도까지 직접 채운다(호가 점유 포함).
        val ledger = newLedger()
        repeat(20) { i -> ledger.tryRegister("H0UNASP0", "1%05d".format(i)) } // 호가 20
        repeat(19) { i -> ledger.tryRegister("H0UNCNT0", "2%05d".format(i)) } // 체결가 19 → 합 39(가용 0)
        val r = newRouter(graceMs = 0L, ledger = ledger)

        // 장중 신규 STRATEGY 진입(삼성전자) — demand 0 종목이 없어 evict 불가 + 한도 초과 → 발사 보류.
        r.request(RealtimeDemandOwner.STRATEGY, "strategy:86", "005930", priority = 10)

        // KIS WS subscribe 미발사(REST polling fallback) — silent SUCCESS 후 frame 0 회피.
        assertFalse(kisWsPort.priceSubscribed.contains("005930"))
        // demand는 등록됨(다음 slot 회수 시 재시도 가능) — TTL refresh가 재요청한다.
        assertTrue(r.isExternallyDemanded("005930"))
    }

    @Test
    fun `한도 회수 후 신규 STRATEGY tick은 정상 구독된다 (보류→재진입)`() {
        val ledger = newLedger()
        repeat(20) { i -> ledger.tryRegister("H0UNASP0", "1%05d".format(i)) }
        repeat(19) { i -> ledger.tryRegister("H0UNCNT0", "2%05d".format(i)) }
        val r = newRouter(graceMs = 0L, ledger = ledger)

        r.request(RealtimeDemandOwner.STRATEGY, "strategy:86", "005930", priority = 10)
        assertFalse(kisWsPort.priceSubscribed.contains("005930")) // 보류

        // 호가 1건 해제 → 가용 1 → 재요청 시 admit.
        ledger.release("H0UNASP0", "100000")
        r.request(RealtimeDemandOwner.STRATEGY, "strategy:86", "005930", priority = 10)
        assertTrue(kisWsPort.priceSubscribed.contains("005930"))
    }

    class TestKisWsPort : KisWsPort {
        var connected = false
        val priceSubscribed: MutableSet<String> = CopyOnWriteArraySet()
        val priceUnsubscribed: MutableSet<String> = CopyOnWriteArraySet()

        override fun connect() {
            connected = true
        }

        override fun subscribe(codes: List<String>) {
            priceSubscribed.addAll(codes)
        }

        override fun unsubscribe(codes: List<String>) {
            priceSubscribed.removeAll(codes.toSet())
            priceUnsubscribed.addAll(codes)
        }

        override fun subscribedCodes(): Set<String> = priceSubscribed.toSet()

        override fun subscribeOrderbook(codes: List<String>) {}

        override fun unsubscribeOrderbook(codes: List<String>) {}

        override fun subscribedOrderbookCodes(): Set<String> = emptySet()

        override fun subscribeMarketStatus(codes: List<String>) {}

        override fun unsubscribeMarketStatus(codes: List<String>) {}

        override fun subscribedMarketStatusCodes(): Set<String> = emptySet()
    }
}
