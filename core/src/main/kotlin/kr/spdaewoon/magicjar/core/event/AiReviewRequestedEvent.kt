package kr.spdaewoon.magicjar.core.event

import java.util.UUID

data class AiReviewRequestedEvent(
    val journalEntryId: UUID,
)
