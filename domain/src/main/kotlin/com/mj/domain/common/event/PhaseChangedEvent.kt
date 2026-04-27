package com.mj.domain.common.event

import java.time.Instant

data class PhaseChangedEvent(
    val from: String,
    val to: String,
    val at: Instant,
)
