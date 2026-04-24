package kr.spdaewoon.magicjar.core.domain

import java.time.Instant

data class HarnessState(
    // "0" | "A" | "B" | "C" | "DONE"
    val currentPhase: String,
    val phaseHistory: List<PhaseEntry>,
    val agents: List<AgentStatus>,
    val issuesOpen: Int,
    val issuesFixed: Int,
    val generatedAt: Instant,
)
