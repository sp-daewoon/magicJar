package com.mj.consumer.infrastructure.secret

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec

class AesGcmSecretCipherTest {
    // 32바이트 = 256bit AES key (테스트 fixture)
    // 32바이트(256bit) AES key fixture
    private val masterKey =
        SecretKeySpec(
            Base64.getDecoder().decode("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="),
            "AES",
        )
    private val cipher = AesGcmSecretCipher(masterKey)

    @Test
    fun `encrypt then decrypt yields original`() {
        val plain = "PSAKi7XV..fake-app-key..xQ"
        val encrypted = cipher.encrypt(plain)
        encrypted shouldNotBe plain
        encrypted shouldNotContain plain
        cipher.decrypt(encrypted) shouldBe plain
    }

    @Test
    fun `encrypt produces different output for same input due to random IV`() {
        val plain = "secret"
        val a = cipher.encrypt(plain)
        val b = cipher.encrypt(plain)
        a shouldNotBe b
    }

    @Test
    fun `decrypt fails on tampered ciphertext`() {
        val encrypted = cipher.encrypt("plain")
        // 끝 4글자 변조 → GCM tag 불일치
        val tampered = encrypted.dropLast(4) + "AAAA"
        assertThrows<AEADBadTagException> {
            cipher.decrypt(tampered)
        }
    }
}
