package kr.spdaewoon.magicjar.api.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 셋업 미완 시 /api/* 차단 필터 (ADR 0046 §2-6, spec §4-7-1 LOCK 정합).
 *
 * 인증 정책:
 * - /api/onboarding/* → 통과 (셋업 진행 중 언제나 허용)
 * - /actuator/*       → 통과 (헬스 체크)
 * - /api/ 외 경로     → 통과 (정적 리소스·frontend)
 * - 그 외 /api/*      → user_account 1행 존재 시 통과, 0행이면 401 + ONBOARDING_REQUIRED
 *
 * user 세션 정책 (1차 single-tenant 단순화):
 * - user_account가 1행 이상 존재 = 셋업 완료 = 모든 API 통과
 * - Spring Security·JWT 미도입 (post-MVP 예정 — spec §7 비범위)
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class OnboardingGuardFilter(
    private val userAccountJpaRepository: UserAccountJpaRepository,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        req: HttpServletRequest,
        res: HttpServletResponse,
        chain: FilterChain,
    ) {
        val path = req.requestURI

        // 통과 조건: /api/onboarding/*, /actuator/*, /api/ 외 경로
        if (path.startsWith("/api/onboarding/") ||
            path.startsWith("/actuator/") ||
            !path.startsWith("/api/")
        ) {
            chain.doFilter(req, res)
            return
        }

        // 그 외 /api/* — 셋업 완료 여부 확인
        if (userAccountJpaRepository.existsAny()) {
            chain.doFilter(req, res)
        } else {
            res.status = HttpServletResponse.SC_UNAUTHORIZED
            res.contentType = "application/json;charset=UTF-8"
            res.writer.write(
                """{"code":"ONBOARDING_REQUIRED","message":"셋업 마법사 완료 후 사용 가능합니다"}""",
            )
        }
    }
}
