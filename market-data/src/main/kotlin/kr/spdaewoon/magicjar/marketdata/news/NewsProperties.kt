package kr.spdaewoon.magicjar.marketdata.news

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "magicjar.marketdata.news")
data class NewsProperties(
    val rssUrls: List<String> = listOf("https://finance.naver.com/news/news_list.naver?mode=RANK"),
    val userAgent: String = "magicjar/0.1 (+https://github.com/sp-daewoon/magicJar)",
    val enabled: Boolean = true,
)
