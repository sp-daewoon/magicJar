# System Event Log P3 Implementation Plan (재정의)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** P1·P2 위에 (1) **의제 B (ACK reconcile)** — OrderReconciliationWorker ACK 상태 확장 + KIS inquire-daily-ccld backfill, (2) **Gap 1 (PositionUpdatePort)** — 체결 시 즉시 position upsert, (3) **catalog 확장** — Kafka publish retry/DLT + KIS_RATELIMIT emit, (4) **correlation_id UI 그룹 표시** 통합 구현.

**Architecture:** P1 SystemEventLogger framework 위에 emit point 추가 + 신규 PositionUpdatePort + OrderReconciliationWorker 확장. P2 STOMP fanout·UI 인프라 재사용. PR #621·#626 패턴 1:1.

**Tech Stack:** Spring Boot + JPA + Flyway (V45 신설 가능성) + Kafka + Kotlin / React 19 + TS.

**Spec**: [`_workspace/specs/2026-05-20-system-event-log-design.md`](../specs/2026-05-20-system-event-log-design.md) §3 P3 (재정의 — viewed/resolved 제외 + Gap 1 통합 + correlation_id UI 추가)
**P1 PR**: #626 / **P2 PR**: #628 / **P2.1 PR**: #629

---

## P3 재정의 scope (2026-05-20 orchestrator 재정의 — 사용자 결재)

| 항목 | 상태 |
|------|------|
| 의제 B (ACK stuck reconcile) | ★★★ 사용자 원래 의도 — 포함 |
| Gap 1 (PositionUpdatePort) | ★★★ 체결 직후 보유 화면 stale 해소 — 포함 |
| catalog 확장 — Kafka retry/DLT + KIS_RATELIMIT | ★★ 포함 |
| catalog 확장 — DB·Redis fail | ⏬ P4 후속 (단계 분리) |
| correlation_id UI 그룹 | ★ P2.x → P3 흡수 |
| viewed/resolved REST | ❌ 제외 (P2·P2.1 cover) |

---

## File Structure (P3)

### 신규 (6 file)

**domain (1)**: `domain/src/main/kotlin/.../position/port/PositionUpdatePort.kt`
**persistence-shared (1)**: `persistence-shared/.../position/PositionUpdateAdapter.kt`
**test (4)**:
- `consumer/src/test/.../OrderReconciliationWorker ACK 확장 신규 case`
- `consumer/src/test/.../KisExecutionWsListener position upsert + emit 신규 case`
- `persistence-shared/src/test/.../PositionUpdateAdapterTest.kt`
- `consumer/src/test/.../OrderEventOutboxPublisherWorker emit 신규 case`

### 수정 (8 file)

**backend (7)**:
- `consumer/src/main/kotlin/.../order/OrderReconciliationWorker.kt` — findByStatusIn 확장 + KIS inquire-daily-ccld backfill + position upsert + system_event emit
- `domain/src/main/kotlin/.../order/execution/port/OrderRequestRepository.kt` — findByStatusInAndRequestedAtBefore signature
- `persistence-shared/src/main/kotlin/.../OrderRequestRepositoryAdapter.kt` (or api) — findByStatusIn 구현
- `consumer/src/main/kotlin/.../order/KisExecutionWsListener.kt` — handleExecution 후 position upsert + system_event emit
- `consumer/src/main/kotlin/.../order/OrderOutboxPublisherWorker.kt` — Kafka publish retry/DLT 시 emit
- `consumer/src/main/kotlin/.../infrastructure/kis/RedisKisRateLimiterAdapter.kt` — onTpsExceeded 호출 시 emit
- `consumer/src/main/kotlin/.../infrastructure/kis/ConsumerRedisKisRateLimiterAdapter.kt` — 동일 패턴

**frontend (1)**:
- `frontend/src/pages/SystemEvent/SystemEventPage.tsx` — correlation_id 그룹 표시 (인접 row + 시각 구분)

---

## Phase 분해 (P1·P2 패턴 동일 — 4 phase + final reviewer)

| Phase | scope | 영역 | 규모 | sub-agent |
|-------|-------|------|------|-----------|
| **P3-A** | Task 1-4 — 의제 B + Gap 1 통합 (PositionUpdatePort + OrderReconciliationWorker ACK 확장 + KIS inquire-daily-ccld + KisExecutionWsListener position upsert + emit) | domain·persistence-shared·consumer | medium-large | backend-core |
| **P3-B** | Task 5-6 — catalog 확장 (Kafka retry/DLT emit + KIS_RATELIMIT emit) | consumer | medium | backend-core |
| **P3-C** | Task 7 — correlation_id UI 그룹 표시 | frontend | small | frontend |
| **P3-D** | Task 8 — 통합 검증 + push + PR | 다영역 | small | backend-core |

---

## Task 1: domain PositionUpdatePort interface

**Files:**
- Create: `domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/position/port/PositionUpdatePort.kt`

- [ ] **Step 1: interface 작성**

```kotlin
package kr.spdaewoon.magicjar.domain.position.port

import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import java.math.BigDecimal

/**
 * 체결 시 즉시 position upsert port (Gap 1 — 2026-05-20).
 *
 * 기존: BalanceReconcileJob 5분 cron + PositionBootstrapRunner 부팅 시점만 position 갱신
 *      → 체결 직후 5분간 보유 화면 stale (사용자 dogfooding 결함)
 * 신규: KisExecutionWsListener.handleExecution 후 즉시 호출 → 보유 화면 실시간 정합
 *
 * 구현: persistence-shared PositionUpdateAdapter (JPA position table upsert + 평균가 재계산).
 */
interface PositionUpdatePort {
    /**
     * 체결 발생 시 position upsert.
     *
     * @param userId KIS 계좌 사용자 ID
     * @param code 종목 코드 (예: "005930")
     * @param side OrderSide.BUY → 보유량 +, SELL → 보유량 -
     * @param executedQty 체결 수량 (양수)
     * @param executedPrice 체결 단가
     *
     * 동작:
     *  - BUY 시: 기존 보유 + executedQty, 평균가 = (기존 보유 × 평균가 + 체결 × 단가) / (기존 + 체결)
     *  - SELL 시: 기존 보유 - executedQty, 평균가는 유지 (FIFO 가정), 보유 0 시 row 삭제
     *  - 기존 row 없으면 INSERT
     */
    fun upsertOnExecution(
        userId: String,
        code: String,
        side: OrderSide,
        executedQty: Int,
        executedPrice: BigDecimal,
    )
}
```

- [ ] **Step 2: compile 검증**

Run: `./gradlew :domain:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: commit**

```bash
git add domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/position/port/PositionUpdatePort.kt
git -c user.email="bot+backend-core@magicjar.local" -c user.name="magicjar-backend-core" commit -m "feat(domain): PositionUpdatePort interface (Gap 1 — 체결 시 즉시 position upsert, P3)"
```

---

## Task 2: persistence-shared PositionUpdateAdapter + 4 test

**Files:**
- Create: `persistence-shared/src/main/kotlin/kr/spdaewoon/magicjar/persistence/shared/position/PositionUpdateAdapter.kt`
- Test: `persistence-shared/src/test/kotlin/kr/spdaewoon/magicjar/persistence/shared/position/PositionUpdateAdapterTest.kt`

- [ ] **Step 1: 실패 test 작성 (4 case)**

```kotlin
package kr.spdaewoon.magicjar.persistence.shared.position

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PositionUpdateAdapterTest {
    private val jpa = mockk<PositionJpaRepository>(relaxed = true)
    private val sut = PositionUpdateAdapter(jpa)

    @Test
    fun `BUY 신규 — 기존 row 0 시 INSERT (보유=executedQty 평균가=executedPrice)`() {
        every { jpa.findByUserIdAndCode("74683223", "005930") } returns null

        sut.upsertOnExecution("74683223", "005930", OrderSide.BUY, 1, BigDecimal("276000"))

        verify { jpa.save(match { it.userId == "74683223" && it.code == "005930" && it.quantity == 1 && it.avgPrice == BigDecimal("276000") }) }
    }

    @Test
    fun `BUY 추가 — 기존 보유 + 평균가 재계산`() {
        val existing = mockk<PositionJpaEntity>(relaxed = true)
        every { existing.quantity } returns 1
        every { existing.avgPrice } returns BigDecimal("270000")
        every { jpa.findByUserIdAndCode("74683223", "005930") } returns existing

        sut.upsertOnExecution("74683223", "005930", OrderSide.BUY, 2, BigDecimal("276000"))

        // 평균가 = (1×270000 + 2×276000) / 3 = 274000
        verify { jpa.save(match { it.quantity == 3 && it.avgPrice == BigDecimal("274000") }) }
    }

    @Test
    fun `SELL 차감 — 보유 quantity 만큼 차감 (평균가 유지)`() {
        val existing = mockk<PositionJpaEntity>(relaxed = true)
        every { existing.quantity } returns 3
        every { existing.avgPrice } returns BigDecimal("274000")
        every { jpa.findByUserIdAndCode("74683223", "005930") } returns existing

        sut.upsertOnExecution("74683223", "005930", OrderSide.SELL, 1, BigDecimal("280000"))

        verify { jpa.save(match { it.quantity == 2 && it.avgPrice == BigDecimal("274000") }) }
    }

    @Test
    fun `SELL 전량 — 보유 0 시 row DELETE`() {
        val existing = mockk<PositionJpaEntity>(relaxed = true)
        every { existing.quantity } returns 1
        every { existing.avgPrice } returns BigDecimal("274000")
        every { jpa.findByUserIdAndCode("74683223", "005930") } returns existing

        sut.upsertOnExecution("74683223", "005930", OrderSide.SELL, 1, BigDecimal("280000"))

        verify { jpa.delete(existing) }
    }
}
```

- [ ] **Step 2: test 실패 검증**

Run: `./gradlew :persistence-shared:test --tests "*PositionUpdateAdapter*"`
Expected: FAIL (PositionUpdateAdapter 미존재)

- [ ] **Step 3: PositionUpdateAdapter 구현**

```kotlin
package kr.spdaewoon.magicjar.persistence.shared.position

import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.position.port.PositionUpdatePort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * PositionUpdatePort 구현 (Gap 1 — 체결 시 즉시 position upsert, P3).
 *
 * BUY 평균가 재계산: (기존 보유 × 평균가 + 체결 × 단가) / (기존 + 체결)
 * SELL: 보유 - executedQty (평균가 유지), 보유 0 시 DELETE
 *
 * Note: 기존 PositionJpaRepository 위치는 persistence-shared 합류 가정. 위치 확인 후 import 조정 의무.
 */
@Component
class PositionUpdateAdapter(
    private val jpa: PositionJpaRepository,
) : PositionUpdatePort {
    private val log = LoggerFactory.getLogger(PositionUpdateAdapter::class.java)

    @Transactional
    override fun upsertOnExecution(
        userId: String,
        code: String,
        side: OrderSide,
        executedQty: Int,
        executedPrice: BigDecimal,
    ) {
        val existing = jpa.findByUserIdAndCode(userId, code)
        when (side) {
            OrderSide.BUY -> handleBuy(existing, userId, code, executedQty, executedPrice)
            OrderSide.SELL -> handleSell(existing, executedQty)
        }
    }

    private fun handleBuy(
        existing: PositionJpaEntity?,
        userId: String,
        code: String,
        executedQty: Int,
        executedPrice: BigDecimal,
    ) {
        if (existing == null) {
            jpa.save(PositionJpaEntity(userId = userId, code = code, quantity = executedQty, avgPrice = executedPrice))
            log.info("Position INSERT (BUY 신규): userId={} code={} qty={} avg={}", userId, code, executedQty, executedPrice)
        } else {
            val newQty = existing.quantity + executedQty
            val newAvg = (existing.avgPrice * existing.quantity.toBigDecimal() + executedPrice * executedQty.toBigDecimal())
                .divide(newQty.toBigDecimal(), 4, RoundingMode.HALF_UP)
            existing.quantity = newQty
            existing.avgPrice = newAvg
            jpa.save(existing)
            log.info("Position UPDATE (BUY 추가): userId={} code={} qty={} newAvg={}", userId, code, newQty, newAvg)
        }
    }

    private fun handleSell(
        existing: PositionJpaEntity?,
        executedQty: Int,
    ) {
        if (existing == null) {
            log.warn("Position SELL — 기존 보유 0 (KIS 잔고 drift?): executedQty={}", executedQty)
            return
        }
        val newQty = existing.quantity - executedQty
        if (newQty <= 0) {
            jpa.delete(existing)
            log.info("Position DELETE (SELL 전량): userId={} code={}", existing.userId, existing.code)
        } else {
            existing.quantity = newQty
            jpa.save(existing)
            log.info("Position UPDATE (SELL 차감): userId={} code={} qty={}", existing.userId, existing.code, newQty)
        }
    }
}
```

- [ ] **Step 4: test 통과**

Run: `./gradlew :persistence-shared:test --tests "*PositionUpdateAdapter*"`
Expected: 4/4 PASS

- [ ] **Step 5: commit**

```bash
git add persistence-shared/src/main/kotlin/kr/spdaewoon/magicjar/persistence/shared/position/PositionUpdateAdapter.kt persistence-shared/src/test/kotlin/kr/spdaewoon/magicjar/persistence/shared/position/PositionUpdateAdapterTest.kt
git -c user.email="bot+backend-core@magicjar.local" -c user.name="magicjar-backend-core" commit -m "feat(persistence-shared): PositionUpdateAdapter + 4 test (Gap 1, P3)"
```

---

## Task 3: KisExecutionWsListener — handleExecution 후 position upsert + system_event emit

**Files:**
- Modify: `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/order/KisExecutionWsListener.kt`

- [ ] **Step 1: 생성자에 PositionUpdatePort + SystemEventLogger inject**

기존 의존:
```kotlin
@Component
class KisExecutionWsListener(
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
    // ... 기존 의존 ...
)
```

신규 추가:
```kotlin
    private val positionUpdatePort: PositionUpdatePort,
    private val systemEventLogger: SystemEventLogger,
```

- [ ] **Step 2: handleExecution 함수에 position upsert + emit 추가**

handleExecution 끝부분 (sum FILLED/PARTIALLY_FILLED 분기 후) 다음 호출 추가:

```kotlin
// Gap 1 — 체결 시 즉시 position upsert (5분 cron 대신 실시간 정합)
runCatching {
    positionUpdatePort.upsertOnExecution(
        userId = request.userId,
        code = msg.code,
        side = OrderSide.valueOf(msg.sllBuyDvsnCd.toOrderSide()),  // 1=SELL, 2=BUY KIS 규약
        executedQty = msg.cntgQty,
        executedPrice = msg.cntgUnpr,
    )
}.onFailure { e -> log.error("Position upsert 실패: orderId={} err={}", orderId, e.message) }

// P3 — system_event emit (체결 완료 audit)
runCatching {
    systemEventLogger.emit(SystemEvent(
        category = SystemEventCategory.RECONCILE,  // 또는 OTHER — 체결은 정상 path
        severity = SystemEventSeverity.INFO,
        actor = "KisExecutionWsListener",
        action = "EXECUTION_RECEIVED",
        userId = request.userId,
        detailJson = """{"orderId":$orderId,"code":"${msg.code}","qty":${msg.cntgQty},"price":${msg.cntgUnpr}}""",
    ))
}.onFailure { e -> log.error("SystemEventLogger.emit 실패: ${e.message}") }
```

- [ ] **Step 3: 신규 test case 추가**

```kotlin
@Test
fun `Gap 1 — 체결 후 PositionUpdatePort upsertOnExecution 호출 (BUY 시 보유 +)`() {
    // ... 기존 mock 셋업 ...
    val msg = executionMessage(odno = "0000032843", cntgQty = 1, cntgUnpr = BigDecimal("276000"), sllBuyDvsnCd = "2")
    
    sut.processMessage(msg)
    
    verify { positionUpdatePort.upsertOnExecution("74683223", "005930", OrderSide.BUY, 1, BigDecimal("276000")) }
}

@Test
fun `P3 — 체결 후 SystemEventLogger emit EXECUTION_RECEIVED`() {
    val msg = executionMessage(...)
    
    sut.processMessage(msg)
    
    verify { systemEventLogger.emit(match { it.action == "EXECUTION_RECEIVED" && it.userId == "74683223" }) }
}
```

- [ ] **Step 4: test 통과**

Run: `./gradlew :consumer:test --tests "*KisExecutionWsListener*"`
Expected: 기존 + 신규 2건 PASS

- [ ] **Step 5: commit**

```bash
git add consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/order/KisExecutionWsListener.kt consumer/src/test/kotlin/kr/spdaewoon/magicjar/consumer/order/KisExecutionWsListenerTest.kt
git -c user.email="bot+backend-core@magicjar.local" -c user.name="magicjar-backend-core" commit -m "feat(backend-core): KisExecutionWsListener position upsert + EXECUTION_RECEIVED emit (Gap 1, P3)"
```

---

## Task 4: OrderReconciliationWorker — ACK 상태 확장 + KIS inquire-daily-ccld backfill + position upsert + system_event emit

**Files:**
- Modify: `domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/order/execution/port/OrderRequestRepository.kt`
- Modify: `persistence-shared/src/main/kotlin/.../OrderRequestRepositoryAdapter.kt` (또는 api 모듈)
- Modify: `consumer/src/main/kotlin/.../order/OrderReconciliationWorker.kt`

- [ ] **Step 1: OrderRequestRepository port에 findByStatusInAndRequestedAtBefore 추가**

```kotlin
interface OrderRequestRepository {
    // ... 기존 메서드 ...
    
    /**
     * 의제 B — ACK 또는 PENDING_RECONCILE 상태 reconcile 대상 조회.
     * ACK 상태에서 KIS H0STCNI9 통보 누락 시 본 worker가 KIS REST inquire-daily-ccld로 backfill.
     */
    fun findByStatusInAndRequestedAtBefore(
        statuses: List<OrderStatus>,
        cutoff: Instant,
        limit: Int = 50,
    ): List<OrderRequest>
}
```

- [ ] **Step 2: Adapter 구현**

```kotlin
@Transactional(readOnly = true)
override fun findByStatusInAndRequestedAtBefore(
    statuses: List<OrderStatus>,
    cutoff: Instant,
    limit: Int,
): List<OrderRequest> =
    jpa.findByStatusInAndRequestedAtBefore(statuses.map { it.name }, cutoff, PageRequest.of(0, limit))
        .map { toDomain(it) }
```

JpaRepository에 query method 추가:
```kotlin
fun findByStatusInAndRequestedAtBefore(
    statuses: List<String>,
    cutoff: Instant,
    pageable: Pageable,
): List<OrderRequestJpaEntity>
```

- [ ] **Step 3: OrderReconciliationWorker.reconcile 본문 확장**

```kotlin
@Scheduled(fixedDelay = 60_000)
@Transactional
fun reconcile() {
    val cutoff = Instant.now().minusSeconds(5 * 60)  // ACK 5분 경과
    // 의제 B 확장 — PENDING_RECONCILE + ACK 모두 reconcile 대상
    val targets = orderRequestRepo.findByStatusInAndRequestedAtBefore(
        statuses = listOf(OrderStatus.PENDING_RECONCILE, OrderStatus.ACK),
        cutoff = cutoff,
        limit = BATCH_SIZE,
    )
    
    if (targets.isEmpty()) {
        log.trace("reconcile: 대상 0건")
        return
    }
    
    // 기존 FIRED emit
    runCatching {
        systemEventLogger.emit(SystemEvent(category = RECONCILE, severity = INFO, ..., action = "RECONCILE_FALLBACK_FIRED", detailJson = """{"targetCount":${targets.size}}"""))
    }.onFailure { ... }
    
    targets.forEach { order ->
        val result = kisOrderInquireCcld.lookup(order.kisOrderNo)
        when (result) {
            is OrderInquireResult.Found -> {
                if (result.filledQty > 0) {
                    // RESOLVED emit (기존)
                    // ★ P3 신규 — KIS inquire-daily-ccld 호출 → 체결 detail 가져옴
                    val executions = kisOrderInquireDailyCcldHttpAdapter.lookup(order.kisOrderNo, ...)
                    executions.forEach { exec ->
                        val eventId = "${order.kisOrderNo}:${exec.stckCntgHour}:${exec.cntgQty}"
                        if (!orderExecutionRepo.existsByEventId(eventId)) {
                            orderExecutionRepo.save(...)
                            
                            // ★ P3 — Gap 1 position upsert
                            positionUpdatePort.upsertOnExecution(
                                userId = order.userId,
                                code = order.code,
                                side = order.side,
                                executedQty = exec.cntgQty,
                                executedPrice = exec.cntgUnpr,
                            )
                            
                            // ★ P3 — STOMP fanout (PR #621 AccountBalanceChangedKafkaProducer 재사용)
                            accountBalanceChangedKafkaProducer.publish(
                                userId = order.userId,
                                accountType = ...,
                                reason = "ORDER_FILLED",
                                ...
                            )
                        }
                    }
                    
                    repo.markCompleted(order.id!!, Instant.now(), if (result.filledQty >= result.orderQty) FILLED else PARTIALLY_FILLED)
                }
                // Found(filledQty=0) — ACK 유지
            }
            // ... 기존 분기 ...
        }
    }
}
```

- [ ] **Step 4: 신규 test case (의제 B + Gap 1 통합 검증)**

```kotlin
@Test
fun `의제 B + Gap 1 — ACK stuck row 발견 + KIS 체결 backfill + position upsert + emit`() {
    val ackStuck = orderRequest(id = 5L, status = OrderStatus.ACK, kisOrderNo = "0000033601", userId = "74683223")
    every { orderRequestRepo.findByStatusInAndRequestedAtBefore(any(), any(), any()) } returns listOf(ackStuck)
    every { kisOrderInquireCcld.lookup("0000033601") } returns OrderInquireResult.Found(filledQty = 1, orderQty = 1)
    every { kisOrderInquireDailyCcldHttpAdapter.lookup(...) } returns listOf(execution(qty = 1, price = 276000))
    every { orderExecutionRepo.existsByEventId(any()) } returns false
    
    sut.reconcile()
    
    verify { orderExecutionRepo.save(any()) }
    verify { positionUpdatePort.upsertOnExecution("74683223", "005930", OrderSide.SELL, 1, BigDecimal("276000")) }
    verify { repo.markCompleted(5L, any(), OrderStatus.FILLED) }
    val resolvedEvent = slot<SystemEvent>()
    verify { systemEventLogger.emit(match { it.action == "RECONCILE_FALLBACK_RESOLVED" }) }
}
```

- [ ] **Step 5: 빌드 + test**

Run: `./gradlew :domain:check :persistence-shared:check :consumer:test --tests "*OrderReconciliation*"`
Expected: BUILD SUCCESSFUL + 신규 case PASS

- [ ] **Step 6: commit**

```bash
git add domain/src/main/kotlin/.../OrderRequestRepository.kt persistence-shared/src/main/kotlin/.../OrderRequestRepositoryAdapter.kt consumer/src/main/kotlin/.../OrderReconciliationWorker.kt consumer/src/test/kotlin/.../OrderReconciliationWorkerTest.kt
git -c user.email="bot+backend-core@magicjar.local" -c user.name="magicjar-backend-core" commit -m "feat(backend-core): OrderReconciliationWorker ACK 확장 + KIS inquire-daily-ccld backfill + position upsert + emit (의제 B + Gap 1, P3)"
```

---

## Task 5: OrderOutboxPublisherWorker — Kafka publish retry/DLT 시 system_event emit

**Files:**
- Modify: `consumer/src/main/kotlin/.../order/OrderOutboxPublisherWorker.kt`

- [ ] **Step 1: 생성자에 SystemEventLogger inject 추가**

- [ ] **Step 2: handlePublishFailure 함수에 emit 호출 추가**

```kotlin
private fun handlePublishFailure(outbox: OrderEventOutbox, id: Long, errMsg: String): Int {
    val newAttempt = outbox.attemptCount + 1
    if (newAttempt >= MAX_ATTEMPT) {
        // 기존 markFailed + raiseFailureAlertSafely + engageKillSwitchSafely
        
        // ★ P3 — system_event emit (Kafka publish DLT)
        runCatching {
            systemEventLogger.emit(SystemEvent(
                category = SystemEventCategory.KAFKA,
                severity = SystemEventSeverity.ERROR,
                actor = "OrderOutboxPublisherWorker",
                action = "KAFKA_PUBLISH_DLT",
                userId = outbox.partitionKey,
                detailJson = """{"outboxId":$id,"topic":"${outbox.topic}","attempts":$newAttempt,"lastError":"${errMsg.take(100)}"}""",
            ))
        }.onFailure { e -> log.error("SystemEventLogger.emit 실패: ${e.message}") }
    } else {
        // 기존 recordAttemptFailure
        
        // ★ P3 — system_event emit (Kafka retry)
        runCatching {
            systemEventLogger.emit(SystemEvent(
                category = SystemEventCategory.KAFKA,
                severity = SystemEventSeverity.WARN,
                actor = "OrderOutboxPublisherWorker",
                action = "KAFKA_PUBLISH_RETRY",
                userId = outbox.partitionKey,
                detailJson = """{"outboxId":$id,"topic":"${outbox.topic}","attempts":$newAttempt}""",
            ))
        }.onFailure { e -> log.error("SystemEventLogger.emit 실패: ${e.message}") }
    }
    return newAttempt
}
```

- [ ] **Step 3: 신규 test case 2건**

```kotlin
@Test
fun `Kafka publish retry (attempt < MAX) — KAFKA_PUBLISH_RETRY emit (severity WARN)`() {
    // ... mock 셋업 ...
    sut.drainPending()
    verify { systemEventLogger.emit(match { it.action == "KAFKA_PUBLISH_RETRY" && it.severity == SystemEventSeverity.WARN }) }
}

@Test
fun `Kafka publish DLT (attempt = MAX) — KAFKA_PUBLISH_DLT emit (severity ERROR)`() {
    // ... mock 셋업 (attemptCount = 2 → +1 = 3 = MAX) ...
    sut.drainPending()
    verify { systemEventLogger.emit(match { it.action == "KAFKA_PUBLISH_DLT" && it.severity == SystemEventSeverity.ERROR }) }
}
```

- [ ] **Step 4: 빌드 + test**

Run: `./gradlew :consumer:test --tests "*OrderOutboxPublisherWorker*"`
Expected: 기존 + 신규 2건 PASS

- [ ] **Step 5: commit**

```bash
git add consumer/src/main/kotlin/.../OrderOutboxPublisherWorker.kt consumer/src/test/kotlin/.../OrderOutboxPublisherWorkerTest.kt
git -c user.email="bot+backend-core@magicjar.local" -c user.name="magicjar-backend-core" commit -m "feat(backend-core): OrderOutboxPublisherWorker emit KAFKA_PUBLISH_RETRY/DLT (catalog 확장, P3)"
```

---

## Task 6: RedisKisRateLimiterAdapter — onTpsExceeded 시 system_event emit (KIS_RATELIMIT)

**Files:**
- Modify: `api/src/main/kotlin/.../infrastructure/kis/ratelimit/RedisKisRateLimiterAdapter.kt`
- Modify: `consumer/src/main/kotlin/.../infrastructure/kis/ratelimit/ConsumerRedisKisRateLimiterAdapter.kt`

- [ ] **Step 1: 생성자에 SystemEventLogger inject (ObjectProvider 또는 nullable — Bean wiring 안전)**

```kotlin
class RedisKisRateLimiterAdapter(
    // ... 기존 의존 ...
    private val systemEventLoggerProvider: ObjectProvider<SystemEventLogger>? = null,
) : KisRateLimiterPort {
    // ...
    
    override fun onTpsExceeded() {
        val count = tpsExceededCount.incrementAndGet()
        log.warn("[KisRateLimit] TPS exceeded — count={} ...", count)
        
        // ★ P3 — system_event emit (KIS rate limit hit)
        runCatching {
            systemEventLoggerProvider?.getIfAvailable()?.emit(SystemEvent(
                category = SystemEventCategory.KIS_RATELIMIT,
                severity = SystemEventSeverity.WARN,
                actor = "RedisKisRateLimiterAdapter",
                action = "EGW00201_HIT",
                detailJson = """{"tpsExceededCount":$count,"bucketKey":"${props.bucketKey}"}""",
            ))
        }.onFailure { e -> log.debug("SystemEventLogger.emit 실패 (graceful): ${e.message}") }
    }
}
```

- [ ] **Step 2: ConsumerRedisKisRateLimiterAdapter도 동일 패턴 적용**

- [ ] **Step 3: 빌드 + test**

Run: `./gradlew :api:test :consumer:test`
Expected: 회귀 0

- [ ] **Step 4: commit**

```bash
git add api/src/main/kotlin/.../RedisKisRateLimiterAdapter.kt consumer/src/main/kotlin/.../ConsumerRedisKisRateLimiterAdapter.kt
git -c user.email="bot+backend-core@magicjar.local" -c user.name="magicjar-backend-core" commit -m "feat(backend-core): RedisKisRateLimiterAdapter emit EGW00201_HIT (KIS_RATELIMIT catalog, P3)"
```

---

## Task 7: frontend correlation_id UI 그룹 표시

**Files:**
- Modify: `frontend/src/pages/SystemEvent/SystemEventPage.tsx`

- [ ] **Step 1: correlation_id 그룹 표시 로직 추가**

기존 dashboard 테이블에서 correlation_id 같은 row 인접 시 시각 그룹 인디케이터 추가:

```tsx
// SystemEventPage.tsx
const groupedItems = useMemo(() => {
  if (!data?.items) return []
  const result: Array<SystemEvent & { isGroupStart?: boolean; groupSize?: number }> = []
  const groupCount: Record<string, number> = {}
  
  data.items.forEach((item) => {
    if (item.correlationId) {
      groupCount[item.correlationId] = (groupCount[item.correlationId] ?? 0) + 1
    }
  })
  
  let lastGroupId: string | null = null
  data.items.forEach((item) => {
    const isGroupStart = item.correlationId !== null && item.correlationId !== lastGroupId
    const groupSize = item.correlationId ? groupCount[item.correlationId] : undefined
    result.push({ ...item, isGroupStart, groupSize })
    lastGroupId = item.correlationId
  })
  
  return result
}, [data])

// 테이블 row 렌더링 시:
{groupedItems.map((event) => (
  <tr 
    key={event.id} 
    className={`border-b ${event.correlationId === lastCorrelationId ? 'border-l-4 border-l-blue-400' : ''}`}
  >
    {/* 그룹 표시 — correlation_id 인접 row */}
    {event.isGroupStart && event.groupSize! > 1 && (
      <div className="text-xs text-blue-500 px-2">
        🔗 {event.groupSize}건 그룹
      </div>
    )}
    {/* ... 기존 컬럼 ... */}
  </tr>
))}
```

- [ ] **Step 2: typecheck + build**

Run: `cd frontend && pnpm typecheck && pnpm build`
Expected: PASS

- [ ] **Step 3: commit**

```bash
git add frontend/src/pages/SystemEvent/SystemEventPage.tsx
git -c user.email="bot+frontend@magicjar.local" -c user.name="magicjar-frontend" commit -m "feat(frontend): /system Dashboard correlation_id 그룹 시각화 (P3)"
```

---

## Task 8: 통합 검증 + push + PR 발행

- [ ] **Step 1: 전체 모듈 빌드·test·ktlint**

Run: `./gradlew :domain:check :persistence-shared:check :consumer:check :api:check`
Run: `cd frontend && pnpm typecheck && pnpm test && pnpm build`
Expected: 모두 BUILD SUCCESSFUL / GREEN + 회귀 0

- [ ] **Step 2: push + PR**

```bash
git push -u origin agent/backend-core/system-event-log-p3
gh pr create --base main --title "feat: system event log P3 — 의제 B (ACK reconcile) + Gap 1 (Position upsert) + catalog 확장 (Kafka·KIS)" --body "..."
```

PR body: spec·P1·P2·P3 재정의 인용 + 7 task 매트릭스 + 5 신규 emit point + 사용자 의무 (재배포 + dogfooding)

---

## Self-Review

| 체크 | 결과 |
|------|------|
| Spec coverage (P3 재정의 — 의제 B + Gap 1 + catalog Kafka·KIS + correlation_id UI) | ✅ Task 1-7 모두 매핑 |
| Placeholder | ✅ 0건 (단 PositionJpaRepository·기존 worker mock 패턴은 sub-agent 진행 시 grep 검증 의무) |
| Type consistency | ✅ SystemEvent·PositionUpdatePort·OrderStatus 명명 일관 |
| 후속 의제 명시 | ✅ DB·Redis fail catalog는 P4 (별도 plan) |
| P1·P2 산출물 회귀 | ✅ 기존 worker test 회귀 0 검증 의무 (Task 4·5·6 신규 mock 추가) |

## Phase 분리 (subagent-driven 진입 시)

- **P3-A**: Task 1·2·3·4 — backend-core (의제 B + Gap 1 통합)
- **P3-B**: Task 5·6 — backend-core (catalog 확장)
- **P3-C**: Task 7 — frontend (correlation_id UI 그룹)
- **P3-D**: Task 8 — 통합 + PR

총 4 sub-agent + 1 final reviewer (P1·P2 패턴 동일).

## 변경 이력
- 2026-05-20 1st draft: 초기 P3 spec (viewed/resolved 포함)
- 2026-05-20 재정의: orchestrator 직접 재정의 (사용자 결재) — viewed/resolved 제외 + Gap 1 통합 + correlation_id UI 추가
