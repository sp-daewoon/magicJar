package kr.spdaewoon.magicjar.core.event

import java.time.Instant

data class PhaseChangedEvent(
    val from: String,
    val to: String,
    val at: Instant,
)
