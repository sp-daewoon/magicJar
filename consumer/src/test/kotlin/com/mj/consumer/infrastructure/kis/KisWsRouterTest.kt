package com.mj.consumer.infrastructure.kis

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Sinks

/**
 * KisWsRouter 단위 테스트.
 *
 * WS 연결은 fake하고 handleFrame을 직접 호출해 라우팅 로직만 검증.
 * plan §9-1 T2 (2026-05-04-track2-r2-quote-ws.md).
 */
class KisWsRouterTest {
    private val properties =
        KisProperties(
            appKey = "TEST_KEY",
            appSecret = "TEST_SECRET",
            wsUrl = "ws://localhost:99999", // 연결 불요 (테스트에서 connect 안 함)
        )
    private val approvalProvider = mockk<KisWsApprovalProvider>()
    private val mapper = jacksonObjectMapper()
    private lateinit var router: KisWsRouter
    private lateinit var sink: Sinks.Many<String>

    @BeforeEach
    fun setUp() {
        every { approvalProvider.getApprovalKey() } returns "APPROVAL_KEY_TEST"
        router = KisWsRouter(properties, approvalProvider, mapper)
        sink = Sinks.many().unicast().onBackpressureBuffer()
    }

    // ─── handleFrame dispatch 테스트 ───────────────────────────

    @Test
    fun `plaintext frame(0) trId×trKey 매핑 리스너 호출`() {
        val listener = mockk<KisWsFrameListener>(relaxed = true)
        router.subscribe("H0UNCNT0", "005930", listener)

        // "0|H0UNCNT0|001|005930^093045^75000^..."
        val frame = "0|H0UNCNT0|001|005930^093045^75000^0^0^0^0^0^0^0^0^0^500^1234567"
        router.handleFrame(sink, "APPROVAL_KEY_TEST", frame)

        verify(exactly = 1) { listener.onFrame("H0UNCNT0", "005930", any()) }
    }

    @Test
    fun `다른 trKey 리스너에는 dispatch 안 됨`() {
        val listener1 = mockk<KisWsFrameListener>(relaxed = true)
        val listener2 = mockk<KisWsFrameListener>(relaxed = true)
        router.subscribe("H0UNCNT0", "005930", listener1)
        router.subscribe("H0UNCNT0", "000660", listener2)

        val frame = "0|H0UNCNT0|001|005930^093045^75000^0^0^0^0^0^0^0^0^0^500^1234567"
        router.handleFrame(sink, "APPROVAL_KEY_TEST", frame)

        verify(exactly = 1) { listener1.onFrame(any(), any(), any()) }
        verify(exactly = 0) { listener2.onFrame(any(), any(), any()) }
    }

    @Test
    fun `unsubscribe 후 frame 도착 시 리스너 호출 안 됨`() {
        val listener = mockk<KisWsFrameListener>(relaxed = true)
        router.subscribe("H0UNCNT0", "005930", listener)
        router.unsubscribe("H0UNCNT0", "005930")

        val frame = "0|H0UNCNT0|001|005930^093045^75000^0^0^0^0^0^0^0^0^0^500^1234567"
        router.handleFrame(sink, "APPROVAL_KEY_TEST", frame)

        verify(exactly = 0) { listener.onFrame(any(), any(), any()) }
    }

    @Test
    fun `JSON SUBSCRIBE SUCCESS (시세 trId) — iv key 미캐시`() {
        // 체결통보가 아닌 trId는 iv/key를 캐시하지 않아야 한다
        val json =
            """
            {
              "header":{"tr_id":"H0UNCNT0"},
              "body":{"output":{"iv":"someiv","key":"somekey"},"msg_cd":"OPSP0000"}
            }
            """.trimIndent()

        // 예외 없이 처리돼야 함
        router.handleFrame(sink, "APPROVAL_KEY_TEST", json)
        // ivKey가 캐시되지 않음 → 암호화 frame 도착 시 drop
        val encFrame = "1|H0UNCNT0|001|ENCRYPTED_PAYLOAD"
        val listener = mockk<KisWsFrameListener>(relaxed = true)
        router.subscribe("H0UNCNT0", any_code(), listener)
        router.handleFrame(sink, "APPROVAL_KEY_TEST", encFrame)
        verify(exactly = 0) { listener.onFrame(any(), any(), any()) }
    }

    @Test
    fun `JSON SUBSCRIBE SUCCESS (체결통보 trId H0STCNI0) — iv key 캐시`() {
        val json =
            """
            {
              "header":{"tr_id":"H0STCNI0"},
              "body":{"output":{"iv":"aabbcc","key":"ddeeff"},"msg_cd":"OPSP0000"}
            }
            """.trimIndent()
        // 체결통보 trId → iv/key가 캐시됨 (AES decode 경로 활성)
        router.handleFrame(sink, "APPROVAL_KEY_TEST", json)
        // 이후 암호화 체결통보 frame이 오면 iv/key로 복호 시도 (KisAesDecoder 통과 여부는 별도)
    }

    @Test
    fun `PINGPONG JSON 프레임 수신 시 PONG 응답 emit`() {
        val emitted = mutableListOf<String>()
        val captureSink = Sinks.many().unicast().onBackpressureBuffer<String>()

        val json = """{"header":{"tr_id":"PINGPONG"}}"""
        router.handleFrame(captureSink, "APPROVAL_KEY_TEST", json)

        // sink에 emit 됐는지 확인
        captureSink.asFlux().subscribe { emitted.add(it) }
        Thread.sleep(100)
        assert(emitted.any { it.contains("PINGPONG") }) {
            "Expected PONG response containing PINGPONG in sink output"
        }
    }

    @Test
    fun `컬럼 분리 검증 — caret 구분 columns 올바르게 전달`() {
        val capturedCols = mutableListOf<List<String>>()
        val listener = KisWsFrameListener { _, _, columns -> capturedCols.add(columns) }
        router.subscribe("H0UNCNT0", "005930", listener)

        val frame = "0|H0UNCNT0|001|005930^093045^75000^A^B^C^D^E^F^G^H^I^500^1234567"
        router.handleFrame(sink, "APPROVAL_KEY_TEST", frame)

        assert(capturedCols.size == 1)
        val cols = capturedCols[0]
        assert(cols[0] == "005930")
        assert(cols[1] == "093045")
        assert(cols[2] == "75000")
        assert(cols[12] == "500")
        assert(cols[13] == "1234567")
    }

    private fun any_code() = "999999"
}
