# 시스템 운영 이벤트 로그 설계 (System Event Log)

**발행**: 2026-05-20
**작성자**: architect (brainstorming 스킬 사용)
**상태**: Draft → User Review 대기
**관련 의제**: ACK stuck reconcile (의제 B) — 본 spec 위에 얹어 진행

## 1. 의제 정의

magicJar는 자동매매 자본 path 전반에서 **WebSocket/이벤트가 주요 path, cron은 fallback 대안**으로 설계됨. 단 다음 운영 가시성이 부재:

1. **Reconcile fallback이 시도되거나 성공해서 변경 발생** = 운영 결함 신호인데 히스토리 0
2. **WebSocket 끊김/재연결/실패 이유** 추적 불가
3. **Kafka publish retry·KIS rate limit hit (EGW00201)·DB·Redis fail** 등 외부 의존 실패 가시성 0
4. 사용자(최고 관리자)가 운영 결함을 화면에서 즉시 인지 못함 → dogfooding 의무

사용자 비판 정합:
> "예를 들어 웹소켓이 끊겼는데 이유 왜 끊겼다 재연결했다 이런걸 남기기 위한거지"

## 2. 사용자 요구사항 (brainstorming 결재)

| # | 요구사항 |
|---|---------|
| R1 | WebSocket/이벤트 = 주요 path, cron = fallback (LOCK 정합) |
| R2 | fallback 시도·성공·실패 모두 히스토리 영속 |
| R3 | 알람센터(벨) + 운영 dashboard 페이지 + CRITICAL toast (UI) |
| R4 | 확장 가능 — 다양한 운영 이벤트 cover (WebSocket·Kafka·KIS·DB·Redis 등) |
| R5 | 의제 B (ACK stuck reconcile)는 본 spec 위에 얹어 진행 |

## 3. Scope (3 Phase 분해)

| Phase | scope | 영역 | 규모 |
|-------|-------|------|------|
| **P1** | Flyway V44 + domain port `SystemEventLogger` + persistence-shared adapter + `SystemEventKafkaProducer` + emit point 5 (RECONCILE 3 + WEBSOCKET 2) | domain·consumer·api·persistence-shared | ~10 file |
| **P2** | `SystemEventStompNotifier` + frontend `useSystemEventStompSubscription` + 알림센터 + `/system` dashboard + CRITICAL toast | api·frontend | ~12 file |
| **P3** | catalog 확장 (Kafka retry·KIS rate limit·DB·Redis fail) + **의제 B (ACK reconcile)** 통합 + viewed/resolved REST endpoint | 다영역 | ~15 file |

## 4. Approach 비교 + 채택

### Approach 1: Outbox + Kafka + STOMP (★ 채택)
```
emit source → SystemEventLogger → DB INSERT + Kafka publish
            → @KafkaListener → STOMP fanout → frontend hook
```
- **장점**: PR #621 (account.balance.changed) 패턴 1:1 정합, 모듈 경계 정합 (consumer가 STOMP 직접 호출 X)
- **단점**: 모든 이벤트가 Kafka 한 번 거침 (운영 이벤트 빈도 낮음 — 무시 가능)

### Approach 2: DB 직접 + frontend polling (거부)
- 거부: 실시간성 ↓, polling 패턴 회귀 (사용자 비판 정합 위반)

### Approach 3: STOMP 직접 fanout (Kafka 우회, 거부)
- 거부: consumer 모듈이 STOMP 직접 사용 → 모듈 경계 위반 (ADR 0004)

## 5. Architecture

```mermaid
flowchart TB
    subgraph SRC["emit source (확장 가능)"]
        E1[OrderReconciliationWorker fallback fired/succeeded/failed]
        E2[BalanceReconcileJob drift detected/resolved]
        E3[KisExecutionBackfillService backfill fired/empty]
        E4[KisWebSocketBridge disconnect/reconnect/fail]
        E5[Kafka publish retry/DLT - P3 catalog 확장]
        E6[KIS rate limit EGW00201 hit - P3]
        E7[DB·Redis fail - P3]
    end

    SRC --> EL[SystemEventLogger domain port]
    EL --> ADAPTER[SystemEventLoggerAdapter persistence-shared]
    ADAPTER --> DB[(system_event 테이블)]
    ADAPTER --> KP[SystemEventKafkaProducer]
    KP --> KAFKA[Kafka system.event.v1]
    KAFKA --> NOTIFIER[api SystemEventStompNotifier]

    NOTIFIER --> SPLIT{userId null?}
    SPLIT -->|NO user-specific| STOMP1[/topic/system.event.user.userId]
    SPLIT -->|YES system-wide| STOMP2[/topic/system.event.broadcast]

    STOMP1 & STOMP2 --> UI_HOOK[frontend useSystemEventStompSubscription]
    UI_HOOK --> CENTER[NotificationCenter 벨 아이콘]
    UI_HOOK --> TOAST{severity = CRITICAL?}
    TOAST -->|YES| TOAST_SHOW[Toast popup]
    TOAST -->|NO| SKIP[skip toast]
    UI_HOOK --> DASHBOARD[/system 운영 dashboard]
```

## 6. Components

### 6.1 domain port (확장 가능 핵심)

```kotlin
// domain/event/SystemEventLogger.kt
interface SystemEventLogger {
    /**
     * 운영 이벤트 emit — DB 영속 + Kafka publish.
     *
     * graceful: emit 실패는 listener thread 안 죽임 (caller가 runCatching wrap 의무).
     */
    fun emit(event: SystemEvent)
}

data class SystemEvent(
    val category: SystemEventCategory,
    val severity: SystemEventSeverity,
    val actor: String,                   // 발사 component (예: "OrderReconciliationWorker")
    val action: String,                  // 이벤트 코드 (예: "RECONCILE_FALLBACK_FIRED")
    val userId: String? = null,          // null = system-wide broadcast
    val correlationId: String? = null,   // 동일 사건 묶기 (자동 생성 + emit site 명시 전달)
    val detailJson: String,              // 자유 형식 JSON
    val occurredAt: Instant = Instant.now(),
)

enum class SystemEventCategory {
    RECONCILE,        // P1 — Reconcile worker 3종
    WEBSOCKET,        // P1 — KisWebSocketBridge disconnect/reconnect/fail
    KAFKA,            // P3 — Kafka publish retry·DLT
    KIS_RATELIMIT,    // P3 — EGW00201·EGW00133
    DB,               // P3 — DB connection·query fail
    REDIS,            // P3 — Redis fail
    OTHER,            // 확장용
}

enum class SystemEventSeverity { INFO, WARN, ERROR, CRITICAL }
```

### 6.2 persistence-shared adapter

```kotlin
// persistence-shared/.../event/SystemEventLoggerAdapter.kt
@Component
class SystemEventLoggerAdapter(
    private val jpa: SystemEventJpaRepository,
    private val kafkaProducer: SystemEventKafkaProducer,
) : SystemEventLogger {
    @Transactional
    override fun emit(event: SystemEvent) {
        runCatching {
            val saved = jpa.save(SystemEventJpaEntity.fromDomain(event))
            // Kafka publish는 graceful (DB 영속은 성공해도 Kafka 실패 OK)
            runCatching { kafkaProducer.publish(saved.toDomain()) }
                .onFailure { e -> log.warn("SystemEvent Kafka publish 실패 — DB 영속 정상: id={} err={}", saved.id, e.message) }
        }.onFailure { e ->
            log.error("SystemEvent DB INSERT 실패: category={} severity={} err={}", event.category, event.severity, e.message)
        }
    }
}
```

### 6.3 Kafka producer

```kotlin
// consumer 또는 persistence-shared/.../event/SystemEventKafkaProducer.kt
@Component
class SystemEventKafkaProducer(private val kafkaTemplate: KafkaTemplate<String, String>, private val objectMapper: ObjectMapper) {
    fun publish(event: SystemEvent) {
        val partitionKey = event.userId ?: "broadcast"
        val payload = objectMapper.writeValueAsString(event)
        kafkaTemplate.send("system.event.v1", partitionKey, payload).get(5, TimeUnit.SECONDS)
    }
}
```

### 6.4 api notifier

```kotlin
// api/.../messaging/SystemEventStompNotifier.kt
@Component
class SystemEventStompNotifier(private val stomp: SimpMessagingTemplate, private val objectMapper: ObjectMapper) {
    @KafkaListener(topics = ["system.event.v1"], groupId = "magicjar-system-event-stomp")
    fun onMessage(record: ConsumerRecord<String, String>) {
        runCatching {
            val event = objectMapper.readValue<SystemEvent>(record.value())
            val topic = if (event.userId != null) {
                "/topic/system.event.user.${event.userId}"
            } else {
                "/topic/system.event.broadcast"
            }
            stomp.convertAndSend(topic, event)
        }.onFailure { e -> log.error("SystemEvent STOMP fanout 실패: err={}", e.message) }
    }
}
```

### 6.5 frontend hook

```typescript
// frontend/src/api/hooks/useSystemEventStompSubscription.ts
export function useSystemEventStompSubscription(userId: string | null) {
    const queryClient = useQueryClient()
    const { showToast } = useToast()

    // user-specific topic
    useStompTopic(
        userId ? `/topic/system.event.user.${userId}` : null,
        (event: SystemEvent) => handleEvent(event, queryClient, showToast),
    )

    // broadcast topic (모든 사용자)
    useStompTopic(
        '/topic/system.event.broadcast',
        (event: SystemEvent) => handleEvent(event, queryClient, showToast),
    )
}

function handleEvent(event: SystemEvent, queryClient: QueryClient, showToast: ShowToast) {
    queryClient.invalidateQueries({ queryKey: ['system-event'], exact: false })
    if (event.severity === 'CRITICAL') {
        showToast(`[${event.category}] ${event.action}: ${event.detailJson}`, 'error')
    }
}
```

### 6.6 NotificationCenter (P2)

```tsx
// frontend/src/components/notification/NotificationCenter.tsx
export function NotificationCenter() {
    const { data: events, unreadCount } = useSystemEventList({ unreadOnly: true, limit: 10 })
    // 벨 아이콘 + 드롭다운 + viewed_at 마킹
}
```

### 6.7 /system dashboard (P2)

```tsx
// frontend/src/pages/SystemEvent/SystemEventPage.tsx
export function SystemEventPage() {
    // 필터 (category·severity·viewed/unviewed) + 페이지네이션 + correlation_id 그룹
}
```

## 7. Data Model

```sql
-- Flyway V44__system_event.sql
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

CREATE INDEX idx_system_event_occurred ON system_event (occurred_at DESC);
CREATE INDEX idx_system_event_user_unread ON system_event (user_id, viewed_at) WHERE viewed_at IS NULL;
CREATE INDEX idx_system_event_category ON system_event (category, severity, occurred_at DESC);
CREATE INDEX idx_system_event_correlation ON system_event (correlation_id) WHERE correlation_id IS NOT NULL;
```

## 8. P1 emit point 5건 (구체 매트릭스)

| # | source | category | severity | action |
|---|--------|----------|----------|--------|
| 1 | OrderReconciliationWorker.reconcile fired (PENDING_RECONCILE 0건 아님) | RECONCILE | INFO | `RECONCILE_FALLBACK_FIRED` |
| 2 | OrderReconciliationWorker resolved (status 전환 성공) | RECONCILE | WARN | `RECONCILE_FALLBACK_RESOLVED` |
| 3 | BalanceReconcileJob drift detected (KIS 잔고 ≠ DB position) | RECONCILE | WARN | `RECONCILE_DRIFT_DETECTED` |
| 4 | KisWebSocketBridge disconnect | WEBSOCKET | WARN | `WS_DISCONNECTED` |
| 5 | KisWebSocketBridge reconnect 성공 | WEBSOCKET | INFO | `WS_RECONNECTED` (correlationId = disconnect 시 발급 UUID) |

## 9. Error Handling

| 결함 | 대응 |
|------|------|
| EventLogger.emit DB INSERT 실패 | log.error + Kafka publish 시도 (DB 실패가 Kafka 막지 않음 — 단 정상 case는 DB 영속이 source-of-truth) |
| EventLogger.emit Kafka publish 실패 | log.warn (DB 영속은 OK라 후속 정합 가능 — 단 STOMP fanout 손실) |
| caller side cascade 차단 | `runCatching { eventLogger.emit(event) }.onFailure { log.error(...) }` 패턴 의무 |
| EventLogger 자체 결함이 무한 루프 | emit 자체에서 발생한 예외는 emit 0 (log.error만, 자기 자신 호출 X) |

## 10. Testing

### 10.1 Unit
- `SystemEventLoggerAdapter`: DB INSERT 정합 + Kafka publish 정합 + DB 실패·Kafka 실패 graceful
- `SystemEventStompNotifier`: @KafkaListener 수신 + user-specific/broadcast 분기 + STOMP fanout 검증
- 각 emit point unit test: 결함 시뮬레이션 → emit 호출 검증

### 10.2 Integration (P3)
- end-to-end: 결함 시뮬레이션 → DB row 확인 + STOMP 메시지 수신 + 알림센터 update + CRITICAL toast (frontend mock)

## 11. STOMP topic 분기 (확정)

- `SystemEvent.userId != null` → `/topic/system.event.user.{userId}` (user-specific)
- `SystemEvent.userId == null` → `/topic/system.event.broadcast` (system-wide)
- frontend는 두 topic 모두 subscribe (DashboardPage 또는 App-level mount)

## 12. correlation_id 사용 패턴 (확정)

- 자동 생성: framework default `UUID.randomUUID()` (emit site에서 명시 안 하면 자동)
- emit site 명시 전달: 동일 사건 묶을 때 (예: WS disconnect 시 발급 UUID → reconnect 시 동일 ID 재사용)
- UI 그룹: correlation_id 같은 이벤트는 dashboard에서 묶어 표시 (P2)
- null 가능 — UI에서 그룹 안 함 (단발 이벤트)

## 13. 보존 정책

- DB row 영구 보존 (운영 audit 의무)
- `viewed_at` 마킹: 사용자 행위 (UN read count 계산용)
- `resolved_at` 마킹: dismiss (목록에서 사라지지 않고 표시만 변경)
- archive 정책은 P3 이후 별도 ADR (현재 의제 외)

## 14. 후속 의제 통합 (P3 — 의제 B)

P3에서 의제 B (ACK stuck reconcile) 진행 시 본 framework 위에 얹음:
- OrderReconciliationWorker `findByStatusInAndRequestedAtBefore` (ACK + PENDING_RECONCILE 통합)
- 정합 시 `SystemEventLogger.emit(RECONCILE, WARN, "ACK_STUCK_RESOLVED", ...)` 호출
- 즉 의제 B는 본 spec P3에서 cover

## 15. 영역 분리 / 단일 책임

- `SystemEventLogger`: 단일 책임 (emit interface)
- `SystemEventLoggerAdapter`: 단일 책임 (DB·Kafka outbox 영속)
- `SystemEventStompNotifier`: 단일 책임 (Kafka → STOMP fanout)
- `NotificationCenter`: 단일 책임 (UI 알림센터)
- `/system Dashboard`: 단일 책임 (UI 운영 dashboard)

각 component는 단일 책임 정합. 기존 Reconcile/WebSocket/Kafka component는 emit 호출 1 line 추가만 (영역 침범 0).

## 16. Phase 1 dispatch 매트릭스

| sub-agent | scope | 영역 |
|-----------|-------|------|
| architect | ADR 발행 (모듈 경계·STOMP topic 분기·event catalog 매트릭스 LOCK) | docs only |
| backend-core | persistence-shared (entity·repo·adapter) + consumer (Producer·5 emit point) + api (Notifier) | 다영역 large |

P1 dispatch는 본 spec 승인 후 writing-plans 스킬에서 구체 plan으로 분해.

## 17. ADR 발행 권장

본 spec 위에 ADR 발행 의무 (architect 단발 작업, brainstorm 외 별도):
- 제목: `시스템 운영 이벤트 로그 정책 (System Event Log)`
- 결정 사항: WebSocket/이벤트 = 메인 path LOCK + cron = fallback LOCK + EventLogger 통합 framework + STOMP topic 분기 + 보존 정책

## 18. 변경 이력

- 2026-05-20: Draft 발행 (brainstorming 스킬, orchestrator 작성)
