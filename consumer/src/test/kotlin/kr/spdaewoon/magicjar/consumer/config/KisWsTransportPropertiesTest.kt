package kr.spdaewoon.magicjar.consumer.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class KisWsTransportPropertiesTest {
    @Test
    fun `legacy user-id는 credential owner fallback으로만 사용한다`() {
        val properties =
            KisWsTransportProperties(
                urlProd = "ws://ops.koreainvestment.com:21000",
                credentialOwnerId = "",
                htsId = "",
                userId = "legacy-owner",
            )

        assertThat(properties.resolvedCredentialOwnerId).isEqualTo("legacy-owner")
        assertThat(properties.htsId).isBlank()
    }

    @Test
    fun `명시 credential owner는 legacy user-id보다 우선한다`() {
        val properties =
            KisWsTransportProperties(
                urlProd = "ws://ops.koreainvestment.com:21000",
                credentialOwnerId = "owner-explicit",
                htsId = "hts-explicit",
                userId = "legacy-owner",
            )

        assertThat(properties.resolvedCredentialOwnerId).isEqualTo("owner-explicit")
        assertThat(properties.htsId).isEqualTo("hts-explicit")
    }

    @Test
    fun `prod market transport가 21000이 아니면 startup validation 실패`() {
        assertThatThrownBy {
            KisWsTransportProperties(urlProd = "ws://ops.koreainvestment.com:31000")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("21000")
    }

    @Test
    fun `prod market transport URL scheme이 websocket이 아니면 startup validation 실패`() {
        assertThatThrownBy {
            KisWsTransportProperties(urlProd = "https://ops.koreainvestment.com:21000")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("ws")
    }

    @Test
    fun `KIS 직접 endpoint에 wss scheme을 설정하면 startup validation 실패`() {
        assertThatThrownBy {
            KisWsTransportProperties(urlProd = "wss://ops.koreainvestment.com:21000")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("공식 ws scheme")
    }

    @Test
    fun `공식 호스트가 아닌 21000 endpoint는 startup validation 실패`() {
        assertThatThrownBy {
            KisWsTransportProperties(urlProd = "ws://attacker.example:21000")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("공식 prod endpoint")
    }

    @Test
    fun `공식 endpoint에 userInfo path query fragment가 포함되면 startup validation 실패`() {
        listOf(
            "ws://user@ops.koreainvestment.com:21000",
            "ws://ops.koreainvestment.com:21000/tryitout",
            "ws://ops.koreainvestment.com:21000?redirect=attacker",
            "ws://ops.koreainvestment.com:21000#fragment",
        ).forEach { url ->
            assertThatThrownBy { KisWsTransportProperties(urlProd = url) }
                .describedAs(url)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("공식 prod endpoint")
        }
    }
}
