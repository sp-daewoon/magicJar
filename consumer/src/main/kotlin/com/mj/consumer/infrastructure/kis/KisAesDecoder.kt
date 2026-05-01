package com.mj.consumer.infrastructure.kis

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * KIS WS 체결통보 페이로드 AES256/CBC/PKCS5 복호.
 * subscribe 응답에 포함된 iv·key 사용. spec §5.5.
 *
 * - key/iv는 KIS subscribe 응답의 body.output.{key,iv} 문자열 그대로 (UTF-8 byte)
 * - ciphertext는 base64 인코딩된 데이터 프레임
 * - 결과는 ^ 구분 26 컬럼 평문
 */
object KisAesDecoder {
    fun decode(
        cipherTextB64: String,
        key: String,
        iv: String,
    ): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(StandardCharsets.UTF_8)),
        )
        val ct = Base64.getDecoder().decode(cipherTextB64)
        val plain = cipher.doFinal(ct)
        return String(plain, StandardCharsets.UTF_8)
    }
}
