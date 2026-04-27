package com.mj.consumer.messaging.listener

import com.mj.consumer.application.ai.service.SentimentAnalyzer
import com.mj.consumer.messaging.producer.NewsAnalyzedPublisher
import com.mj.domain.journal.NewsItem
import com.mj.domain.news.event.NewsAnalyzedEvent
import com.mj.domain.news.event.NewsRawEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * news.raw.v1 → SentimentAnalyzer → NewsAnalyzedPublisher.
 *
 * AI 감성분석 실패 시 neutral 기본값으로 발행한다.
 */
@Component
class NewsAnalyzerListener(
    private val sentimentAnalyzer: SentimentAnalyzer,
    private val newsAnalyzedPublisher: NewsAnalyzedPublisher,
) {
    private val log = LoggerFactory.getLogger(NewsAnalyzerListener::class.java)

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.newsRaw:news.raw.v1}"],
        groupId = "\${spring.kafka.consumer.group-id:magicjar-consumer}",
    )
    fun onNewsRaw(event: NewsRawEvent) {
        log.debug("NewsAnalyzerListener received (newsId={})", event.newsId)
        runCatching {
            val newsItem =
                NewsItem(
                    id = event.newsId,
                    source = event.source,
                    publishedAt = event.publishedAt,
                    title = event.title,
                    body = event.body,
                    instruments = event.instruments,
                )
            val analysisOpt = sentimentAnalyzer.analyze(listOf(newsItem))
            val analysis = if (analysisOpt.isPresent) analysisOpt.get() else null
            val analyzed =
                NewsAnalyzedEvent(
                    newsId = event.newsId,
                    source = event.source,
                    publishedAt = event.publishedAt,
                    title = event.title,
                    sentiment = analysis?.overallSentiment ?: "NEUTRAL",
                    sentimentScore = analysis?.confidence?.toDouble() ?: 0.0,
                    summary = analysis?.summary,
                    instruments = event.instruments,
                    analyzedAt = Instant.now(),
                )
            newsAnalyzedPublisher.publish(analyzed)
        }.onFailure { ex ->
            log.warn("NewsAnalyzerListener failed (newsId={}): {}", event.newsId, ex.message)
        }
    }
}
