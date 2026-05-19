package kr.spdaewoon.magicjar.api.adapter.redis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

/**
 * KisCacheInvalidator 단위 테스트 — T-1·T-2 prefix 통합 + 전환기 안전망 가드.
 *
 * 핵심 회귀 가드:
 * - deleteAccessToken 호출 시 신규 표준 `kis:token:` + 결함 `access_token:` **둘 다** DEL
 * - prefix 상수가 의도와 일치 (T-1·T-2 LOCK)
 */
class KisCacheInvalidatorTest {
    private val redis: StringRedisTemplate = mock()
    private val sut = KisCacheInvalidator(redis)

    @Test
    fun `T-1 prefix 상수 — TOKEN_KEY_PREFIX 는 kis_token`() {
        assertThat(KisCacheInvalidator.TOKEN_KEY_PREFIX).isEqualTo("kis:token")
    }

    @Test
    fun `T-2 전환기 안전망 — LEGACY_TOKEN_KEY_PREFIX 는 access_token`() {
        assertThat(KisCacheInvalidator.LEGACY_TOKEN_KEY_PREFIX).isEqualTo("access_token")
    }

    @Test
    fun `T-1·T-2 — deleteAccessToken 호출 시 두 prefix 모두 DEL (전환기 안전망)`() {
        whenever(redis.delete(any<String>())).thenReturn(true)

        sut.deleteAccessToken(userId = "u-001", accountType = "REAL")

        verify(redis).delete(eq("kis:token:u-001:REAL"))
        verify(redis).delete(eq("access_token:u-001:REAL"))
    }

    @Test
    fun `T-1·T-2 — MOCK accountType 도 두 prefix DEL`() {
        whenever(redis.delete(any<String>())).thenReturn(true)

        sut.deleteAccessToken(userId = "u-001", accountType = "MOCK")

        verify(redis).delete(eq("kis:token:u-001:MOCK"))
        verify(redis).delete(eq("access_token:u-001:MOCK"))
    }

    @Test
    fun `setKeyChangedLock — 5s TTL`() {
        val ops: ValueOperations<String, String> = mock()
        whenever(redis.opsForValue()).thenReturn(ops)

        sut.setKeyChangedLock(userId = "u-001", accountType = "REAL")

        verify(ops).set(
            eq("kis:key-changed-lock:u-001:REAL"),
            eq("1"),
            eq(Duration.ofSeconds(5)),
        )
    }

    @Test
    fun `isKeyChangedLockActive — Redis hasKey true 반환`() {
        whenever(redis.hasKey("kis:key-changed-lock:u-001:REAL")).thenReturn(true)

        val active = sut.isKeyChangedLockActive(userId = "u-001", accountType = "REAL")

        assertThat(active).isTrue()
    }

    @Test
    fun `isKeyChangedLockActive — Redis hasKey false 반환`() {
        whenever(redis.hasKey("kis:key-changed-lock:u-001:REAL")).thenReturn(false)

        val active = sut.isKeyChangedLockActive(userId = "u-001", accountType = "REAL")

        assertThat(active).isFalse()
    }

    @Test
    fun `deleteApprovalKey — kis_approval prefix`() {
        whenever(redis.delete(any<String>())).thenReturn(true)

        sut.deleteApprovalKey(userId = "u-001")

        verify(redis).delete(eq("kis:approval:u-001"))
    }
}
