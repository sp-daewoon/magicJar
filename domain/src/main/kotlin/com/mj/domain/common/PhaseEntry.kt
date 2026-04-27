package com.mj.domain.common

import java.time.Instant

data class PhaseEntry(
    val phase: String,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    // "initial" | "partial" | "incremental" | "team" | ...
    val mode: String,
    val agents: List<String>,
)
