package com.mj.consumer.infrastructure.kis

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class KisErrorCategoryTest {
    @Test
    fun `401 status maps to AUTH`() {
        KisErrorCategory.classify(401, null) shouldBe KisErrorCategory.AUTH
    }

    @Test
    fun `auth msg_cd EGW00121 maps to AUTH`() {
        KisErrorCategory.classify(200, "EGW00121") shouldBe KisErrorCategory.AUTH
    }

    @Test
    fun `429 maps to RATE_LIMIT`() {
        KisErrorCategory.classify(429, null) shouldBe KisErrorCategory.RATE_LIMIT
    }

    @Test
    fun `5xx maps to SERVER`() {
        KisErrorCategory.classify(503, null) shouldBe KisErrorCategory.SERVER
    }

    @Test
    fun `INVALID_ORDER msg code`() {
        KisErrorCategory.classify(200, "APBK0067") shouldBe KisErrorCategory.INVALID_ORDER
    }

    @Test
    fun `INSUFFICIENT_FUNDS msg code`() {
        KisErrorCategory.classify(200, "APBK0918") shouldBe KisErrorCategory.INSUFFICIENT_FUNDS
    }

    @Test
    fun `unknown returns UNKNOWN`() {
        KisErrorCategory.classify(200, "ZZZZZZ") shouldBe KisErrorCategory.UNKNOWN
    }
}
