package com.mj.domain.common.event

import java.util.UUID

data class AiReviewRequestedEvent(
    val journalEntryId: UUID,
)
