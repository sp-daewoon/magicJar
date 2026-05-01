package com.mj.consumer.infrastructure.secret

import com.mj.domain.secret.SecretCipher
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM. IV 12바이트 랜덤 → ciphertext 앞에 prepend → base64.
 * spec §8.2.
 *
 * - master key는 32바이트(256bit) AES key. base64 디코드된 raw bytes.
 * - 매 encrypt 시 새 IV 생성 → 같은 평문이라도 ciphertext 매번 다름.
 * - 복호 실패(tampered/wrong key) 시 javax.crypto.AEADBadTagException.
 */
class AesGcmSecretCipher(
    private val key: SecretKey,
) : SecretCipher {
    override fun encrypt(plain: String): String {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ct)
    }

    override fun decrypt(encoded: String): String {
        val raw = Base64.getDecoder().decode(encoded)
        require(raw.size > IV_LEN) { "ciphertext too short" }
        val iv = raw.copyOfRange(0, IV_LEN)
        val ct = raw.copyOfRange(IV_LEN, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ct), StandardCharsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val TAG_BITS = 128
    }
}
