package kr.spdaewoon.magicjar.ai.service

import kr.spdaewoon.magicjar.core.event.AiReviewRequestedEvent
import kr.spdaewoon.magicjar.core.port.TradeJournalRepository
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 매매 종료 또는 사용자 요청 시 발행되는 `AiReviewRequestedEvent`를 비동기로 처리.
 * 리뷰 결과는 `TradeJournalRepository.save()`를 통해 `aiReview` 필드를 업데이트한다.
 */
@Component
class TradeReviewEventListener(
    private val reviewer: TradeReviewer,
    private val journalRepo: TradeJournalRepository,
) {
    private val log = LoggerFactory.getLogger(TradeReviewEventListener::class.java)

    @Async
    @EventListener
    fun onReviewRequested(event: AiReviewRequestedEvent) {
        val entry = journalRepo.find(event.journalEntryId)
        if (entry == null) {
            log.warn("AiReviewRequested: journal entry {} not found", event.journalEntryId)
            return
        }
        val reviewText = reviewer.review(entry)
        if (reviewText.isEmpty) {
            log.info("AiReviewRequested: review empty for entry {} (AI disabled or failed)", entry.id)
            return
        }
        val updated = entry.copy(aiReview = reviewText.get(), updatedAt = Instant.now())
        journalRepo.save(updated)
        log.info("AiReview completed for entry {}", entry.id)
    }
}
