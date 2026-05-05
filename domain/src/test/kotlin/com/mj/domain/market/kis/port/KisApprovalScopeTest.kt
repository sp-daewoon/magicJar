package com.mj.domain.market.kis.port

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * R3-5c-3 PR1 — KisApprovalScope sealed class 키 정합 회귀.
 */
class KisApprovalScopeTest {
    @Test
    fun `System 시세 WS approval 키 정합`() {
        val scope = KisApprovalScope.System("prod")
        scope.approvalKey() shouldBe "magicjar:kis:approval:system:prod"
        scope.lockKey() shouldBe "magicjar:kis:approval:lock:system:prod"
        scope.identifier() shouldBe "approval:system:prod"
    }

    @Test
    fun `User 체결통보 WS approval 키 정합`() {
        val scope = KisApprovalScope.User(userId = "74683223", mode = "vps")
        scope.approvalKey() shouldBe "magicjar:kis:approval:user:74683223:vps"
        scope.lockKey() shouldBe "magicjar:kis:approval:lock:user:74683223:vps"
        scope.identifier() shouldBe "approval:user:74683223:vps"
    }

    @Test
    fun `KisAuthScope 와 KisApprovalScope 는 같은 식별자 prefix가 다르다`() {
        // ADR 0050 R3-5c-3: token vs approval Redis 키 namespace 분리.
        val token = KisAuthScope.System("prod").tokenKey()
        val approval = KisApprovalScope.System("prod").approvalKey()
        (token == approval) shouldBe false
        token shouldBe "magicjar:kis:token:system:prod"
        approval shouldBe "magicjar:kis:approval:system:prod"
    }
}
