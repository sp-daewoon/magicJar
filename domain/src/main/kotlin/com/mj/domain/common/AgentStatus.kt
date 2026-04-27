package com.mj.domain.common

import java.time.Instant

data class AgentStatus(
    val name: String,
    val roleSummary: String,
    val skills: List<String>,
    val lastActivity: Instant? = null,
    val lastArtifacts: List<String> = emptyList(),
    // "IDLE" | "RUNNING" | "BLOCKED"
    val status: String,
)
