package com.mj.consumer.infrastructure.kis

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * KIS subscribe 응답의 iv·key는 16바이트 문자열로 들어온다.
 * 라운드트립 검증: 같은 iv·key로 암호화 후 복호하면 원문이 나와야 한다.
 */
class KisAesDecoderTest {
    private val iv = "abcdef1234567890"
    private val key = "0123456789abcdef" // AES-128 (KIS subscribe 응답이 16B 키 제공)

    private fun encryptForTest(
        plain: String,
        key: String,
        iv: String,
    ): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
        )
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(ct)
    }

    @Test
    fun `decode AES with iv and key round-trip`() {
        val plain =
            "USERID^50185338^0000123456^^^^^^005930^1^70000^143025^N^2^Y^06010^1^TEST^^^^^^"
        val cipher = encryptForTest(plain, key, iv)

        val decoded = KisAesDecoder.decode(cipher, key, iv)
        decoded shouldBe plain
    }

    @Test
    fun `parse decoded raw into KisExecutionNoticeRaw 26 columns`() {
        val plain =
            "USERID^50185338^0000123456^^^^^^005930^1^70000^143025^N^2^Y^06010^1^TEST^^^^^^"
        val cipher = encryptForTest(plain, key, iv)
        val decoded = KisAesDecoder.decode(cipher, key, iv)

        val raw = KisExecutionNoticeRaw.fromColumns(decoded.split("^"))
        raw.acntNo shouldBe "50185338"
        raw.orderNo shouldBe "0000123456"
        raw.stockCode shouldBe "005930"
        raw.executedQty shouldBe 1
        raw.cntgYn shouldBe "2" // 체결통보
        raw.krxFwdgOrdOrgno shouldBe "06010"
    }
}
