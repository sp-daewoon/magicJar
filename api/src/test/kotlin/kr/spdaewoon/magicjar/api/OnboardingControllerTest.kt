package kr.spdaewoon.magicjar.api

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.OnboardingStatus
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.api.service.OnboardingService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * OnboardingController 슬라이스 테스트 (spec §8-1 smoke 1건, 룰 4 정합).
 * DB 없이 @MockBean으로 검증.
 *
 * 검증: 빈 DB 상태 → GET /api/onboarding/status → hasUserAccount=false
 */
@WebMvcTest(
    controllers = [kr.spdaewoon.magicjar.api.controller.OnboardingController::class],
)
class OnboardingControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockBean
    lateinit var onboardingService: OnboardingService

    // OnboardingGuardFilter 의존성 — mockBean
    @MockBean
    lateinit var userAccountJpaRepository: UserAccountJpaRepository

    @Test
    fun `GET status should return hasUserAccount=false when no account registered`() {
        // given: 빈 DB 상태
        whenever(userAccountJpaRepository.existsAny()).thenReturn(false)
        whenever(onboardingService.getStatus()).thenReturn(
            OnboardingStatus(
                hasUserAccount = false,
                hasMockAccount = false,
                hasDartKey = false,
                hasSlackWebhook = false,
            ),
        )

        // when & then
        mockMvc
            .get("/api/onboarding/status")
            .andExpect {
                status { isOk() }
                jsonPath("$.hasUserAccount") { value(false) }
                jsonPath("$.hasMockAccount") { value(false) }
                jsonPath("$.hasDartKey") { value(false) }
                jsonPath("$.hasSlackWebhook") { value(false) }
            }
    }
}
