package kr.spdaewoon.magicjar.config

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * 로그 메시지에서 KIS appkey/appsecret, ANTHROPIC_API_KEY 등 민감정보를 마스킹한다.
 * application.yml이나 기타 로그에서 실제 키 문자열이 노출되지 않도록 한다.
 */
class SecretMaskingConverter : ClassicConverter() {
    private val patterns =
        listOf(
            Regex("(appkey|app-key|app_key)=([^\\s\"'&]+)", RegexOption.IGNORE_CASE),
            Regex("(appsecret|app-secret|app_secret)=([^\\s\"'&]+)", RegexOption.IGNORE_CASE),
            Regex("(anthropic[_-]?api[_-]?key)[=:]\\s*([^\\s\"'&]+)", RegexOption.IGNORE_CASE),
            Regex("Bearer\\s+([A-Za-z0-9\\-_.]{20,})", RegexOption.IGNORE_CASE),
        )

    override fun convert(event: ILoggingEvent): String {
        var msg = event.formattedMessage ?: ""
        for (p in patterns) {
            msg = p.replace(msg) { match -> maskMatch(match.groupValues) }
        }
        return msg
    }

    private fun maskMatch(groups: List<String>): String =
        when {
            groups.size >= 3 -> "${groups[1]}=***"
            else -> "Bearer ***"
        }
}
