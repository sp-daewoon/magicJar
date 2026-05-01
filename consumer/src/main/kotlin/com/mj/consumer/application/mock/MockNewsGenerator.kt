package com.mj.consumer.application.mock

import com.mj.consumer.messaging.producer.NewsAnalyzedPublisher
import com.mj.domain.news.event.NewsAnalyzedEvent
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * Mock 뉴스 감성 분석 발행기. 실 Anthropic/Gemini API 키 없이 AI 카드 시각 검증.
 *
 * 동작:
 *   - intervalMs마다 symbols 중 1종목 무작위 선택
 *   - title/summary는 사전 정의 풀에서 sentiment 별로 무작위 합성
 *   - NewsAnalyzedPublisher.publish() → market.tick.v1 흐름과 같은 경로로 STOMP fanout
 */
@Component
@ConditionalOnProperty(prefix = "magicjar.market.mock-news", name = ["enabled"], havingValue = "true")
class MockNewsGenerator(
    private val publisher: NewsAnalyzedPublisher,
    private val props: MockNewsProperties,
) {
    private val log = LoggerFactory.getLogger(MockNewsGenerator::class.java)

    private data class Template(
        val sentiment: String,
        val title: String,
        val summary: String,
    )

    private val templates =
        listOf(
            Template("POSITIVE", "%s 3분기 영업이익 컨센서스 상회", "메모리 가격 회복과 모바일 수요 증가로 시장 예상치를 웃돈 실적 발표."),
            Template("POSITIVE", "%s 신규 대형 수주 공시", "북미 고객사와 다년간 공급 계약 체결, 안정적 매출 기대."),
            Template("POSITIVE", "%s 외국인·기관 동반 매수세", "최근 5거래일 누적 순매수 5천억원 돌파, 수급 호전."),
            Template("NEUTRAL", "%s 실적 발표 임박", "이번 주 분기 실적 발표 예정. 시장 컨센서스 부합 전망."),
            Template("NEUTRAL", "%s 공시 — 자기주식 처분 결정", "주가 영향 제한적. 임직원 보상 목적의 정기 처분."),
            Template("NEGATIVE", "%s 4분기 가이던스 하향", "글로벌 수요 둔화 우려로 시장 추정치 하회 가능성 시사."),
            Template("NEGATIVE", "%s 환율 변동 손실 인식", "달러·원 급등으로 외화 부채 평가손실 반영, 일회성 영향."),
            Template("NEGATIVE", "%s 경쟁사 신제품 출시 영향 우려", "주요 경쟁사 차세대 라인업 발표로 시장 점유율 압박 전망."),
        )

    private val instrumentNames =
        mapOf(
            "005930" to "삼성전자",
            "000660" to "SK하이닉스",
            "035720" to "카카오",
            "005380" to "현대차",
            "051910" to "LG화학",
        )

    @Scheduled(fixedDelayString = "\${magicjar.market.mock-news.interval-ms:30000}")
    fun emit() {
        if (props.symbols.isEmpty()) return
        val code = props.symbols.random()
        val name = instrumentNames[code] ?: code
        val tpl = templates.random()
        val now = Instant.now()
        val confidence = 0.6 + Random.nextDouble() * 0.35 // 0.60 ~ 0.95

        val event =
            NewsAnalyzedEvent(
                newsId = UUID.randomUUID(),
                source = listOf("NAVER", "OPEN_DART").random(),
                publishedAt = now,
                title = tpl.title.format(name),
                sentiment = tpl.sentiment,
                sentimentScore = confidence,
                summary = tpl.summary,
                instruments = listOf(code),
                analyzedAt = now,
            )
        publisher.publish(event)
        log.debug("MockNewsGenerator → {} ({}, {}%)", code, tpl.sentiment, (confidence * 100).toInt())
    }
}
