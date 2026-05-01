package com.mj.domain.secret

/**
 * 암호화/복호화 추상.
 * 1차 구현은 AES-GCM(AesGcmSecretCipher), 후속 사이클에 Vault로 교체 가능.
 * spec §8.3 멀티테넌트 확장 path.
 */
interface SecretCipher {
    fun encrypt(plain: String): String

    fun decrypt(encoded: String): String
}
