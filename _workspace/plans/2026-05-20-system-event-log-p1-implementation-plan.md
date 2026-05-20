# System Event Log P1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** magicJar 운영 이벤트 (Reconcile fallback·WebSocket disconnect/reconnect) 자동 영속 framework P1 구현 — Flyway V44 + domain port + persistence-shared adapter + Kafka producer + 5 emit point.

**Architecture:** Outbox 패턴 통합 — emit source → `SystemEventLogger` (domain port) → `SystemEventLoggerAdapter` (persistence-shared, DB INSERT + Kafka publish) → Kafka `system.event.v1`. P2 STOMP fanout·UI 별도 plan. PR #621 (account.balance.changed) 패턴 1:1 정합.

**Tech Stack:** Kotlin 2.0 K2 + Spring Boot 3.3 + JPA + Flyway + Kafka + JUnit 5 + mockk.

**Spec**: [`_workspace/specs/2026-05-20-system-event-log-design.md`](../specs/2026-05-20-system-event-log-design.md)

---

## File Structure (P1)

### 신규 (9 file)

| 파일 | 책임 |
|------|------|
| `api/src/main/resources/db/migration/V44__system_event.sql` | Flyway migration — system_event 테이블 + index 4종 |
| `domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/event/SystemEvent.kt` | data class + SystemEventCategory enum + SystemEventSeverity enum |
| `domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/event/port/SystemEventLogger.kt` | interface (도메인 port) |
| `persistence-shared/src/main/kotlin/kr/spdaewoon/magicjar/persistence/shared/event/SystemEventJpaEntity.kt` | JPA entity (table mapping) |
| `persistence-shared/src/main/kotlin/kr/spdaewoon/magicjar/persistence/shared/event/SystemEventJpaRepository.kt` | Spring Data JPA repository |
| `persistence-shared/src/main/kotlin/kr/spdaewoon/magicjar/persistence/shared/event/SystemEventLoggerAdapter.kt` | port adapter (DB INSERT + Kafka publish) |
| `persistence-shared/src/main/kotlin/kr/spdaewoon/magicjar/persistence/shared/event/SystemEventKafkaProducer.kt` | Kafka producer (`system.event.v1` topic) |
| `persistence-shared/src/test/kotlin/.../event/SystemEventLoggerAdapterTest.kt` | 단위 test (DB·Kafka 정합 + graceful 실패) |
| `persistence-shared/src/test/kotlin/.../event/SystemEventKafkaProducerTest.kt` | 단위 test (publish 정합) |

### 수정 (4 file — 5 emit point)

| 파일 | emit point |
|------|-----------|
| `consumer/.../order/OrderReconciliationWorker.kt` | RECONCILE_FALLBACK_FIRED + RECONCILE_FALLBACK_RESOLVED (2건) |
| `consumer/.../reconcile/BalanceReconcileService.kt` | RECONCILE_DRIFT_DETECTED (1건) |
| `consumer/.../ws/KisWebSocketBridge.kt` | WS_DISCONNECTED + WS_RECONNECTED (2건, correlationId 동일) |
| `consumer/src/test/.../` (각 worker test 정합 — 신규 mock 추가) | (3 test 수정) |

---

## Task 1: Flyway V44 migration — system_event 테이블

**Files:**
- Create: `api/src/main/resources/db/migration/V44__system_event.sql`

- [ ] **Step 1: Flyway V44 SQL 작성**

```sql
-- V44__system_event.sql
-- 시스템 운영 이벤트 로그 P1 (ADR 발행 의무 — _workspace/specs/2026-05-20-system-event-log-design.md)
-- WebSocket·Reconcile·Kafka·KIS·DB·Redis 등 외부 의존 실패 catalog + correlation_id 그룹 + viewed/resolved UI 마킹

CREATE TABLE IF NOT EXISTS system_event (
    id              BIGSERIAL PRIMARY KEY,
    category        VARCHAR(20) NOT NULL,
    severity        VARCHAR(10) NOT NULL,
    actor           VARCHAR(60) NOT NULL,
    action          VARCHAR(80) NOT NULL,
    user_id         VARCHAR(40),
    correlation_id  VARCHAR(64),
    detail_json     JSONB NOT NULL,
    occurred_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMP WITH TIME ZONE,
    viewed_at       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_system_event_severity CHECK (severity IN ('INFO','WARN','ERROR','CRITICAL'))
);

CREATE INDEX IF NOT EXISTS idx_system_event_occurred ON system_event (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_system_event_user_unread ON system_event (user_id, viewed_at) WHERE viewed_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_system_event_category ON system_event (category, severity, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_system_event_correlation ON system_event (correlation_id) WHERE correlation_id IS NOT NULL;
```

- [ ] **Step 2: Flyway 검증 (api 빌드 — V44 채번 충돌 0 확인)**

Run: `./gradlew :api:compileKotlin`
Expected: BUILD SUCCESSFUL (Flyway 부팅 시 V44 적용 — runtime 부팅 직접 검증은 통합 test 단계에서)

- [ ] **Step 3: commit**

```bash
git add api/src/main/resources/db/migration/V44__system_event.sql
git -c user.email="bot+backend-core@magicjar.local" -c user.name="magicjar-backend-core" commit -m "feat(backend-core): Flyway V44 system_event 테이블 (P1)"
```

---

## Task 2: domain SystemEvent data class + enum

**Files:**
- Create: `domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/event/SystemEvent.kt`

- [ ] **Step 1: SystemEvent data class + enum 작성**

```kotlin
package kr.spdaewoon.magicjar.domain.event

import java.time.Instant

/**
 * 시스템 운영 이벤트 — Reconcile·WebSocket·Kafka·KIS·DB·Redis 등 외부 의존 실패 catalog.
 *
 * 영속: SystemEventLoggerAdapter → DB system_event + Kafka system.event.v1.
 * UI 표시: P2 STOMP fanout `/topic/system.event.user.{userId}` 또는 `/topic/system.event.broadcast`.
 *
 * spec: `_workspace/specs/2026-05-20-system-event-log-design.md`
 */
data class SystemEvent(
    val category: SystemEventCategory,
    val severity: SystemEventSeverity,
    /** 발사 component (예: "OrderReconciliationWorker") */
    val actor: String,
    /** 이벤트 코드 (예: "RECONCILE_FALLBACK_FIRED") */
    val action: String,
    /** null = system-wide broadcast */
    val userId: String? = null,
    /** 동일 사건 묶기 — 자동 생성 또는 emit site 명시 전달 (WS disconnect→reconnect 같은 case) */
    val correlationId: String? = null,
    /** 자유 형식 JSON (재발생 사유·retry count 등) */
    val detailJson: String,
    val occurredAt: Instant = Instant.now(),
)

enum class SystemEventCategory {
    /** P1 — Reconcile worker 3종 */
    RECONCILE,
    /** P1 — KisWebSocketBridge disconnect/reconnect/fail */
    WEBSOCKET,
    /** P3 — Kafka publish retry·DLT */
    KAFKA,
    /** P3 — EGW00201·EGW00133 */
    KIS_RATELIMIT,
    /** P3 — DB connection·query fail */
    DB,
    /** P3 — Redis fail */
    REDIS,
    /** 확장용 */
    OTHER,
}

enum class SystemEventSeverity { INFO, WARN, ERROR, CRITICAL }
```

- [ ] **Step 2: compile 검증**

Run: `./gradlew :domain:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: commit**

```bash
git add domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/event/SystemEvent.kt
git -c user.email="bot+backend-core@magicjar.local" -c user.name="magicjar-backend-core" commit -m "feat(domain): SystemEvent data class + Category·Severity enum (P1)"
```

---

## Task 3: domain SystemEventLogger interface

**Files:**
- Create: `domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/event/port/SystemEventLogger.kt`

- [ ] **Step 1: SystemEventLogger interface 작성**

```kotlin
package kr.spdaewoon.magicjar.domain.event.port

import kr.spdaewoon.magicjar.domain.event.SystemEvent

/**
 * 시스템 운영 이벤트 영속 port (domain).
 *
 * 구현: persistence-shared SystemEventLoggerAdapter (DB INSERT + Kafka publish outbox).
 *
 * graceful 정책 (spec §9):
 * - DB INSERT 실패 → log.error + Kafka 시도 (graceful)
 * - Kafka publish 실패 → log.warn (DB 영속만 유지)
 * - caller side cascade 차단 — `runCatching { eventLogger.emit(event) }.onFailure { log.error(...) }` 패턴 의무
 */
interface SystemEventLogger {
    /**
     * 운영 이벤트 emit — DB 영속 + Kafka publish.
     *
     * 실패는 listener thread 안 죽임 (caller가 runCatching wrap 의무).
     */
    fun emit(event: SystemEvent)
}
```

- [ ] **Step 2: compile 검증**

Run: `./gradlew :domain:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: commit**

```bash
git add domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/event/port/SystemEventLogger.kt
git -c user.email="bot+backend-core@magicjar.local" -c user.name="magicjar-backend-core" commit -m "feat(domain): SystemEventLogger port interface (P1)"
```

---

## Task 4: persistence-shared SystemEventJpaEntity + Repository

**Files:**
- Create: `persistence-shared/src/main/kotlin/kr/spdaewoon/magicjar/persistence/shared/event/SystemEventJpaEntity.kt`
- Create: `persistence-shared/src/main/kotlin/kr/spdaewoon/magicjar/persistence/shared/event/SystemEventJpaRepository.kt`

- [ ] **Step 1: SystemEventJpaEntity 작성**

```kotlin
package kr.spdaewoon.magicjar.persistence.shared.event

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import org.hibernate.annotations.Type
import java.time.Instant

@Entity
@Table(name = "system_event")
class SystemEventJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "category", nullable = false, updatable = false, length = 20)
    val category: String,
    @Column(name = "severity", nullable = false, updatable = false, length = 10)
    val severity: String,
    @Column(name = "actor", nullable = false, updatable = false, length = 60)
    val actor: String,
    @Column(name = "action", nullable = false, updatable = false, length = 80)
    val action: String,
    @Column(name = "user_id", updatable = false, length = 40)
    val userId: String? = null,
    @Column(name = "correlation_id", updatable = false, length = 64)
    val correlationId: String? = null,
    @Type(JsonBinaryType::class)
    @Column(name = "detail_json", nullable = false, columnDefinition = "jsonb")
    val detailJson: String,
    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: Instant,
    @Column(name = "resolved_at")
    var resolvedAt: Instant? = null,
    @Column(name = "viewed_at")
    var viewedAt: Instant? = null,
) {
    protected constructor() : this(
        category = "",
        severity = "",
        actor = "",
        action = "",
        detailJson = "{}",
        occurredAt = Instant.EPOCH,
    )

    fun toDomain(): SystemEvent =
        SystemEvent(
            category = runCatching { SystemEventCategory.valueOf(category) }.getOrDefault(SystemEventCategory.OTHER),
            severity = runCatching { SystemEventSeverity.valueOf(severity) }.getOrDefault(SystemEventSeverity.INFO),
            actor = actor,
            action = action,
            userId = userId,
            correlationId = correlationId,
            detailJson = detailJson,
            occurredAt = occurredAt,
        )

    companion object {
        fun fromDomain(event: SystemEvent): SystemEventJpaEntity =
            SystemEventJpaEntity(
                category = event.category.name,
                severity = event.severity.name,
                actor = event.actor,
                action = event.action,
                userId = event.userId,
                correlationId = event.correlationId,
                detailJson = event.detailJson,
                occurredAt = event.occurredAt,
            )
    }
}
```

- [ ] **Step 2: SystemEventJpaRepository 작성**

```kotlin
package kr.spdaewoon.magicjar.persistence.shared.event

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface SystemEventJpaRepository : JpaRepository<SystemEventJpaEntity, Long> {
    /** 사용자별 미확인 이벤트 (UN read count) — index idx_system_event_user_unread */
    fun findByUserIdAndViewedAtIsNullOrderByOccurredAtDesc(
        userId: String,
        pageable: Pageable,
    ): List<SystemEventJpaEntity>

    /** 운영 dashboard 페이지네이션 (전체) */
    fun findAllByOrderByOccurredAtDesc(pageable: Pageable): List<SystemEventJpaEntity>

    /** correlation_id 그룹 조회 (UI 그룹 표시 — P2) */
    fun findByCorrelationIdOrderByOccurredAtAsc(correlationId: String): List<SystemEventJpaEntity>
}
```

- [ ] **Step 3: compile 검증**

Run: `./gradlew :persistence-shared:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: commit**

```bash
git add persistence-shared/src/main/kotlin/kr/spdaewoon/magicjar/persistence/shared/event/
git -c user.email="bot+backend-core@magicjar.local" -c user.name="magicjar-backend-core" commit -m "feat(persistence-shared): SystemEventJpaEntity + Repository (P1)"
```

---

## Task 5: persistence-shared SystemEventKafkaProducer

**Files:**
- Create: `persistence-shared/src/main/kotlin/kr/spdaewoon/magicjar/persistence/shared/event/SystemEventKafkaProducer.kt`
- Test: `persistence-shared/src/test/kotlin/kr/spdaewoon/magicjar/persistence/shared/event/SystemEventKafkaProducerTest.kt`

- [ ] **Step 1: 실패 test 작성**

```kotlin
package kr.spdaewoon.magicjar.persistence.shared.event

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.time.Instant
import java.util.concurrent.CompletableFuture

class SystemEventKafkaProducerTest {
    private val kafkaTemplate = mockk<KafkaTemplate<String, String>>(relaxed = true)
    private val objectMapper = ObjectMapper()
    private val sut = SystemEventKafkaProducer(kafkaTemplate, objectMapper)

    @Test
    fun `userId 있으면 partitionKey = userId 로 publish`() {
        val event = SystemEvent(
            category = SystemEventCategory.RECONCILE,
            severity = SystemEventSeverity.INFO,
            actor = "OrderReconciliationWorker",
            action = "RECONCILE_FALLBACK_FIRED",
            userId = "74683223",
            detailJson = """{"count":1}""",
            occurredAt = Instant.parse("2026-05-20T15:00:00Z"),
        )
        every {
            kafkaTemplate.send("system.event.v1", "74683223", any<String>())
        } returns CompletableFuture.completedFuture(mockk<SendResult<String, String>>(relaxed = true))

        sut.publish(event)

        verify { kafkaTemplate.send("system.event.v1", "74683223", any<String>()) }
    }

    @Test
    fun `userId null 이면 partitionKey = broadcast 로 publish`() {
        val event = SystemEvent(
            category = SystemEventCategory.WEBSOCKET,
            severity = SystemEventSeverity.WARN,
            actor = "KisWebSocketBridge",
            action = "WS_DISCONNECTED",
            detailJson = """{"reason":"timeout"}""",
        )
        every {
            kafkaTemplate.send("system.event.v1", "broadcast", any<String>())
        } returns CompletableFuture.completedFuture(mockk<SendResult<String, String>>(relaxed = true))

        sut.publish(event)

        verify { kafkaTemplate.send("system.event.v1", "broadcast", any<String>()) }
    }
}
```

- [ ] **Step 2: test 실패 검증**

Run: `./gradlew :persistence-shared:test --tests "*SystemEventKafkaProducer*"`
Expected: FAIL (SystemEventKafkaProducer 미존재)

- [ ] **Step 3: SystemEventKafkaProducer 구현**

```kotlin
package kr.spdaewoon.magicjar.persistence.shared.event

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * SystemEvent → Kafka system.event.v1 publish.
 *
 * partition key: userId 있으면 userId, 없으면 "broadcast".
 * Kafka send timeout 5s — graceful (실패는 caller adapter 가 wrap).
 */
@Component
class SystemEventKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(SystemEventKafkaProducer::class.java)

    companion object {
        const val TOPIC = "system.event.v1"
        const val SEND_TIMEOUT_SECONDS = 5L
        const val BROADCAST_KEY = "broadcast"
    }

    fun publish(event: SystemEvent) {
        val partitionKey = event.userId ?: BROADCAST_KEY
        val payload = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(TOPIC, partitionKey, payload).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        log.debug("SystemEvent publish OK: category={} severity={} partitionKey={}", event.category, event.severity, partitionKey)
    }
}
```

- [ ] **Step 4: test 통과 검증**

Run: `./gradlew :persistence-shared:test --tests "*SystemEventKafkaProducer*"`
Expected: 2/2 PASS

- [ ] **Step 5: commit**

```bash
git add persistence-shared/src/main/kotlin/kr/spdaewoon/magicjar/persistence/shared/event/SystemEventKafkaProducer.kt persistence-shared/src/test/kotlin/kr/spdaewoon/magicjar/persistence/shared/event/SystemEventKafkaProducerTest.kt
git -c user.email="bot+backend-core@magicjar.local" -c user.name="magicjar-backend-core" commit -m "feat(persistence-shared): SystemEventKafkaProducer + 2 test (P1)"
```

---

## Task 6: persistence-shared SystemEventLoggerAdapter (port impl)

**Files:**
- Create: `persistence-shared/src/main/kotlin/kr/spdaewoon/magicjar/persistence/shared/event/SystemEventLoggerAdapter.kt`
- Test: `persistence-shared/src/test/kotlin/kr/spdaewoon/magicjar/persistence/shared/event/SystemEventLoggerAdapterTest.kt`

- [ ] **Step 1: 실패 test 작성 (4 case)**

```kotlin
package kr.spdaewoon.magicjar.persistence.shared.event

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import org.junit.jupiter.api.Test
import java.time.Instant

class SystemEventLoggerAdapterTest {
    private val jpa = mockk<SystemEventJpaRepository>(relaxed = true)
    private val kafkaProducer = mockk<SystemEventKafkaProducer>(relaxed = true)
    private val sut = SystemEventLoggerAdapter(jpa, kafkaProducer)

    private fun fixture() = SystemEvent(
        category = SystemEventCategory.RECONCILE,
        severity = SystemEventSeverity.INFO,
        actor = "OrderReconciliationWorker",
        action = "RECONCILE_FALLBACK_FIRED",
        userId = "74683223",
        detailJson = """{"count":1}""",
        occurredAt = Instant.parse("2026-05-20T15:00:00Z"),
    )

    @Test
    fun `정상 emit — DB INSERT + Kafka publish 둘 다 호출`() {
        val event = fixture()
        every { jpa.save(any()) } answers { firstArg<SystemEventJpaEntity>().also { } }

        sut.emit(event)

        verify { jpa.save(any<SystemEventJpaEntity>()) }
        verify { kafkaProducer.publish(any<SystemEvent>()) }
    }

    @Test
    fun `DB INSERT 실패 — log error + Kafka publish 시도 안 함 (DB 영속이 source-of-truth)`() {
        val event = fixture()
        every { jpa.save(any()) } throws RuntimeException("DB connection fail")

        sut.emit(event)

        verify { jpa.save(any<SystemEventJpaEntity>()) }
        verify(exactly = 0) { kafkaProducer.publish(any()) }
    }

    @Test
    fun `Kafka publish 실패 — DB INSERT 는 영속 + log warn (graceful)`() {
        val event = fixture()
        every { jpa.save(any()) } answers { firstArg() }
        every { kafkaProducer.publish(any()) } throws RuntimeException("Kafka broker down")

        sut.emit(event)

        verify { jpa.save(any<SystemEventJpaEntity>()) }
        verify { kafkaProducer.publish(any<SystemEvent>()) }
        // no throw — graceful
    }

    @Test
    fun `emit 자체 무한 루프 차단 — adapter 안에서 발생한 예외는 emit 0 (log error only)`() {
        val event = fixture()
        every { jpa.save(any()) } throws IllegalStateException("simulated")
        every { kafkaProducer.publish(any()) } throws IllegalStateException("simulated")

        sut.emit(event)

        // no throw — listener thread 살아 있다
    }
}
```

- [ ] **Step 2: test 실패 검증**

Run: `./gradlew :persistence-shared:test --tests "*SystemEventLoggerAdapter*"`
Expected: FAIL (SystemEventLoggerAdapter 미존재)

- [ ] **Step 3: SystemEventLoggerAdapter 구현**

```kotlin
package kr.spdaewoon.magicjar.persistence.shared.event

import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * SystemEventLogger port impl — DB INSERT (source-of-truth) + Kafka publish (STOMP fanout 트리거).
 *
 * graceful 정책:
 * - DB INSERT 실패 → log.error + Kafka 시도 0 (DB 영속이 source-of-truth)
 * - Kafka publish 실패 → log.warn (DB 영속만 유지 — UI 알람은 후속 정합 가능)
 * - emit 자체에서 발생한 예외는 listener thread 안 죽임 (caller가 runCatching wrap 의무)
 */
@Component
class SystemEventLoggerAdapter(
    private val jpa: SystemEventJpaRepository,
    private val kafkaProducer: SystemEventKafkaProducer,
) : SystemEventLogger {
    private val log = LoggerFactory.getLogger(SystemEventLoggerAdapter::class.java)

    @Transactional
    override fun emit(event: SystemEvent) {
        val saved = runCatching { jpa.save(SystemEventJpaEntity.fromDomain(event)) }
            .onFailure { e ->
                log.error(
                    "SystemEvent DB INSERT 실패 — Kafka publish skip: category={} severity={} action={} err={}",
                    event.category,
                    event.severity,
                    event.action,
                    e.message,
                )
                return
            }.getOrNull() ?: return

        runCatching { kafkaProducer.publish(saved.toDomain()) }
            .onFailure { e ->
                log.warn(
                    "SystemEvent Kafka publish 실패 — DB 영속만 유지: id={} category={} err={}",
                    saved.id,
                    event.category,
                    e.message,
                )
            }
    }
}
```

- [ ] **Step 4: test 통과 검증**

Run: `./gradlew :persistence-shared:test --tests "*SystemEventLoggerAdapter*"`
Expected: 4/4 PASS

- [ ] **Step 5: commit**

```bash
git add persistence-shared/src/main/kotlin/kr/spdaewoon/magicjar/persistence/shared/event/SystemEventLoggerAdapter.kt persistence-shared/src/test/kotlin/kr/spdaewoon/magicjar/persistence/shared/event/SystemEventLoggerAdapterTest.kt
git -c user.email="bot+backend-core@magicjar.local" -c user.name="magicjar-backend-core" commit -m "feat(persistence-shared): SystemEventLoggerAdapter (port impl) + 4 test (P1)"
```

---

## Task 7: OrderReconciliationWorker emit (RECONCILE_FALLBACK_FIRED + RECONCILE_FALLBACK_RESOLVED)

**Files:**
- Modify: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/order/OrderReconciliationWorker.kt`
- Modify: `consumer/src/test/kotlin/kr/spdaewoon/magicjar/consumer/order/OrderReconciliationWorkerTest.kt` (가정 — 기존 test 있음)

- [ ] **Step 1: 신규 test case 작성 (2건)**

```kotlin
// OrderReconciliationWorkerTest.kt 에 추가

@Test
fun `reconcile fired — PENDING_RECONCILE 1건 이상 발견 시 SystemEventLogger emit RECONCILE_FALLBACK_FIRED`() {
    val pending = orderRequest(id = 1L, status = OrderStatus.PENDING_RECONCILE, kisOrderNo = "0000033601")
    every { orderRequestRepo.findByStatusAndRequestedAtBefore(any(), any(), any()) } returns listOf(pending)
    every { kisOrderInquireCcld.lookup(any()) } returns OrderInquireResult.Found(filledQty = 0, orderQty = 1)

    sut.reconcile()

    val eventSlot = slot<SystemEvent>()
    verify { systemEventLogger.emit(capture(eventSlot)) }
    assertThat(eventSlot.captured.category).isEqualTo(SystemEventCategory.RECONCILE)
    assertThat(eventSlot.captured.action).isEqualTo("RECONCILE_FALLBACK_FIRED")
    assertThat(eventSlot.captured.severity).isEqualTo(SystemEventSeverity.INFO)
}

@Test
fun `reconcile resolved — KIS 체결 발견 시 RECONCILE_FALLBACK_RESOLVED emit (severity WARN)`() {
    val pending = orderRequest(id = 1L, status = OrderStatus.PENDING_RECONCILE, kisOrderNo = "0000033601", userId = "74683223")
    every { orderRequestRepo.findByStatusAndRequestedAtBefore(any(), any(), any()) } returns listOf(pending)
    every { kisOrderInquireCcld.lookup("0000033601") } returns OrderInquireResult.Found(filledQty = 1, orderQty = 1)

    sut.reconcile()

    val events = mutableListOf<SystemEvent>()
    verify { systemEventLogger.emit(capture(events)) }
    val resolvedEvent = events.first { it.action == "RECONCILE_FALLBACK_RESOLVED" }
    assertThat(resolvedEvent.severity).isEqualTo(SystemEventSeverity.WARN)
    assertThat(resolvedEvent.userId).isEqualTo("74683223")
}
```

- [ ] **Step 2: test 실패 검증**

Run: `./gradlew :consumer:test --tests "*OrderReconciliationWorker*"`
Expected: FAIL (systemEventLogger 의존 미주입)

- [ ] **Step 3: OrderReconciliationWorker 생성자 의존 추가 + emit 호출 2건**

```kotlin
// OrderReconciliationWorker 생성자 의존 추가
@Component
class OrderReconciliationWorker(
    // 기존 의존들...
    private val systemEventLogger: SystemEventLogger,
) {
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    fun reconcile() {
        val pending = orderRequestRepo.findByStatusAndRequestedAtBefore(...)
        if (pending.isEmpty()) {
            log.trace("reconcile: PENDING_RECONCILE 0건")
            return
        }
        
        // ★ emit point #1 — fallback fired
        runCatching {
            systemEventLogger.emit(SystemEvent(
                category = SystemEventCategory.RECONCILE,
                severity = SystemEventSeverity.INFO,
                actor = "OrderReconciliationWorker",
                action = "RECONCILE_FALLBACK_FIRED",
                detailJson = """{"pendingCount":${pending.size}}""",
            ))
        }.onFailure { log.error("SystemEventLogger.emit 실패: ${it.message}") }
        
        pending.forEach { order ->
            // ... 기존 reconcile 흐름 ...
            
            when (val result = kisOrderInquireCcld.lookup(order.kisOrderNo)) {
                is OrderInquireResult.Found -> {
                    if (result.filledQty > 0) {
                        // ★ emit point #2 — fallback resolved (체결 발견)
                        runCatching {
                            systemEventLogger.emit(SystemEvent(
                                category = SystemEventCategory.RECONCILE,
                                severity = SystemEventSeverity.WARN,
                                actor = "OrderReconciliationWorker",
                                action = "RECONCILE_FALLBACK_RESOLVED",
                                userId = order.userId,
                                detailJson = """{"orderId":${order.id},"filledQty":${result.filledQty},"orderQty":${result.orderQty}}""",
                            ))
                        }.onFailure { log.error("SystemEventLogger.emit 실패: ${it.message}") }
                    }
                    // ... markCompleted 기존 흐름 ...
                }
                // ... 기타 result 분기 ...
            }
        }
    }
}
```

- [ ] **Step 4: 기존 OrderReconciliationWorkerTest 생성자 mock 추가**

```kotlin
private val systemEventLogger = mockk<SystemEventLogger>(relaxed = true)
private val sut = OrderReconciliationWorker(/* 기존 mock들 */, systemEventLogger)
```

- [ ] **Step 5: test 통과 검증**

Run: `./gradlew :consumer:test --tests "*OrderReconciliationWorker*"`
Expected: 기존 case + 신규 2건 모두 PASS

- [ ] **Step 6: commit**

```bash
git add consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/order/OrderReconciliationWorker.kt consumer/src/test/kotlin/kr/spdaewoon/magicjar/consumer/order/OrderReconciliationWorkerTest.kt
git -c user.email="bot+backend-core@magicjar.local" -c user.name="magicjar-backend-core" commit -m "feat(backend-core): OrderReconciliationWorker emit RECONCILE_FALLBACK_FIRED + RESOLVED (P1 emit point 1·2)"
```

---

## Task 8: BalanceReconcileService emit (RECONCILE_DRIFT_DETECTED)

**Files:**
- Modify: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/reconcile/BalanceReconcileService.kt`
- Modify: 관련 test (있으면)

- [ ] **Step 1: 신규 test case 작성**

```kotlin
@Test
fun `drift detected — KIS 잔고와 DB position 차이 발견 시 RECONCILE_DRIFT_DETECTED emit (severity WARN)`() {
    // mock 셋업 — KIS 잔고에 005930 1주 / DB position 005930 2주 (drift)
    every { kisReconcileBalanceAdapter.fetch(any(), any()) } returns balance(holding("005930", 1))
    every { internalPositionQuery.findByUser("74683223") } returns listOf(position("005930", 2))

    sut.reconcile("74683223")

    val eventSlot = slot<SystemEvent>()
    verify { systemEventLogger.emit(capture(eventSlot)) }
    assertThat(eventSlot.captured.action).isEqualTo("RECONCILE_DRIFT_DETECTED")
    assertThat(eventSlot.captured.severity).isEqualTo(SystemEventSeverity.WARN)
    assertThat(eventSlot.captured.userId).isEqualTo("74683223")
}
```

- [ ] **Step 2: test 실패 검증**

Run: `./gradlew :consumer:test --tests "*BalanceReconcileService*"`
Expected: FAIL (systemEventLogger 미주입)

- [ ] **Step 3: BalanceReconcileService 의존 + drift detection 후 emit**

```kotlin
// BalanceReconcileService.reconcile 본문에 drift 비교 후
val drift = kisHoldings != dbPositions  // 단순화 표현; 실제는 holding-by-code 비교
if (drift) {
    runCatching {
        systemEventLogger.emit(SystemEvent(
            category = SystemEventCategory.RECONCILE,
            severity = SystemEventSeverity.WARN,
            actor = "BalanceReconcileService",
            action = "RECONCILE_DRIFT_DETECTED",
            userId = userId,
            detailJson = """{"kisHoldings":${kisHoldings.size},"dbPositions":${dbPositions.size}}""",
        ))
    }.onFailure { log.error("SystemEventLogger.emit 실패: ${it.message}") }
}
```

- [ ] **Step 4: test 통과**

Run: `./gradlew :consumer:test --tests "*BalanceReconcileService*"`
Expected: PASS

- [ ] **Step 5: commit**

```bash
git add consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/reconcile/BalanceReconcileService.kt
git -c user.email="bot+backend-core@magicjar.local" -c user.name="magicjar-backend-core" commit -m "feat(backend-core): BalanceReconcileService emit RECONCILE_DRIFT_DETECTED (P1 emit point 3)"
```

---

## Task 9: KisWebSocketBridge emit (WS_DISCONNECTED + WS_RECONNECTED correlationId)

**Files:**
- Modify: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/ws/KisWebSocketBridge.kt`

- [ ] **Step 1: KisWebSocketBridge disconnect/reconnect handler에 emit 호출 + correlationId 자동 생성**

```kotlin
@Component
class KisWebSocketBridge(
    // 기존 의존들...
    private val systemEventLogger: SystemEventLogger,
) : KisWsPort {
    // disconnect 시 발급되는 correlationId — reconnect 시 재사용
    private val currentDisconnectCorrelationId = AtomicReference<String?>(null)

    private fun onDisconnect(reason: String) {
        val correlationId = UUID.randomUUID().toString()
        currentDisconnectCorrelationId.set(correlationId)

        runCatching {
            systemEventLogger.emit(SystemEvent(
                category = SystemEventCategory.WEBSOCKET,
                severity = SystemEventSeverity.WARN,
                actor = "KisWebSocketBridge",
                action = "WS_DISCONNECTED",
                correlationId = correlationId,
                detailJson = """{"reason":"$reason"}""",
            ))
        }.onFailure { log.error("SystemEventLogger.emit 실패: ${it.message}") }

        // 기존 disconnect 흐름...
    }

    private fun onReconnect() {
        val correlationId = currentDisconnectCorrelationId.getAndSet(null)
            ?: return  // disconnect 흔적 없으면 emit 0 (첫 connect 시점)

        runCatching {
            systemEventLogger.emit(SystemEvent(
                category = SystemEventCategory.WEBSOCKET,
                severity = SystemEventSeverity.INFO,
                actor = "KisWebSocketBridge",
                action = "WS_RECONNECTED",
                correlationId = correlationId,
                detailJson = """{"timestamp":"${Instant.now()}"}""",
            ))
        }.onFailure { log.error("SystemEventLogger.emit 실패: ${it.message}") }

        // 기존 reconnect 흐름...
    }
}
```

- [ ] **Step 2: test 작성 (disconnect → reconnect 동일 correlationId 검증)**

```kotlin
@Test
fun `WS disconnect + reconnect — 동일 correlationId 로 2건 emit (WS_DISCONNECTED + WS_RECONNECTED)`() {
    val eventCap = mutableListOf<SystemEvent>()
    every { systemEventLogger.emit(capture(eventCap)) } returns Unit

    sut.simulateDisconnect("KIS broker timeout")
    sut.simulateReconnect()

    assertThat(eventCap).hasSize(2)
    assertThat(eventCap[0].action).isEqualTo("WS_DISCONNECTED")
    assertThat(eventCap[1].action).isEqualTo("WS_RECONNECTED")
    assertThat(eventCap[0].correlationId).isNotNull
    assertThat(eventCap[0].correlationId).isEqualTo(eventCap[1].correlationId)
}
```

- [ ] **Step 3: test 통과 검증**

Run: `./gradlew :consumer:test --tests "*KisWebSocketBridge*"`
Expected: PASS

- [ ] **Step 4: commit**

```bash
git add consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/ws/KisWebSocketBridge.kt
git -c user.email="bot+backend-core@magicjar.local" -c user.name="magicjar-backend-core" commit -m "feat(backend-core): KisWebSocketBridge emit WS_DISCONNECTED + WS_RECONNECTED correlationId (P1 emit point 4·5)"
```

---

## Task 10: 전체 통합 검증 + PR 발행

- [ ] **Step 1: 전체 모듈 빌드 + test**

Run: `./gradlew :domain:check :persistence-shared:check :consumer:check :api:check`
Expected: BUILD SUCCESSFUL (전 모듈)

- [ ] **Step 2: ktlint 전체**

Run: `./gradlew ktlintCheck`
Expected: GREEN

- [ ] **Step 3: docker 통합 부팅 검증 (선택)**

Run: `scripts/build-and-deploy.sh consumer-app api-app`
Expected:
- Flyway V44 적용 성공 log
- consumer 부팅 시 SystemEventLogger bean wiring 성공
- ApplicationContext 부팅 완료

- [ ] **Step 4: push + PR 발행**

```bash
git push -u origin agent/backend-core/system-event-log-p1
gh pr create --base main --title "feat(backend-core): system event log P1 — schema + framework + 5 emit point" --body "..."
```

- [ ] **Step 5: 사용자 결재 → 머지 + 정리**

머지 후 `git worktree remove` + `git branch -d`.

---

## Self-Review

| 체크 | 결과 |
|------|------|
| Spec coverage (§3 P1 scope) | ✅ Flyway V44 + domain port + persistence-shared adapter + Kafka producer + 5 emit point 모두 task 매핑 |
| Placeholder scan (TBD·TODO) | ✅ 0건 |
| Type consistency | ✅ SystemEvent·SystemEventCategory·SystemEventSeverity 명명 모든 task 일관 |
| Test 누락 | ✅ Task 5·6·7·8·9 각각 TDD 패턴 |
| 운영 명령 누락 | ✅ Task 10 docker 통합 검증 명시 |

## Phase 분리 명시

- **본 plan은 P1 scope만 다룸** (Flyway V44 + domain + persistence-shared + Kafka producer + 5 emit point)
- **P2 (STOMP fanout + UI 알림센터 + dashboard + CRITICAL toast)는 별도 plan**
- **P3 (catalog 확장 + 의제 B ACK reconcile 통합)는 별도 plan**

각 Phase는 독립 실행 가능 — P1 단독으로도 DB·Kafka 영속 정합 + 운영 로그 audit 가능 (단 UI 표시는 P2 의무).
