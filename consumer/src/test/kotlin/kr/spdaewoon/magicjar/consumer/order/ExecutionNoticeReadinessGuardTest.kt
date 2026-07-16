package kr.spdaewoon.magicjar.consumer.order

import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.consumer.ws.KisWebSocketBridge
import kr.spdaewoon.magicjar.consumer.ws.WsStatusSnapshot
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider

class ExecutionNoticeReadinessGuardTest {
    @Test
    fun `READY snapshot allows a new buy`() {
        val bridge = mockk<KisWebSocketBridge>()
        every { bridge.wsStatusSnapshot() } returns snapshot("READY", null)

        assertThat(guard(bridge).evaluate()).isEqualTo(ExecutionReadinessDecision.Allowed)
    }

    @Test
    fun `non-ready snapshot denies without propagating untrusted state and reason`() {
        val bridge = mockk<KisWebSocketBridge>()
        every { bridge.wsStatusSnapshot() } returns snapshot("DEGRADED<script>", "ACK pending: user=secret@example.com")

        assertThat(guard(bridge).evaluate()).isEqualTo(
            ExecutionReadinessDecision.Denied(
                state = "UNKNOWN",
                reason = "UNSPECIFIED",
            ),
        )
    }

    @Test
    fun `missing bridge denies instead of assuming ready`() {
        assertThat(guard(null).evaluate()).isEqualTo(
            ExecutionReadinessDecision.Denied("BEAN_ABSENT", "KIS_WEBSOCKET_BRIDGE_UNAVAILABLE"),
        )
    }

    @Test
    fun `status exception denies without exposing exception message`() {
        val bridge = mockk<KisWebSocketBridge>()
        every { bridge.wsStatusSnapshot() } throws IllegalStateException("token=top-secret")

        assertThat(guard(bridge).evaluate()).isEqualTo(
            ExecutionReadinessDecision.Denied("STATUS_ERROR", "STATUS_QUERY_FAILED"),
        )
    }

    @Test
    fun `MOCK resolved order mode denies even when REAL snapshot is READY`() {
        val bridge = mockk<KisWebSocketBridge>()
        every { bridge.wsStatusSnapshot() } returns snapshot("READY", null)

        assertThat(guard(bridge).evaluate(TradingMode.MOCK)).isEqualTo(
            ExecutionReadinessDecision.Denied("MODE_MISMATCH", "EXECUTION_NOTICE_MODE_NOT_CONFIGURED"),
        )
    }

    @Test
    fun `READY state with mismatched execution plane denies`() {
        val bridge = mockk<KisWebSocketBridge>()
        every { bridge.wsStatusSnapshot() } returns snapshot("READY", null).copy(executionMode = "MOCK", executionTrId = "H0STCNI9")

        assertThat(guard(bridge).evaluate(TradingMode.REAL)).isEqualTo(
            ExecutionReadinessDecision.Denied("MODE_MISMATCH", "EXECUTION_NOTICE_MODE_MISMATCH"),
        )
    }

    @Test
    fun `READY fields cannot allow when execution transport is disconnected`() {
        val bridge = mockk<KisWebSocketBridge>()
        every { bridge.wsStatusSnapshot() } returns snapshot("READY", null).copy(executionConnected = false)

        assertThat(guard(bridge).evaluate(TradingMode.REAL)).isEqualTo(
            ExecutionReadinessDecision.Denied("UNKNOWN", "UNSPECIFIED"),
        )
    }

    private fun guard(bridge: KisWebSocketBridge?): ExecutionNoticeReadinessGuard {
        val provider = mockk<ObjectProvider<KisWebSocketBridge>>()
        every { provider.getIfAvailable() } returns bridge
        return ExecutionNoticeReadinessGuard(provider)
    }

    private fun snapshot(
        state: String,
        reason: String?,
    ): WsStatusSnapshot =
        WsStatusSnapshot(
            connected = state == "READY",
            sessionCid = null,
            reconnects24h = 0,
            lastConnectedAtMs = null,
            executionSubscribed = state == "READY",
            executionConnected = state == "READY",
            executionAck = state == "READY",
            executionAes = state == "READY",
            executionMode = "REAL",
            executionTrId = "H0STCNI0",
            executionTransport = "PROD_SHARED",
            executionUserIdMasked = null,
            executionState = state,
            executionDegradedReason = reason,
        )
}
