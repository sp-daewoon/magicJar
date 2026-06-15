package kr.spdaewoon.magicjar.domain.redis

import io.kotest.matchers.shouldBe
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.junit.jupiter.api.Test

class RedisKeyRegistryTest {
    @Test
    fun `current account and kis keys preserve production wire format`() {
        RedisKeyRegistry.Account.balance("74683223", AccountType.REAL) shouldBe "account:balance:74683223:REAL"
        RedisKeyRegistry.Account.balanceLock("74683223", AccountType.REAL) shouldBe "account:balance:lock:74683223:REAL"
        RedisKeyRegistry.Kis.token("74683223", AccountType.MOCK) shouldBe "kis:token:74683223:MOCK"
        RedisKeyRegistry.Kis.tokenLock("74683223", AccountType.MOCK) shouldBe "kis:token:lock:74683223:MOCK"
        RedisKeyRegistry.Kis.approval("74683223", AccountType.REAL) shouldBe "kis:approval:74683223:REAL"
    }

    @Test
    fun `standard keys follow labelled dimension convention`() {
        RedisKeyRegistry.Account.Standard.balance("74683223", "REAL") shouldBe
            "account:balance:user:74683223:account:REAL"
        RedisKeyRegistry.Market.Standard.quoteLast("005930") shouldBe "market:quote:last:code:005930"
        RedisKeyRegistry.Ops.Standard.watchlistSource("74683223") shouldBe "ops:watchlist:source:user:74683223"
    }

    @Test
    fun `no ttl copy migration rules map low risk legacy keys to standard keys`() {
        RedisKeyRegistry.noTtlCopyMigrationRules
            .first { it.name == "instrument.by-code" }
            .targetFor("instrument:by-code:005930") shouldBe "instrument:summary:code:005930"

        RedisKeyRegistry.noTtlCopyMigrationRules
            .first { it.name == "ops.realtime.status" }
            .targetFor("admin:realtime-status") shouldBe "ops:realtime:status"
    }

    @Test
    fun `critical account and order keys are excluded from copy migration rules`() {
        RedisKeyRegistry.noTtlCopyMigrationRules.any { it.targetFor("account:balance:74683223:REAL") != null } shouldBe false
        RedisKeyRegistry.noTtlCopyMigrationRules.any { it.targetFor("idem:order:sig-1:74683223") != null } shouldBe false
        RedisKeyRegistry.noTtlCopyMigrationRules.any { it.targetFor("risk:trade-freq:86:74683223:day") != null } shouldBe false
    }

    @Test
    fun `copy migration rules do not include same-key mappings`() {
        RedisKeyRegistry.noTtlCopyMigrationRules.any {
            it.targetFor("instrument:search:prefix:삼성") == "instrument:search:prefix:삼성"
        } shouldBe false
    }
}
