package com.mj.api.mcp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpAuthTest {
    @Test
    fun `API key 일치 시 인증 성공 + hash 반환`() {
        val auth = McpAuth(configuredKey = "secret-key-123")
        val r = auth.authenticate(providedKey = "secret-key-123", remoteAddress = "10.0.0.5")
        assertTrue(r.authenticated)
        assertNotNull(r.clientKeyHash)
    }

    @Test
    fun `API key 불일치 시 실패`() {
        val auth = McpAuth(configuredKey = "secret-key-123")
        val r = auth.authenticate(providedKey = "wrong", remoteAddress = "10.0.0.5")
        assertFalse(r.authenticated)
    }

    @Test
    fun `API key 미설정 + localhost client 통과 (dev fallback)`() {
        val auth = McpAuth(configuredKey = "")
        val r = auth.authenticate(providedKey = null, remoteAddress = "127.0.0.1")
        assertTrue(r.authenticated)
        assertNull(r.clientKeyHash) // 익명 client
    }

    @Test
    fun `API key 미설정 + non-localhost client는 차단`() {
        val auth = McpAuth(configuredKey = "")
        val r = auth.authenticate(providedKey = null, remoteAddress = "192.168.1.10")
        assertFalse(r.authenticated)
    }

    @Test
    fun `IPv6 loopback 통과`() {
        val auth = McpAuth(configuredKey = "")
        val r = auth.authenticate(providedKey = null, remoteAddress = "::1")
        assertTrue(r.authenticated)
    }
}
