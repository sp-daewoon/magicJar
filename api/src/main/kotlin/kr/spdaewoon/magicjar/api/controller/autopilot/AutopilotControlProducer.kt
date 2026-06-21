package kr.spdaewoon.magicjar.api.controller.autopilot

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * magicjar-autopilot **제어 cmd producer** (spec 2026-06-22 §2-0-B·§3 · Phase 0 Task 4).
 *
 * api 컨트롤러([AutopilotController])가 [KafkaTopics.Autopilot.CONTROL] 에 `{action, reason, correctedLimits?, requestId}`
 * 를 publish 하고, consumer `AutopilotControlListener` 가 소비한다. api 는 consumer 모듈 의존 0(모듈 경계 LOCK)이므로
 * Kafka 가 유일한 제어 채널이다(`ReconcileManualTriggerService` 패턴 정합).
 *
 * partition key = "autopilot"(account-wide 단일 ordering — 제어는 순서가 중요).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 제어 producer (ADR 0023 §2-4 정합)
 */
@Component
class AutopilotControlProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(AutopilotControlProducer::class.java)

    companion object {
        private const val PARTITION_KEY = "autopilot"
    }

    /**
     * 제어 cmd publish — STOP / RESUME / ROLLBACK.
     *
     * @return 멱등·추적용 requestId(UUID). 호출자(컨트롤러)가 202 응답에 실어 반환.
     * @throws ResponseStatusException 503 — Kafka publish 실패 시.
     */
    fun publish(
        action: String,
        reason: String?,
        correctedLimits: AutopilotCorrectedLimits?,
    ): String {
        val requestId = UUID.randomUUID().toString()
        val payload =
            objectMapper.writeValueAsString(
                AutopilotControlMessage(
                    action = action,
                    reason = reason,
                    correctedLimits = correctedLimits,
                    requestId = requestId,
                ),
            )
        return try {
            kafkaTemplate.send(KafkaTopics.Autopilot.CONTROL, PARTITION_KEY, payload)
            log.warn("autopilot 제어 publish: action={} requestId={} reason={}", action, requestId, reason)
            requestId
        } catch (e: Exception) {
            log.error("autopilot 제어 publish 실패: action={} err={}", action, e.message)
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "autopilot 제어 publish 실패: ${e.message}")
        }
    }
}

/**
 * 제어 wire 메시지 — consumer `AutopilotControlCommand` 와 필드 1:1 (Kafka JSON 계약).
 * api 는 consumer 타입 import 불가(모듈 경계)이므로 api 측 mirror 를 둔다(필드명 동일 = 역직렬화 정합).
 */
data class AutopilotControlMessage(
    val action: String,
    val reason: String?,
    val correctedLimits: AutopilotCorrectedLimits?,
    val requestId: String,
)
