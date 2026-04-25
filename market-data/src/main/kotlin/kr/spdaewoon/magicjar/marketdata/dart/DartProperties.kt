package kr.spdaewoon.magicjar.marketdata.dart

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "dart")
data class DartProperties(
    val apiKey: String? = null,
    val baseUrl: String = "https://opendart.fss.or.kr",
) {
    fun hasCredentials(): Boolean = !apiKey.isNullOrBlank()
}
