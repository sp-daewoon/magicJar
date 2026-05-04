package com.mj.consumer.infrastructure.kis

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.serverError
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class KisWsApprovalProviderTest {
    private lateinit var wm: WireMockServer
    private lateinit var httpSession: KisHttpSession
    private lateinit var provider: KisWsApprovalProvider

    private val fixedClock = Clock.fixed(Instant.parse("2026-05-04T00:00:00Z"), ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        wm = WireMockServer(0).also { it.start() }
        httpSession = KisHttpSession(baseUrl = "http://localhost:${wm.port()}")
        provider =
            KisWsApprovalProvider(
                properties = KisProperties(appKey = "TEST_KEY", appSecret = "TEST_SECRET"),
                httpSession = httpSession,
                clock = fixedClock,
            )
    }

    @AfterEach
    fun tearDown() {
        wm.stop()
    }

    @Test
    fun `getApprovalKey 성공 시 approval_key 반환`() {
        wm.stubFor(
            post(urlEqualTo("/oauth2/Approval"))
                .willReturn(okJson("""{"approval_key":"APPROVAL_KEY_ABCDE"}""")),
        )

        val key = provider.getApprovalKey()

        key shouldBe "APPROVAL_KEY_ABCDE"
    }

    @Test
    fun `getApprovalKey 두 번 호출 시 캐시 히트 — HTTP 1번만 호출`() {
        wm.stubFor(
            post(urlEqualTo("/oauth2/Approval"))
                .willReturn(okJson("""{"approval_key":"CACHED_KEY"}""")),
        )

        val first = provider.getApprovalKey()
        val second = provider.getApprovalKey()

        first shouldBe "CACHED_KEY"
        second shouldBe "CACHED_KEY"
        wm.allServeEvents.size shouldBe 1 // HTTP 1번만
    }

    @Test
    fun `invalidate 후 재발급`() {
        wm.stubFor(
            post(urlEqualTo("/oauth2/Approval"))
                .willReturn(okJson("""{"approval_key":"RENEWED_KEY"}""")),
        )

        provider.getApprovalKey() // 첫 번째 발급
        provider.invalidate() // 캐시 무효화
        val renewed = provider.getApprovalKey() // 재발급

        renewed shouldBe "RENEWED_KEY"
        wm.allServeEvents.size shouldBe 2 // HTTP 2번 호출
    }

    @Test
    fun `만료 후 자동 재발급`() {
        // 만료된 clock으로 프로바이더 생성
        val pastClock = Clock.fixed(Instant.parse("2026-05-04T00:00:00Z"), ZoneOffset.UTC)
        val futureClockForExpiry =
            Clock.fixed(
                Instant.parse("2026-05-04T00:00:00Z").plusSeconds(KisWsApprovalProvider.TTL_SECONDS + 10),
                ZoneOffset.UTC,
            )

        // 첫 발급 → 고정 clock으로 캐시
        val expiredProvider =
            KisWsApprovalProvider(
                properties = KisProperties(appKey = "K", appSecret = "S"),
                httpSession = httpSession,
                clock = pastClock,
            )
        wm.stubFor(
            post(urlEqualTo("/oauth2/Approval"))
                .willReturn(okJson("""{"approval_key":"ORIGINAL"}""")),
        )
        expiredProvider.getApprovalKey() shouldBe "ORIGINAL"

        // 만료된 clock 으로 새 provider (같은 httpSession)
        wm.stubFor(
            post(urlEqualTo("/oauth2/Approval"))
                .willReturn(okJson("""{"approval_key":"REFRESHED"}""")),
        )
        val refreshedProvider =
            KisWsApprovalProvider(
                properties = KisProperties(appKey = "K", appSecret = "S"),
                httpSession = httpSession,
                clock = futureClockForExpiry,
            )
        refreshedProvider.getApprovalKey() shouldBe "REFRESHED"
    }

    @Test
    fun `서버 오류 시 예외 throw`() {
        wm.stubFor(
            post(urlEqualTo("/oauth2/Approval"))
                .willReturn(serverError()),
        )

        shouldThrow<Exception> {
            provider.getApprovalKey()
        }
    }

    @Test
    fun `appKey 미설정 시 IllegalStateException`() {
        val noKeyProvider =
            KisWsApprovalProvider(
                properties = KisProperties(appKey = null, appSecret = null),
                httpSession = httpSession,
                clock = fixedClock,
            )

        shouldThrow<IllegalArgumentException> {
            noKeyProvider.getApprovalKey()
        }
    }

    @Test
    fun `approval_key 가 blank 이면 에러`() {
        wm.stubFor(
            post(urlEqualTo("/oauth2/Approval"))
                .willReturn(okJson("""{"approval_key":""}""")),
        )

        shouldThrow<Exception> {
            provider.getApprovalKey()
        }
    }
}
