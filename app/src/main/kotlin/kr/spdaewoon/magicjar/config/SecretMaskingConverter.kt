package kr.spdaewoon.magicjar.config

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.pattern.CompositeConverter

/**
 * 로그 메시지에서 KIS appkey/appsecret, ANTHROPIC_API_KEY 등 민감정보를 마스킹한다.
 * `%mask(%msg)` 형태의 composite converter — child 패턴(%msg)의 결과 문자열을 input으로 받는다.
 */
class SecretMaskingConverter : CompositeConverter<ILoggingEvent>() {
    private val patterns =
        listOf(
            Regex("(appkey|app-key|app_key)=([^\\s\"'&]+)", RegexOption.IGNORE_CASE),
            Regex("(appsecret|app-secret|app_secret)=([^\\s\"'&]+)", RegexOption.IGNORE_CASE),
            Regex("(anthropic[_-]?api[_-]?key)[=:]\\s*([^\\s\"'&]+)", RegexOption.IGNORE_CASE),
            Regex("Bearer\\s+([A-Za-z0-9\\-_.]{20,})", RegexOption.IGNORE_CASE),
        )

    override fun transform(
        event: ILoggingEvent,
        input: String,
    ): String {
        var msg = input
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
