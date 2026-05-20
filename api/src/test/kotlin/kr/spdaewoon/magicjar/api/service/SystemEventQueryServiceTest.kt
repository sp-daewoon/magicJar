package kr.spdaewoon.magicjar.api.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.persistence.shared.event.SystemEventJpaEntity
import kr.spdaewoon.magicjar.persistence.shared.event.SystemEventJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Pageable
import java.time.Instant

/**
 * P2.1 — final reviewer ★★ #2 fix 검증.
 *
 * 4 filter case 매트릭스 정합 (SystemEventQueryService.findList):
 *
 * | userId | unreadOnly | items 호출                                                | totalCount 호출                    | unreadCount 호출                       |
 * |--------|------------|-----------------------------------------------------------|------------------------------------|----------------------------------------|
 * | Y      | true       | findByUserIdAndViewedAtIsNullOrderByOccurredAtDesc        | countByUserIdAndViewedAtIsNull     | countByUserIdAndViewedAtIsNull         |
 * | Y      | false      | findByUserIdOrderByOccurredAtDesc                         | countByUserId                      | countByUserIdAndViewedAtIsNull         |
 * | N      | true       | findByViewedAtIsNullOrderByOccurredAtDesc                 | countByViewedAtIsNull              | countByViewedAtIsNull                  |
 * | N      | false      | findAllByOrderByOccurredAtDesc                            | jpa.count()                        | countByViewedAtIsNull                  |
 *
 * 기존 결함:
 * - userId null + unreadOnly=true → 전체 반환 (filter 무시)
 * - totalCount 가 항상 전체 count (filter 무시)
 * - userId null 시 unreadCount=0 (frontend dashboard 미확인 표시 정확성 손실)
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
class SystemEventQueryServiceTest {
    private val jpa = mockk<SystemEventJpaRepository>(relaxed = true)
    private val sut = SystemEventQueryService(jpa)

    private fun entity(id: Long): SystemEventJpaEntity =
        SystemEventJpaEntity(
            id = id,
            category = "RECONCILE",
            severity = "WARN",
            actor = "Test",
            action = "TEST_ACTION",
            userId = "74683223",
            correlationId = null,
            detailJson = "{}",
            occurredAt = Instant.parse("2026-05-20T15:00:00Z"),
        )

    @Test
    fun `userId 있고 unreadOnly=true — 사용자 unread 만 반환 + totalCount = unreadCount`() {
        val items = listOf(entity(1L), entity(2L))
        every {
            jpa.findByUserIdAndViewedAtIsNullOrderByOccurredAtDesc("74683223", any())
        } returns items
        every { jpa.countByUserIdAndViewedAtIsNull("74683223") } returns 2L

        val result = sut.findList(userId = "74683223", unreadOnly = true, page = 0, size = 20)

        assertThat(result.items).hasSize(2)
        assertThat(result.totalCount).isEqualTo(2L)
        assertThat(result.unreadCount).isEqualTo(2L)
        verify(exactly = 1) {
            jpa.findByUserIdAndViewedAtIsNullOrderByOccurredAtDesc("74683223", any<Pageable>())
        }
        verify(exactly = 0) { jpa.findAllByOrderByOccurredAtDesc(any()) }
        verify(exactly = 0) { jpa.count() }
    }

    @Test
    fun `userId 있고 unreadOnly=false — 사용자 전체 + totalCount=user_count + unreadCount=user_unread`() {
        val items = listOf(entity(1L), entity(2L), entity(3L))
        every { jpa.findByUserIdOrderByOccurredAtDesc("74683223", any()) } returns items
        every { jpa.countByUserId("74683223") } returns 10L
        every { jpa.countByUserIdAndViewedAtIsNull("74683223") } returns 3L

        val result = sut.findList(userId = "74683223", unreadOnly = false, page = 0, size = 20)

        assertThat(result.items).hasSize(3)
        assertThat(result.totalCount).isEqualTo(10L)
        assertThat(result.unreadCount).isEqualTo(3L)
        verify(exactly = 1) { jpa.findByUserIdOrderByOccurredAtDesc("74683223", any<Pageable>()) }
        verify(exactly = 1) { jpa.countByUserId("74683223") }
        verify(exactly = 1) { jpa.countByUserIdAndViewedAtIsNull("74683223") }
        verify(exactly = 0) { jpa.findAllByOrderByOccurredAtDesc(any()) }
        verify(exactly = 0) { jpa.count() }
    }

    @Test
    fun `P2-1 ★★ #2 — userId null 이고 unreadOnly=true — 전체 unread 만 + filter 무시 결함 fix`() {
        val items = listOf(entity(1L))
        every { jpa.findByViewedAtIsNullOrderByOccurredAtDesc(any()) } returns items
        every { jpa.countByViewedAtIsNull() } returns 5L

        val result = sut.findList(userId = null, unreadOnly = true, page = 0, size = 20)

        assertThat(result.items).hasSize(1)
        assertThat(result.totalCount).isEqualTo(5L)
        assertThat(result.unreadCount).isEqualTo(5L)
        verify(exactly = 1) { jpa.findByViewedAtIsNullOrderByOccurredAtDesc(any<Pageable>()) }
        verify(exactly = 1) { jpa.countByViewedAtIsNull() }
        // 결함 fix 증거 — 기존엔 findAllByOrderByOccurredAtDesc 호출 + jpa.count() 호출
        verify(exactly = 0) { jpa.findAllByOrderByOccurredAtDesc(any()) }
        verify(exactly = 0) { jpa.count() }
    }

    @Test
    fun `P2-1 ★★ #2 — userId null 이고 unreadOnly=false — 전체 + totalCount=jpa_count + unreadCount=countByViewedAtIsNull`() {
        val items = listOf(entity(1L), entity(2L))
        every { jpa.findAllByOrderByOccurredAtDesc(any()) } returns items
        every { jpa.count() } returns 100L
        every { jpa.countByViewedAtIsNull() } returns 7L

        val result = sut.findList(userId = null, unreadOnly = false, page = 0, size = 20)

        assertThat(result.items).hasSize(2)
        assertThat(result.totalCount).isEqualTo(100L)
        // 결함 fix 증거 — 기존엔 userId null 시 unreadCount=0L hardcoded
        assertThat(result.unreadCount).isEqualTo(7L)
        verify(exactly = 1) { jpa.findAllByOrderByOccurredAtDesc(any<Pageable>()) }
        verify(exactly = 1) { jpa.count() }
        verify(exactly = 1) { jpa.countByViewedAtIsNull() }
    }
}
