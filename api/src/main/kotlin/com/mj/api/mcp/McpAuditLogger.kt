package com.mj.api.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.mj.api.mcp.infrastructure.McpCallLogJpa
import com.mj.api.mcp.infrastructure.McpCallLogJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * MCP audit logger (T17 / spec §6).
 *
 * 모든 JSON-RPC 호출(성공·실패·인증실패·rate limit) 기록.
 * INSERT 실패도 호출 본흐름을 막지 않는다 (try-catch + warn 로그).
 *
 * Propagation.REQUIRES_NEW: tool 실행 트랜잭션과 분리 — tool 실패해도 audit는 남는다.
 */
@Component
class McpAuditLogger(
    private val repo: McpCallLogJpaRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(McpAuditLogger::class.java)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(record: AuditRecord) {
        try {
            val params = record.params?.let { runCatching { objectMapper.writeValueAsString(it) }.getOrNull() }
            val entity =
                McpCallLogJpa(
                    requestId = record.requestId.take(64),
                    clientKeyHash = record.clientKeyHash,
                    clientAddress = record.clientAddress?.take(45),
                    method = record.method.take(64),
                    toolName = record.toolName?.take(64),
                    params = params,
                    responseSizeBytes = record.responseSizeBytes,
                    latencyMs = record.latencyMs,
                    errorCode = record.errorCode,
                    errorMessage = record.errorMessage?.take(2000),
                    calledAt = record.calledAt,
                )
            repo.save(entity)
        } catch (e: Exception) {
            log.warn("[MCP_AUDIT] insert failed (non-blocking): {}", e.message)
        }
    }
}

data class AuditRecord(
    val requestId: String,
    val clientKeyHash: String?,
    val clientAddress: String?,
    val method: String,
    val toolName: String?,
    val params: Any?,
    val responseSizeBytes: Int?,
    val latencyMs: Int?,
    val errorCode: Int?,
    val errorMessage: String?,
    val calledAt: Instant,
)
