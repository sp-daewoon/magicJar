package kr.spdaewoon.magicjar.consumer.order

import kr.spdaewoon.magicjar.consumer.ws.KisWebSocketBridge
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/** Read-only admission decision backed only by the execution-notice status exposed by the WS bridge. */
@Component
class ExecutionNoticeReadinessGuard(
    private val bridgeProvider: ObjectProvider<KisWebSocketBridge>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun evaluate(expectedMode: TradingMode = TradingMode.REAL): ExecutionReadinessDecision {
        if (expectedMode != TradingMode.REAL) {
            return ExecutionReadinessDecision.Denied(
                state = "MODE_MISMATCH",
                reason = "EXECUTION_NOTICE_MODE_NOT_CONFIGURED",
            )
        }
        val bridge =
            bridgeProvider.getIfAvailable()
                ?: return ExecutionReadinessDecision.Denied(
                    state = "BEAN_ABSENT",
                    reason = "KIS_WEBSOCKET_BRIDGE_UNAVAILABLE",
                )

        return try {
            val snapshot = bridge.wsStatusSnapshot()
            val planeMatches =
                snapshot.executionMode == TradingMode.REAL.name &&
                    snapshot.executionTrId == REAL_EXECUTION_TR_ID &&
                    snapshot.executionTransport == REAL_EXECUTION_TRANSPORT
            if (!planeMatches) {
                ExecutionReadinessDecision.Denied(
                    state = "MODE_MISMATCH",
                    reason = "EXECUTION_NOTICE_MODE_MISMATCH",
                )
            } else if (
                snapshot.executionState == READY &&
                snapshot.executionConnected &&
                snapshot.executionSubscribed &&
                snapshot.executionAck &&
                snapshot.executionAes
            ) {
                ExecutionReadinessDecision.Allowed
            } else {
                ExecutionReadinessDecision.Denied(
                    state = allowlisted(snapshot.executionState, ALLOWED_STATES, "UNKNOWN"),
                    reason = allowlisted(snapshot.executionDegradedReason, ALLOWED_REASONS, "UNSPECIFIED"),
                )
            }
        } catch (e: Exception) {
            log.error("KIS execution readiness status 조회 실패 (new BUY fail-closed): type={}", e.javaClass.simpleName)
            ExecutionReadinessDecision.Denied(
                state = "STATUS_ERROR",
                reason = "STATUS_QUERY_FAILED",
            )
        }
    }

    private fun allowlisted(
        value: String?,
        allowed: Set<String>,
        fallback: String,
    ): String = value?.takeIf(allowed::contains) ?: fallback

    private companion object {
        const val READY = "READY"
        const val REAL_EXECUTION_TR_ID = "H0STCNI0"
        const val REAL_EXECUTION_TRANSPORT = "PROD_SHARED"
        val ALLOWED_STATES = setOf("DEGRADED", "NOT_CONFIGURED")
        val ALLOWED_REASONS =
            setOf(
                "MOCK_EXECUTION_NOT_CONFIGURED_PHASE1",
                "HTS_ID_NOT_CONFIGURED",
                "MARKET_SESSION_DISCONNECTED",
                "SUBSCRIBE_NOT_ATTEMPTED",
                "SUBSCRIBE_ACK_PENDING",
                "AES_KEY_PENDING",
            )
    }
}

sealed interface ExecutionReadinessDecision {
    data object Allowed : ExecutionReadinessDecision

    data class Denied(
        val state: String,
        val reason: String,
    ) : ExecutionReadinessDecision {
        fun rejectionReason(): String = "EXECUTION_NOTICE_NOT_READY:state=$state:reason=$reason"
    }
}
