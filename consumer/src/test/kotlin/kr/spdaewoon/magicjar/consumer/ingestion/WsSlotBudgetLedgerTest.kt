package kr.spdaewoon.magicjar.consumer.ingestion

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider

/**
 * WsSlotBudgetLedger 단위 테스트 (ADR 0123 R1).
 *
 * 검증 범위:
 * - 채널(code × trId) 점유 등록/취소 회계 — 동일 종목 다른 채널(체결가·호가) 별도 slot.
 * - available 산식 (maxSlots - reserved - 점유).
 * - OPSP0008 거부 회계 보정 (점유 취소 + 거부 카운트).
 * - 한도 도달 시 점유 카운트 (R1은 차단 없이 등록 — eviction은 R2).
 * - ledger 비활성(롤백 toggle) 시 회계 no-op.
 * - 거부 후 재시도 가능 상태 (보정으로 미점유 → 재등록 가능).
 */
class WsSlotBudgetLedgerTest {
    private fun meterProvider(registry: MeterRegistry?): ObjectProvider<MeterRegistry> =
        mockk<ObjectProvider<MeterRegistry>>(relaxed = true).also {
            every { it.ifAvailable } returns registry
        }

    private fun ledger(
        enabled: Boolean = true,
        maxSlots: Int = 40,
        registry: MeterRegistry? = SimpleMeterRegistry(),
        admissionEnabled: Boolean = true,
    ): WsSlotBudgetLedger =
        WsSlotBudgetLedger(
            meterRegistryProvider = meterProvider(registry),
            ledgerEnabled = enabled,
            maxSlots = maxSlots,
            admissionEnabled = admissionEnabled,
        )

    @Test
    fun `채널 등록 — code x trId 단위 점유 + 다른 채널 별도 slot`() {
        val l = ledger()
        l.register(trId = "H0UNCNT0", code = "005930")
        l.register(trId = "H0UNASP0", code = "005930") // 동일 종목, 다른 채널(호가) = 별도 slot
        assertThat(l.occupiedCount()).isEqualTo(2)
        assertThat(l.isOccupied("H0UNCNT0", "005930")).isTrue()
        assertThat(l.isOccupied("H0UNASP0", "005930")).isTrue()
        assertThat(l.isOccupied("H0UNMKO0", "005930")).isFalse()
    }

    @Test
    fun `register 멱등 — 동일 채널 재등록 중복 카운트 0`() {
        val l = ledger()
        l.register("H0UNCNT0", "005930")
        l.register("H0UNCNT0", "005930")
        assertThat(l.occupiedCount()).isEqualTo(1)
    }

    @Test
    fun `available 산식 — maxSlots 40 - reserved 1 - 점유`() {
        val l = ledger(maxSlots = 40)
        // 점유 0 → available = 40 - 1(체결통보 reserve) = 39
        assertThat(l.availableSlots()).isEqualTo(39)
        l.register("H0UNCNT0", "005930")
        l.register("H0UNASP0", "005930")
        assertThat(l.availableSlots()).isEqualTo(37)
    }

    @Test
    fun `한도 도달 시 점유 카운트 — R1은 차단 없이 등록`() {
        val l = ledger(maxSlots = 40)
        // 39 채널 등록 (reserved 1 제외 한도 소진) → available 0
        repeat(39) { i -> l.register("H0UNCNT0", "%06d".format(i)) }
        assertThat(l.occupiedCount()).isEqualTo(39)
        assertThat(l.availableSlots()).isEqualTo(0)
        // R1: available 0이어도 추가 등록은 차단되지 않음 (회계만, eviction은 R2).
        l.register("H0UNCNT0", "999999")
        assertThat(l.occupiedCount()).isEqualTo(40)
        assertThat(l.availableSlots()).isEqualTo(0) // 음수 방지 coerce
    }

    @Test
    fun `snapshot — tr_id별 점유 카운트 + 한도 (관리자 실시간 상태)`() {
        val l = ledger(maxSlots = 40)
        l.register("H0UNCNT0", "005930")
        l.register("H0UNCNT0", "000660")
        l.register("H0UNASP0", "005930")
        val snap = l.snapshot()
        assertThat(snap.used).isEqualTo(3)
        assertThat(snap.max).isEqualTo(40)
        assertThat(snap.byTrId["H0UNCNT0"]).isEqualTo(2)
        assertThat(snap.byTrId["H0UNASP0"]).isEqualTo(1)
    }

    @Test
    fun `snapshot — codesByTrId drill-down (정렬된 종목코드 목록, 카운트와 일관)`() {
        val l = ledger(maxSlots = 40)
        // 의도적으로 역순 등록 → 정렬 검증.
        l.register("H0UNCNT0", "005930")
        l.register("H0UNCNT0", "000660")
        l.register("H0UNASP0", "035720")
        val snap = l.snapshot()
        // trId별 종목코드 목록이 정렬돼 노출 — "H0UNCNT0 N개가 뭔지" drill-down.
        assertThat(snap.codesByTrId["H0UNCNT0"]).containsExactly("000660", "005930")
        assertThat(snap.codesByTrId["H0UNASP0"]).containsExactly("035720")
        // 카운트(byTrId)와 코드 목록 크기 일관.
        assertThat(snap.codesByTrId["H0UNCNT0"]!!.size).isEqualTo(snap.byTrId["H0UNCNT0"])
    }

    @Test
    fun `snapshot — ledger 비활성 시 used 0`() {
        val l = ledger(enabled = false)
        l.register("H0UNCNT0", "005930")
        val snap = l.snapshot()
        assertThat(snap.used).isEqualTo(0)
        assertThat(snap.byTrId).isEmpty()
        assertThat(snap.codesByTrId).isEmpty()
    }

    @Test
    fun `release — 점유 취소 후 available 회복`() {
        val l = ledger(maxSlots = 40)
        l.register("H0UNCNT0", "005930")
        assertThat(l.occupiedCount()).isEqualTo(1)
        l.release("H0UNCNT0", "005930")
        assertThat(l.occupiedCount()).isEqualTo(0)
        // 미점유 release 멱등.
        l.release("H0UNCNT0", "005930")
        assertThat(l.occupiedCount()).isEqualTo(0)
    }

    @Test
    fun `OPSP0008 거부 회계 보정 — 점유 취소 + 거부 카운트`() {
        val l = ledger()
        l.register("H0UNASP0", "093370")
        assertThat(l.isOccupied("H0UNASP0", "093370")).isTrue()

        val outcome = l.onRejected(trId = "H0UNASP0", code = "093370")
        // 거부 → 채널 점유 취소 (회계 보정).
        assertThat(l.isOccupied("H0UNASP0", "093370")).isFalse()
        assertThat(outcome.occupiedAfter).isEqualTo(0)
        assertThat(l.rejectedTotal()).isEqualTo(1)
    }

    @Test
    fun `거부 후 재등록 가능 — 회계 보정으로 미점유 상태`() {
        val l = ledger()
        l.register("H0UNCNT0", "093370")
        l.onRejected("H0UNCNT0", "093370")
        assertThat(l.isOccupied("H0UNCNT0", "093370")).isFalse()
        // 재시도(R2 eviction이 slot 확보 후) — 재등록 가능.
        val occupied = l.register("H0UNCNT0", "093370")
        assertThat(occupied).isEqualTo(1)
        assertThat(l.isOccupied("H0UNCNT0", "093370")).isTrue()
    }

    @Test
    fun `반복 거부 — 첫 거부만 WARN throttle 통과`() {
        val l = ledger()
        l.register("H0UNCNT0", "093370")
        val first = l.onRejected("H0UNCNT0", "093370")
        // 같은 채널 재등록 후 즉시 재거부 — throttle window 내라 WARN 미발사.
        l.register("H0UNCNT0", "093370")
        val second = l.onRejected("H0UNCNT0", "093370")
        assertThat(first.warnEmitted).isTrue()
        assertThat(second.warnEmitted).isFalse()
        assertThat(l.rejectedTotal()).isEqualTo(2)
    }

    @Test
    fun `ledger 비활성 — 회계 no-op (롤백 toggle)`() {
        val l = ledger(enabled = false)
        l.register("H0UNCNT0", "005930")
        l.register("H0UNASP0", "005930")
        assertThat(l.occupiedCount()).isEqualTo(0)
        val outcome = l.onRejected("H0UNCNT0", "005930")
        assertThat(outcome.occupiedAfter).isEqualTo(0)
        assertThat(outcome.warnEmitted).isFalse()
        assertThat(l.rejectedTotal()).isEqualTo(0)
        assertThat(l.enabled()).isFalse()
    }

    @Test
    fun `Prometheus 게이지 — 점유·가용 노출`() {
        val registry = SimpleMeterRegistry()
        val l = ledger(maxSlots = 40, registry = registry)
        l.register("H0UNCNT0", "005930")
        l.register("H0UNASP0", "005930")

        val occupied = registry.find(WsSlotBudgetLedger.METRIC_SLOTS_OCCUPIED).gauge()
        val available = registry.find(WsSlotBudgetLedger.METRIC_SLOTS_AVAILABLE).gauge()
        assertThat(occupied).isNotNull
        assertThat(available).isNotNull
        assertThat(occupied!!.value()).isEqualTo(2.0)
        assertThat(available!!.value()).isEqualTo(37.0) // 40 - 1 - 2

        l.onRejected("H0UNASP0", "093370") // 미점유 거부도 카운터 증가
        val rejected =
            registry
                .find(WsSlotBudgetLedger.METRIC_SUBSCRIBE_REJECTED)
                .tag("msg_cd", "OPSP0008")
                .counter()
        assertThat(rejected).isNotNull
        assertThat(rejected!!.count()).isEqualTo(1.0)
    }

    @Test
    fun `MeterRegistry 부재 — graceful 부팅 (회계는 정상 동작)`() {
        val l = ledger(registry = null)
        l.register("H0UNCNT0", "005930")
        assertThat(l.occupiedCount()).isEqualTo(1)
        // metric 미등록이어도 거부 회계 보정은 정상.
        l.onRejected("H0UNCNT0", "005930")
        assertThat(l.occupiedCount()).isEqualTo(0)
        assertThat(l.rejectedTotal()).isEqualTo(1)
    }

    // ── 선제 admission 게이트 (R2 — 2026-06-22 장중 신규구독 silent 누락 fix) ────────

    @Test
    fun `tryRegister — 한도 내 미점유 채널은 admit + 점유`() {
        val l = ledger(maxSlots = 40)
        val r = l.tryRegister("H0UNCNT0", "005930")
        assertThat(r.admitted).isTrue()
        assertThat(r.alreadyOccupied).isFalse()
        assertThat(r.occupiedAfter).isEqualTo(1)
        assertThat(l.isOccupied("H0UNCNT0", "005930")).isTrue()
    }

    @Test
    fun `tryRegister — 이미 점유한 채널 재요청은 멱등 admit (재구독 절대 차단 금지)`() {
        val l = ledger(maxSlots = 40)
        l.tryRegister("H0UNCNT0", "005930")
        val again = l.tryRegister("H0UNCNT0", "005930")
        assertThat(again.admitted).isTrue()
        assertThat(again.alreadyOccupied).isTrue()
        // 중복 점유 0 — 카운트 1 유지.
        assertThat(l.occupiedCount()).isEqualTo(1)
    }

    @Test
    fun `tryRegister — 한도(40) 초과 시 deny + 점유 안 함 (silent 누락 선제 차단)`() {
        val l = ledger(maxSlots = 40)
        // 39 채널 점유(reserved 1 제외 한도 소진) → available 0.
        repeat(39) { i -> assertThat(l.tryRegister("H0UNCNT0", "%06d".format(i)).admitted).isTrue() }
        assertThat(l.occupiedCount()).isEqualTo(39)
        // 40번째 신규 채널은 deny — KIS 발사 보류(acked-not-streaming 회피).
        val denied = l.tryRegister("H0UNCNT0", "999999")
        assertThat(denied.admitted).isFalse()
        assertThat(denied.alreadyOccupied).isFalse()
        assertThat(l.isOccupied("H0UNCNT0", "999999")).isFalse() // 점유하지 않음
        assertThat(l.occupiedCount()).isEqualTo(39) // 변화 없음
        assertThat(l.admissionDeniedTotal()).isEqualTo(1)
    }

    @Test
    fun `tryRegister — 체결가+호가 합산이 한도를 채우면 신규 체결가도 deny (라이브 2026-06-22 재현)`() {
        // 라이브 근본 원인 재현: 체결가 + 호가가 같은 40 한도를 공유한다.
        // reserved 1 제외 가용 39. 호가 20 + 체결가 19 = 39 채널 점유.
        val l = ledger(maxSlots = 40)
        repeat(20) { i -> l.tryRegister("H0UNASP0", "1%05d".format(i)) }
        repeat(19) { i -> l.tryRegister("H0UNCNT0", "2%05d".format(i)) }
        assertThat(l.occupiedCount()).isEqualTo(39)
        // 추가 체결가(삼성전자) 신규 구독 — 한도 초과로 deny (frame silent 누락 선제 차단).
        val samsung = l.tryRegister("H0UNCNT0", "005930")
        assertThat(samsung.admitted).isFalse()
        assertThat(l.isOccupied("H0UNCNT0", "005930")).isFalse()
    }

    @Test
    fun `tryRegister — release로 slot 회수되면 다시 admit (evict 후 재진입)`() {
        val l = ledger(maxSlots = 40)
        repeat(39) { i -> l.tryRegister("H0UNCNT0", "%06d".format(i)) }
        assertThat(l.tryRegister("H0UNCNT0", "999999").admitted).isFalse()
        // 점유 1건 해제 → 가용 1 → 재admit 가능.
        l.release("H0UNCNT0", "000000")
        val readmit = l.tryRegister("H0UNCNT0", "999999")
        assertThat(readmit.admitted).isTrue()
        assertThat(l.isOccupied("H0UNCNT0", "999999")).isTrue()
    }

    @Test
    fun `tryRegister — admission 비활성(롤백 toggle) 시 한도 초과여도 무조건 admit (R1 회귀)`() {
        val l = ledger(maxSlots = 40, admissionEnabled = false)
        // 한도 초과까지 채워도 deny 0 — R1 회귀(발사 직후 register만, 차단 없음).
        repeat(45) { i -> assertThat(l.tryRegister("H0UNCNT0", "%06d".format(i)).admitted).isTrue() }
        assertThat(l.occupiedCount()).isEqualTo(45)
        assertThat(l.admissionDeniedTotal()).isEqualTo(0)
        assertThat(l.admissionEnabled()).isFalse()
    }

    @Test
    fun `tryRegister — ledger 비활성 시 무조건 admit (회계 no-op)`() {
        val l = ledger(enabled = false)
        val r = l.tryRegister("H0UNCNT0", "005930")
        assertThat(r.admitted).isTrue()
        assertThat(l.occupiedCount()).isEqualTo(0) // 회계 no-op
    }

    @Test
    fun `tryRegister deny — admission denied 카운터 노출`() {
        val registry = SimpleMeterRegistry()
        val l = ledger(maxSlots = 40, registry = registry)
        repeat(39) { i -> l.tryRegister("H0UNCNT0", "%06d".format(i)) }
        l.tryRegister("H0UNCNT0", "999999") // deny
        val denied =
            registry
                .find(WsSlotBudgetLedger.METRIC_ADMISSION_DENIED)
                .tag("tr_id", "H0UNCNT0")
                .counter()
        assertThat(denied).isNotNull
        assertThat(denied!!.count()).isEqualTo(1.0)
    }
}
