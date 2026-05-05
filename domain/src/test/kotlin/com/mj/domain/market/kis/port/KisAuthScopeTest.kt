package com.mj.domain.market.kis.port

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * R3-5c-3 PR1 — KisAuthScope sealed class 키 정합 회귀.
 */
class KisAuthScopeTest {
    @Test
    fun `System prod tokenKey 정합`() {
        val scope = KisAuthScope.System("prod")
        scope.tokenKey() shouldBe "magicjar:kis:token:system:prod"
        scope.lockKey() shouldBe "magicjar:kis:token:lock:system:prod"
        scope.identifier() shouldBe "system:prod"
        scope.mode shouldBe "prod"
    }

    @Test
    fun `System vps tokenKey 정합`() {
        val scope = KisAuthScope.System("vps")
        scope.tokenKey() shouldBe "magicjar:kis:token:system:vps"
        scope.lockKey() shouldBe "magicjar:kis:token:lock:system:vps"
        scope.identifier() shouldBe "system:vps"
    }

    @Test
    fun `User vps tokenKey 정합 — V21 String userId`() {
        val scope = KisAuthScope.User(userId = "74683223", mode = "vps")
        scope.tokenKey() shouldBe "magicjar:kis:token:user:74683223:vps"
        scope.lockKey() shouldBe "magicjar:kis:token:lock:user:74683223:vps"
        scope.identifier() shouldBe "user:74683223:vps"
    }

    @Test
    fun `User prod tokenKey 정합`() {
        val scope = KisAuthScope.User(userId = "74683223", mode = "prod")
        scope.tokenKey() shouldBe "magicjar:kis:token:user:74683223:prod"
        scope.lockKey() shouldBe "magicjar:kis:token:lock:user:74683223:prod"
    }

    @Test
    fun `System과 User는 동일 mode라도 다른 lockKey`() {
        KisAuthScope.System("prod").lockKey() shouldBe "magicjar:kis:token:lock:system:prod"
        KisAuthScope.User("74683223", "prod").lockKey() shouldBe
            "magicjar:kis:token:lock:user:74683223:prod"
    }

    @Test
    fun `data class equality — 동일 mode userId면 같은 인스턴스`() {
        (KisAuthScope.System("prod") == KisAuthScope.System("prod")) shouldBe true
        (KisAuthScope.User("74683223", "prod") == KisAuthScope.User("74683223", "prod")) shouldBe true
        (KisAuthScope.System("prod") == KisAuthScope.System("vps")) shouldBe false
    }
}
