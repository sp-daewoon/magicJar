package kr.spdaewoon.magicjar.consumer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

/**
 * KIS 시장 데이터 WebSocket transport와 identity 계약.
 *
 * 시장 데이터 물리 세션은 runtime mode와 무관하게 REAL approval + prod :21000만 사용한다.
 * [credentialOwnerId]는 user_kis_credential 조회용 내부 식별자이며 [htsId]는 H0STCNI0 tr_key다.
 * legacy [userId]는 credential owner fallback으로만 허용하며 HTS ID로 대체하지 않는다.
 */
@ConfigurationProperties(prefix = "magicjar.kis.ws")
data class KisWsTransportProperties(
    val urlProd: String = "ws://ops.koreainvestment.com:21000",
    val credentialOwnerId: String = "",
    val htsId: String = "",
    val userId: String = "",
) {
    init {
        val uri =
            runCatching { URI(urlProd) }
                .getOrElse { throw IllegalArgumentException("magicjar.kis.ws.url-prod는 유효한 ws URL이어야 합니다", it) }
        require(uri.scheme == "ws") {
            "magicjar.kis.ws.url-prod는 KIS 공식 ws scheme이어야 합니다"
        }
        require(
            uri.host == "ops.koreainvestment.com" &&
                uri.port == 21000 &&
                uri.userInfo == null &&
                (uri.path.isNullOrEmpty() || uri.path == "/") &&
                uri.query == null &&
                uri.fragment == null,
        ) {
            "magicjar.kis.ws.url-prod는 공식 prod endpoint ws://ops.koreainvestment.com:21000 이어야 합니다"
        }
    }

    val resolvedCredentialOwnerId: String
        get() = credentialOwnerId.ifBlank { userId }
}
