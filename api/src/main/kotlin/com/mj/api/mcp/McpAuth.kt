package com.mj.api.mcp

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest

/**
 * MCP 인증 (T17 / spec §5-1).
 *
 * 1) X-MCP-Key 헤더 일치 → OK
 * 2) MAGICJAR_MCP_API_KEY 미설정 + remoteAddress ∈ {127.0.0.1, ::1, 0:0:0:0:0:0:0:1} → OK (dev fallback)
 * 3) 둘 다 실패 → -32002 AUTHENTICATION_REQUIRED
 *
 * 반환값은 audit 로그에 쓰일 client identity hash(prefix 16자)·null이면 익명.
 */
@Component
class McpAuth(
    @Value("\${magicjar.mcp.api-key:}") private val configuredKey: String,
) {
    fun authenticate(
        providedKey: String?,
        remoteAddress: String?,
    ): AuthResult {
        val keySet = configuredKey.isNotBlank()
        val provided = providedKey?.takeIf { it.isNotBlank() }

        if (keySet && provided != null && constantTimeEquals(provided, configuredKey)) {
            return AuthResult(authenticated = true, clientKeyHash = sha256Prefix(provided))
        }
        if (!keySet && isLocalhost(remoteAddress)) {
            // dev fallback — API key 미설정 + localhost client
            return AuthResult(authenticated = true, clientKeyHash = null)
        }
        return AuthResult(authenticated = false, clientKeyHash = null)
    }

    private fun isLocalhost(addr: String?): Boolean {
        if (addr == null) return false
        return addr == "127.0.0.1" ||
            addr == "::1" ||
            addr == "0:0:0:0:0:0:0:1" ||
            addr.startsWith("127.") // 127.x 전부 loopback
    }

    private fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }

    private fun sha256Prefix(raw: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it) } // 16자
    }

    data class AuthResult(
        val authenticated: Boolean,
        val clientKeyHash: String?,
    )
}
