package com.mj.domain.news.event

import java.time.Instant
import java.util.UUID

/** Kafka 토픽 dart.disclosure.v1 메시지 스키마 — DART 공시 수신 */
data class DartDisclosureEvent(
    val disclosureId: UUID,
    val code: String,
    val companyName: String,
    val disclosureType: String,
    val title: String,
    val filedAt: Instant,
    val url: String?,
)
