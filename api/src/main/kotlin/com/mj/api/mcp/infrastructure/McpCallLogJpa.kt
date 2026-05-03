package com.mj.api.mcp.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * mcp_call_log JPA entity (T17 / V18 / spec §6).
 *
 * 모든 JSON-RPC 호출 audit. API key는 hash prefix(16자)만, 원문 X.
 */
@Entity
@Table(name = "mcp_call_log")
class McpCallLogJpa(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "request_id", nullable = false, length = 64)
    var requestId: String = "",
    @Column(name = "client_key_hash", length = 64)
    var clientKeyHash: String? = null,
    @Column(name = "client_address", length = 45)
    var clientAddress: String? = null,
    @Column(name = "method", nullable = false, length = 64)
    var method: String = "",
    @Column(name = "tool_name", length = 64)
    var toolName: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", columnDefinition = "jsonb")
    var params: String? = null,
    @Column(name = "response_size_bytes")
    var responseSizeBytes: Int? = null,
    @Column(name = "latency_ms")
    var latencyMs: Int? = null,
    @Column(name = "error_code")
    var errorCode: Int? = null,
    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String? = null,
    @Column(name = "called_at", nullable = false)
    var calledAt: Instant = Instant.now(),
)

interface McpCallLogJpaRepository : JpaRepository<McpCallLogJpa, Long>
