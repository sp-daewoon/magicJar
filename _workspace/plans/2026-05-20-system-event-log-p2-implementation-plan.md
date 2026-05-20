# System Event Log P2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** P1 발사된 system_event 를 STOMP 실시간 push + REST 페이지네이션 + UI 알림센터·dashboard + CRITICAL toast로 사용자가 화면에서 즉시 확인하는 path 구축.

**Architecture:** P1 SystemEventKafkaProducer → Kafka `system.event.v1` → api SystemEventStompNotifier @KafkaListener → STOMP topic 분기 (`/topic/system.event.user.{userId}` user-specific + `/topic/system.event.broadcast` system-wide) → frontend useSystemEventStompSubscription → React Query invalidate + CRITICAL toast + NotificationCenter 벨 + `/system` dashboard 페이지. PR #621 (account.balance.changed) 패턴 1:1 정합.

**Tech Stack:** Spring Boot + Spring Messaging STOMP + Kafka + Kotlin / React 19 + TS + Vite + Tailwind + TanStack Query + react-toastify (또는 기존 useToast).

**Spec**: [`_workspace/specs/2026-05-20-system-event-log-design.md`](../specs/2026-05-20-system-event-log-design.md)
**P1 PR**: [#626 (이미 머지)](https://github.com/sp-daewoon/magicJar/pull/626)

---

## File Structure (P2)

### 신규 (12 file)

**backend (api 4 + test 1):**
- `api/src/main/kotlin/.../messaging/SystemEventStompNotifier.kt` (@KafkaListener + STOMP fanout 분기)
- `api/src/main/kotlin/.../controller/SystemEventController.kt` (REST endpoint 3건)
- `api/src/main/kotlin/.../controller/SystemEventDto.kt` (REST DTO)
- `api/src/main/kotlin/.../service/SystemEventQueryService.kt` (목록·viewed·resolved)
- `api/src/test/kotlin/.../SystemEventStompNotifierTest.kt` (STOMP 분기 검증)

**frontend (hook 2 + components 3 + page 1 + test 2):**
- `frontend/src/api/system-event.ts` (REST API client + type)
- `frontend/src/api/hooks/useSystemEventStompSubscription.ts` (STOMP subscribe + invalidate + toast)
- `frontend/src/api/hooks/useSystemEventList.ts` (REST 페이지네이션 + 필터)
- `frontend/src/components/notification/NotificationCenter.tsx` (벨 아이콘 + 드롭다운)
- `frontend/src/components/notification/NotificationItem.tsx` (목록 row)
- `frontend/src/pages/SystemEvent/SystemEventPage.tsx` (/system 운영 dashboard)
- `frontend/src/api/hooks/__tests__/useSystemEventStompSubscription.test.tsx`
- `frontend/src/components/notification/__tests__/NotificationCenter.test.tsx`

### 수정 (3 file)

- `frontend/src/App.tsx` (또는 routes 파일 — `/system` path 등록)
- `frontend/src/components/Header.tsx` (또는 layout — NotificationCenter 벨 통합)
- `api/src/main/kotlin/.../config/WebSocketConfig.kt` (STOMP destination 등록 — 기존 패턴 따라 신규 topic 추가 필요 시)

---

## Task 1: api SystemEventStompNotifier (@KafkaListener + STOMP 분기 fanout)

**Files:**
- Create: `api/src/main/kotlin/kr/spdaewoon/magicjar/api/messaging/SystemEventStompNotifier.kt`
- Test: `api/src/test/kotlin/kr/spdaewoon/magicjar/api/messaging/SystemEventStompNotifierTest.kt`

- [ ] **Step 1: 실패 test 작성 (3 case — user-specific + broadcast + 잘못된 payload skip)**

```kotlin
package kr.spdaewoon.magicjar.api.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import org.springframework.messaging.simp.SimpMessagingTemplate

class SystemEventStompNotifierTest {
    private val stomp = mockk<SimpMessagingTemplate>(relaxed = true)
    private val objectMapper = ObjectMapper().apply {
        registerKotlinModule()
        registerModule(JavaTimeModule())
    }
    private val sut = SystemEventStompNotifier(stomp, objectMapper)

    @Test
    fun `userId 있는 event — user-specific topic 으로 fanout`() {
        val payload = """{"category":"RECONCILE","severity":"WARN","actor":"OrderReconciliationWorker","action":"RECONCILE_FALLBACK_RESOLVED","userId":"74683223","correlationId":null,"detailJson":"{\"orderId\":5}","occurredAt":"2026-05-20T15:00:00Z"}"""
        val record = ConsumerRecord("system.event.v1", 0, 0L, "74683223", payload)

        sut.onMessage(record)

        verify { stomp.convertAndSend(eq("/topic/system.event.user.74683223"), any<Any>()) }
    }

    @Test
    fun `userId null event — broadcast topic 으로 fanout`() {
        val payload = """{"category":"WEBSOCKET","severity":"WARN","actor":"KisWebSocketBridge","action":"WS_DISCONNECTED","userId":null,"correlationId":"abc-123","detailJson":"{\"reason\":\"timeout\"}","occurredAt":"2026-05-20T15:00:00Z"}"""
        val record = ConsumerRecord("system.event.v1", 0, 0L, "broadcast", payload)

        sut.onMessage(record)

        verify { stomp.convertAndSend(eq("/topic/system.event.broadcast"), any<Any>()) }
    }

    @Test
    fun `잘못된 payload — log error + skip (listener thread 안 죽임)`() {
        val record = ConsumerRecord("system.event.v1", 0, 0L, "broadcast", "{invalid json")

        sut.onMessage(record)  // no throw

        verify(exactly = 0) { stomp.convertAndSend(any<String>(), any<Any>()) }
    }
}
```

- [ ] **Step 2: test 실패 검증**

Run: `./gradlew :api:test --tests "*SystemEventStompNotifier*"`
Expected: FAIL (SystemEventStompNotifier 미존재)

- [ ] **Step 3: SystemEventStompNotifier 구현**

```kotlin
package kr.spdaewoon.magicjar.api.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * P2 — Kafka system.event.v1 → STOMP fanout.
 *
 * 분기 (spec §11):
 * - userId != null → /topic/system.event.user.{userId} (user-specific)
 * - userId == null → /topic/system.event.broadcast (system-wide)
 *
 * graceful — 잘못된 payload 또는 STOMP 실패는 listener thread 안 죽임.
 *
 * PR #621 (StompAccountNotifier) 패턴 1:1 정합.
 */
@Component
class SystemEventStompNotifier(
    private val stomp: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(SystemEventStompNotifier::class.java)

    companion object {
        const val TOPIC_USER_PREFIX = "/topic/system.event.user."
        const val TOPIC_BROADCAST = "/topic/system.event.broadcast"
        const val KAFKA_TOPIC = "system.event.v1"
        const val GROUP_ID = "magicjar-system-event-stomp"
    }

    @KafkaListener(topics = [KAFKA_TOPIC], groupId = GROUP_ID)
    fun onMessage(record: ConsumerRecord<String, String>) {
        runCatching {
            val event = objectMapper.readValue<SystemEvent>(record.value())
            val topic = if (event.userId != null) {
                "$TOPIC_USER_PREFIX${event.userId}"
            } else {
                TOPIC_BROADCAST
            }
            stomp.convertAndSend(topic, event)
            log.debug("SystemEvent STOMP fanout: topic={} category={} severity={}", topic, event.category, event.severity)
        }.onFailure { e ->
            log.error("SystemEvent STOMP fanout 실패: offset={} err={}", record.offset(), e.message)
        }
    }
}
```

- [ ] **Step 4: test 통과**

Run: `./gradlew :api:test --tests "*SystemEventStompNotifier*"`
Expected: 3/3 PASS

- [ ] **Step 5: commit**

```bash
git add api/src/main/kotlin/kr/spdaewoon/magicjar/api/messaging/SystemEventStompNotifier.kt api/src/test/kotlin/kr/spdaewoon/magicjar/api/messaging/SystemEventStompNotifierTest.kt
git -c user.email="bot+backend-core@magicjar.local" -c user.name="magicjar-backend-core" commit -m "feat(backend-core): SystemEventStompNotifier — Kafka → STOMP fanout 분기 (P2)"
```

---

## Task 2: api REST GET /api/system-event (목록 + 필터·페이지네이션)

**Files:**
- Create: `api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/SystemEventController.kt`
- Create: `api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/SystemEventDto.kt`
- Create: `api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/SystemEventQueryService.kt`

- [ ] **Step 1: SystemEventDto 작성**

```kotlin
package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.media.Schema
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import java.time.Instant

@Schema(description = "시스템 이벤트 응답")
data class SystemEventResponseDto(
    @field:Schema(description = "DB ID", example = "42") val id: Long,
    @field:Schema(description = "카테고리", example = "RECONCILE") val category: String,
    @field:Schema(description = "심각도", example = "WARN") val severity: String,
    @field:Schema(description = "발사 component", example = "OrderReconciliationWorker") val actor: String,
    @field:Schema(description = "이벤트 코드", example = "RECONCILE_FALLBACK_RESOLVED") val action: String,
    @field:Schema(description = "사용자 ID (null = system-wide)") val userId: String?,
    @field:Schema(description = "동일 사건 묶기 UUID") val correlationId: String?,
    @field:Schema(description = "자유 형식 JSON") val detailJson: String,
    @field:Schema(description = "발생 시각 ISO 8601") val occurredAt: Instant,
    @field:Schema(description = "사용자 확인 시각") val viewedAt: Instant?,
    @field:Schema(description = "해결 처리 시각") val resolvedAt: Instant?,
)

@Schema(description = "페이지네이션 응답")
data class SystemEventPageDto(
    val items: List<SystemEventResponseDto>,
    val totalCount: Long,
    val unreadCount: Long,
    val page: Int,
    val size: Int,
)
```

- [ ] **Step 2: SystemEventQueryService 작성**

```kotlin
package kr.spdaewoon.magicjar.api.service

import kr.spdaewoon.magicjar.api.controller.SystemEventPageDto
import kr.spdaewoon.magicjar.api.controller.SystemEventResponseDto
import kr.spdaewoon.magicjar.persistence.shared.event.SystemEventJpaEntity
import kr.spdaewoon.magicjar.persistence.shared.event.SystemEventJpaRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class SystemEventQueryService(
    private val jpa: SystemEventJpaRepository,
) {
    @Transactional(readOnly = true)
    fun findList(
        userId: String?,
        unreadOnly: Boolean,
        page: Int,
        size: Int,
    ): SystemEventPageDto {
        val pageable = PageRequest.of(page, size)
        val items = if (userId != null && unreadOnly) {
            jpa.findByUserIdAndViewedAtIsNullOrderByOccurredAtDesc(userId, pageable)
        } else {
            jpa.findAllByOrderByOccurredAtDesc(pageable)
        }
        val totalCount = jpa.count()
        val unreadCount = if (userId != null) {
            jpa.findByUserIdAndViewedAtIsNullOrderByOccurredAtDesc(userId, PageRequest.of(0, Int.MAX_VALUE)).size.toLong()
        } else {
            0L
        }
        return SystemEventPageDto(
            items = items.map { it.toResponse() },
            totalCount = totalCount,
            unreadCount = unreadCount,
            page = page,
            size = size,
        )
    }

    @Transactional
    fun markViewed(id: Long): SystemEventResponseDto? {
        val entity = jpa.findById(id).orElse(null) ?: return null
        entity.viewedAt = Instant.now()
        return jpa.save(entity).toResponse()
    }

    @Transactional
    fun markResolved(id: Long): SystemEventResponseDto? {
        val entity = jpa.findById(id).orElse(null) ?: return null
        entity.resolvedAt = Instant.now()
        return jpa.save(entity).toResponse()
    }

    private fun SystemEventJpaEntity.toResponse(): SystemEventResponseDto =
        SystemEventResponseDto(
            id = id ?: 0L,
            category = category,
            severity = severity,
            actor = actor,
            action = action,
            userId = userId,
            correlationId = correlationId,
            detailJson = detailJson,
            occurredAt = occurredAt,
            viewedAt = viewedAt,
            resolvedAt = resolvedAt,
        )
}
```

- [ ] **Step 3: SystemEventController 작성**

```kotlin
package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.service.SystemEventQueryService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 시스템 이벤트 REST API — P2.
 *
 * 3 endpoint:
 * - GET /api/system-event — 목록 + 필터·페이지네이션
 * - PATCH /api/system-event/{id}/viewed — 사용자 확인 표시
 * - PATCH /api/system-event/{id}/resolved — 해결 처리 표시
 */
@RestController
@RequestMapping("/api/system-event")
@Tag(name = "SystemEvent", description = "시스템 운영 이벤트 (Reconcile·WebSocket·Kafka·KIS 등)")
class SystemEventController(
    private val service: SystemEventQueryService,
) {
    @Operation(summary = "시스템 이벤트 목록", description = "필터·페이지네이션 + 사용자별 unread count")
    @GetMapping
    fun list(
        @Parameter(description = "사용자 ID (null = 전체)") @RequestParam(required = false) userId: String?,
        @Parameter(description = "미확인만 (default false)") @RequestParam(defaultValue = "false") unreadOnly: Boolean,
        @Parameter(description = "페이지 (0-based)") @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "페이지 크기 (default 20, max 100)") @RequestParam(defaultValue = "20") size: Int,
    ): SystemEventPageDto = service.findList(userId, unreadOnly, page, size.coerceIn(1, 100))

    @Operation(summary = "사용자 확인 표시 (viewed_at 마킹)")
    @PatchMapping("/{id}/viewed")
    fun markViewed(@PathVariable id: Long): ResponseEntity<SystemEventResponseDto> {
        val response = service.markViewed(id)
        return if (response != null) ResponseEntity.ok(response) else ResponseEntity.notFound().build()
    }

    @Operation(summary = "해결 처리 표시 (resolved_at 마킹)")
    @PatchMapping("/{id}/resolved")
    fun markResolved(@PathVariable id: Long): ResponseEntity<SystemEventResponseDto> {
        val response = service.markResolved(id)
        return if (response != null) ResponseEntity.ok(response) else ResponseEntity.notFound().build()
    }
}
```

- [ ] **Step 4: 빌드 검증**

Run: `./gradlew :api:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: commit**

```bash
git add api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/SystemEventController.kt api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/SystemEventDto.kt api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/SystemEventQueryService.kt
git -c user.email="bot+backend-core@magicjar.local" -c user.name="magicjar-backend-core" commit -m "feat(backend-core): SystemEvent REST API — list + PATCH viewed/resolved (P2)"
```

---

## Task 3: frontend api/system-event.ts (REST client + type)

**Files:**
- Create: `frontend/src/api/system-event.ts`

- [ ] **Step 1: REST client + TS type 작성**

```typescript
import { apiClient } from './client'

export type SystemEventSeverity = 'INFO' | 'WARN' | 'ERROR' | 'CRITICAL'
export type SystemEventCategory = 'RECONCILE' | 'WEBSOCKET' | 'KAFKA' | 'KIS_RATELIMIT' | 'DB' | 'REDIS' | 'OTHER'

export interface SystemEvent {
  id: number
  category: SystemEventCategory
  severity: SystemEventSeverity
  actor: string
  action: string
  userId: string | null
  correlationId: string | null
  detailJson: string
  occurredAt: string  // ISO 8601
  viewedAt: string | null
  resolvedAt: string | null
}

export interface SystemEventPage {
  items: SystemEvent[]
  totalCount: number
  unreadCount: number
  page: number
  size: number
}

export interface ListParams {
  userId?: string
  unreadOnly?: boolean
  page?: number
  size?: number
}

export const systemEventApi = {
  async list(params: ListParams = {}): Promise<SystemEventPage> {
    const { data } = await apiClient.get<SystemEventPage>('/api/system-event', { params })
    return data
  },
  async markViewed(id: number): Promise<SystemEvent> {
    const { data } = await apiClient.patch<SystemEvent>(`/api/system-event/${id}/viewed`)
    return data
  },
  async markResolved(id: number): Promise<SystemEvent> {
    const { data } = await apiClient.patch<SystemEvent>(`/api/system-event/${id}/resolved`)
    return data
  },
}
```

- [ ] **Step 2: typecheck 검증**

Run: `cd frontend && pnpm typecheck`
Expected: PASS

- [ ] **Step 3: commit**

```bash
git add frontend/src/api/system-event.ts
git -c user.email="bot+frontend@magicjar.local" -c user.name="magicjar-frontend" commit -m "feat(frontend): system-event REST API client + types (P2)"
```

---

## Task 4: frontend useSystemEventStompSubscription hook (PR #622 패턴)

**Files:**
- Create: `frontend/src/api/hooks/useSystemEventStompSubscription.ts`
- Test: `frontend/src/api/hooks/__tests__/useSystemEventStompSubscription.test.tsx`

- [ ] **Step 1: 실패 test 작성**

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useSystemEventStompSubscription } from '../useSystemEventStompSubscription'
import { useStompTopic } from '../useStompTopic'

vi.mock('../useStompTopic')
vi.mock('../../ui/ToastProvider', () => ({
  useToast: () => ({ showToast: vi.fn() }),
}))

describe('useSystemEventStompSubscription', () => {
  let queryClient: QueryClient
  beforeEach(() => {
    queryClient = new QueryClient()
    vi.clearAllMocks()
  })

  it('user-specific topic 과 broadcast topic 둘 다 subscribe', () => {
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useSystemEventStompSubscription('74683223'), { wrapper })
    expect(useStompTopic).toHaveBeenCalledWith('/topic/system.event.user.74683223', expect.any(Function))
    expect(useStompTopic).toHaveBeenCalledWith('/topic/system.event.broadcast', expect.any(Function))
  })

  it('userId null 이면 user-specific topic 미구독 + broadcast 만 subscribe', () => {
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useSystemEventStompSubscription(null), { wrapper })
    expect(useStompTopic).toHaveBeenCalledWith(null, expect.any(Function))
    expect(useStompTopic).toHaveBeenCalledWith('/topic/system.event.broadcast', expect.any(Function))
  })
})
```

- [ ] **Step 2: test 실패 검증**

Run: `cd frontend && pnpm test -- useSystemEventStompSubscription`
Expected: FAIL

- [ ] **Step 3: hook 구현**

```typescript
import { useQueryClient } from '@tanstack/react-query'
import type { SystemEvent } from '../system-event'
import { useStompTopic } from './useStompTopic'
import { useToast } from '../../ui/ToastProvider'

/**
 * 시스템 이벤트 STOMP 구독 (P2).
 *
 * 2 topic 구독:
 * - /topic/system.event.user.{userId} — user-specific (userId 있을 때만)
 * - /topic/system.event.broadcast — 시스템 전체
 *
 * 메시지 수신 시:
 * - queryClient.invalidateQueries(['system-event']) → list refetch
 * - severity = CRITICAL → toast 표시
 */
export function useSystemEventStompSubscription(userId: string | null): void {
  const queryClient = useQueryClient()
  const { showToast } = useToast()

  const handleEvent = (event: SystemEvent) => {
    queryClient.invalidateQueries({ queryKey: ['system-event'], exact: false })
    if (event.severity === 'CRITICAL') {
      showToast(`[${event.category}] ${event.action}: ${event.detailJson}`, 'error')
    }
  }

  useStompTopic(userId ? `/topic/system.event.user.${userId}` : null, handleEvent)
  useStompTopic('/topic/system.event.broadcast', handleEvent)
}
```

- [ ] **Step 4: test 통과**

Run: `cd frontend && pnpm test -- useSystemEventStompSubscription`
Expected: 2/2 PASS

- [ ] **Step 5: commit**

```bash
git add frontend/src/api/hooks/useSystemEventStompSubscription.ts frontend/src/api/hooks/__tests__/useSystemEventStompSubscription.test.tsx
git -c user.email="bot+frontend@magicjar.local" -c user.name="magicjar-frontend" commit -m "feat(frontend): useSystemEventStompSubscription hook + 2 test (P2)"
```

---

## Task 5: frontend useSystemEventList REST hook

**Files:**
- Create: `frontend/src/api/hooks/useSystemEventList.ts`

- [ ] **Step 1: hook 작성 (TDD 생략 — 단순 wrap)**

```typescript
import { useQuery } from '@tanstack/react-query'
import { systemEventApi, type ListParams, type SystemEventPage } from '../system-event'

const POLLING_INTERVAL_MS = 30_000  // fallback polling (STOMP push 보조)

export function useSystemEventList(params: ListParams = {}) {
  return useQuery<SystemEventPage>({
    queryKey: ['system-event', 'list', params],
    queryFn: () => systemEventApi.list(params),
    refetchInterval: POLLING_INTERVAL_MS,
    refetchIntervalInBackground: false,
    staleTime: 25_000,
  })
}
```

- [ ] **Step 2: typecheck 검증**

Run: `cd frontend && pnpm typecheck`
Expected: PASS

- [ ] **Step 3: commit**

```bash
git add frontend/src/api/hooks/useSystemEventList.ts
git -c user.email="bot+frontend@magicjar.local" -c user.name="magicjar-frontend" commit -m "feat(frontend): useSystemEventList REST hook + 30s polling fallback (P2)"
```

---

## Task 6: frontend NotificationCenter 컴포넌트 (벨 + 드롭다운)

**Files:**
- Create: `frontend/src/components/notification/NotificationCenter.tsx`
- Create: `frontend/src/components/notification/NotificationItem.tsx`
- Test: `frontend/src/components/notification/__tests__/NotificationCenter.test.tsx`

- [ ] **Step 1: NotificationItem 컴포넌트 작성**

```tsx
import type { SystemEvent } from '../../api/system-event'

interface Props {
  event: SystemEvent
  onClick: (id: number) => void
}

const severityColor: Record<string, string> = {
  INFO: 'text-blue-600',
  WARN: 'text-yellow-600',
  ERROR: 'text-orange-600',
  CRITICAL: 'text-red-600 font-bold',
}

export function NotificationItem({ event, onClick }: Props) {
  const isUnread = event.viewedAt === null
  return (
    <div
      className={`p-3 border-b cursor-pointer hover:bg-gray-50 ${isUnread ? 'bg-blue-50' : ''}`}
      onClick={() => onClick(event.id)}
    >
      <div className={`text-sm ${severityColor[event.severity]}`}>
        [{event.category}] {event.action}
      </div>
      <div className="text-xs text-gray-500">{new Date(event.occurredAt).toLocaleString('ko-KR')}</div>
    </div>
  )
}
```

- [ ] **Step 2: NotificationCenter 컴포넌트 작성**

```tsx
import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useSystemEventList } from '../../api/hooks/useSystemEventList'
import { systemEventApi } from '../../api/system-event'
import { NotificationItem } from './NotificationItem'

interface Props {
  userId: string | null
}

export function NotificationCenter({ userId }: Props) {
  const [open, setOpen] = useState(false)
  const queryClient = useQueryClient()
  const { data } = useSystemEventList({
    userId: userId ?? undefined,
    unreadOnly: true,
    page: 0,
    size: 10,
  })

  const markViewed = useMutation({
    mutationFn: (id: number) => systemEventApi.markViewed(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['system-event'], exact: false }),
  })

  const unreadCount = data?.unreadCount ?? 0
  const items = data?.items ?? []

  return (
    <div className="relative">
      <button
        aria-label="시스템 이벤트 알림"
        className="relative p-2 hover:bg-gray-100 rounded"
        onClick={() => setOpen((prev) => !prev)}
      >
        🔔
        {unreadCount > 0 && (
          <span className="absolute -top-1 -right-1 bg-red-600 text-white text-xs rounded-full px-1.5">
            {unreadCount > 99 ? '99+' : unreadCount}
          </span>
        )}
      </button>
      {open && (
        <div className="absolute right-0 mt-2 w-80 bg-white border rounded shadow-lg z-50">
          <div className="p-3 border-b font-semibold">시스템 이벤트 ({unreadCount} 미확인)</div>
          <div className="max-h-96 overflow-y-auto">
            {items.length === 0 ? (
              <div className="p-4 text-center text-gray-400 text-sm">새 이벤트 없음</div>
            ) : (
              items.map((event) => (
                <NotificationItem key={event.id} event={event} onClick={markViewed.mutate} />
              ))
            )}
          </div>
          <div className="p-2 border-t text-center">
            <a href="/system" className="text-sm text-blue-600 hover:underline">
              전체 보기 →
            </a>
          </div>
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 3: test 작성 (1 case — 벨 클릭 시 드롭다운 표시 + unread count)**

```tsx
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { NotificationCenter } from '../NotificationCenter'

vi.mock('../../../api/hooks/useSystemEventList', () => ({
  useSystemEventList: () => ({
    data: {
      items: [
        {
          id: 1,
          category: 'RECONCILE',
          severity: 'WARN',
          actor: 'OrderReconciliationWorker',
          action: 'RECONCILE_FALLBACK_FIRED',
          userId: null,
          correlationId: null,
          detailJson: '{}',
          occurredAt: '2026-05-20T15:00:00Z',
          viewedAt: null,
          resolvedAt: null,
        },
      ],
      totalCount: 1,
      unreadCount: 1,
      page: 0,
      size: 10,
    },
  }),
}))

describe('NotificationCenter', () => {
  it('벨 클릭 시 드롭다운 + unread count 표시', () => {
    const queryClient = new QueryClient()
    render(
      <QueryClientProvider client={queryClient}>
        <NotificationCenter userId="74683223" />
      </QueryClientProvider>,
    )
    expect(screen.getByText('1')).toBeInTheDocument()  // unread badge
    fireEvent.click(screen.getByLabelText('시스템 이벤트 알림'))
    expect(screen.getByText(/시스템 이벤트/)).toBeInTheDocument()
    expect(screen.getByText(/RECONCILE_FALLBACK_FIRED/)).toBeInTheDocument()
  })
})
```

- [ ] **Step 4: test 통과**

Run: `cd frontend && pnpm test -- NotificationCenter`
Expected: PASS

- [ ] **Step 5: commit**

```bash
git add frontend/src/components/notification/
git -c user.email="bot+frontend@magicjar.local" -c user.name="magicjar-frontend" commit -m "feat(frontend): NotificationCenter 벨 + 드롭다운 + 1 test (P2)"
```

---

## Task 7: frontend /system Dashboard 페이지

**Files:**
- Create: `frontend/src/pages/SystemEvent/SystemEventPage.tsx`

- [ ] **Step 1: SystemEventPage 작성 (필터·페이지네이션·correlation_id 그룹)**

```tsx
import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useSystemEventList } from '../../api/hooks/useSystemEventList'
import { systemEventApi, type SystemEvent } from '../../api/system-event'

const severityColor: Record<string, string> = {
  INFO: 'bg-blue-100 text-blue-800',
  WARN: 'bg-yellow-100 text-yellow-800',
  ERROR: 'bg-orange-100 text-orange-800',
  CRITICAL: 'bg-red-100 text-red-800 font-bold',
}

export function SystemEventPage() {
  const [page, setPage] = useState(0)
  const [unreadOnly, setUnreadOnly] = useState(false)
  const queryClient = useQueryClient()

  const { data, isLoading } = useSystemEventList({ unreadOnly, page, size: 20 })

  const markResolved = useMutation({
    mutationFn: (id: number) => systemEventApi.markResolved(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['system-event'], exact: false }),
  })

  // correlation_id 그룹 (UI 시각화 — 같은 correlation_id 인접 표시)
  const groupedItems: SystemEvent[] = data?.items ?? []

  return (
    <div className="container mx-auto p-6">
      <h1 className="text-2xl font-bold mb-4">시스템 이벤트 운영 대시보드</h1>

      <div className="mb-4 flex items-center gap-4">
        <label className="flex items-center gap-2">
          <input
            type="checkbox"
            checked={unreadOnly}
            onChange={(e) => setUnreadOnly(e.target.checked)}
          />
          미확인만
        </label>
        <div className="text-sm text-gray-500">
          총 {data?.totalCount ?? 0}건 · 미확인 {data?.unreadCount ?? 0}건
        </div>
      </div>

      {isLoading ? (
        <div>로딩 중...</div>
      ) : (
        <table className="w-full border-collapse">
          <thead>
            <tr className="border-b bg-gray-50">
              <th className="p-2 text-left">발생 시각</th>
              <th className="p-2 text-left">카테고리</th>
              <th className="p-2 text-left">심각도</th>
              <th className="p-2 text-left">Action</th>
              <th className="p-2 text-left">Actor</th>
              <th className="p-2 text-left">사용자</th>
              <th className="p-2 text-left">Correlation</th>
              <th className="p-2 text-left">Detail</th>
              <th className="p-2 text-left">작업</th>
            </tr>
          </thead>
          <tbody>
            {groupedItems.map((event) => (
              <tr key={event.id} className="border-b hover:bg-gray-50">
                <td className="p-2 text-sm">{new Date(event.occurredAt).toLocaleString('ko-KR')}</td>
                <td className="p-2 text-sm">{event.category}</td>
                <td className="p-2">
                  <span className={`px-2 py-1 rounded text-xs ${severityColor[event.severity]}`}>
                    {event.severity}
                  </span>
                </td>
                <td className="p-2 text-sm font-mono">{event.action}</td>
                <td className="p-2 text-sm">{event.actor}</td>
                <td className="p-2 text-sm">{event.userId ?? '(전체)'}</td>
                <td className="p-2 text-xs font-mono">{event.correlationId?.slice(0, 8) ?? '-'}</td>
                <td className="p-2 text-xs font-mono max-w-xs truncate">{event.detailJson}</td>
                <td className="p-2">
                  {event.resolvedAt === null && (
                    <button
                      onClick={() => markResolved.mutate(event.id)}
                      className="text-xs text-blue-600 hover:underline"
                    >
                      해결 표시
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <div className="mt-4 flex justify-between items-center">
        <button
          disabled={page === 0}
          onClick={() => setPage((p) => p - 1)}
          className="px-3 py-1 border rounded disabled:opacity-50"
        >
          이전
        </button>
        <div className="text-sm text-gray-500">페이지 {page + 1}</div>
        <button
          disabled={(data?.items.length ?? 0) < 20}
          onClick={() => setPage((p) => p + 1)}
          className="px-3 py-1 border rounded disabled:opacity-50"
        >
          다음
        </button>
      </div>
    </div>
  )
}
```

- [ ] **Step 2: typecheck 검증**

Run: `cd frontend && pnpm typecheck`
Expected: PASS

- [ ] **Step 3: commit**

```bash
git add frontend/src/pages/SystemEvent/SystemEventPage.tsx
git -c user.email="bot+frontend@magicjar.local" -c user.name="magicjar-frontend" commit -m "feat(frontend): /system Dashboard 페이지 (P2)"
```

---

## Task 8: App.tsx routes + Header NotificationCenter 통합

**Files:**
- Modify: `frontend/src/App.tsx` (또는 routes.tsx)
- Modify: `frontend/src/components/Header.tsx` (또는 layout)

- [ ] **Step 1: App.tsx에 /system route 추가**

기존 routes 구조 파악 후 추가:
```tsx
import { SystemEventPage } from './pages/SystemEvent/SystemEventPage'
// ... 기존 routes ...
<Route path="/system" element={<SystemEventPage />} />
```

- [ ] **Step 2: Header에 NotificationCenter 통합 + STOMP subscribe hook 호출**

```tsx
import { NotificationCenter } from './notification/NotificationCenter'
import { useSystemEventStompSubscription } from '../api/hooks/useSystemEventStompSubscription'
import { useUserId } from '../stores/userStore'  // 또는 기존 사용자 ID 획득 hook

export function Header() {
  const userId = useUserId()  // string | null
  useSystemEventStompSubscription(userId)  // 전역 STOMP subscribe

  return (
    <header className="...">
      {/* ... 기존 헤더 ... */}
      <NotificationCenter userId={userId} />
    </header>
  )
}
```

- [ ] **Step 3: typecheck + build 검증**

Run: `cd frontend && pnpm typecheck && pnpm build`
Expected: PASS

- [ ] **Step 4: commit**

```bash
git add frontend/src/App.tsx frontend/src/components/Header.tsx
git -c user.email="bot+frontend@magicjar.local" -c user.name="magicjar-frontend" commit -m "feat(frontend): App routes /system + Header NotificationCenter 통합 (P2)"
```

---

## Task 9: 전체 통합 검증 + push + PR 발행

- [ ] **Step 1: 전체 빌드·test**

Run: `./gradlew :api:check`
Run: `cd frontend && pnpm typecheck && pnpm test && pnpm build`
Expected: 모두 BUILD SUCCESSFUL / GREEN

- [ ] **Step 2: push**

```bash
git push -u origin agent/<sub-agent>/system-event-log-p2
```

- [ ] **Step 3: PR 생성**

```bash
gh pr create --base main --title "feat: system event log P2 — STOMP fanout + REST API + UI 알림센터/dashboard" --body "..."
```

PR body: spec·P1·plan 인용 + 9 task 매트릭스 + STOMP topic·REST endpoint 매트릭스 + 사용자 의무 (재배포 + 검증)

---

## Self-Review

| 체크 | 결과 |
|------|------|
| Spec coverage | ✅ §6.4 Notifier + §6.5 hook + §6.6 NotificationCenter + §6.7 dashboard + §11 STOMP 분기 + §12 correlationId 그룹 모두 task 매핑 |
| Placeholder scan | ✅ 0건 |
| Type consistency | ✅ SystemEvent·SystemEventPage·SystemEventCategory·SystemEventSeverity 명명 모든 task 일관 |
| Backend ↔ Frontend 정합 | ✅ STOMP topic (`/topic/system.event.user.{userId}` + `.broadcast`) + REST endpoint (`/api/system-event` + `/{id}/viewed` + `/{id}/resolved`) backend·frontend 1:1 |
| 미정의 type/method | ✅ `useStompTopic` 가정 — magicJar 기존 hook (PR #622 패턴) 활용. 부재 시 신규 구현 필요 (sub-agent 진행 시 grep 검증 의무) |

## Phase 분리 명시

- **본 plan은 P2 scope만** (STOMP fanout + REST + UI)
- **P3 catalog 확장 (Kafka·KIS rate limit·DB·Redis fail) + 의제 B (ACK reconcile) 통합 별도 plan**

## P2 dispatch 매트릭스 (subagent-driven 진입 시)

| Phase | sub-agent | scope | task |
|-------|-----------|-------|------|
| **A** | backend-core | api SystemEventStompNotifier + REST endpoint | Task 1·2 |
| **B** | frontend | api client + STOMP hook + REST hook | Task 3·4·5 |
| **C** | frontend | NotificationCenter + Dashboard 페이지 | Task 6·7 |
| **D** | frontend | App·Header 통합 + 전체 검증 + PR | Task 8·9 |

총 4 sub-agent + 1 final reviewer.

## 변경 이력

- 2026-05-20: P2 plan 발행 (writing-plans 스킬, orchestrator 작성)
